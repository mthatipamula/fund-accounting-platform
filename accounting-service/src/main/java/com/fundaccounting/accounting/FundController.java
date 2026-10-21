package com.fundaccounting.accounting;

import com.fundaccounting.accounting.AccountingRepository.CapitalRow;
import com.fundaccounting.accounting.AccountingRepository.FundRow;
import com.fundaccounting.accounting.AccountingRepository.Investor;
import com.fundaccounting.common.ApiErrors.ApiException;
import com.fundaccounting.common.AsOf;
import com.fundaccounting.engine.model.Fund;
import com.fundaccounting.engine.model.ShareClass;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Fund set-up, investors and capital activity (subscriptions / redemptions, bitemporal). */
@RestController
@RequestMapping("/api/accounting")
public class FundController {
    private final AccountingRepository repo;
    private final Clock clock;

    public FundController(AccountingRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public record ShareClassIn(@NotBlank String id, @NotBlank String name, @NotNull @DecimalMin("0") BigDecimal managementFeeRate,
                               @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal initialNavPerShare) {}

    public record FundIn(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,40}") String id, @NotBlank String name,
                         @NotBlank @Pattern(regexp = "[A-Z]{3}") String baseCurrency,
                         @NotBlank @Pattern(regexp = "FIFO|AVERAGE") String costMethod, @NotNull LocalDate inceptionDate,
                         @NotEmpty List<@Valid ShareClassIn> shareClasses) {}

    public record AccountInfo(String account, String code, String label, String type, boolean monetary) {}

    /** The chart of accounts used by every fund's general ledger. */
    @GetMapping("/accounts")
    public List<AccountInfo> accounts() {
        return java.util.Arrays.stream(com.fundaccounting.engine.ledger.Account.values())
                .map(a -> new AccountInfo(a.name(), a.code, a.label, a.type.name(), a.monetary)).toList();
    }

    @GetMapping("/funds")
    public List<FundRow> funds() { return repo.funds(); }

    @GetMapping("/funds/{id}")
    public Fund fund(@PathVariable String id) { return repo.fund(id); }

    @PostMapping("/funds")
    @ResponseStatus(HttpStatus.CREATED)
    public Fund createFund(@Valid @RequestBody FundIn in) {
        repo.createFund(new FundRow(in.id(), in.name(), in.baseCurrency(), in.costMethod(), in.inceptionDate()),
                in.shareClasses().stream().map(c -> new ShareClass(c.id(), c.name(), c.managementFeeRate(), c.initialNavPerShare())).toList());
        return repo.fund(in.id());
    }

    public record InvestorIn(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,40}") String id, @NotBlank String name,
                             @Pattern(regexp = "INSTITUTION|INDIVIDUAL|FUND_OF_FUNDS|EMPLOYEE") String type) {}

    @GetMapping("/investors")
    public List<Investor> investors() { return repo.investors(); }

    @PostMapping("/investors")
    @ResponseStatus(HttpStatus.CREATED)
    public Investor createInvestor(@Valid @RequestBody InvestorIn in) {
        Investor inv = new Investor(in.id(), in.name(), in.type() == null ? "INSTITUTION" : in.type());
        repo.createInvestor(inv);
        return inv;
    }

    // ---------------------------------------------------------------- capital activity

    public record CapitalIn(@NotBlank String fundId, @NotBlank String shareClassId, @NotBlank String investorId,
                            @NotBlank @Pattern(regexp = "SUBSCRIPTION|REDEMPTION") String type, @NotNull LocalDate dealingDate,
                            @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
                            @DecimalMin(value = "0", inclusive = false) BigDecimal shares) {}

    public record CapitalRevision(@NotNull Integer expectedVersion, @Valid @NotNull CapitalIn activity) {}

    @GetMapping("/capital")
    public List<CapitalRow> capital(@RequestParam String fundId, @RequestParam(required = false) String knownAt) {
        return repo.capitalAsKnownAt(fundId, AsOf.knownAt(knownAt));
    }

    @PostMapping("/capital")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CapitalRow createCapital(@Valid @RequestBody CapitalIn in, @AuthenticationPrincipal Jwt user) {
        validate(in);
        CapitalRow row = row("CA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), 1, in, false, user);
        repo.insertCapital(row);
        return row;
    }

    @PutMapping("/capital/{id}")
    @Transactional
    public CapitalRow amendCapital(@PathVariable String id, @Valid @RequestBody CapitalRevision rev, @AuthenticationPrincipal Jwt user) {
        CapitalRow current = lockCurrent(id, rev.expectedVersion());
        validate(rev.activity());
        checkOpen(current.fundId(), current.dealingDate());
        CapitalRow next = row(id, current.version() + 1, rev.activity(), false, user);
        repo.supersedeCapital(id, current.version(), next.recordedAt());
        repo.insertCapital(next);
        return next;
    }

    @PostMapping("/capital/{id}/cancel")
    @Transactional
    public CapitalRow cancelCapital(@PathVariable String id, @RequestParam int expectedVersion, @AuthenticationPrincipal Jwt user) {
        CapitalRow c = lockCurrent(id, expectedVersion);
        checkOpen(c.fundId(), c.dealingDate());
        CapitalIn same = new CapitalIn(c.fundId(), c.shareClassId(), c.investorId(), c.type(), c.dealingDate(), c.amount(), c.shares());
        CapitalRow next = row(id, c.version() + 1, same, true, user);
        repo.supersedeCapital(id, c.version(), next.recordedAt());
        repo.insertCapital(next);
        return next;
    }

    private CapitalRow lockCurrent(String id, int expectedVersion) {
        CapitalRow c = repo.currentCapital(id, true).orElseThrow(() -> ApiException.notFound("Capital activity " + id));
        if (c.cancelled()) throw ApiException.conflict("Capital activity " + id + " is cancelled");
        if (c.version() != expectedVersion)
            throw ApiException.conflict("Capital activity " + id + " is at version " + c.version() + ", not " + expectedVersion + "; reload and retry");
        return c;
    }

    private void validate(CapitalIn in) {
        Fund fund = repo.fund(in.fundId());
        if (fund.shareClasses().stream().noneMatch(c -> c.id().equals(in.shareClassId())))
            throw ApiException.badRequest("Share class " + in.shareClassId() + " is not in fund " + in.fundId());
        if (in.dealingDate().isBefore(fund.inceptionDate())) throw ApiException.badRequest("Dealing date is before fund inception");
        if ("SUBSCRIPTION".equals(in.type()) && in.amount() == null) throw ApiException.badRequest("A subscription needs an amount");
        if ("REDEMPTION".equals(in.type()) && in.shares() == null) throw ApiException.badRequest("A redemption needs a number of shares");
        checkOpen(in.fundId(), in.dealingDate());
    }

    private void checkOpen(String fundId, LocalDate date) {
        repo.currentClose(fundId).ifPresent(p -> {
            if (!date.isAfter(p.closedThrough()))
                throw ApiException.conflict("The period through " + p.closedThrough() + " is closed for " + fundId);
        });
    }

    private CapitalRow row(String id, int version, CapitalIn in, boolean cancelled, Jwt user) {
        Instant now = clock.instant();
        return new CapitalRow(id, version, in.fundId(), in.shareClassId(), in.investorId(), in.type(), in.dealingDate(),
                "SUBSCRIPTION".equals(in.type()) ? in.amount() : null, "REDEMPTION".equals(in.type()) ? in.shares() : null,
                now, null, cancelled, username(user));
    }

    static String username(Jwt user) {
        if (user == null) return "system";
        String name = user.getClaimAsString("preferred_username");
        return name != null ? name : user.getSubject();
    }
}

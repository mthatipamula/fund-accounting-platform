package com.fundaccounting.accounting;

import com.fundaccounting.accounting.AccountingRepository.PeriodClose;
import com.fundaccounting.accounting.ValuationService.Valuation;
import com.fundaccounting.common.ApiErrors.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Period close: freezes the books through a date. After a close, reports for those dates show the books exactly as
 * they were at the close, and back-dated trades or capital activity are refused (trade-service learns about the
 * close through the period-events topic). Reopening is an audited admin action.
 */
@RestController
@RequestMapping("/api/accounting/funds/{fundId}/periods")
public class PeriodController {
    private final AccountingRepository repo;
    private final ValuationService valuation;
    private final ObjectMapper json;
    private final Clock clock;

    public PeriodController(AccountingRepository repo, ValuationService valuation, ObjectMapper json, Clock clock) {
        this.repo = repo;
        this.valuation = valuation;
        this.json = json;
        this.clock = clock;
    }

    public record CloseIn(@NotNull LocalDate closeThrough) {}

    @GetMapping
    public List<PeriodClose> history(@PathVariable String fundId) {
        repo.fund(fundId);
        return repo.periodHistory(fundId);
    }

    @PostMapping("/close")
    @PreAuthorize("hasRole('fa-admin')")
    @Transactional
    public PeriodClose close(@PathVariable String fundId, @Valid @RequestBody CloseIn in, @AuthenticationPrincipal Jwt user) {
        repo.fund(fundId);
        repo.currentClose(fundId).ifPresent(p -> {
            if (!in.closeThrough().isAfter(p.closedThrough()))
                throw ApiException.conflict("Already closed through " + p.closedThrough() + "; reopen first to close an earlier date");
        });
        if (in.closeThrough().isAfter(LocalDate.now(clock))) throw ApiException.badRequest("Can't close a future date");
        Instant at = clock.instant();
        Valuation v = valuation.value(fundId, in.closeThrough(), at);
        if (!v.state().exceptions().isEmpty())
            throw ApiException.conflict("Resolve " + v.state().exceptions().size() + " exception(s) before closing: "
                    + v.state().exceptions().getFirst().message());
        repo.insertPeriodClose(fundId, in.closeThrough(), "CLOSE", at, FundController.username(user), v.state().nav());
        publish(fundId, in.closeThrough());
        return repo.periodHistory(fundId).getFirst();
    }

    @PostMapping("/reopen")
    @PreAuthorize("hasRole('fa-admin')")
    @Transactional
    public PeriodClose reopen(@PathVariable String fundId, @AuthenticationPrincipal Jwt user) {
        repo.fund(fundId);
        if (repo.currentClose(fundId).isEmpty()) throw ApiException.conflict("No closed period to reopen");
        repo.insertPeriodClose(fundId, null, "REOPEN", clock.instant(), FundController.username(user), null);
        publish(fundId, null);
        return repo.periodHistory(fundId).getFirst();
    }

    private void publish(String fundId, LocalDate closedThrough) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("fundId", fundId);
        event.put("closedThrough", closedThrough == null ? null : closedThrough.toString());
        repo.outbox(Messaging.PERIOD_TOPIC, fundId, json.writeValueAsString(event));
    }
}

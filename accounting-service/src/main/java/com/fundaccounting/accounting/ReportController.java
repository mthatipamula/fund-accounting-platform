package com.fundaccounting.accounting;

import com.fundaccounting.accounting.ValuationService.Valuation;
import com.fundaccounting.common.AsOf;
import com.fundaccounting.engine.FundState;
import com.fundaccounting.engine.PnlReport;
import com.fundaccounting.engine.ledger.JournalLine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Reports, all "as of" a date and optionally "as known at" a moment.
 * Every response says which knowledge time it used and whether a period close pinned it.
 */
@RestController
@RequestMapping("/api/accounting/funds/{fundId}")
public class ReportController {
    private final ValuationService valuation;

    public ReportController(ValuationService valuation) { this.valuation = valuation; }

    public record Basis(String fundId, LocalDate asOf, Instant knownAt, boolean lockedByClose, LocalDate closedThrough) {
        static Basis of(Valuation v) {
            return new Basis(v.fund().id(), v.state().asOf(), v.knownAt(), v.lockedByClose(), v.closedThrough());
        }
    }

    public record Summary(Basis basis, String baseCurrency, java.math.BigDecimal nav, java.math.BigDecimal totalAssets,
                          java.math.BigDecimal totalLiabilities, List<FundState.ClassNav> shareClasses,
                          List<FundState.CashBalance> cash, int positionCount, List<FundState.Exception> exceptions) {}

    public record Report<T>(Basis basis, T data) {}

    private Valuation run(String fundId, String asOf, String knownAt) {
        return valuation.value(fundId, AsOf.date(asOf), AsOf.knownAtOrNull(knownAt));
    }

    @GetMapping("/summary")
    public Summary summary(@PathVariable String fundId, @RequestParam(required = false) String asOf, @RequestParam(required = false) String knownAt) {
        Valuation v = run(fundId, asOf, knownAt);
        FundState s = v.state();
        return new Summary(Basis.of(v), s.baseCurrency(), s.nav(), s.totalAssets(), s.totalLiabilities(), s.shareClasses(),
                s.cash(), s.positions().size(), s.exceptions());
    }

    @GetMapping("/positions")
    public Report<List<FundState.Position>> positions(@PathVariable String fundId, @RequestParam(required = false) String asOf,
                                                      @RequestParam(required = false) String knownAt) {
        Valuation v = run(fundId, asOf, knownAt);
        return new Report<>(Basis.of(v), v.state().positions());
    }

    @GetMapping("/pnl")
    public Report<PnlReport> pnl(@PathVariable String fundId, @RequestParam String from, @RequestParam(required = false) String asOf,
                                 @RequestParam(required = false) String knownAt) {
        Valuation v = run(fundId, asOf, knownAt);
        return new Report<>(Basis.of(v), PnlReport.of(v.state().journal(), LocalDate.parse(from), v.state().asOf()));
    }

    @GetMapping("/realized")
    public Report<List<FundState.Realized>> realized(@PathVariable String fundId, @RequestParam(required = false) String asOf,
                                                     @RequestParam(required = false) String knownAt) {
        Valuation v = run(fundId, asOf, knownAt);
        return new Report<>(Basis.of(v), v.state().realized());
    }

    @GetMapping("/trial-balance")
    public Report<?> trialBalance(@PathVariable String fundId, @RequestParam(required = false) String asOf,
                                  @RequestParam(required = false) String knownAt) {
        Valuation v = run(fundId, asOf, knownAt);
        return new Report<>(Basis.of(v), v.state().trialBalance());
    }

    /** General ledger detail, optionally filtered by account code and date range. */
    @GetMapping("/journal")
    public Report<List<JournalLine>> journal(@PathVariable String fundId, @RequestParam(required = false) String asOf,
                                             @RequestParam(required = false) String knownAt, @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String account) {
        Valuation v = run(fundId, asOf, knownAt);
        LocalDate start = from == null ? null : LocalDate.parse(from);
        return new Report<>(Basis.of(v), v.state().journal().stream()
                .filter(l -> start == null || !l.date().isBefore(start))
                .filter(l -> account == null || l.account().code.equals(account)).toList());
    }

    @GetMapping("/nav-history")
    public Report<List<FundState.NavPoint>> navHistory(@PathVariable String fundId, @RequestParam(required = false) String asOf,
                                                       @RequestParam(required = false) String knownAt) {
        Valuation v = run(fundId, asOf, knownAt);
        return new Report<>(Basis.of(v), v.state().navHistory());
    }

    @GetMapping("/investors")
    public Report<List<FundState.InvestorHolding>> investorHoldings(@PathVariable String fundId, @RequestParam(required = false) String asOf,
                                                                    @RequestParam(required = false) String knownAt) {
        Valuation v = run(fundId, asOf, knownAt);
        return new Report<>(Basis.of(v), v.state().investors());
    }
}

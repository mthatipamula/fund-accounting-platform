package com.fundaccounting.engine;

import com.fundaccounting.engine.ledger.JournalLine;
import com.fundaccounting.engine.ledger.Ledger;
import com.fundaccounting.engine.model.AssetClass;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Everything known about a fund at the end of the as-of date. Amounts are in the base currency unless named local. */
public record FundState(
        String fundId,
        String baseCurrency,
        LocalDate asOf,
        BigDecimal nav,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        List<Position> positions,
        List<CashBalance> cash,
        List<ClassNav> shareClasses,
        List<InvestorHolding> investors,
        List<Realized> realized,
        List<NavPoint> navHistory,
        List<JournalLine> journal,
        List<Ledger.TrialBalanceRow> trialBalance,
        List<Exception> exceptions) {

    public record Position(String securityId, String symbol, String name, AssetClass assetClass, String currency,
                           BigDecimal quantity, BigDecimal price, BigDecimal fxRate, BigDecimal costLocal,
                           BigDecimal costBase, BigDecimal marketValueLocal, BigDecimal marketValueBase,
                           BigDecimal unrealizedBase, BigDecimal unrealizedPriceBase, BigDecimal unrealizedFxBase,
                           BigDecimal accruedInterestLocal, BigDecimal accruedInterestBase, BigDecimal weight) {}

    public record CashBalance(String currency, BigDecimal local, BigDecimal base) {}

    public record ClassNav(String shareClassId, String name, BigDecimal nav, BigDecimal shares, BigDecimal navPerShare,
                           BigDecimal managementFeeRate) {}

    public record InvestorHolding(String investorId, String shareClassId, BigDecimal shares, BigDecimal value) {}

    /** A realized gain or loss from a sale or maturity, split into the price move and the currency move. */
    public record Realized(LocalDate date, String securityId, String tradeId, BigDecimal quantity,
                           BigDecimal proceedsLocal, BigDecimal costLocal, BigDecimal proceedsBase, BigDecimal costBase,
                           BigDecimal gainBase, BigDecimal priceComponentBase, BigDecimal fxComponentBase) {}

    public record NavPoint(LocalDate date, BigDecimal nav, Map<String, BigDecimal> navPerShare) {}

    /** Something the books could not process normally (missing price, oversell, …). Never silently ignored. */
    public record Exception(LocalDate date, String severity, String message) {}
}

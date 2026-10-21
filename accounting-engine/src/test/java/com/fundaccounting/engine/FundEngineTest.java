package com.fundaccounting.engine;

import com.fundaccounting.engine.ledger.Account;
import com.fundaccounting.engine.ledger.JournalLine;
import com.fundaccounting.engine.model.Bitemporal;
import com.fundaccounting.engine.model.CapitalActivity;
import com.fundaccounting.engine.model.CorporateAction;
import com.fundaccounting.engine.model.CostMethod;
import com.fundaccounting.engine.model.DayCount;
import com.fundaccounting.engine.model.Fund;
import com.fundaccounting.engine.model.Security;
import com.fundaccounting.engine.model.ShareClass;
import com.fundaccounting.engine.model.Side;
import com.fundaccounting.engine.model.Trade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Scenario tests with hand-computed expected values. */
class FundEngineTest {
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    static final LocalDate JAN1 = LocalDate.of(2026, 1, 1);
    static final ShareClass ZERO_FEE = new ShareClass("A", "Class A", BigDecimal.ZERO, new BigDecimal("100"));
    static final Security AAPL = Security.equity("AAPL", "AAPL", "Apple Inc.", "USD");
    static final Security SAP = Security.equity("SAP", "SAP", "SAP SE", "EUR");

    static LocalDate jan(int day) { return LocalDate.of(2026, 1, day); }

    static Fund fund(CostMethod method, ShareClass... classes) {
        return new Fund("F1", "Test Fund", "USD", method, JAN1, List.of(classes));
    }

    static CapitalActivity subscribe(String id, String cls, String investor, LocalDate d, String amount) {
        return new CapitalActivity(id, 1, "F1", cls, investor, CapitalActivity.Type.SUBSCRIPTION, d, new BigDecimal(amount), null, T0, null, false);
    }

    static CapitalActivity redeem(String id, String cls, String investor, LocalDate d, String shares) {
        return new CapitalActivity(id, 1, "F1", cls, investor, CapitalActivity.Type.REDEMPTION, d, null, new BigDecimal(shares), T0, null, false);
    }

    static Trade trade(String id, Security s, Side side, String qty, String price, String comm, LocalDate td, LocalDate sd) {
        return Trade.of(id, "F1", s.id(), side, qty, price, comm, td, sd, T0);
    }

    static void assertBooksBalance(FundState st) {
        assertThat(st.exceptions()).as("exceptions").isEmpty();
        BigDecimal dr = st.journal().stream().map(JournalLine::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cr = st.journal().stream().map(JournalLine::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(dr).isEqualByComparingTo(cr);
        BigDecimal classes = st.shareClasses().stream().map(FundState.ClassNav::nav).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(st.nav()).isEqualByComparingTo(classes);
        assertThat(st.nav()).isEqualByComparingTo(st.totalAssets().subtract(st.totalLiabilities()));
    }

    static FundState equityScenario(CostMethod method) {
        var md = new InMemoryMarketData().price("AAPL", jan(2), "150").price("AAPL", jan(3), "160").price("AAPL", jan(5), "170");
        var trades = List.of(
                trade("T1", AAPL, Side.BUY, "1000", "150", "10", jan(2), jan(4)),
                trade("T2", AAPL, Side.BUY, "1000", "160", "0", jan(3), jan(5)),
                trade("T3", AAPL, Side.SELL, "1500", "170", "15", jan(5), jan(7)));
        return FundEngine.run(fund(method, ZERO_FEE), Map.of("AAPL", AAPL), trades, List.of(),
                List.of(subscribe("S1", "A", "INV1", JAN1, "1000000")), md, jan(7));
    }

    @Test
    void fifo_relieves_oldest_lots_first() {
        FundState st = equityScenario(CostMethod.FIFO);
        assertBooksBalance(st);
        FundState.Realized r = st.realized().getFirst();
        assertThat(r.proceedsBase()).isEqualByComparingTo("254985");          // 1500 × 170 − 15
        assertThat(r.costBase()).isEqualByComparingTo("230010");              // 150,010 + half of 160,000
        assertThat(r.gainBase()).isEqualByComparingTo("24975");
        FundState.Position p = st.positions().getFirst();
        assertThat(p.quantity()).isEqualByComparingTo("500");
        assertThat(p.costBase()).isEqualByComparingTo("80000");
        assertThat(p.unrealizedBase()).isEqualByComparingTo("5000");          // 500 × 170 − 80,000
        assertThat(st.nav()).isEqualByComparingTo("1029975");
        assertThat(st.cash().getFirst().base()).isEqualByComparingTo("944975"); // all trades settled
        assertThat(st.shareClasses().getFirst().navPerShare()).isEqualByComparingTo("102.9975");
    }

    @Test
    void average_cost_gives_the_same_total_pnl_with_a_different_split() {
        FundState st = equityScenario(CostMethod.AVERAGE);
        assertBooksBalance(st);
        assertThat(st.realized().getFirst().gainBase()).isEqualByComparingTo("22477.5");   // 254,985 − 1500 × 155.005
        assertThat(st.positions().getFirst().unrealizedBase()).isEqualByComparingTo("7497.5");
        assertThat(st.nav()).isEqualByComparingTo("1029975");
    }

    @Test
    void trades_settle_through_payables_and_receivables() {
        // As of Jan 5 the Jan 3 buy settles today but the Jan 5 sale is still a receivable.
        var md = new InMemoryMarketData().price("AAPL", jan(2), "150").price("AAPL", jan(3), "160").price("AAPL", jan(5), "170");
        var trades = List.of(trade("T1", AAPL, Side.BUY, "1000", "150", "10", jan(2), jan(4)),
                trade("T2", AAPL, Side.BUY, "1000", "160", "0", jan(3), jan(5)),
                trade("T3", AAPL, Side.SELL, "1500", "170", "15", jan(5), jan(7)));
        FundState st = FundEngine.run(fund(CostMethod.FIFO, ZERO_FEE), Map.of("AAPL", AAPL), trades, List.of(),
                List.of(subscribe("S1", "A", "INV1", JAN1, "1000000")), md, jan(5));
        assertBooksBalance(st);
        BigDecimal receivable = st.trialBalance().stream().filter(r -> r.account() == Account.RECEIVABLE_SECURITIES_SOLD)
                .findFirst().orElseThrow().debit();
        assertThat(receivable).isEqualByComparingTo("254985");
        assertThat(st.cash().getFirst().base()).isEqualByComparingTo("689990");  // 1,000,000 − 150,010 − 160,000
    }

    @Test
    void foreign_currency_pnl_splits_into_price_and_fx() {
        var md = new InMemoryMarketData().price("SAP", jan(2), "100").price("SAP", jan(5), "105")
                .fx("EUR", jan(2), "1.10").fx("EUR", jan(5), "1.20");
        FundState held = FundEngine.run(fund(CostMethod.FIFO, ZERO_FEE), Map.of("SAP", SAP),
                List.of(trade("T1", SAP, Side.BUY, "1000", "100", "0", jan(2), jan(2))), List.of(),
                List.of(subscribe("S1", "A", "INV1", JAN1, "1000000")), md, jan(5));
        assertBooksBalance(held);
        FundState.Position p = held.positions().getFirst();
        assertThat(p.costBase()).isEqualByComparingTo("110000");
        assertThat(p.marketValueBase()).isEqualByComparingTo("126000");
        assertThat(p.unrealizedPriceBase()).isEqualByComparingTo("6000");     // (105,000 − 100,000) × 1.20
        assertThat(p.unrealizedFxBase()).isEqualByComparingTo("10000");       // 100,000 × (1.20 − 1.10)
        // The EUR cash used to pay is now −100,000 EUR worth −120,000 USD: a 10,000 FX loss on cash.
        assertThat(held.nav()).isEqualByComparingTo("1006000");

        FundState sold = FundEngine.run(fund(CostMethod.FIFO, ZERO_FEE), Map.of("SAP", SAP),
                List.of(trade("T1", SAP, Side.BUY, "1000", "100", "0", jan(2), jan(2)),
                        trade("T2", SAP, Side.SELL, "1000", "105", "0", jan(5), jan(5))), List.of(),
                List.of(subscribe("S1", "A", "INV1", JAN1, "1000000")), md, jan(5));
        assertBooksBalance(sold);
        FundState.Realized r = sold.realized().getFirst();
        assertThat(r.gainBase()).isEqualByComparingTo("16000");
        assertThat(r.priceComponentBase()).isEqualByComparingTo("6000");
        assertThat(r.fxComponentBase()).isEqualByComparingTo("10000");
        assertThat(sold.nav()).isEqualByComparingTo("1006000");
        assertThat(sold.positions()).isEmpty();
    }

    @Test
    void split_adjusts_lots_and_dividend_is_receivable_until_paid() {
        Security xyz = Security.equity("XYZ", "XYZ", "XYZ Corp", "USD");
        var md = new InMemoryMarketData().price("XYZ", jan(2), "50").price("XYZ", jan(3), "25");
        var actions = List.of(
                new CorporateAction("CA1", 1, "XYZ", CorporateAction.Type.SPLIT, jan(3), jan(3), null, new BigDecimal("2"), T0, null, false),
                new CorporateAction("CA2", 1, "XYZ", CorporateAction.Type.CASH_DIVIDEND, jan(4), jan(10), new BigDecimal("0.5"), null, T0, null, false));
        var trades = List.of(trade("T1", xyz, Side.BUY, "100", "50", "0", jan(2), jan(2)));
        var subs = List.of(subscribe("S1", "A", "INV1", JAN1, "100000"));

        FundState beforePay = FundEngine.run(fund(CostMethod.FIFO, ZERO_FEE), Map.of("XYZ", xyz), trades, actions, subs, md, jan(9));
        assertBooksBalance(beforePay);
        assertThat(beforePay.positions().getFirst().quantity()).isEqualByComparingTo("200");
        assertThat(beforePay.positions().getFirst().costBase()).isEqualByComparingTo("5000");
        assertThat(beforePay.positions().getFirst().unrealizedBase()).isEqualByComparingTo("0");
        assertThat(beforePay.trialBalance().stream().filter(r -> r.account() == Account.DIVIDENDS_RECEIVABLE)
                .findFirst().orElseThrow().debit()).isEqualByComparingTo("100");

        FundState paid = FundEngine.run(fund(CostMethod.FIFO, ZERO_FEE), Map.of("XYZ", xyz), trades, actions, subs, md, jan(10));
        assertBooksBalance(paid);
        assertThat(paid.cash().getFirst().base()).isEqualByComparingTo("95100");   // 100,000 − 5,000 + 100
        assertThat(paid.nav()).isEqualByComparingTo("100100");
    }

    @Test
    void bond_accrues_interest_net_of_purchased_interest_and_matures_at_par() {
        Security ust = Security.bond("UST27", "UST 4% 2027", "US Treasury 4% 15-Jan-2027", "USD", new BigDecimal("0.04"), 2,
                LocalDate.of(2025, 1, 15), LocalDate.of(2027, 1, 15), DayCount.THIRTY_360);
        var md = new InMemoryMarketData().price("UST27", LocalDate.of(2026, 3, 15), "99");
        var trades = List.of(trade("T1", ust, Side.BUY, "1000000", "99", "0", LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 15)));
        var subs = List.of(subscribe("S1", "A", "INV1", JAN1, "2000000"));
        Fund f = fund(CostMethod.FIFO, ZERO_FEE);

        FundState afterCoupon = FundEngine.run(f, Map.of("UST27", ust), trades, List.of(), subs, md, LocalDate.of(2026, 7, 15));
        assertBooksBalance(afterCoupon);
        // Purchased interest: 60 days (30/360) × 4% on 1,000,000 = 6,666.67, paid to the seller and recovered in the coupon.
        BigDecimal interestIncome = PnlReport.of(afterCoupon.journal(), JAN1, LocalDate.of(2026, 7, 15)).byAccount().get(Account.INTEREST_INCOME);
        assertThat(interestIncome).isCloseTo(new BigDecimal("13333.33"), within(new BigDecimal("0.01")));   // 120 days
        assertThat(afterCoupon.trialBalance().stream().noneMatch(r -> r.account() == Account.INTEREST_RECEIVABLE)).isTrue();

        FundState matured = FundEngine.run(f, Map.of("UST27", ust), trades, List.of(), subs, md, LocalDate.of(2027, 1, 15));
        assertBooksBalance(matured);
        assertThat(matured.positions()).isEmpty();
        assertThat(matured.realized().getFirst().gainBase()).isEqualByComparingTo("10000");    // redeemed at par, bought at 99
        // 2,000,000 − 990,000 − 6,666.67 + 20,000 + 20,000 + 1,000,000
        assertThat(matured.nav()).isCloseTo(new BigDecimal("2043333.33"), within(new BigDecimal("0.01")));
    }

    @Test
    void share_classes_accrue_their_own_fees_and_deal_at_nav_per_share() {
        ShareClass a = new ShareClass("A", "Class A", new BigDecimal("0.01"), new BigDecimal("100"));
        ShareClass i = new ShareClass("I", "Class I", BigDecimal.ZERO, new BigDecimal("100"));
        var capital = List.of(subscribe("S1", "A", "INV1", JAN1, "1000000"), subscribe("S2", "I", "INV2", JAN1, "1000000"),
                redeem("R1", "I", "INV2", jan(10), "5000"));
        FundState st = FundEngine.run(fund(CostMethod.FIFO, a, i), Map.of(), List.of(), List.of(), capital,
                new InMemoryMarketData(), LocalDate.of(2026, 2, 1));
        assertBooksBalance(st);
        FundState.ClassNav classA = st.shareClasses().get(0), classI = st.shareClasses().get(1);
        // Class A: 1% a year accrued daily on its own NAV for 31 days.
        double expectedA = 100 * Math.pow(1 - 0.01 / 365, 31);
        assertThat(classA.navPerShare().doubleValue()).isCloseTo(expectedA, within(1e-6));
        assertThat(classI.navPerShare()).isEqualByComparingTo("100");
        assertThat(classI.shares()).isEqualByComparingTo("5000");
        // January fees were paid in cash on Jan 31, so nothing is owed on Feb 1 except Feb 1's accrual.
        BigDecimal feesPaid = st.journal().stream().filter(l -> "FEE_PAYMENT".equals(l.sourceType()) && l.account() == Account.CASH)
                .map(JournalLine::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Fees start the day after the first subscription: Jan 2 to Jan 31 is 30 daily accruals.
        assertThat(feesPaid.doubleValue()).isCloseTo(1_000_000 * (1 - Math.pow(1 - 0.01 / 365, 30)), within(1e-4));
        assertThat(st.investors()).extracting(FundState.InvestorHolding::investorId).containsExactlyInAnyOrder("INV1", "INV2");
    }

    @Test
    void corrections_are_bitemporal_reports_can_be_reproduced_as_known_earlier() {
        Instant firstBooked = Instant.parse("2026-01-02T15:00:00Z"), corrected = Instant.parse("2026-01-03T09:00:00Z");
        Trade original = new Trade("T1", 1, "F1", "AAPL", Side.BUY, new BigDecimal("1000"), new BigDecimal("150"),
                BigDecimal.ZERO, jan(2), jan(2), firstBooked, corrected, false);
        Trade amended = new Trade("T1", 2, "F1", "AAPL", Side.BUY, new BigDecimal("800"), new BigDecimal("150"),
                BigDecimal.ZERO, jan(2), jan(2), corrected, null, false);
        List<Trade> history = List.of(original, amended);
        var md = new InMemoryMarketData().price("AAPL", jan(2), "150").price("AAPL", jan(3), "155");
        var subs = List.of(subscribe("S1", "A", "INV1", JAN1, "1000000"));
        Fund f = fund(CostMethod.FIFO, ZERO_FEE);

        FundState asKnownBefore = FundEngine.run(f, Map.of("AAPL", AAPL), Bitemporal.asKnownAt(history, firstBooked.plusSeconds(60)),
                List.of(), subs, md, jan(2));
        FundState asKnownNow = FundEngine.run(f, Map.of("AAPL", AAPL), Bitemporal.asKnownAt(history, corrected.plusSeconds(60)),
                List.of(), subs, md, jan(2));
        assertThat(asKnownBefore.positions().getFirst().quantity()).isEqualByComparingTo("1000");
        assertThat(asKnownNow.positions().getFirst().quantity()).isEqualByComparingTo("800");
    }

    @Test
    void overselling_is_reported_and_not_booked() {
        var md = new InMemoryMarketData().price("AAPL", jan(2), "150");
        FundState st = FundEngine.run(fund(CostMethod.FIFO, ZERO_FEE), Map.of("AAPL", AAPL),
                List.of(trade("T1", AAPL, Side.BUY, "100", "150", "0", jan(2), jan(2)),
                        trade("T2", AAPL, Side.SELL, "500", "150", "0", jan(3), jan(3))),
                List.of(), List.of(subscribe("S1", "A", "INV1", JAN1, "100000")), md, jan(3));
        assertThat(st.exceptions()).singleElement().satisfies(e -> assertThat(e.message()).contains("exceeds position"));
        assertThat(st.positions().getFirst().quantity()).isEqualByComparingTo("100");
    }
}

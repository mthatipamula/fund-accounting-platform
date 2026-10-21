package com.fundaccounting.engine;

import com.fundaccounting.engine.ledger.Account;
import com.fundaccounting.engine.ledger.Ledger;
import com.fundaccounting.engine.ledger.Ledger.Post;
import com.fundaccounting.engine.model.CapitalActivity;
import com.fundaccounting.engine.model.CorporateAction;
import com.fundaccounting.engine.model.CostMethod;
import com.fundaccounting.engine.model.Fund;
import com.fundaccounting.engine.model.Security;
import com.fundaccounting.engine.model.ShareClass;
import com.fundaccounting.engine.model.Side;
import com.fundaccounting.engine.model.Trade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.fundaccounting.engine.Money.div;
import static com.fundaccounting.engine.Money.mul;

/**
 * Replays a fund's events day by day from inception to the as-of date and books everything to a double-entry ledger.
 *
 * <p>Each day, in order: revalue foreign-currency balances; corporate actions (splits, dividend entitlements);
 * payments (dividends, coupons, maturities); trades; settlements; end-of-day interest accrual and mark-to-market;
 * allocation of the day's P&L to share classes; management fees; investor subscriptions and redemptions at the day's
 * NAV per share; month-end fee payment. After each day the books are checked: ledger net assets must equal the sum of
 * share-class NAVs, and investment cost must equal the cost of open lots.
 *
 * <p>The caller passes only the event versions known at the chosen moment (see {@code Bitemporal.asKnownAt}), so the
 * same engine produces "as of" and "as known at" reports.
 */
public final class FundEngine {
    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);
    private static final BigDecimal PAR = BigDecimal.valueOf(100);

    private final Fund fund;
    private final Map<String, Security> securities;
    private final MarketData market;
    private final String base;
    private final Ledger ledger;

    private final Map<String, Deque<Lot>> lots = new LinkedHashMap<>();
    private final Map<String, BigDecimal> accruedBook = new HashMap<>();   // interest receivable per bond, local
    private final Map<String, BigDecimal> unrealizedBook = new HashMap<>(); // unrealized appreciation per security, base
    private final Map<String, BigDecimal> lastPrice = new HashMap<>();
    private final Map<String, BigDecimal> lastFx = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private final Map<String, ClassState> classes = new LinkedHashMap<>();
    private final Map<String, BigDecimal> investorShares = new LinkedHashMap<>();  // "classId|investorId" -> shares
    private final List<FundState.Realized> realized = new ArrayList<>();
    private final List<FundState.NavPoint> navHistory = new ArrayList<>();
    private final List<FundState.Exception> exceptions = new ArrayList<>();
    private BigDecimal previousNav = BigDecimal.ZERO;

    private static final class Lot {
        final String tradeId;
        final LocalDate opened;
        BigDecimal quantity, costLocal, costBase;

        Lot(String tradeId, LocalDate opened, BigDecimal quantity, BigDecimal costLocal, BigDecimal costBase) {
            this.tradeId = tradeId;
            this.opened = opened;
            this.quantity = quantity;
            this.costLocal = costLocal;
            this.costBase = costBase;
        }
    }

    private static final class ClassState {
        final ShareClass shareClass;
        BigDecimal nav = BigDecimal.ZERO, shares = BigDecimal.ZERO;

        ClassState(ShareClass shareClass) { this.shareClass = shareClass; }

        BigDecimal navPerShare() {
            return shares.signum() == 0 ? shareClass.initialNavPerShare() : div(nav, shares);
        }
    }

    /** A payable/receivable or income amount waiting for its settlement or payment date. */
    private record Pending(LocalDate date, Account account, String currency, BigDecimal local, String securityId, String sourceId) {}

    private FundEngine(Fund fund, Map<String, Security> securities, MarketData market) {
        this.fund = fund;
        this.securities = securities;
        this.market = market;
        this.base = fund.baseCurrency();
        this.ledger = new Ledger(base);
        for (ShareClass sc : fund.shareClasses()) classes.put(sc.id(), new ClassState(sc));
    }

    public static FundState run(Fund fund, Map<String, Security> securities, List<Trade> trades,
                                List<CorporateAction> actions, List<CapitalActivity> capital, MarketData market,
                                LocalDate asOf) {
        return new FundEngine(fund, securities, market).replay(trades, actions, capital, asOf);
    }

    private FundState replay(List<Trade> trades, List<CorporateAction> actions, List<CapitalActivity> capital, LocalDate asOf) {
        Map<LocalDate, List<Trade>> tradesByDate = group(trades.stream()
                .sorted(Comparator.comparing(Trade::recordedAt).thenComparing(Trade::id)).toList(), Trade::tradeDate);
        Map<LocalDate, List<CorporateAction>> actionsByDate = group(actions, CorporateAction::exDate);
        Map<LocalDate, List<CapitalActivity>> capitalByDate = group(capital.stream()
                .sorted(Comparator.comparing(CapitalActivity::recordedAt).thenComparing(CapitalActivity::id)).toList(),
                CapitalActivity::dealingDate);

        for (LocalDate d = fund.inceptionDate(); !d.isAfter(asOf); d = d.plusDays(1)) {
            Map<String, BigDecimal> fx = fxRates(d);
            ledger.revalue(d, fx);
            for (CorporateAction ca : actionsByDate.getOrDefault(d, List.of())) corporateAction(d, ca, fx);
            payments(d, fx);
            for (Trade t : tradesByDate.getOrDefault(d, List.of())) trade(d, t, fx);
            settle(d, fx);
            accrueInterest(d, fx);
            markToMarket(d, fx);
            allocateAndChargeFees(d);
            for (CapitalActivity c : capitalByDate.getOrDefault(d, List.of())) capital(d, c);
            if (d.plusDays(1).getDayOfMonth() == 1) payFees(d);
            checkBooks(d);
            Map<String, BigDecimal> perShare = new LinkedHashMap<>();
            classes.forEach((id, c) -> perShare.put(id, c.navPerShare()));
            navHistory.add(new FundState.NavPoint(d, previousNav, perShare));
        }
        return snapshot(asOf);
    }

    // ---------------------------------------------------------------- market data

    private Map<String, BigDecimal> fxRates(LocalDate d) {
        Map<String, BigDecimal> fx = new HashMap<>();
        fx.put(base, BigDecimal.ONE);
        for (Security s : securities.values()) {
            if (fx.containsKey(s.currency())) continue;
            var rate = market.fxToBase(s.currency(), base, d);
            if (rate.isPresent()) {
                lastFx.put(s.currency(), rate.get());
                fx.put(s.currency(), rate.get());
            } else if (lastFx.containsKey(s.currency())) {
                fx.put(s.currency(), lastFx.get(s.currency()));
            }
        }
        return fx;
    }

    private BigDecimal fx(Map<String, BigDecimal> fx, String currency, LocalDate d) {
        BigDecimal rate = fx.get(currency);
        if (rate == null) {
            exceptions.add(new FundState.Exception(d, "ERROR", "No FX rate for " + currency + "/" + base + "; using 1"));
            return BigDecimal.ONE;
        }
        return rate;
    }

    private BigDecimal price(Security s, LocalDate d) {
        var p = market.price(s.id(), d);
        if (p.isPresent()) {
            lastPrice.put(s.id(), p.get());
            return p.get();
        }
        BigDecimal fallback = lastPrice.get(s.id());
        if (fallback == null) {
            exceptions.add(new FundState.Exception(d, "WARNING", "No price for " + s.symbol() + "; valued at cost"));
            return null;
        }
        return fallback;
    }

    // ---------------------------------------------------------------- events

    private void corporateAction(LocalDate d, CorporateAction ca, Map<String, BigDecimal> fx) {
        Security s = securities.get(ca.securityId());
        Deque<Lot> open = lots.getOrDefault(ca.securityId(), new ArrayDeque<>());
        BigDecimal held = quantity(ca.securityId());
        switch (ca.type()) {
            case SPLIT -> {
                for (Lot lot : open) lot.quantity = lot.quantity.multiply(ca.ratio());   // cost unchanged
                lastPrice.computeIfPresent(ca.securityId(), (k, p) -> div(p, ca.ratio()));
            }
            case CASH_DIVIDEND -> {
                if (held.signum() <= 0) return;
                BigDecimal local = held.multiply(ca.amountPerShare());
                BigDecimal baseAmt = mul(local, fx(fx, s.currency(), d));
                ledger.post(d, "Dividend " + s.symbol() + " " + ca.amountPerShare() + "/sh on " + held, "CORPORATE_ACTION", ca.id(),
                        List.of(new Post(Account.DIVIDENDS_RECEIVABLE, baseAmt, s.currency(), local, s.id()),
                                new Post(Account.DIVIDEND_INCOME, baseAmt.negate(), null, null, s.id())));
                pending.add(new Pending(ca.payDate(), Account.DIVIDENDS_RECEIVABLE, s.currency(), local, s.id(), ca.id()));
            }
        }
    }

    private void payments(LocalDate d, Map<String, BigDecimal> fx) {
        // Coupons and maturities on bonds held at the start of the day.
        for (String id : List.copyOf(lots.keySet())) {
            Security s = securities.get(id);
            BigDecimal face = quantity(id);
            if (!s.isBond() || face.signum() <= 0) continue;
            BigDecimal rate = fx(fx, s.currency(), d);
            if (s.couponDates().contains(d)) {
                BigDecimal coupon = s.couponAmount(face);
                BigDecimal baseAmt = mul(coupon, rate);
                ledger.post(d, "Coupon " + s.symbol(), "COUPON", id + "@" + d,
                        List.of(new Post(Account.CASH, baseAmt, s.currency(), coupon, id),
                                new Post(Account.INTEREST_RECEIVABLE, baseAmt.negate(), s.currency(), coupon.negate(), id)));
                accruedBook.merge(id, coupon.negate(), BigDecimal::add);
            }
            if (d.equals(s.maturityDate())) {
                sell(d, s, face, PAR, BigDecimal.ZERO, "MATURITY-" + id, rate, true);
            }
        }
    }

    private void trade(LocalDate d, Trade t, Map<String, BigDecimal> fx) {
        Security s = securities.get(t.securityId());
        if (s == null) {
            exceptions.add(new FundState.Exception(d, "ERROR", "Trade " + t.id() + " references unknown security " + t.securityId()));
            return;
        }
        BigDecimal rate = fx(fx, s.currency(), d);
        lastPrice.putIfAbsent(s.id(), t.price());
        if (t.side() == Side.BUY) buy(d, s, t, rate);
        else sell(d, s, t.quantity(), t.price(), t.commission(), t.id(), rate, false, t.settleDate());
    }

    private void buy(LocalDate d, Security s, Trade t, BigDecimal rate) {
        BigDecimal costLocal = s.marketValue(t.quantity(), t.price()).add(t.commission());
        BigDecimal purchasedInterest = s.accruedInterest(t.quantity(), d);
        BigDecimal costBase = mul(costLocal, rate);
        BigDecimal interestBase = mul(purchasedInterest, rate);
        BigDecimal totalLocal = costLocal.add(purchasedInterest);
        ledger.post(d, "Buy " + t.quantity().stripTrailingZeros().toPlainString() + " " + s.symbol() + " @ " + t.price(), "TRADE", t.id(),
                List.of(new Post(Account.INVESTMENTS_AT_COST, costBase, null, null, s.id()),
                        new Post(Account.INTEREST_RECEIVABLE, interestBase, s.currency(), purchasedInterest, s.id()),
                        new Post(Account.PAYABLE_SECURITIES_PURCHASED, costBase.add(interestBase).negate(), s.currency(), totalLocal.negate(), s.id())));
        if (purchasedInterest.signum() != 0) accruedBook.merge(s.id(), purchasedInterest, BigDecimal::add);
        Deque<Lot> open = lots.computeIfAbsent(s.id(), k -> new ArrayDeque<>());
        if (fund.costMethod() == CostMethod.AVERAGE && !open.isEmpty()) {
            Lot avg = open.getFirst();
            avg.quantity = avg.quantity.add(t.quantity());
            avg.costLocal = avg.costLocal.add(costLocal);
            avg.costBase = avg.costBase.add(costBase);
        } else {
            open.addLast(new Lot(t.id(), d, t.quantity(), costLocal, costBase));
        }
        pending.add(new Pending(t.settleDate(), Account.PAYABLE_SECURITIES_PURCHASED, s.currency(), totalLocal.negate(), s.id(), t.id()));
    }

    private void sell(LocalDate d, Security s, BigDecimal qty, BigDecimal price, BigDecimal commission, String sourceId,
                      BigDecimal rate, boolean maturity) {
        sell(d, s, qty, price, commission, sourceId, rate, maturity, d);
    }

    private void sell(LocalDate d, Security s, BigDecimal qty, BigDecimal price, BigDecimal commission, String sourceId,
                      BigDecimal rate, boolean maturity, LocalDate settleDate) {
        BigDecimal held = quantity(s.id());
        if (qty.compareTo(held) > 0) {
            exceptions.add(new FundState.Exception(d, "ERROR", "Sell of " + qty.toPlainString() + " " + s.symbol()
                    + " exceeds position " + held.toPlainString() + "; trade " + sourceId + " not booked"));
            return;
        }
        BigDecimal proceedsLocal = s.marketValue(qty, price).subtract(commission);
        BigDecimal accruedSold = maturity ? BigDecimal.ZERO : s.accruedInterest(qty, d);
        BigDecimal costLocal = BigDecimal.ZERO, costBase = BigDecimal.ZERO, remaining = qty;
        Deque<Lot> open = lots.get(s.id());
        while (remaining.signum() > 0) {
            Lot lot = open.getFirst();
            if (remaining.compareTo(lot.quantity) >= 0) {           // close the whole lot
                costLocal = costLocal.add(lot.costLocal);
                costBase = costBase.add(lot.costBase);
                remaining = remaining.subtract(lot.quantity);
                open.removeFirst();
            } else {                                                // close part of the lot
                BigDecimal partLocal = div(lot.costLocal.multiply(remaining), lot.quantity);
                BigDecimal partBase = div(lot.costBase.multiply(remaining), lot.quantity);
                lot.costLocal = lot.costLocal.subtract(partLocal);
                lot.costBase = lot.costBase.subtract(partBase);
                lot.quantity = lot.quantity.subtract(remaining);
                costLocal = costLocal.add(partLocal);
                costBase = costBase.add(partBase);
                remaining = BigDecimal.ZERO;
            }
        }
        if (open.isEmpty()) lots.remove(s.id());

        BigDecimal proceedsBase = mul(proceedsLocal, rate);
        BigDecimal accruedBase = mul(accruedSold, rate);
        BigDecimal gain = proceedsBase.subtract(costBase);
        String label = (maturity ? "Maturity " : "Sell ") + qty.stripTrailingZeros().toPlainString() + " " + s.symbol()
                + (maturity ? "" : " @ " + price);
        ledger.post(d, label, maturity ? "MATURITY" : "TRADE", sourceId,
                List.of(new Post(Account.RECEIVABLE_SECURITIES_SOLD, proceedsBase.add(accruedBase), s.currency(), proceedsLocal.add(accruedSold), s.id()),
                        new Post(Account.INVESTMENTS_AT_COST, costBase.negate(), null, null, s.id()),
                        new Post(Account.INTEREST_RECEIVABLE, accruedBase.negate(), s.currency(), accruedSold.negate(), s.id()),
                        new Post(Account.REALIZED_GAIN_LOSS, gain.negate(), null, null, s.id())));
        if (accruedSold.signum() != 0) accruedBook.merge(s.id(), accruedSold.negate(), BigDecimal::add);
        realized.add(new FundState.Realized(d, s.id(), sourceId, qty, proceedsLocal, costLocal, proceedsBase, costBase,
                gain, mul(proceedsLocal.subtract(costLocal), rate), mul(costLocal, rate).subtract(costBase)));
        pending.add(new Pending(settleDate, Account.RECEIVABLE_SECURITIES_SOLD, s.currency(), proceedsLocal.add(accruedSold), s.id(), sourceId));
    }

    /** Settles trades and pays dividends due on or before today, through cash at today's rate. */
    private void settle(LocalDate d, Map<String, BigDecimal> fx) {
        for (Pending p : List.copyOf(pending)) {
            if (p.date().isAfter(d)) continue;
            pending.remove(p);
            BigDecimal baseAmt = mul(p.local(), fx(fx, p.currency(), d));
            String what = switch (p.account()) {
                case PAYABLE_SECURITIES_PURCHASED -> "Settle purchase";
                case RECEIVABLE_SECURITIES_SOLD -> "Settle sale";
                default -> "Dividend paid";
            };
            ledger.post(d, what + " " + securities.get(p.securityId()).symbol(), "SETTLEMENT", p.sourceId(),
                    List.of(new Post(Account.CASH, baseAmt, p.currency(), p.local(), p.securityId()),
                            new Post(p.account(), baseAmt.negate(), p.currency(), p.local().negate(), p.securityId())));
        }
    }

    // ---------------------------------------------------------------- end of day

    private void accrueInterest(LocalDate d, Map<String, BigDecimal> fx) {
        // Includes bonds just sold or matured, so the last day's accrual sold with the bond is booked as income.
        List<String> ids = new ArrayList<>(lots.keySet());
        accruedBook.forEach((id, v) -> { if (v.signum() != 0 && !ids.contains(id)) ids.add(id); });
        for (String id : ids) {
            Security s = securities.get(id);
            if (!s.isBond()) continue;
            BigDecimal target = s.accruedInterest(quantity(id), d);
            BigDecimal income = target.subtract(accruedBook.getOrDefault(id, BigDecimal.ZERO));
            if (income.signum() == 0) continue;
            BigDecimal baseAmt = mul(income, fx(fx, s.currency(), d));
            ledger.post(d, "Interest accrual " + s.symbol(), "ACCRUAL", id + "@" + d,
                    List.of(new Post(Account.INTEREST_RECEIVABLE, baseAmt, s.currency(), income, id),
                            new Post(Account.INTEREST_INCOME, baseAmt.negate(), null, null, id)));
            accruedBook.merge(id, income, BigDecimal::add);
        }
    }

    private void markToMarket(LocalDate d, Map<String, BigDecimal> fx) {
        for (String id : new ArrayList<>(unionKeys())) {
            Security s = securities.get(id);
            BigDecimal target = BigDecimal.ZERO;
            if (lots.containsKey(id)) {
                BigDecimal p = price(s, d);
                BigDecimal costBase = cost(id, false);
                target = p == null ? BigDecimal.ZERO : mul(s.marketValue(quantity(id), p), fx(fx, s.currency(), d)).subtract(costBase);
            }
            BigDecimal change = target.subtract(unrealizedBook.getOrDefault(id, BigDecimal.ZERO));
            if (change.signum() == 0) continue;
            ledger.post(d, "Mark to market " + s.symbol(), "VALUATION", id + "@" + d,
                    List.of(new Post(Account.UNREALIZED_APPRECIATION, change, null, null, id),
                            new Post(Account.CHANGE_IN_UNREALIZED, change.negate(), null, null, id)));
            if (target.signum() == 0) unrealizedBook.remove(id); else unrealizedBook.put(id, target);
        }
    }

    private List<String> unionKeys() {
        List<String> keys = new ArrayList<>(lots.keySet());
        for (String k : unrealizedBook.keySet()) if (!keys.contains(k)) keys.add(k);
        return keys;
    }

    /** Splits the day's P&L across share classes in proportion to their NAV, then accrues each class's fee. */
    private void allocateAndChargeFees(LocalDate d) {
        BigDecimal pnl = ledger.netAssets().subtract(previousNav);
        BigDecimal total = classes.values().stream().map(c -> c.nav).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) {
            if (pnl.signum() != 0) {
                exceptions.add(new FundState.Exception(d, "ERROR", "P&L of " + pnl + " with no investor capital"));
            }
            return;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        List<ClassState> funded = classes.values().stream().filter(c -> c.nav.signum() != 0).toList();
        for (int i = 0; i < funded.size(); i++) {
            ClassState c = funded.get(i);
            BigDecimal share = i == funded.size() - 1 ? pnl.subtract(allocated) : div(pnl.multiply(c.nav), total);
            c.nav = c.nav.add(share);
            allocated = allocated.add(share);
        }
        for (ClassState c : funded) {
            BigDecimal fee = div(c.nav.multiply(c.shareClass.managementFeeRate()), DAYS_PER_YEAR);
            if (fee.signum() <= 0) continue;
            ledger.post(d, "Management fee " + c.shareClass.name(), "FEE", c.shareClass.id() + "@" + d,
                    List.of(Post.debit(Account.MANAGEMENT_FEE_EXPENSE, fee), Post.credit(Account.MANAGEMENT_FEES_PAYABLE, fee)));
            c.nav = c.nav.subtract(fee);
        }
        previousNav = sumClassNav();
    }

    private void capital(LocalDate d, CapitalActivity c) {
        ClassState cls = classes.get(c.shareClassId());
        if (cls == null) {
            exceptions.add(new FundState.Exception(d, "ERROR", "Unknown share class " + c.shareClassId()));
            return;
        }
        BigDecimal navps = cls.navPerShare();
        String key = c.shareClassId() + "|" + c.investorId();
        switch (c.type()) {
            case SUBSCRIPTION -> {
                BigDecimal shares = div(c.amount(), navps);
                ledger.post(d, "Subscription " + c.investorId() + " into " + cls.shareClass.name(), "CAPITAL", c.id(),
                        List.of(Post.debit(Account.CASH, c.amount()), Post.credit(Account.CAPITAL_SUBSCRIPTIONS, c.amount())));
                cls.nav = cls.nav.add(c.amount());
                cls.shares = cls.shares.add(shares);
                investorShares.merge(key, shares, BigDecimal::add);
            }
            case REDEMPTION -> {
                BigDecimal owned = investorShares.getOrDefault(key, BigDecimal.ZERO);
                if (c.shares().compareTo(owned) > 0) {
                    exceptions.add(new FundState.Exception(d, "ERROR", "Redemption of " + c.shares() + " shares by "
                            + c.investorId() + " exceeds holding " + owned + "; not processed"));
                    return;
                }
                BigDecimal amount = mul(c.shares(), navps);
                ledger.post(d, "Redemption " + c.investorId() + " from " + cls.shareClass.name(), "CAPITAL", c.id(),
                        List.of(Post.debit(Account.CAPITAL_REDEMPTIONS, amount), Post.credit(Account.CASH, amount)));
                cls.nav = cls.nav.subtract(amount);
                cls.shares = cls.shares.subtract(c.shares());
                investorShares.merge(key, c.shares().negate(), BigDecimal::add);
            }
        }
        previousNav = sumClassNav();
    }

    private void payFees(LocalDate d) {
        BigDecimal owed = ledger.balance(Account.MANAGEMENT_FEES_PAYABLE).negate();
        if (owed.signum() <= 0) return;
        ledger.post(d, "Pay management fees for " + d.getMonth(), "FEE_PAYMENT", "FEES@" + d,
                List.of(Post.debit(Account.MANAGEMENT_FEES_PAYABLE, owed), Post.credit(Account.CASH, owed)));
    }

    /** The books must agree with themselves every day; any difference is reported, never hidden. */
    private void checkBooks(LocalDate d) {
        BigDecimal ledgerNav = ledger.netAssets();
        BigDecimal classNav = sumClassNav();
        if (ledgerNav.subtract(classNav).abs().compareTo(new BigDecimal("0.000001")) > 0) {
            exceptions.add(new FundState.Exception(d, "ERROR", "Ledger NAV " + ledgerNav + " differs from share class NAV " + classNav));
        }
        BigDecimal lotCost = BigDecimal.ZERO;
        for (String id : lots.keySet()) lotCost = lotCost.add(cost(id, false));
        if (ledger.balance(Account.INVESTMENTS_AT_COST).subtract(lotCost).abs().compareTo(new BigDecimal("0.000001")) > 0) {
            exceptions.add(new FundState.Exception(d, "ERROR", "Investments at cost do not match open lots"));
        }
        previousNav = classNav;
    }

    // ---------------------------------------------------------------- helpers

    private BigDecimal sumClassNav() {
        return classes.values().stream().map(c -> c.nav).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal quantity(String securityId) {
        return lots.getOrDefault(securityId, new ArrayDeque<>()).stream().map(l -> l.quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal cost(String securityId, boolean local) {
        return lots.getOrDefault(securityId, new ArrayDeque<>()).stream().map(l -> local ? l.costLocal : l.costBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static <T> Map<LocalDate, List<T>> group(List<T> items, java.util.function.Function<T, LocalDate> date) {
        Map<LocalDate, List<T>> map = new TreeMap<>();
        for (T item : items) map.computeIfAbsent(date.apply(item), k -> new ArrayList<>()).add(item);
        return map;
    }

    // ---------------------------------------------------------------- result

    private FundState snapshot(LocalDate asOf) {
        Map<String, BigDecimal> fx = fxRates(asOf);
        BigDecimal nav = ledger.netAssets();
        List<FundState.Position> positions = new ArrayList<>();
        for (String id : lots.keySet()) {
            Security s = securities.get(id);
            BigDecimal qty = quantity(id);
            BigDecimal rate = fx.getOrDefault(s.currency(), BigDecimal.ONE);
            BigDecimal price = lastPrice.get(id);
            BigDecimal costLocal = cost(id, true), costBase = cost(id, false);
            BigDecimal mvLocal = price == null ? costLocal : s.marketValue(qty, price);
            BigDecimal mvBase = mul(mvLocal, rate);
            BigDecimal accrued = s.isBond() ? s.accruedInterest(qty, asOf) : BigDecimal.ZERO;
            positions.add(new FundState.Position(id, s.symbol(), s.name(), s.assetClass(), s.currency(), qty, price, rate,
                    costLocal, costBase, mvLocal, mvBase, mvBase.subtract(costBase), mul(mvLocal.subtract(costLocal), rate),
                    mul(costLocal, rate).subtract(costBase), accrued, mul(accrued, rate),
                    nav.signum() == 0 ? BigDecimal.ZERO : div(mvBase, nav)));
        }
        List<FundState.CashBalance> cash = new ArrayList<>();
        ledger.monetaryBalances().forEach((k, v) -> {
            if (k.account() == Account.CASH && (v[0].signum() != 0 || v[1].signum() != 0)) cash.add(new FundState.CashBalance(k.currency(), v[0], v[1]));
        });
        cash.sort(Comparator.comparing(FundState.CashBalance::currency));
        List<FundState.ClassNav> classNavs = classes.values().stream()
                .map(c -> new FundState.ClassNav(c.shareClass.id(), c.shareClass.name(), c.nav, c.shares, c.navPerShare(), c.shareClass.managementFeeRate()))
                .toList();
        List<FundState.InvestorHolding> investors = new ArrayList<>();
        investorShares.forEach((key, shares) -> {
            if (shares.signum() == 0) return;
            String[] parts = key.split("\\|", 2);
            investors.add(new FundState.InvestorHolding(parts[1], parts[0], shares, mul(shares, classes.get(parts[0]).navPerShare())));
        });
        BigDecimal assets = BigDecimal.ZERO, liabilities = BigDecimal.ZERO;
        for (Account a : Account.values()) {
            if (a.type == Account.Type.ASSET) assets = assets.add(ledger.balance(a));
            if (a.type == Account.Type.LIABILITY) liabilities = liabilities.add(ledger.balance(a).negate());
        }
        return new FundState(fund.id(), base, asOf, nav, assets, liabilities, positions, cash, classNavs, investors,
                List.copyOf(realized), List.copyOf(navHistory), List.copyOf(ledger.lines()), ledger.trialBalance(), List.copyOf(exceptions));
    }
}

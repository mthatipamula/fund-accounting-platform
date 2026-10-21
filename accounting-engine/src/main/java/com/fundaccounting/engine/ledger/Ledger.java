package com.fundaccounting.engine.ledger;

import com.fundaccounting.engine.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Double-entry ledger in the fund's base currency, tracking foreign-currency balances of monetary accounts. */
public final class Ledger {
    private final String baseCurrency;
    private final List<JournalLine> lines = new ArrayList<>();
    private final Map<Account, BigDecimal> base = new EnumMap<>(Account.class);
    private final Map<Key, BigDecimal[]> monetary = new HashMap<>();   // [local, base] per account and currency
    private long nextEntry = 1;

    public record Key(Account account, String currency) {}

    /** A line to post: base-currency amount (positive = debit) and, for monetary accounts, the local amount. */
    public record Post(Account account, BigDecimal baseAmount, String currency, BigDecimal localAmount, String securityId) {
        public static Post debit(Account a, BigDecimal baseAmount) { return new Post(a, baseAmount, null, null, null); }
        public static Post credit(Account a, BigDecimal baseAmount) { return new Post(a, baseAmount.negate(), null, null, null); }
    }

    public Ledger(String baseCurrency) { this.baseCurrency = baseCurrency; }

    public String baseCurrency() { return baseCurrency; }

    /** Posts a balanced entry. Throws if debits and credits differ. */
    public void post(LocalDate date, String description, String sourceType, String sourceId, List<Post> posts) {
        BigDecimal sum = posts.stream().map(Post::baseAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() != 0) {
            throw new IllegalStateException("Unbalanced entry '" + description + "': off by " + sum);
        }
        long id = nextEntry++;
        for (Post p : posts) {
            if (p.baseAmount().signum() == 0 && (p.localAmount() == null || p.localAmount().signum() == 0)) continue;
            String ccy = p.currency() != null ? p.currency() : baseCurrency;
            BigDecimal local = p.localAmount() != null ? p.localAmount() : p.baseAmount();
            BigDecimal amt = p.baseAmount();
            lines.add(new JournalLine(id, date, p.account(), ccy, p.securityId(),
                    amt.signum() > 0 ? amt : BigDecimal.ZERO, amt.signum() < 0 ? amt.negate() : BigDecimal.ZERO,
                    local, description, sourceType, sourceId));
            base.merge(p.account(), amt, BigDecimal::add);
            if (p.account().monetary) {
                BigDecimal[] bal = monetary.computeIfAbsent(new Key(p.account(), ccy), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                bal[0] = bal[0].add(local);
                bal[1] = bal[1].add(amt);
            }
        }
    }

    /** Signed base balance (debit positive). */
    public BigDecimal balance(Account a) { return base.getOrDefault(a, BigDecimal.ZERO); }

    /** Local-currency balance of a monetary account in one currency (debit positive). */
    public BigDecimal localBalance(Account a, String currency) {
        BigDecimal[] bal = monetary.get(new Key(a, currency));
        return bal == null ? BigDecimal.ZERO : bal[0];
    }

    public BigDecimal baseBalance(Account a, String currency) {
        BigDecimal[] bal = monetary.get(new Key(a, currency));
        return bal == null ? BigDecimal.ZERO : bal[1];
    }

    public Map<Key, BigDecimal[]> monetaryBalances() { return monetary; }

    public List<JournalLine> lines() { return lines; }

    /** Assets minus liabilities: the fund's net asset value per the books. */
    public BigDecimal netAssets() {
        BigDecimal nav = BigDecimal.ZERO;
        for (var e : base.entrySet()) {
            if (e.getKey().type == Account.Type.ASSET || e.getKey().type == Account.Type.LIABILITY) nav = nav.add(e.getValue());
        }
        return nav;  // liabilities carry credit (negative) balances
    }

    /** Revalues every foreign-currency monetary balance to today's rate; the difference is an FX gain or loss. */
    public void revalue(LocalDate date, Map<String, BigDecimal> fxToBase) {
        for (var e : List.copyOf(monetary.entrySet())) {
            Key k = e.getKey();
            if (k.currency().equals(baseCurrency)) continue;
            BigDecimal rate = fxToBase.get(k.currency());
            if (rate == null) continue;
            BigDecimal target = Money.mul(e.getValue()[0], rate);
            BigDecimal diff = target.subtract(e.getValue()[1]);
            if (diff.signum() == 0) continue;
            post(date, "FX revaluation " + k.currency() + " " + k.account().label, "FX_REVALUATION", k.account().code + "-" + k.currency(),
                    List.of(new Post(k.account(), diff, k.currency(), BigDecimal.ZERO, null),
                            Post.credit(Account.FX_GAIN_LOSS, diff)));
        }
    }

    public record TrialBalanceRow(Account account, BigDecimal debit, BigDecimal credit) {}

    public List<TrialBalanceRow> trialBalance() {
        List<TrialBalanceRow> rows = new ArrayList<>();
        for (Account a : Account.values()) {
            BigDecimal b = balance(a);
            if (b.signum() == 0) continue;
            rows.add(new TrialBalanceRow(a, b.signum() > 0 ? b : BigDecimal.ZERO, b.signum() < 0 ? b.negate() : BigDecimal.ZERO));
        }
        return rows;
    }
}

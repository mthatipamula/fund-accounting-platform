package com.fundaccounting.engine;

import com.fundaccounting.engine.ledger.Account;
import com.fundaccounting.engine.ledger.JournalLine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P&L for a date range, built from the general ledger: totals by income/expense account and an attribution by
 * security (realized + change in unrealized + income). Gains are positive.
 */
public record PnlReport(LocalDate from, LocalDate to, Map<Account, BigDecimal> byAccount,
                        Map<String, SecurityPnl> bySecurity, BigDecimal total) {

    public record SecurityPnl(String securityId, BigDecimal realized, BigDecimal unrealizedChange, BigDecimal income, BigDecimal total) {}

    public static PnlReport of(List<JournalLine> journal, LocalDate from, LocalDate to) {
        Map<Account, BigDecimal> byAccount = new EnumMap<>(Account.class);
        Map<String, BigDecimal[]> sec = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (JournalLine l : journal) {
            if (l.date().isBefore(from) || l.date().isAfter(to)) continue;
            Account a = l.account();
            if (a.type != Account.Type.INCOME && a.type != Account.Type.EXPENSE) continue;
            BigDecimal gain = l.credit().subtract(l.debit());          // income is credit-normal
            byAccount.merge(a, gain, BigDecimal::add);
            total = total.add(gain);
            if (l.securityId() == null) continue;
            BigDecimal[] b = sec.computeIfAbsent(l.securityId(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            switch (a) {
                case REALIZED_GAIN_LOSS -> b[0] = b[0].add(gain);
                case CHANGE_IN_UNREALIZED -> b[1] = b[1].add(gain);
                case DIVIDEND_INCOME, INTEREST_INCOME -> b[2] = b[2].add(gain);
                default -> { }
            }
        }
        Map<String, SecurityPnl> bySecurity = new LinkedHashMap<>();
        sec.forEach((id, b) -> bySecurity.put(id, new SecurityPnl(id, b[0], b[1], b[2], b[0].add(b[1]).add(b[2]))));
        return new PnlReport(from, to, byAccount, bySecurity, total);
    }
}

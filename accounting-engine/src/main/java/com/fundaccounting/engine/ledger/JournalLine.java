package com.fundaccounting.engine.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One line of a balanced journal entry. Amounts are in the fund's base currency; {@code localAmount} is the signed
 * amount in {@code currency} for monetary accounts (positive = debit direction).
 */
public record JournalLine(long entryId, LocalDate date, Account account, String currency, String securityId,
                          BigDecimal debit, BigDecimal credit, BigDecimal localAmount, String description,
                          String sourceType, String sourceId) {
    public BigDecimal net() { return debit.subtract(credit); }
}

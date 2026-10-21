package com.fundaccounting.engine.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * CASH_DIVIDEND: amount per share, entitlement on holdings at the end of the day before the ex-date, paid on payDate.
 * SPLIT: ratio = new shares per old share (2 for a 2-for-1 split), applied on the ex-date.
 */
public record CorporateAction(String id, int version, String securityId, Type type, LocalDate exDate, LocalDate payDate,
                              BigDecimal amountPerShare, BigDecimal ratio, Instant recordedAt, Instant supersededAt,
                              boolean cancelled) implements Bitemporal {
    public enum Type { CASH_DIVIDEND, SPLIT }

    @Override public LocalDate effectiveDate() { return exDate; }
}

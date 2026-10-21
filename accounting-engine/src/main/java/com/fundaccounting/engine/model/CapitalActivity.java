package com.fundaccounting.engine.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Investor money in or out of a share class, dealt at that day's NAV per share.
 * SUBSCRIPTION: amount in the fund's base currency. REDEMPTION: number of shares redeemed.
 */
public record CapitalActivity(String id, int version, String fundId, String shareClassId, String investorId, Type type,
                              LocalDate dealingDate, BigDecimal amount, BigDecimal shares, Instant recordedAt,
                              Instant supersededAt, boolean cancelled) implements Bitemporal {
    public enum Type { SUBSCRIPTION, REDEMPTION }

    @Override public LocalDate effectiveDate() { return dealingDate; }
}

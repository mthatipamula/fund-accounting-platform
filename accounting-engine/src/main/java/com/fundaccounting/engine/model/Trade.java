package com.fundaccounting.engine.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A trade. Price is per share (equities) or percent of par (bonds); commission is in the security currency and is
 * added to cost on buys and deducted from proceeds on sells.
 */
public record Trade(String id, int version, String fundId, String securityId, Side side, BigDecimal quantity,
                    BigDecimal price, BigDecimal commission, LocalDate tradeDate, LocalDate settleDate,
                    Instant recordedAt, Instant supersededAt, boolean cancelled) implements Bitemporal {
    @Override public LocalDate effectiveDate() { return tradeDate; }

    public static Trade of(String id, String fundId, String securityId, Side side, String quantity, String price,
                           String commission, LocalDate tradeDate, LocalDate settleDate, Instant recordedAt) {
        return new Trade(id, 1, fundId, securityId, side, new BigDecimal(quantity), new BigDecimal(price),
                new BigDecimal(commission), tradeDate, settleDate, recordedAt, null, false);
    }
}

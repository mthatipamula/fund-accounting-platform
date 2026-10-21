package com.fundaccounting.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Prices and FX rates as known at the moment a report is run. Implementations return the latest value on or before
 * the date (so weekends and holidays carry the last price).
 */
public interface MarketData {
    Optional<BigDecimal> price(String securityId, LocalDate date);

    /** Units of base currency per one unit of {@code currency}. 1 when they are the same. */
    Optional<BigDecimal> fxToBase(String currency, String baseCurrency, LocalDate date);
}

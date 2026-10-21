package com.fundaccounting.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/** Prices and FX rates held in memory; lookups return the latest value on or before the date. */
public final class InMemoryMarketData implements MarketData {
    private final Map<String, NavigableMap<LocalDate, BigDecimal>> prices = new HashMap<>();
    private final Map<String, NavigableMap<LocalDate, BigDecimal>> fx = new HashMap<>();   // currency -> base rate

    public InMemoryMarketData price(String securityId, LocalDate date, String price) {
        return price(securityId, date, new BigDecimal(price));
    }

    public InMemoryMarketData price(String securityId, LocalDate date, BigDecimal price) {
        prices.computeIfAbsent(securityId, k -> new TreeMap<>()).put(date, price);
        return this;
    }

    public InMemoryMarketData fx(String currency, LocalDate date, String rate) {
        return fx(currency, date, new BigDecimal(rate));
    }

    public InMemoryMarketData fx(String currency, LocalDate date, BigDecimal rate) {
        fx.computeIfAbsent(currency, k -> new TreeMap<>()).put(date, rate);
        return this;
    }

    @Override public Optional<BigDecimal> price(String securityId, LocalDate date) {
        return latest(prices.get(securityId), date);
    }

    @Override public Optional<BigDecimal> fxToBase(String currency, String baseCurrency, LocalDate date) {
        if (currency.equals(baseCurrency)) return Optional.of(BigDecimal.ONE);
        return latest(fx.get(currency), date);
    }

    private static Optional<BigDecimal> latest(NavigableMap<LocalDate, BigDecimal> series, LocalDate date) {
        if (series == null) return Optional.empty();
        var e = series.floorEntry(date);
        return e == null ? Optional.empty() : Optional.of(e.getValue());
    }
}

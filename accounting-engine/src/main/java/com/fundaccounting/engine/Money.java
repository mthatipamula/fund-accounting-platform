package com.fundaccounting.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money arithmetic. Internally values keep high precision so that the ledger balances exactly; amounts are rounded to
 * cents only for display. Never use double for money.
 */
public final class Money {
    public static final int SCALE = 10;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    private Money() {}

    public static BigDecimal of(String v) { return new BigDecimal(v); }

    public static BigDecimal scale(BigDecimal v) { return v.setScale(SCALE, ROUNDING); }

    public static BigDecimal mul(BigDecimal a, BigDecimal b) { return scale(a.multiply(b)); }

    public static BigDecimal div(BigDecimal a, BigDecimal b) { return a.divide(b, SCALE, ROUNDING); }

    public static BigDecimal cents(BigDecimal v) { return v.setScale(2, ROUNDING); }

    public static boolean isZero(BigDecimal v) { return v.signum() == 0; }
}

package com.fundaccounting.engine.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A security (instrument). Bond fields are null for equities.
 *
 * @param couponRate annual coupon as a decimal (0.045 = 4.5%)
 * @param couponsPerYear 1, 2 or 4
 */
public record Security(String id, String symbol, String name, AssetClass assetClass, String currency,
                       BigDecimal couponRate, Integer couponsPerYear, LocalDate issueDate, LocalDate maturityDate,
                       DayCount dayCount) {

    public static Security equity(String id, String symbol, String name, String currency) {
        return new Security(id, symbol, name, AssetClass.EQUITY, currency, null, null, null, null, null);
    }

    public static Security bond(String id, String symbol, String name, String currency, BigDecimal couponRate,
                                int couponsPerYear, LocalDate issueDate, LocalDate maturityDate, DayCount dayCount) {
        return new Security(id, symbol, name, AssetClass.BOND, currency, couponRate, couponsPerYear, issueDate, maturityDate, dayCount);
    }

    public boolean isBond() { return assetClass == AssetClass.BOND; }

    /** Market value in the security's currency for a quantity at a price. Bonds are quoted per 100 of face. */
    public BigDecimal marketValue(BigDecimal quantity, BigDecimal price) {
        BigDecimal mv = quantity.multiply(price);
        return isBond() ? mv.movePointLeft(2) : mv;
    }

    /** Coupon dates from issue to maturity (stepping back from maturity, the market convention). */
    public List<LocalDate> couponDates() {
        List<LocalDate> dates = new ArrayList<>();
        if (!isBond()) return dates;
        int months = 12 / couponsPerYear;
        for (LocalDate d = maturityDate; d.isAfter(issueDate); d = d.minusMonths(months)) dates.addFirst(d);
        return dates;
    }

    /** The coupon period start for a date: the last coupon date on or before it, or the issue date. */
    public LocalDate accrualStart(LocalDate on) {
        LocalDate start = issueDate;
        for (LocalDate c : couponDates()) {
            if (c.isAfter(on)) break;
            start = c;
        }
        return start;
    }

    /** Accrued interest (in the security currency) on a face amount at the end of a date. */
    public BigDecimal accruedInterest(BigDecimal face, LocalDate on) {
        if (!isBond() || on.isBefore(issueDate) || !on.isBefore(maturityDate)) return BigDecimal.ZERO;
        BigDecimal fraction = dayCount.fraction(accrualStart(on), on);
        return face.multiply(couponRate).multiply(fraction);
    }

    public BigDecimal couponAmount(BigDecimal face) {
        return face.multiply(couponRate).divide(BigDecimal.valueOf(couponsPerYear), 12, java.math.RoundingMode.HALF_EVEN);
    }
}

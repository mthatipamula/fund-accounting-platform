package com.fundaccounting.engine.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Day-count conventions used to accrue bond interest. */
public enum DayCount {
    THIRTY_360 {
        @Override public BigDecimal fraction(LocalDate from, LocalDate to) {
            int d1 = Math.min(from.getDayOfMonth(), 30);
            int d2 = to.getDayOfMonth() == 31 && d1 == 30 ? 30 : to.getDayOfMonth();
            long days = 360L * (to.getYear() - from.getYear()) + 30L * (to.getMonthValue() - from.getMonthValue()) + (d2 - d1);
            return BigDecimal.valueOf(days).divide(BigDecimal.valueOf(360), 12, RoundingMode.HALF_EVEN);
        }
    },
    ACT_365 {
        @Override public BigDecimal fraction(LocalDate from, LocalDate to) {
            return BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to)).divide(BigDecimal.valueOf(365), 12, RoundingMode.HALF_EVEN);
        }
    };

    public abstract BigDecimal fraction(LocalDate from, LocalDate to);
}

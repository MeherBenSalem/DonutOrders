package io.nightbeam.donutorders.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money is settled in cents. Every amount that moves is rounded here first. */
public final class Money {

    /** Amounts below half a cent count as nothing (rounding dust). */
    public static final double EPSILON = 0.005D;

    private Money() {
    }

    /** Rounded to cents; non-finite and negative amounts become zero. */
    public static double round(double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0D) return 0.0D;
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** {@code count × price} in cents, computed without double rounding drift. */
    public static double times(long count, double price) {
        if (count <= 0 || !Double.isFinite(price) || price <= 0.0D) return 0.0D;
        return BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(count)).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** {@code a - b} in cents, never below zero. */
    public static double minus(double a, double b) {
        return round(BigDecimal.valueOf(a).subtract(BigDecimal.valueOf(b)).doubleValue());
    }

    public static boolean positive(double amount) {
        return Double.isFinite(amount) && amount >= EPSILON;
    }
}

package com.donutorders.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Formats large numbers into compact human-readable strings with K/M/B suffixes.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code 999}       → {@code "999"}</li>
 *   <li>{@code 1_000}     → {@code "1K"}</li>
 *   <li>{@code 1_500}     → {@code "1.5K"}</li>
 *   <li>{@code 1_000_000} → {@code "1M"}</li>
 *   <li>{@code 21_500_000_000d} → {@code "21.5B"}</li>
 * </ul>
 */
public final class NumberFormatter {

    private static final DecimalFormat COMMA_FORMAT;
    private static final DecimalFormat SUFFIX_FORMAT;

    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        COMMA_FORMAT = new DecimalFormat("#,##0.##", symbols);
        SUFFIX_FORMAT = new DecimalFormat("#,##0.##", symbols);
    }

    private NumberFormatter() {}

    /**
     * Formats {@code value} into a compact string.
     *
     * @param value the number to format
     * @return formatted string, e.g. {@code "1.5K"}, {@code "2.3M"}
     */
    public static String format(double value) {
        if (value < 0) {
            return "-" + format(-value);
        }
        if (value >= 1_000_000_000) {
            return SUFFIX_FORMAT.format(value / 1_000_000_000.0) + "B";
        }
        if (value >= 1_000_000) {
            return SUFFIX_FORMAT.format(value / 1_000_000.0) + "M";
        }
        if (value >= 1_000) {
            return SUFFIX_FORMAT.format(value / 1_000.0) + "K";
        }
        // Below 1 000: show as plain integer (orders always deal in whole units)
        return COMMA_FORMAT.format(value);
    }

    /**
     * Formats an integer amount (e.g. item count) using the same compact rules.
     */
    public static String format(int value) {
        return format((double) value);
    }

    /**
     * Formats a price with a leading currency symbol, e.g. {@code "$1.5K"}.
     */
    public static String formatPrice(double value) {
        return "$" + format(value);
    }
}

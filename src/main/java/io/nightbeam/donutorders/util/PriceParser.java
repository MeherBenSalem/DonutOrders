package io.nightbeam.donutorders.util;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads prices players type: {@code 2500}, {@code 2,500}, {@code 2.5k}, {@code 1.25m}, {@code 3b}, {@code 1t}.
 * Anything else (negative numbers, NaN, hex, Java's {@code 10d} suffixes) is rejected.
 */
public final class PriceParser {

    private static final Pattern PRICE = Pattern.compile("^(\\d{1,3}(?:,\\d{3})+|\\d+)?(?:\\.(\\d+))?([kmbt])?$");

    private PriceParser() {
    }

    public static OptionalDouble parse(String input, boolean allowShorthand) {
        if (input == null) return OptionalDouble.empty();
        String value = input.trim().toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "").replace("$", "");
        Matcher matcher = PRICE.matcher(value);
        if (value.isEmpty() || !matcher.matches() || (matcher.group(1) == null && matcher.group(2) == null)) {
            return OptionalDouble.empty();
        }
        String suffix = matcher.group(3);
        if (suffix != null && !allowShorthand) return OptionalDouble.empty();
        String whole = matcher.group(1) == null ? "0" : matcher.group(1).replace(",", "");
        String fraction = matcher.group(2) == null ? "" : "." + matcher.group(2);
        BigDecimal amount = new BigDecimal(whole + fraction);
        if (suffix != null) {
            amount = amount.movePointRight(switch (suffix) {
                case "k" -> 3;
                case "m" -> 6;
                case "b" -> 9;
                default -> 12;
            });
        }
        double result = amount.doubleValue();
        return Double.isFinite(result) ? OptionalDouble.of(result) : OptionalDouble.empty();
    }
}

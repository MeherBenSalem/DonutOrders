package com.donutorders.util;

import java.util.Locale;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Normalizes chat used by the new-order amount/price flow.
 * Paper Adventure chat, color codes, commas, and {@code 64.0}-style wholes
 * must all parse as numbers.
 */
public final class ChatInputParser {

    private static final Pattern COLOR_CODES = Pattern.compile("(?i)[§&][0-9a-fk-orx]");
    private static final Pattern HEX_CODES = Pattern.compile("(?i)[§&]#?[0-9a-f]{6}");
    private static final Pattern MINI_TAGS = Pattern.compile("<[^>]*>");

    private ChatInputParser() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = HEX_CODES.matcher(raw).replaceAll("");
        text = COLOR_CODES.matcher(text).replaceAll("");
        text = MINI_TAGS.matcher(text).replaceAll("");
        return text.trim();
    }

    public static boolean isCancel(String raw, String keyword) {
        String expected = normalize(keyword);
        if (expected.isEmpty()) {
            expected = "cancel";
        }
        return normalize(raw).equalsIgnoreCase(expected);
    }

    public static OptionalInt parseAmount(String raw) {
        String text = normalizeForNumber(raw);
        if (text.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            if (text.contains(".") || text.contains("e") || text.contains("E")) {
                double value = Double.parseDouble(text);
                if (value <= 0 || value > Integer.MAX_VALUE || value != Math.rint(value)) {
                    return OptionalInt.empty();
                }
                return OptionalInt.of((int) value);
            }
            int value = Integer.parseInt(text);
            if (value <= 0) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(value);
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }

    public static OptionalDouble parsePrice(String raw) {
        String text = normalizeForNumber(raw);
        if (text.isEmpty()) {
            return OptionalDouble.empty();
        }
        try {
            double value = Double.parseDouble(text);
            if (value <= 0 || Double.isNaN(value) || Double.isInfinite(value)) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(value);
        } catch (NumberFormatException ignored) {
            return OptionalDouble.empty();
        }
    }

    private static String normalizeForNumber(String raw) {
        String text = normalize(raw).replace(" ", "").replace("_", "");
        text = text.replace(",", "");
        return text.toLowerCase(Locale.ROOT);
    }
}

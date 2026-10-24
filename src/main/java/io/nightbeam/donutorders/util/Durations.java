package io.nightbeam.donutorders.util;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads durations such as {@code 30m}, {@code 12h}, {@code 7d}, {@code 2w} or {@code 1d12h}. */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d{1,6})\\s*(w|d|h|m|s)");

    private Durations() {
    }

    public static OptionalLong parse(String input) {
        if (input == null) return OptionalLong.empty();
        String value = input.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (value.isEmpty()) return OptionalLong.empty();
        if (value.chars().allMatch(Character::isDigit)) value = value + "h";
        Matcher matcher = PART.matcher(value);
        long total = 0L;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) return OptionalLong.empty();
            end = matcher.end();
            long amount = Long.parseLong(matcher.group(1));
            total += amount * switch (matcher.group(2)) {
                case "w" -> 604_800_000L;
                case "d" -> 86_400_000L;
                case "h" -> 3_600_000L;
                case "m" -> 60_000L;
                default -> 1_000L;
            };
        }
        return end == value.length() && total > 0 ? OptionalLong.of(total) : OptionalLong.empty();
    }

    public static long parse(String input, long fallback) {
        return parse(input).orElse(fallback);
    }
}

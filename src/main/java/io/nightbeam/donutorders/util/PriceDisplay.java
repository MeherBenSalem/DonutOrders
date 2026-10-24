package io.nightbeam.donutorders.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.function.DoubleFunction;
import org.bukkit.configuration.ConfigurationSection;

/** Presentation only: never used to parse, store or settle a price. */
public final class PriceDisplay {
    private PriceDisplay() { }

    public static String compact(double price, ConfigurationSection config,
                                 DoubleFunction<String> provider, String currency) {
        requireFinite(price);
        if (!config.getBoolean("price-display.compact.enabled", false) || Math.abs(price) < 1000) {
            return provider.apply(price);
        }
        int precision = Math.max(0, Math.min(6, config.getInt("price-display.compact.precision", 1)));
        BigDecimal amount = BigDecimal.valueOf(price);
        boolean millions = Math.abs(price) >= 1_000_000;
        BigDecimal scaled = amount.divide(BigDecimal.valueOf(millions ? 1_000_000 : 1000), precision, RoundingMode.HALF_UP);
        if (!millions && scaled.abs().compareTo(BigDecimal.valueOf(1000)) >= 0) {
            millions = true;
            scaled = amount.divide(BigDecimal.valueOf(1_000_000), precision, RoundingMode.HALF_UP);
        }
        String suffix = config.getString("price-display.compact." + (millions ? "million-suffix" : "thousand-suffix"), millions ? "M" : "k");
        if (suffix == null || suffix.isBlank()) suffix = millions ? "M" : "k";
        String template = config.getString("price-display.compact.template", "%amount%%suffix% %currency%");
        if (template == null || !template.contains("%amount%")) template = "%amount%%suffix% %currency%";
        return template.replace("%amount%", number(scaled, locale(config)))
                .replace("%suffix%", suffix).replace("%currency%", currency == null ? "" : currency).trim();
    }

    public static String precise(double price, ConfigurationSection config, DoubleFunction<String> provider) {
        requireFinite(price);
        String formatted = provider.apply(price);
        BigDecimal exact = BigDecimal.valueOf(price);
        // Keep the provider's currency style, and expose all digits when it rounds.
        if (showsExactly(formatted, exact)) return formatted;
        return formatted + " (" + number(exact, locale(config)) + ")";
    }

    /** True when the formatted text is the exact amount, read with "." or "," as the decimal separator. */
    static boolean showsExactly(String formatted, BigDecimal amount) {
        String digits = formatted == null ? "" : formatted.replaceAll("[^0-9.,]", "");
        if (digits.isEmpty() || !Character.isDigit(digits.charAt(0)) || !Character.isDigit(digits.charAt(digits.length() - 1))) {
            return false;
        }
        for (String candidate : new String[] {digits.replace(",", ""), digits.replace(".", "").replace(',', '.')}) {
            try {
                if (new BigDecimal(candidate).compareTo(amount) == 0) return true;
            } catch (NumberFormatException notOneNumber) {
                // Try the other reading.
            }
        }
        return false;
    }

    private static String number(BigDecimal amount, Locale locale) {
        DecimalFormat format = new DecimalFormat("0", DecimalFormatSymbols.getInstance(locale));
        format.setGroupingUsed(false);
        format.setMaximumFractionDigits(340);
        return format.format(amount.stripTrailingZeros());
    }

    private static Locale locale(ConfigurationSection config) {
        String tag = config.getString("price-display.locale", "en-US");
        try {
            return new Locale.Builder().setLanguageTag(tag == null ? "en-US" : tag).build();
        } catch (java.util.IllformedLocaleException exception) {
            return Locale.US;
        }
    }

    private static void requireFinite(double price) {
        if (!Double.isFinite(price)) throw new IllegalArgumentException("Price must be finite");
    }
}

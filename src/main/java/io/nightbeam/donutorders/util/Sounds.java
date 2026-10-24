package io.nightbeam.donutorders.util;

import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Plays configured sounds by key ("minecraft:ui.button.click" or "minecraft:ui.button.click:0.6:1.2" for
 * volume and pitch). Keys are strings, so they work on every version whatever the Sound API looks like.
 */
public final class Sounds {

    private Sounds() {
    }

    public static void play(Player player, ConfigurationSection config, String event) {
        if (player == null || config == null || !config.getBoolean("sounds.enabled", true)) return;
        String spec = config.getString("sounds." + event, "");
        if (spec == null || spec.isBlank() || spec.equalsIgnoreCase("none")) return;
        String[] parts = spec.trim().split(":");
        String key = spec.trim().toLowerCase(Locale.ROOT);
        float volume = 0.8F;
        float pitch = 1.0F;
        if (parts.length >= 4) {
            key = (parts[0] + ":" + parts[1]).toLowerCase(Locale.ROOT);
            volume = number(parts[2], volume);
            pitch = number(parts[3], pitch);
        } else if (parts.length == 3 && !parts[0].isEmpty() && Character.isLetter(parts[0].charAt(0)) && isNumber(parts[1])) {
            key = parts[0].toLowerCase(Locale.ROOT);
            volume = number(parts[1], volume);
            pitch = number(parts[2], pitch);
        }
        try {
            player.playSound(player.getLocation(), key, volume, pitch);
        } catch (RuntimeException ignored) {
            // A bad key in the config should never break a purchase.
        }
    }

    private static boolean isNumber(String value) {
        try {
            Float.parseFloat(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static float number(String value, float fallback) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}

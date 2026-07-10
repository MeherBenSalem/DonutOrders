package com.donutorders.util;

import com.donutorders.DonutOrders;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Central access point for all player-facing text loaded from {@code messages.yml}.
 *
 * <p>Supports:
 * <ul>
 *   <li>{@code &} color codes and {@code #RRGGBB} hex (via {@link DonutOrders#colorize})</li>
 *   <li>Positional placeholders {@code {0}}, {@code {1}}, …</li>
 *   <li>Named placeholders {@code {buyer}}, {@code {price}}, etc.</li>
 *   <li>Safe fallbacks when a key is missing (defaults from jar, then code default)</li>
 * </ul>
 *
 * <p>Call {@link DonutOrders#reloadPluginConfig()} (or {@code /orders admin reload})
 * to pick up edits to {@code messages.yml} without a full restart.
 */
public final class MessageHelper {

    private MessageHelper() {}

    // ── Core getters ──────────────────────────────────────────────────────────

    /**
     * Returns the raw (uncolorized) string for {@code path}, or {@code def} if missing.
     */
    public static String raw(String path, String def) {
        FileConfiguration messages = messages();
        if (messages == null) return def != null ? def : "";
        String value = messages.getString(path);
        if (value == null || value.isEmpty()) {
            return def != null ? def : path;
        }
        return value;
    }

    /**
     * Returns a colorized string for {@code path}, falling back to {@code def}.
     */
    public static String get(String path, String def) {
        return DonutOrders.colorize(raw(path, def));
    }

    /**
     * Returns a colorized string with positional placeholders replaced.
     * {@code {0}} is replaced by {@code args[0]}, etc.
     */
    public static String get(String path, String def, Object... args) {
        return DonutOrders.colorize(format(raw(path, def), args));
    }

    /**
     * Returns a colorized string with named placeholders replaced.
     * Keys in the map correspond to {@code {key}} in the template.
     */
    public static String getNamed(String path, String def, Map<String, String> placeholders) {
        return DonutOrders.colorize(formatNamed(raw(path, def), placeholders));
    }

    /**
     * Returns a colorized string with named placeholders from alternating key/value pairs.
     * Example: {@code getNamed("gui.x", "def", "buyer", "Steve", "price", "10")}
     */
    public static String getNamed(String path, String def, String... keyValues) {
        return DonutOrders.colorize(formatNamed(raw(path, def), toMap(keyValues)));
    }

    /**
     * Returns a list of colorized lore lines for {@code path}.
     * Empty list if the key is missing or not a list.
     */
    public static List<String> getList(String path) {
        return getList(path, Collections.emptyMap());
    }

    /**
     * Returns a list of colorized lore lines with named placeholders applied to each line.
     */
    public static List<String> getList(String path, Map<String, String> placeholders) {
        FileConfiguration messages = messages();
        if (messages == null) return Collections.emptyList();
        List<String> raw = messages.getStringList(path);
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>(raw.size());
        for (String line : raw) {
            out.add(DonutOrders.colorize(formatNamed(line, placeholders)));
        }
        return out;
    }

    /**
     * Returns a list of colorized lore lines with named placeholders from key/value pairs.
     */
    public static List<String> getList(String path, String... keyValues) {
        return getList(path, toMap(keyValues));
    }

    /**
     * Returns a list of colorized lore lines with positional placeholders applied.
     */
    public static List<String> getListPositional(String path, Object... args) {
        FileConfiguration messages = messages();
        if (messages == null) return Collections.emptyList();
        List<String> raw = messages.getStringList(path);
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>(raw.size());
        for (String line : raw) {
            out.add(DonutOrders.colorize(format(line, args)));
        }
        return out;
    }

    // ── Convenience ───────────────────────────────────────────────────────────

    /** Colorized plugin prefix from {@code prefix}. */
    public static String prefix() {
        return get("prefix", "&8[&b&lᴅᴏɴᴜᴛ&f&lᴏʀᴅᴇʀꜱ&8] &r");
    }

    /**
     * Sends a colorized message (no prefix) to {@code sender}.
     */
    public static void send(CommandSender sender, String path, String def, Object... args) {
        sender.sendMessage(get(path, def, args));
    }

    /**
     * Sends a colorized message with the plugin prefix prepended.
     */
    public static void sendPrefixed(CommandSender sender, String path, String def, Object... args) {
        sender.sendMessage(prefix() + get(path, def, args));
    }

    /**
     * Formats a raw template string with positional placeholders (no colorize).
     */
    public static String format(String template, Object... args) {
        if (template == null) return "";
        if (args == null || args.length == 0) return template;
        String result = template;
        for (int i = 0; i < args.length; i++) {
            String value = args[i] != null ? String.valueOf(args[i]) : "";
            result = result.replace("{" + i + "}", value);
        }
        return result;
    }

    /**
     * Formats a raw template string with named placeholders (no colorize).
     */
    public static String formatNamed(String template, Map<String, String> placeholders) {
        if (template == null) return "";
        if (placeholders == null || placeholders.isEmpty()) return template;
        String result = template;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", e.getValue() != null ? e.getValue() : "");
        }
        return result;
    }

    /**
     * Formats a countdown / expiry string using {@code time.*} keys from messages.yml.
     */
    public static String formatExpiry(long expiresAtMillis) {
        long remaining = expiresAtMillis - System.currentTimeMillis();
        if (remaining <= 0) {
            return raw("time.expired", "ᴇxᴘɪʀᴇᴅ");
        }

        long totalSeconds = remaining / 1000;
        long days    = totalSeconds / 86400;
        long hours   = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;

        String daySuffix    = raw("time.days-suffix", "ᴅ");
        String hourSuffix   = raw("time.hours-suffix", "ʜ");
        String minuteSuffix = raw("time.minutes-suffix", "ᴍ");

        StringBuilder sb = new StringBuilder();
        if (days > 0)    sb.append(days).append(daySuffix).append(" ");
        if (hours > 0)   sb.append(hours).append(hourSuffix).append(" ");
        if (minutes > 0 || sb.length() == 0) sb.append(minutes).append(minuteSuffix);
        return sb.toString().trim();
    }

    /**
     * Returns the translated display name for an {@link com.donutorders.model.OrderStatus}.
     */
    public static String statusName(com.donutorders.model.OrderStatus status) {
        if (status == null) return "";
        return switch (status) {
            case ACTIVE    -> raw("status.active", "ᴀᴄᴛɪᴠᴇ");
            case COMPLETED -> raw("status.completed", "ᴄᴏᴍᴘʟᴇᴛᴇᴅ");
            case EXPIRED   -> raw("status.expired", "ᴇxᴘɪʀᴇᴅ");
            case CANCELLED -> raw("status.cancelled", "ᴄᴀɴᴄᴇʟʟᴇᴅ");
            case PENDING   -> raw("status.pending", "ᴘᴇɴᴅɪɴɢ ᴄᴏʟʟᴇᴄᴛɪᴏɴ");
            case CLAIMED   -> raw("status.claimed", "ᴄʟᴀɪᴍᴇᴅ");
        };
    }

    /**
     * Color code prefix for a status (e.g. {@code §a} for ACTIVE).
     * Stored without {@code &} so they can be concatenated into already-§ strings.
     */
    public static String statusColor(com.donutorders.model.OrderStatus status) {
        if (status == null) return "§7";
        String key = switch (status) {
            case ACTIVE    -> "status.color.active";
            case COMPLETED -> "status.color.completed";
            case EXPIRED   -> "status.color.expired";
            case CANCELLED -> "status.color.cancelled";
            case PENDING   -> "status.color.pending";
            case CLAIMED   -> "status.color.claimed";
        };
        String def = switch (status) {
            case ACTIVE    -> "&a";
            case COMPLETED -> "&b";
            case EXPIRED   -> "&6";
            case CANCELLED -> "&c";
            case PENDING   -> "&e";
            case CLAIMED   -> "&8";
        };
        // colorize turns &a into §a
        return DonutOrders.colorize(raw(key, def));
    }

    /** Keyword players type in chat to cancel input (default: cancel). */
    public static String cancelKeyword() {
        return raw("chat-cancel-keyword", "cancel");
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static FileConfiguration messages() {
        DonutOrders plugin = DonutOrders.getInstance();
        return plugin != null ? plugin.getMessages() : null;
    }

    private static Map<String, String> toMap(String... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return Collections.emptyMap();
        }
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}

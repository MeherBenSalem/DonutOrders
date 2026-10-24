package io.nightbeam.donutorders.util;

import io.nightbeam.donutorders.DonutOrdersPlugin;
import io.nightbeam.donutorders.model.OrderCategory;
import io.nightbeam.donutorders.model.OrderSort;
import io.nightbeam.donutorders.model.OrderStatus;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Messages accept MiniMessage tags ({@code <gold>}, {@code <#ffaa00>}, {@code <gradient:...>}), legacy
 * {@code &} codes and {@code &#RRGGBB} hex in the same string.
 *
 * <p>Templates come from messages.yml and are trusted. Placeholder values (item names, player names,
 * search terms) are escaped so they can never add formatting, click events or colors. Values produced by
 * the plugin itself, such as translatable item names, are wrapped with {@link #trusted(String)}.
 */
public final class MessageUtil {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pattern PLACEHOLDER = Pattern.compile("%([A-Za-z0-9_.-]+)%");
    private static final Pattern LEGACY = Pattern.compile("(?i)[&§](?:#([0-9a-f]{6})|x((?:[&§][0-9a-f]){6})|([0-9a-fk-or&]))");
    /** Unforgeable marker for plugin-generated MiniMessage fragments; players cannot type it into names. */
    private static final String TRUSTED = "\uE000" + randomToken() + "\uE001";
    private static final Map<Character, String> CODES = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"), Map.entry('3', "dark_aqua"),
            Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"), Map.entry('6', "gold"), Map.entry('7', "gray"),
            Map.entry('8', "dark_gray"), Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"), Map.entry('f', "white"),
            Map.entry('k', "obfuscated"), Map.entry('l', "bold"), Map.entry('m', "strikethrough"), Map.entry('n', "underlined"),
            Map.entry('o', "italic"));

    private final DonutOrdersPlugin plugin;
    private String prefix;

    public MessageUtil(DonutOrdersPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration messages = messages();
        String fromMessages = messages == null ? null : messages.getString("prefix");
        String fromConfig = plugin.getConfig() == null ? null : plugin.getConfig().getString("messages.prefix");
        if (fromMessages != null && !fromMessages.isEmpty()) {
            this.prefix = fromMessages;
        } else if (fromConfig != null && !fromConfig.isEmpty()) {
            this.prefix = fromConfig;
        } else {
            this.prefix = "<#49d6a0><b>Orders</b> <dark_gray>» ";
        }
    }

    /** Marks a MiniMessage fragment built by the plugin so placeholder escaping keeps its tags. */
    public static String trusted(String miniMessage) {
        return TRUSTED + miniMessage;
    }

    /** Escapes a value for use inside a template: no tags, no legacy codes. */
    public static String escape(String value) {
        if (value == null) return "";
        if (value.startsWith(TRUSTED)) return value.substring(TRUSTED.length());
        return MINI.escapeTags(value.replace(TRUSTED, "")).replace("&", "&&").replace("§", "&&");
    }

    public String raw(String path, String def) {
        FileConfiguration config = messages();
        if (config == null) {
            return def != null ? def : path;
        }
        String value = config.getString(path);
        if (value == null || value.isEmpty()) {
            return def != null ? def : path;
        }
        return value;
    }

    public List<String> rawList(String path, List<String> def) {
        FileConfiguration config = messages();
        if (config == null || !config.isList(path)) return def;
        List<String> lines = config.getStringList(path);
        return lines.isEmpty() ? def : lines;
    }

    public String format(String path, String def, Map<String, String> placeholders) {
        return substitute(raw(path, def), placeholders);
    }

    public String format(String path, String def, String... keyValues) {
        return format(path, def, toMap(keyValues));
    }

    /** Substitutes escaped values into a trusted template in one pass, so values are never re-expanded. */
    public static String substitute(String template, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty() || template.indexOf('%') < 0) return template;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = placeholders.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : escape(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Parses a trusted template (MiniMessage and legacy codes). */
    public Component component(String message) {
        return parse(message);
    }

    public static Component parse(String message) {
        if (message == null || message.isEmpty()) return Component.empty();
        try {
            return MINI.deserialize(legacyToMiniMessage(message.replace(TRUSTED, "")));
        } catch (RuntimeException malformed) {
            return Component.text(message);
        }
    }

    public Component component(String path, String def) {
        return component(raw(path, def));
    }

    public Component component(String path, String def, String... keyValues) {
        return component(format(path, def, keyValues));
    }

    /**
     * An item name or menu title: no default italics. With {@code style.small-caps} on, the template's own
     * words are written in small capitals (placeholders such as player and item names are left as they are).
     */
    public Component name(String path, String def, String... keyValues) {
        String template = raw(path, def);
        if (plugin.getConfig().getBoolean("style.small-caps", true)) template = smallCaps(template);
        return noItalic(component(substitute(template, toMap(keyValues))));
    }

    private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    /**
     * Small capitals for letters outside MiniMessage tags, {@code %placeholders%} and legacy color codes.
     */
    public static String smallCaps(String template) {
        if (template == null) return "";
        StringBuilder out = new StringBuilder(template.length());
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '<') {
                int end = template.indexOf('>', i);
                if (end > i) {
                    out.append(template, i, end + 1);
                    i = end + 1;
                    continue;
                }
            } else if (c == '%') {
                int end = template.indexOf('%', i + 1);
                if (end > i + 1 && template.substring(i + 1, end).matches("[A-Za-z0-9_.-]+")) {
                    out.append(template, i, end + 1);
                    i = end + 1;
                    continue;
                }
            } else if ((c == '&' || c == '§') && i + 1 < template.length()) {
                char next = template.charAt(i + 1);
                if (next == '#' && i + 8 <= template.length()) {
                    out.append(template, i, i + 8);
                    i += 8;
                } else {
                    out.append(c).append(next);
                    i += 2;
                }
                continue;
            }
            char lower = Character.toLowerCase(c);
            out.append(lower >= 'a' && lower <= 'z' ? SMALL_CAPS.charAt(lower - 'a') : c);
            i++;
        }
        return out.toString();
    }

    /** A lore line: no default italics, gray unless the template sets a color. */
    public Component lore(String path, String def, String... keyValues) {
        return loreLine(component(path, def, keyValues));
    }

    public static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static Component loreLine(Component component) {
        return noItalic(component).colorIfAbsent(NamedTextColor.GRAY);
    }

    public List<Component> loreComponents(String path, String... keyValues) {
        FileConfiguration config = messages();
        if (config == null) {
            return List.of();
        }
        List<String> lines = config.getStringList(path);
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        Map<String, String> placeholders = toMap(keyValues);
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(loreLine(component(substitute(line, placeholders))));
        }
        return out;
    }

    public String plain(Component component) {
        return PLAIN.serialize(component);
    }

    public void send(CommandSender sender, String message) {
        if (message == null || message.isBlank()) return;
        sender.sendMessage(component(prefix + message));
    }

    public void sendRaw(CommandSender sender, String path, String def) {
        send(sender, raw(path, def));
    }

    public void sendFormatted(CommandSender sender, String path, String def, String... keyValues) {
        send(sender, format(path, def, keyValues));
    }

    public void sendComponent(CommandSender sender, String path, String def, String... keyValues) {
        sender.sendMessage(component(path, def, keyValues));
    }

    public String category(OrderCategory category) {
        return raw("category." + category.key(), title(category.name()));
    }

    public String sort(OrderSort sort) {
        return raw("sort." + sort.name().toLowerCase(Locale.ROOT).replace('_', '-'), title(sort.name()));
    }

    public String status(OrderStatus status) {
        return raw("status." + status.name().toLowerCase(Locale.ROOT), title(status.name()));
    }

    /** "1 day", "12 hours", "30 minutes", from messages.yml units. */
    public String duration(long millis) {
        long minutes = Math.max(1L, (millis + 59_999L) / 60_000L);
        if (minutes % (60L * 24L) == 0) return plural(minutes / (60L * 24L), "day");
        if (minutes % 60L == 0) return plural(minutes / 60L, "hour");
        if (minutes >= 60L * 24L) return plural(minutes / (60L * 24L), "day") + " " + plural((minutes / 60L) % 24L, "hour");
        if (minutes >= 60L) return plural(minutes / 60L, "hour") + " " + plural(minutes % 60L, "minute");
        return plural(minutes, "minute");
    }

    /** Time left until a deadline, compact ("2d 4h", "35m"). */
    public String timeLeft(long millis) {
        if (millis <= 0) return raw("time.expired", "expired");
        long seconds = millis / 1000L;
        long days = seconds / 86_400L;
        long hours = (seconds % 86_400L) / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        String d = raw("time.short-day", "d");
        String h = raw("time.short-hour", "h");
        String m = raw("time.short-minute", "m");
        if (days > 0) return days + d + " " + hours + h;
        if (hours > 0) return hours + h + " " + minutes + m;
        return Math.max(1L, minutes) + m;
    }

    private String plural(long count, String unit) {
        return format("time." + unit + (count == 1 ? "" : "s"), "%count% " + unit + (count == 1 ? "" : "s"),
                "count", String.valueOf(count));
    }

    private static String title(String constant) {
        StringBuilder out = new StringBuilder();
        for (String word : constant.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    public String unknownPlayer() {
        return raw("unknown-player", "Unknown");
    }

    private FileConfiguration messages() {
        return plugin.getMessagesConfig();
    }

    /** Converts legacy {@code &}/{@code §} codes and hex to MiniMessage tags; {@code &&} is a literal ampersand. */
    static String legacyToMiniMessage(String input) {
        if (input.indexOf('&') < 0 && input.indexOf('§') < 0) return input;
        Matcher matcher = LEGACY.matcher(input);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            if (matcher.group(1) != null) {
                replacement = "<reset><#" + matcher.group(1).toLowerCase(Locale.ROOT) + ">";
            } else if (matcher.group(2) != null) {
                String digits = matcher.group(2).replaceAll("[&§]", "");
                replacement = "<reset><#" + digits.toLowerCase(Locale.ROOT) + ">";
            } else {
                char code = Character.toLowerCase(matcher.group(3).charAt(0));
                if (code == '&') {
                    replacement = "&";
                } else if (code == 'r') {
                    replacement = "<reset>";
                } else if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
                    // A legacy color also clears bold, italic and the rest.
                    replacement = "<reset><" + CODES.get(code) + ">";
                } else {
                    replacement = "<" + CODES.get(code) + ">";
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String randomToken() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static Map<String, String> toMap(String... keyValues) {
        Map<String, String> map = new HashMap<>();
        if (keyValues == null) {
            return map;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}

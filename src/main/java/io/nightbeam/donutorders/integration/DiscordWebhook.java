package io.nightbeam.donutorders.integration;

import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.util.Enchants;
import io.nightbeam.donutorders.util.ItemNames;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Posts order events to a Discord channel webhook as embeds: the item, amounts, prices and the players' heads.
 * Requests run on one background thread in order, and Discord's rate limit (HTTP 429) is respected.
 */
public final class DiscordWebhook {

    public enum Kind {
        CREATED(0xF2B33D, "New buy order"),
        DELIVERED(0x5B8DEF, "Delivery"),
        FILLED(0x4CC38A, "Order filled"),
        CANCELLED(0x8A8F98, "Order cancelled"),
        EXPIRED(0x8A8F98, "Order expired"),
        SUSPICIOUS(0xE5534B, "Suspicious delivery");

        final int color;
        final String title;

        Kind(int color, String title) {
            this.color = color;
            this.title = title;
        }
    }

    /**
     * Everything an embed shows. Prices are already formatted with the server's economy; {@code value} is the
     * amount of money involved, compared with {@code discord.min-value}. {@code actor} is the other player
     * (the seller of a delivery) or null; {@code note} is an extra line (the reason of a suspicious delivery).
     */
    public record Post(Kind kind, Order order, int amount, String price, String total, double value, String actorName,
                       UUID actor, String note) {
    }

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)");

    private final Supplier<FileConfiguration> config;
    private final Logger logger;
    private final HttpClient client;
    private final ExecutorService sender;
    private volatile long lastWarning;

    public DiscordWebhook(Supplier<FileConfiguration> config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.sender = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "DonutOrders-Discord");
            thread.setDaemon(true);
            return thread;
        });
    }

    public boolean wants(Kind kind, double value) {
        FileConfiguration cfg = config.get();
        if (!cfg.getBoolean("discord.enabled", false)) return false;
        String url = cfg.getString("discord.webhook-url", "");
        if (url == null || !url.startsWith("https://")) return false;
        if (kind != Kind.SUSPICIOUS && value < cfg.getDouble("discord.min-value", 0.0D)) return false;
        return cfg.getStringList("discord.events").stream().anyMatch(name -> name.equalsIgnoreCase(kind.name()));
    }

    public void post(Post post) {
        if (!wants(post.kind(), post.value())) return;
        FileConfiguration cfg = config.get();
        String url = cfg.getString("discord.webhook-url", "");
        String body = json(post, cfg);
        try {
            sender.execute(() -> send(url, body, 0));
        } catch (RuntimeException rejected) {
            // Shutting down.
        }
    }

    private void send(String url, String body, int attempt) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "DonutOrders (https://github.com/MeherBenSalem/DonutOrders)")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429 && attempt < 3) {
                Matcher matcher = RETRY_AFTER.matcher(response.body());
                double seconds = matcher.find() ? Double.parseDouble(matcher.group(1)) : 2.0D;
                Thread.sleep((long) Math.min(30_000, Math.max(250, seconds * 1000)));
                send(url, body, attempt + 1);
            } else if (response.statusCode() >= 300) {
                warn("Discord webhook answered HTTP " + response.statusCode() + ". Check discord.webhook-url.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            warn("Discord webhook failed: " + exception.getMessage());
        }
    }

    private void warn(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarning > 60_000L) {
            lastWarning = now;
            logger.warning(message);
        }
    }

    static String json(Post post, FileConfiguration cfg) {
        Order order = post.order();
        ItemStack item = order.template();
        String name = ItemNames.plain(item);
        StringBuilder description = new StringBuilder("**").append(markdown(name)).append("**");
        for (String line : details(item)) description.append("\n").append(markdown(line));
        if (post.note() != null && !post.note().isBlank()) description.append("\n\n").append(markdown(post.note()));

        List<String> fields = new ArrayList<>();
        String wanted = order.unlimited() ? "unlimited" : String.valueOf(order.amount());
        switch (post.kind()) {
            case CREATED -> {
                fields.add(field("Wants", wanted, true));
                fields.add(field("Price each", post.price(), true));
                fields.add(field("Total", post.total(), true));
                fields.add(field("Ends", "<t:" + order.expiresAt() / 1000 + ":R>", true));
            }
            case DELIVERED, SUSPICIOUS -> {
                fields.add(field("Delivered", String.valueOf(post.amount()), true));
                fields.add(field("Paid", post.total(), true));
                fields.add(field("Seller", post.actorName(), true));
                fields.add(field("Progress", order.delivered() + "/" + wanted, true));
            }
            default -> {
                fields.add(field("Delivered", order.delivered() + "/" + wanted, true));
                fields.add(field("Price each", post.price(), true));
                if (post.total() != null) fields.add(field("Refunded", post.total(), true));
            }
        }
        fields.add(field("Buyer", order.ownerName(), true));

        String title = cfg.getString("discord.titles." + post.kind().name().toLowerCase(Locale.ROOT), post.kind().title);
        String head = cfg.getString("discord.head-url", "https://mc-heads.net/avatar/{uuid}/64");
        String icon = cfg.getString("discord.item-icon-url", "");
        StringBuilder json = new StringBuilder("{");
        appendString(json, "username", cfg.getString("discord.username", "Buy Orders")).append(',');
        String avatar = cfg.getString("discord.avatar-url", "");
        if (avatar != null && !avatar.isBlank()) appendString(json, "avatar_url", avatar).append(',');
        json.append("\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{");
        appendString(json, "title", title).append(',');
        appendString(json, "description", description.toString()).append(',');
        json.append("\"color\":").append(post.kind().color).append(',');
        json.append("\"fields\":[").append(String.join(",", fields)).append("],");
        if (head != null && !head.isBlank()) {
            boolean seller = post.actor() != null;
            json.append("\"author\":{");
            appendString(json, "name", seller ? post.actorName() : order.ownerName()).append(',');
            appendString(json, "icon_url", head.replace("{uuid}", (seller ? post.actor() : order.owner()).toString()));
            json.append("},");
        }
        if (icon != null && !icon.isBlank()) {
            json.append("\"thumbnail\":{");
            appendString(json, "url", icon.replace("{material}", item.getType().name().toLowerCase(Locale.ROOT)));
            json.append("},");
        }
        json.append("\"footer\":{");
        appendString(json, "text", cfg.getString("discord.footer", "DonutOrders") + " · " + order.shortId());
        json.append("},");
        appendString(json, "timestamp", Instant.now().toString());
        json.append("}]}");
        return json.toString();
    }

    /** Enchantments and lore as plain lines, at most eight. */
    static List<String> details(ItemStack item) {
        List<String> lines = new ArrayList<>();
        try {
            if (!item.hasItemMeta()) return lines;
            // A book's stored enchantments are already in its name.
            Map<org.bukkit.enchantments.Enchantment, Integer> enchants = Enchants.applied(item);
            enchants.forEach((enchantment, level) -> lines.add(Enchants.name(enchantment) + Enchants.levelSuffix(enchantment, level)));
            ItemMeta meta = item.getItemMeta();
            List<Component> lore = meta.hasLore() ? meta.lore() : null;
            if (lore != null) for (Component line : lore) lines.add(PLAIN.serialize(line));
        } catch (RuntimeException | LinkageError ignored) {
            // Metadata from another version: the name is enough.
        }
        return lines.size() > 8 ? lines.subList(0, 8) : lines;
    }

    private static String field(String name, String value, boolean inline) {
        StringBuilder json = new StringBuilder("{");
        appendString(json, "name", name).append(',');
        appendString(json, "value", value == null || value.isBlank() ? "-" : value).append(',');
        json.append("\"inline\":").append(inline).append('}');
        return json.toString();
    }

    private static String markdown(String text) {
        return text.replaceAll("([*_~`|>\\\\])", "\\\\$1");
    }

    private static StringBuilder appendString(StringBuilder json, String key, String value) {
        json.append('"').append(key).append("\":\"");
        String text = value == null ? "" : value;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) json.append(String.format("\\u%04x", (int) c));
                    else json.append(c);
                }
            }
        }
        return json.append('"');
    }

    public void shutdown() {
        sender.shutdown();
        try {
            sender.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

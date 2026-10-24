package io.nightbeam.donutorders.hook;

import io.nightbeam.donutorders.DonutOrdersPlugin;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.model.PlayerStats;
import io.nightbeam.donutorders.model.PriceInsight;
import io.nightbeam.donutorders.service.OrderService;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * {@code %donutorders_...%}. Values that need the database are cached for a minute and refreshed in the
 * background, so a scoreboard never waits for a query.
 */
public final class OrdersPlaceholders extends PlaceholderExpansion {

    private record Cached(long at, Object value) {
    }

    private final DonutOrdersPlugin plugin;
    private final OrderService orders;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public OrdersPlaceholders(DonutOrdersPlugin plugin, OrderService orders) {
        this.plugin = plugin;
        this.orders = orders;
    }

    @Override public String getIdentifier() { return "donutorders"; }
    @Override public String getAuthor() { return "NightBeam"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        switch (key) {
            case "market_active" -> {
                return String.valueOf(orders.cache().active(now).size());
            }
            case "market_value" -> {
                double total = 0.0D;
                for (Order order : orders.cache().active(now)) total += order.serverOrder() ? 0.0D : order.escrow();
                return orders.format(total);
            }
            default -> {
            }
        }
        for (String prefix : new String[] {"best_", "average_", "demand_", "orders_"}) {
            if (!key.startsWith(prefix)) continue;
            Material material = Material.matchMaterial(params.substring(prefix.length()));
            if (material == null) return "";
            String itemKey = material.name();
            if (prefix.equals("best_") || prefix.equals("demand_") || prefix.equals("orders_")) {
                double[] demand = orders.cache().demand(itemKey, now);
                return switch (prefix) {
                    case "best_" -> demand[0] > 0 ? orders.format(demand[0]) : "-";
                    case "demand_" -> String.valueOf((long) demand[1]);
                    default -> String.valueOf((int) demand[2]);
                };
            }
            PriceInsight insight = (PriceInsight) cached("insight:" + itemKey, () -> orders.insight(itemKey), PriceInsight.empty(itemKey));
            return insight.average() > 0 ? orders.format(insight.average()) : "-";
        }
        if (player == null) return "";
        UUID id = player.getUniqueId();
        switch (key) {
            case "active", "open" -> {
                return String.valueOf(orders.cache().openCount(id));
            }
            case "limit", "order_limit" -> {
                Player online = player.getPlayer();
                if (online == null) return "";
                int limit = orders.rules().orderLimit(online);
                return limit < 0 ? "∞" : String.valueOf(limit);
            }
            case "slots_left" -> {
                Player online = player.getPlayer();
                if (online == null) return "";
                int limit = orders.rules().orderLimit(online);
                return limit < 0 ? "∞" : String.valueOf(Math.max(0, limit - orders.cache().openCount(id)));
            }
            case "collect", "waiting" -> {
                return String.valueOf(orders.cache().waitingFor(id));
            }
            case "delivered", "earned", "received", "spent" -> {
                PlayerStats stats = (PlayerStats) cached("stats:" + id, () -> orders.stats(id), PlayerStats.EMPTY);
                return switch (key) {
                    case "delivered" -> String.valueOf(stats.itemsDelivered());
                    case "earned" -> orders.format(stats.earned());
                    case "received" -> String.valueOf(stats.itemsReceived());
                    default -> orders.format(stats.spent());
                };
            }
            default -> {
                return null;
            }
        }
    }

    private Object cached(String key, java.util.function.Supplier<java.util.concurrent.CompletableFuture<?>> loader, Object empty) {
        long now = System.currentTimeMillis();
        Cached known = cache.get(key);
        if (known == null || now - known.at() > 60_000L) {
            // Keep showing the old value while the new one loads.
            cache.put(key, new Cached(now, known == null ? empty : known.value()));
            loader.get().thenAccept(value -> cache.put(key, new Cached(System.currentTimeMillis(), value)));
        }
        Cached current = cache.get(key);
        return current == null ? empty : current.value();
    }
}

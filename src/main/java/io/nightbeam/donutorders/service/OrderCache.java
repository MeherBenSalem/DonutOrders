package io.nightbeam.donutorders.service;

import io.nightbeam.donutorders.model.BrowseRequest;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.model.OrderPage;
import io.nightbeam.donutorders.model.OrderStatus;
import io.nightbeam.donutorders.util.Enchants;
import io.nightbeam.donutorders.util.ItemNames;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.bukkit.inventory.ItemStack;

/**
 * The orders this server shows: every open order and every closed one that still holds items. It is a view of
 * the database, refreshed by this server's own changes, Redis messages and polling; it never decides anything.
 */
public final class OrderCache {

    private record Search(long version, String text) {
    }

    private final Map<UUID, Order> orders = new ConcurrentHashMap<>();
    private final Map<UUID, Search> search = new ConcurrentHashMap<>();

    public void replaceAll(Collection<Order> loaded) {
        orders.clear();
        search.clear();
        for (Order order : loaded) if (order.live()) orders.put(order.id(), order);
    }

    /** Stores a newer snapshot (or drops the order once it is closed and emptied). */
    public void apply(Order order) {
        if (order == null) return;
        orders.compute(order.id(), (id, known) -> {
            if (known != null && known.version() > order.version()) return known;
            return order.live() ? order : null;
        });
        if (!orders.containsKey(order.id())) search.remove(order.id());
    }

    public void remove(UUID id) {
        orders.remove(id);
        search.remove(id);
    }

    public Optional<Order> get(UUID id) {
        return Optional.ofNullable(orders.get(id));
    }

    /** An order by its full id or the first characters of it (as shown to players), if that is unambiguous. */
    public Optional<Order> find(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        String needle = id.trim().toLowerCase(Locale.ROOT);
        try {
            return get(UUID.fromString(needle));
        } catch (IllegalArgumentException partial) {
            if (needle.length() < 4) return Optional.empty();
            List<Order> found = orders.values().stream().filter(order -> order.id().toString().startsWith(needle)).limit(2).toList();
            return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
        }
    }

    public Collection<Order> all() {
        return orders.values();
    }

    public List<Order> active(long now) {
        return orders.values().stream().filter(order -> order.acceptsDeliveries(now)).toList();
    }

    /** Open orders past their end time, for the expiry task. */
    public List<Order> expired(long now) {
        return orders.values().stream().filter(order -> order.status() == OrderStatus.ACTIVE && order.expiresAt() <= now).toList();
    }

    /** A player's live orders: open first, then by creation, newest first. */
    public List<Order> ofOwner(UUID owner) {
        return orders.values().stream().filter(order -> order.owner().equals(owner))
                .sorted(Comparator.comparing((Order order) -> order.status() != OrderStatus.ACTIVE)
                        .thenComparing(Comparator.comparingLong(Order::createdAt).reversed()))
                .toList();
    }

    public int openCount(UUID owner) {
        int count = 0;
        for (Order order : orders.values()) if (order.owner().equals(owner) && order.status() == OrderStatus.ACTIVE) count++;
        return count;
    }

    public int waitingFor(UUID owner) {
        int waiting = 0;
        for (Order order : orders.values()) if (order.owner().equals(owner)) waiting += order.waiting();
        return waiting;
    }

    /**
     * Open orders that take this item, best price first; equal prices go to the oldest order (first come, first
     * served). The excluded player's own orders are skipped.
     */
    public List<Order> bestFor(ItemStack item, UUID exclude, ItemMatcher matcher, long now) {
        List<Order> matching = new ArrayList<>();
        for (Order order : orders.values()) {
            if (!order.acceptsDeliveries(now) || order.owner().equals(exclude)) continue;
            if (matcher.accepts(order, item)) matching.add(order);
        }
        matching.sort(Comparator.comparingDouble(Order::price).reversed().thenComparingLong(Order::createdAt));
        return matching;
    }

    /** Best open price, items wanted and number of open orders for an item key. */
    public double[] demand(String itemKey, long now) {
        double best = 0.0D;
        double wanted = 0.0D;
        int count = 0;
        for (Order order : orders.values()) {
            if (!order.acceptsDeliveries(now) || !order.itemKey().equals(itemKey)) continue;
            best = Math.max(best, order.price());
            wanted += order.unlimited() ? 0 : order.remaining();
            count++;
        }
        return new double[] {best, wanted, count};
    }

    public OrderPage browse(BrowseRequest request, int pageSize, long now, Predicate<Order> deliverable) {
        String needle = request.search().toLowerCase(Locale.ROOT).trim();
        List<Order> matching = new ArrayList<>();
        for (Order order : orders.values()) {
            if (!order.acceptsDeliveries(now)) continue;
            if (request.owner() != null && !order.owner().equals(request.owner())) continue;
            if (!request.category().matches(order.template().getType())) continue;
            if (!needle.isEmpty() && !searchText(order).contains(needle)) continue;
            if (request.deliverableOnly() && !deliverable.test(order)) continue;
            matching.add(order);
        }
        matching.sort(request.sort().comparator());
        int size = Math.max(1, pageSize);
        int pages = Math.max(1, (matching.size() + size - 1) / size);
        int page = Math.min(Math.max(1, request.page()), pages);
        int from = (page - 1) * size;
        List<Order> slice = matching.subList(Math.min(from, matching.size()), Math.min(from + size, matching.size()));
        return new OrderPage(List.copyOf(slice), page, pages, matching.size());
    }

    /** Item name, enchantments and the buyer's name, lower case, for board search. */
    String searchText(Order order) {
        Search known = search.get(order.id());
        if (known != null && known.version() == order.version()) return known.text();
        StringBuilder text = new StringBuilder(order.material().replace('_', ' '));
        try {
            text.append(' ').append(ItemNames.plain(order.template()));
            Enchants.stored(order.template()).keySet().forEach(enchantment -> text.append(' ').append(Enchants.name(enchantment)));
            Enchants.applied(order.template()).keySet().forEach(enchantment -> text.append(' ').append(Enchants.name(enchantment)));
        } catch (RuntimeException | LinkageError unavailable) {
            // Tests have no server; the material and name still match.
        }
        text.append(' ').append(order.ownerName());
        String value = text.toString().toLowerCase(Locale.ROOT);
        search.put(order.id(), new Search(order.version(), value));
        return value;
    }

    public int size() {
        return orders.size();
    }
}

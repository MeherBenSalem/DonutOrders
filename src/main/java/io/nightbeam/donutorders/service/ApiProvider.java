package io.nightbeam.donutorders.service;

import io.nightbeam.donutorders.api.DonutOrdersApi;
import io.nightbeam.donutorders.gui.GuiManager;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.model.PriceInsight;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The services-manager implementation of {@link DonutOrdersApi}. */
public final class ApiProvider implements DonutOrdersApi {

    private final OrderService orders;
    private final GuiManager gui;

    public ApiProvider(OrderService orders, GuiManager gui) {
        this.orders = orders;
        this.gui = gui;
    }

    @Override
    public List<Order> activeOrders() {
        return orders.cache().active(System.currentTimeMillis());
    }

    @Override
    public List<Order> ordersOf(UUID owner) {
        return orders.cache().ofOwner(owner);
    }

    @Override
    public Optional<Order> order(UUID orderId) {
        return orders.cache().get(orderId);
    }

    @Override
    public List<Order> bestOrdersFor(ItemStack item, UUID excludeOwner) {
        return orders.bestOrdersFor(item, excludeOwner);
    }

    @Override
    public double bestPrice(ItemStack item, UUID excludeOwner) {
        List<Order> best = orders.bestOrdersFor(item, excludeOwner);
        return best.isEmpty() ? 0.0D : best.get(0).price();
    }

    @Override
    public CompletableFuture<SellResult> sellToOrders(Player seller, List<ItemStack> items) {
        return orders.sellForApi(seller, items);
    }

    @Override
    public CompletableFuture<PriceInsight> insight(ItemStack item) {
        return orders.insight(ItemKeys.fromHeld(item).key());
    }

    @Override
    public CompletableFuture<Order> createServerOrder(ItemStack item, int amount, double pricePerItem, Duration duration) {
        if (amount < 1 || !Double.isFinite(pricePerItem) || pricePerItem <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Amount and price must be positive"));
        }
        return orders.createServerOrder(ItemKeys.fromHeld(item), amount, pricePerItem, duration.toMillis(), null);
    }

    @Override
    public void openBoard(Player player) {
        gui.openBoard(player);
    }

    @Override
    public String formatPrice(double amount) {
        return orders.format(amount);
    }
}

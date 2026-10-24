package io.nightbeam.donutorders.api;

import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.model.PriceInsight;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Public API for other plugins. Get it with {@link #get()} after DonutOrders has enabled (add
 * {@code softdepend: [DonutOrders]} to your plugin.yml). Events live in {@code io.nightbeam.donutorders.api.event}.
 *
 * <p>Orders returned here are immutable snapshots; their {@code template()} must not be modified.
 */
public interface DonutOrdersApi {

    static Optional<DonutOrdersApi> get() {
        RegisteredServiceProvider<DonutOrdersApi> provider = Bukkit.getServicesManager().getRegistration(DonutOrdersApi.class);
        return provider == null ? Optional.empty() : Optional.of(provider.getProvider());
    }

    /** Orders that accept deliveries right now. */
    List<Order> activeOrders();

    /** A player's open orders and closed ones that still hold items for them. */
    List<Order> ordersOf(UUID owner);

    Optional<Order> order(UUID orderId);

    /**
     * Open orders that would take this item, best price first, excluding the given player's own orders (may be
     * null). Use it to show "sells for" prices or route a sell command.
     */
    List<Order> bestOrdersFor(ItemStack item, UUID excludeOwner);

    /** The best price per item an open order pays for this item, or 0. */
    double bestPrice(ItemStack item, UUID excludeOwner);

    /**
     * Sells items into the best open orders for a player, as if they delivered them by hand (seller tax applies,
     * the player is paid). The caller hands the items over: they must already be out of any inventory. Items no
     * order wanted come back in the result, for the caller to sell elsewhere or return.
     *
     * <p>Call it on the player's thread (the main thread, or the player's region on Folia).
     */
    CompletableFuture<SellResult> sellToOrders(Player seller, List<ItemStack> items);

    /** Deliveries of the item within the configured insight window, and the orders open for it now. */
    CompletableFuture<PriceInsight> insight(ItemStack item);

    /**
     * An order paid by the server (no escrow, delivered items are removed from the game). Amount
     * {@link Order#UNLIMITED} keeps it open until it expires.
     */
    CompletableFuture<Order> createServerOrder(ItemStack item, int amount, double pricePerItem, Duration duration);

    void openBoard(Player player);

    /** Formats an amount with the server's economy, for example "$1,250.00". */
    String formatPrice(double amount);

    /** The outcome of {@link #sellToOrders}: items sold, money paid after tax, and what nobody wanted. */
    record SellResult(int sold, double earned, List<ItemStack> leftovers) {
    }
}

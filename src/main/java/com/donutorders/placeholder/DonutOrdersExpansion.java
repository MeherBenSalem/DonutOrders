package com.donutorders.placeholder;

import com.donutorders.DonutOrders;
import com.donutorders.manager.OrderLimitManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI expansion exposing DonutOrders placeholders.
 *
 * <p>Supported placeholders:
 * <ul>
 *   <li>{@code %donutorders_order_limit%} — player's maximum active order count.</li>
 * </ul>
 */
public class DonutOrdersExpansion extends PlaceholderExpansion {

    private final DonutOrders plugin;
    private final OrderLimitManager orderLimitManager;

    public DonutOrdersExpansion(DonutOrders plugin, OrderLimitManager orderLimitManager) {
        this.plugin = plugin;
        this.orderLimitManager = orderLimitManager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "donutorders";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null || !player.isOnline()) {
            return "";
        }
        if (params.equalsIgnoreCase("order_limit")) {
            return String.valueOf(
                    orderLimitManager.getLimitValue(player.getPlayer()));
        }
        return null;
    }
}

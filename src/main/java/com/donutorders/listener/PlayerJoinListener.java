package com.donutorders.listener;

import com.donutorders.manager.OrderLimitManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Refreshes the cached order limit when a player joins.
 */
public class PlayerJoinListener implements Listener {

    private final OrderLimitManager orderLimitManager;

    public PlayerJoinListener(OrderLimitManager orderLimitManager) {
        this.orderLimitManager = orderLimitManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        orderLimitManager.refresh(event.getPlayer());
    }
}

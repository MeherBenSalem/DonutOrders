package com.donutorders.listener;

import com.donutorders.DonutOrders;
import com.donutorders.manager.OrderLimitManager;
import com.donutorders.scheduler.FoliaScheduler;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.EventBus;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Refreshes cached order limits when LuckPerms recalculates a player's permissions.
 */
public class LuckPermsLimitListener {

    private final OrderLimitManager orderLimitManager;

    public LuckPermsLimitListener(OrderLimitManager orderLimitManager) {
        this.orderLimitManager = orderLimitManager;
    }

    public void register(LuckPerms luckPerms) {
        EventBus eventBus = luckPerms.getEventBus();
        eventBus.subscribe(DonutOrders.getInstance(), UserDataRecalculateEvent.class,
                this::onUserDataRecalculate);
    }

    private void onUserDataRecalculate(UserDataRecalculateEvent event) {
        Player player = Bukkit.getPlayer(event.getUser().getUniqueId());
        if (player == null || !player.isOnline()) {
            return;
        }
        FoliaScheduler.runAtEntity(player,
                () -> orderLimitManager.refresh(player),
                null);
    }
}

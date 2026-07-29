package com.donutorders.listener;

import com.donutorders.gui.DeliverItemsGUI;
import com.donutorders.manager.ChatInputHandler;
import com.donutorders.manager.GUIManager;
import com.donutorders.manager.OrderLimitManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Cleans up when a player disconnects.
 *
 * <ul>
 *   <li>Cancels any pending chat input session (triggers the {@code onCancel}
 *       callback so the GUI state is cleaned up properly).</li>
 *   <li>If the player had a {@link DeliverItemsGUI} open, items are returned to
 *       the player's inventory (dropped at their last location since they are
 *       off-line; on Folia the entity is still valid during this event).</li>
 *   <li>Clears the GUI state entry from {@link GUIManager}.</li>
 * </ul>
 *
 * <p>This event fires on the player's region thread (Folia) / main thread
 * (Paper), so all operations here are safe.
 */
public class PlayerQuitListener implements Listener {

    private final GUIManager         guiManager;
    private final ChatInputHandler   chatInputHandler;
    private final OrderLimitManager  orderLimitManager;

    public PlayerQuitListener(GUIManager guiManager, ChatInputHandler chatInputHandler,
                              OrderLimitManager orderLimitManager) {
        this.guiManager         = guiManager;
        this.chatInputHandler   = chatInputHandler;
        this.orderLimitManager  = orderLimitManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Cancel any chat input session — triggers onCancel if registered
        chatInputHandler.cancelSession(player);

        // Return delivery items if the player quits mid-delivery
        GUIManager.PlayerGUIState state = guiManager.getState(player.getUniqueId());
        if (state != null
                && state.type == GUIManager.GUIType.DELIVER_ITEMS
                && state.gui instanceof DeliverItemsGUI deliverGUI) {
            deliverGUI.returnItems(player);
        }

        guiManager.clearState(player.getUniqueId());
        orderLimitManager.evict(player.getUniqueId());
    }
}

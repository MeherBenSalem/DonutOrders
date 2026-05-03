package com.donutorders.listener;

import com.donutorders.gui.BaseGUI;
import com.donutorders.gui.ConfirmDeliveryGUI;
import com.donutorders.gui.DeliverItemsGUI;
import com.donutorders.manager.GUIManager;
import com.donutorders.util.ItemUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Routes inventory events to the correct GUI handler.
 *
 * <h2>Folia thread note</h2>
 * In Folia, {@link InventoryClickEvent} fires on the thread that owns the
 * player's region — the same thread that owns the player entity. No extra
 * scheduling is needed for direct GUI responses.
 *
 * <h2>Material validation in DeliverItemsGUI</h2>
 * Wrong-material items are rejected in {@link InventoryClickEvent} by cancelling
 * the event before the item is placed in the delivery slot. The correct-material
 * check is delegated here (not in the GUI class) because the event must be
 * cancelled at the listener level to prevent the server from processing the move.
 */
public class InventoryListener implements Listener {

    private final GUIManager guiManager;

    public InventoryListener(GUIManager guiManager) {
        this.guiManager = guiManager;
    }

    // ── Click ─────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        GUIManager.PlayerGUIState state = guiManager.getState(player.getUniqueId());
        if (state == null) return;

        Inventory topInv = event.getView().getTopInventory();
        Inventory clickedInv = event.getClickedInventory();

        // Always cancel interactions with OUR top inventory to prevent item theft
        event.setCancelled(true);

        // Ignore clicks in the player's own bottom inventory
        if (clickedInv == null || !clickedInv.equals(topInv)) {
            // Special case: shift-click from player inventory into DeliverItemsGUI
            if (state.type == GUIManager.GUIType.DELIVER_ITEMS
                    && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                    && clickedInv != null && clickedInv.equals(event.getView().getBottomInventory())) {

                handleDeliveryShiftClick(event, player, state);
            }
            return;
        }

        int slot = event.getSlot();
        ItemStack clicked = event.getCurrentItem();
        ClickType type    = event.getClick();

        // ── DeliverItemsGUI: material validation before routing to GUI ────────
        if (state.type == GUIManager.GUIType.DELIVER_ITEMS
                && state.gui instanceof DeliverItemsGUI deliverGUI) {

            if (slot < DeliverItemsGUI.INPUT_SLOTS) {
                // Player is placing a cursor item into an input slot
                ItemStack cursor = event.getCursor();
                if (cursor != null && cursor.getType().isItem()) {
                    if (!ItemUtils.isSameMaterial(cursor, deliverGUI.getOrder().getItemTemplate())) {
                        // Wrong material — reject (event already cancelled)
                        player.sendMessage(com.donutorders.DonutOrders.colorize(
                            com.donutorders.DonutOrders.getInstance().getMessages()
                                .getString("delivery-wrong-item", "&cᴡʀᴏɴɢ ɪᴛᴇᴍ ᴛʏᴘᴇ.")
                                .replace("{0}", ItemUtils.prettyName(
                                    deliverGUI.getOrder().getItemTemplate().getType()))));
                        return;
                    }
                    // Correct material: allow the placement
                    event.setCancelled(false);
                    return;
                }
                // Picking up an item from the slot is allowed (player changed their mind)
                if (event.getAction() == InventoryAction.PICKUP_ALL
                        || event.getAction() == InventoryAction.PICKUP_HALF
                        || event.getAction() == InventoryAction.PICKUP_ONE
                        || event.getAction() == InventoryAction.PICKUP_SOME
                        || event.getAction() == InventoryAction.SWAP_WITH_CURSOR) {
                    if (clicked != null && !ItemUtils.isSameMaterial(
                            event.getCursor(), deliverGUI.getOrder().getItemTemplate())) {
                        // If swapping in wrong item, block
                        if (event.getAction() == InventoryAction.SWAP_WITH_CURSOR) {
                            return;
                        }
                        // Pickup is fine
                        event.setCancelled(false);
                        return;
                    }
                    event.setCancelled(false);
                    return;
                }
            }
            // Bottom-row button clicks go to the GUI's handleClick as normal
        }

        // Route the click to the GUI
        if (state.gui != null) {
            state.gui.handleClick(player, slot, clicked, type);
        }
    }

    // ── Drag ─────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        GUIManager.PlayerGUIState state = guiManager.getState(player.getUniqueId());
        if (state == null) return;

        Inventory topInv = event.getView().getTopInventory();
        int topSize = topInv.getSize();

        // Check if any dragged slots overlap our GUI's top inventory
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                // Dragging into our GUI

                // Allow dragging correct items into DeliverItemsGUI input area
                if (state.type == GUIManager.GUIType.DELIVER_ITEMS
                        && state.gui instanceof DeliverItemsGUI deliverGUI
                        && rawSlot < DeliverItemsGUI.INPUT_SLOTS) {

                    if (ItemUtils.isSameMaterial(event.getOldCursor(),
                            deliverGUI.getOrder().getItemTemplate())) {
                        // Correct material — allow this specific drag
                        continue;
                    }
                }

                // Block everything else
                event.setCancelled(true);
                return;
            }
        }
    }

    // ── Close ─────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        GUIManager.PlayerGUIState state = guiManager.getState(player.getUniqueId());
        if (state == null) return;

        // If player closes DeliverItemsGUI without confirming, return all items
        if (state.type == GUIManager.GUIType.DELIVER_ITEMS
                && state.gui instanceof DeliverItemsGUI deliverGUI) {
            // Run on entity thread (we're already here, but future-proof with explicit scheduling)
            com.donutorders.scheduler.FoliaScheduler.runAtEntity(player,
                () -> deliverGUI.returnItems(player), null);
        } else if (state.type == GUIManager.GUIType.CONFIRM_DELIVERY
                && state.gui instanceof ConfirmDeliveryGUI confirmGUI) {
            // Player closed ConfirmDeliveryGUI without clicking Confirm or Cancel
            // (e.g. pressed ESC). Return the item snapshot if not yet submitted.
            com.donutorders.scheduler.FoliaScheduler.runAtEntity(player,
                () -> confirmGUI.returnItems(player), null);
        }

        guiManager.clearState(player.getUniqueId());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Handles a shift-click from the player's inventory into the DeliverItemsGUI.
     * Only lets the item through if the material matches the order template.
     */
    private void handleDeliveryShiftClick(InventoryClickEvent event,
                                          Player player,
                                          GUIManager.PlayerGUIState state) {
        if (!(state.gui instanceof DeliverItemsGUI deliverGUI)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;

        if (ItemUtils.isSameMaterial(clicked, deliverGUI.getOrder().getItemTemplate())) {
            // Allow the shift-click
            event.setCancelled(false);
        } else {
            player.sendMessage(com.donutorders.DonutOrders.colorize(
                com.donutorders.DonutOrders.getInstance().getMessages()
                    .getString("delivery-wrong-item", "&cᴡʀᴏɴɢ ɪᴛᴇᴍ ᴛʏᴘᴇ.")
                    .replace("{0}", ItemUtils.prettyName(
                        deliverGUI.getOrder().getItemTemplate().getType()))));
        }
    }
}

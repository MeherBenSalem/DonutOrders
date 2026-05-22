package com.donutorders.listener;

import com.donutorders.gui.BaseGUI;
import com.donutorders.gui.ConfirmDeliveryGUI;
import com.donutorders.gui.DeliverItemsGUI;
import com.donutorders.manager.GUIManager;
import com.donutorders.util.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

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
    private final Map<UUID, PlayerInteractionState> interactionStates = new ConcurrentHashMap<>();

    private static class PlayerInteractionState {
        int lastTick = -1;
        int lastSlot = -1;
        int interactionsThisTick = 0;
    }

    public InventoryListener(GUIManager guiManager) {
        this.guiManager = guiManager;
    }

    // ── Click ─────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        GUIManager.PlayerGUIState state = guiManager.getState(player.getUniqueId());
        if (state == null) return;

        int currentTick = Bukkit.getCurrentTick();
        PlayerInteractionState interactionState = interactionStates.computeIfAbsent(player.getUniqueId(), k -> new PlayerInteractionState());

        if (interactionState.lastTick == currentTick) {
            interactionState.interactionsThisTick++;
        } else {
            interactionState.lastTick = currentTick;
            interactionState.interactionsThisTick = 1;
        }

        int slot = event.getSlot();

        // 1. If a player exceeds 2 inventory interactions in a single tick, cancel and log
        if (interactionState.interactionsThisTick > 2) {
            event.setCancelled(true);
            Bukkit.getLogger().log(Level.WARNING,
                "[Security] Player {0} exceeded click rate limit ({1} clicks in tick {2}). Possible packet exploit.",
                new Object[]{player.getName(), interactionState.interactionsThisTick, currentTick});
            return;
        }

        // 2. If a player clicks the exact same slot multiple times in the same tick, cancel and log
        if (interactionState.interactionsThisTick > 1 && interactionState.lastSlot == slot) {
            event.setCancelled(true);
            Bukkit.getLogger().log(Level.WARNING,
                "[Security] Player {0} double-clicked slot {1} in the same tick ({2}). Possible packet replay/delay exploit.",
                new Object[]{player.getName(), slot, currentTick});
            return;
        }

        interactionState.lastSlot = slot;

        Inventory topInv = event.getView().getTopInventory();
        Inventory clickedInv = event.getClickedInventory();

        // ── DELIVER_ITEMS GUI (Fulfill Order) Selective Interaction ──────────
        if (state.type == GUIManager.GUIType.DELIVER_ITEMS && state.gui instanceof DeliverItemsGUI deliverGUI) {
            ItemStack template = deliverGUI.getOrder().getItemTemplate();
            boolean isTopInv = clickedInv != null && clickedInv.equals(topInv);

            if (!isTopInv) {
                // Clicking in player's own bottom inventory
                InventoryAction action = event.getAction();
                if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                    // Shift click from player inventory into the top GUI
                    ItemStack current = event.getCurrentItem();
                    if (current != null && current.getType() != org.bukkit.Material.AIR) {
                        if (current.isSimilar(template)) {
                            event.setCancelled(false);
                        } else {
                            event.setCancelled(true);
                            player.sendMessage(com.donutorders.DonutOrders.colorize(
                                com.donutorders.DonutOrders.getInstance().getMessages()
                                    .getString("delivery-wrong-item", "&cᴡʀᴏɴɢ ɪᴛᴇᴍ ᴛʏᴘᴇ.")
                                    .replace("{0}", ItemUtils.prettyName(template.getType()))));
                        }
                    }
                } else if (action == InventoryAction.COLLECT_TO_CURSOR) {
                    // Double click: only allow collecting if the cursor item is similar to the template
                    ItemStack cursor = event.getCursor();
                    if (cursor != null && cursor.getType() != org.bukkit.Material.AIR) {
                        if (cursor.isSimilar(template)) {
                            event.setCancelled(false);
                        } else {
                            event.setCancelled(true);
                        }
                    }
                } else {
                    // Standard rearrangement/manipulation within player's own inventory
                    event.setCancelled(false);
                }
                return;
            }

            // Clicking inside top GUI inventory
            ItemStack clicked = event.getCurrentItem();
            ClickType type = event.getClick();

            if (slot >= DeliverItemsGUI.INPUT_SLOTS) {
                // Protected slots (buttons & fillers)
                event.setCancelled(true);
                deliverGUI.handleClick(player, slot, clicked, type);
                return;
            }

            // Input slots (0 - 44)
            InventoryAction action = event.getAction();

            // Allow pickups, drops, and shifting out from GUI input slots freely
            if (action == InventoryAction.PICKUP_ALL
                    || action == InventoryAction.PICKUP_HALF
                    || action == InventoryAction.PICKUP_ONE
                    || action == InventoryAction.PICKUP_SOME
                    || action == InventoryAction.DROP_ALL_SLOT
                    || action == InventoryAction.DROP_ONE_SLOT
                    || action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                    || action == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(false);
                return;
            }

            // Allow cursor placing / swapping only if the item matches order template
            if (action == InventoryAction.PLACE_ALL
                    || action == InventoryAction.PLACE_SOME
                    || action == InventoryAction.PLACE_ONE
                    || action == InventoryAction.SWAP_WITH_CURSOR) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && cursor.getType() != org.bukkit.Material.AIR) {
                    if (cursor.isSimilar(template)) {
                        event.setCancelled(false);
                    } else {
                        event.setCancelled(true);
                        player.sendMessage(com.donutorders.DonutOrders.colorize(
                            com.donutorders.DonutOrders.getInstance().getMessages()
                                .getString("delivery-wrong-item", "&cᴡʀᴏɴɢ ɪᴛᴇᴍ ᴛʏᴘᴇ.")
                                .replace("{0}", ItemUtils.prettyName(template.getType()))));
                    }
                }
                return;
            }

            // Allow hotbar swaps only if the hotbar item matches order template
            if (action == InventoryAction.HOTBAR_SWAP || action == InventoryAction.HOTBAR_MOVE_AND_READD) {
                int hotbarSlot = event.getHotbarButton();
                ItemStack hotbarItem = (hotbarSlot >= 0 && hotbarSlot < 9) ? player.getInventory().getItem(hotbarSlot) : null;
                if (hotbarItem != null && hotbarItem.getType() != org.bukkit.Material.AIR) {
                    if (hotbarItem.isSimilar(template)) {
                        event.setCancelled(false);
                    } else {
                        event.setCancelled(true);
                        player.sendMessage(com.donutorders.DonutOrders.colorize(
                            com.donutorders.DonutOrders.getInstance().getMessages()
                                .getString("delivery-wrong-item", "&cᴡʀᴏɴɢ ɪᴛᴇᴍ ᴛʏᴘᴇ.")
                                .replace("{0}", ItemUtils.prettyName(template.getType()))));
                    }
                } else {
                    event.setCancelled(false);
                }
                return;
            }

            // Allow offhand swaps only if the offhand item matches order template
            if (type == ClickType.SWAP_OFFHAND) {
                ItemStack offhandItem = player.getInventory().getItemInOffHand();
                if (offhandItem != null && offhandItem.getType() != org.bukkit.Material.AIR) {
                    if (offhandItem.isSimilar(template)) {
                        event.setCancelled(false);
                    } else {
                        event.setCancelled(true);
                        player.sendMessage(com.donutorders.DonutOrders.colorize(
                            com.donutorders.DonutOrders.getInstance().getMessages()
                                .getString("delivery-wrong-item", "&cᴡʀᴏɴɢ ɪᴛᴇᴍ ᴛʏᴘᴇ.")
                                .replace("{0}", ItemUtils.prettyName(template.getType()))));
                    }
                } else {
                    event.setCancelled(false);
                }
                return;
            }

            // Catch-all safety fallback for input slots
            event.setCancelled(true);
            return;
        }

        // ── STANDARD GUI DEFAULT LOCKDOWN ─────────────────────────────────────
        event.setCancelled(true);

        // Ignore clicks in the player's own bottom inventory
        if (clickedInv == null || !clickedInv.equals(topInv)) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        ClickType type    = event.getClick();

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

                    ItemStack oldCursor = event.getOldCursor();
                    if (oldCursor != null && oldCursor.isSimilar(deliverGUI.getOrder().getItemTemplate())) {
                        // Correct material and metadata — allow this specific drag
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

        interactionStates.remove(player.getUniqueId());

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
}


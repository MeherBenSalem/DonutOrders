package com.donutorders.gui;

import com.donutorders.manager.GUIManager;
import com.donutorders.model.Order;
import com.donutorders.util.DeliveryItemUtils;
import com.donutorders.util.ItemUtils;
import com.donutorders.util.NumberFormatter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

/**
 * GUI: "ᴅᴇʟɪᴠᴇʀ ɪᴛᴇᴍꜱ" — the seller places items here to fulfill a buy order.
 *
 * <p>Layout (54 slots):
 * <pre>
 * [0–44]   Input area — seller places items here (only correct material accepted)
 * [45]     Order info display (locked, no click)
 * [46]–[48] Filler
 * [49]     "ᴄᴏɴꜰɪʀᴍ" button (green wool)
 * [50]–[52] Filler
 * [53]     "ᴄᴀɴᴄᴇʟ" button (red wool)
 * </pre>
 *
 * <p>Wrong-material items are rejected immediately via {@link #handleClick}.
 * The actual validation of final slot contents and the delivery transaction
 * happen in {@link ConfirmDeliveryGUI}.
 */
public class DeliverItemsGUI extends BaseGUI {

    /** Slots available for the seller to place items. */
    public static final int INPUT_SLOTS = 45;

    private static final int SLOT_ORDER_INFO = 45;
    private static final int SLOT_CONFIRM    = 49;
    private static final int SLOT_CANCEL     = 53;

    private final GUIManager guiManager;
    private final Order order;

    /**
     * Set to {@code true} the moment the player clicks CONFIRM.
     * Prevents {@link #returnItems} from handing items back to the player
     * after the GUI transition to {@link ConfirmDeliveryGUI} fires
     * {@code InventoryCloseEvent} and schedules a delayed returnItems call.
     */
    private volatile boolean confirmed = false;

    public DeliverItemsGUI(GUIManager guiManager, Order order) {
        super(Bukkit.createInventory(null, 54, "ᴅᴇʟɪᴠᴇʀ ɪᴛᴇᴍꜱ"));
        this.guiManager = guiManager;
        this.order      = order;
        build();
    }

    private void build() {
        // Locked order info in slot 45
        inventory.setItem(SLOT_ORDER_INFO, ItemUtils.createGuiItem(
            order.getItemTemplate().getType(),
            "§b§l" + ItemUtils.prettyName(order.getItemTemplate().getType()),
            Arrays.asList(
                "§8━━━━━━━━━━━━━━━━━━━━",
                "§7ʙᴜʏᴇʀ: §f" + order.getBuyerName(),
                "§7ɴᴇᴇᴅꜱ: §f" + NumberFormatter.format(order.getAmountRemaining()),
                "§7ᴘʀɪᴄᴇ/ᴜɴɪᴛ: §a" + NumberFormatter.formatPrice(order.getPricePerItem()),
                "§8━━━━━━━━━━━━━━━━━━━━",
                "§7ᴘʟᴀᴄᴇ §f" + ItemUtils.prettyName(order.getItemTemplate().getType())
                    + " §7ɪɴ ᴛʜᴇ ꜱʟᴏᴛꜱ ᴀʙᴏᴠᴇ.",
                "§7ꜱʜᴜʟᴋᴇʀ ʙᴏxᴇꜱ ᴡɪᴛʜ ᴍᴀᴛᴄʜɪɴɢ",
                "§7ɪᴛᴇᴍꜱ ᴀʀᴇ ᴀʟꜱᴏ ꜱᴜᴘᴘᴏʀᴛᴇᴅ.")));

        inventory.setItem(SLOT_CONFIRM, ItemUtils.createGuiItem(
            Material.LIME_WOOL,
            "§a§lᴄᴏɴꜰɪʀᴍ",
            Arrays.asList("§7ᴘʀᴏᴄᴇᴇᴅ ᴡɪᴛʜ ᴅᴇʟɪᴠᴇʀʏ.")));

        inventory.setItem(SLOT_CANCEL, ItemUtils.createGuiItem(
            Material.RED_WOOL,
            "§c§lᴄᴀɴᴄᴇʟ",
            Arrays.asList("§7ɢᴏ ʙᴀᴄᴋ ᴛᴏ ᴛʜᴇ ᴍᴀʀᴋᴇᴛᴘʟᴀᴄᴇ.")));

        // Fill the bottom bar (excluding the three action slots and input slot)
        for (int i = 46; i <= 52; i++) {
            if (i != SLOT_CONFIRM && i != SLOT_CANCEL) {
                inventory.setItem(i, filler());
            }
        }
        // Input area (0–44) starts empty — player places items there
    }

    @Override
    public void handleClick(Player player, int slot, ItemStack clicked, ClickType type) {
        // ── Locked bottom row slots ─────────────────────────────────────────
        if (slot >= INPUT_SLOTS) {
            if (slot == SLOT_CONFIRM) {
                handleConfirm(player);
            } else if (slot == SLOT_CANCEL) {
                returnItems(player);
                guiManager.openPublicOrders(player, 0);
            }
            // All other bottom slots are locked fillers — no action
            return;
        }

        // ── Input area: validate material on click ──────────────────────────
        // The InventoryClickEvent is cancelled for wrong items; correct items
        // are allowed through by NOT cancelling. The listener cancels the event
        // for this GUI by default; returning without action here means the
        // caller (listener) will cancel the event — so we need special logic.
        // We handle this by checking in the listener instead; see InventoryListener.
        // This method just handles button presses for bottom-row slots.
    }

    /**
     * Called by the listener when the player confirms delivery.
     * Scans input slots, validates count, then opens ConfirmDeliveryGUI.
     */
    private void handleConfirm(Player player) {
        // Lock FIRST — blocks returnItems() from returning items during the
        // InventoryCloseEvent that fires when ConfirmDeliveryGUI opens.
        confirmed = true;

        ItemStack[] inputSlots = new ItemStack[INPUT_SLOTS];
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack item = inventory.getItem(i);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            if (item.isSimilar(order.getItemTemplate())) {
                inputSlots[i] = item.clone();
            } else if (DeliveryItemUtils.isShulkerBox(item)
                    && DeliveryItemUtils.shulkerContainsDeliverable(item, order.getItemTemplate())) {
                inputSlots[i] = item.clone();
            }
        }

        int validCount = DeliveryItemUtils.countAvailable(
                player, inputSlots, order.getItemTemplate());

        if (validCount == 0) {
            // Nothing valid — release the lock so the player can cancel normally
            confirmed = false;
            player.sendMessage(com.donutorders.DonutOrders.colorize(
                com.donutorders.DonutOrders.getInstance().getMessages()
                    .getString("delivery-no-items", "&cɴᴏ ᴠᴀʟɪᴅ ɪᴛᴇᴍꜱ.")));
            return;
        }

        // Return excess items (beyond amountRemaining) to the player
        returnExcessItems(player, inputSlots);

        // Clear ALL remaining input slots so that the InventoryCloseEvent triggered
        // by opening ConfirmDeliveryGUI finds an empty GUI and returns nothing.
        int clearedCount = 0;
        for (int i = 0; i < INPUT_SLOTS; i++) {
            if (inventory.getItem(i) != null) {
                inventory.setItem(i, null);
                clearedCount++;
            }
        }
        com.donutorders.DonutOrders.getInstance().getLogger().info(
            "[DonutOrders] DeliverItemsGUI confirmed for " + player.getName()
            + " — cleared " + clearedCount + " GUI slots, snapshot item count: " + validCount);

        // Update delivery state and open confirmation
        GUIManager.PlayerGUIState state = guiManager.getState(player.getUniqueId());
        if (state != null) state.deliverySlots = inputSlots;

        guiManager.openConfirmDelivery(player, order.getOrderId(), inputSlots);
    }

    /**
     * Returns all items from input slots back to the player.
     * Called when the player cancels or closes without confirming.
     * No-op if {@link #handleConfirm} has already been called.
     */
    public void returnItems(Player player) {
        if (confirmed) {
            com.donutorders.DonutOrders.getInstance().getLogger().info(
                "[DonutOrders] DeliverItemsGUI.returnItems skipped for " + player.getName()
                + " — delivery already confirmed.");
            return;
        }
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                var overflow = player.getInventory().addItem(item);
                overflow.values().forEach(drop ->
                    player.getWorld().dropItemNaturally(player.getLocation(), drop));
                inventory.setItem(i, null);
            }
        }
    }

    /**
     * Returns items beyond what the order needs back to the player's inventory.
     */
    private void returnExcessItems(Player player, ItemStack[] snapshot) {
        int needed = order.getAmountRemaining();
        int counted = 0;
        for (int i = 0; i < snapshot.length; i++) {
            ItemStack item = snapshot[i];
            if (item == null || DeliveryItemUtils.isShulkerBox(item)) {
                continue;
            }
            if (counted >= needed) {
                // Return this entire stack
                var overflow = player.getInventory().addItem(item);
                overflow.values().forEach(d ->
                    player.getWorld().dropItemNaturally(player.getLocation(), d));
                inventory.setItem(i, null);
                snapshot[i] = null;
            } else {
                int take = Math.min(item.getAmount(), needed - counted);
                counted += take;
                if (take < item.getAmount()) {
                    // Partial: return excess portion
                    ItemStack excess = item.clone();
                    excess.setAmount(item.getAmount() - take);
                    var overflow = player.getInventory().addItem(excess);
                    overflow.values().forEach(d ->
                        player.getWorld().dropItemNaturally(player.getLocation(), d));
                    // Keep only the taken portion in snapshot
                    ItemStack kept = item.clone();
                    kept.setAmount(take);
                    snapshot[i] = kept;
                    inventory.setItem(i, kept);
                }
            }
        }
    }

    /** Returns the order this delivery GUI was opened for. */
    public Order getOrder() { return order; }
}

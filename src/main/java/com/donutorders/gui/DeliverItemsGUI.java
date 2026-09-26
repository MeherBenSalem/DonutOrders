package com.donutorders.gui;

import com.donutorders.DonutOrders;
import com.donutorders.manager.GUIManager;
import com.donutorders.model.Order;
import com.donutorders.util.DeliveryItemUtils;
import com.donutorders.util.ItemUtils;
import com.donutorders.util.MessageHelper;
import com.donutorders.util.NumberFormatter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GUI: the seller places items here to fulfill a buy order.
 *
 * <p>Wrong-material items are rejected immediately via the inventory listener.
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
     * OPEN items still belong to this GUI. CONFIRMED means the snapshot was
     * handed to {@link ConfirmDeliveryGUI}. RETURNED means cancel/close/quit
     * already gave the live slot contents back.
     */
    private enum Phase { OPEN, CONFIRMED, RETURNED }

    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.OPEN);

    public DeliverItemsGUI(GUIManager guiManager, Order order) {
        super(Bukkit.createInventory(null, 54,
                MessageHelper.get("gui.deliver-items.title", "ᴅᴇʟɪᴠᴇʀ ɪᴛᴇᴍꜱ")));
        this.guiManager = guiManager;
        this.order      = order;
        build();
    }

    private void build() {
        String itemName = ItemUtils.describeOrderItem(order.getItemTemplate());

        inventory.setItem(SLOT_ORDER_INFO, ItemUtils.createGuiItem(
            order.getItemTemplate().getType(),
            MessageHelper.getNamed("gui.deliver-items.order-info.name", "&b&l{item}",
                "item", itemName),
            MessageHelper.getList("gui.deliver-items.order-info.lore",
                "buyer", order.getBuyerName(),
                "remaining", NumberFormatter.format(order.getAmountRemaining()),
                "price", NumberFormatter.formatPrice(order.getPricePerItem()),
                "item", itemName)));

        inventory.setItem(SLOT_CONFIRM, ItemUtils.createGuiItem(
            Material.LIME_WOOL,
            MessageHelper.get("gui.deliver-items.confirm.name", "&a&lᴄᴏɴꜰɪʀᴍ"),
            MessageHelper.getList("gui.deliver-items.confirm.lore")));

        inventory.setItem(SLOT_CANCEL, ItemUtils.createGuiItem(
            Material.RED_WOOL,
            MessageHelper.get("gui.deliver-items.cancel.name", "&c&lᴄᴀɴᴄᴇʟ"),
            MessageHelper.getList("gui.deliver-items.cancel.lore")));

        for (int i = 46; i <= 52; i++) {
            if (i != SLOT_CONFIRM && i != SLOT_CANCEL) {
                inventory.setItem(i, filler());
            }
        }
    }

    @Override
    public void handleClick(Player player, int slot, ItemStack clicked, ClickType type) {
        if (slot >= INPUT_SLOTS) {
            if (slot == SLOT_CONFIRM) {
                handleConfirm(player);
            } else if (slot == SLOT_CANCEL) {
                returnItems(player);
                guiManager.openPublicOrders(player, 0);
            }
        }
    }

    private void handleConfirm(Player player) {
        if (!phase.compareAndSet(Phase.OPEN, Phase.CONFIRMED)) {
            return;
        }

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
            phase.set(Phase.OPEN);
            MessageHelper.send(player, "delivery-no-items",
                "&cʏᴏᴜ ʜᴀᴠᴇ ɴᴏ ᴠᴀʟɪᴅ ɪᴛᴇᴍꜱ ᴛᴏ ᴅᴇʟɪᴠᴇʀ.");
            return;
        }

        returnExcessItems(player, inputSlots);

        int clearedCount = 0;
        for (int i = 0; i < INPUT_SLOTS; i++) {
            if (inventory.getItem(i) != null) {
                inventory.setItem(i, null);
                clearedCount++;
            }
        }
        log("DeliverItemsGUI confirmed for " + player.getName()
            + " — cleared " + clearedCount + " GUI slots, snapshot item count: " + validCount);

        GUIManager.PlayerGUIState state = guiManager.getState(player.getUniqueId());
        if (state != null) state.deliverySlots = inputSlots;

        guiManager.openConfirmDelivery(player, order.getOrderId(), inputSlots);
    }

    /**
     * Returns all items from input slots back to the player (or {@code deathDrops}
     * on death). Called when the player cancels or closes without confirming.
     * No-op if confirm already transferred the snapshot, or if items were
     * already returned (cancel + close, quit + close, double-cancel).
     */
    public void returnItems(Player player) {
        returnItems(player, null);
    }

    public void returnItems(Player player, List<ItemStack> deathDrops) {
        if (!phase.compareAndSet(Phase.OPEN, Phase.RETURNED)) {
            log("DeliverItemsGUI.returnItems skipped for "
                + (player != null ? player.getName() : "unknown")
                + " — delivery already " + phase.get() + ".");
            return;
        }
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack item = inventory.getItem(i);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            inventory.setItem(i, null);
            if (deathDrops != null) {
                deathDrops.add(item.clone());
            } else {
                ItemUtils.giveOrDrop(player, item);
            }
        }
    }

    private static void log(String message) {
        DonutOrders plugin = DonutOrders.getInstance();
        if (plugin != null) {
            plugin.getLogger().info("[DonutOrders] " + message);
        }
    }

    private void returnExcessItems(Player player, ItemStack[] snapshot) {
        int needed = order.getAmountRemaining();
        int counted = 0;
        for (int i = 0; i < snapshot.length; i++) {
            ItemStack item = snapshot[i];
            if (item == null || DeliveryItemUtils.isShulkerBox(item)) {
                continue;
            }
            if (counted >= needed) {
                ItemUtils.giveOrDrop(player, item);
                inventory.setItem(i, null);
                snapshot[i] = null;
            } else {
                int take = Math.min(item.getAmount(), needed - counted);
                counted += take;
                if (take < item.getAmount()) {
                    ItemStack excess = item.clone();
                    excess.setAmount(item.getAmount() - take);
                    ItemUtils.giveOrDrop(player, excess);
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

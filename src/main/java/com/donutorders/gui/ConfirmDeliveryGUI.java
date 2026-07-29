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

/**
 * GUI: shows the seller a summary before committing a delivery.
 */
public class ConfirmDeliveryGUI extends BaseGUI {

    private static final int SLOT_CONFIRM = 11;
    private static final int SLOT_SUMMARY = 13;
    private static final int SLOT_CANCEL  = 15;

    private final GUIManager guiManager;
    private final Order order;
    private final Player seller;
    private final ItemStack[] items;
    private final int deliverCount;
    private final double payout;

    /**
     * Set to {@code true} the moment {@code fulfillOrder} is dispatched.
     * Guards {@link #returnItems} against returning the snapshot items
     * after the transaction has already been submitted to {@code OrderManager}.
     */
    private volatile boolean submitted = false;

    public ConfirmDeliveryGUI(GUIManager guiManager, Player seller, Order order, ItemStack[] items) {
        super(Bukkit.createInventory(null, 27,
                MessageHelper.get("gui.confirm-delivery.title", "ᴄᴏɴꜰɪʀᴍ ᴅᴇʟɪᴠᴇʀʏ")));
        this.guiManager    = guiManager;
        this.seller        = seller;
        this.order         = order;
        this.items         = items;
        this.deliverCount  = Math.min(
                DeliveryItemUtils.countAvailable(seller, items, order.getItemTemplate()),
                order.getAmountRemaining());
        this.payout        = order.getPricePerItem() * deliverCount;
        build();
    }

    private void build() {
        String itemName = ItemUtils.describeOrderItem(order.getItemTemplate());

        inventory.setItem(SLOT_SUMMARY, ItemUtils.createGuiItem(
            order.getItemTemplate().getType(),
            MessageHelper.getNamed("gui.confirm-delivery.summary.name", "&f&l{item}",
                "item", itemName),
            MessageHelper.getList("gui.confirm-delivery.summary.lore",
                "count", NumberFormatter.format(deliverCount),
                "payout", NumberFormatter.formatPrice(payout))));

        inventory.setItem(SLOT_CONFIRM, ItemUtils.createGuiItem(
            Material.LIME_WOOL,
            MessageHelper.get("gui.confirm-delivery.confirm.name", "&a&lᴄᴏɴꜰɪʀᴍ"),
            MessageHelper.getList("gui.confirm-delivery.confirm.lore")));

        inventory.setItem(SLOT_CANCEL, ItemUtils.createGuiItem(
            Material.RED_WOOL,
            MessageHelper.get("gui.confirm-delivery.cancel.name", "&c&lᴄᴀɴᴄᴇʟ"),
            MessageHelper.getList("gui.confirm-delivery.cancel.lore")));

        fillEmpty();
    }

    @Override
    public void handleClick(Player player, int slot, ItemStack clicked, ClickType type) {
        if (slot == SLOT_CONFIRM) {
            submitted = true;
            DonutOrders.getInstance().getLogger().info(
                "[DonutOrders] ConfirmDeliveryGUI submitted by " + player.getName()
                + " — dispatching fulfillOrder for " + deliverCount + " items.");
            guiManager.getOrderManager().fulfillOrder(player, order.getOrderId(), items,
                (success, errorMsg) -> {
                    if (success) {
                        MessageHelper.sendPrefixed(player, "delivery-success",
                            "&aᴅᴇʟɪᴠᴇʀᴇᴅ &f{0}× {1}&a. ʏᴏᴜ ᴇᴀʀɴᴇᴅ &f{2}&a.",
                            NumberFormatter.format(deliverCount),
                            ItemUtils.describeOrderItem(order.getItemTemplate()),
                            NumberFormatter.formatPrice(payout));
                    } else {
                        player.sendMessage(errorMsg != null ? errorMsg
                                : MessageHelper.get("delivery-failed",
                                    "&cᴅᴇʟɪᴠᴇʀʏ ꜰᴀɪʟᴇᴅ. ᴘʟᴇᴀꜱᴇ ᴛʀʏ ᴀɢᴀɪɴ."));
                    }
                    guiManager.openPublicOrders(player, 0);
                });
        } else if (slot == SLOT_CANCEL) {
            returnItems(player);
            guiManager.openPublicOrders(player, 0);
        }
    }

    /**
     * Returns the item snapshot to the player's inventory.
     * Called on CANCEL click and when the GUI is closed without confirming.
     * No-op if {@link #submitted} is {@code true}.
     */
    public void returnItems(Player player) {
        if (submitted) {
            DonutOrders.getInstance().getLogger().info(
                "[DonutOrders] ConfirmDeliveryGUI.returnItems skipped for "
                + player.getName() + " — delivery already submitted.");
            return;
        }
        DonutOrders.getInstance().getLogger().info(
            "[DonutOrders] ConfirmDeliveryGUI.returnItems — returning snapshot to "
            + player.getName());
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR) continue;
            var overflow = player.getInventory().addItem(item.clone());
            overflow.values().forEach(drop ->
                player.getWorld().dropItemNaturally(player.getLocation(), drop));
        }
    }
}

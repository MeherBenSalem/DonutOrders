package com.donutorders.gui;

import com.donutorders.DonutOrders;
import com.donutorders.manager.GUIManager;
import com.donutorders.model.Order;
import com.donutorders.util.DeliveryItemAccount;
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

/**
 * GUI: shows the seller a summary before committing a delivery.
 */
public class ConfirmDeliveryGUI extends BaseGUI {

    private static final int SLOT_CONFIRM = 11;
    private static final int SLOT_SUMMARY = 13;
    private static final int SLOT_CANCEL  = 15;

    private final GUIManager guiManager;
    private final Order order;
    private final DeliveryItemAccount account;
    private final int deliverCount;
    private final double payout;

    public ConfirmDeliveryGUI(GUIManager guiManager, Player seller, Order order, ItemStack[] items) {
        super(Bukkit.createInventory(null, 27,
                MessageHelper.get("gui.confirm-delivery.title", "ᴄᴏɴꜰɪʀᴍ ᴅᴇʟɪᴠᴇʀʏ")));
        this.guiManager    = guiManager;
        this.order         = order;
        this.account       = new DeliveryItemAccount(items);
        this.deliverCount  = Math.min(
                DeliveryItemUtils.countAvailable(seller, account.snapshot(), order.getItemTemplate()),
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
            if (!account.trySubmit()) {
                return;
            }
            log("ConfirmDeliveryGUI submitted by " + player.getName()
                + " — dispatching fulfillOrder for " + deliverCount + " items.");
            guiManager.getOrderManager().fulfillOrder(player, order.getOrderId(), account.snapshot(),
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
     * Returns the item snapshot to the player's inventory (or {@code deathDrops}
     * on death). Called on CANCEL, ESC-close, quit, death, and plugin disable.
     * No-op if the snapshot was already returned or submitted.
     */
    public void returnItems(Player player) {
        returnItems(player, null);
    }

    public void returnItems(Player player, List<ItemStack> deathDrops) {
        List<ItemStack> toGive = account.takeForReturn();
        if (toGive.isEmpty()) {
            log("ConfirmDeliveryGUI.returnItems skipped for "
                + (player != null ? player.getName() : "unknown")
                + " — delivery already " + account.state() + ".");
            return;
        }
        log("ConfirmDeliveryGUI.returnItems — returning snapshot to "
            + (player != null ? player.getName() : "unknown"));
        if (deathDrops != null) {
            deathDrops.addAll(toGive);
        } else {
            ItemUtils.giveOrDropAll(player, toGive);
        }
    }

    private static void log(String message) {
        DonutOrders plugin = DonutOrders.getInstance();
        if (plugin != null) {
            plugin.getLogger().info("[DonutOrders] " + message);
        }
    }
}

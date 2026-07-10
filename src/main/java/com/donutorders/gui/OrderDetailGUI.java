package com.donutorders.gui;

import com.donutorders.manager.GUIManager;
import com.donutorders.model.Order;
import com.donutorders.model.OrderStatus;
import com.donutorders.util.ItemUtils;
import com.donutorders.util.MessageHelper;
import com.donutorders.util.NumberFormatter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/**
 * GUI: the buyer's detail / management screen for a single order.
 */
public class OrderDetailGUI extends BaseGUI {

    private static final int SLOT_COLLECT = 11;
    private static final int SLOT_SUMMARY = 13;
    private static final int SLOT_CANCEL  = 15;
    private static final int SLOT_BACK    = 22;

    private final GUIManager guiManager;
    private final Order order;
    private final boolean hasStashItems;

    public OrderDetailGUI(GUIManager guiManager, Order order, boolean hasStashItems) {
        super(Bukkit.createInventory(null, 27,
                MessageHelper.get("gui.order-detail.title", "ᴏʀᴅᴇʀ ᴅᴇᴛᴀɪʟꜱ")));
        this.guiManager    = guiManager;
        this.order         = order;
        this.hasStashItems = hasStashItems;
        build();
    }

    private void build() {
        inventory.setItem(SLOT_SUMMARY, buildSummaryItem());

        boolean canCollect = order.getStatus() == OrderStatus.PENDING;

        if (canCollect) {
            String stashLine = hasStashItems
                    ? MessageHelper.get("gui.your-orders.stash-has-items", "&e⬛ ꜱᴛᴀꜱʜ ʜᴀꜱ ɪᴛᴇᴍꜱ")
                    : MessageHelper.get("gui.your-orders.stash-empty", "&7ꜱᴛᴀꜱʜ ɪꜱ ᴇᴍᴘᴛʏ");

            inventory.setItem(SLOT_COLLECT, ItemUtils.createGuiItem(
                Material.CHEST,
                MessageHelper.get("gui.order-detail.collect.name", "&a&lᴄᴏʟʟᴇᴄᴛ"),
                MessageHelper.getList("gui.order-detail.collect.lore",
                    "stash_line", stashLine)));
        }

        if (order.getStatus() == OrderStatus.ACTIVE) {
            inventory.setItem(SLOT_CANCEL, ItemUtils.createGuiItem(
                Material.RED_WOOL,
                MessageHelper.get("gui.order-detail.cancel.name", "&c&lᴄᴀɴᴄᴇʟ ᴏʀᴅᴇʀ"),
                MessageHelper.getList("gui.order-detail.cancel.lore",
                    "funds", NumberFormatter.formatPrice(order.getRemainingFunds()))));
        }

        inventory.setItem(SLOT_BACK, ItemUtils.createGuiItem(
            Material.ARROW,
            MessageHelper.get("gui.order-detail.back.name", "&7« ʙᴀᴄᴋ"),
            MessageHelper.getList("gui.order-detail.back.lore")));

        fillEmpty();
    }

    private ItemStack buildSummaryItem() {
        String statusColor = MessageHelper.statusColor(order.getStatus());
        String statusName  = MessageHelper.statusName(order.getStatus());

        return ItemUtils.createGuiItem(
            order.getItemTemplate().getType(),
            MessageHelper.getNamed("gui.order-detail.summary.name", "&f&l{item}",
                "item", ItemUtils.prettyName(order.getItemTemplate().getType())),
            MessageHelper.getList("gui.order-detail.summary.lore",
                "status_color", statusColor,
                "status", statusName,
                "fulfilled", NumberFormatter.format(order.getAmountFulfilled()),
                "requested", NumberFormatter.format(order.getAmountRequested()),
                "price", NumberFormatter.formatPrice(order.getPricePerItem()),
                "funds", NumberFormatter.formatPrice(order.getRemainingFunds()),
                "expiry", order.getFormattedExpiry()));
    }

    @Override
    public void handleClick(Player player, int slot, ItemStack clicked, ClickType type) {
        if (slot == SLOT_COLLECT) {
            guiManager.openCollectStash(player, order.getOrderId());
        } else if (slot == SLOT_CANCEL && order.getStatus() == OrderStatus.ACTIVE) {
            // Capture refund before cancelOrder zeroes remaining funds
            final double refundAmount = order.getRemainingFunds();
            guiManager.getOrderManager().cancelOrder(player, order.getOrderId(),
                success -> {
                    if (success) {
                        MessageHelper.sendPrefixed(player, "order-cancelled",
                            "&aᴏʀᴅᴇʀ ᴄᴀɴᴄᴇʟʟᴇᴅ. &f{0} &7ʀᴇꜰᴜɴᴅᴇᴅ.",
                            NumberFormatter.formatPrice(refundAmount));
                    } else {
                        MessageHelper.send(player, "order-cancel-failed",
                            "&cꜰᴀɪʟᴇᴅ ᴛᴏ ᴄᴀɴᴄᴇʟ ᴏʀᴅᴇʀ.");
                    }
                    guiManager.openYourOrders(player, 0);
                });
        } else if (slot == SLOT_BACK) {
            guiManager.openYourOrders(player, 0);
        }
    }
}

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

import java.util.List;

/**
 * GUI: the buyer's personal order management screen.
 */
public class YourOrdersGUI extends BaseGUI {

    private static final int PAGE_SIZE   = 45;
    private static final int SLOT_PREV   = 45;
    private static final int SLOT_BROWSE = 48;
    private static final int SLOT_NEW    = 49;
    private static final int SLOT_NEXT   = 53;

    private final GUIManager guiManager;
    private final List<Order> orders;
    private final int page;
    private final int maxPage;

    public YourOrdersGUI(GUIManager guiManager, List<Order> orders, int page) {
        super(Bukkit.createInventory(null, 54,
                MessageHelper.get("gui.your-orders.title", "ʏᴏᴜʀ ᴏʀᴅᴇʀꜱ")));
        this.guiManager = guiManager;
        this.orders     = orders;
        this.page       = page;
        this.maxPage    = orders.isEmpty() ? 0 : Math.max(0, (orders.size() - 1) / PAGE_SIZE);
        build();
    }

    private void build() {
        int start = page * PAGE_SIZE;
        int end   = Math.min(start + PAGE_SIZE, orders.size());

        for (int i = start; i < end; i++) {
            inventory.setItem(i - start, buildOrderItem(orders.get(i)));
        }

        if (page > 0) {
            inventory.setItem(SLOT_PREV, ItemUtils.createGuiItem(
                Material.ARROW,
                MessageHelper.get("buttons.previous.name", "&7« ᴘʀᴇᴠɪᴏᴜꜱ"),
                MessageHelper.getList("buttons.previous.lore",
                    "page", String.valueOf(page),
                    "max_page", String.valueOf(maxPage + 1))));
        }
        if (page < maxPage) {
            inventory.setItem(SLOT_NEXT, ItemUtils.createGuiItem(
                Material.ARROW,
                MessageHelper.get("buttons.next.name", "&7ɴᴇxᴛ »"),
                MessageHelper.getList("buttons.next.lore",
                    "page", String.valueOf(page + 2),
                    "max_page", String.valueOf(maxPage + 1))));
        }

        inventory.setItem(SLOT_NEW, ItemUtils.createGuiItem(
            Material.LIME_STAINED_GLASS_PANE,
            MessageHelper.get("gui.your-orders.new-order.name", "&a&lɴᴇᴡ ᴏʀᴅᴇʀ"),
            MessageHelper.getList("gui.your-orders.new-order.lore")));

        inventory.setItem(SLOT_BROWSE, ItemUtils.createGuiItem(
            Material.COMPASS,
            MessageHelper.get("gui.your-orders.browse-market.name", "&b&lʙʀᴏᴡꜱᴇ ᴍᴀʀᴋᴇᴛ"),
            MessageHelper.getList("gui.your-orders.browse-market.lore")));

        fillEmpty();
    }

    private ItemStack buildOrderItem(Order order) {
        Material mat = order.getItemTemplate().getType();
        String statusColor = MessageHelper.statusColor(order.getStatus());
        String statusName  = MessageHelper.statusName(order.getStatus());

        boolean hasStash = order.getStatus() == OrderStatus.PENDING
                || order.getAmountFulfilled() > 0
                || order.getStatus() == OrderStatus.EXPIRED
                || order.getStatus() == OrderStatus.CANCELLED;

        String stashLine = hasStash
                ? MessageHelper.get("gui.your-orders.stash-has-items", "&e⬛ ꜱᴛᴀꜱʜ ʜᴀꜱ ɪᴛᴇᴍꜱ")
                : MessageHelper.get("gui.your-orders.stash-empty", "&7ꜱᴛᴀꜱʜ ɪꜱ ᴇᴍᴘᴛʏ");

        return ItemUtils.createGuiItem(mat,
            MessageHelper.getNamed("gui.your-orders.order-item.name",
                "{status_color}{item}",
                "status_color", statusColor,
                "item", ItemUtils.describeOrderItem(order.getItemTemplate())),
            MessageHelper.getList("gui.your-orders.order-item.lore",
                "status_color", statusColor,
                "status", statusName,
                "fulfilled", NumberFormatter.format(order.getAmountFulfilled()),
                "requested", NumberFormatter.format(order.getAmountRequested()),
                "price", NumberFormatter.formatPrice(order.getPricePerItem()),
                "expiry", order.getFormattedExpiry(),
                "stash_line", stashLine));
    }

    @Override
    public void handleClick(Player player, int slot, ItemStack clicked, ClickType type) {
        if (slot == SLOT_NEW) {
            guiManager.openNewOrderPicker(player);
            return;
        }
        if (slot == SLOT_BROWSE) {
            guiManager.openPublicOrders(player, 0);
            return;
        }
        if (slot == SLOT_PREV && page > 0) {
            guiManager.openYourOrders(player, page - 1);
            return;
        }
        if (slot == SLOT_NEXT && page < maxPage) {
            guiManager.openYourOrders(player, page + 1);
            return;
        }

        int orderIndex = page * PAGE_SIZE + slot;
        if (slot < PAGE_SIZE && orderIndex < orders.size()) {
            guiManager.openOrderDetail(player, orders.get(orderIndex).getOrderId());
        }
    }
}

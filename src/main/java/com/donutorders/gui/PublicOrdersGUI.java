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
 * GUI: public marketplace showing all active buy orders.
 *
 * <p>Layout (54 slots):
 * <pre>
 * [0–44]  Order items (up to 45 per page)
 * [45]    Previous-page button
 * [48]    "My Orders" button
 * [53]    Next-page button
 * </pre>
 */
public class PublicOrdersGUI extends BaseGUI {

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_MY   = 48;
    private static final int SLOT_NEXT = 53;

    private final GUIManager guiManager;
    private final List<Order> orders;
    private final int page;
    private final int maxPage;

    public PublicOrdersGUI(GUIManager guiManager, List<Order> orders, int page) {
        super(Bukkit.createInventory(null, 54,
                MessageHelper.get("gui.public-orders.title", "ᴏʀᴅᴇʀꜱ")));
        this.guiManager = guiManager;
        this.orders     = orders;
        this.page       = page;
        this.maxPage    = Math.max(0, (orders.size() - 1) / PAGE_SIZE);
        build();
    }

    private void build() {
        int start = page * PAGE_SIZE;
        int end   = Math.min(start + PAGE_SIZE, orders.size());

        for (int i = start; i < end; i++) {
            Order o = orders.get(i);
            inventory.setItem(i - start, buildOrderItem(o));
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

        inventory.setItem(SLOT_MY, ItemUtils.createGuiItem(
            Material.PLAYER_HEAD,
            MessageHelper.get("gui.public-orders.my-orders.name", "&b&lᴍʏ ᴏʀᴅᴇʀꜱ"),
            MessageHelper.getList("gui.public-orders.my-orders.lore")));

        fillEmpty();
    }

    private ItemStack buildOrderItem(Order order) {
        ItemStack template = order.getItemTemplate();
        String itemName = ItemUtils.describeOrderItem(template);
        return ItemUtils.createGuiItem(template.getType(),
            MessageHelper.getNamed("gui.public-orders.order-item.name", "&f{item}",
                "item", itemName),
            MessageHelper.getList("gui.public-orders.order-item.lore",
                "buyer", order.getBuyerName(),
                "remaining", NumberFormatter.format(order.getAmountRemaining()),
                "requested", NumberFormatter.format(order.getAmountRequested()),
                "price", NumberFormatter.formatPrice(order.getPricePerItem()),
                "total", NumberFormatter.formatPrice(
                    order.getPricePerItem() * order.getAmountRemaining()),
                "expiry", order.getFormattedExpiry()));
    }

    @Override
    public void handleClick(Player player, int slot, ItemStack clicked, ClickType type) {
        if (slot == SLOT_PREV && page > 0) {
            guiManager.openPublicOrders(player, page - 1);
            return;
        }
        if (slot == SLOT_NEXT && page < maxPage) {
            guiManager.openPublicOrders(player, page + 1);
            return;
        }
        if (slot == SLOT_MY) {
            guiManager.openYourActiveOrders(player, 0);
            return;
        }

        int orderIndex = page * PAGE_SIZE + slot;
        if (slot < PAGE_SIZE && orderIndex < orders.size()) {
            Order order = orders.get(orderIndex);
            if (order.getBuyerUUID().equals(player.getUniqueId())) {
                MessageHelper.send(player, "delivery-own-order",
                    "&cʏᴏᴜ ᴄᴀɴɴᴏᴛ ꜰᴜʟꜰɪʟʟ ʏᴏᴜʀ ᴏᴡɴ ᴏʀᴅᴇʀ.");
                return;
            }
            if (order.getStatus() != OrderStatus.ACTIVE) return;
            guiManager.openDeliverItems(player, order.getOrderId());
        }
    }
}

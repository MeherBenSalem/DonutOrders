package com.donutorders.gui;

import com.donutorders.manager.GUIManager;
import com.donutorders.model.Order;
import com.donutorders.model.OrderStatus;
import com.donutorders.util.ItemUtils;
import com.donutorders.util.NumberFormatter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;

/**
 * GUI: "ʏᴏᴜʀ ᴏʀᴅᴇʀꜱ" — the buyer's personal order management screen.
 *
 * <p>Layout (54 slots):
 * <pre>
 * [0–44]  Player's own orders
 * [45]    Previous-page button
 * [46]    Filler
 * [47]    Filler
 * [48]    "ʙʀᴏᴡꜱᴇ ᴍᴀʀᴋᴇᴛ" button
 * [49]    "ɴᴇᴡ ᴏʀᴅᴇʀ" button (lime glass)
 * [50]–[52] Filler
 * [53]    Next-page button
 * </pre>
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
        super(Bukkit.createInventory(null, 54, "ʏᴏᴜʀ ᴏʀᴅᴇʀꜱ"));
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

        // Navigation
        if (page > 0) {
            inventory.setItem(SLOT_PREV, ItemUtils.createGuiItem(
                Material.ARROW, "§7« ᴘʀᴇᴠɪᴏᴜꜱ",
                Arrays.asList("§8ᴘᴀɢᴇ " + page + " ᴏꜰ " + (maxPage + 1))));
        }
        if (page < maxPage) {
            inventory.setItem(SLOT_NEXT, ItemUtils.createGuiItem(
                Material.ARROW, "§7ɴᴇxᴛ »",
                Arrays.asList("§8ᴘᴀɢᴇ " + (page + 2) + " ᴏꜰ " + (maxPage + 1))));
        }

        inventory.setItem(SLOT_NEW, ItemUtils.createGuiItem(
            Material.LIME_STAINED_GLASS_PANE,
            "§a§lɴᴇᴡ ᴏʀᴅᴇʀ",
            Arrays.asList("§7ᴄʀᴇᴀᴛᴇ ᴀ ɴᴇᴡ ʙᴜʏ ᴏʀᴅᴇʀ.")));

        inventory.setItem(SLOT_BROWSE, ItemUtils.createGuiItem(
            Material.COMPASS,
            "§b§lʙʀᴏᴡꜱᴇ ᴍᴀʀᴋᴇᴛ",
            Arrays.asList("§7ᴠɪᴇᴡ ᴀʟʟ ᴘᴜʙʟɪᴄ ᴏʀᴅᴇʀꜱ.")));

        fillEmpty();
    }

    private ItemStack buildOrderItem(Order order) {
        Material mat = order.getItemTemplate().getType();
        String statusColor = switch (order.getStatus()) {
            case ACTIVE    -> "§a";
            case COMPLETED -> "§b";
            case EXPIRED   -> "§6";
            case CANCELLED -> "§c";
        };
        String statusName = switch (order.getStatus()) {
            case ACTIVE    -> "ᴀᴄᴛɪᴠᴇ";
            case COMPLETED -> "ᴄᴏᴍᴘʟᴇᴛᴇᴅ";
            case EXPIRED   -> "ᴇxᴘɪʀᴇᴅ";
            case CANCELLED -> "ᴄᴀɴᴄᴇʟʟᴇᴅ";
        };

        boolean hasStash = order.getAmountFulfilled() > 0
                || order.getStatus() == OrderStatus.EXPIRED
                || order.getStatus() == OrderStatus.CANCELLED;

        return ItemUtils.createGuiItem(mat,
            statusColor + ItemUtils.prettyName(mat),
            Arrays.asList(
                "§8━━━━━━━━━━━━━━━━━━━━",
                "§7ꜱᴛᴀᴛᴜꜱ: " + statusColor + statusName,
                "§7ᴘʀᴏɢʀᴇꜱꜱ: §f"
                    + NumberFormatter.format(order.getAmountFulfilled())
                    + " §7/ §f" + NumberFormatter.format(order.getAmountRequested()),
                "§7ᴘʀɪᴄᴇ/ᴜɴɪᴛ: §a" + NumberFormatter.formatPrice(order.getPricePerItem()),
                "§7ᴇxᴘɪʀᴇꜱ: §e" + order.getFormattedExpiry(),
                hasStash ? "§e⬛ ꜱᴛᴀꜱʜ ʜᴀꜱ ɪᴛᴇᴍꜱ" : "§7ꜱᴛᴀꜱʜ ɪꜱ ᴇᴍᴘᴛʏ",
                "§8━━━━━━━━━━━━━━━━━━━━",
                "§eʟᴇꜰᴛ ᴄʟɪᴄᴋ §7ᴛᴏ ᴠɪᴇᴡ ᴅᴇᴛᴀɪʟꜱ"
            ));
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

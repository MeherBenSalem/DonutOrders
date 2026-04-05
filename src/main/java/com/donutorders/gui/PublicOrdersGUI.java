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
 * GUI: "ᴏʀᴅᴇʀꜱ" — public marketplace showing all active buy orders.
 *
 * <p>Layout (54 slots):
 * <pre>
 * [0–44]  Order items (up to 45 per page)
 * [45]    Previous-page button
 * [46]    Filler
 * [47]    Filler
 * [48]    "ᴍʏ ᴏʀᴅᴇʀꜱ" button
 * [49]    Filler
 * [50]    Filler
 * [51]    Filler
 * [52]    Filler
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
        super(Bukkit.createInventory(null, 54, "ᴏʀᴅᴇʀꜱ"));
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

        // Navigation
        if (page > 0) {
            inventory.setItem(SLOT_PREV, ItemUtils.createGuiItem(
                Material.ARROW,
                "§7« ᴘʀᴇᴠɪᴏᴜꜱ",
                Arrays.asList("§8ᴘᴀɢᴇ " + page + " ᴏꜰ " + (maxPage + 1))));
        }
        if (page < maxPage) {
            inventory.setItem(SLOT_NEXT, ItemUtils.createGuiItem(
                Material.ARROW,
                "§7ɴᴇxᴛ »",
                Arrays.asList("§8ᴘᴀɢᴇ " + (page + 2) + " ᴏꜰ " + (maxPage + 1))));
        }

        inventory.setItem(SLOT_MY, ItemUtils.createGuiItem(
            Material.PLAYER_HEAD,
            "§b§lᴍʏ ᴏʀᴅᴇʀꜱ",
            Arrays.asList("§7ᴠɪᴇᴡ ʏᴏᴜʀ ᴏᴡɴ ᴏʀᴅᴇʀꜱ.")));

        fillEmpty();
    }

    /** Builds the display item shown in the marketplace for one order. */
    private ItemStack buildOrderItem(Order order) {
        Material mat = order.getItemTemplate().getType();
        String itemName = ItemUtils.prettyName(mat);
        return ItemUtils.createGuiItem(mat, "§f" + itemName, Arrays.asList(
            "§8━━━━━━━━━━━━━━━━━━━━",
            "§7ʙᴜʏᴇʀ: §f" + order.getBuyerName(),
            "§7ᴡᴀɴᴛꜱ: §f" + NumberFormatter.format(order.getAmountRemaining())
                + " §7/ §f" + NumberFormatter.format(order.getAmountRequested()),
            "§7ᴘʀɪᴄᴇ/ᴜɴɪᴛ: §a" + NumberFormatter.formatPrice(order.getPricePerItem()),
            "§7ᴛᴏᴛᴀʟ ᴘᴏᴛᴇɴᴛɪᴀʟ: §a"
                + NumberFormatter.formatPrice(order.getPricePerItem() * order.getAmountRemaining()),
            "§7ᴇxᴘɪʀᴇꜱ: §e" + order.getFormattedExpiry(),
            "§8━━━━━━━━━━━━━━━━━━━━",
            "§eʟᴇꜰᴛ ᴄʟɪᴄᴋ §7ᴛᴏ ᴅᴇʟɪᴠᴇʀ ɪᴛᴇᴍꜱ"
        ));
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
            guiManager.openYourOrders(player, 0);
            return;
        }

        // Order slot
        int orderIndex = page * PAGE_SIZE + slot;
        if (slot < PAGE_SIZE && orderIndex < orders.size()) {
            Order order = orders.get(orderIndex);
            // Prevent buyer from selling to their own order
            if (order.getBuyerUUID().equals(player.getUniqueId())) {
                player.sendMessage(com.donutorders.DonutOrders.colorize(
                    "§cʏᴏᴜ ᴄᴀɴɴᴏᴛ ꜰᴜʟꜰɪʟʟ ʏᴏᴜʀ ᴏᴡɴ ᴏʀᴅᴇʀ."));
                return;
            }
            if (order.getStatus() != OrderStatus.ACTIVE) return;
            guiManager.openDeliverItems(player, order.getOrderId());
        }
    }
}

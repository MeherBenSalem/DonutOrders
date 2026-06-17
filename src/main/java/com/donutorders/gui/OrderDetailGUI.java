package com.donutorders.gui;

import com.donutorders.DonutOrders;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GUI: "ᴏʀᴅᴇʀ ᴅᴇᴛᴀɪʟꜱ" — the buyer's detail / management screen for a single order.
 *
 * <p>Layout (27 slots):
 * <pre>
 * [0–8]    Row 1 — fillers
 * [9]      Filler
 * [10]     Filler
 * [11]     "ᴄᴏʟʟᴇᴄᴛ" (only if stash has items or funds to refund)
 * [12]     Filler
 * [13]     Order summary item
 * [14]     Filler
 * [15]     "ᴄᴀɴᴄᴇʟ ᴏʀᴅᴇʀ" (only if ACTIVE)
 * [16]     Filler
 * [17]     Filler
 * [18]–[26] Row 3 — [22] = "ʙᴀᴄᴋ" button, rest fillers
 * </pre>
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
        super(Bukkit.createInventory(null, 27, "ᴏʀᴅᴇʀ ᴅᴇᴛᴀɪʟꜱ"));
        this.guiManager    = guiManager;
        this.order         = order;
        this.hasStashItems = hasStashItems;
        build();
    }

    private void build() {
        // Summary
        inventory.setItem(SLOT_SUMMARY, buildSummaryItem());

        // Collect button (shown if status is PENDING)
        boolean canCollect = order.getStatus() == OrderStatus.PENDING;

        if (canCollect) {
            inventory.setItem(SLOT_COLLECT, ItemUtils.createGuiItem(
                Material.CHEST,
                "§a§lᴄᴏʟʟᴇᴄᴛ",
                Arrays.asList(
                    "§7ᴏᴘᴇɴ ꜱᴛᴀꜱʜ ᴀɴᴅ ᴄᴏʟʟᴇᴄᴛ ɪᴛᴇᴍꜱ.",
                    hasStashItems ? "§e⬛ ꜱᴛᴀꜱʜ ʜᴀꜱ ɪᴛᴇᴍꜱ" : "§7ꜱᴛᴀꜱʜ ɪꜱ ᴇᴍᴘᴛʏ"
                )));
        }

        // Cancel button (only if active)
        if (order.getStatus() == OrderStatus.ACTIVE) {
            inventory.setItem(SLOT_CANCEL, ItemUtils.createGuiItem(
                Material.RED_WOOL,
                "§c§lᴄᴀɴᴄᴇʟ ᴏʀᴅᴇʀ",
                Arrays.asList(
                    "§7ᴄᴀɴᴄᴇʟ ᴛʜɪꜱ ᴏʀᴅᴇʀ.",
                    "§7ʀᴇᴍᴀɪɴɪɴɢ ꜰᴜɴᴅꜱ: §a"
                        + NumberFormatter.formatPrice(order.getRemainingFunds()) + " §7ʀᴇꜰᴜɴᴅᴇᴅ."
                )));
        }

        inventory.setItem(SLOT_BACK, ItemUtils.createGuiItem(
            Material.ARROW, "§7« ʙᴀᴄᴋ",
            Arrays.asList("§7ʀᴇᴛᴜʀɴ ᴛᴏ ʏᴏᴜʀ ᴏʀᴅᴇʀꜱ.")));

        fillEmpty();
    }

    private ItemStack buildSummaryItem() {
        String statusColor = switch (order.getStatus()) {
            case ACTIVE    -> "§a";
            case COMPLETED -> "§b";
            case EXPIRED   -> "§6";
            case CANCELLED -> "§c";
            case PENDING   -> "§e";
            case CLAIMED   -> "§8";
        };
        String statusName = switch (order.getStatus()) {
            case ACTIVE    -> "ᴀᴄᴛɪᴠᴇ";
            case COMPLETED -> "ᴄᴏᴍᴘʟᴇᴛᴇᴅ";
            case EXPIRED   -> "ᴇxᴘɪʀᴇᴅ";
            case CANCELLED -> "ᴄᴀɴᴄᴇʟʟᴇᴅ";
            case PENDING   -> "ᴘᴇɴᴅɪɴɢ ᴄᴏʟʟᴇᴄᴛɪᴏɴ";
            case CLAIMED   -> "ᴄʟᴀɪᴍᴇᴅ";
        };
        List<String> lore = new ArrayList<>(Arrays.asList(
            "§8━━━━━━━━━━━━━━━━━━━━",
            "§7ꜱᴛᴀᴛᴜꜱ: " + statusColor + statusName,
            "§7ᴘʀᴏɢʀᴇꜱꜱ: §f"
                + NumberFormatter.format(order.getAmountFulfilled())
                + " §7/ §f" + NumberFormatter.format(order.getAmountRequested()),
            "§7ᴘʀɪᴄᴇ/ᴜɴɪᴛ: §a" + NumberFormatter.formatPrice(order.getPricePerItem()),
            "§7ꜰᴜɴᴅꜱ ʜᴇʟᴅ: §a" + NumberFormatter.formatPrice(order.getRemainingFunds()),
            "§7ᴇxᴘɪʀᴇꜱ: §e" + order.getFormattedExpiry(),
            "§8━━━━━━━━━━━━━━━━━━━━"
        ));
        return ItemUtils.createGuiItem(
            order.getItemTemplate().getType(),
            "§f§l" + ItemUtils.prettyName(order.getItemTemplate().getType()),
            lore);
    }

    @Override
    public void handleClick(Player player, int slot, ItemStack clicked, ClickType type) {
        if (slot == SLOT_COLLECT) {
            guiManager.openCollectStash(player, order.getOrderId());
        } else if (slot == SLOT_CANCEL && order.getStatus() == OrderStatus.ACTIVE) {
            guiManager.getOrderManager().cancelOrder(player, order.getOrderId(),
                success -> {
                    if (success) {
                        String msg = DonutOrders.getInstance().getMessages()
                            .getString("order-cancelled", "&aᴏʀᴅᴇʀ ᴄᴀɴᴄᴇʟʟᴇᴅ. &f{0} §7ʀᴇꜰᴜɴᴅᴇᴅ.")
                            .replace("{0}", NumberFormatter.formatPrice(order.getRemainingFunds()));
                        player.sendMessage(DonutOrders.colorize(
                            DonutOrders.getInstance().getMessages()
                                .getString("prefix", "") + msg));
                    } else {
                        player.sendMessage(DonutOrders.colorize("§cꜰᴀɪʟᴇᴅ ᴛᴏ ᴄᴀɴᴄᴇʟ ᴏʀᴅᴇʀ."));
                    }
                    guiManager.openYourOrders(player, 0);
                });
        } else if (slot == SLOT_BACK) {
            guiManager.openYourOrders(player, 0);
        }
    }
}

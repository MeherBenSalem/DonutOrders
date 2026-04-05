package com.donutorders.gui;

import com.donutorders.DonutOrders;
import com.donutorders.manager.GUIManager;
import com.donutorders.model.Order;
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
 * GUI: "ᴄᴏɴꜰɪʀᴍ ᴅᴇʟɪᴠᴇʀʏ" — shows the seller a summary before committing.
 *
 * <p>Layout (27 slots):
 * <pre>
 * [0–8]    Row 1 — fillers
 * [9]      Filler
 * [10]     Filler
 * [11]     §a CONFIRM  (lime wool)
 * [12]     Filler
 * [13]     Summary item (paper / item icon)
 * [14]     Filler
 * [15]     §c CANCEL   (red wool)
 * [16]–[26] Fillers
 * </pre>
 */
public class ConfirmDeliveryGUI extends BaseGUI {

    private static final int SLOT_CONFIRM = 11;
    private static final int SLOT_SUMMARY = 13;
    private static final int SLOT_CANCEL  = 15;

    private final GUIManager guiManager;
    private final Order order;
    private final ItemStack[] items;   // snapshot from DeliverItemsGUI
    private final int deliverCount;
    private final double payout;

    public ConfirmDeliveryGUI(GUIManager guiManager, Order order, ItemStack[] items) {
        super(Bukkit.createInventory(null, 27, "ᴄᴏɴꜰɪʀᴍ ᴅᴇʟɪᴠᴇʀʏ"));
        this.guiManager    = guiManager;
        this.order         = order;
        this.items         = items;
        this.deliverCount  = countDelivery(items, order);
        this.payout        = order.getPricePerItem() * deliverCount;
        build();
    }

    private int countDelivery(ItemStack[] slots, Order o) {
        int total = 0;
        for (ItemStack item : slots) {
            if (ItemUtils.isSameMaterial(item, o.getItemTemplate()) && item != null) {
                total += item.getAmount();
            }
        }
        return Math.min(total, o.getAmountRemaining());
    }

    private void build() {
        inventory.setItem(SLOT_SUMMARY, ItemUtils.createGuiItem(
            order.getItemTemplate().getType(),
            "§f§l" + ItemUtils.prettyName(order.getItemTemplate().getType()),
            Arrays.asList(
                "§8━━━━━━━━━━━━━━━━━━━━",
                "§7ᴅᴇʟɪᴠᴇʀɪɴɢ: §f" + NumberFormatter.format(deliverCount),
                "§7ᴘᴀʏᴏᴜᴛ: §a" + NumberFormatter.formatPrice(payout),
                "§8━━━━━━━━━━━━━━━━━━━━"
            )));

        inventory.setItem(SLOT_CONFIRM, ItemUtils.createGuiItem(
            Material.LIME_WOOL, "§a§lᴄᴏɴꜰɪʀᴍ",
            Arrays.asList("§7ᴄʟɪᴄᴋ ᴛᴏ ᴅᴇʟɪᴠᴇʀ ᴀɴᴅ ʀᴇᴄᴇɪᴠᴇ ᴘᴀʏᴍᴇɴᴛ.")));

        inventory.setItem(SLOT_CANCEL, ItemUtils.createGuiItem(
            Material.RED_WOOL, "§c§lᴄᴀɴᴄᴇʟ",
            Arrays.asList("§7ɢᴏ ʙᴀᴄᴋ.")));

        fillEmpty();
    }

    @Override
    public void handleClick(Player player, int slot, ItemStack clicked, ClickType type) {
        if (slot == SLOT_CONFIRM) {
            guiManager.getOrderManager().fulfillOrder(player, order.getOrderId(), items,
                (success, errorMsg) -> {
                    if (success) {
                        String msg = DonutOrders.getInstance().getMessages()
                            .getString("delivery-success",
                                "&aᴅᴇʟɪᴠᴇʀᴇᴅ &f{0}× {1}&a. ʏᴏᴜ ᴇᴀʀɴᴇᴅ &f{2}&a.")
                            .replace("{0}", NumberFormatter.format(deliverCount))
                            .replace("{1}", ItemUtils.prettyName(order.getItemTemplate().getType()))
                            .replace("{2}", NumberFormatter.formatPrice(payout));
                        player.sendMessage(DonutOrders.colorize(
                            DonutOrders.getInstance().getMessages()
                                .getString("prefix", "") + msg));
                    } else {
                        player.sendMessage(DonutOrders.colorize("§c" + errorMsg));
                    }
                    guiManager.openPublicOrders(player, 0);
                });
        } else if (slot == SLOT_CANCEL) {
            guiManager.openPublicOrders(player, 0);
        }
    }
}

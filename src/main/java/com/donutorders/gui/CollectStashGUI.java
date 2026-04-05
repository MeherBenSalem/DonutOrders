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
 * GUI: "ᴄᴏʟʟᴇᴄᴛ" — shows the buyer what is in their virtual stash.
 *
 * <p>Layout (54 slots):
 * <pre>
 * [0–44]  Stash contents (display only — items given on "ᴄᴏʟʟᴇᴄᴛ ᴀʟʟ")
 * [45]–[48] Fillers
 * [49]    "ᴄᴏʟʟᴇᴄᴛ ᴀʟʟ" button
 * [50]–[52] Fillers
 * [53]    "ᴄʟᴏꜱᴇ" button
 * </pre>
 *
 * <p>Players cannot take items directly from slots — they click "ᴄᴏʟʟᴇᴄᴛ ᴀʟʟ"
 * which gives everything to their inventory (or drops at feet on overflow).
 */
public class CollectStashGUI extends BaseGUI {

    private static final int SLOT_COLLECT = 49;
    private static final int SLOT_CLOSE   = 53;
    private static final int DISPLAY_SLOTS = 45;

    private final GUIManager guiManager;
    private final Order order;

    public CollectStashGUI(GUIManager guiManager, Order order, ItemStack[] stash) {
        super(Bukkit.createInventory(null, 54, "ᴄᴏʟʟᴇᴄᴛ"));
        this.guiManager = guiManager;
        this.order      = order;
        build(stash);
    }

    private void build(ItemStack[] stash) {
        // Place stash items (cleaned copies — hide attributes/enchants)
        for (int i = 0; i < DISPLAY_SLOTS && i < stash.length; i++) {
            if (stash[i] != null && stash[i].getType() != Material.AIR) {
                inventory.setItem(i, ItemUtils.cleanedCopy(stash[i]));
            }
        }

        inventory.setItem(SLOT_COLLECT, ItemUtils.createGuiItem(
            Material.HOPPER,
            "§a§lᴄᴏʟʟᴇᴄᴛ ᴀʟʟ",
            Arrays.asList(
                "§7ɢɪᴠᴇ ᴀʟʟ ɪᴛᴇᴍꜱ ᴛᴏ ʏᴏᴜʀ ɪɴᴠᴇɴᴛᴏʀʏ.",
                "§7ᴏᴠᴇʀꜰʟᴏᴡ ᴡɪʟʟ ʙᴇ ᴅʀᴏᴘᴘᴇᴅ ᴀᴛ ʏᴏᴜʀ ꜰᴇᴇᴛ.")));

        inventory.setItem(SLOT_CLOSE, ItemUtils.createGuiItem(
            Material.BARRIER, "§c§lᴄʟᴏꜱᴇ",
            Arrays.asList("§7ᴄʟᴏꜱᴇ ᴛʜɪꜱ ᴍᴇɴᴜ.")));

        fillEmpty();
    }

    @Override
    public void handleClick(Player player, int slot, ItemStack clicked, ClickType type) {
        if (slot == SLOT_COLLECT) {
            guiManager.getOrderManager().collectStash(player, order.getOrderId(),
                success -> {
                    if (success) {
                        player.sendMessage(DonutOrders.colorize(
                            DonutOrders.getInstance().getMessages()
                                .getString("collect-success", "&aɪᴛᴇᴍꜱ ᴄᴏʟʟᴇᴄᴛᴇᴅ.")));
                    } else {
                        player.sendMessage(DonutOrders.colorize(
                            DonutOrders.getInstance().getMessages()
                                .getString("collect-nothing", "&7ɴᴏᴛʜɪɴɢ ᴛᴏ ᴄᴏʟʟᴇᴄᴛ.")));
                    }
                    // Reopen their orders list after collecting
                    guiManager.openYourOrders(player, 0);
                });
        } else if (slot == SLOT_CLOSE) {
            player.closeInventory();
        }
        // All other slots are locked — no item pickup allowed
    }
}

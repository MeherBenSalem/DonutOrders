package com.donutorders.gui;

import com.donutorders.manager.GUIManager;
import com.donutorders.model.Order;
import com.donutorders.util.ItemUtils;
import com.donutorders.util.MessageHelper;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/**
 * GUI: shows the buyer what is in their virtual stash.
 *
 * <p>Players cannot take items directly from slots — they click "Collect All"
 * which gives everything to their inventory (or drops at feet on overflow).
 */
public class CollectStashGUI extends BaseGUI {

    private static final int SLOT_COLLECT = 49;
    private static final int SLOT_CLOSE   = 53;
    private static final int DISPLAY_SLOTS = 45;

    private final GUIManager guiManager;
    private final Order order;

    public CollectStashGUI(GUIManager guiManager, Order order, ItemStack[] stash) {
        super(Bukkit.createInventory(null, 54,
                MessageHelper.get("gui.collect-stash.title", "ᴄᴏʟʟᴇᴄᴛ")));
        this.guiManager = guiManager;
        this.order      = order;
        build(stash);
    }

    private void build(ItemStack[] stash) {
        for (int i = 0; i < DISPLAY_SLOTS && i < stash.length; i++) {
            if (stash[i] != null && stash[i].getType() != Material.AIR) {
                inventory.setItem(i, ItemUtils.cleanedCopy(stash[i]));
            }
        }

        inventory.setItem(SLOT_COLLECT, ItemUtils.createGuiItem(
            Material.HOPPER,
            MessageHelper.get("gui.collect-stash.collect-all.name", "&a&lᴄᴏʟʟᴇᴄᴛ ᴀʟʟ"),
            MessageHelper.getList("gui.collect-stash.collect-all.lore")));

        inventory.setItem(SLOT_CLOSE, ItemUtils.createGuiItem(
            Material.BARRIER,
            MessageHelper.get("gui.collect-stash.close.name", "&c&lᴄʟᴏꜱᴇ"),
            MessageHelper.getList("gui.collect-stash.close.lore")));

        fillEmpty();
    }

    @Override
    public void handleClick(Player player, int slot, ItemStack clicked, ClickType type) {
        if (slot == SLOT_COLLECT) {
            guiManager.getOrderManager().collectStash(player, order.getOrderId(),
                success -> {
                    if (success) {
                        MessageHelper.send(player, "collect-success",
                            "&aɪᴛᴇᴍꜱ ᴄᴏʟʟᴇᴄᴛᴇᴅ ꜰʀᴏᴍ ꜱᴛᴀꜱʜ.");
                    } else {
                        MessageHelper.send(player, "collect-nothing",
                            "&7ɴᴏᴛʜɪɴɢ ᴛᴏ ᴄᴏʟʟᴇᴄᴛ.");
                    }
                    guiManager.openYourOrders(player, 0);
                });
        } else if (slot == SLOT_CLOSE) {
            player.closeInventory();
        }
    }
}

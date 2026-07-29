package com.donutorders.gui;

import com.donutorders.manager.GUIManager;
import com.donutorders.util.EnchantOrderUtils;
import com.donutorders.util.ItemUtils;
import com.donutorders.util.MessageHelper;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * GUI: enchant picker for enchanted-book buy orders.
 */
public class EnchantPickerGUI extends BaseGUI {

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_NEXT = 53;
    private static final int SLOT_BACK = 49;

    private final GUIManager guiManager;
    private final List<Enchantment> enchants;
    private final int page;
    private final int maxPage;

    public EnchantPickerGUI(GUIManager guiManager, int page) {
        super(Bukkit.createInventory(null, 54,
                MessageHelper.get("gui.enchant-picker.title", "ꜱᴇʟᴇᴄᴛ ᴇɴᴄʜᴀɴᴛ")));
        this.guiManager = guiManager;
        this.enchants   = EnchantOrderUtils.listBookEnchantments();
        this.page       = page;
        this.maxPage    = enchants.isEmpty() ? 0 : Math.max(0, (enchants.size() - 1) / PAGE_SIZE);
        build();
    }

    private void build() {
        int start = page * PAGE_SIZE;
        int end   = Math.min(start + PAGE_SIZE, enchants.size());

        for (int i = start; i < end; i++) {
            inventory.setItem(i - start, buildEnchantItem(enchants.get(i)));
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

        inventory.setItem(SLOT_BACK, ItemUtils.createGuiItem(
            Material.BARRIER,
            MessageHelper.get("gui.enchant-picker.back.name", "&c&lʙᴀᴄᴋ"),
            MessageHelper.getList("gui.enchant-picker.back.lore")));

        fillEmpty();
    }

    private ItemStack buildEnchantItem(Enchantment enchant) {
        ItemStack book = ItemUtils.cleanedCopy(EnchantOrderUtils.buildEnchantedBook(enchant, 1));
        ItemMeta meta = book.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MessageHelper.getNamed("gui.enchant-picker.enchant.name", "&f{enchant}",
                "enchant", EnchantOrderUtils.prettyEnchantName(enchant)));
            meta.setLore(MessageHelper.getList("gui.enchant-picker.enchant.lore"));
            meta.addItemFlags(ItemFlag.values());
            book.setItemMeta(meta);
        }
        return book;
    }

    @Override
    public void handleClick(Player player, int slot, ItemStack clicked, ClickType type) {
        if (slot == SLOT_PREV && page > 0) {
            guiManager.openEnchantPicker(player, page - 1);
            return;
        }
        if (slot == SLOT_NEXT && page < maxPage) {
            guiManager.openEnchantPicker(player, page + 1);
            return;
        }
        if (slot == SLOT_BACK) {
            guiManager.openNewOrderPicker(player, 0);
            return;
        }

        int enchantIndex = page * PAGE_SIZE + slot;
        if (slot < PAGE_SIZE && enchantIndex < enchants.size()) {
            Enchantment selected = enchants.get(enchantIndex);
            GUIManager.PlayerGUIState state = guiManager.getState(player.getUniqueId());
            if (state != null) {
                state.enchantPickerPage = page;
            }
            guiManager.openEnchantLevel(player, selected);
        }
    }
}

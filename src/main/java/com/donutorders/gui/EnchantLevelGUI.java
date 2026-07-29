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

/**
 * GUI: level picker for a selected enchanted-book enchant.
 */
public class EnchantLevelGUI extends BaseGUI {

    private static final int SLOT_BACK = 49;

    private final GUIManager guiManager;
    private final Enchantment enchantment;
    private final int maxLevel;

    public EnchantLevelGUI(GUIManager guiManager, Enchantment enchantment) {
        super(Bukkit.createInventory(null, 54,
                MessageHelper.get("gui.enchant-level.title", "ꜱᴇʟᴇᴄᴛ ʟᴇᴠᴇʟ")));
        this.guiManager  = guiManager;
        this.enchantment = enchantment;
        this.maxLevel    = enchantment.getMaxLevel();
        build();
    }

    private void build() {
        String enchantName = EnchantOrderUtils.prettyEnchantName(enchantment);

        for (int level = 1; level <= maxLevel; level++) {
            inventory.setItem(level - 1, buildLevelItem(enchantName, level));
        }

        inventory.setItem(SLOT_BACK, ItemUtils.createGuiItem(
            Material.BARRIER,
            MessageHelper.get("gui.enchant-level.back.name", "&c&lʙᴀᴄᴋ"),
            MessageHelper.getList("gui.enchant-level.back.lore")));

        fillEmpty();
    }

    private ItemStack buildLevelItem(String enchantName, int level) {
        ItemStack book = ItemUtils.cleanedCopy(
                EnchantOrderUtils.buildEnchantedBook(enchantment, level));
        ItemMeta meta = book.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MessageHelper.getNamed("gui.enchant-level.level.name",
                "&f{enchant} {level}",
                "enchant", enchantName,
                "level", EnchantOrderUtils.toRoman(level)));
            meta.setLore(MessageHelper.getList("gui.enchant-level.level.lore"));
            meta.addItemFlags(ItemFlag.values());
            book.setItemMeta(meta);
        }
        return book;
    }

    @Override
    public void handleClick(Player player, int slot, ItemStack clicked, ClickType type) {
        if (slot == SLOT_BACK) {
            GUIManager.PlayerGUIState state = guiManager.getState(player.getUniqueId());
            int returnPage = state != null ? state.enchantPickerPage : 0;
            guiManager.openEnchantPicker(player, returnPage);
            return;
        }

        int level = slot + 1;
        if (slot >= 0 && slot < maxLevel) {
            ItemStack template = EnchantOrderUtils.buildEnchantedBook(enchantment, level);
            NewOrderGUI.beginAmountPriceFlow(guiManager, player, template);
        }
    }
}

package com.donutorders.util;

import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Helpers for creating and describing enchanted-book buy orders.
 */
public final class EnchantOrderUtils {

    private static final String[] ROMAN = {
            "", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"
    };

    private static List<Enchantment> cachedBookEnchantments;

    private EnchantOrderUtils() {}

    /** Returns {@code true} when the material requires the enchant picker flow. */
    public static boolean needsEnchantPicker(Material material) {
        return material == Material.ENCHANTED_BOOK;
    }

    /**
     * Returns all registry enchantments that can be stored on an enchanted book,
     * sorted alphabetically by {@link #prettyEnchantName(Enchantment)}.
     */
    public static List<Enchantment> listBookEnchantments() {
        if (cachedBookEnchantments != null) {
            return cachedBookEnchantments;
        }

        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        List<Enchantment> enchants = new ArrayList<>();
        for (Enchantment enchant : Registry.ENCHANTMENT) {
            if (enchant.getMaxLevel() > 0 && enchant.canEnchantItem(book)) {
                enchants.add(enchant);
            }
        }
        enchants.sort(Comparator.comparing(EnchantOrderUtils::prettyEnchantName));
        cachedBookEnchantments = List.copyOf(enchants);
        return cachedBookEnchantments;
    }

    /** Builds a single-enchant enchanted book template for order creation. */
    public static ItemStack buildEnchantedBook(Enchantment enchantment, int level) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK, 1);
        ItemMeta meta = book.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storageMeta) {
            storageMeta.addStoredEnchant(enchantment, level, true);
            book.setItemMeta(storageMeta);
        }
        return book;
    }

    /** Returns a title-cased display name for an enchantment, e.g. {@code Sharpness}. */
    public static String prettyEnchantName(Enchantment enchantment) {
        if (enchantment == null || enchantment.getKey() == null) {
            return "";
        }
        return prettyKey(enchantment.getKey().getKey());
    }

    /**
     * Returns a human-readable order item name.
     * Enchanted books with one stored enchant become e.g. {@code Sharpness V Enchanted Book}.
     */
    public static String describeOrderItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return "";
        }
        if (item.getType() == Material.ENCHANTED_BOOK) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof EnchantmentStorageMeta storageMeta) {
                Map<Enchantment, Integer> stored = storageMeta.getStoredEnchants();
                if (stored.size() == 1) {
                    Map.Entry<Enchantment, Integer> entry = stored.entrySet().iterator().next();
                    return prettyEnchantName(entry.getKey())
                            + " " + toRoman(entry.getValue())
                            + " Enchanted Book";
                }
            }
            return ItemUtils.prettyName(Material.ENCHANTED_BOOK);
        }
        return ItemUtils.prettyName(item.getType());
    }

    /** Converts levels 1–10 to Roman numerals; other levels use decimal strings. */
    public static String toRoman(int level) {
        if (level >= 1 && level < ROMAN.length) {
            return ROMAN[level];
        }
        return String.valueOf(level);
    }

    private static String prettyKey(String key) {
        String raw = key.replace('_', ' ').toLowerCase();
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : raw.toCharArray()) {
            sb.append(cap ? Character.toUpperCase(c) : c);
            cap = (c == ' ');
        }
        return sb.toString();
    }
}

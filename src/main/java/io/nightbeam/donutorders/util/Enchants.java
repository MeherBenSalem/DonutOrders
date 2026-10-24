package io.nightbeam.donutorders.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Enchantments across every supported version. {@code Enchantment} changed from a class to an interface and its
 * registry moved, so nothing here calls an instance method on it directly: keys come through {@link Keyed}, the
 * level cap through reflection, and the registry is looked up by name. The list is read from the server's
 * registry, so new enchantments (and data-pack ones) appear without an update.
 */
public final class Enchants {

    private static volatile List<Enchantment> cache;

    private Enchants() {
    }

    /** Every enchantment that can go on a book, sorted by name. */
    public static List<Enchantment> all() {
        List<Enchantment> known = cache;
        if (known != null) return known;
        List<Enchantment> found = new ArrayList<>();
        for (Object entry : registry()) {
            if (entry instanceof Enchantment enchantment && maxLevel(enchantment) > 0) found.add(enchantment);
        }
        found.sort(Comparator.comparing(Enchants::name));
        cache = List.copyOf(found);
        return cache;
    }

    public static void clearCache() {
        cache = null;
    }

    public static Enchantment byKey(String key) {
        if (key == null || key.isBlank()) return null;
        NamespacedKey namespaced = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
        if (namespaced == null) return null;
        for (Enchantment enchantment : all()) if (namespaced.equals(((Keyed) enchantment).getKey())) return enchantment;
        return null;
    }

    /** "minecraft:sharpness". */
    public static String key(Enchantment enchantment) {
        try {
            return ((Keyed) enchantment).getKey().toString();
        } catch (RuntimeException | LinkageError unavailable) {
            return String.valueOf(enchantment).toLowerCase(Locale.ROOT);
        }
    }

    /** "Sharpness" (English, for logs, search and webhooks). */
    public static String name(Enchantment enchantment) {
        String key = key(enchantment);
        String path = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
        StringBuilder out = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /** The client translation key ("enchantment.minecraft.sharpness"), so players read their own language. */
    public static String translationKey(Enchantment enchantment) {
        String key = key(enchantment);
        int colon = key.indexOf(':');
        return colon < 0 ? "enchantment.minecraft." + key : "enchantment." + key.substring(0, colon) + "." + key.substring(colon + 1);
    }

    public static int maxLevel(Enchantment enchantment) {
        try {
            Method method = enchantment.getClass().getMethod("getMaxLevel");
            method.setAccessible(true);
            return ((Number) method.invoke(enchantment)).intValue();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return 1;
        }
    }

    /** "V"; nothing for single-level enchantments such as Mending. */
    public static String levelSuffix(Enchantment enchantment, int level) {
        if (level == 1 && maxLevel(enchantment) <= 1) return "";
        return " " + roman(level);
    }

    public static String roman(int level) {
        if (level < 1 || level > 10) return String.valueOf(level);
        return new String[] {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"}[level - 1];
    }

    public static ItemStack book(Enchantment enchantment, int level) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storage) {
            storage.addStoredEnchant(enchantment, level, true);
            book.setItemMeta(storage);
        }
        return book;
    }

    /** Stored enchantments of a book, or an empty map. */
    public static Map<Enchantment, Integer> stored(ItemStack item) {
        try {
            if (item == null || !item.hasItemMeta()) return Map.of();
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof EnchantmentStorageMeta storage && storage.hasStoredEnchants()) {
                return new LinkedHashMap<>(storage.getStoredEnchants());
            }
        } catch (RuntimeException | LinkageError unreadable) {
            // Treated as a plain book.
        }
        return Map.of();
    }

    /** Applied enchantments of a tool or armor piece, or an empty map. */
    public static Map<Enchantment, Integer> applied(ItemStack item) {
        try {
            if (item == null || !item.hasItemMeta()) return Map.of();
            ItemMeta meta = item.getItemMeta();
            return meta != null && meta.hasEnchants() ? new LinkedHashMap<>(meta.getEnchants()) : Map.of();
        } catch (RuntimeException | LinkageError unreadable) {
            return Map.of();
        }
    }

    private static Iterable<?> registry() {
        try {
            Field field = org.bukkit.Registry.class.getField("ENCHANTMENT");
            Object registry = field.get(null);
            if (registry instanceof Iterable<?> iterable) return iterable;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError moved) {
            // Fall through to the server registry.
        }
        try {
            Method method = Bukkit.getServer().getClass().getMethod("getRegistry", Class.class);
            Object registry = method.invoke(Bukkit.getServer(), Enchantment.class);
            if (registry instanceof Iterable<?> iterable) return iterable;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // No registry: no enchantment orders.
        }
        return List.of();
    }
}

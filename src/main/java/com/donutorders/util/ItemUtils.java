package com.donutorders.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Utility helpers for ItemStack manipulation used throughout the GUI layer.
 *
 * <p>All methods that touch ItemMeta add every available {@link ItemFlag} so
 * that the vanilla attribute/enchantment lore lines are never shown to players.
 * This produces the "clean" look required by the GUI spec.
 */
public final class ItemUtils {

    /** Pre-built array of all item flags for convenience. */
    private static final ItemFlag[] ALL_FLAGS = ItemFlag.values();

    private ItemUtils() {}

    // ──────────────────────────────────────────────────────────────────────────
    //  Display helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Creates a GUI display item with a custom name and lore, with all
     * vanilla attribute / enchantment lines hidden.
     *
     * @param material the material
     * @param name     display name (supports §-color codes)
     * @param lore     lore lines (may be null or empty)
     * @return a clean display ItemStack
     */
    public static ItemStack createGuiItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(name);
        if (lore != null && !lore.isEmpty()) {
            meta.setLore(lore);
        }
        meta.addItemFlags(ALL_FLAGS);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Creates a single-colour filler pane used to pad GUI borders.
     *
     * @param material typically {@link Material#GRAY_STAINED_GLASS_PANE}
     * @return an invisible filler item (empty name, no lore)
     */
    public static ItemStack filler(Material material) {
        return createGuiItem(material, " ", null);
    }

    /**
     * Applies all {@link ItemFlag}s to {@code item} in-place, hiding attribute
     * text, enchantments, potion effects, and other clutter.
     *
     * @param item the item to clean (mutated)
     * @return the same item, for chaining
     */
    public static ItemStack cleanItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.addItemFlags(ALL_FLAGS);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Creates a cleaned <em>copy</em> of {@code item} — does not mutate the
     * original. Useful when displaying player-supplied items in a GUI slot
     * without affecting what the player has in their hand.
     *
     * @param item source item
     * @return cleaned copy
     */
    public static ItemStack cleanedCopy(ItemStack item) {
        if (item == null) return null;
        ItemStack copy = item.clone();
        return cleanItem(copy);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Comparison helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when both stacks share the same {@link Material},
     * ignoring amount, meta, and display name. Used to validate if a seller's
     * item satisfies a buy order for a particular material type.
     */
    public static boolean isSameMaterial(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        return a.getType() == b.getType();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Serialisation — used for stash persistence in SQLite
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Serialises an {@link ItemStack} to a Base64-encoded string that can be
     * stored in a TEXT column.
     *
     * @param item the item to serialize (may be null → returns "")
     * @return Base64 string, or empty string for null/AIR items
     */
    public static String serializeItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return "";
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeObject(item);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Deserialises a Base64-encoded string produced by {@link #serializeItem}.
     *
     * @param data Base64 string  
     * @return the ItemStack, or {@code null} if the data is empty or corrupt
     */
    public static ItemStack deserializeItem(String data) {
        if (data == null || data.isEmpty()) return null;
        try (ByteArrayInputStream bais =
                     new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
            return (ItemStack) bois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Serialises an array of ItemStacks (the 54-slot virtual stash) to a
     * Base64-encoded string. Null / AIR slots are preserved as empty strings so
     * slot indices are kept intact on deserialisation.
     *
     * @param items array to serialize
     * @return Base64 string
     */
    public static String serializeItemArray(ItemStack[] items) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeInt(items.length);
            for (ItemStack item : items) {
                boos.writeObject(item);
            }
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Deserialises an item array produced by {@link #serializeItemArray}.
     *
     * @param data  Base64 string
     * @param size  expected array length (used when data is empty)
     * @return item array; empty item slots contain {@code null}
     */
    public static ItemStack[] deserializeItemArray(String data, int size) {
        if (data == null || data.isEmpty()) {
            return new ItemStack[size];
        }
        try (ByteArrayInputStream bais =
                     new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
            int length = bois.readInt();
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                items[i] = (ItemStack) bois.readObject();
            }
            return items;
        } catch (IOException | ClassNotFoundException e) {
            return new ItemStack[size];
        }
    }

    /**
     * Returns the total number of non-null, non-AIR items in an array.
     */
    public static int countItems(ItemStack[] items) {
        int total = 0;
        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR) {
                total += item.getAmount();
            }
        }
        return total;
    }

    /**
     * Counts items in {@code items} that match the material of {@code template}.
     */
    public static int countMatchingItems(ItemStack[] items, ItemStack template) {
        int total = 0;
        for (ItemStack item : items) {
            if (isSameMaterial(item, template)
                    && item != null && item.getType() != Material.AIR) {
                total += item.getAmount();
            }
        }
        return total;
    }

    /**
     * Returns a pretty, title-cased name for a {@link Material},
     * e.g. {@code IRON_INGOT} → {@code "Iron Ingot"}.
     */
    public static String prettyName(Material material) {
        String raw = material.name().replace('_', ' ').toLowerCase();
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : raw.toCharArray()) {
            sb.append(cap ? Character.toUpperCase(c) : c);
            cap = (c == ' ');
        }
        return sb.toString();
    }

    /**
     * Returns a human-readable name for an order item template, including
     * enchanted-book enchants when present.
     */
    public static String describeOrderItem(ItemStack item) {
        return EnchantOrderUtils.describeOrderItem(item);
    }
}

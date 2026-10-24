package io.nightbeam.donutorders.service;

import io.nightbeam.donutorders.model.MatchMode;
import io.nightbeam.donutorders.util.Enchants;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Turns what a player wants into an order template, its match mode and a key that groups equal orders for
 * price insight ("DIAMOND", "ENCHANTED_BOOK:minecraft:sharpness:5", "EXACT:POTION:3fa2c1d09b7e").
 */
public final class ItemKeys {

    /** Items whose material says too little: they are ordered by holding one (exact match). */
    public static final Set<String> EXACT_ONLY = Set.of("POTION", "SPLASH_POTION", "LINGERING_POTION", "TIPPED_ARROW",
            "SUSPICIOUS_STEW", "GOAT_HORN", "OMINOUS_BOTTLE", "WRITTEN_BOOK", "FILLED_MAP", "PLAYER_HEAD", "FIREWORK_STAR",
            "SPAWNER", "TRIAL_SPAWNER", "PAINTING", "INSTRUMENT");

    public record Spec(ItemStack template, MatchMode mode, String key) {
    }

    private ItemKeys() {
    }

    /** A plain material order. */
    public static Spec material(Material material) {
        ItemStack template = new ItemStack(material);
        return new Spec(template, MatchMode.TYPE, material.name());
    }

    /** An enchanted book with one enchantment. */
    public static Spec book(Enchantment enchantment, int level) {
        return new Spec(Enchants.book(enchantment, level), MatchMode.BOOK,
                "ENCHANTED_BOOK:" + Enchants.key(enchantment) + ":" + level);
    }

    /** The exact item (amount aside). */
    public static Spec exact(ItemStack item) {
        ItemStack template = item.clone();
        template.setAmount(1);
        return new Spec(template, MatchMode.EXACT, exactKey(template));
    }

    /**
     * What ordering a held item means: a plain item becomes a material order, a book with one enchantment a book
     * order, anything else an exact order.
     */
    public static Spec fromHeld(ItemStack held) {
        Map<Enchantment, Integer> stored = Enchants.stored(held);
        if (held.getType() == Material.ENCHANTED_BOOK && stored.size() == 1 && !renamedOrLore(held)) {
            Map.Entry<Enchantment, Integer> entry = stored.entrySet().iterator().next();
            return book(entry.getKey(), entry.getValue());
        }
        if (isPlain(held) && !EXACT_ONLY.contains(held.getType().name())) return material(held.getType());
        return exact(held);
    }

    /** True when the item is just its material: no name, enchantments or data (wear is ignored). */
    public static boolean isPlain(ItemStack item) {
        try {
            if (!item.hasItemMeta()) return true;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return true;
            if (meta.hasLore()) return false;
            if (meta instanceof Damageable damageable && damageable.hasDamage()) damageable.setDamage(0);
            return org.bukkit.Bukkit.getItemFactory().equals(meta, org.bukkit.Bukkit.getItemFactory().getItemMeta(item.getType()));
        } catch (RuntimeException | LinkageError unreadable) {
            return false;
        }
    }

    /** The key of an order made from a stored template (used when importing 1.x orders). */
    public static Spec of(ItemStack template, MatchMode mode) {
        return switch (mode) {
            case TYPE -> material(template.getType());
            case BOOK -> {
                Map<Enchantment, Integer> stored = Enchants.stored(template);
                if (stored.isEmpty()) yield material(template.getType());
                Map.Entry<Enchantment, Integer> entry = stored.entrySet().iterator().next();
                yield book(entry.getKey(), entry.getValue());
            }
            case EXACT -> exact(template);
        };
    }

    static String exactKey(ItemStack template) {
        String material = template.getType().name();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(template.serializeAsBytes());
            return "EXACT:" + material + ":" + HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException | RuntimeException | LinkageError unavailable) {
            return "EXACT:" + material;
        }
    }

    private static boolean renamedOrLore(ItemStack item) {
        try {
            ItemMeta meta = item.getItemMeta();
            return meta != null && (meta.hasDisplayName() || meta.hasLore());
        } catch (RuntimeException | LinkageError unreadable) {
            return true;
        }
    }
}

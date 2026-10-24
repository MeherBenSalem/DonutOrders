package io.nightbeam.donutorders.service;

import io.nightbeam.donutorders.model.MatchMode;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.util.Enchants;
import io.nightbeam.donutorders.util.ShulkerBoxSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Decides which delivered items an order accepts, and opens shulker boxes to take what it wants out of them.
 * Containers are only accepted as items when they are empty, so a full box can never be "sold" as an empty one.
 */
public final class ItemMatcher {

    /** Items taken for an order and everything to give back (the rest, including opened boxes). */
    public record Extraction(List<ItemStack> matched, List<ItemStack> returned) {

        public int units() {
            int units = 0;
            for (ItemStack stack : matched) units += stack.getAmount();
            return units;
        }
    }

    private final Supplier<FileConfiguration> config;

    public ItemMatcher(Supplier<FileConfiguration> config) {
        this.config = config;
    }

    public boolean matches(Order order, ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        ItemStack template = order.template();
        return switch (order.mode()) {
            case EXACT -> exact(template, item);
            case BOOK -> item.getType() == Material.ENCHANTED_BOOK && book(template, item) && acceptsMeta(item, false);
            case TYPE -> item.getType() == template.getType() && acceptsMeta(item, true);
        };
    }

    /** True when the item is a container the order would open (a filled shulker box, unless boxes are ordered). */
    public boolean opens(Order order, ItemStack item) {
        return config.get().getBoolean("delivery.unpack-shulker-boxes", true) && ShulkerBoxSupport.isShulkerBox(item)
                && !ShulkerBoxSupport.isShulkerBox(order.template()) && !ShulkerBoxSupport.getContents(item).isEmpty();
    }

    /** Whether an item may be put in the delivery menu of this order: it matches, or is a box holding a match. */
    public boolean accepts(Order order, ItemStack item) {
        if (matches(order, item)) return true;
        if (!opens(order, item)) return false;
        for (ItemStack content : ShulkerBoxSupport.getContents(item)) if (matches(order, content)) return true;
        return false;
    }

    /**
     * Takes up to {@code cap} matching items from the offered stacks. Loose items are taken first, then the
     * contents of shulker boxes; boxes go back with whatever the order did not take still inside.
     */
    public Extraction extract(Order order, List<ItemStack> offered, int cap) {
        List<ItemStack> matched = new ArrayList<>();
        List<ItemStack> returned = new ArrayList<>();
        int left = Math.max(0, cap);
        List<ItemStack> boxes = new ArrayList<>();
        for (ItemStack stack : offered) {
            if (stack == null || stack.getType().isAir()) continue;
            if (matches(order, stack)) {
                left = take(stack.clone(), left, matched, returned);
            } else if (opens(order, stack)) {
                boxes.add(stack);
            } else {
                returned.add(stack.clone());
            }
        }
        for (ItemStack box : boxes) {
            if (left <= 0 || box.getAmount() != 1) {
                returned.add(box.clone());
                continue;
            }
            ItemStack copy = box.clone();
            ItemMeta meta = copy.getItemMeta();
            if (!(meta instanceof BlockStateMeta stateMeta) || !(stateMeta.getBlockState() instanceof ShulkerBox shulker)) {
                returned.add(copy);
                continue;
            }
            ItemStack[] contents = shulker.getInventory().getContents();
            boolean changed = false;
            for (int slot = 0; slot < contents.length && left > 0; slot++) {
                ItemStack content = contents[slot];
                if (content == null || !matches(order, content)) continue;
                int take = Math.min(left, content.getAmount());
                ItemStack taken = content.clone();
                taken.setAmount(take);
                matched.add(taken);
                left -= take;
                if (take == content.getAmount()) {
                    contents[slot] = null;
                } else {
                    ItemStack rest = content.clone();
                    rest.setAmount(content.getAmount() - take);
                    contents[slot] = rest;
                }
                changed = true;
            }
            if (changed) {
                shulker.getInventory().setContents(contents);
                stateMeta.setBlockState((BlockState) shulker);
                copy.setItemMeta(stateMeta);
            }
            returned.add(copy);
        }
        return new Extraction(matched, returned);
    }

    /** Counts what {@link #extract} would take, without copying anything. */
    public int count(Order order, List<ItemStack> offered) {
        int units = 0;
        for (ItemStack stack : offered) {
            if (stack == null || stack.getType().isAir()) continue;
            if (matches(order, stack)) {
                units += stack.getAmount();
            } else if (opens(order, stack) && stack.getAmount() == 1) {
                for (ItemStack content : ShulkerBoxSupport.getContents(stack)) if (matches(order, content)) units += content.getAmount();
            }
        }
        return units;
    }

    private static int take(ItemStack stack, int left, List<ItemStack> matched, List<ItemStack> returned) {
        if (left <= 0) {
            returned.add(stack);
            return 0;
        }
        if (stack.getAmount() <= left) {
            matched.add(stack);
            return left - stack.getAmount();
        }
        ItemStack part = stack.clone();
        part.setAmount(left);
        matched.add(part);
        ItemStack rest = stack.clone();
        rest.setAmount(stack.getAmount() - left);
        returned.add(rest);
        return 0;
    }

    private boolean exact(ItemStack template, ItemStack item) {
        if (item.getType() != template.getType()) return false;
        String customId = customId(template);
        if (customId != null) return customId.equals(customId(item));
        ItemStack copy = item.clone();
        copy.setAmount(1);
        return template.isSimilar(copy);
    }

    private boolean book(ItemStack template, ItemStack item) {
        Map<Enchantment, Integer> wanted = Enchants.stored(template);
        Map<Enchantment, Integer> offered = Enchants.stored(item);
        if (wanted.isEmpty() || offered.isEmpty()) return wanted.isEmpty() && offered.isEmpty();
        FileConfiguration cfg = config.get();
        boolean higher = cfg.getBoolean("matching.books.accept-higher-levels", false);
        boolean extra = cfg.getBoolean("matching.books.accept-extra-enchantments", false);
        if (!extra && offered.size() != wanted.size()) return false;
        for (Map.Entry<Enchantment, Integer> entry : wanted.entrySet()) {
            Integer level = null;
            String key = Enchants.key(entry.getKey());
            for (Map.Entry<Enchantment, Integer> candidate : offered.entrySet()) {
                if (Enchants.key(candidate.getKey()).equals(key)) level = candidate.getValue();
            }
            if (level == null) return false;
            if (higher ? level < entry.getValue() : level.intValue() != entry.getValue().intValue()) return false;
        }
        return true;
    }

    /**
     * The tolerance rules for material and book orders: names, wear, enchantments, plugin data, lore and
     * containers. Exact orders compare the whole item instead.
     */
    private boolean acceptsMeta(ItemStack item, boolean typeOrder) {
        ItemMeta meta;
        try {
            if (!item.hasItemMeta()) return true;
            meta = item.getItemMeta();
        } catch (RuntimeException | LinkageError unreadable) {
            return false;
        }
        if (meta == null) return true;
        FileConfiguration cfg = config.get();
        if (meta.hasDisplayName() && !cfg.getBoolean("matching.accept-renamed", false)) return false;
        if (typeOrder && meta instanceof Damageable damageable && damageable.hasDamage() && !cfg.getBoolean("matching.accept-damaged", false)) {
            return false;
        }
        if (typeOrder && meta.hasEnchants() && !cfg.getBoolean("matching.accept-enchanted", true)) return false;
        if (!cfg.getBoolean("matching.accept-custom-items", false) && isCustom(meta, cfg)) return false;
        if (meta instanceof BlockStateMeta stateMeta && ShulkerBoxSupport.isShulkerBox(item)) {
            try {
                if (stateMeta.hasBlockState() && !ShulkerBoxSupport.getContents(item).isEmpty()) return false;
            } catch (RuntimeException | LinkageError unreadable) {
                return false;
            }
        }
        try {
            if (meta instanceof BundleMeta bundle && bundle.hasItems()) return false;
        } catch (RuntimeException | LinkageError noBundles) {
            // Bundles are experimental on 1.20.x.
        }
        return true;
    }

    /** Custom model data, plugin data that is not on the ignore list, or lore (unless lore is ignored). */
    private static boolean isCustom(ItemMeta meta, FileConfiguration cfg) {
        try {
            if (meta.hasCustomModelData()) return true;
        } catch (RuntimeException | LinkageError unsupported) {
            // Newer servers moved custom model data into a component; plugin data still tells.
        }
        if (meta.hasLore() && !cfg.getBoolean("matching.ignore-lore", false)) return true;
        List<String> ignored = cfg.getStringList("matching.ignored-plugin-data");
        for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
            boolean skip = false;
            for (String pattern : ignored) {
                String lower = pattern.toLowerCase(Locale.ROOT);
                if (lower.endsWith(":*") ? key.getNamespace().equals(lower.substring(0, lower.length() - 2)) : key.toString().equals(lower)) {
                    skip = true;
                    break;
                }
            }
            if (!skip) return true;
        }
        return false;
    }

    /**
     * The id a custom-item plugin stores on its items (for example {@code nexo:id}), so exact orders for custom
     * items match by id even when a plugin rewrites the lore or durability.
     */
    private String customId(ItemStack item) {
        List<String> keys = config.get().getStringList("matching.custom-item-ids");
        if (keys.isEmpty()) return null;
        try {
            if (!item.hasItemMeta()) return null;
            PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
            for (String key : keys) {
                NamespacedKey namespaced = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
                if (namespaced != null && data.has(namespaced, PersistentDataType.STRING)) {
                    return key + "=" + data.get(namespaced, PersistentDataType.STRING);
                }
            }
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
        return null;
    }
}

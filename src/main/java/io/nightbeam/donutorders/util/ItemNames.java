package io.nightbeam.donutorders.util;

import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Item names for messages. Renamed items keep their styled name; everything else is a translatable
 * component, so every player reads the name in their own client language. Enchanted books name their
 * enchantments: "Enchanted Book (Mending)".
 */
public final class ItemNames {

    private ItemNames() {
    }

    /** A trusted MiniMessage fragment for {@code %item%} placeholders. */
    public static String fragment(ItemStack item) {
        Component custom = customName(item);
        if (custom != null) {
            return MessageUtil.trusted(MiniMessage.miniMessage().serialize(custom));
        }
        StringBuilder out = new StringBuilder("<lang:").append(translationKey(item)).append('>');
        Map<Enchantment, Integer> stored = Enchants.stored(item);
        if (!stored.isEmpty()) {
            out.append(" (");
            boolean first = true;
            for (Map.Entry<Enchantment, Integer> entry : stored.entrySet()) {
                if (!first) out.append(", ");
                first = false;
                out.append("<lang:").append(Enchants.translationKey(entry.getKey())).append('>')
                        .append(Enchants.levelSuffix(entry.getKey(), entry.getValue()));
            }
            out.append(')');
        }
        return MessageUtil.trusted(out.toString());
    }

    public static Component component(ItemStack item) {
        Component custom = customName(item);
        if (custom != null) return custom;
        Component name = Component.translatable(translationKey(item));
        Map<Enchantment, Integer> stored = Enchants.stored(item);
        if (stored.isEmpty()) return name;
        name = name.append(Component.text(" ("));
        boolean first = true;
        for (Map.Entry<Enchantment, Integer> entry : stored.entrySet()) {
            if (!first) name = name.append(Component.text(", "));
            first = false;
            name = name.append(Component.translatable(Enchants.translationKey(entry.getKey())))
                    .append(Component.text(Enchants.levelSuffix(entry.getKey(), entry.getValue())));
        }
        return name.append(Component.text(")"));
    }

    /** English plain-text name for logs, webhooks and search. */
    public static String plain(ItemStack item) {
        Component custom = customName(item);
        if (custom != null) return PlainTextComponentSerializer.plainText().serialize(custom);
        Map<Enchantment, Integer> stored = Enchants.stored(item);
        if (stored.isEmpty()) return pretty(item.getType());
        StringBuilder out = new StringBuilder(pretty(item.getType())).append(" (");
        boolean first = true;
        for (Map.Entry<Enchantment, Integer> entry : stored.entrySet()) {
            if (!first) out.append(", ");
            first = false;
            out.append(Enchants.name(entry.getKey())).append(Enchants.levelSuffix(entry.getKey(), entry.getValue()));
        }
        return out.append(')').toString();
    }

    public static String pretty(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static Component customName(ItemStack item) {
        try {
            if (!item.hasItemMeta()) return null;
            ItemMeta meta = item.getItemMeta();
            return meta != null && meta.hasDisplayName() ? meta.displayName() : null;
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    static String translationKey(ItemStack item) {
        try {
            return item.translationKey();
        } catch (RuntimeException | LinkageError unavailable) {
            Material material = item.getType();
            String path = material.name().toLowerCase(Locale.ROOT);
            return (material.isBlock() ? "block.minecraft." : "item.minecraft.") + path;
        }
    }
}

package io.nightbeam.donutorders.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Menu buttons. Names and lore are shown without Minecraft's default italics; lore defaults to gray. */
public final class ItemBuilder {

    private final ItemStack itemStack;

    private ItemBuilder(Material material) {
        this.itemStack = new ItemStack(material);
    }

    private ItemBuilder(ItemStack itemStack) {
        this.itemStack = itemStack.clone();
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(material);
    }

    public static ItemBuilder of(ItemStack itemStack) {
        return new ItemBuilder(itemStack);
    }

    public ItemBuilder name(Component component) {
        editMeta(meta -> meta.displayName(MessageUtil.noItalic(component)));
        return this;
    }

    public ItemBuilder lore(List<Component> lore) {
        List<Component> lines = new ArrayList<>(lore.size());
        for (Component line : lore) lines.add(MessageUtil.loreLine(line));
        editMeta(meta -> meta.lore(lines));
        return this;
    }

    public ItemBuilder addFlags(ItemFlag... flags) {
        editMeta(meta -> meta.addItemFlags(flags));
        return this;
    }

    public ItemBuilder hideAll() {
        return addFlags(ItemFlag.values());
    }

    /** Adds the enchantment glint without showing an enchantment (selected tabs, active toggles). */
    public ItemBuilder glow(boolean glow) {
        if (!glow || GLINT == null) return this;
        editMeta(meta -> {
            try {
                GLINT.invoke(meta, Boolean.TRUE);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Decoration only.
            }
        });
        return this;
    }

    /** ItemMeta#setEnchantmentGlintOverride (1.20.5+); 1.20.1-1.20.4 show selection in the lore only. */
    private static final java.lang.reflect.Method GLINT = glintMethod();

    private static java.lang.reflect.Method glintMethod() {
        try {
            return ItemMeta.class.getMethod("setEnchantmentGlintOverride", Boolean.class);
        } catch (NoSuchMethodException | RuntimeException olderServer) {
            return null;
        }
    }

    /** Resource-pack model: custom model data, where the server supports it. Zero or less does nothing. */
    public ItemBuilder model(int customModelData) {
        if (customModelData <= 0) return this;
        try {
            editMeta(meta -> meta.setCustomModelData(customModelData));
        } catch (RuntimeException | LinkageError unsupported) {
            // Custom model data is optional decoration.
        }
        return this;
    }

    public ItemBuilder amount(int amount) {
        itemStack.setAmount(Math.max(1, Math.min(itemStack.getMaxStackSize(), amount)));
        return this;
    }

    public ItemBuilder editMeta(Consumer<ItemMeta> consumer) {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) return this;
        consumer.accept(meta);
        itemStack.setItemMeta(meta);
        return this;
    }

    public ItemBuilder lore(Component... loreLines) {
        List<Component> lore = new ArrayList<>();
        for (Component loreLine : loreLines) {
            lore.add(loreLine);
        }
        return lore(lore);
    }

    public ItemStack build() {
        return itemStack;
    }
}

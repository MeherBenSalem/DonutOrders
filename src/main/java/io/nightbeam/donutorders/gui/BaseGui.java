package io.nightbeam.donutorders.gui;

import io.nightbeam.donutorders.util.ItemBuilder;
import io.nightbeam.donutorders.util.MessageUtil;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * A chest menu. The holder identifies the menu, so a renamed chest can never pass for one. Clicks are cancelled
 * before {@link #handleClick} runs, except in menus that take items ({@link #takesItems()}), which decide
 * themselves.
 */
public abstract class BaseGui implements InventoryHolder {

    protected final GuiManager gui;
    private Inventory inventory;

    protected BaseGui(GuiManager gui) {
        this.gui = gui;
    }

    public abstract Inventory render(Player player);

    public abstract void handleClick(Player player, InventoryClickEvent event);

    /** True for menus with slots players put items into (the delivery menu). */
    public boolean takesItems() {
        return false;
    }

    public void handleDrag(Player player, InventoryDragEvent event) {
        event.setCancelled(true);
    }

    /** The menu was closed (or replaced by another menu). */
    public void onClose(Player player) {
    }

    @Override
    public final Inventory getInventory() {
        return inventory;
    }

    protected final Inventory create(int rows, Component title) {
        inventory = Bukkit.createInventory(this, rows * 9, title);
        return inventory;
    }

    protected final MessageUtil messages() {
        return gui.plugin().messages();
    }

    /** A menu button: its material (and resource-pack model) can be changed under {@code menus.buttons}. */
    protected final ItemBuilder button(String key, Material fallback) {
        return gui.button(key, fallback);
    }

    protected final Component name(String key, String fallback, String... values) {
        return messages().name(key, fallback, values);
    }

    protected final Component lore(String key, String fallback, String... values) {
        return messages().lore(key, fallback, values);
    }

    protected static List<Component> lines(Component... lines) {
        List<Component> list = new ArrayList<>();
        for (Component line : lines) if (line != null) list.add(line);
        return list;
    }

    protected final ItemStack filler() {
        return gui.button("filler", Material.GRAY_STAINED_GLASS_PANE).name(Component.empty()).hideAll().build();
    }

    /** Fills every empty slot with the filler pane. Call after placing the real items. */
    protected final void fill(Inventory inventory, int from, int to) {
        ItemStack pane = filler();
        for (int slot = from; slot <= to && slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) inventory.setItem(slot, pane);
        }
    }

    protected final void fill(Inventory inventory) {
        fill(inventory, 0, inventory.getSize() - 1);
    }

    protected final void click(Player player) {
        gui.click(player);
    }
}

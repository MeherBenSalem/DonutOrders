package com.donutorders.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Abstract base for all DonutOrders GUI screens.
 *
 * <p>Each concrete subclass builds its {@link Inventory} in the constructor and
 * implements {@link #handleClick} to process player interactions.
 *
 * <p>All {@code handleClick} calls arrive on the player's region thread
 * (Folia fires {@code InventoryClickEvent} on the owning thread), so it is safe
 * to call Bukkit API directly from within the handler.
 */
public abstract class BaseGUI {

    /** The Bukkit inventory backing this screen. */
    protected final Inventory inventory;

    protected BaseGUI(Inventory inventory) {
        this.inventory = inventory;
    }

    /**
     * Called by {@link com.donutorders.listener.InventoryListener} when a player
     * clicks a slot in this GUI.
     *
     * @param player  the clicking player
     * @param slot    the clicked slot index (0-based)
     * @param clicked the ItemStack in the clicked slot (may be AIR)
     * @param type    the type of click (LEFT, RIGHT, SHIFT_LEFT, …)
     */
    public abstract void handleClick(Player player, int slot,
                                     ItemStack clicked, ClickType type);

    /** Returns the Bukkit {@link Inventory} — passed to {@link Player#openInventory}. */
    public Inventory getInventory() {
        return inventory;
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    /**
     * Creates a single-pane filler item (gray glass pane, empty display name).
     * Used to pad unused GUI slots so they look clean.
     */
    public static ItemStack filler() {
        return com.donutorders.util.ItemUtils.filler(Material.GRAY_STAINED_GLASS_PANE);
    }

    /**
     * Fills every null slot in the inventory with the filler pane.
     * Call this at the end of a constructor to ensure a clean layout.
     */
    protected void fillEmpty() {
        ItemStack fill = filler();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, fill);
            }
        }
    }
}

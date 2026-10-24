package io.nightbeam.donutorders.gui;

import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** A yes/no question with the thing in question in the middle. The answer runs once. */
final class ConfirmGui extends BaseGui {

    record Request(Component title, ItemStack display, List<Component> extraLore, Consumer<Player> onConfirm, Consumer<Player> onCancel) {
    }

    private static final int CONFIRM = 11, DISPLAY = 13, CANCEL = 15;

    private final Request request;
    private boolean answered;

    ConfirmGui(GuiManager gui, Request request) {
        super(gui);
        this.request = request;
    }

    @Override
    public Inventory render(Player player) {
        Inventory inventory = create(3, request.title());
        ItemStack display = request.display().clone();
        if (!request.extraLore().isEmpty()) {
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<Component> lore = new java.util.ArrayList<>(meta.hasLore() && meta.lore() != null ? meta.lore() : List.of());
                lore.add(Component.empty());
                lore.addAll(request.extraLore());
                meta.lore(lore);
                display.setItemMeta(meta);
            }
        }
        inventory.setItem(DISPLAY, display);
        inventory.setItem(CONFIRM, button("confirm", Material.LIME_CONCRETE).name(name("gui.confirm.accept", "<green><b>Confirm")).build());
        inventory.setItem(CANCEL, button("cancel", Material.RED_CONCRETE).name(name("gui.confirm.decline", "<red><b>Cancel")).build());
        fill(inventory);
        return inventory;
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() != getInventory() || answered) return;
        if (event.getRawSlot() == CONFIRM) {
            answered = true;
            click(player);
            player.closeInventory();
            request.onConfirm().accept(player);
        } else if (event.getRawSlot() == CANCEL) {
            answered = true;
            click(player);
            request.onCancel().accept(player);
        }
    }
}

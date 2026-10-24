package io.nightbeam.donutorders.listener;

import io.nightbeam.donutorders.gui.BaseGui;
import io.nightbeam.donutorders.gui.GuiManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Menu clicks, drags and closes; answers to chat questions; joins and quits. */
public final class MenuListener implements Listener {

    private final GuiManager gui;

    public MenuListener(GuiManager gui) {
        this.gui = gui;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) gui.handleClick(player, event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) gui.handleDrag(player, event);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && event.getView().getTopInventory().getHolder(false) instanceof BaseGui menu) {
            menu.onClose(player);
        }
    }

    /** Paper's chat event fires on every supported version; the lowest priority keeps answers out of public chat. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!gui.awaitingInput(player.getUniqueId())) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        gui.plugin().schedulerAdapter().runEntity(player, () -> gui.answer(player, text));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        gui.orders().onJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        gui.forget(event.getPlayer());
        gui.orders().onQuit(event.getPlayer());
    }
}

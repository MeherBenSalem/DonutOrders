package com.donutorders.listener;

import com.donutorders.manager.ChatInputHandler;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Captures new-order chat input on Paper's Adventure {@link AsyncChatEvent}
 * and on the legacy {@link AsyncPlayerChatEvent} for Spigot.
 *
 * <p>Modern Paper (1.19+) delivers the typed text on {@code AsyncChatEvent}.
 * {@code AsyncPlayerChatEvent#getMessage()} is often empty or unused there, so
 * listening only to the Bukkit event makes amounts, prices, and the cancel
 * keyword look rejected.
 */
public class ChatListener implements Listener {

    private static final boolean PAPER_ASYNC_CHAT = hasPaperAsyncChat();

    private final ChatInputHandler chatInputHandler;

    public ChatListener(ChatInputHandler chatInputHandler) {
        this.chatInputHandler = chatInputHandler;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPaperChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (chatInputHandler.handleInput(player, message)) {
            event.setCancelled(true);
            event.viewers().clear();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncPlayerChatEvent event) {
        if (PAPER_ASYNC_CHAT) {
            return;
        }
        Player player = event.getPlayer();
        String message = event.getMessage();
        if (chatInputHandler.handleInput(player, message)) {
            event.setCancelled(true);
        }
    }

    private static boolean hasPaperAsyncChat() {
        try {
            Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}

package com.donutorders.listener;

import com.donutorders.manager.ChatInputHandler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Intercepts async chat events to feed input to {@link ChatInputHandler}.
 *
 * <p>{@code AsyncPlayerChatEvent} fires on an async thread (as the name
 * suggests).  {@link ChatInputHandler#handleInput} is therefore called on the
 * async thread — it internally re-schedules the {@code onInput} callback onto
 * the player's region thread via {@link com.donutorders.scheduler.FoliaScheduler#runAtEntity}
 * before invoking it, so GUI code in the callback is always on the correct thread.
 *
 * <p>If the message is consumed by a session the event is cancelled so it
 * does not appear in public chat.
 */
public class ChatListener implements Listener {

    private final ChatInputHandler chatInputHandler;

    public ChatListener(ChatInputHandler chatInputHandler) {
        this.chatInputHandler = chatInputHandler;
    }

    /**
     * MONITOR priority so we run after plugins that might cancel the event for
     * other reasons, but with {@code ignoreCancelled = false} so we can still
     * consume the message even if another plugin cancelled it first.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player  = event.getPlayer();
        String message = event.getMessage();

        boolean consumed = chatInputHandler.handleInput(player, message);
        if (consumed) {
            event.setCancelled(true);
        }
    }
}

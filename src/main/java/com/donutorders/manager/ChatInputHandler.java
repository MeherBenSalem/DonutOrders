package com.donutorders.manager;

import com.donutorders.DonutOrders;
import com.donutorders.scheduler.FoliaScheduler;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Manages chat-based input sessions used by the new-order creation flow.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>GUI calls {@link #requestInput} to register a session and prompt the player.</li>
 *   <li>{@link com.donutorders.listener.ChatListener} calls {@link #handleInput} for
 *       every chat message. If the player has an active session the message is
 *       consumed (event cancelled) and the session callback is invoked.</li>
 *   <li>Typing {@code "cancel"} (case-insensitive) invokes the cancel callback.</li>
 *   <li>Sessions expire after {@link #SESSION_TIMEOUT_MS}; a repeating global task
 *       cleans them up.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * {@link #sessions} is a {@link ConcurrentHashMap} — safe to read/write from any
 * thread. The {@code onInput} callback is always bounced to the player's entity
 * thread via {@link FoliaScheduler#runAtEntity} before being invoked, so the GUI
 * layer can open inventories and send messages without any additional scheduling.
 */
public class ChatInputHandler {

    /** Session expires after two minutes of no input. */
    private static final long SESSION_TIMEOUT_MS = 120_000;

    /** Active chat sessions keyed by player UUID. */
    private final ConcurrentHashMap<UUID, ChatSession> sessions = new ConcurrentHashMap<>();

    // ── Session model ─────────────────────────────────────────────────────────

    private static final class ChatSession {
        final Consumer<String> onInput;
        final Runnable onCancel;
        final long expiresAt;

        ChatSession(Consumer<String> onInput, Runnable onCancel) {
            this.onInput   = onInput;
            this.onCancel  = onCancel;
            this.expiresAt = System.currentTimeMillis() + SESSION_TIMEOUT_MS;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Registers a chat input session for {@code player} and sends the prompt.
     *
     * <p>If the player already has an active session it is replaced.
     *
     * @param player   the player to prompt
     * @param prompt   the message to send (supports color codes)
     * @param onInput  called with the validated raw string on the player's thread
     * @param onCancel called if the player types "cancel" or the session expires
     */
    public void requestInput(Player player, String prompt,
                             Consumer<String> onInput, Runnable onCancel) {
        sessions.put(player.getUniqueId(),
                new ChatSession(onInput, onCancel));
        player.sendMessage(DonutOrders.colorize(prompt));
    }

    /**
     * Attempts to handle a chat message as input for an active session.
     *
     * <p>Called from {@link com.donutorders.listener.ChatListener} on the async
     * chat event thread. If a session is found the input is processed and
     * {@code true} is returned (telling the listener to cancel the event).
     *
     * @param player  the player who sent the message
     * @param message the raw message text
     * @return {@code true} if the message was consumed by a session
     */
    public boolean handleInput(Player player, String message) {
        ChatSession session = sessions.remove(player.getUniqueId());
        if (session == null) return false;

        if (message.equalsIgnoreCase("cancel")) {
            // Re-schedule cancel onto entity thread in case caller opens a GUI
            FoliaScheduler.runAtEntity(player,
                    session.onCancel::run,
                    session.onCancel);
        } else {
            final String trimmed = message.trim();
            FoliaScheduler.runAtEntity(player,
                    () -> session.onInput.accept(trimmed),
                    () -> session.onCancel.run());
        }
        return true;
    }

    /**
     * Cancels any active session for {@code player} and triggers the cancel
     * callback. Called on player quit / GUI close.
     *
     * @param player the player whose session to cancel
     */
    public void cancelSession(Player player) {
        ChatSession session = sessions.remove(player.getUniqueId());
        if (session != null && session.onCancel != null) {
            // Player might be quitting — use runGlobal as fallback since entity
            // may be invalid. The callback should be safe on any thread.
            FoliaScheduler.runGlobal(session.onCancel);
        }
    }

    /**
     * Returns {@code true} if the player currently has a pending chat session.
     */
    public boolean hasPendingSession(UUID playerUUID) {
        return sessions.containsKey(playerUUID);
    }

    /**
     * Runs the timeout cleanup pass.
     * Call this from the global repeating task (every ~20 s).
     */
    public void tickTimeouts() {
        sessions.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired()) {
                ChatSession s = entry.getValue();
                if (s.onCancel != null) {
                    FoliaScheduler.runGlobal(s.onCancel);
                }
                return true;
            }
            return false;
        });
    }
}

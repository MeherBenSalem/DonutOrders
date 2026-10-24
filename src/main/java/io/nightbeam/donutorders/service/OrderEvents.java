package io.nightbeam.donutorders.service;

import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

/**
 * Fires API events on a thread Bukkit accepts: "Pre" events synchronously on the player's thread, the others
 * asynchronously (moved off the main or region thread when needed).
 */
public final class OrderEvents {

    private final Executor async;
    private final Logger logger;

    public OrderEvents(Executor async, Logger logger) {
        this.async = async;
        this.logger = logger;
    }

    /** @return true when the action may go ahead (the event was not cancelled) */
    public boolean callCancellable(Event event) {
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "An order event listener failed: " + exception.getMessage(), exception);
            return true;
        }
        return !(event instanceof Cancellable cancellable) || !cancellable.isCancelled();
    }

    public void callAsync(Event event) {
        Runnable fire = () -> {
            try {
                Bukkit.getPluginManager().callEvent(event);
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "An order event listener failed: " + exception.getMessage(), exception);
            }
        };
        try {
            // Async events may not be fired from the main thread (Folia: any tick thread counts as primary).
            if (Bukkit.isPrimaryThread()) {
                async.execute(fire);
            } else {
                fire.run();
            }
        } catch (RuntimeException rejected) {
            // Shutting down: nobody is listening any more.
        }
    }
}

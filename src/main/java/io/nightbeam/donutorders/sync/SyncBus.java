package io.nightbeam.donutorders.sync;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Tells the other servers of a network that something changed, so they refresh at once instead of at the next
 * poll. It only carries ids: receivers always re-read the database.
 */
public interface SyncBus {

    enum Kind {
        /** An order changed: re-read it. */
        ORDER,
        /** A player has new notifications: deliver them if they are online here. */
        NOTICE
    }

    void start();

    void shutdown();

    void publish(Kind kind, UUID id);

    default void setHandler(java.util.function.BiConsumer<Kind, UUID> handler) {
    }

    /** True when messages actually reach other servers. */
    default boolean live() {
        return false;
    }

    static SyncBus noop() {
        return new SyncBus() {
            @Override public void start() { }
            @Override public void shutdown() { }
            @Override public void publish(Kind kind, UUID id) { }
        };
    }

    static Consumer<UUID> ignore() {
        return id -> { };
    }
}

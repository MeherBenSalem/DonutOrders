package io.nightbeam.donutorders.model;

import java.util.Map;
import java.util.UUID;

/** A notification kept for a player who was offline or on another server when it happened. */
public record Notice(long id, UUID player, Kind kind, Map<String, String> values, long at) {

    public enum Kind {
        DELIVERED,
        FILLED,
        EXPIRED,
        CANCELLED_BY_STAFF,
        PAID
    }
}

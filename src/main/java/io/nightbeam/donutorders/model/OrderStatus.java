package io.nightbeam.donutorders.model;

/** An order is ACTIVE until it is filled, cancelled by its owner or staff, or runs out of time. */
public enum OrderStatus {
    ACTIVE,
    FILLED,
    CANCELLED,
    EXPIRED;

    public boolean closed() {
        return this != ACTIVE;
    }

    /** Statuses stored by 1.x and unknown values map to the closest 2.0 status. */
    public static OrderStatus parse(String value) {
        if (value == null) return CANCELLED;
        return switch (value.toUpperCase(java.util.Locale.ROOT)) {
            case "ACTIVE" -> ACTIVE;
            case "FILLED", "COMPLETED" -> FILLED;
            case "EXPIRED" -> EXPIRED;
            default -> CANCELLED;
        };
    }
}

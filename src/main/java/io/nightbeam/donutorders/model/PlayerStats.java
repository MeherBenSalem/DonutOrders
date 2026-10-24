package io.nightbeam.donutorders.model;

/** Lifetime totals: items delivered to other players' orders and items received through your own. */
public record PlayerStats(long itemsDelivered, double earned, long itemsReceived, double spent) {

    public static final PlayerStats EMPTY = new PlayerStats(0L, 0.0D, 0L, 0.0D);
}

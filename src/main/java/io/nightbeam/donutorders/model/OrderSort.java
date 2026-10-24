package io.nightbeam.donutorders.model;

import java.util.Comparator;

/** Board sorts. The first four are the ones players know from the big SMP servers. */
public enum OrderSort {
    MONEY_PER_ITEM(Comparator.comparingDouble(Order::price).reversed()),
    RECENTLY_LISTED(Comparator.comparingLong(Order::createdAt).reversed()),
    MOST_DELIVERED(Comparator.comparingInt(Order::delivered).reversed()),
    MOST_PAID(Comparator.comparingDouble(Order::paid).reversed()),
    ENDING_SOON(Comparator.comparingLong(Order::expiresAt));

    private final Comparator<Order> comparator;

    OrderSort(Comparator<Order> comparator) {
        // Ties: newest first, then by id so pages never shuffle.
        this.comparator = comparator.thenComparing(Comparator.comparingLong(Order::createdAt).reversed())
                .thenComparing(order -> order.id().toString());
    }

    public Comparator<Order> comparator() {
        return comparator;
    }

    public OrderSort next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static OrderSort parse(String value, OrderSort fallback) {
        if (value == null) return fallback;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}

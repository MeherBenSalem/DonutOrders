package io.nightbeam.donutorders.model;

import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/**
 * An immutable snapshot of one buy order. {@code template} is a single item and must not be modified.
 *
 * <p>{@code escrow} is the money still held for the items not delivered yet. {@code collected} counts delivered
 * items the owner has taken; server orders keep nothing, so for them it always equals {@code delivered}.
 */
public record Order(
        UUID id,
        UUID owner,
        String ownerName,
        boolean serverOrder,
        ItemStack template,
        String itemKey,
        String material,
        MatchMode mode,
        int amount,
        int delivered,
        int collected,
        double price,
        double escrow,
        long createdAt,
        long expiresAt,
        OrderStatus status,
        long closedAt,
        long updatedAt,
        long version) {

    /** Owner id of orders created by staff for the server. */
    public static final UUID SERVER = new UUID(0L, 0L);

    /** Amount of an unlimited server order. */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    public int remaining() {
        return Math.max(0, amount - delivered);
    }

    /** Delivered items the owner has not collected yet. */
    public int waiting() {
        return Math.max(0, delivered - collected);
    }

    public boolean unlimited() {
        return amount == UNLIMITED;
    }

    public boolean acceptsDeliveries(long now) {
        return status == OrderStatus.ACTIVE && expiresAt > now && remaining() > 0;
    }

    /** Still shown under "your orders": open, or closed with items left to collect. */
    public boolean live() {
        return status == OrderStatus.ACTIVE || waiting() > 0;
    }

    /** Money paid out to sellers so far. */
    public double paid() {
        return delivered * price;
    }

    /** What the whole order is worth. */
    public double total() {
        return unlimited() ? 0.0D : amount * price;
    }

    public double progress() {
        if (unlimited() || amount <= 0) return 0.0D;
        return Math.min(1.0D, delivered / (double) amount);
    }

    public Order withProgress(int delivered, double escrow, OrderStatus status, long closedAt, long updatedAt) {
        return new Order(id, owner, ownerName, serverOrder, template, itemKey, material, mode, amount, delivered, collected,
                price, escrow, createdAt, expiresAt, status, closedAt, updatedAt, version + 1);
    }

    public Order withCollected(int collected, long updatedAt) {
        return new Order(id, owner, ownerName, serverOrder, template, itemKey, material, mode, amount, delivered, collected,
                price, escrow, createdAt, expiresAt, status, closedAt, updatedAt, version + 1);
    }

    public Order withTerms(int amount, double price, double escrow, long expiresAt, long updatedAt) {
        return new Order(id, owner, ownerName, serverOrder, template, itemKey, material, mode, amount, delivered, collected,
                price, escrow, createdAt, expiresAt, status, closedAt, updatedAt, version + 1);
    }

    public Order closed(OrderStatus status, long closedAt) {
        return new Order(id, owner, ownerName, serverOrder, template, itemKey, material, mode, amount, delivered, collected,
                price, 0.0D, createdAt, expiresAt, status, closedAt, closedAt, version + 1);
    }

    /** The first eight characters of the id, shown to players and accepted by commands. */
    public String shortId() {
        return id.toString().substring(0, 8);
    }
}

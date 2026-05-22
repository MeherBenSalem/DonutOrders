package com.donutorders.model;

/**
 * Lifecycle states for a buy order.
 *
 * <ul>
 *   <li>{@link #ACTIVE}    — order is visible in the marketplace and can be fulfilled.</li>
 *   <li>{@link #COMPLETED} — all requested items were delivered; no more deliveries possible.</li>
 *   <li>{@link #EXPIRED}   — lifetime elapsed; remaining funds / items available to collect.</li>
 *   <li>{@link #CANCELLED} — buyer manually cancelled; refund held in stash for collection.</li>
 * </ul>
 */
public enum OrderStatus {
    ACTIVE,
    COMPLETED,
    EXPIRED,
    CANCELLED,
    PENDING,
    CLAIMED
}

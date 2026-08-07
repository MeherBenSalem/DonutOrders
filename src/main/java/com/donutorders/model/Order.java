package com.donutorders.model;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Immutable-by-convention value object representing a single buy order.
 *
 * <p>All mutable state changes must go through {@link com.donutorders.manager.OrderManager}
 * which persists every mutation to SQLite before returning.
 *
 * <p>The {@link #processingDelivery} flag is <em>transient</em> — it lives only
 * in memory and is used as an anti-duplication lock: the delivery flow sets it
 * to {@code true} with a CAS before any item / money movement, preventing two
 * concurrent deliveries from racing against each other.
 */
public class Order {

    // ── Identity ─────────────────────────────────────────────────────────────

    private final UUID orderId;
    private final UUID buyerUUID;
    private final String buyerName;

    // ── What is being bought ──────────────────────────────────────────────────

    /** Template item — only the {@link org.bukkit.Material} is used for matching. */
    private final ItemStack itemTemplate;

    // ── Quantities ────────────────────────────────────────────────────────────

    private final int amountRequested;
    private volatile int amountFulfilled;

    // ── Finances ─────────────────────────────────────────────────────────────

    /** Price the buyer offered per single item. */
    private final double pricePerItem;

    /**
     * Funds still held in escrow for this order. Starts at
     * {@code pricePerItem * amountRequested} and decreases as sellers are paid
     * out. On cancellation / expiry the remaining amount is refunded.
     */
    private volatile double remainingFunds;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private final long createdAt;
    private final long expiresAt;
    private volatile OrderStatus status;

    private volatile long claimedAt;
    private volatile UUID claimedBy;
    private volatile long updatedAt;

    // ── Anti-duplication lock (transient — not persisted) ─────────────────────

    /**
     * Set to {@code true} by the delivery flow before any item or money is
     * touched. A successful CAS from {@code false} → {@code true} is the
     * authoritative gate: only one thread ever runs the actual transfer.
     * Reset to {@code false} if the delivery fails or is rolled back.
     */
    private final transient AtomicBoolean processingDelivery = new AtomicBoolean(false);

    /**
     * Set to {@code true} by the stash collection flow. Prevents concurrent stash claims.
     */
    private final transient AtomicBoolean processingClaim = new AtomicBoolean(false);

    // ── Constructors ──────────────────────────────────────────────────────────

    public Order(UUID orderId,
                 UUID buyerUUID,
                 String buyerName,
                 ItemStack itemTemplate,
                 int amountRequested,
                 int amountFulfilled,
                 double pricePerItem,
                 double remainingFunds,
                 long createdAt,
                 long expiresAt,
                 OrderStatus status) {
        this(orderId, buyerUUID, buyerName, itemTemplate, amountRequested, amountFulfilled,
             pricePerItem, remainingFunds, createdAt, expiresAt, status, 0L, null, createdAt);
    }

    public Order(UUID orderId,
                 UUID buyerUUID,
                 String buyerName,
                 ItemStack itemTemplate,
                 int amountRequested,
                 int amountFulfilled,
                 double pricePerItem,
                 double remainingFunds,
                 long createdAt,
                 long expiresAt,
                 OrderStatus status,
                 long claimedAt,
                 UUID claimedBy) {
        this(orderId, buyerUUID, buyerName, itemTemplate, amountRequested, amountFulfilled,
             pricePerItem, remainingFunds, createdAt, expiresAt, status, claimedAt, claimedBy,
             Math.max(createdAt, claimedAt));
    }

    public Order(UUID orderId,
                 UUID buyerUUID,
                 String buyerName,
                 ItemStack itemTemplate,
                 int amountRequested,
                 int amountFulfilled,
                 double pricePerItem,
                 double remainingFunds,
                 long createdAt,
                 long expiresAt,
                 OrderStatus status,
                 long claimedAt,
                 UUID claimedBy,
                 long updatedAt) {
        this.orderId = orderId;
        this.buyerUUID = buyerUUID;
        this.buyerName = buyerName;
        this.itemTemplate = itemTemplate;
        this.amountRequested = amountRequested;
        this.amountFulfilled = amountFulfilled;
        this.pricePerItem = pricePerItem;
        this.remainingFunds = remainingFunds;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = status;
        this.claimedAt = claimedAt;
        this.claimedBy = claimedBy;
        this.updatedAt = updatedAt;
    }

    // ── Derived helpers ───────────────────────────────────────────────────────

    /** How many more items are still needed to fully fill this order. */
    public int getAmountRemaining() {
        return amountRequested - amountFulfilled;
    }

    /** {@code true} when all requested items have been delivered. */
    public boolean isFullyFulfilled() {
        return amountFulfilled >= amountRequested;
    }

    /**
     * Returns a human-readable countdown, e.g. {@code "6d 18h 10m"}.
     * Labels come from {@code messages.yml} ({@code time.*} keys).
     */
    public String getFormattedExpiry() {
        return com.donutorders.util.MessageHelper.formatExpiry(expiresAt);
    }

    /**
     * Attempts to acquire the delivery lock via a compare-and-set.
     *
     * @return {@code true} if this thread successfully locked delivery;
     *         {@code false} if another thread is already processing a delivery
     */
    public boolean tryLockDelivery() {
        return processingDelivery.compareAndSet(false, true);
    }

    /** Releases the delivery lock, allowing future deliveries to proceed. */
    public void unlockDelivery() {
        processingDelivery.set(false);
    }

    /**
     * Attempts to acquire the claim lock via a compare-and-set.
     *
     * @return {@code true} if this thread successfully locked claim;
     *         {@code false} if another thread is already claiming this order
     */
    public boolean tryLockClaim() {
        return processingClaim.compareAndSet(false, true);
    }

    /** Releases the claim lock. */
    public void unlockClaim() {
        processingClaim.set(false);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID getOrderId()           { return orderId; }
    public UUID getBuyerUUID()         { return buyerUUID; }
    public String getBuyerName()       { return buyerName; }
    public ItemStack getItemTemplate() { return itemTemplate; }
    public int getAmountRequested()    { return amountRequested; }
    public int getAmountFulfilled()    { return amountFulfilled; }
    public double getPricePerItem()    { return pricePerItem; }
    public double getRemainingFunds()  { return remainingFunds; }
    public long getCreatedAt()         { return createdAt; }
    public long getExpiresAt()         { return expiresAt; }
    public OrderStatus getStatus()     { return status; }
    public long getClaimedAt()         { return claimedAt; }
    public UUID getClaimedBy()         { return claimedBy; }
    public long getUpdatedAt()         { return updatedAt; }

    // ── Setters (package-internal mutation — always followed by a DB write) ────

    public void setAmountFulfilled(int amountFulfilled) {
        this.amountFulfilled = amountFulfilled;
    }

    public void setRemainingFunds(double remainingFunds) {
        this.remainingFunds = remainingFunds;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public void setClaimedAt(long claimedAt) {
        this.claimedAt = claimedAt;
    }

    public void setClaimedBy(UUID claimedBy) {
        this.claimedBy = claimedBy;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** Applies mutable fields from a fresher DB snapshot onto this cached instance. */
    public void applyRemoteState(Order fromDb) {
        this.amountFulfilled = fromDb.amountFulfilled;
        this.remainingFunds = fromDb.remainingFunds;
        this.status = fromDb.status;
        this.claimedAt = fromDb.claimedAt;
        this.claimedBy = fromDb.claimedBy;
        this.updatedAt = fromDb.updatedAt;
    }
}

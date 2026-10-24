package io.nightbeam.donutorders.api.event;

import io.nightbeam.donutorders.model.Order;
import org.bukkit.event.HandlerList;

/** An order closed: filled, cancelled (by its owner or staff) or expired. {@link #getRefund()} went back to the owner. */
public final class OrderClosedEvent extends OrderEvent {

    public enum Reason { FILLED, CANCELLED, CANCELLED_BY_STAFF, EXPIRED }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Reason reason;
    private final double refund;

    public OrderClosedEvent(Order order, Reason reason, double refund) {
        super(order, true);
        this.reason = reason;
        this.refund = refund;
    }

    public Reason getReason() { return reason; }
    public double getRefund() { return refund; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

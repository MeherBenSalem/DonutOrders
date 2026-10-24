package io.nightbeam.donutorders.api.event;

import io.nightbeam.donutorders.model.Order;
import org.bukkit.event.HandlerList;

/** An order was created and its money is held. */
public final class OrderCreatedEvent extends OrderEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public OrderCreatedEvent(Order order) {
        super(order, true);
    }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

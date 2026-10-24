package io.nightbeam.donutorders.api.event;

import io.nightbeam.donutorders.model.Order;
import org.bukkit.event.Event;

/** Base of every order event. Events whose name does not start with "Pre" are fired asynchronously. */
public abstract class OrderEvent extends Event {

    private final Order order;

    protected OrderEvent(Order order, boolean async) {
        super(async);
        this.order = order;
    }

    /** The order after the change (for Pre events: before it). Null for {@link OrderPreCreateEvent}. */
    public Order getOrder() {
        return order;
    }
}

package io.nightbeam.donutorders.api.event;

import io.nightbeam.donutorders.model.Order;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

/** A player is about to deliver items to an order. Cancel it to refuse; the items go back to them. */
public final class OrderPreDeliverEvent extends OrderEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player seller;
    private final int amount;
    private boolean cancelled;
    private String cancelMessage;

    public OrderPreDeliverEvent(Order order, Player seller, int amount) {
        super(order, false);
        this.seller = seller;
        this.amount = amount;
    }

    public Player getSeller() { return seller; }
    /** Items offered; the order may take fewer if another player delivers first. */
    public int getAmount() { return amount; }
    public String getCancelMessage() { return cancelMessage; }
    public void setCancelMessage(String cancelMessage) { this.cancelMessage = cancelMessage; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

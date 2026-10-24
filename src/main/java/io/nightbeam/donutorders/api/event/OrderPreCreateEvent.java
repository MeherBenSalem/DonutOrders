package io.nightbeam.donutorders.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/** A player is about to create an order (nothing has been charged yet). Cancel it to refuse. */
public final class OrderPreCreateEvent extends OrderEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player buyer;
    private final ItemStack item;
    private final int amount;
    private final double price;
    private final long durationMillis;
    private boolean cancelled;
    private String cancelMessage;

    public OrderPreCreateEvent(Player buyer, ItemStack item, int amount, double price, long durationMillis) {
        super(null, false);
        this.buyer = buyer;
        this.item = item.clone();
        this.amount = amount;
        this.price = price;
        this.durationMillis = durationMillis;
    }

    public Player getBuyer() { return buyer; }
    /** A copy of the ordered item; changing it has no effect. */
    public ItemStack getItem() { return item.clone(); }
    public int getAmount() { return amount; }
    public double getPrice() { return price; }
    public long getDurationMillis() { return durationMillis; }
    /** Shown to the player when the event is cancelled (MiniMessage or & codes). Null uses the default. */
    public String getCancelMessage() { return cancelMessage; }
    public void setCancelMessage(String cancelMessage) { this.cancelMessage = cancelMessage; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

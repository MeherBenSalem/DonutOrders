package io.nightbeam.donutorders.api.event;

import io.nightbeam.donutorders.model.Order;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/** Items were delivered to an order and the seller was paid. */
public final class OrderDeliveredEvent extends OrderEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID seller;
    private final String sellerName;
    private final int amount;
    private final double payout;
    private final double tax;

    public OrderDeliveredEvent(Order order, UUID seller, String sellerName, int amount, double payout, double tax) {
        super(order, true);
        this.seller = seller;
        this.sellerName = sellerName;
        this.amount = amount;
        this.payout = payout;
        this.tax = tax;
    }

    public UUID getSeller() { return seller; }
    public String getSellerName() { return sellerName; }
    public int getAmount() { return amount; }
    /** What the items were worth at the order's price; the seller received this minus {@link #getTax()}. */
    public double getPayout() { return payout; }
    public double getTax() { return tax; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

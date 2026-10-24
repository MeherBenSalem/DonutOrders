package io.nightbeam.donutorders.model;

import java.util.UUID;

/** One delivery to an order, as kept in the audit trail. */
public record Delivery(long id, UUID orderId, UUID seller, String sellerName, UUID owner, String ownerName, String itemKey,
                       String material, int amount, double price, double tax, long at) {

    public double total() {
        return amount * price;
    }
}

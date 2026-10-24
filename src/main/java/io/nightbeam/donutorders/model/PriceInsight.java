package io.nightbeam.donutorders.model;

/**
 * What an item has been selling for through orders: completed deliveries within the insight window and the
 * orders open right now. Prices are per single item; zero means "no data".
 */
public record PriceInsight(String itemKey, int deliveries, long items, double average, double min, double max, double last,
                           double bestOpen, long openDemand, int openOrders) {

    public static PriceInsight empty(String itemKey) {
        return new PriceInsight(itemKey, 0, 0L, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0L, 0);
    }

    public boolean hasHistory() {
        return deliveries > 0;
    }

    public PriceInsight withOpen(double bestOpen, long openDemand, int openOrders) {
        return new PriceInsight(itemKey, deliveries, items, average, min, max, last, bestOpen, openDemand, openOrders);
    }
}

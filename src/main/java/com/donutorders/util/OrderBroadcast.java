package com.donutorders.util;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.UUID;

/**
 * Formats and gates the public chat announce sent when a buy order is created.
 */
public final class OrderBroadcast {

    public static final String MESSAGE_PATH = "order-created-broadcast";
    public static final String DEFAULT_MESSAGE =
            "&e{0} &7created a buy order: &f{1} &7× {2} &7@ &f{3} &7each";
    public static final String CONFIG_PATH = "orders.broadcast-on-create";

    private OrderBroadcast() {}

    public static boolean isEnabled(FileConfiguration config) {
        return config == null || config.getBoolean(CONFIG_PATH, true);
    }

    /**
     * The creating player already receives {@code order-created}; skip them
     * so they are not double-notified.
     */
    public static boolean shouldNotify(UUID recipient, UUID buyer) {
        if (recipient == null || buyer == null) {
            return false;
        }
        return !recipient.equals(buyer);
    }

    public static String formatBody(String buyerName, String item, int amount, String price) {
        return MessageHelper.get(MESSAGE_PATH, DEFAULT_MESSAGE, buyerName, item, amount, price);
    }
}

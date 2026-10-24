package io.nightbeam.donutorders.gui;

import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.model.OrderStatus;
import io.nightbeam.donutorders.service.OrderService;
import io.nightbeam.donutorders.util.MessageUtil;
import io.nightbeam.donutorders.util.Money;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** The order item shown in menus: the ordered item itself with the order's details added below its tooltip. */
final class OrderIcons {

    private OrderIcons() {
    }

    /** The item with the lines appended to its own lore (custom items keep theirs). */
    static ItemStack icon(Order order, List<Component> lines) {
        ItemStack icon = order.template().clone();
        int shown = order.unlimited() ? 64 : Math.max(1, Math.min(order.remaining() > 0 ? order.remaining() : order.amount(), icon.getMaxStackSize()));
        icon.setAmount(shown);
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;
        List<Component> lore = new ArrayList<>();
        List<Component> existing = meta.hasLore() ? meta.lore() : null;
        if (existing != null) lore.addAll(existing);
        for (Component line : lines) lore.add(MessageUtil.loreLine(line));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    /** The details every menu shows: buyer, price, progress, paid so far and time left. */
    static List<Component> details(MessageUtil messages, OrderService orders, Order order) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        lines.add(messages.component("gui.order.buyer", "<gray>Buyer: <white>%player%", "player", order.ownerName()));
        lines.add(messages.component("gui.order.price", "<gray>Pays: <gold>%price%</gold> each", "price", orders.format(order.price())));
        if (order.unlimited()) {
            lines.add(messages.component("gui.order.unlimited", "<gray>Wants: <white>as many as you have"));
        } else {
            lines.add(messages.component("gui.order.progress", "<gray>Delivered: <white>%delivered%</white>/%amount% %bar%",
                    "delivered", String.valueOf(order.delivered()), "amount", String.valueOf(order.amount()),
                    "bar", MessageUtil.trusted(bar(order.progress()))));
            if (order.status() == OrderStatus.ACTIVE) {
                lines.add(messages.component("gui.order.remaining", "<gray>Still wants: <white>%remaining%</white> <dark_gray>(%value%)",
                        "remaining", String.valueOf(order.remaining()), "value", orders.format(Money.times(order.remaining(), order.price()))));
            }
        }
        if (order.delivered() > 0) {
            lines.add(messages.component("gui.order.paid", "<gray>Paid out: <gold>%paid%", "paid", orders.format(order.paid())));
        }
        if (order.status() == OrderStatus.ACTIVE) {
            lines.add(messages.component("gui.order.ends", "<gray>Ends in: <white>%time%",
                    "time", messages.timeLeft(order.expiresAt() - System.currentTimeMillis())));
        } else {
            lines.add(messages.component("gui.order.status", "<gray>Status: <white>%status%", "status", messages.status(order.status())));
        }
        return lines;
    }

    /** A 20-segment progress bar. */
    static String bar(double progress) {
        int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, progress)) * 20.0D);
        return "<green>" + "|".repeat(filled) + "</green><dark_gray>" + "|".repeat(20 - filled) + "</dark_gray>";
    }
}

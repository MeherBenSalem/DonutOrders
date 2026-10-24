package io.nightbeam.donutorders.command;

import io.nightbeam.donutorders.DonutOrdersPlugin;
import io.nightbeam.donutorders.gui.GuiManager;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.service.ItemKeys;
import io.nightbeam.donutorders.service.OrderService;
import io.nightbeam.donutorders.util.Durations;
import io.nightbeam.donutorders.util.Enchants;
import io.nightbeam.donutorders.util.PriceParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /order}: your orders. {@code /order new}, {@code /order create <item|hand> <amount> <price> [time]},
 * {@code /order collect}, {@code /order mailbox}, {@code /order history}, {@code /order alerts},
 * {@code /order cancel <id>}.
 */
public final class OrderCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("new", "create", "collect", "mailbox", "history", "alerts", "cancel");

    private final DonutOrdersPlugin plugin;
    private final GuiManager gui;
    private final OrderService orders;

    public OrderCommand(DonutOrdersPlugin plugin, GuiManager gui, OrderService orders) {
        this.plugin = plugin;
        this.gui = gui;
        this.orders = orders;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().sendRaw(sender, "general.players-only", "&cOnly players can use this.");
            return true;
        }
        if (!player.hasPermission("donutorders.use")) {
            plugin.messages().sendRaw(player, "general.no-permission", "&cYou do not have permission to do that.");
            return true;
        }
        if (!orders.ready()) {
            plugin.messages().sendRaw(player, "general.starting", "&eDonutOrders is still starting. Try again in a moment.");
            return true;
        }
        if (args.length == 0) {
            gui.openMyOrders(player, 1);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "new" -> gui.openCatalog(player);
            case "create" -> create(player, args);
            case "collect" -> orders.collectAll(player).thenAccept(given -> plugin.schedulerAdapter().runEntity(player, () -> {
                if (given > 0) plugin.messages().sendFormatted(player, "collect.collected", "&aCollected &f%count% &aitems.", "count", String.valueOf(given));
                else plugin.messages().sendRaw(player, "collect.nothing", "&7Nothing to collect.");
            }));
            case "mailbox", "mail" -> orders.collectMail(player).thenAccept(given -> plugin.schedulerAdapter().runEntity(player, () -> {
                if (given > 0) plugin.messages().sendFormatted(player, "collect.collected", "&aCollected &f%count% &aitems.", "count", String.valueOf(given));
                else if (given == -2) plugin.messages().sendRaw(player, "collect.full", "&cMake room in your inventory first.");
                else plugin.messages().sendRaw(player, "collect.nothing", "&7Nothing to collect.");
            }));
            case "history" -> gui.openHistory(player, 1, false);
            case "alerts", "notify" -> {
                boolean on = orders.notifier().toggleAlerts(player);
                plugin.messages().sendRaw(player, on ? "alerts.enabled" : "alerts.disabled",
                        on ? "&aDelivery alerts and order announcements are on." : "&7Delivery alerts and order announcements are off.");
            }
            case "cancel" -> {
                Optional<Order> order = args.length > 1 ? orders.cache().find(args[1]) : Optional.empty();
                if (order.isEmpty() || !order.get().owner().equals(player.getUniqueId())) {
                    plugin.messages().sendRaw(player, "command.unknown-order", "&cNo order of yours has that id. The id is shown in &e/order&c.");
                    return true;
                }
                orders.cancel(player, order.get()).thenAccept(result -> plugin.schedulerAdapter().runEntity(player, () -> gui.result(player, result)));
            }
            default -> plugin.messages().sendRaw(player, "command.order-usage",
                    "&cUsage: /order [new|create|collect|mailbox|history|alerts|cancel]");
        }
        return true;
    }

    /** {@code /order create <item|hand> <amount> <price> [time]}; books as {@code enchanted_book:sharpness:5}. */
    private void create(Player player, String[] args) {
        if (args.length < 4) {
            plugin.messages().sendRaw(player, "command.create-usage", "&cUsage: /order create <item|hand> <amount> <price> [time]");
            return;
        }
        Optional<ItemKeys.Spec> spec = spec(player, args[1]);
        if (spec.isEmpty()) {
            plugin.messages().sendFormatted(player, "command.unknown-item", "&cUnknown item: %item%", "item", args[1]);
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[2].replace(",", "").replace("_", ""));
        } catch (NumberFormatException invalid) {
            plugin.messages().sendFormatted(player, "input.invalid-amount", "&cType a whole number from 1 to %max%.",
                    "max", String.valueOf(orders.rules().maxAmount(player)));
            return;
        }
        OptionalDouble price = PriceParser.parse(args[3], true);
        if (price.isEmpty()) {
            plugin.messages().sendRaw(player, "input.invalid-price", "&cThat is not a price.");
            return;
        }
        long duration = args.length > 4 ? Durations.parse(args[4], -1L) : orders.rules().clampDuration(player, orders.rules().defaultDuration());
        if (duration <= 0) {
            plugin.messages().sendRaw(player, "command.invalid-duration", "&cUse a time like 12h, 3d or 1w.");
            return;
        }
        orders.create(player, spec.get(), amount, price.getAsDouble(), duration)
                .thenAccept(result -> plugin.schedulerAdapter().runEntity(player, () -> gui.result(player, result)));
    }

    static Optional<ItemKeys.Spec> spec(Player player, String input) {
        if (input.equalsIgnoreCase("hand")) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            return hand == null || hand.getType().isAir() ? Optional.empty() : Optional.of(ItemKeys.fromHeld(hand));
        }
        String[] parts = input.toLowerCase(Locale.ROOT).split(":");
        if (parts.length >= 2 && parts[0].equals("enchanted_book")) {
            // enchanted_book:sharpness[:5] or enchanted_book:namespace:key[:5]
            int end = parts.length;
            Integer level = null;
            if (parts[end - 1].chars().allMatch(Character::isDigit)) {
                level = Integer.parseInt(parts[end - 1]);
                end--;
            }
            String key = String.join(":", java.util.Arrays.copyOfRange(parts, 1, end));
            if (key.isEmpty()) return Optional.empty();
            Enchantment enchantment = Enchants.byKey(key.contains(":") ? key : "minecraft:" + key);
            if (enchantment == null) return Optional.empty();
            int chosen = level == null ? Enchants.maxLevel(enchantment) : level;
            return Optional.of(ItemKeys.book(enchantment, Math.max(1, Math.min(255, chosen))));
        }
        Material material = Material.matchMaterial(input);
        if (material == null || !material.isItem() || material.isAir()) return Optional.empty();
        return Optional.of(ItemKeys.material(material));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return OrdersCommand.filter(SUBCOMMANDS, args[0]);
        if (args[0].equalsIgnoreCase("create")) {
            if (args.length == 2) {
                List<String> items = new ArrayList<>();
                items.add("hand");
                String typed = args[1].toLowerCase(Locale.ROOT);
                if (typed.length() >= 2) {
                    for (Material material : Material.values()) {
                        if (material.name().startsWith("LEGACY_") || !material.isItem()) continue;
                        String name = material.name().toLowerCase(Locale.ROOT);
                        if (name.startsWith(typed)) items.add(name);
                        if (items.size() > 40) break;
                    }
                }
                return OrdersCommand.filter(items, args[1]);
            }
            if (args.length == 3) return OrdersCommand.filter(List.of("1", "16", "64", "256", "1000"), args[2]);
            if (args.length == 4) return OrdersCommand.filter(List.of("10", "100", "1k", "10k"), args[3]);
            if (args.length == 5) return OrdersCommand.filter(List.of("1h", "12h", "1d", "3d", "7d"), args[4]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cancel") && sender instanceof Player player) {
            return OrdersCommand.filter(orders.cache().ofOwner(player.getUniqueId()).stream()
                    .filter(order -> order.status() == io.nightbeam.donutorders.model.OrderStatus.ACTIVE).map(Order::shortId).toList(), args[1]);
        }
        return List.of();
    }
}

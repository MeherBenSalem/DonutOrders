package io.nightbeam.donutorders.command;

import io.nightbeam.donutorders.DonutOrdersPlugin;
import io.nightbeam.donutorders.gui.GuiManager;
import io.nightbeam.donutorders.gui.SellFlow;
import io.nightbeam.donutorders.model.BrowseRequest;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.service.ItemKeys;
import io.nightbeam.donutorders.service.OrderService;
import io.nightbeam.donutorders.util.ItemNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** {@code /orders [search] | sell [hand|all] | price | view <player> | open <id> | help}: the public board. */
public final class OrdersCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("sell", "price", "view", "help");

    private final DonutOrdersPlugin plugin;
    private final GuiManager gui;
    private final OrderService orders;

    public OrdersCommand(DonutOrdersPlugin plugin, GuiManager gui, OrderService orders) {
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
            gui.openBoard(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sell" -> {
                if (!player.hasPermission("donutorders.sell")) {
                    plugin.messages().sendRaw(player, "general.no-permission", "&cYou do not have permission to do that.");
                    return true;
                }
                boolean everything = args.length > 1 && args[1].equalsIgnoreCase("all");
                SellFlow.confirm(gui, player, everything, player::closeInventory);
            }
            case "price" -> price(player);
            case "view" -> {
                if (args.length < 2) {
                    plugin.messages().sendRaw(player, "command.view-usage", "&cUsage: /orders view <player>");
                    return true;
                }
                @SuppressWarnings("deprecation")
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                String name = target.getName() == null ? args[1] : target.getName();
                BrowseRequest request = gui.board(player).withSearch("").withOwner(target.getUniqueId(), name);
                gui.openBoard(player, request);
            }
            case "open" -> {
                Optional<Order> order = args.length > 1 ? orders.cache().find(args[1]) : Optional.empty();
                if (order.isEmpty()) {
                    plugin.messages().sendRaw(player, "deliver.closed", "&cThat order is no longer taking deliveries.");
                    return true;
                }
                if (order.get().owner().equals(player.getUniqueId())) gui.openMyOrders(player, 1);
                else gui.openDeliver(player, order.get());
            }
            case "help" -> help(player);
            default -> gui.openBoard(player, gui.board(player).withOwner(null, null).withSearch(String.join(" ", args)));
        }
        return true;
    }

    private void price(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            plugin.messages().sendRaw(player, "price.hold-item", "&cHold the item you want to price.");
            return;
        }
        ItemKeys.Spec spec = ItemKeys.fromHeld(hand);
        String item = ItemNames.fragment(spec.template());
        orders.insight(spec.key()).thenAccept(insight -> plugin.schedulerAdapter().runEntity(player, () -> {
            plugin.messages().sendFormatted(player, "price.header", "&dPrice check: &f%item%", "item", item);
            if (insight.bestOpen() > 0) {
                plugin.messages().sendFormatted(player, "price.best", "&7Best open order: &6%price% &7each (%count% orders want %items%)",
                        "price", orders.format(insight.bestOpen()), "count", String.valueOf(insight.openOrders()), "items", String.valueOf(insight.openDemand()));
            } else {
                plugin.messages().sendRaw(player, "price.no-open", "&7No open orders want this right now.");
            }
            if (insight.hasHistory()) {
                plugin.messages().sendFormatted(player, "price.history", "&7Average &6%average% &7(range %min% to %max%, last %last%) over %items% items",
                        "average", orders.format(insight.average()), "min", orders.format(insight.min()), "max", orders.format(insight.max()),
                        "last", orders.format(insight.last()), "items", String.valueOf(insight.items()));
            } else {
                plugin.messages().sendRaw(player, "price.no-history", "&7No recent deliveries of this item.");
            }
        }));
    }

    private void help(Player player) {
        for (String[] line : new String[][] {
                {"help.board", "&d/orders &7- browse open orders and deliver"},
                {"help.search", "&d/orders <search> &7- find orders by item, enchantment or player"},
                {"help.sell", "&d/orders sell [all] &7- sell your held item (or everything) to the best orders"},
                {"help.price", "&d/orders price &7- what the held item sells for"},
                {"help.view", "&d/orders view <player> &7- a player's open orders"},
                {"help.mine", "&d/order &7- your orders: create, collect, change, cancel"},
                {"help.create", "&d/order create <item|hand> <amount> <price> [time] &7- create without menus"}}) {
            player.sendMessage(plugin.messages().component(line[0], line[1]));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(SUBCOMMANDS, args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("sell")) return filter(List.of("hand", "all"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("view")) {
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) names.add(online.getName());
            return filter(names, args[1]);
        }
        return List.of();
    }

    static List<String> filter(List<String> options, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}

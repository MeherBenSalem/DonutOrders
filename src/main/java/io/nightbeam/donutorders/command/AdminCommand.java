package io.nightbeam.donutorders.command;

import io.nightbeam.donutorders.DonutOrdersPlugin;
import io.nightbeam.donutorders.gui.GuiManager;
import io.nightbeam.donutorders.model.BrowseRequest;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.service.ItemKeys;
import io.nightbeam.donutorders.service.OrderService;
import io.nightbeam.donutorders.util.Durations;
import io.nightbeam.donutorders.util.PriceParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * {@code /orderadmin reload | info | view <player> | history <player> | cancel <id> [reason] | purge <days> |
 * server <item|hand> <amount|unlimited> <price> [time] | freeze | unfreeze}.
 */
public final class AdminCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("reload", "info", "view", "history", "cancel", "purge", "server", "freeze", "unfreeze");

    private final DonutOrdersPlugin plugin;
    private final GuiManager gui;
    private final OrderService orders;

    public AdminCommand(DonutOrdersPlugin plugin, GuiManager gui, OrderService orders) {
        this.plugin = plugin;
        this.gui = gui;
        this.orders = orders;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("donutorders.admin")) {
            plugin.messages().sendRaw(sender, "general.no-permission", "&cYou do not have permission to do that.");
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadEverything();
                plugin.messages().sendRaw(sender, "admin.reloaded", "&aDonutOrders configuration and messages reloaded.");
            }
            case "info" -> info(sender);
            case "view" -> {
                if (!(sender instanceof Player player) || args.length < 2) {
                    plugin.messages().sendRaw(sender, "admin.view-usage", "&cUsage (in game): /orderadmin view <player>");
                    return true;
                }
                OfflinePlayer target = offline(args[1]);
                BrowseRequest request = gui.board(player).withSearch("").withOwner(target.getUniqueId(), target.getName() == null ? args[1] : target.getName());
                gui.openBoard(player, request);
            }
            case "history" -> {
                if (!(sender instanceof Player player) || args.length < 2) {
                    plugin.messages().sendRaw(sender, "admin.history-usage", "&cUsage (in game): /orderadmin history <player>");
                    return true;
                }
                gui.openHistory(player, offline(args[1]).getUniqueId(), 1, false);
            }
            case "cancel" -> {
                Optional<Order> order = args.length > 1 ? orders.cache().find(args[1]) : Optional.empty();
                if (order.isEmpty()) {
                    plugin.messages().sendRaw(sender, "admin.unknown-order", "&cNo open order has that id.");
                    return true;
                }
                String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
                orders.adminCancel(sender, order.get(), reason).thenAccept(result -> plugin.messages().send(sender, result.message()));
            }
            case "purge" -> {
                int days;
                try {
                    days = Integer.parseInt(args.length > 1 ? args[1] : "");
                } catch (NumberFormatException invalid) {
                    plugin.messages().sendRaw(sender, "admin.purge-usage", "&cUsage: /orderadmin purge <days>");
                    return true;
                }
                orders.purgeNow(days).thenAccept(removed -> plugin.messages().sendFormatted(sender, "admin.purged",
                        "&aRemoved %count% finished orders older than %days% days.", "count", String.valueOf(removed), "days", String.valueOf(days)));
            }
            case "server" -> server(sender, args);
            case "freeze", "unfreeze" -> {
                boolean frozen = args[0].equalsIgnoreCase("freeze");
                orders.rules().setFrozen(frozen);
                plugin.messages().sendRaw(sender, frozen ? "admin.frozen" : "admin.unfrozen",
                        frozen ? "&eThe order market is frozen: nobody can create or deliver until /orderadmin unfreeze."
                                : "&aThe order market is open again.");
            }
            default -> usage(sender);
        }
        return true;
    }

    private void server(CommandSender sender, String[] args) {
        if (args.length < 4) {
            plugin.messages().sendRaw(sender, "admin.server-usage", "&cUsage: /orderadmin server <item|hand> <amount|unlimited> <price> [time]");
            return;
        }
        Optional<ItemKeys.Spec> spec = sender instanceof Player player ? OrderCommand.spec(player, args[1]) : specFromName(args[1]);
        if (spec.isEmpty()) {
            plugin.messages().sendFormatted(sender, "command.unknown-item", "&cUnknown item: %item%", "item", args[1]);
            return;
        }
        int amount;
        if (args[2].equalsIgnoreCase("unlimited") || args[2].equals("*")) {
            amount = Order.UNLIMITED;
        } else {
            try {
                amount = Integer.parseInt(args[2]);
                if (amount < 1) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                plugin.messages().sendRaw(sender, "admin.server-usage", "&cUsage: /orderadmin server <item|hand> <amount|unlimited> <price> [time]");
                return;
            }
        }
        OptionalDouble price = PriceParser.parse(args[3], true);
        if (price.isEmpty() || price.getAsDouble() <= 0) {
            plugin.messages().sendRaw(sender, "input.invalid-price", "&cThat is not a price.");
            return;
        }
        long duration = args.length > 4 ? Durations.parse(args[4], -1L) : orders.rules().defaultDuration();
        if (duration <= 0) {
            plugin.messages().sendRaw(sender, "command.invalid-duration", "&cUse a time like 12h, 3d or 1w.");
            return;
        }
        orders.createServerOrder(spec.get(), amount, price.getAsDouble(), duration, null).whenComplete((order, failure) -> {
            if (failure != null) {
                plugin.messages().sendRaw(sender, "admin.server-failed", "&cThe server order could not be saved.");
                return;
            }
            plugin.messages().sendFormatted(sender, "admin.server-created", "&aServer order %order% created.", "order", order.shortId());
        });
    }

    private static Optional<ItemKeys.Spec> specFromName(String name) {
        org.bukkit.Material material = org.bukkit.Material.matchMaterial(name);
        return material == null || !material.isItem() ? Optional.empty() : Optional.of(ItemKeys.material(material));
    }

    private void info(CommandSender sender) {
        orders.repository().counts().thenAccept(counts -> {
            plugin.messages().sendFormatted(sender, "admin.info-header", "&dDonutOrders %version% &7on %server%",
                    "version", plugin.getDescription().getVersion(), "server", Bukkit.getName() + " " + Bukkit.getBukkitVersion());
            plugin.messages().sendFormatted(sender, "admin.info-storage", "&7Storage: &f%storage% &7· Economy: &f%economy%",
                    "storage", orders.databaseType().name(), "economy", orders.payments().bridge());
            plugin.messages().sendFormatted(sender, "admin.info-cache", "&7Cached: &f%cached% &7orders (%active% open) · market %state%",
                    "cached", String.valueOf(orders.cache().size()), "active", String.valueOf(orders.cache().active(System.currentTimeMillis()).size()),
                    "state", orders.rules().frozen() ? "frozen" : "open");
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : counts.entrySet()) parts.add(entry.getKey().toLowerCase(Locale.ROOT) + "=" + entry.getValue());
            plugin.messages().sendFormatted(sender, "admin.info-database", "&7Database: &f%counts%", "counts", String.join(", ", parts));
            if (orders.repository().unreadableRows() > 0) {
                plugin.messages().sendFormatted(sender, "admin.info-unreadable", "&c%count% rows could not be read on this server version.",
                        "count", String.valueOf(orders.repository().unreadableRows()));
            }
        });
    }

    private void usage(CommandSender sender) {
        plugin.messages().sendRaw(sender, "admin.usage",
                "&cUsage: /orderadmin <reload|info|view|history|cancel|purge|server|freeze|unfreeze>");
    }

    @SuppressWarnings("deprecation")
    private static OfflinePlayer offline(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayer(name);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("donutorders.admin")) return List.of();
        if (args.length == 1) return OrdersCommand.filter(SUBCOMMANDS, args[0]);
        if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "view", "history" -> {
                    List<String> names = new ArrayList<>();
                    for (Player online : Bukkit.getOnlinePlayers()) names.add(online.getName());
                    return OrdersCommand.filter(names, args[1]);
                }
                case "cancel" -> {
                    return OrdersCommand.filter(orders.cache().active(System.currentTimeMillis()).stream().map(Order::shortId).toList(), args[1]);
                }
                case "purge" -> {
                    return OrdersCommand.filter(List.of("30", "60", "90"), args[1]);
                }
                case "server" -> {
                    return OrdersCommand.filter(List.of("hand", "wheat", "cobblestone", "diamond"), args[1]);
                }
                default -> {
                    return List.of();
                }
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("server")) return OrdersCommand.filter(List.of("unlimited", "64", "1000"), args[2]);
        return List.of();
    }
}

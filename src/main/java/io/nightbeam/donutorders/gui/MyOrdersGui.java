package io.nightbeam.donutorders.gui;

import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.model.OrderStatus;
import io.nightbeam.donutorders.service.ItemKeys;
import io.nightbeam.donutorders.service.OrderService;
import io.nightbeam.donutorders.util.Sounds;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * A player's open orders and closed ones that still hold items: collect, edit or cancel them, start a new order,
 * empty the mailbox and see the history.
 */
final class MyOrdersGui extends BaseGui {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45, BOARD = 46, HISTORY = 47, NEW = 48, COLLECT = 49, MAILBOX = 50, ALERTS = 51, NEXT = 53;

    private int page;
    private List<Order> shown = List.of();
    private int pages = 1;

    MyOrdersGui(GuiManager gui, int page) {
        super(gui);
        this.page = page;
    }

    @Override
    public Inventory render(Player player) {
        OrderService orders = gui.orders();
        List<Order> all = orders.cache().ofOwner(player.getUniqueId());
        pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(1, Math.min(page, pages));
        shown = all.subList((page - 1) * PAGE_SIZE, Math.min(all.size(), page * PAGE_SIZE));
        Inventory inventory = create(6, name("gui.mine.title", "Your orders (%page%/%pages%)", "page", String.valueOf(page),
                "pages", String.valueOf(pages)));
        for (int slot = 0; slot < shown.size(); slot++) inventory.setItem(slot, icon(shown.get(slot)));
        if (all.isEmpty()) {
            inventory.setItem(22, button("empty", Material.STRUCTURE_VOID).name(name("gui.mine.empty", "<gray>You have no orders"))
                    .lore(lines(lore("gui.mine.empty-hint", "<gray>Click <green>New order</green> to buy items from other players."))).build());
        }
        int limit = orders.rules().orderLimit(player);
        int open = orders.cache().openCount(player.getUniqueId());
        int waiting = orders.cache().waitingFor(player.getUniqueId());
        if (page > 1) inventory.setItem(PREVIOUS, button("previous", Material.ARROW).name(name("gui.previous", "<yellow>Previous page")).build());
        if (page < pages) inventory.setItem(NEXT, button("next", Material.ARROW).name(name("gui.next", "<yellow>Next page")).build());
        inventory.setItem(BOARD, button("board", Material.COMPASS).name(name("gui.mine.board", "<#49d6a0>All orders")).build());
        inventory.setItem(HISTORY, button("history", Material.BOOK).name(name("gui.mine.history", "<#49d6a0>History"))
                .lore(lines(lore("gui.mine.history-line", "<gray>Finished orders and your deliveries"))).build());
        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean holding = hand != null && !hand.getType().isAir();
        inventory.setItem(NEW, button("new-order", Material.EMERALD_BLOCK).name(name("gui.mine.new", "<green><b>New order"))
                .lore(lines(lore("gui.mine.slots", "<gray>Open orders: <white>%open%</white>/%limit%", "open", String.valueOf(open),
                                "limit", limit < 0 ? "∞" : String.valueOf(limit)),
                        Component.empty(),
                        lore("gui.mine.new-hint", "<yellow>Click</yellow> <gray>to pick an item"),
                        holding ? lore("gui.mine.new-held", "<yellow>Shift-click</yellow> <gray>to order the item in your hand") : null)).build());
        inventory.setItem(COLLECT, button("collect-all", waiting > 0 ? Material.HOPPER_MINECART : Material.MINECART)
                .name(name("gui.mine.collect", "<#49d6a0>Collect all"))
                .lore(lines(lore("gui.mine.collect-line", "<gray>Items waiting: <green>%count%", "count", String.valueOf(waiting)))).glow(waiting > 0).build());
        inventory.setItem(MAILBOX, button("mailbox", Material.ENDER_CHEST).name(name("gui.mine.mailbox", "<#49d6a0>Mailbox"))
                .lore(lines(lore("gui.mine.mailbox-line", "<gray>Items that did not fit in your inventory"),
                        lore("gui.mine.mailbox-hint", "<yellow>Click</yellow> <gray>to take them"))).build());
        boolean alerts = orders.notifier().alertsOn(player);
        inventory.setItem(ALERTS, button("alerts", alerts ? Material.BELL : Material.GRAY_DYE).name(name("gui.mine.alerts", "<#49d6a0>Alerts"))
                .lore(lines(lore(alerts ? "gui.board.state-on" : "gui.board.state-off", alerts ? "<green>On" : "<gray>Off"),
                        lore("gui.mine.alerts-line", "<gray>Delivery alerts and new-order announcements"))).build());
        fill(inventory, 45, 53);
        orders.repository().mailCount(player.getUniqueId()).thenAccept(count -> gui.plugin().schedulerAdapter().runEntity(player, () -> {
            if (count <= 0 || player.getOpenInventory().getTopInventory() != getInventory()) return;
            inventory.setItem(MAILBOX, button("mailbox", Material.ENDER_CHEST).name(name("gui.mine.mailbox", "<#49d6a0>Mailbox"))
                    .lore(lines(lore("gui.mine.mailbox-count", "<gray>Items waiting: <green>%count%", "count", String.valueOf(count)),
                            lore("gui.mine.mailbox-hint", "<yellow>Click</yellow> <gray>to take them"))).glow(true).build());
        }));
        return inventory;
    }

    private ItemStack icon(Order order) {
        List<Component> lines = OrderIcons.details(messages(), gui.orders(), order);
        if (order.waiting() > 0) {
            lines.add(lore("gui.mine.waiting", "<gray>Ready to collect: <green>%count%", "count", String.valueOf(order.waiting())));
        }
        if (order.status() == OrderStatus.ACTIVE) {
            lines.add(lore("gui.mine.escrow", "<gray>Money held: <gold>%amount%", "amount", gui.orders().format(order.escrow())));
        }
        lines.add(Component.empty());
        if (order.waiting() > 0) lines.add(lore("gui.mine.click-collect", "<yellow>Click</yellow> <gray>to collect"));
        if (order.status() == OrderStatus.ACTIVE) {
            lines.add(lore("gui.mine.click-edit", "<yellow>Right-click</yellow> <gray>to change price, amount or time"));
            lines.add(lore("gui.mine.click-cancel", "<red>Shift-right-click</red> <gray>to cancel"));
        }
        lines.add(lore("gui.mine.id", "<dark_gray>#%id%", "id", order.shortId()));
        return OrderIcons.icon(order, lines);
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() != getInventory()) return;
        int slot = event.getRawSlot();
        OrderService orders = gui.orders();
        if (slot < PAGE_SIZE) {
            if (slot >= shown.size()) return;
            Order order = orders.cache().get(shown.get(slot).id()).orElse(shown.get(slot));
            ClickType click = event.getClick();
            if (click == ClickType.SHIFT_RIGHT && order.status() == OrderStatus.ACTIVE) {
                click(player);
                gui.confirm(player, new ConfirmGui.Request(name("gui.confirm.cancel", "<red>Cancel this order?"),
                        OrderIcons.icon(order, OrderIcons.details(messages(), orders, order)),
                        lines(lore("gui.confirm.cancel-line", "<gray>You get <gold>%amount%</gold> back.", "amount", orders.format(order.escrow())),
                                order.delivered() > 0 ? lore("gui.confirm.cancel-items", "<gray>Delivered items stay here to collect.") : null),
                        who -> orders.cancel(who, order).thenAccept(result -> gui.plugin().schedulerAdapter().runEntity(who, () -> {
                            gui.result(who, result);
                            gui.openMyOrders(who, page);
                        })),
                        who -> gui.openMyOrders(who, page)));
            } else if (event.isRightClick() && order.status() == OrderStatus.ACTIVE) {
                click(player);
                gui.openEdit(player, order);
            } else if (order.waiting() > 0) {
                orders.collect(player, order).thenAccept(given -> gui.plugin().schedulerAdapter().runEntity(player, () -> {
                    collected(player, given);
                    if (player.getOpenInventory().getTopInventory().getHolder(false) == this) gui.openMyOrders(player, page);
                }));
            }
            return;
        }
        switch (slot) {
            case PREVIOUS -> {
                if (page > 1) {
                    click(player);
                    gui.openMyOrders(player, page - 1);
                }
            }
            case NEXT -> {
                if (page < pages) {
                    click(player);
                    gui.openMyOrders(player, page + 1);
                }
            }
            case BOARD -> {
                click(player);
                gui.openBoard(player);
            }
            case HISTORY -> {
                click(player);
                gui.openHistory(player, 1, false);
            }
            case NEW -> {
                click(player);
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (event.isShiftClick() && hand != null && !hand.getType().isAir()) {
                    gui.openCreate(player, ItemKeys.fromHeld(hand));
                } else {
                    gui.openCatalog(player);
                }
            }
            case COLLECT -> orders.collectAll(player).thenAccept(given -> gui.plugin().schedulerAdapter().runEntity(player, () -> {
                collected(player, given);
                if (player.getOpenInventory().getTopInventory().getHolder(false) == this) gui.openMyOrders(player, page);
            }));
            case MAILBOX -> orders.collectMail(player).thenAccept(given -> gui.plugin().schedulerAdapter().runEntity(player, () -> {
                collected(player, given);
                if (player.getOpenInventory().getTopInventory().getHolder(false) == this) gui.openMyOrders(player, page);
            }));
            case ALERTS -> {
                boolean on = orders.notifier().toggleAlerts(player);
                gui.plugin().messages().sendRaw(player, on ? "alerts.enabled" : "alerts.disabled",
                        on ? "&aDelivery alerts and order announcements are on." : "&7Delivery alerts and order announcements are off.");
                click(player);
                gui.openMyOrders(player, page);
            }
            default -> {
            }
        }
    }

    private void collected(Player player, int given) {
        if (given > 0) {
            gui.plugin().messages().sendFormatted(player, "collect.collected", "&aCollected &f%count% &aitems.", "count", String.valueOf(given));
        } else if (given == -2) {
            gui.plugin().messages().sendRaw(player, "collect.full", "&cMake room in your inventory first.");
            Sounds.play(player, gui.plugin().getConfig(), "error");
        } else if (given == 0) {
            gui.plugin().messages().sendRaw(player, "collect.nothing", "&7Nothing to collect.");
        }
    }
}

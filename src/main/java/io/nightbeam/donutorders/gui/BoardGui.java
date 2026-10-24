package io.nightbeam.donutorders.gui;

import io.nightbeam.donutorders.model.BrowseRequest;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.model.OrderCategory;
import io.nightbeam.donutorders.model.OrderPage;
import io.nightbeam.donutorders.model.OrderSort;
import io.nightbeam.donutorders.service.OrderService;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The order board: 45 open orders per page with sorting, categories, search, a "what I can deliver" filter and
 * quick delivery. Left-click opens the delivery menu; shift-click delivers straight from the inventory.
 */
final class BoardGui extends BaseGui {

    static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45, SORT = 46, CATEGORY = 47, SEARCH = 48, MINE = 49, DELIVERABLE = 50, SELL = 51,
            REFRESH = 52, NEXT = 53;

    private BrowseRequest request;
    private OrderPage page;

    BoardGui(GuiManager gui, BrowseRequest request) {
        super(gui);
        this.request = request;
    }

    @Override
    public Inventory render(Player player) {
        OrderService orders = gui.orders();
        page = orders.cache().browse(request, PAGE_SIZE, System.currentTimeMillis(), order -> orders.canDeliver(player, order));
        request = request.withPage(page.page());
        gui.remember(player, request);
        String title = request.owner() != null
                ? messages().raw("gui.board.title-player", "Orders of %player% (%page%/%pages%)")
                : messages().raw("gui.board.title", "Orders (%page%/%pages%)");
        Inventory inventory = create(6, name("gui.board.title" + (request.owner() != null ? "-player" : ""), title,
                "page", String.valueOf(page.page()), "pages", String.valueOf(page.pages()),
                "player", request.ownerName() == null ? "" : request.ownerName()));
        List<Order> shown = page.orders();
        for (int slot = 0; slot < shown.size(); slot++) inventory.setItem(slot, icon(player, shown.get(slot)));
        if (shown.isEmpty()) {
            inventory.setItem(22, button("empty", Material.STRUCTURE_VOID)
                    .name(name("gui.board.empty", request.filtered() ? "<gray>No orders match" : "<gray>No open orders yet"))
                    .lore(lines(lore("gui.board.empty-hint", "<gray>Create one with <yellow>/order</yellow>."))).build());
        }
        renderBar(player, inventory);
        fill(inventory, 45, 53);
        return inventory;
    }

    private ItemStack icon(Player player, Order order) {
        OrderService orders = gui.orders();
        List<Component> lines = OrderIcons.details(messages(), orders, order);
        lines.add(Component.empty());
        if (order.owner().equals(player.getUniqueId())) {
            lines.add(lore("gui.board.own-order", "<gray>This is your order. <yellow>/order</yellow> to manage it."));
        } else {
            lines.add(lore("gui.board.click-deliver", "<yellow>Click</yellow> <gray>to deliver"));
            lines.add(lore("gui.board.shift-deliver", "<yellow>Shift-click</yellow> <gray>to deliver from your inventory"));
        }
        if (player.hasPermission("donutorders.admin")) {
            lines.add(lore("gui.board.admin-cancel", "<red>Shift-right-click</red> <gray>to cancel (staff)"));
        }
        return OrderIcons.icon(order, lines);
    }

    private void renderBar(Player player, Inventory inventory) {
        if (page.page() > 1) {
            inventory.setItem(PREVIOUS, button("previous", Material.ARROW).name(name("gui.previous", "<yellow>Previous page")).build());
        }
        if (page.page() < page.pages()) {
            inventory.setItem(NEXT, button("next", Material.ARROW).name(name("gui.next", "<yellow>Next page")).build());
        }
        List<Component> sortLines = new ArrayList<>();
        for (OrderSort sort : OrderSort.values()) {
            sortLines.add(messages().component(sort == request.sort() ? "gui.board.option-selected" : "gui.board.option",
                    sort == request.sort() ? "<#49d6a0>▶ <white>%option%" : "<dark_gray>  %option%", "option", messages().sort(sort)));
        }
        sortLines.add(Component.empty());
        sortLines.add(lore("gui.board.sort-hint", "<yellow>Click</yellow> <gray>to change"));
        inventory.setItem(SORT, button("sort", Material.HOPPER).name(name("gui.board.sort", "<#49d6a0>Sort")).lore(sortLines).build());

        List<Component> categoryLines = new ArrayList<>();
        for (OrderCategory category : OrderCategory.values()) {
            categoryLines.add(messages().component(category == request.category() ? "gui.board.option-selected" : "gui.board.option",
                    category == request.category() ? "<#49d6a0>▶ <white>%option%" : "<dark_gray>  %option%", "option", messages().category(category)));
        }
        categoryLines.add(Component.empty());
        categoryLines.add(lore("gui.board.category-hint", "<yellow>Left/right-click</yellow> <gray>to change"));
        inventory.setItem(CATEGORY, gui.button("category", request.category().icon())
                .name(name("gui.board.category", "<#49d6a0>Category")).lore(categoryLines).build());

        List<Component> searchLines = new ArrayList<>();
        if (!request.search().isBlank()) {
            searchLines.add(lore("gui.board.search-current", "<gray>Searching: <white>%search%", "search", request.search()));
            searchLines.add(lore("gui.board.search-clear", "<yellow>Right-click</yellow> <gray>to clear"));
        }
        searchLines.add(lore("gui.board.search-hint", "<gray>Items, enchantments or players"));
        inventory.setItem(SEARCH, button("search", Material.OAK_SIGN).name(name("gui.board.search", "<#49d6a0>Search")).lore(searchLines)
                .glow(!request.search().isBlank()).build());

        int open = gui.orders().cache().openCount(player.getUniqueId());
        int waiting = gui.orders().cache().waitingFor(player.getUniqueId());
        inventory.setItem(MINE, button("my-orders", Material.CHEST).name(name("gui.board.mine", "<#49d6a0>Your orders"))
                .lore(lines(lore("gui.board.mine-open", "<gray>Open: <white>%count%", "count", String.valueOf(open)),
                        waiting > 0 ? lore("gui.board.mine-waiting", "<gray>Items to collect: <green>%count%", "count", String.valueOf(waiting)) : null,
                        Component.empty(), lore("gui.board.mine-hint", "<yellow>Click</yellow> <gray>to manage or create orders"))).build());

        inventory.setItem(DELIVERABLE, button("deliverable", request.deliverableOnly() ? Material.LIME_DYE : Material.GRAY_DYE)
                .name(name("gui.board.deliverable", "<#49d6a0>Only what I can deliver"))
                .lore(lines(lore(request.deliverableOnly() ? "gui.board.state-on" : "gui.board.state-off", request.deliverableOnly() ? "<green>On" : "<gray>Off"),
                        lore("gui.board.deliverable-hint", "<gray>Shows orders for items in your inventory")))
                .glow(request.deliverableOnly()).build());

        if (player.hasPermission("donutorders.sell")) {
            inventory.setItem(SELL, button("sell", Material.EMERALD).name(name("gui.board.sell", "<#49d6a0>Sell to orders"))
                    .lore(lines(lore("gui.board.sell-line", "<gray>Sells everything open orders want,"),
                            lore("gui.board.sell-line-2", "<gray>best price first. You confirm first."))).build());
        }
        inventory.setItem(REFRESH, button("refresh", Material.SUNFLOWER).name(name("gui.board.refresh", "<#49d6a0>Refresh"))
                .lore(lines(lore("gui.board.count", "<gray>%count% open orders", "count", String.valueOf(page.total())))).build());
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() == null || event.getClickedInventory() != getInventory()) return;
        int slot = event.getRawSlot();
        if (slot < PAGE_SIZE) {
            if (slot >= page.orders().size()) return;
            Order order = page.orders().get(slot);
            handleOrder(player, order, event.getClick());
            return;
        }
        switch (slot) {
            case PREVIOUS -> {
                if (page.page() > 1) reopen(player, request.withPage(page.page() - 1));
            }
            case NEXT -> {
                if (page.page() < page.pages()) reopen(player, request.withPage(page.page() + 1));
            }
            case SORT -> reopen(player, request.withSort(request.sort().next()));
            case CATEGORY -> reopen(player, request.withCategory(event.isRightClick() ? request.category().previous() : request.category().next()));
            case SEARCH -> {
                if (event.isRightClick() && !request.search().isBlank()) {
                    reopen(player, request.withSearch(""));
                    return;
                }
                BrowseRequest current = request;
                gui.ask(player, "input.search", "&eType what you are looking for in chat, or &6%cancel% &eto go back.",
                        (who, text) -> gui.openBoard(who, current.withSearch(text)), () -> gui.openBoard(player, current));
            }
            case MINE -> {
                click(player);
                gui.openMyOrders(player, 1);
            }
            case DELIVERABLE -> reopen(player, request.withDeliverableOnly(!request.deliverableOnly()));
            case SELL -> {
                if (player.hasPermission("donutorders.sell")) {
                    click(player);
                    SellFlow.confirm(gui, player, true, () -> gui.openBoard(player, request));
                }
            }
            case REFRESH -> reopen(player, request);
            default -> {
            }
        }
    }

    private void handleOrder(Player player, Order order, ClickType click) {
        if (click == ClickType.SHIFT_RIGHT && player.hasPermission("donutorders.admin")) {
            click(player);
            gui.confirm(player, new ConfirmGui.Request(
                    name("gui.confirm.admin-cancel", "<red>Cancel %player%'s order?", "player", order.ownerName()),
                    OrderIcons.icon(order, OrderIcons.details(messages(), gui.orders(), order)),
                    lines(lore("gui.confirm.admin-cancel-line", "<gray>The buyer is refunded what is still held.")),
                    who -> gui.orders().adminCancel(who, order, "").thenAccept(result ->
                            gui.plugin().schedulerAdapter().runEntity(who, () -> {
                                gui.result(who, result);
                                gui.openBoard(who, request);
                            })),
                    who -> gui.openBoard(who, request)));
            return;
        }
        if (order.owner().equals(player.getUniqueId())) {
            click(player);
            gui.openMyOrders(player, 1);
            return;
        }
        if (click.isShiftClick()) {
            gui.orders().quickDeliver(player, order).thenAccept(summary -> gui.plugin().schedulerAdapter().runEntity(player, () -> {
                gui.plugin().messages().send(player, gui.orders().describe(summary));
                if (summary.delivered() <= 0) io.nightbeam.donutorders.util.Sounds.play(player, gui.plugin().getConfig(), "error");
                if (player.getOpenInventory().getTopInventory().getHolder(false) == this) reopen(player, request);
            }));
            return;
        }
        click(player);
        gui.openDeliver(player, order);
    }

    private void reopen(Player player, BrowseRequest next) {
        click(player);
        gui.openBoard(player, next);
    }
}

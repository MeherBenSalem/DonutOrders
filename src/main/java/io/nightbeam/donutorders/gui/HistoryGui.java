package io.nightbeam.donutorders.gui;

import io.nightbeam.donutorders.model.Delivery;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.service.OrderService;
import io.nightbeam.donutorders.util.ItemBuilder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Finished orders, or the deliveries a player made to other players' orders, with their lifetime totals. */
final class HistoryGui extends BaseGui {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45, MODE = 48, BACK = 49, STATS = 50, NEXT = 53;

    private final UUID owner;
    private final int page;
    private final boolean deliveries;
    private boolean more;

    HistoryGui(GuiManager gui, UUID owner, int page, boolean deliveries) {
        super(gui);
        this.owner = owner;
        this.page = Math.max(1, page);
        this.deliveries = deliveries;
    }

    @Override
    public Inventory render(Player player) {
        Inventory inventory = create(6, name(deliveries ? "gui.history.title-deliveries" : "gui.history.title",
                deliveries ? "Your deliveries (page %page%)" : "Finished orders (page %page%)", "page", String.valueOf(page)));
        inventory.setItem(22, button("loading", Material.CLOCK).name(name("gui.history.loading", "<gray>Loading...")).build());
        bar(inventory);
        return inventory;
    }

    /** Fills the page from the database once it answers. */
    void load(Player player) {
        OrderService orders = gui.orders();
        int offset = (page - 1) * PAGE_SIZE;
        Inventory inventory = getInventory();
        if (deliveries) {
            orders.repository().deliveriesBy(owner, offset, PAGE_SIZE + 1).thenAccept(found -> gui.plugin().schedulerAdapter().runEntity(player, () -> {
                if (player.getOpenInventory().getTopInventory() != inventory) return;
                more = found.size() > PAGE_SIZE;
                List<ItemStack> icons = new ArrayList<>();
                for (Delivery delivery : found.subList(0, Math.min(PAGE_SIZE, found.size()))) icons.add(icon(orders, delivery));
                show(inventory, icons);
            }));
        } else {
            orders.repository().history(owner, offset, PAGE_SIZE + 1).thenAccept(found -> gui.plugin().schedulerAdapter().runEntity(player, () -> {
                if (player.getOpenInventory().getTopInventory() != inventory) return;
                more = found.size() > PAGE_SIZE;
                List<ItemStack> icons = new ArrayList<>();
                for (Order order : found.subList(0, Math.min(PAGE_SIZE, found.size()))) {
                    List<Component> lines = OrderIcons.details(messages(), orders, order);
                    lines.add(lore("gui.history.closed", "<gray>Closed: <white>%date%", "date", date(order.closedAt())));
                    icons.add(OrderIcons.icon(order, lines));
                }
                show(inventory, icons);
            }));
        }
        orders.stats(owner).thenAccept(stats -> gui.plugin().schedulerAdapter().runEntity(player, () -> {
            if (player.getOpenInventory().getTopInventory() != inventory) return;
            inventory.setItem(STATS, button("stats", Material.GOLD_BLOCK).name(name("gui.history.stats", "<#49d6a0>Your totals"))
                    .lore(lines(lore("gui.history.stats-delivered", "<gray>You delivered: <white>%items%</white> items for <gold>%amount%",
                                    "items", String.valueOf(stats.itemsDelivered()), "amount", orders.format(stats.earned())),
                            lore("gui.history.stats-received", "<gray>You received: <white>%items%</white> items for <gold>%amount%",
                                    "items", String.valueOf(stats.itemsReceived()), "amount", orders.format(stats.spent())))).build());
        }));
    }

    private ItemStack icon(OrderService orders, Delivery delivery) {
        Material material = Material.matchMaterial(delivery.material());
        return ItemBuilder.of(material == null || !material.isItem() ? Material.PAPER : material).hideAll()
                .amount(Math.min(64, Math.max(1, delivery.amount())))
                .lore(lines(lore("gui.history.delivery-to", "<gray>To <white>%player%</white>'s order", "player", delivery.ownerName()),
                        lore("gui.history.delivery-amount", "<gray>%amount%× at <gold>%price%</gold> each", "amount", String.valueOf(delivery.amount()),
                                "price", orders.format(delivery.price())),
                        lore("gui.history.delivery-paid", "<gray>You got: <green>%amount%", "amount",
                                orders.format(io.nightbeam.donutorders.util.Money.minus(delivery.total(), delivery.tax()))),
                        lore("gui.history.delivery-date", "<dark_gray>%date%", "date", date(delivery.at())))).build();
    }

    private void show(Inventory inventory, List<ItemStack> icons) {
        for (int slot = 0; slot < PAGE_SIZE; slot++) inventory.setItem(slot, slot < icons.size() ? icons.get(slot) : null);
        if (icons.isEmpty()) {
            inventory.setItem(22, button("empty", Material.STRUCTURE_VOID).name(name("gui.history.empty", "<gray>Nothing here yet")).build());
        }
        bar(inventory);
    }

    private void bar(Inventory inventory) {
        if (page > 1) inventory.setItem(PREVIOUS, button("previous", Material.ARROW).name(name("gui.previous", "<yellow>Previous page")).build());
        if (more) inventory.setItem(NEXT, button("next", Material.ARROW).name(name("gui.next", "<yellow>Next page")).build());
        inventory.setItem(MODE, button("history-mode", deliveries ? Material.CHEST : Material.HOPPER)
                .name(name(deliveries ? "gui.history.show-orders" : "gui.history.show-deliveries",
                        deliveries ? "<#49d6a0>Show finished orders" : "<#49d6a0>Show your deliveries")).build());
        inventory.setItem(BACK, button("back", Material.ARROW).name(name("gui.back", "<yellow>Back")).build());
        fill(inventory, 45, 53);
    }

    private String date(long millis) {
        return new SimpleDateFormat(messages().raw("gui.history.date-format", "yyyy-MM-dd HH:mm")).format(new Date(millis));
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() != getInventory()) return;
        switch (event.getRawSlot()) {
            case PREVIOUS -> {
                if (page > 1) {
                    click(player);
                    gui.openHistory(player, page - 1, deliveries);
                }
            }
            case NEXT -> {
                if (more) {
                    click(player);
                    gui.openHistory(player, page + 1, deliveries);
                }
            }
            case MODE -> {
                click(player);
                gui.openHistory(player, 1, !deliveries);
            }
            case BACK -> {
                click(player);
                gui.openMyOrders(player, 1);
            }
            default -> {
            }
        }
    }
}

package io.nightbeam.donutorders.gui;

import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.model.OrderStatus;
import io.nightbeam.donutorders.service.OrderService;
import io.nightbeam.donutorders.util.Money;
import io.nightbeam.donutorders.util.PriceParser;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

/** Changes an open order: more items, a new price, more time, or cancel it. The money difference is shown first. */
final class EditGui extends BaseGui {

    private static final int[][] ADD_STEPS = {{19, -64}, {20, -16}, {21, -1}, {23, 1}, {24, 16}, {25, 64}};
    private static final int ICON = 4, ADD = 22, PRICE = 31, BACK = 45, CANCEL_ORDER = 48, SAVE = 49, CLOSE = 53;

    private final Order order;
    private int add;
    private double price;
    private int extendIndex = -1;
    private boolean submitting;

    EditGui(GuiManager gui, Order order) {
        super(gui);
        this.order = order;
        this.price = order.price();
    }

    @Override
    public Inventory render(Player player) {
        submitting = false;
        Inventory inventory = create(6, name("gui.edit.title", "Change your order"));
        draw(player, inventory);
        return inventory;
    }

    private void draw(Player player, Inventory inventory) {
        OrderService orders = gui.orders();
        inventory.setItem(ICON, OrderIcons.icon(order, OrderIcons.details(messages(), orders, order)));
        for (int[] button : ADD_STEPS) {
            boolean down = button[1] < 0;
            inventory.setItem(button[0], button(down ? "amount-down" : "amount-up", down ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE)
                    .name(name(down ? "gui.create.amount-minus" : "gui.create.amount-plus", down ? "<red>%step%" : "<green>+%step%",
                            "step", String.valueOf(button[1]))).amount(Math.abs(button[1])).build());
        }
        inventory.setItem(ADD, button("amount", Material.CHEST).name(name("gui.edit.add", "<#49d6a0>Add items: <white>+%amount%", "amount", String.valueOf(add)))
                .lore(lines(lore("gui.edit.new-amount", "<gray>New total: <white>%amount%", "amount", String.valueOf(order.amount() + add)),
                        lore("gui.create.type-hint", "<yellow>Click</yellow> <gray>to type a number"))).build());
        String[] priceSteps = {"-10%", "-1%", "", "+1%", "+10%"};
        for (int i = 0; i < priceSteps.length; i++) {
            if (priceSteps[i].isEmpty()) continue;
            boolean down = priceSteps[i].startsWith("-");
            inventory.setItem(29 + i, button(down ? "price-down" : "price-up", down ? Material.RED_DYE : Material.LIME_DYE)
                    .name(name(down ? "gui.create.price-minus" : "gui.create.price-plus", down ? "<red>%step%" : "<green>%step%", "step", priceSteps[i])).build());
        }
        inventory.setItem(PRICE, button("price", Material.GOLD_NUGGET).name(name("gui.create.price", "<#49d6a0>Price each: <gold>%price%",
                        "price", orders.format(price)))
                .lore(lines(lore("gui.edit.old-price", "<gray>Now: <white>%price%", "price", orders.format(order.price())),
                        lore("gui.create.price-hint", "<yellow>Click</yellow> <gray>to type a price (2.5k, 1m work)"))).build());
        List<Long> options = orders.rules().durationOptions(player);
        int start = 40 - Math.min(options.size(), 9) / 2;
        for (int i = 0; i < Math.min(options.size(), 9); i++) {
            boolean chosen = i == extendIndex;
            inventory.setItem(start + i, button(chosen ? "duration-selected" : "duration", chosen ? Material.CLOCK : Material.GRAY_DYE)
                    .name(name("gui.edit.extend", chosen ? "<green>▶ +%duration%" : "<gray>+%duration%", "duration", messages().duration(options.get(i))))
                    .glow(chosen).build());
        }
        inventory.setItem(BACK, button("back", Material.ARROW).name(name("gui.back", "<yellow>Back")).build());
        inventory.setItem(CANCEL_ORDER, button("cancel-order", Material.TNT).name(name("gui.edit.cancel", "<red>Cancel this order"))
                .lore(lines(lore("gui.confirm.cancel-line", "<gray>You get <gold>%amount%</gold> back.", "amount", orders.format(order.escrow())))).build());
        inventory.setItem(SAVE, saveItem(player, orders));
        inventory.setItem(CLOSE, button("close", Material.BARRIER).name(name("gui.close", "<red>Close")).build());
        fill(inventory);
    }

    private org.bukkit.inventory.ItemStack saveItem(Player player, OrderService orders) {
        double escrow = Money.times((long) order.amount() + add - order.delivered(), price);
        double charge = Money.minus(escrow, order.escrow());
        double refund = Money.minus(order.escrow(), escrow);
        List<Component> lines = new ArrayList<>();
        if (add > 0) lines.add(lore("gui.edit.change-amount", "<gray>Amount: <white>%old% → %new%", "old", String.valueOf(order.amount()),
                "new", String.valueOf(order.amount() + add)));
        if (Math.abs(price - order.price()) >= Money.EPSILON) lines.add(lore("gui.edit.change-price", "<gray>Price: <white>%old% → %new%",
                "old", orders.format(order.price()), "new", orders.format(price)));
        long extend = extension(player);
        if (extend > 0) lines.add(lore("gui.edit.change-time", "<gray>Ends in: <white>%old% → %new%",
                "old", messages().timeLeft(order.expiresAt() - System.currentTimeMillis()),
                "new", messages().timeLeft(Math.min(order.expiresAt() + extend, System.currentTimeMillis() + orders.rules().maxDuration(player))
                        - System.currentTimeMillis())));
        if (lines.isEmpty()) {
            lines.add(lore("gui.edit.nothing", "<gray>Change the amount, price or time first"));
            return button("confirm-blocked", Material.GRAY_CONCRETE).name(name("gui.edit.save", "<green><b>Save changes")).lore(lines).build();
        }
        lines.add(Component.empty());
        if (charge > 0) lines.add(lore("gui.edit.charge", "<gray>You pay: <gold>%amount%", "amount", orders.format(charge)));
        if (refund > 0) lines.add(lore("gui.edit.refund", "<gray>You get back: <green>%amount%", "amount", orders.format(refund)));
        return button("confirm", Material.LIME_CONCRETE).name(name("gui.edit.save", "<green><b>Save changes")).lore(lines).build();
    }

    private long extension(Player player) {
        List<Long> options = gui.orders().rules().durationOptions(player);
        return extendIndex >= 0 && extendIndex < options.size() ? options.get(extendIndex) : 0L;
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() != getInventory() || submitting) return;
        int slot = event.getRawSlot();
        OrderService orders = gui.orders();
        for (int[] button : ADD_STEPS) {
            if (button[0] != slot) continue;
            add = (int) Math.max(0, Math.min((long) orders.rules().maxAmount(player) - order.amount(), (long) add + button[1]));
            redraw(player);
            return;
        }
        if (slot >= 29 && slot <= 33 && slot != PRICE) {
            double factor = switch (slot) {
                case 29 -> -0.10D;
                case 30 -> -0.01D;
                case 32 -> 0.01D;
                default -> 0.10D;
            };
            setPrice(price + Math.max(0.01D, Math.abs(price * factor)) * Math.signum(factor));
            redraw(player);
            return;
        }
        if (slot >= 36 && slot <= 44) {
            List<Long> options = orders.rules().durationOptions(player);
            int index = slot - (40 - Math.min(options.size(), 9) / 2);
            if (index >= 0 && index < Math.min(options.size(), 9)) {
                extendIndex = extendIndex == index ? -1 : index;
                redraw(player);
            }
            return;
        }
        switch (slot) {
            case ADD -> gui.ask(player, "input.add-amount", "&eType how many items to add in chat, or &6%cancel% &eto go back.", (who, text) -> {
                try {
                    long parsed = Long.parseLong(text.replace(",", "").trim());
                    if (parsed < 0 || parsed + order.amount() > orders.rules().maxAmount(who)) throw new NumberFormatException();
                    add = (int) parsed;
                } catch (NumberFormatException invalid) {
                    gui.plugin().messages().sendFormatted(who, "input.invalid-amount", "&cType a whole number from 1 to %max%.",
                            "max", String.valueOf(orders.rules().maxAmount(who) - order.amount()));
                }
                gui.open(who, this);
            }, () -> gui.open(player, this));
            case PRICE -> gui.ask(player, "input.price", "&eType the price per item in chat (2.5k and 1m work), or &6%cancel% &eto go back.",
                    (who, text) -> {
                        OptionalDouble parsed = PriceParser.parse(text, true);
                        if (parsed.isEmpty() || parsed.getAsDouble() <= 0) {
                            gui.plugin().messages().sendRaw(who, "input.invalid-price", "&cThat is not a price.");
                        } else {
                            setPrice(parsed.getAsDouble());
                        }
                        gui.open(who, this);
                    }, () -> gui.open(player, this));
            case BACK -> {
                click(player);
                gui.openMyOrders(player, 1);
            }
            case CLOSE -> player.closeInventory();
            case CANCEL_ORDER -> {
                click(player);
                gui.confirm(player, new ConfirmGui.Request(name("gui.confirm.cancel", "<red>Cancel this order?"),
                        OrderIcons.icon(order, OrderIcons.details(messages(), orders, order)),
                        lines(lore("gui.confirm.cancel-line", "<gray>You get <gold>%amount%</gold> back.", "amount", orders.format(order.escrow()))),
                        who -> orders.cancel(who, current()).thenAccept(result -> gui.plugin().schedulerAdapter().runEntity(who, () -> {
                            gui.result(who, result);
                            gui.openMyOrders(who, 1);
                        })),
                        who -> gui.open(who, this)));
            }
            case SAVE -> save(player);
            default -> {
            }
        }
    }

    private Order current() {
        return gui.orders().cache().get(order.id()).orElse(order);
    }

    private void save(Player player) {
        long extend = extension(player);
        if (add == 0 && extend == 0 && Math.abs(price - order.price()) < Money.EPSILON) return;
        if (current().status() != OrderStatus.ACTIVE) {
            gui.plugin().messages().sendRaw(player, "cancel.not-open", "&cThat order is already closed.");
            return;
        }
        submitting = true;
        player.closeInventory();
        // The latest snapshot: a delivery since the menu opened only changes what is still held, not the change asked for.
        gui.orders().edit(player, current(), add, price, extend).thenAccept(result -> gui.plugin().schedulerAdapter().runEntity(player, () -> {
            gui.result(player, result);
            gui.openMyOrders(player, 1);
        }));
    }

    private void setPrice(double value) {
        OrderService orders = gui.orders();
        double min = orders.rules().minPrice(order.itemKey(), order.template().getType());
        double max = orders.rules().maxPrice(order.itemKey(), order.template().getType());
        price = Money.round(Math.max(min, Math.min(max, value)));
    }

    private void redraw(Player player) {
        click(player);
        draw(player, getInventory());
    }
}

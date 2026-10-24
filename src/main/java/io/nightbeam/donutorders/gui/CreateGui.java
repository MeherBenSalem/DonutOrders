package io.nightbeam.donutorders.gui;

import io.nightbeam.donutorders.model.PriceInsight;
import io.nightbeam.donutorders.service.ItemKeys;
import io.nightbeam.donutorders.service.OrderService;
import io.nightbeam.donutorders.util.ItemBuilder;
import io.nightbeam.donutorders.util.ItemNames;
import io.nightbeam.donutorders.util.Money;
import io.nightbeam.donutorders.util.PriceParser;
import io.nightbeam.donutorders.util.Sounds;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Sets up a new order: amount, price per item and duration, with what the item has been selling for, the best
 * open order, the full cost and a warning when the price is far above the market.
 */
final class CreateGui extends BaseGui {

    private static final int ITEM = 4;
    /** Amount buttons: slot and step. */
    private static final int[][] AMOUNT_STEPS = {{18, -64}, {19, -16}, {20, -1}, {24, 1}, {25, 16}, {26, 64}};
    private static final int AMOUNT = 22, OUTBID = 27, PRICE = 31, AVERAGE = 35;
    private static final int BACK = 45, INSIGHT = 47, CONFIRM = 49, CLOSE = 53;

    private final ItemKeys.Spec spec;
    private int amount;
    private double price;
    private int durationIndex;
    private PriceInsight insight;
    private boolean submitting;

    CreateGui(GuiManager gui, ItemKeys.Spec spec, Player player) {
        super(gui);
        this.spec = spec;
        OrderService orders = gui.orders();
        int stack = spec.template().getMaxStackSize();
        this.amount = Math.min(orders.rules().maxAmount(player), stack <= 1 ? 1 : stack);
        double[] demand = orders.cache().demand(spec.key(), System.currentTimeMillis());
        double min = orders.rules().minPrice(spec.key(), spec.template().getType());
        this.price = Money.round(Math.max(min, demand[0] > 0 ? demand[0] : min));
        List<Long> options = orders.rules().durationOptions(player);
        long wanted = orders.rules().clampDuration(player, orders.rules().defaultDuration());
        this.durationIndex = Math.max(0, options.indexOf(wanted));
        if (!options.contains(wanted)) {
            for (int i = 0; i < options.size(); i++) if (options.get(i) <= wanted) durationIndex = i;
        }
    }

    @Override
    public Inventory render(Player player) {
        submitting = false;
        Inventory inventory = create(6, name("gui.create.title", "New order"));
        draw(player, inventory);
        if (insight == null) {
            gui.orders().insight(spec.key()).thenAccept(found -> gui.plugin().schedulerAdapter().runEntity(player, () -> {
                insight = found;
                if (player.getOpenInventory().getTopInventory() == inventory) draw(player, inventory);
            })).exceptionally(failure -> null);
        }
        return inventory;
    }

    private void draw(Player player, Inventory inventory) {
        OrderService orders = gui.orders();
        ItemStack shown = spec.template().clone();
        inventory.setItem(ITEM, ItemBuilder.of(shown).lore(lines(lore("gui.create.ordering", "<gray>You are ordering this item"),
                lore("gui.create.mode-" + spec.mode().name().toLowerCase(java.util.Locale.ROOT), switch (spec.mode()) {
                    case TYPE -> "<dark_gray>Any plain %item% counts";
                    case BOOK -> "<dark_gray>Books with exactly this enchantment count";
                    case EXACT -> "<dark_gray>Only this exact item counts";
                }, "item", ItemNames.fragment(spec.template())))).build());

        for (int[] button : AMOUNT_STEPS) {
            int slot = button[0];
            int step = button[1];
            inventory.setItem(slot, button(step < 0 ? "amount-down" : "amount-up", step < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE)
                    .name(name(step < 0 ? "gui.create.amount-minus" : "gui.create.amount-plus", step < 0 ? "<red>%step%" : "<green>+%step%",
                            "step", String.valueOf(step))).amount(Math.abs(step)).build());
        }
        inventory.setItem(AMOUNT, button("amount", Material.CHEST).name(name("gui.create.amount", "<#49d6a0>Amount: <white>%amount%",
                        "amount", String.valueOf(amount)))
                .lore(lines(lore("gui.create.type-hint", "<yellow>Click</yellow> <gray>to type a number"))).build());

        double[] demand = orders.cache().demand(spec.key(), System.currentTimeMillis());
        if (demand[0] > 0) {
            inventory.setItem(OUTBID, button("outbid", Material.GOLD_INGOT).name(name("gui.create.outbid", "<gold>Outbid the best order"))
                    .lore(lines(lore("gui.create.outbid-line", "<gray>Best open order pays <gold>%price%</gold> each", "price", orders.format(demand[0])),
                            lore("gui.create.outbid-hint", "<yellow>Click</yellow> <gray>to pay %price%", "price", orders.format(outbid(demand[0]))))).build());
        }
        String[] priceSteps = {"-10%", "-1%", "", "+1%", "+10%"};
        for (int i = 0; i < priceSteps.length; i++) {
            if (priceSteps[i].isEmpty()) continue;
            boolean down = priceSteps[i].startsWith("-");
            inventory.setItem(29 + i, button(down ? "price-down" : "price-up", down ? Material.RED_DYE : Material.LIME_DYE)
                    .name(name(down ? "gui.create.price-minus" : "gui.create.price-plus", down ? "<red>%step%" : "<green>%step%", "step", priceSteps[i])).build());
        }
        inventory.setItem(PRICE, button("price", Material.GOLD_NUGGET).name(name("gui.create.price", "<#49d6a0>Price each: <gold>%price%",
                        "price", orders.format(price)))
                .lore(lines(lore("gui.create.price-hint", "<yellow>Click</yellow> <gray>to type a price (2.5k, 1m work)"))).build());
        if (insight != null && insight.average() > 0) {
            inventory.setItem(AVERAGE, button("average", Material.COMPARATOR).name(name("gui.create.average", "<aqua>Use the average"))
                    .lore(lines(lore("gui.create.average-line", "<gray>Recent deliveries averaged <gold>%price%", "price", orders.format(insight.average())),
                            lore("gui.create.average-hint", "<yellow>Click</yellow> <gray>to use it"))).build());
        }

        List<Long> options = orders.rules().durationOptions(player);
        durationIndex = Math.max(0, Math.min(durationIndex, options.size() - 1));
        int start = 40 - Math.min(options.size(), 9) / 2;
        for (int i = 0; i < Math.min(options.size(), 9); i++) {
            boolean chosen = i == durationIndex;
            inventory.setItem(start + i, button(chosen ? "duration-selected" : "duration", chosen ? Material.CLOCK : Material.GRAY_DYE)
                    .name(name("gui.create.duration", chosen ? "<green>▶ %duration%" : "<gray>%duration%", "duration", messages().duration(options.get(i))))
                    .glow(chosen).build());
        }

        inventory.setItem(BACK, button("back", Material.ARROW).name(name("gui.back", "<yellow>Back")).build());
        inventory.setItem(INSIGHT, insightItem(orders, demand));
        inventory.setItem(CONFIRM, confirmItem(player, orders, options.get(durationIndex)));
        inventory.setItem(CLOSE, button("close", Material.BARRIER).name(name("gui.close", "<red>Close")).build());
        fill(inventory);
    }

    private ItemStack insightItem(OrderService orders, double[] demand) {
        List<Component> lines = new ArrayList<>();
        if (insight == null) {
            lines.add(lore("gui.create.insight-loading", "<gray>Loading..."));
        } else if (!insight.hasHistory()) {
            lines.add(lore("gui.create.insight-none", "<gray>No deliveries in the last %days% days",
                    "days", String.valueOf(gui.plugin().getConfig().getLong("insight.days", 7L))));
        } else {
            lines.add(lore("gui.create.insight-average", "<gray>Average: <gold>%price%", "price", orders.format(insight.average())));
            lines.add(lore("gui.create.insight-range", "<gray>Range: <white>%min%</white> to <white>%max%", "min", orders.format(insight.min()),
                    "max", orders.format(insight.max())));
            lines.add(lore("gui.create.insight-last", "<gray>Last: <white>%price%", "price", orders.format(insight.last())));
            lines.add(lore("gui.create.insight-volume", "<gray>Delivered: <white>%items%</white> items in %count% deliveries",
                    "items", String.valueOf(insight.items()), "count", String.valueOf(insight.deliveries())));
        }
        lines.add(Component.empty());
        if (demand[2] > 0) {
            lines.add(lore("gui.create.insight-open", "<gray>Open orders: <white>%count%</white> wanting <white>%items%",
                    "count", String.valueOf((int) demand[2]), "items", String.valueOf((long) demand[1])));
            lines.add(lore("gui.create.insight-best", "<gray>Best open price: <gold>%price%", "price", orders.format(demand[0])));
        } else {
            lines.add(lore("gui.create.insight-no-open", "<gray>No other open orders for this item"));
        }
        return button("insight", Material.WRITABLE_BOOK).name(name("gui.create.insight", "<aqua>Price insight")).lore(lines).build();
    }

    private ItemStack confirmItem(Player player, OrderService orders, long duration) {
        double total = Money.times(amount, price);
        double fee = orders.fees().creationFee(player, total);
        double cost = Money.round(total + fee);
        Optional<String> problem = orders.checkCreate(player, spec, amount, price, duration);
        List<Component> lines = new ArrayList<>();
        lines.add(lore("gui.create.summary", "<white>%amount%× %item%</white> <gray>at <gold>%price%</gold> each", "amount", String.valueOf(amount),
                "item", ItemNames.fragment(spec.template()), "price", orders.format(price)));
        lines.add(lore("gui.create.total", "<gray>Held for sellers: <gold>%amount%", "amount", orders.format(total)));
        if (fee > 0) lines.add(lore("gui.create.fee", "<gray>Order fee: <red>%amount%", "amount", orders.format(fee)));
        lines.add(lore("gui.create.cost", "<gray>You pay now: <gold>%amount%", "amount", orders.format(cost)));
        lines.add(lore("gui.create.balance", "<gray>Balance after: <white>%amount%", "amount", orders.format(Math.max(0.0D, orders.payments().balance(player) - cost))));
        lines.add(lore("gui.create.ends", "<gray>Ends in: <white>%duration%", "duration", messages().duration(duration)));
        double warn = gui.plugin().getConfig().getDouble("insight.overpay-warning-percent", 200.0D);
        if (insight != null && insight.average() > 0 && warn > 0 && price > insight.average() * (1.0D + warn / 100.0D)) {
            lines.add(Component.empty());
            lines.add(lore("gui.create.overpay", "<red>⚠ That is %times%× the average price!", "times",
                    String.format(java.util.Locale.US, "%.1f", price / insight.average())));
        }
        double[] demand = orders.cache().demand(spec.key(), System.currentTimeMillis());
        if (demand[2] > 0 && price > demand[0]) {
            lines.add(lore("gui.create.first-in-line", "<green>Your order will be first in line"));
        }
        lines.add(Component.empty());
        if (problem.isPresent()) {
            lines.add(messages().component(problem.get()));
            return button("confirm-blocked", Material.GRAY_CONCRETE).name(name("gui.create.cannot", "<red>Cannot create yet")).lore(lines).build();
        }
        lines.add(lore("gui.create.confirm-hint", "<yellow>Click</yellow> <gray>to create the order"));
        return button("confirm", Material.LIME_CONCRETE).name(name("gui.create.confirm", "<green><b>Create order")).lore(lines).build();
    }

    private double outbid(double best) {
        double step = gui.plugin().getConfig().getDouble("insight.outbid-step", 0.0D);
        return Money.round(best + (step > 0 ? step : Math.max(0.01D, best * 0.01D)));
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() != getInventory() || submitting) return;
        int slot = event.getRawSlot();
        OrderService orders = gui.orders();
        int maxAmount = orders.rules().maxAmount(player);
        for (int[] button : AMOUNT_STEPS) {
            if (button[0] != slot) continue;
            amount = (int) Math.max(1, Math.min(maxAmount, (long) amount + button[1]));
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
            double change = Math.max(0.01D, Math.abs(price * factor)) * Math.signum(factor);
            setPrice(price + change);
            redraw(player);
            return;
        }
        if (slot >= 36 && slot <= 44) {
            List<Long> options = orders.rules().durationOptions(player);
            int start = 40 - Math.min(options.size(), 9) / 2;
            int index = slot - start;
            if (index >= 0 && index < Math.min(options.size(), 9)) {
                durationIndex = index;
                redraw(player);
            }
            return;
        }
        switch (slot) {
            case AMOUNT -> gui.ask(player, "input.amount", "&eType the amount you want in chat, or &6%cancel% &eto go back.", (who, text) -> {
                try {
                    long parsed = Long.parseLong(text.replace(",", "").replace("_", "").trim());
                    if (parsed < 1 || parsed > maxAmount) throw new NumberFormatException();
                    amount = (int) parsed;
                } catch (NumberFormatException invalid) {
                    gui.plugin().messages().sendFormatted(who, "input.invalid-amount", "&cType a whole number from 1 to %max%.",
                            "max", String.valueOf(maxAmount));
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
            case OUTBID -> {
                double[] demand = orders.cache().demand(spec.key(), System.currentTimeMillis());
                if (demand[0] > 0) {
                    setPrice(outbid(demand[0]));
                    redraw(player);
                }
            }
            case AVERAGE -> {
                if (insight != null && insight.average() > 0) {
                    setPrice(insight.average());
                    redraw(player);
                }
            }
            case BACK -> {
                click(player);
                gui.openCatalog(player);
            }
            case CLOSE -> player.closeInventory();
            case CONFIRM -> submit(player);
            default -> {
            }
        }
    }

    private void setPrice(double value) {
        OrderService orders = gui.orders();
        double min = orders.rules().minPrice(spec.key(), spec.template().getType());
        double max = orders.rules().maxPrice(spec.key(), spec.template().getType());
        price = Money.round(Math.max(min, Math.min(max, value)));
    }

    private void redraw(Player player) {
        click(player);
        draw(player, getInventory());
    }

    private void submit(Player player) {
        OrderService orders = gui.orders();
        List<Long> options = orders.rules().durationOptions(player);
        long duration = options.get(Math.max(0, Math.min(durationIndex, options.size() - 1)));
        Optional<String> problem = orders.checkCreate(player, spec, amount, price, duration);
        if (problem.isPresent()) {
            gui.plugin().messages().send(player, problem.get());
            Sounds.play(player, gui.plugin().getConfig(), "error");
            return;
        }
        submitting = true;
        player.closeInventory();
        orders.create(player, spec, amount, price, duration).thenAccept(result -> gui.plugin().schedulerAdapter().runEntity(player, () -> {
            gui.result(player, result);
            if (result.success()) gui.openMyOrders(player, 1);
            else gui.open(player, this);
        }));
    }
}

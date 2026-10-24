package io.nightbeam.donutorders.gui;

import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.service.OrderService;
import io.nightbeam.donutorders.util.ItemNames;
import io.nightbeam.donutorders.util.Money;
import io.nightbeam.donutorders.util.Sounds;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Players put the items they deliver into the top five rows; only items the order accepts go in. The menu holds
 * them until the player confirms (they go to the order service, which returns what the order did not take) or
 * leaves (everything goes back). A one-way state makes sure the items take exactly one of those two paths.
 */
final class DeliverGui extends BaseGui {

    private enum State { OPEN, SUBMITTED, RETURNED }

    static final int INPUT = 45;
    private static final int INFO = 45, FILL = 47, CONFIRM = 49, BACK = 51, CANCEL = 53;
    /** Click actions allowed in the input slots; anything else (number keys, double-click, bundles, ...) is refused. */
    private static final Set<String> ALLOWED = Set.of("PICKUP_ALL", "PICKUP_HALF", "PICKUP_SOME", "PICKUP_ONE", "PLACE_ALL",
            "PLACE_SOME", "PLACE_ONE", "SWAP_WITH_CURSOR", "MOVE_TO_OTHER_INVENTORY", "NOTHING");

    private final Order initial;
    private State state = State.OPEN;
    private boolean refreshQueued;
    private Player viewer;

    DeliverGui(GuiManager gui, Order order) {
        super(gui);
        this.initial = order;
    }

    @Override
    public boolean takesItems() {
        return true;
    }

    private Order order() {
        return gui.orders().cache().get(initial.id()).orElse(initial);
    }

    @Override
    public Inventory render(Player player) {
        viewer = player;
        Inventory inventory = create(6, name("gui.deliver.title", "Deliver to %player%", "player", initial.ownerName()));
        renderBar(inventory);
        return inventory;
    }

    private void renderBar(Inventory inventory) {
        Order order = order();
        OrderService orders = gui.orders();
        List<ItemStack> offered = offered(inventory);
        int matching = Math.min(orders.matcher().count(order, offered), order.remaining());
        double gross = Money.times(matching, order.price());
        double tax = Money.round(gross * orders.fees().taxRate(viewer));
        List<Component> info = OrderIcons.details(messages(), orders, order);
        info.add(Component.empty());
        info.add(lore("gui.deliver.how", "<gray>Put the items in the slots above."));
        info.add(lore("gui.deliver.how-2", "<gray>Shulker boxes are opened for you."));
        inventory.setItem(INFO, OrderIcons.icon(order, info));
        inventory.setItem(FILL, button("fill", Material.HOPPER).name(name("gui.deliver.fill", "<#49d6a0>Add from inventory"))
                .lore(lines(lore("gui.deliver.fill-line", "<gray>Moves every matching item from your inventory"))).build());
        inventory.setItem(CONFIRM, button("deliver", matching > 0 ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE)
                .name(name("gui.deliver.confirm", "<green><b>Deliver</b> %amount%× %item%", "amount", String.valueOf(matching),
                        "item", ItemNames.fragment(order.template())))
                .lore(lines(lore("gui.deliver.payout", "<gray>You get: <green>%amount%", "amount", orders.format(Money.minus(gross, tax))),
                        tax > 0 ? lore("gui.deliver.tax", "<gray>Tax: <red>-%amount%", "amount", orders.format(tax)) : null,
                        matching < orders.matcher().count(order, offered)
                                ? lore("gui.deliver.extra", "<gray>Extra items come back to you") : null)).build());
        inventory.setItem(BACK, button("back", Material.ARROW).name(name("gui.back", "<yellow>Back")).build());
        inventory.setItem(CANCEL, button("close", Material.BARRIER).name(name("gui.deliver.cancel", "<red>Cancel"))
                .lore(lines(lore("gui.deliver.cancel-line", "<gray>Your items come back"))).build());
        fill(inventory, 45, 53);
    }

    private static List<ItemStack> offered(Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        for (int slot = 0; slot < INPUT; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) items.add(item);
        }
        return items;
    }

    @Override
    public void handleClick(Player player, InventoryClickEvent event) {
        if (state != State.OPEN) {
            event.setCancelled(true);
            return;
        }
        int raw = event.getRawSlot();
        String action = event.getAction().name();
        int top = getInventory().getSize();
        if (raw >= top) {
            // The player's own inventory: free, except moves into the menu (checked here) and double-click gathering.
            if (action.equals("COLLECT_TO_CURSOR")) {
                event.setCancelled(true);
            } else if (action.equals("MOVE_TO_OTHER_INVENTORY")) {
                event.setCancelled(true);
                moveIn(player, event.getSlot());
                queueRefresh(player);
            }
            return;
        }
        if (raw >= INPUT) {
            event.setCancelled(true);
            handleButton(player, raw);
            return;
        }
        if (!ALLOWED.contains(action)) {
            event.setCancelled(true);
            return;
        }
        if (action.startsWith("PLACE") || action.equals("SWAP_WITH_CURSOR")) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir() && !gui.orders().matcher().accepts(order(), cursor)) {
                event.setCancelled(true);
                wrongItem(player);
                return;
            }
        }
        queueRefresh(player);
    }

    @Override
    public void handleDrag(Player player, InventoryDragEvent event) {
        if (state != State.OPEN) {
            event.setCancelled(true);
            return;
        }
        boolean intoMenu = false;
        for (int raw : event.getRawSlots()) {
            if (raw < getInventory().getSize()) {
                if (raw >= INPUT) {
                    event.setCancelled(true);
                    return;
                }
                intoMenu = true;
            }
        }
        if (intoMenu && !gui.orders().matcher().accepts(order(), event.getOldCursor())) {
            event.setCancelled(true);
            wrongItem(player);
            return;
        }
        queueRefresh(player);
    }

    private void handleButton(Player player, int slot) {
        switch (slot) {
            case FILL -> {
                ItemStack[] contents = player.getInventory().getStorageContents();
                for (int i = 0; i < contents.length; i++) {
                    if (contents[i] != null && gui.orders().matcher().accepts(order(), contents[i])) moveIn(player, i);
                }
                click(player);
                renderBar(getInventory());
            }
            case CONFIRM -> confirm(player);
            case BACK -> {
                click(player);
                gui.openBoard(player);
            }
            case CANCEL -> {
                click(player);
                player.closeInventory();
            }
            default -> {
            }
        }
    }

    /** Moves an accepted stack from a player inventory slot into the input slots, merging like the game does. */
    private void moveIn(Player player, int playerSlot) {
        ItemStack stack = player.getInventory().getItem(playerSlot);
        if (stack == null || stack.getType().isAir()) return;
        if (!gui.orders().matcher().accepts(order(), stack)) {
            wrongItem(player);
            return;
        }
        Inventory inventory = getInventory();
        int left = stack.getAmount();
        int max = stack.getMaxStackSize();
        for (int slot = 0; slot < INPUT && left > 0; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing != null && existing.isSimilar(stack) && existing.getAmount() < max) {
                int move = Math.min(left, max - existing.getAmount());
                existing.setAmount(existing.getAmount() + move);
                inventory.setItem(slot, existing);
                left -= move;
            }
        }
        for (int slot = 0; slot < INPUT && left > 0; slot++) {
            if (inventory.getItem(slot) != null) continue;
            ItemStack placed = stack.clone();
            placed.setAmount(Math.min(left, max));
            inventory.setItem(slot, placed);
            left -= placed.getAmount();
        }
        if (left <= 0) {
            player.getInventory().setItem(playerSlot, null);
        } else {
            ItemStack rest = stack.clone();
            rest.setAmount(left);
            player.getInventory().setItem(playerSlot, rest);
        }
    }

    private void confirm(Player player) {
        Order order = order();
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : offered(getInventory())) items.add(item.clone());
        if (gui.orders().matcher().count(order, items) <= 0) {
            gui.plugin().messages().sendRaw(player, "deliver.nothing-matches", "&cYou have nothing this order wants.");
            Sounds.play(player, gui.plugin().getConfig(), "error");
            return;
        }
        state = State.SUBMITTED;
        for (int slot = 0; slot < INPUT; slot++) getInventory().setItem(slot, null);
        player.closeInventory();
        gui.orders().deliver(player, order.id(), items).thenAccept(summary -> gui.plugin().schedulerAdapter().runEntity(player, () -> {
            gui.plugin().messages().send(player, gui.orders().describe(summary));
            if (summary.delivered() <= 0) Sounds.play(player, gui.plugin().getConfig(), "error");
            if (gui.plugin().getConfig().getBoolean("delivery.reopen-board", true) && !(player.getOpenInventory().getTopInventory().getHolder(false) instanceof BaseGui)) {
                gui.openBoard(player);
            }
        }));
    }

    /** Everything in the input slots goes back to the player (the mailbox if it does not fit, or if they died). */
    @Override
    public void onClose(Player player) {
        if (state != State.OPEN) return;
        state = State.RETURNED;
        List<ItemStack> items = new ArrayList<>();
        for (int slot = 0; slot < INPUT; slot++) {
            ItemStack item = getInventory().getItem(slot);
            if (item != null && !item.getType().isAir()) items.add(item.clone());
            getInventory().setItem(slot, null);
        }
        if (items.isEmpty()) return;
        if (player.isDead() || !player.isOnline()) {
            gui.orders().mail(player.getUniqueId(), items, "returned");
            return;
        }
        Map<Integer, ItemStack> left = player.getInventory().addItem(items.toArray(new ItemStack[0]));
        if (!left.isEmpty()) gui.orders().mail(player.getUniqueId(), left.values(), "returned");
    }

    /** True while the menu still holds the player's items (for shutdown). */
    boolean holdingItems() {
        return state == State.OPEN;
    }

    private void wrongItem(Player player) {
        gui.plugin().messages().sendFormatted(player, "deliver.wrong-item", "&cThis order only takes %item%.",
                "item", ItemNames.fragment(order().template()));
        Sounds.play(player, gui.plugin().getConfig(), "error");
    }

    private void queueRefresh(Player player) {
        if (refreshQueued) return;
        refreshQueued = true;
        gui.plugin().schedulerAdapter().runEntityLater(player, () -> {
            refreshQueued = false;
            if (state == State.OPEN && player.getOpenInventory().getTopInventory().getHolder(false) == this) renderBar(getInventory());
        }, 1L);
    }
}

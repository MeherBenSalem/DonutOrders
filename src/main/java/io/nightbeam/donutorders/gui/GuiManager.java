package io.nightbeam.donutorders.gui;

import io.nightbeam.donutorders.DonutOrdersPlugin;
import io.nightbeam.donutorders.model.BrowseRequest;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.model.OrderSort;
import io.nightbeam.donutorders.service.ActionResult;
import io.nightbeam.donutorders.service.ItemKeys;
import io.nightbeam.donutorders.service.OrderService;
import io.nightbeam.donutorders.util.ItemBuilder;
import io.nightbeam.donutorders.util.Sounds;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/** Opens menus, remembers what each player was looking at, and asks questions in chat. */
public final class GuiManager {

    /** A question asked in chat: an amount, a price or a search. */
    private record InputRequest(long id, BiConsumer<Player, String> handler, Runnable onCancel) {
    }

    private static final long INPUT_TIMEOUT_TICKS = 20L * 60L;

    private final DonutOrdersPlugin plugin;
    private final OrderService orders;
    private final Map<UUID, BrowseRequest> boards = new ConcurrentHashMap<>();
    private final Map<UUID, InputRequest> inputs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();

    public GuiManager(DonutOrdersPlugin plugin, OrderService orders) {
        this.plugin = plugin;
        this.orders = orders;
    }

    public DonutOrdersPlugin plugin() {
        return plugin;
    }

    public OrderService orders() {
        return orders;
    }

    // ------------------------------------------------------------------ opening

    public void open(Player player, BaseGui gui) {
        Inventory inventory = gui.render(player);
        player.openInventory(inventory);
    }

    public void openBoard(Player player) {
        open(player, new BoardGui(this, board(player)));
    }

    public void openBoard(Player player, BrowseRequest request) {
        boards.put(player.getUniqueId(), request);
        open(player, new BoardGui(this, request));
    }

    public BrowseRequest board(Player player) {
        return boards.computeIfAbsent(player.getUniqueId(), id -> BrowseRequest.initial(
                OrderSort.parse(plugin.getConfig().getString("menus.default-sort"), OrderSort.MONEY_PER_ITEM)));
    }

    public void remember(Player player, BrowseRequest request) {
        boards.put(player.getUniqueId(), request);
    }

    public void openDeliver(Player player, Order order) {
        var problem = orders.checkDeliver(player, order);
        if (problem.isPresent()) {
            plugin.messages().send(player, problem.get());
            Sounds.play(player, plugin.getConfig(), "error");
            return;
        }
        open(player, new DeliverGui(this, order));
    }

    public void openMyOrders(Player player, int page) {
        open(player, new MyOrdersGui(this, page));
    }

    public void openCatalog(Player player) {
        open(player, new CatalogGui(this, io.nightbeam.donutorders.model.OrderCategory.ALL, "", 1));
    }

    public void openCreate(Player player, ItemKeys.Spec spec) {
        open(player, new CreateGui(this, spec, player));
    }

    public void openHistory(Player player, int page, boolean deliveries) {
        openHistory(player, player.getUniqueId(), page, deliveries);
    }

    /** A player's history; staff may look at anyone's. */
    public void openHistory(Player player, UUID owner, int page, boolean deliveries) {
        HistoryGui history = new HistoryGui(this, owner, page, deliveries);
        open(player, history);
        history.load(player);
    }

    public void openEdit(Player player, Order order) {
        open(player, new EditGui(this, order));
    }

    public void confirm(Player player, ConfirmGui.Request request) {
        open(player, new ConfirmGui(this, request));
    }

    // ------------------------------------------------------------------ buttons and feedback

    /** A button item; {@code menus.buttons.<key>.material} and {@code .model} override the defaults. */
    public ItemBuilder button(String key, Material fallback) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("menus.buttons." + key);
        Material material = fallback;
        int model = 0;
        if (section != null) {
            Material configured = Material.matchMaterial(section.getString("material", ""));
            if (configured != null && configured.isItem() && !configured.isAir()) material = configured;
            model = section.getInt("model", 0);
        }
        return ItemBuilder.of(material).model(model).hideAll();
    }

    public void click(Player player) {
        Sounds.play(player, plugin.getConfig(), "click");
    }

    /** Shows an action's message with the matching sound. */
    public void result(Player player, ActionResult result) {
        plugin.messages().send(player, result.message());
        Sounds.play(player, plugin.getConfig(), result.success() ? "success" : "error");
    }

    // ------------------------------------------------------------------ chat questions

    /**
     * Closes the menu and asks a question in chat. The answer (or the cancel word, or a one-minute timeout) is
     * handled on the player's thread.
     */
    public void ask(Player player, String promptKey, String promptDefault, BiConsumer<Player, String> handler, Runnable onCancel) {
        long id = System.nanoTime();
        inputs.put(player.getUniqueId(), new InputRequest(id, handler, onCancel));
        plugin.messages().sendFormatted(player, promptKey, promptDefault, "cancel", cancelWord());
        player.closeInventory();
        plugin.schedulerAdapter().runEntityLater(player, () -> {
            InputRequest pending = inputs.get(player.getUniqueId());
            if (pending != null && pending.id() == id && inputs.remove(player.getUniqueId(), pending)) {
                plugin.messages().sendRaw(player, "input.timeout", "&7No answer, so nothing was changed.");
                pending.onCancel().run();
            }
        }, INPUT_TIMEOUT_TICKS);
    }

    public boolean awaitingInput(UUID player) {
        return inputs.containsKey(player);
    }

    /** Called on the player's thread with what they typed. */
    public void answer(Player player, String text) {
        InputRequest request = inputs.remove(player.getUniqueId());
        if (request == null) return;
        String value = text == null ? "" : text.trim();
        if (value.equalsIgnoreCase(cancelWord()) || value.equalsIgnoreCase("cancel")) {
            plugin.messages().sendRaw(player, "input.cancelled", "&7Cancelled.");
            request.onCancel().run();
            return;
        }
        request.handler().accept(player, value);
    }

    private String cancelWord() {
        return plugin.messages().raw("input.cancel-word", "cancel").toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ events

    public void handleClick(Player player, InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof BaseGui gui)) return;
        if (!gui.takesItems()) event.setCancelled(true);
        // Macro clickers: one action per 50 ms is plenty for a person.
        long now = System.currentTimeMillis();
        Long last = lastClick.put(player.getUniqueId(), now);
        if (!gui.takesItems() && last != null && now - last < 50L) {
            event.setCancelled(true);
            return;
        }
        gui.handleClick(player, event);
    }

    public void handleDrag(Player player, InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof BaseGui gui) gui.handleDrag(player, event);
    }

    /** Closes every open menu, handing items in delivery menus back first (plugin disable). */
    public void closeAll() {
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            try {
                if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof BaseGui gui) {
                    gui.onClose(player);
                    player.closeInventory();
                }
            } catch (RuntimeException notThisThread) {
                // Folia: another region owns this player; their menu closes with the server.
            }
        }
    }

    public void clearCaches() {
        CatalogGui.clearCache();
    }

    public void forget(Player player) {
        inputs.remove(player.getUniqueId());
        boards.remove(player.getUniqueId());
        lastClick.remove(player.getUniqueId());
    }
}

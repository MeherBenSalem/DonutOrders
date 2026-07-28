package com.donutorders.manager;

import com.donutorders.DonutOrders;
import com.donutorders.gui.*;
import com.donutorders.model.Order;
import com.donutorders.scheduler.FoliaScheduler;
import com.donutorders.storage.StorageManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates all GUI interactions and tracks each player's current GUI state.
 *
 * <h2>Opening pattern (Folia-safe)</h2>
 * Every {@code open*()} method first loads any required async data (e.g.
 * stash from DB), then <em>re-schedules to the player's entity thread</em> via
 * {@link FoliaScheduler#runAtEntity} before calling
 * {@link Player#openInventory}. This is correct for both Paper and Folia.
 *
 * <h2>State tracking</h2>
 * {@link PlayerGUIState} is stored in a {@link ConcurrentHashMap} and lets the
 * {@link com.donutorders.listener.InventoryListener} route click events to the
 * currently active GUI class without the GUI having to register itself.
 */
public class GUIManager {

    private final StorageManager storage;
    private final OrderManager orderManager;
    private final ChatInputHandler chatInput;

    /** Current GUI state of each online player. */
    private final ConcurrentHashMap<UUID, PlayerGUIState> states = new ConcurrentHashMap<>();

    public GUIManager(StorageManager storage, OrderManager orderManager,
                      ChatInputHandler chatInput) {
        this.storage      = storage;
        this.orderManager = orderManager;
        this.chatInput    = chatInput;
    }

    // ── State model ───────────────────────────────────────────────────────────

    /** Represents one player's open GUI context. */
    public static class PlayerGUIState {
        public GUIType type;
        public BaseGUI gui;
        /** Order id for delivery / collect / detail views. May be null. */
        public UUID contextOrderId;
        /** Tracks items held in DeliverItemsGUI so they can be returned on close. */
        public ItemStack[] deliverySlots;
        /** The material selected in NewOrderGUI, carried into chat input. */
        public ItemStack selectedItem;
        /** Pending amount entered by chat; set before price prompt. */
        public Integer pendingAmount;
    }

    public enum GUIType {
        PUBLIC_ORDERS,
        YOUR_ORDERS,
        NEW_ORDER,
        DELIVER_ITEMS,
        CONFIRM_DELIVERY,
        COLLECT_STASH,
        ORDER_DETAIL
    }

    // ── Open methods ──────────────────────────────────────────────────────────

    /** Opens the public orders list (page 0 = first page). */
    public void openPublicOrders(Player player, int page) {
        FoliaScheduler.runAtEntity(player, () -> {
            List<Order> active = storage.getAllActiveOrders();
            PublicOrdersGUI gui = new PublicOrdersGUI(this, active, page);

            PlayerGUIState state = new PlayerGUIState();
            state.type = GUIType.PUBLIC_ORDERS;
            state.gui  = gui;
            // State is set AFTER openInventory because openInventory fires
            // InventoryCloseEvent synchronously, which calls clearState().
            player.openInventory(gui.getInventory());
            states.put(player.getUniqueId(), state);
        }, null);
    }

    /** Opens the buyer's own orders list. */
    public void openYourOrders(Player player, int page) {
        FoliaScheduler.runAtEntity(player, () -> {
            List<Order> playerOrders = storage.getPlayerOrders(player.getUniqueId());
            YourOrdersGUI gui = new YourOrdersGUI(this, playerOrders, page);

            PlayerGUIState state = new PlayerGUIState();
            state.type = GUIType.YOUR_ORDERS;
            state.gui  = gui;
            player.openInventory(gui.getInventory());
            states.put(player.getUniqueId(), state);
        }, null);
    }

    /** Opens the item picker for creating a new order (first page). */
    public void openNewOrderPicker(Player player) {
        openNewOrderPicker(player, 0);
    }

    /** Opens the item picker for creating a new order on the given page. */
    public void openNewOrderPicker(Player player, int page) {
        FoliaScheduler.runAtEntity(player, () -> {
            NewOrderGUI gui = new NewOrderGUI(this, Math.max(0, page));

            PlayerGUIState state = new PlayerGUIState();
            state.type = GUIType.NEW_ORDER;
            state.gui  = gui;
            player.openInventory(gui.getInventory());
            states.put(player.getUniqueId(), state);
        }, null);
    }

    /** Opens the delivery GUI for a given order. */
    public void openDeliverItems(Player player, UUID orderId) {
        Order order = storage.getOrder(orderId);
        if (order == null) return;

        FoliaScheduler.runAtEntity(player, () -> {
            DeliverItemsGUI gui = new DeliverItemsGUI(this, order);

            PlayerGUIState state = new PlayerGUIState();
            state.type            = GUIType.DELIVER_ITEMS;
            state.gui             = gui;
            state.contextOrderId  = orderId;
            state.deliverySlots   = new ItemStack[45];
            player.openInventory(gui.getInventory());
            states.put(player.getUniqueId(), state);
        }, null);
    }

    /**
     * Opens the delivery confirmation GUI.
     *
     * @param player  the selling player
     * @param orderId the order being fulfilled
     * @param items   the 45-slot input array with the items to deliver
     */
    public void openConfirmDelivery(Player player, UUID orderId, ItemStack[] items) {
        Order order = storage.getOrder(orderId);
        if (order == null) return;

        FoliaScheduler.runAtEntity(player, () -> {
            ConfirmDeliveryGUI gui = new ConfirmDeliveryGUI(this, player, order, items);

            PlayerGUIState state = new PlayerGUIState();
            state.type           = GUIType.CONFIRM_DELIVERY;
            state.gui            = gui;
            state.contextOrderId = orderId;
            state.deliverySlots  = items;
            player.openInventory(gui.getInventory());
            states.put(player.getUniqueId(), state);
        }, null);
    }

    /** Opens the stash collection GUI. Loads stash async then opens on entity thread. */
    public void openCollectStash(Player player, UUID orderId) {
        storage.loadStash(orderId, stash -> {
            // Back onto entity thread to open inventory
            FoliaScheduler.runAtEntity(player, () -> {
                Order order = storage.getOrder(orderId);
                if (order == null) return;

                CollectStashGUI gui = new CollectStashGUI(this, order, stash);

                PlayerGUIState state = new PlayerGUIState();
                state.type           = GUIType.COLLECT_STASH;
                state.gui            = gui;
                state.contextOrderId = orderId;
                player.openInventory(gui.getInventory());
                states.put(player.getUniqueId(), state);
            }, null);
        });
    }

    /** Opens the order detail view (for the buyer). */
    public void openOrderDetail(Player player, UUID orderId) {
        Order order = storage.getOrder(orderId);
        if (order == null) return;

        storage.loadStash(orderId, stash -> {
            FoliaScheduler.runAtEntity(player, () -> {
                boolean hasStashItems = false;
                for (ItemStack i : stash) {
                    if (i != null && i.getType() != Material.AIR) {
                        hasStashItems = true;
                        break;
                    }
                }
                OrderDetailGUI gui = new OrderDetailGUI(this, order, hasStashItems);

                PlayerGUIState state = new PlayerGUIState();
                state.type           = GUIType.ORDER_DETAIL;
                state.gui            = gui;
                state.contextOrderId = orderId;
                player.openInventory(gui.getInventory());
                states.put(player.getUniqueId(), state);
            }, null);
        });
    }

    // ── State access ──────────────────────────────────────────────────────────

    /**
     * Returns the active GUI state for {@code playerUUID}, or {@code null}.
     * Safe to call from any thread — reads from ConcurrentHashMap.
     */
    public PlayerGUIState getState(UUID playerUUID) {
        return states.get(playerUUID);
    }

    /** Removes GUI state for a player (called on inventory close / quit). */
    public void clearState(UUID playerUUID) {
        states.remove(playerUUID);
    }

    /**
     * Registers a GUI state directly. Used when a GUI class opens a new
     * inventory inline (e.g. pagination within the same GUI type) and needs
     * to restore state after {@code openInventory} fires the close event.
     */
    public void setState(UUID playerUUID, PlayerGUIState state) {
        states.put(playerUUID, state);
    }

    // ── Dependency accessors (used by GUI classes) ────────────────────────────

    public StorageManager    getStorage()      { return storage;      }
    public OrderManager      getOrderManager() { return orderManager; }
    public ChatInputHandler  getChatInput()    { return chatInput;    }
}

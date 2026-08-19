package com.donutorders.manager;

import com.donutorders.DonutOrders;
import com.donutorders.model.Order;
import com.donutorders.model.OrderStatus;
import com.donutorders.scheduler.FoliaScheduler;
import com.donutorders.storage.StorageManager;
import com.donutorders.util.DeliveryItemUtils;
import com.donutorders.util.ItemUtils;
import com.donutorders.util.MessageHelper;
import com.donutorders.util.NumberFormatter;
import com.donutorders.util.OrderBroadcast;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central business-logic manager for buy orders.
 *
 * <h2>Thread-safety model</h2>
 * <ol>
 *   <li>All public methods are safe to call from any thread (region or async).</li>
 *   <li>Database writes are dispatched via {@link FoliaScheduler#runAsync}
 *       inside {@link StorageManager} — they never block a region thread.</li>
 *   <li>Vault {@code deposit}/{@code withdraw} calls <b>must</b> run on the
 *       player's region thread. All economy operations in this class are
 *       therefore performed inside a {@link FoliaScheduler#runAtEntity} callback
 *       that is called after async DB work completes.</li>
 *   <li>Callbacks supplied by the GUI layer are executed on the entity/player
 *       thread so they can immediately open inventories or send messages.</li>
 * </ol>
 *
 * <h2>Anti-duplication</h2>
 * Each {@link Order} carries an {@link java.util.concurrent.atomic.AtomicBoolean}
 * delivery lock. {@link #fulfillOrder} first acquires this lock with a CAS;
 * if two concurrent deliveries race, only one proceeds.
 */
public class OrderManager {

    private static final Logger LOG = DonutOrders.getInstance().getLogger();

    private final StorageManager storage;
    private final Economy economy;

    public OrderManager(StorageManager storage, Economy economy) {
        this.storage = storage;
        this.economy = economy;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Creates a new buy order for {@code buyer}.
     *
     * <p>Steps:
     * <ol>
     *   <li>Validate active order count against config limit.</li>
     *   <li>Validate and withdraw funds via Vault (on entity thread).</li>
     *   <li>Persist the order async, then invoke {@code callback} on the
     *       player's thread.</li>
     * </ol>
     *
     * @param buyer        the player creating the order
     * @param itemTemplate the item material being ordered
     * @param amount       number of items requested (> 0)
     * @param pricePerItem price offered per single item (> 0)
     * @param callback     {@code (success, errorMessageOrNull)} invoked on the
     *                     player's region thread when done
     */
    public void createOrder(Player buyer,
                            ItemStack itemTemplate,
                            int amount,
                            double pricePerItem,
                            BiConsumer<Boolean, String> callback) {

        // Validation is cheap — can run on any thread
        int maxOrders = DonutOrders.getInstance().getOrderLimitManager().getLimitValue(buyer);
        List<Order> existing = storage.getPlayerOrders(buyer.getUniqueId());
        long activeCount = existing.stream()
                .filter(o -> o.getStatus() == OrderStatus.ACTIVE).count();
        if (activeCount >= maxOrders) {
            String msg = MessageHelper.get("max-orders-reached",
                    "&cʏᴏᴜ ʜᴀᴠᴇ ʀᴇᴀᴄʜᴇᴅ ᴛʜᴇ ᴍᴀxɪᴍᴜᴍ ɴᴜᴍʙᴇʀ ᴏꜰ ᴀᴄᴛɪᴠᴇ ᴏʀᴅᴇʀꜱ ({0}).",
                    maxOrders);
            FoliaScheduler.runAtEntity(buyer,
                    () -> callback.accept(false, msg),
                    () -> callback.accept(false, msg));
            return;
        }

        AllowedItemsManager itemsManager = DonutOrders.getInstance().getAllowedItemsManager();
        if (itemsManager == null
                || itemTemplate == null
                || !itemsManager.isAllowed(itemTemplate.getType())) {
            String msg = MessageHelper.get("item-not-allowed",
                    "&cᴛʜᴀᴛ ɪᴛᴇᴍ ɪꜱ ɴᴏᴛ ᴀʟʟᴏᴡᴇᴅ ꜰᴏʀ ᴏʀᴅᴇʀꜱ.");
            FoliaScheduler.runAtEntity(buyer,
                    () -> callback.accept(false, msg),
                    () -> callback.accept(false, msg));
            return;
        }

        double total = pricePerItem * amount;
        // Apply optional tax sink
        double tax = DonutOrders.getInstance().getConfig()
                .getDouble("orders.tax-percent", 0.0) / 100.0;
        double taxedTotal = total * (1.0 + tax);

        // Vault operations MUST run on the entity's region thread.
        FoliaScheduler.runAtEntity(buyer, () -> {
            if (!economy.has(buyer, taxedTotal)) {
                callback.accept(false, MessageHelper.get("insufficient-funds",
                        "&cɪɴꜱᴜꜰꜰɪᴄɪᴇɴᴛ ꜰᴜɴᴅꜱ. ʏᴏᴜ ɴᴇᴇᴅ &f{0}&c ʙᴜᴛ ᴏɴʟʏ ʜᴀᴠᴇ &f{1}&c.",
                        NumberFormatter.formatPrice(taxedTotal),
                        NumberFormatter.formatPrice(economy.getBalance(buyer))));
                return;
            }

            economy.withdrawPlayer(buyer, taxedTotal);

            // Build the order object
            long now = System.currentTimeMillis();
            int expiryDays = DonutOrders.getInstance().getConfig()
                    .getInt("orders.default-expiry-days", 7);
            long expiresAt = now + (long) expiryDays * 86_400_000L;

            Order order = new Order(
                    UUID.randomUUID(),
                    buyer.getUniqueId(),
                    buyer.getName(),
                    itemTemplate.clone(),
                    amount,
                    0,
                    pricePerItem,
                    total,          // funds held = amount * price (tax is sunk already)
                    now,
                    expiresAt,
                    OrderStatus.ACTIVE
            );

            // Persist async; callback from storage stays on async thread, so
            // we re-schedule back to the player thread here.
            storage.saveOrder(order, () ->
                    FoliaScheduler.runAtEntity(buyer, () -> {
                        broadcastOrderCreated(buyer, order);
                        callback.accept(true, null);
                    }, () -> {
                        broadcastOrderCreated(buyer, order);
                        callback.accept(true, null);
                    }));

        }, () -> callback.accept(false, MessageHelper.get("player-retired",
                "&cᴘʟᴀʏᴇʀ ɪꜱ ɴᴏ ʟᴏɴɢᴇʀ ᴀᴠᴀɪʟᴀʙʟᴇ.")));
    }

    // ── Fulfill (Deliver items) ────────────────────────────────────────────────

    /**
     * Processes a delivery of items from {@code seller} to fill {@code orderId}.
     *
     * <p>The {@code items} array is the 45-slot DeliverItemsGUI input area.
     * Only slots containing the correct material are counted.
     *
     * <p>Anti-duplication: acquires per-order {@link Order#tryLockDelivery()}
     * before any money or item movement. Releases lock on both success and
     * failure.
     *
     * @param seller      the player delivering items
     * @param orderId     the order being fulfilled
     * @param items       items placed in the delivery GUI (45 slots)
     * @param callback    {@code (success, errorMessageOrNull)} on seller's thread
     */
    public void fulfillOrder(Player seller,
                             UUID orderId,
                             ItemStack[] items,
                             BiConsumer<Boolean, String> callback) {

        storage.refreshOrderFromDb(orderId, refreshed -> {
            Order order = refreshed;
            if (order == null) {
                String msg = MessageHelper.get("order-not-found", "&cᴏʀᴅᴇʀ ɴᴏᴛ ꜰᴏᴜɴᴅ.");
                FoliaScheduler.runAtEntity(seller,
                        () -> callback.accept(false, msg),
                        () -> callback.accept(false, msg));
                return;
            }

            if (order.getStatus() != OrderStatus.ACTIVE) {
                String msg = MessageHelper.get("order-no-longer-active",
                        "&cᴏʀᴅᴇʀ ɪꜱ ɴᴏ ʟᴏɴɢᴇʀ ᴀᴄᴛɪᴠᴇ.");
                FoliaScheduler.runAtEntity(seller,
                        () -> callback.accept(false, msg),
                        () -> callback.accept(false, msg));
                return;
            }

            if (order.getBuyerUUID().equals(seller.getUniqueId())) {
                String msg = MessageHelper.get("delivery-own-order",
                        "&cʏᴏᴜ ᴄᴀɴɴᴏᴛ ꜰᴜʟꜰɪʟʟ ʏᴏᴜʀ ᴏᴡɴ ᴏʀᴅᴇʀ.");
                FoliaScheduler.runAtEntity(seller,
                        () -> callback.accept(false, msg),
                        () -> callback.accept(false, msg));
                return;
            }

            int amountNeeded = order.getAmountRemaining();
            ItemStack template = order.getItemTemplate();
            int validCount = Math.min(
                    DeliveryItemUtils.countAvailable(seller, items, template),
                    amountNeeded);

            if (validCount == 0) {
                String msg = MessageHelper.get("delivery-no-items",
                        "&cʏᴏᴜ ʜᴀᴠᴇ ɴᴏ ᴠᴀʟɪᴅ ɪᴛᴇᴍꜱ ᴛᴏ ᴅᴇʟɪᴠᴇʀ.");
                FoliaScheduler.runAtEntity(seller,
                        () -> callback.accept(false, msg),
                        () -> callback.accept(false, msg));
                return;
            }

            if (!order.tryLockDelivery()) {
                String msg = MessageHelper.get("delivery-in-progress",
                        "&cᴅᴇʟɪᴠᴇʀʏ ᴀʟʀᴇᴀᴅʏ ɪɴ ᴘʀᴏɢʀᴇꜱꜱ.");
                FoliaScheduler.runAtEntity(seller,
                        () -> callback.accept(false, msg),
                        () -> callback.accept(false, msg));
                return;
            }

            final int expectedFilled = order.getAmountFulfilled();

            FoliaScheduler.runAtEntity(seller, () -> {
                try {
                    int deliverCount = Math.min(
                            DeliveryItemUtils.countAvailable(seller, items, template),
                            amountNeeded);
                    if (deliverCount == 0) {
                        callback.accept(false, MessageHelper.get("delivery-no-items",
                                "&cʏᴏᴜ ʜᴀᴠᴇ ɴᴏ ᴠᴀʟɪᴅ ɪᴛᴇᴍꜱ ᴛᴏ ᴅᴇʟɪᴠᴇʀ."));
                        return;
                    }

                    ItemStack[] stashItems = DeliveryItemUtils.extract(
                            seller, items, template, deliverCount);
                    int extractedCount = 0;
                    for (ItemStack stashItem : stashItems) {
                        if (stashItem != null) {
                            extractedCount += stashItem.getAmount();
                        }
                    }
                    if (extractedCount == 0) {
                        callback.accept(false, MessageHelper.get("delivery-no-items",
                                "&cʏᴏᴜ ʜᴀᴠᴇ ɴᴏ ᴠᴀʟɪᴅ ɪᴛᴇᴍꜱ ᴛᴏ ᴅᴇʟɪᴠᴇʀ."));
                        return;
                    }

                    double payout = order.getPricePerItem() * extractedCount;
                    economy.depositPlayer(seller, payout);

                    order.setAmountFulfilled(order.getAmountFulfilled() + extractedCount);
                    order.setRemainingFunds(order.getRemainingFunds() - payout);

                    if (order.isFullyFulfilled()) {
                        order.setStatus(OrderStatus.PENDING);
                    }

                    DeliveryItemUtils.returnSnapshotShulkers(seller, items);

                    storage.updateOrderOptimistic(order, expectedFilled, OrderStatus.ACTIVE, success -> {
                        if (!success) {
                            FoliaScheduler.runAtEntity(seller, () -> callback.accept(false,
                                    MessageHelper.get("order-no-longer-active",
                                            "&cᴏʀᴅᴇʀ ɪꜱ ɴᴏ ʟᴏɴɢᴇʀ ᴀᴄᴛɪᴠᴇ.")),
                                    () -> callback.accept(false,
                                            MessageHelper.get("order-no-longer-active",
                                                    "&cᴏʀᴅᴇʀ ɪꜱ ɴᴏ ʟᴏɴɢᴇʀ ᴀᴄᴛɪᴠᴇ.")));
                            return;
                        }
                        storage.loadStash(order.getOrderId(), existingStash -> {
                            ItemStack[] mergedStash = mergeStash(existingStash, stashItems);
                            storage.saveStash(order.getOrderId(), mergedStash, () ->
                                FoliaScheduler.runAtEntity(seller,
                                    () -> callback.accept(true, null),
                                    () -> callback.accept(true, null)));
                        });
                    });

                } finally {
                    order.unlockDelivery();
                }
            }, () -> {
                order.unlockDelivery();
                callback.accept(false, MessageHelper.get("player-retired",
                        "&cᴘʟᴀʏᴇʀ ɪꜱ ɴᴏ ʟᴏɴɢᴇʀ ᴀᴠᴀɪʟᴀʙʟᴇ."));
            });
        });
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    /**
     * Cancels {@code orderId} on behalf of {@code buyer}, refunding remaining
     * funds to the buyer's account and marking the stash for collection.
     *
     * @param buyer    the buyer requesting the cancellation
     * @param orderId  the order id
     * @param callback {@code (success)} on buyer's thread
     */
    public void cancelOrder(Player buyer, UUID orderId, Consumer<Boolean> callback) {
        Order order = storage.getOrder(orderId);
        if (order == null || !order.getBuyerUUID().equals(buyer.getUniqueId())) {
            FoliaScheduler.runAtEntity(buyer,
                    () -> callback.accept(false),
                    () -> callback.accept(false));
            return;
        }
        if (order.getStatus() != OrderStatus.ACTIVE) {
            FoliaScheduler.runAtEntity(buyer,
                    () -> callback.accept(false),
                    () -> callback.accept(false));
            return;
        }

        // Economy refund must be on the entity thread
        FoliaScheduler.runAtEntity(buyer, () -> {
            double refund = order.getRemainingFunds();
            if (refund > 0) {
                economy.depositPlayer(buyer, refund);
            }
            order.setRemainingFunds(0);
            order.setStatus(OrderStatus.PENDING);
            storage.updateOrder(order, () ->
                    FoliaScheduler.runAtEntity(buyer,
                            () -> callback.accept(true),
                            () -> callback.accept(true)));
        }, () -> callback.accept(false));
    }

    // ── Collect stash ─────────────────────────────────────────────────────────

    /**
     * Gives the buyer all items from their stash and, if the order is
     * cancelled/expired and remaining funds > 0, refunds those too.
     * If the player's inventory is full, items are dropped at their feet.
     *
     * @param buyer    the player collecting
     * @param orderId  the order to collect from
     * @param callback {@code (success)} on buyer's thread
     */
    public void collectStash(Player buyer, UUID orderId, Consumer<Boolean> callback) {
        storage.refreshOrderFromDb(orderId, order -> {
            if (order == null) {
                FoliaScheduler.runAtEntity(buyer,
                        () -> callback.accept(false),
                        () -> callback.accept(false));
                return;
            }

            if (!order.getBuyerUUID().equals(buyer.getUniqueId())) {
                LOG.log(Level.WARNING, "[Security] Player {0} attempted to claim stash of order {1} owned by {2} ({3}).",
                    new Object[]{buyer.getName(), orderId, order.getBuyerName(), order.getBuyerUUID()});
                FoliaScheduler.runAtEntity(buyer,
                        () -> callback.accept(false),
                        () -> callback.accept(false));
                return;
            }

            if (order.getStatus() == OrderStatus.CLAIMED) {
                LOG.log(Level.WARNING, "[Security] Player {0} tried to collect order {1} which was ALREADY CLAIMED (claimed at: {2}, claimed by: {3}). Replay attack blocked.",
                    new Object[]{buyer.getName(), orderId, order.getClaimedAt(), order.getClaimedBy()});
                FoliaScheduler.runAtEntity(buyer,
                        () -> callback.accept(false),
                        () -> callback.accept(false));
                return;
            }

            if (order.getStatus() != OrderStatus.PENDING) {
                FoliaScheduler.runAtEntity(buyer,
                        () -> callback.accept(false),
                        () -> callback.accept(false));
                return;
            }

            if (!order.tryLockClaim()) {
                LOG.log(Level.WARNING, "[Security] Player {0} triggered rapid concurrent collection for order {1}. Lock acquisition blocked.",
                    new Object[]{buyer.getName(), orderId});
                FoliaScheduler.runAtEntity(buyer,
                        () -> callback.accept(false),
                        () -> callback.accept(false));
                return;
            }

            if (order.getStatus() != OrderStatus.PENDING) {
                order.unlockClaim();
                FoliaScheduler.runAtEntity(buyer,
                        () -> callback.accept(false),
                        () -> callback.accept(false));
                return;
            }

            order.setStatus(OrderStatus.CLAIMED);
            order.setClaimedAt(System.currentTimeMillis());
            order.setClaimedBy(buyer.getUniqueId());

            storage.loadStash(orderId, stash ->
                storage.updateOrderOptimistic(order, null, OrderStatus.PENDING, success -> {
                    if (!success) {
                        order.unlockClaim();
                        FoliaScheduler.runAtEntity(buyer,
                                () -> callback.accept(false),
                                () -> callback.accept(false));
                        return;
                    }
                    storage.clearStash(orderId, () ->
                        FoliaScheduler.runAtEntity(buyer, () -> {
                            try {
                                for (ItemStack item : stash) {
                                    if (item != null && item.getType().isItem()) {
                                        Map<Integer, ItemStack> overflow = buyer.getInventory().addItem(item);
                                        if (!overflow.isEmpty()) {
                                            overflow.values().forEach(drop ->
                                                    buyer.getWorld().dropItemNaturally(buyer.getLocation(), drop));
                                        }
                                    }
                                }

                                double refund = order.getRemainingFunds();
                                if (refund > 0) {
                                    economy.depositPlayer(buyer, refund);
                                    order.setRemainingFunds(0);
                                    storage.updateOrder(order, null);
                                }

                                callback.accept(true);
                            } finally {
                                order.unlockClaim();
                            }
                        }, () -> {
                            order.unlockClaim();
                            callback.accept(false);
                        }));
                })
            );
        });
    }

    // ── Expiry ────────────────────────────────────────────────────────────────

    /**
     * Iterates all active orders and processes those that have expired.
     *
     * <p>Called by a {@link FoliaScheduler#runGlobalRepeating} task — runs on
     * the global/main thread. Expired orders are marked and the buyers are
     * notified if online; funds are refunded on their entity thread.
     */
    public void runExpiryCheck() {
        long now = System.currentTimeMillis();
        for (Order order : storage.getAllOrders()) {
            if (order.getStatus() == OrderStatus.ACTIVE
                    && order.getExpiresAt() <= now) {

                order.setStatus(OrderStatus.PENDING);
                storage.updateOrder(order, null);

                // Notify buyer if online
                Player buyer = Bukkit.getPlayer(order.getBuyerUUID());
                if (buyer != null && buyer.isOnline()) {
                    FoliaScheduler.runAtEntity(buyer, () -> {
                        MessageHelper.sendPrefixed(buyer, "order-expired",
                                "&7ʏᴏᴜʀ ᴏʀᴅᴇʀ ʜᴀꜱ ᴇxᴘɪʀᴇᴅ. &f{0} &7ʀᴇꜰᴜɴᴅᴇᴅ.",
                                NumberFormatter.formatPrice(order.getRemainingFunds()));
                    }, null);
                }
            }
        }
    }

    /**
     * Sends a public chat announce for a newly created order. The buyer is
     * skipped because they already received {@code order-created}.
     */
    private void broadcastOrderCreated(Player buyer, Order order) {
        DonutOrders plugin = DonutOrders.getInstance();
        if (plugin == null || !OrderBroadcast.isEnabled(plugin.getConfig())) {
            return;
        }
        String item = ItemUtils.describeOrderItem(order.getItemTemplate());
        String price = NumberFormatter.formatPrice(order.getPricePerItem());
        String buyerName = buyer != null ? buyer.getName() : order.getBuyerName();
        String message = MessageHelper.prefix()
                + OrderBroadcast.formatBody(buyerName, item, order.getAmountRequested(), price);
        UUID skip = buyer != null ? buyer.getUniqueId() : order.getBuyerUUID();
        FoliaScheduler.runGlobal(() -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!OrderBroadcast.shouldNotify(online.getUniqueId(), skip)) {
                    continue;
                }
                FoliaScheduler.runAtEntity(online, () -> online.sendMessage(message));
            }
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Merges new delivery items into the existing stash array, filling empty
     * slots or stacking compatible items.
     */
    private ItemStack[] mergeStash(ItemStack[] existing, ItemStack[] additions) {
        // Work on a 54-slot copy
        ItemStack[] stash = Arrays.copyOf(existing, 54);

        for (ItemStack add : additions) {
            if (add == null) continue;
            int leftOver = add.getAmount();
            // First pass: stack onto same-material stacks
            for (int i = 0; i < stash.length && leftOver > 0; i++) {
                if (stash[i] != null
                        && stash[i].isSimilar(add)
                        && stash[i].getAmount() < stash[i].getMaxStackSize()) {
                    int canAdd = stash[i].getMaxStackSize() - stash[i].getAmount();
                    int adding = Math.min(canAdd, leftOver);
                    stash[i].setAmount(stash[i].getAmount() + adding);
                    leftOver -= adding;
                }
            }
            // Second pass: fill empty slots
            for (int i = 0; i < stash.length && leftOver > 0; i++) {
                if (stash[i] == null) {
                    ItemStack copy = add.clone();
                    copy.setAmount(Math.min(leftOver, add.getMaxStackSize()));
                    stash[i] = copy;
                    leftOver -= copy.getAmount();
                }
            }
            // If there are still leftover items (stash full) they are silently
            // discarded here. In practice this won't happen because the stash
            // is 54 slots and we only deliver up to amountRemaining.
        }
        return stash;
    }
}

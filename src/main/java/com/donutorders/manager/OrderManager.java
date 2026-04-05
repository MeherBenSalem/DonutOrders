package com.donutorders.manager;

import com.donutorders.DonutOrders;
import com.donutorders.model.Order;
import com.donutorders.model.OrderStatus;
import com.donutorders.scheduler.FoliaScheduler;
import com.donutorders.storage.StorageManager;
import com.donutorders.util.ItemUtils;
import com.donutorders.util.NumberFormatter;
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
        int maxOrders = DonutOrders.getInstance().getConfig()
                .getInt("orders.max-per-player", 10);
        List<Order> existing = storage.getPlayerOrders(buyer.getUniqueId());
        long activeCount = existing.stream()
                .filter(o -> o.getStatus() == OrderStatus.ACTIVE).count();
        if (activeCount >= maxOrders) {
            // Bounce to player thread before calling callback (player's GUI is there)
            FoliaScheduler.runAtEntity(buyer,
                    () -> callback.accept(false,
                            "ᴍᴀx ᴏʀᴅᴇʀ ʟɪᴍɪᴛ: " + maxOrders),
                    () -> callback.accept(false, "ᴍᴀx ᴏʀᴅᴇʀ ʟɪᴍɪᴛ"));
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
                callback.accept(false,
                        "ɪɴꜱᴜꜰꜰɪᴄɪᴇɴᴛ ꜰᴜɴᴅꜱ. ɴᴇᴇᴅ "
                        + NumberFormatter.formatPrice(taxedTotal)
                        + ", ʜᴀᴠᴇ "
                        + NumberFormatter.formatPrice(economy.getBalance(buyer)));
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
                    FoliaScheduler.runAtEntity(buyer,
                            () -> callback.accept(true, null),
                            () -> callback.accept(true, null)));

        }, () -> callback.accept(false, "ʀᴇᴛɪʀᴇᴅ"));
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

        Order order = storage.getOrder(orderId);
        if (order == null) {
            FoliaScheduler.runAtEntity(seller,
                    () -> callback.accept(false, "ᴏʀᴅᴇʀ ɴᴏᴛ ꜰᴏᴜɴᴅ"),
                    () -> callback.accept(false, "ᴏʀᴅᴇʀ ɴᴏᴛ ꜰᴏᴜɴᴅ"));
            return;
        }

        if (order.getStatus() != OrderStatus.ACTIVE) {
            FoliaScheduler.runAtEntity(seller,
                    () -> callback.accept(false, "ᴏʀᴅᴇʀ ɪꜱ ɴᴏ ʟᴏɴɢᴇʀ ᴀᴄᴛɪᴠᴇ"),
                    () -> callback.accept(false, "ᴏʀᴅᴇʀ ɪꜱ ɴᴏ ʟᴏɴɢᴇʀ ᴀᴄᴛɪᴠᴇ"));
            return;
        }

        if (order.getBuyerUUID().equals(seller.getUniqueId())) {
            FoliaScheduler.runAtEntity(seller,
                    () -> callback.accept(false, "ᴄᴀɴɴᴏᴛ ꜰᴜʟꜰɪʟʟ ᴏᴡɴ ᴏʀᴅᴇʀ"),
                    () -> callback.accept(false, "ᴄᴀɴɴᴏᴛ ꜰᴜʟꜰɪʟʟ ᴏᴡɴ ᴏʀᴅᴇʀ"));
            return;
        }

        // Count valid items (correct material, up to remaining amount)
        int amountNeeded = order.getAmountRemaining();
        int validCount = 0;
        for (ItemStack item : items) {
            if (ItemUtils.isSameMaterial(item, order.getItemTemplate())
                    && item != null) {
                validCount += item.getAmount();
            }
        }
        validCount = Math.min(validCount, amountNeeded);

        if (validCount == 0) {
            FoliaScheduler.runAtEntity(seller,
                    () -> callback.accept(false, "ɴᴏ ᴠᴀʟɪᴅ ɪᴛᴇᴍꜱ"),
                    () -> callback.accept(false, "ɴᴏ ᴠᴀʟɪᴅ ɪᴛᴇᴍꜱ"));
            return;
        }

        // Acquire delivery lock — prevents concurrent duplicate deliveries
        if (!order.tryLockDelivery()) {
            FoliaScheduler.runAtEntity(seller,
                    () -> callback.accept(false, "ᴅᴇʟɪᴠᴇʀʏ ᴀʟʀᴇᴀᴅʏ ɪɴ ᴘʀᴏɢʀᴇꜱꜱ"),
                    () -> callback.accept(false, "ᴅᴇʟɪᴠᴇʀʏ ᴀʟʀᴇᴀᴅʏ ɪɴ ᴘʀᴏɢʀᴇꜱꜱ"));
            return;
        }

        final int finalValidCount = validCount;

        // All economy and inventory work MUST occur on the seller's region thread.
        FoliaScheduler.runAtEntity(seller, () -> {
            try {
                double payout = order.getPricePerItem() * finalValidCount;

                // Pay the seller
                economy.depositPlayer(seller, payout);

                // Update order in memory
                order.setAmountFulfilled(order.getAmountFulfilled() + finalValidCount);
                order.setRemainingFunds(order.getRemainingFunds() - payout);

                boolean nowComplete = order.isFullyFulfilled();
                if (nowComplete) {
                    order.setStatus(OrderStatus.COMPLETED);
                }

                // Collect the exactly-used items into the stash.
                // We add ONLY up to validCount items to the stash, discarding excess
                // matching items back to the seller (or keeping them in their inv).
                ItemStack[] stashItems = buildDeliveryStash(items, order.getItemTemplate(),
                        finalValidCount);

                // Persist order update first, then stash
                storage.updateOrder(order, () ->
                    storage.loadStash(order.getOrderId(), existingStash -> {
                        ItemStack[] mergedStash = mergeStash(existingStash, stashItems);
                        storage.saveStash(order.getOrderId(), mergedStash, () ->
                            FoliaScheduler.runAtEntity(seller,
                                () -> callback.accept(true, null),
                                () -> callback.accept(true, null)));
                    })
                );

            } finally {
                // Always release the lock, even if an exception was thrown
                order.unlockDelivery();
            }
        }, () -> {
            order.unlockDelivery();
            callback.accept(false, "ʀᴇᴛɪʀᴇᴅ");
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
            order.setStatus(OrderStatus.CANCELLED);
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
        Order order = storage.getOrder(orderId);
        if (order == null || !order.getBuyerUUID().equals(buyer.getUniqueId())) {
            FoliaScheduler.runAtEntity(buyer,
                    () -> callback.accept(false),
                    () -> callback.accept(false));
            return;
        }

        storage.loadStash(orderId, stash -> {
            // After async load, jump back to the player's region thread
            FoliaScheduler.runAtEntity(buyer, () -> {
                boolean anyItems = false;
                for (ItemStack item : stash) {
                    if (item != null && item.getType().isItem()) {
                        anyItems = true;
                        Map<Integer, ItemStack> overflow = buyer.getInventory().addItem(item);
                        if (!overflow.isEmpty()) {
                            // Drop overflow at player's feet
                            overflow.values().forEach(drop ->
                                    buyer.getWorld().dropItemNaturally(buyer.getLocation(), drop));
                        }
                    }
                }

                // Clear the stash in the database
                if (anyItems) {
                    storage.clearStash(orderId, null);
                }

                callback.accept(true);
            }, () -> callback.accept(false));
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

                order.setStatus(OrderStatus.EXPIRED);
                storage.updateOrder(order, null);

                // Notify buyer if online and refund remaining funds
                Player buyer = Bukkit.getPlayer(order.getBuyerUUID());
                if (buyer != null && buyer.isOnline()) {
                    FoliaScheduler.runAtEntity(buyer, () -> {
                        if (order.getRemainingFunds() > 0) {
                            economy.depositPlayer(buyer, order.getRemainingFunds());
                            order.setRemainingFunds(0);
                            storage.updateOrder(order, null);
                        }
                        String msg = DonutOrders.getInstance().getMessages()
                                .getString("order-expired",
                                        "&7ʏᴏᴜʀ ᴏʀᴅᴇʀ ʜᴀꜱ ᴇxᴘɪʀᴇᴅ.")
                                .replace("{0}", NumberFormatter.formatPrice(
                                        order.getPricePerItem() * order.getAmountRequested()));
                        buyer.sendMessage(DonutOrders.colorize(
                                DonutOrders.getInstance().getMessages()
                                        .getString("prefix", "") + msg));
                    }, null);
                }
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Extracts exactly {@code needed} items (matching template) from the
     * delivery GUI slot array into a flat ItemStack array for the stash.
     */
    private ItemStack[] buildDeliveryStash(ItemStack[] guiSlots, ItemStack template, int needed) {
        List<ItemStack> result = new ArrayList<>();
        int remaining = needed;
        for (ItemStack slot : guiSlots) {
            if (remaining <= 0) break;
            if (ItemUtils.isSameMaterial(slot, template) && slot != null) {
                int take = Math.min(slot.getAmount(), remaining);
                ItemStack copy = slot.clone();
                copy.setAmount(take);
                result.add(copy);
                remaining -= take;
            }
        }
        return result.toArray(new ItemStack[0]);
    }

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
                        && ItemUtils.isSameMaterial(stash[i], add)
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

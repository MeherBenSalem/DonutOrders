package io.nightbeam.donutorders.service;

import io.nightbeam.donutorders.DonutOrdersPlugin;
import io.nightbeam.donutorders.api.DonutOrdersApi;
import io.nightbeam.donutorders.api.event.OrderClosedEvent;
import io.nightbeam.donutorders.api.event.OrderCreatedEvent;
import io.nightbeam.donutorders.api.event.OrderDeliveredEvent;
import io.nightbeam.donutorders.api.event.OrderPreCreateEvent;
import io.nightbeam.donutorders.api.event.OrderPreDeliverEvent;
import io.nightbeam.donutorders.integration.DiscordWebhook;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.model.OrderStatus;
import io.nightbeam.donutorders.model.PlayerStats;
import io.nightbeam.donutorders.model.PriceInsight;
import io.nightbeam.donutorders.model.StoredStack;
import io.nightbeam.donutorders.storage.DatabaseType;
import io.nightbeam.donutorders.storage.LegacyImporter;
import io.nightbeam.donutorders.storage.SqlOrderRepository;
import io.nightbeam.donutorders.storage.SqlOrderRepository.DeliveryOutcome;
import io.nightbeam.donutorders.storage.SqlOrderRepository.DeliveryStatus;
import io.nightbeam.donutorders.sync.SyncBus;
import io.nightbeam.donutorders.util.InventorySpace;
import io.nightbeam.donutorders.util.ItemNames;
import io.nightbeam.donutorders.util.MessageUtil;
import io.nightbeam.donutorders.util.Money;
import io.nightbeam.donutorders.util.SchedulerAdapter;
import io.nightbeam.donutorders.util.Sounds;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The order book. Every flow follows the same rule: the database decides (a conditional update only one caller
 * can win), and money and items move only after it has. Items taken from a player are tracked in a
 * {@link Custody} until they are delivered or handed back, so a logout, a failed query or a shutdown can never
 * lose or duplicate them.
 */
public final class OrderService {

    /** The result of delivering to one or more orders. */
    public record Summary(int delivered, double gross, double tax, int orders, int filled, List<ItemStack> leftovers,
                          DeliveryStatus refusal, Order lastOrder, String refusalMessage) {

        public double net() {
            return Money.minus(gross, tax);
        }
    }

    /**
     * Items in the plugin's hands on behalf of a seller. {@code held} is everything not delivered and not yet given
     * back; {@code pending} is the database step in flight and the items it carries.
     */
    private final class Custody {
        final UUID seller;
        final String sellerName;
        final List<ItemStack> held = new ArrayList<>();
        final AtomicBoolean finished = new AtomicBoolean();
        volatile CompletableFuture<DeliveryOutcome> pending;
        volatile List<ItemStack> pendingItems = List.of();
        volatile Order pendingOrder;
        int delivered;
        double gross;
        double tax;
        int orders;
        int filled;
        DeliveryStatus refusal;
        Order lastOrder;
        String refusalMessage;

        Custody(Player seller, List<ItemStack> items) {
            this.seller = seller.getUniqueId();
            this.sellerName = seller.getName();
            for (ItemStack item : items) if (item != null && !item.getType().isAir()) held.add(item.clone());
        }

        Summary summary() {
            return new Summary(delivered, gross, tax, orders, filled, List.copyOf(held), refusal, lastOrder, refusalMessage);
        }
    }

    private final DonutOrdersPlugin plugin;
    private final SchedulerAdapter scheduler;
    private final SqlOrderRepository repository;
    private final DatabaseType databaseType;
    private final OrderCache cache = new OrderCache();
    private final ItemMatcher matcher;
    private final OrderRules rules;
    private final FeeCalculator fees;
    private final Payments payments;
    private final Notifier notifier;
    private final OrderEvents events;
    private final TransactionLog log;
    private final DiscordWebhook webhook;
    private final SyncBus sync;
    private final Set<UUID> closing = ConcurrentHashMap.newKeySet();
    private final Set<UUID> busyPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Custody> custodies = new ConcurrentHashMap<>();
    private final Map<String, Object[]> insights = new ConcurrentHashMap<>();
    private final AtomicLong lastSync = new AtomicLong();
    private final List<SchedulerAdapter.CancellableTask> tasks = new ArrayList<>();
    private volatile boolean ready;

    public OrderService(DonutOrdersPlugin plugin, SchedulerAdapter scheduler, SqlOrderRepository repository, DatabaseType databaseType,
                        Payments payments, FeeCalculator fees, OrderRules rules, SyncBus sync, TransactionLog log, DiscordWebhook webhook) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.repository = repository;
        this.databaseType = databaseType;
        this.payments = payments;
        this.fees = fees;
        this.rules = rules;
        this.sync = sync;
        this.log = log;
        this.webhook = webhook;
        this.matcher = new ItemMatcher(plugin::getConfig);
        this.notifier = new Notifier(plugin, scheduler, repository, sync, payments, this::format);
        this.events = new OrderEvents(scheduler.asyncExecutor(), plugin.getLogger());
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    private MessageUtil messages() {
        return plugin.messages();
    }

    private String msg(String path, String def, String... values) {
        return messages().format(path, def, values);
    }

    // ================================================================== lifecycle

    /** Creates tables, imports 1.x data once, loads the cache and starts the background tasks. */
    public CompletableFuture<Void> start() {
        return repository.initialize()
                .thenCompose(ignored -> new LegacyImporter(repository).importOnce())
                .thenCompose(imported -> {
                    if (imported.orders() > 0 || imported.skipped() > 0) {
                        plugin.getLogger().info("Imported " + imported.orders() + " orders and " + imported.stacks()
                                + " stored stacks from DonutOrders 1.x" + (imported.skipped() > 0 ? " (" + imported.skipped()
                                + " unreadable orders skipped)" : "") + ". The 1.x tables were left untouched.");
                    }
                    return repository.loadLive();
                })
                .thenAccept(orders -> {
                    cache.replaceAll(orders);
                    long newest = 0L;
                    for (Order order : orders) newest = Math.max(newest, order.updatedAt());
                    lastSync.set(Math.max(newest, System.currentTimeMillis() - 5_000L));
                    sync.setHandler((kind, id) -> {
                        if (kind == SyncBus.Kind.ORDER) refresh(id);
                        else if (kind == SyncBus.Kind.NOTICE) {
                            Player player = Bukkit.getPlayer(id);
                            if (player != null) notifier.showNotices(player);
                        }
                    });
                    ready = true;
                    scheduler.runGlobal(this::startTasks);
                });
    }

    private void startTasks() {
        tasks.add(scheduler.runGlobalRepeating(this::expireDue, 100L, 20L * Math.max(5L, config().getLong("orders.expiry-check-seconds", 20L))));
        if (databaseType == DatabaseType.MYSQL) {
            long seconds = Math.max(1L, config().getLong("sync.poll-seconds", 3L));
            tasks.add(scheduler.runGlobalRepeating(this::poll, 40L, 20L * seconds));
        }
        tasks.add(scheduler.runGlobalRepeating(this::retryOwed, 20L * 60L, 20L * 60L * 5L));
        tasks.add(scheduler.runGlobalRepeating(this::purge, 20L * 30L, 20L * 60L * 60L * 12L));
    }

    public boolean ready() {
        return ready;
    }

    /**
     * Stops the tasks, lets queued database work finish, then settles every item in custody: deliveries that
     * committed are paid, everything else is handed back (or mailed).
     */
    public void shutdown() {
        ready = false;
        tasks.forEach(SchedulerAdapter.CancellableTask::cancel);
        tasks.clear();
        scheduler.shutdown(10_000L);
        for (Custody custody : List.copyOf(custodies.values())) {
            try {
                settleOnShutdown(custody);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not settle items held for " + custody.sellerName + " on shutdown", exception);
            }
        }
        custodies.clear();
    }

    private void settleOnShutdown(Custody custody) {
        if (!custody.finished.compareAndSet(false, true)) return;
        CompletableFuture<DeliveryOutcome> pending = custody.pending;
        if (pending != null) {
            DeliveryOutcome outcome = pending.isDone() && !pending.isCompletedExceptionally() ? pending.getNow(null) : null;
            if (outcome != null && outcome.status() == DeliveryStatus.DELIVERED) {
                custody.held.addAll(outcome.overflow());
                double net = Money.minus(outcome.payout(), outcome.tax());
                OfflinePlayer seller = Bukkit.getOfflinePlayer(custody.seller);
                if (!payQuietly(seller, net)) owedNow(custody.seller, net, "delivery " + outcome.order().shortId());
                if (outcome.dust() > 0) owedNow(outcome.order().owner(), outcome.dust(), "rounding " + outcome.order().shortId());
                cache.apply(outcome.order());
            } else {
                custody.held.addAll(custody.pendingItems);
            }
        }
        if (custody.held.isEmpty()) return;
        Player player = Bukkit.getPlayer(custody.seller);
        List<ItemStack> left = new ArrayList<>(custody.held);
        if (player != null && player.isOnline()) {
            try {
                left = new ArrayList<>(player.getInventory().addItem(left.toArray(new ItemStack[0])).values());
            } catch (RuntimeException notHere) {
                // Not this thread's player on Folia: mail everything.
            }
        }
        if (!left.isEmpty()) {
            try {
                repository.addMailNow(custody.seller, left, "shutdown", System.currentTimeMillis());
            } catch (java.sql.SQLException exception) {
                plugin.getLogger().severe("Could not save " + left.size() + " stacks for " + custody.sellerName + " on shutdown: "
                        + exception.getMessage());
            }
        }
    }

    private boolean payQuietly(OfflinePlayer player, double amount) {
        if (!Money.positive(amount)) return true;
        try {
            return plugin.economy().deposit(player, Money.round(amount)).transactionSuccess();
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private void owedNow(UUID player, double amount, String reason) {
        if (!Money.positive(amount)) return;
        try {
            repository.addPayoutNow(player, amount, reason, System.currentTimeMillis());
        } catch (java.sql.SQLException exception) {
            plugin.getLogger().severe("Could not record " + amount + " owed to " + player + " (" + reason + "). Pay it manually.");
        }
    }

    // ================================================================== reading

    public OrderCache cache() {
        return cache;
    }

    public ItemMatcher matcher() {
        return matcher;
    }

    public OrderRules rules() {
        return rules;
    }

    public FeeCalculator fees() {
        return fees;
    }

    public Payments payments() {
        return payments;
    }

    public Notifier notifier() {
        return notifier;
    }

    public SqlOrderRepository repository() {
        return repository;
    }

    public DatabaseType databaseType() {
        return databaseType;
    }

    /** Money as players see it: the economy's own format, or compact (1.2k, 3.5M) when price-display.compact is on. */
    public String format(double amount) {
        FileConfiguration cfg = config();
        if (Double.isFinite(amount) && cfg.getBoolean("price-display.compact.enabled", false)) {
            return io.nightbeam.donutorders.util.PriceDisplay.compact(amount, cfg, payments::format, payments.currencyName());
        }
        return payments.format(amount);
    }

    /** Price insight for an item key, cached for a minute, with the open orders of this server added. */
    public CompletableFuture<PriceInsight> insight(String itemKey) {
        long now = System.currentTimeMillis();
        Object[] known = insights.get(itemKey);
        CompletableFuture<PriceInsight> history;
        if (known != null && now - (long) known[0] < 60_000L) {
            @SuppressWarnings("unchecked")
            CompletableFuture<PriceInsight> cached = (CompletableFuture<PriceInsight>) known[1];
            history = cached;
        } else {
            long days = Math.max(1L, config().getLong("insight.days", 7L));
            history = repository.insight(itemKey, now - days * 86_400_000L);
            insights.put(itemKey, new Object[] {now, history});
        }
        return history.thenApply(found -> {
            double[] demand = cache.demand(itemKey, System.currentTimeMillis());
            return found.withOpen(demand[0], (long) demand[1], (int) demand[2]);
        });
    }

    public CompletableFuture<PlayerStats> stats(UUID player) {
        return repository.stats(player);
    }

    /** Whether the player carries anything this order would take. */
    public boolean canDeliver(Player player, Order order) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && matcher.accepts(order, item)) return true;
        }
        return false;
    }

    /** The orders that take an item, best price first, without the player's own. */
    public List<Order> bestOrdersFor(ItemStack item, UUID exclude) {
        return cache.bestFor(item, exclude, matcher, System.currentTimeMillis());
    }

    // ================================================================== creating

    /**
     * Everything a create checks before money moves, so menus can show the reason up front. Empty means the
     * order may be created.
     */
    public Optional<String> checkCreate(Player player, ItemKeys.Spec spec, int amount, double price, long duration) {
        if (!ready) return Optional.of(msg("general.starting", "&eDonutOrders is still starting. Try again in a moment."));
        if (!player.hasPermission("donutorders.create")) return Optional.of(msg("general.no-permission", "&cYou do not have permission to do that."));
        if (spec.mode() == io.nightbeam.donutorders.model.MatchMode.EXACT && !player.hasPermission("donutorders.create.exact")) {
            return Optional.of(msg("create.no-exact", "&cYou can only order plain items and enchanted books."));
        }
        Optional<OrderRules.Refusal> refusal = rules.checkPlace(player).or(() -> rules.checkItem(player, spec.template()))
                .or(() -> rules.checkCooldown(player));
        if (refusal.isPresent()) return refusal.map(this::refusal);
        int limit = rules.orderLimit(player);
        if (limit >= 0 && cache.openCount(player.getUniqueId()) >= limit) {
            return Optional.of(msg("create.limit", "&cYou already have %limit% open orders.", "limit", String.valueOf(limit)));
        }
        int maxAmount = rules.maxAmount(player);
        if (amount < 1 || amount > maxAmount) {
            return Optional.of(msg("create.amount-range", "&cThe amount must be between 1 and %max%.", "max", String.valueOf(maxAmount)));
        }
        Optional<String> priceProblem = checkPrice(spec, price);
        if (priceProblem.isPresent()) return priceProblem;
        double total = Money.times(amount, price);
        if (total > rules.maxTotal()) {
            return Optional.of(msg("create.total-too-high", "&cAn order may be worth at most %max%.", "max", format(rules.maxTotal())));
        }
        if (duration < rules.minDuration() || duration > rules.maxDuration(player)) {
            return Optional.of(msg("create.duration-range", "&cPick a duration between %min% and %max%.",
                    "min", messages().duration(rules.minDuration()), "max", messages().duration(rules.maxDuration(player))));
        }
        double cost = total + fees.creationFee(player, total);
        if (!payments.has(player, cost)) {
            return Optional.of(msg("create.cannot-afford", "&cThis order costs %cost% and you have %balance%.",
                    "cost", format(cost), "balance", format(payments.balance(player))));
        }
        return Optional.empty();
    }

    public Optional<String> checkPrice(ItemKeys.Spec spec, double price) {
        double min = rules.minPrice(spec.key(), spec.template().getType());
        double max = rules.maxPrice(spec.key(), spec.template().getType());
        if (!Double.isFinite(price) || price < min || price > max) {
            return Optional.of(msg("create.price-range", "&cThe price per item must be between %min% and %max%.",
                    "min", format(min), "max", format(max)));
        }
        return Optional.empty();
    }

    /** Creates an order: the money is taken first, and given back if the order cannot be saved. */
    public CompletableFuture<ActionResult> create(Player player, ItemKeys.Spec spec, int amount, double price, long duration) {
        double roundedPrice = Money.round(price);
        Optional<String> problem = checkCreate(player, spec, amount, roundedPrice, duration);
        if (problem.isPresent()) return done(ActionResult.failure(problem.get()));
        if (!busyPlayers.add(player.getUniqueId())) return done(ActionResult.failure(busy()));
        try {
            OrderPreCreateEvent pre = new OrderPreCreateEvent(player, spec.template(), amount, roundedPrice, duration);
            if (!events.callCancellable(pre)) {
                busyPlayers.remove(player.getUniqueId());
                return done(ActionResult.failure(pre.getCancelMessage() != null ? pre.getCancelMessage()
                        : msg("create.cancelled", "&cThat order cannot be created.")));
            }
            double total = Money.times(amount, roundedPrice);
            double fee = fees.creationFee(player, total);
            double cost = Money.round(total + fee);
            if (!payments.withdraw(player, cost)) {
                busyPlayers.remove(player.getUniqueId());
                return done(ActionResult.failure(msg("create.cannot-afford", "&cThis order costs %cost% and you have %balance%.",
                        "cost", format(cost), "balance", format(payments.balance(player)))));
            }
            long now = System.currentTimeMillis();
            Order order = new Order(UUID.randomUUID(), player.getUniqueId(), player.getName(), false, spec.template(), spec.key(),
                    spec.template().getType().name(), spec.mode(), amount, 0, 0, roundedPrice, total, now, now + duration,
                    OrderStatus.ACTIVE, 0L, now, 0L);
            rules.recordCreated(player.getUniqueId());
            CompletableFuture<ActionResult> result = new CompletableFuture<>();
            CompletableFuture<Void> saved;
            try {
                saved = repository.insert(order);
            } catch (RuntimeException rejected) {
                saved = CompletableFuture.failedFuture(rejected);
            }
            saved.whenComplete((ignored, failure) -> {
                busyPlayers.remove(player.getUniqueId());
                if (failure != null) {
                    plugin.getLogger().log(Level.SEVERE, "Could not save a new order for " + player.getName() + "; refunding " + cost, failure);
                    payments.pay(player.getUniqueId(), cost, "order not saved");
                    result.complete(ActionResult.failure(msg("create.failed", "&cThe order could not be saved. Your money was refunded.")));
                    return;
                }
                cache.apply(order);
                sync.publish(SyncBus.Kind.ORDER, order.id());
                payments.payFeeAccount(fee);
                events.callAsync(new OrderCreatedEvent(order));
                log.log("CREATE", "order", order.id(), "owner", order.ownerName() + "(" + order.owner() + ")", "item", order.itemKey(),
                        "amount", amount, "price", roundedPrice, "escrow", total, "fee", fee);
                webhook.post(new DiscordWebhook.Post(DiscordWebhook.Kind.CREATED, order, 0, format(roundedPrice), format(total), total,
                        null, null, null));
                scheduler.runGlobal(() -> notifier.created(order, player));
                result.complete(ActionResult.success(msg("create.created",
                        "&aOrder created: &f%amount%× %item% &afor &6%price% &aeach. &7%cost% is held until it is delivered.",
                        "amount", String.valueOf(amount), "item", ItemNames.fragment(order.template()), "price", format(roundedPrice),
                        "cost", format(cost))));
            });
            return result;
        } catch (RuntimeException exception) {
            busyPlayers.remove(player.getUniqueId());
            throw exception;
        }
    }

    /** A server order: no escrow, no owner to pay, delivered items leave the game. */
    public CompletableFuture<Order> createServerOrder(ItemKeys.Spec spec, int amount, double price, long duration, String name) {
        long now = System.currentTimeMillis();
        Order order = new Order(UUID.randomUUID(), Order.SERVER, name == null || name.isBlank()
                ? messages().raw("general.server-name", "Server") : name, true, spec.template(), spec.key(),
                spec.template().getType().name(), spec.mode(), amount, 0, 0, Money.round(price), 0.0D, now, now + duration,
                OrderStatus.ACTIVE, 0L, now, 0L);
        return repository.insert(order).thenApply(ignored -> {
            cache.apply(order);
            sync.publish(SyncBus.Kind.ORDER, order.id());
            events.callAsync(new OrderCreatedEvent(order));
            log.log("SERVER_CREATE", "order", order.id(), "item", order.itemKey(), "amount", amount, "price", order.price());
            webhook.post(new DiscordWebhook.Post(DiscordWebhook.Kind.CREATED, order, 0, format(order.price()),
                    order.unlimited() ? "∞" : format(order.total()), order.total(), null, null, null));
            scheduler.runGlobal(() -> notifier.created(order, null));
            return order;
        });
    }

    // ================================================================== delivering

    /** Why this player cannot deliver to this order right now, if anything. */
    public Optional<String> checkDeliver(Player seller, Order order) {
        if (!ready) return Optional.of(msg("general.starting", "&eDonutOrders is still starting. Try again in a moment."));
        if (!seller.hasPermission("donutorders.deliver")) return Optional.of(msg("general.no-permission", "&cYou do not have permission to do that."));
        Optional<OrderRules.Refusal> refusal = rules.checkPlace(seller);
        if (refusal.isPresent()) return refusal.map(this::refusal);
        if (!order.acceptsDeliveries(System.currentTimeMillis())) {
            return Optional.of(msg("deliver.closed", "&cThat order is no longer taking deliveries."));
        }
        if (order.owner().equals(seller.getUniqueId()) && !config().getBoolean("orders.allow-self-delivery", false)) {
            return Optional.of(msg("deliver.own-order", "&cYou cannot deliver to your own order."));
        }
        return Optional.empty();
    }

    /**
     * Delivers items to one order. The items are already out of the seller's inventory; whatever the order does
     * not take comes back to them (or their mailbox).
     */
    public CompletableFuture<Summary> deliver(Player seller, UUID orderId, List<ItemStack> items) {
        Optional<Order> order = cache.get(orderId);
        if (order.isEmpty()) {
            Custody custody = new Custody(seller, items);
            custody.refusalMessage = msg("deliver.closed", "&cThat order is no longer taking deliveries.");
            return finish(seller, custody);
        }
        return route(seller, List.of(order.get()), items, true);
    }

    /** Takes what an order wants from the seller's inventory and delivers it (shift-click on the board). */
    public CompletableFuture<Summary> quickDeliver(Player seller, Order order) {
        Optional<String> problem = checkDeliver(seller, order);
        if (problem.isPresent()) {
            return CompletableFuture.completedFuture(new Summary(0, 0, 0, 0, 0, List.of(), null, order, problem.get()));
        }
        List<ItemStack> taken = takeFromInventory(seller, List.of(order), order.remaining());
        if (taken.isEmpty()) {
            return CompletableFuture.completedFuture(new Summary(0, 0, 0, 0, 0, List.of(), null, order,
                    msg("deliver.nothing-matches", "&cYou have nothing this order wants.")));
        }
        return route(seller, List.of(order), taken, true);
    }

    /** {@code /orders sell}: the held stack, or every matching item, into the best orders. */
    public CompletableFuture<Summary> sell(Player seller, boolean everything) {
        if (!ready) {
            return CompletableFuture.completedFuture(new Summary(0, 0, 0, 0, 0, List.of(), null, null,
                    msg("general.starting", "&eDonutOrders is still starting. Try again in a moment.")));
        }
        Optional<OrderRules.Refusal> refusal = rules.checkPlace(seller);
        if (refusal.isPresent()) {
            return CompletableFuture.completedFuture(new Summary(0, 0, 0, 0, 0, List.of(), null, null, refusal(refusal.get())));
        }
        List<ItemStack> taken = new ArrayList<>();
        if (everything) {
            List<Order> open = cache.active(System.currentTimeMillis()).stream().filter(order -> !order.owner().equals(seller.getUniqueId())).toList();
            taken.addAll(takeFromInventory(seller, open, Integer.MAX_VALUE));
        } else {
            ItemStack hand = seller.getInventory().getItemInMainHand();
            if (hand != null && !hand.getType().isAir() && !bestOrdersFor(hand, seller.getUniqueId()).isEmpty()) {
                taken.add(hand.clone());
                seller.getInventory().setItemInMainHand(null);
            }
        }
        if (taken.isEmpty()) {
            return CompletableFuture.completedFuture(new Summary(0, 0, 0, 0, 0, List.of(), null, null,
                    msg("sell.nothing", "&cNo open order wants what you are selling.")));
        }
        return sellItems(seller, taken, true);
    }

    /** Sells items already out of the seller's inventory into the best orders (also the API's sell routing). */
    public CompletableFuture<Summary> sellItems(Player seller, List<ItemStack> items, boolean giveBack) {
        long now = System.currentTimeMillis();
        List<Order> targets = new ArrayList<>();
        for (Order order : cache.active(now)) {
            if (order.owner().equals(seller.getUniqueId())) continue;
            for (ItemStack item : items) {
                if (matcher.accepts(order, item)) {
                    targets.add(order);
                    break;
                }
            }
        }
        targets.sort(java.util.Comparator.comparingDouble(Order::price).reversed().thenComparingLong(Order::createdAt));
        return route(seller, targets, items, giveBack);
    }

    /** Removes items the given orders accept from a player's storage slots, whole stacks, up to roughly {@code cap}. */
    private List<ItemStack> takeFromInventory(Player seller, List<Order> orders, int cap) {
        ItemStack[] contents = seller.getInventory().getStorageContents();
        List<ItemStack> taken = new ArrayList<>();
        long units = 0;
        for (int slot = 0; slot < contents.length && units < cap; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType().isAir()) continue;
            Order match = null;
            for (Order order : orders) {
                if (matcher.accepts(order, stack)) {
                    match = order;
                    break;
                }
            }
            if (match == null) continue;
            int count = matcher.matches(match, stack) ? stack.getAmount() : matcher.count(match, List.of(stack));
            taken.add(stack.clone());
            contents[slot] = null;
            units += count;
        }
        if (!taken.isEmpty()) seller.getInventory().setStorageContents(contents);
        return taken;
    }

    /** Offers the items to each order in turn, then settles the custody. */
    private CompletableFuture<Summary> route(Player seller, List<Order> targets, List<ItemStack> items, boolean giveBack) {
        Custody custody = new Custody(seller, items);
        custodies.put(UUID.randomUUID(), custody);
        CompletableFuture<Summary> done = new CompletableFuture<>();
        next(seller, custody, targets.iterator(), giveBack, done);
        return done;
    }

    private void next(Player seller, Custody custody, Iterator<Order> targets, boolean giveBack, CompletableFuture<Summary> done) {
        while (targets.hasNext()) {
            Order order = cache.get(targets.next().id()).orElse(null);
            if (order == null) continue;
            Optional<String> problem = checkDeliver(seller, order);
            if (problem.isPresent()) {
                custody.refusalMessage = problem.get();
                continue;
            }
            Optional<String> abuse = checkAddress(seller, order);
            if (abuse.isPresent()) {
                custody.refusalMessage = abuse.get();
                continue;
            }
            ItemMatcher.Extraction extraction = matcher.extract(order, custody.held, order.remaining());
            if (extraction.units() <= 0) continue;
            OrderPreDeliverEvent pre = new OrderPreDeliverEvent(order, seller, extraction.units());
            if (!events.callCancellable(pre)) {
                custody.refusalMessage = pre.getCancelMessage() != null ? pre.getCancelMessage()
                        : msg("deliver.cancelled", "&cThat delivery was refused.");
                continue;
            }
            custody.held.clear();
            custody.held.addAll(extraction.returned());
            custody.pendingItems = extraction.matched();
            custody.pendingOrder = order;
            CompletableFuture<DeliveryOutcome> step;
            try {
                step = repository.deliver(order.id(), extraction.matched(), fees.taxRate(seller), seller.getUniqueId(),
                        seller.getName(), System.currentTimeMillis());
            } catch (RuntimeException rejected) {
                step = CompletableFuture.failedFuture(rejected);
            }
            custody.pending = step;
            step.whenComplete((outcome, failure) -> scheduler.runEntity(seller,
                    () -> {
                        applyStep(seller, custody, outcome, failure);
                        next(seller, custody, targets, giveBack, done);
                    },
                    () -> {
                        applyStep(null, custody, outcome, failure);
                        finishOffline(custody);
                        done.complete(custody.summary());
                    }));
            return;
        }
        if (giveBack) {
            finish(seller, custody).whenComplete((summary, failure) -> done.complete(summary));
        } else {
            custody.finished.set(true);
            custodies.values().remove(custody);
            done.complete(custody.summary());
        }
    }

    /** Applies a finished database step: pays the seller, records the outcome and puts unused items back in custody. */
    private void applyStep(Player seller, Custody custody, DeliveryOutcome outcome, Throwable failure) {
        Order order = custody.pendingOrder;
        List<ItemStack> carried = custody.pendingItems;
        custody.pending = null;
        custody.pendingItems = List.of();
        custody.pendingOrder = null;
        if (failure != null || outcome == null) {
            plugin.getLogger().log(Level.WARNING, "A delivery to order " + (order == null ? "?" : order.shortId()) + " failed", failure);
            custody.held.addAll(carried);
            custody.refusalMessage = msg("deliver.failed", "&cThe delivery could not be saved. Your items were returned.");
            return;
        }
        if (outcome.order() != null) cache.apply(outcome.order());
        if (outcome.status() != DeliveryStatus.DELIVERED) {
            custody.held.addAll(carried);
            custody.refusal = outcome.status();
            custody.lastOrder = outcome.order();
            custody.refusalMessage = switch (outcome.status()) {
                case FULL -> msg("deliver.full", "&cSomeone else filled that order first.");
                case EXPIRED, CLOSED, NOT_FOUND -> msg("deliver.closed", "&cThat order is no longer taking deliveries.");
                default -> msg("deliver.busy", "&cThat order is busy. Try again.");
            };
            return;
        }
        custody.held.addAll(outcome.overflow());
        Order after = outcome.order();
        double net = Money.minus(outcome.payout(), outcome.tax());
        if (seller != null) payments.payNow(seller, net, "delivery " + after.shortId());
        else payments.pay(custody.seller, net, "delivery " + after.shortId());
        payments.payFeeAccount(outcome.tax());
        if (outcome.dust() > 0) payments.pay(after.owner(), outcome.dust(), "rounding " + after.shortId());
        custody.delivered += outcome.accepted();
        custody.gross = Money.round(custody.gross + outcome.payout());
        custody.tax = Money.round(custody.tax + outcome.tax());
        custody.orders++;
        custody.lastOrder = after;
        sync.publish(SyncBus.Kind.ORDER, after.id());
        insights.remove(after.itemKey());
        try {
            notifier.delivered(after, custody.sellerName, outcome.accepted(), outcome.payout());
            events.callAsync(new OrderDeliveredEvent(after, custody.seller, custody.sellerName, outcome.accepted(), outcome.payout(), outcome.tax()));
            log.log("DELIVER", "order", after.id(), "seller", custody.sellerName + "(" + custody.seller + ")", "owner",
                    after.ownerName() + "(" + after.owner() + ")", "item", after.itemKey(), "amount", outcome.accepted(), "price", after.price(),
                    "paid", net, "tax", outcome.tax(), "progress", after.delivered() + "/" + after.amount());
            webhook.post(new DiscordWebhook.Post(DiscordWebhook.Kind.DELIVERED, after, outcome.accepted(), format(after.price()),
                    format(outcome.payout()), outcome.payout(), custody.sellerName, custody.seller, null));
            if (after.status() == OrderStatus.FILLED) {
                custody.filled++;
                notifier.filled(after);
                events.callAsync(new OrderClosedEvent(after, OrderClosedEvent.Reason.FILLED, outcome.dust()));
                log.log("FILLED", "order", after.id(), "owner", after.ownerName(), "item", after.itemKey(), "amount", after.amount());
                webhook.post(new DiscordWebhook.Post(DiscordWebhook.Kind.FILLED, after, after.amount(), format(after.price()), null,
                        after.total(), null, null, null));
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Delivery " + after.shortId() + " completed, but a notification failed", exception);
        }
    }

    /** Gives back what is left in custody, on the seller's thread; anything that does not fit is mailed. */
    private CompletableFuture<Summary> finish(Player seller, Custody custody) {
        if (!custody.finished.compareAndSet(false, true)) return CompletableFuture.completedFuture(custody.summary());
        custodies.values().remove(custody);
        if (!custody.held.isEmpty()) {
            Map<Integer, ItemStack> left = seller.getInventory().addItem(custody.held.toArray(new ItemStack[0]));
            if (!left.isEmpty()) mail(custody.seller, left.values(), "returned");
        }
        Summary summary = custody.summary();
        if (summary.delivered() > 0) Sounds.play(seller, config(), "delivered");
        return CompletableFuture.completedFuture(summary);
    }

    private void finishOffline(Custody custody) {
        if (!custody.finished.compareAndSet(false, true)) return;
        custodies.values().remove(custody);
        if (!custody.held.isEmpty()) mail(custody.seller, custody.held, "returned");
    }

    /** Items for a player's mailbox ({@code /order} → mailbox). */
    public void mail(UUID player, Collection<ItemStack> items, String reason) {
        List<ItemStack> copy = items.stream().filter(item -> item != null && !item.getType().isAir()).map(ItemStack::clone).toList();
        if (copy.isEmpty()) return;
        repository.addMail(player, copy, reason, System.currentTimeMillis()).whenComplete((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not mail " + copy.size() + " stacks to " + player + "; they are logged below", failure);
                copy.forEach(item -> plugin.getLogger().severe("  lost stack for " + player + ": " + item));
                return;
            }
            Player online = Bukkit.getPlayer(player);
            if (online != null) {
                scheduler.runEntity(online, () -> messages().sendFormatted(online, "mail.received",
                        "&eSome items did not fit in your inventory and wait in your mailbox: &6/order mailbox"));
            }
        });
    }

    /** Same-address deliveries: off, alert staff, or block. */
    private Optional<String> checkAddress(Player seller, Order order) {
        String mode = config().getString("anti-abuse.same-address", "alert");
        if (mode == null || mode.equalsIgnoreCase("off") || order.serverOrder()) return Optional.empty();
        Player owner = Bukkit.getPlayer(order.owner());
        if (owner == null || seller.hasPermission("donutorders.bypass.address")) return Optional.empty();
        String a = address(seller);
        String b = address(owner);
        if (a == null || !a.equals(b)) return Optional.empty();
        if (mode.equalsIgnoreCase("block")) {
            return Optional.of(msg("deliver.same-address", "&cYou cannot deliver to an order of a player on your connection."));
        }
        String note = seller.getName() + " and " + owner.getName() + " share an address";
        plugin.getLogger().warning("Suspicious delivery to order " + order.shortId() + ": " + note);
        webhook.post(new DiscordWebhook.Post(DiscordWebhook.Kind.SUSPICIOUS, order, order.remaining(), format(order.price()), "-", 0.0D,
                seller.getName(), seller.getUniqueId(), note));
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (!staff.hasPermission("donutorders.admin.alerts")) continue;
            scheduler.runEntity(staff, () -> messages().sendFormatted(staff, "admin.suspicious",
                    "&c%seller% delivered to %owner%'s order %order% from the same address.",
                    "seller", seller.getName(), "owner", owner.getName(), "order", order.shortId()));
        }
        return Optional.empty();
    }

    private static String address(Player player) {
        try {
            InetSocketAddress socket = player.getAddress();
            return socket == null || socket.getAddress() == null ? null : socket.getAddress().getHostAddress();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** The chat line for a delivery summary. */
    public String describe(Summary summary) {
        if (summary.delivered() <= 0) {
            return summary.refusalMessage() != null ? summary.refusalMessage()
                    : msg("deliver.nothing-matches", "&cYou have nothing this order wants.");
        }
        String tax = summary.tax() > 0 ? msg("deliver.tax-part", " &7(%tax% tax)", "tax", format(summary.tax())) : "";
        if (summary.orders() == 1 && summary.lastOrder() != null) {
            return msg("deliver.delivered", "&aDelivered &f%amount%× %item% &ato %owner%'s order for &6%paid%&a.%tax%",
                    "amount", String.valueOf(summary.delivered()), "item", ItemNames.fragment(summary.lastOrder().template()),
                    "owner", summary.lastOrder().ownerName(), "paid", format(summary.net()), "tax", MessageUtil.trusted(tax));
        }
        return msg("sell.sold", "&aSold &f%amount% &aitems to &f%orders% &aorders for &6%paid%&a.%tax%",
                "amount", String.valueOf(summary.delivered()), "orders", String.valueOf(summary.orders()), "paid", format(summary.net()),
                "tax", MessageUtil.trusted(tax));
    }

    /** What selling these items would pay right now (nothing is taken). */
    public record Estimate(int units, double gross, double tax, int orders) {

        public double net() {
            return Money.minus(gross, tax);
        }
    }

    public Estimate estimate(Player seller, List<ItemStack> items) {
        long now = System.currentTimeMillis();
        List<ItemStack> pool = new ArrayList<>();
        for (ItemStack item : items) if (item != null && !item.getType().isAir()) pool.add(item.clone());
        List<Order> targets = new ArrayList<>();
        for (Order order : cache.active(now)) {
            if (order.owner().equals(seller.getUniqueId())) continue;
            for (ItemStack item : pool) {
                if (matcher.accepts(order, item)) {
                    targets.add(order);
                    break;
                }
            }
        }
        targets.sort(java.util.Comparator.comparingDouble(Order::price).reversed().thenComparingLong(Order::createdAt));
        int units = 0;
        double gross = 0.0D;
        int orders = 0;
        for (Order order : targets) {
            ItemMatcher.Extraction extraction = matcher.extract(order, pool, order.remaining());
            if (extraction.units() <= 0) continue;
            units += extraction.units();
            gross = Money.round(gross + Money.times(extraction.units(), order.price()));
            orders++;
            pool = new ArrayList<>(extraction.returned());
        }
        return new Estimate(units, gross, Money.round(gross * fees.taxRate(seller)), orders);
    }

    /** The items {@code /orders sell} would offer: the held stack, or everything any open order wants. */
    public List<ItemStack> sellCandidates(Player seller, boolean everything) {
        List<ItemStack> candidates = new ArrayList<>();
        if (!everything) {
            ItemStack hand = seller.getInventory().getItemInMainHand();
            if (hand != null && !hand.getType().isAir()) candidates.add(hand.clone());
            return candidates;
        }
        List<Order> open = cache.active(System.currentTimeMillis()).stream().filter(order -> !order.owner().equals(seller.getUniqueId())).toList();
        for (ItemStack stack : seller.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            for (Order order : open) {
                if (matcher.accepts(order, stack)) {
                    candidates.add(stack.clone());
                    break;
                }
            }
        }
        return candidates;
    }

    // ================================================================== collecting

    /** Hands an order's delivered items to its owner: as many whole stacks as fit. Resolves with the items given. */
    public CompletableFuture<Integer> collect(Player owner, Order order) {
        if (!busyPlayers.add(owner.getUniqueId())) return CompletableFuture.completedFuture(-1);
        CompletableFuture<Integer> result = new CompletableFuture<>();
        repository.itemsOf(order.id()).thenAccept(stacks -> scheduler.runEntity(owner, () -> {
            List<Integer> fitting = InventorySpace.plan(owner.getInventory().getStorageContents(), stacks.stream().map(StoredStack::item).toList());
            if (fitting.isEmpty()) {
                busyPlayers.remove(owner.getUniqueId());
                result.complete(stacks.isEmpty() ? 0 : -2);
                return;
            }
            List<StoredStack> wanted = fitting.stream().map(stacks::get).toList();
            repository.takeItems(order.id(), wanted, System.currentTimeMillis()).whenComplete((taken, failure) -> {
                if (failure != null) {
                    busyPlayers.remove(owner.getUniqueId());
                    plugin.getLogger().log(Level.WARNING, "Could not collect order " + order.shortId(), failure);
                    result.complete(0);
                    return;
                }
                List<ItemStack> items = taken.stream().map(StoredStack::item).toList();
                int units = items.stream().mapToInt(ItemStack::getAmount).sum();
                scheduler.runEntity(owner, () -> {
                    busyPlayers.remove(owner.getUniqueId());
                    Map<Integer, ItemStack> left = owner.getInventory().addItem(items.toArray(new ItemStack[0]));
                    if (!left.isEmpty()) mail(owner.getUniqueId(), left.values(), "collected");
                    if (units > 0) {
                        Sounds.play(owner, config(), "collect");
                        log.log("COLLECT", "order", order.id(), "owner", owner.getName(), "items", units);
                    }
                    result.complete(units);
                }, () -> {
                    busyPlayers.remove(owner.getUniqueId());
                    mail(order.owner(), items, "collected");
                    result.complete(units);
                });
                refresh(order.id());
            });
        }, () -> {
            busyPlayers.remove(owner.getUniqueId());
            result.complete(0);
        })).exceptionally(failure -> {
            busyPlayers.remove(owner.getUniqueId());
            result.complete(0);
            return null;
        });
        return result;
    }

    /** Collects every order with items and then the mailbox. Resolves with the items given. */
    public CompletableFuture<Integer> collectAll(Player owner) {
        List<Order> waiting = cache.ofOwner(owner.getUniqueId()).stream().filter(order -> order.waiting() > 0).toList();
        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        for (Order order : waiting) {
            chain = chain.thenCompose(total -> collect(owner, order).thenApply(given -> total + Math.max(0, given)));
        }
        return chain.thenCompose(total -> collectMail(owner).thenApply(given -> total + Math.max(0, given)));
    }

    public CompletableFuture<Integer> collectMail(Player player) {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        repository.mailOf(player.getUniqueId()).thenAccept(stacks -> scheduler.runEntity(player, () -> {
            List<Integer> fitting = InventorySpace.plan(player.getInventory().getStorageContents(), stacks.stream().map(StoredStack::item).toList());
            if (fitting.isEmpty()) {
                result.complete(stacks.isEmpty() ? 0 : -2);
                return;
            }
            repository.takeMail(fitting.stream().map(stacks::get).toList()).whenComplete((taken, failure) -> {
                if (failure != null) {
                    result.complete(0);
                    return;
                }
                List<ItemStack> items = taken.stream().map(StoredStack::item).toList();
                int units = items.stream().mapToInt(ItemStack::getAmount).sum();
                scheduler.runEntity(player, () -> {
                    Map<Integer, ItemStack> left = player.getInventory().addItem(items.toArray(new ItemStack[0]));
                    if (!left.isEmpty()) mail(player.getUniqueId(), left.values(), "returned");
                    result.complete(units);
                }, () -> {
                    mail(player.getUniqueId(), items, "returned");
                    result.complete(units);
                });
            });
        }, () -> result.complete(0))).exceptionally(failure -> {
            result.complete(0);
            return null;
        });
        return result;
    }

    // ================================================================== closing

    /** The owner cancels: the stored escrow (and the fee, when configured) is refunded. */
    public CompletableFuture<ActionResult> cancel(Player owner, Order order) {
        if (!order.owner().equals(owner.getUniqueId())) return done(ActionResult.failure(msg("general.not-yours", "&cThat is not your order.")));
        if (order.status() != OrderStatus.ACTIVE) return done(ActionResult.failure(msg("cancel.not-open", "&cThat order is already closed.")));
        if (!closing.add(order.id())) return done(ActionResult.failure(busy()));
        return repository.close(order.id(), OrderStatus.CANCELLED, System.currentTimeMillis()).handle((outcome, failure) -> {
            closing.remove(order.id());
            if (failure != null || outcome == null) {
                plugin.getLogger().log(Level.WARNING, "Could not cancel order " + order.shortId(), failure);
                return ActionResult.failure(msg("cancel.failed", "&cThe order could not be cancelled. Try again."));
            }
            if (outcome.order() != null) cache.apply(outcome.order());
            if (!outcome.changed()) return ActionResult.failure(msg("cancel.not-open", "&cThat order is already closed."));
            double refund = outcome.refund();
            if (fees.refundFeeOnCancel() && outcome.order().delivered() == 0) {
                refund = Money.round(refund + fees.creationFee(owner, order.total()));
            }
            payments.pay(order.owner(), refund, "cancel " + order.shortId());
            afterClose(outcome.order(), OrderClosedEvent.Reason.CANCELLED, refund, owner.getName());
            String waiting = outcome.order().waiting() > 0 ? msg("cancel.items-waiting", " &7Delivered items wait in &e/order&7.") : "";
            return ActionResult.success(msg("cancel.cancelled", "&aOrder cancelled. &6%refund% &awas refunded.%waiting%",
                    "refund", format(refund), "waiting", MessageUtil.trusted(waiting)));
        });
    }

    /** Staff cancel any order, with an optional reason the owner sees. */
    public CompletableFuture<ActionResult> adminCancel(CommandSender actor, Order order, String reason) {
        if (!closing.add(order.id())) return done(ActionResult.failure(busy()));
        return repository.close(order.id(), OrderStatus.CANCELLED, System.currentTimeMillis()).handle((outcome, failure) -> {
            closing.remove(order.id());
            if (failure != null || outcome == null) return ActionResult.failure(msg("cancel.failed", "&cThe order could not be cancelled. Try again."));
            if (outcome.order() != null) cache.apply(outcome.order());
            if (!outcome.changed()) return ActionResult.failure(msg("cancel.not-open", "&cThat order is already closed."));
            payments.pay(order.owner(), outcome.refund(), "staff cancel " + order.shortId());
            notifier.cancelledByStaff(outcome.order(), outcome.refund(), reason);
            afterClose(outcome.order(), OrderClosedEvent.Reason.CANCELLED_BY_STAFF, outcome.refund(), actor.getName());
            return ActionResult.success(msg("admin.cancelled", "&aCancelled order %order% of %owner%; %refund% refunded.",
                    "order", order.shortId(), "owner", order.ownerName(), "refund", format(outcome.refund())));
        });
    }

    private void expireDue() {
        if (!ready) return;
        for (Order order : cache.expired(System.currentTimeMillis())) {
            if (!closing.add(order.id())) continue;
            repository.close(order.id(), OrderStatus.EXPIRED, System.currentTimeMillis()).whenComplete((outcome, failure) -> {
                closing.remove(order.id());
                if (failure != null || outcome == null) {
                    plugin.getLogger().log(Level.WARNING, "Could not expire order " + order.shortId(), failure);
                    return;
                }
                if (outcome.order() != null) cache.apply(outcome.order());
                if (!outcome.changed()) return;
                payments.pay(order.owner(), outcome.refund(), "expired " + order.shortId());
                notifier.expired(outcome.order(), outcome.refund());
                afterClose(outcome.order(), OrderClosedEvent.Reason.EXPIRED, outcome.refund(), "expiry");
            });
        }
    }

    private void afterClose(Order closed, OrderClosedEvent.Reason reason, double refund, String actor) {
        sync.publish(SyncBus.Kind.ORDER, closed.id());
        insights.remove(closed.itemKey());
        events.callAsync(new OrderClosedEvent(closed, reason, refund));
        log.log(reason.name(), "order", closed.id(), "owner", closed.ownerName() + "(" + closed.owner() + ")", "by", actor,
                "item", closed.itemKey(), "delivered", closed.delivered() + "/" + closed.amount(), "refund", refund);
        webhook.post(new DiscordWebhook.Post(reason == OrderClosedEvent.Reason.EXPIRED ? DiscordWebhook.Kind.EXPIRED : DiscordWebhook.Kind.CANCELLED,
                closed, closed.delivered(), format(closed.price()), format(refund), closed.total(), null, null, null));
    }

    // ================================================================== editing

    /**
     * Changes an open order: more items, a new price, more time. The escrow difference is charged before the
     * change (refunded if the change does not land) or refunded after it.
     */
    public CompletableFuture<ActionResult> edit(Player owner, Order seen, int addAmount, double newPrice, long extendMillis) {
        if (!seen.owner().equals(owner.getUniqueId())) return done(ActionResult.failure(msg("general.not-yours", "&cThat is not your order.")));
        if (seen.status() != OrderStatus.ACTIVE || seen.serverOrder()) {
            return done(ActionResult.failure(msg("cancel.not-open", "&cThat order is already closed.")));
        }
        double price = Money.round(newPrice);
        if (price < seen.price() && !config().getBoolean("orders.allow-price-decrease", true)) {
            return done(ActionResult.failure(msg("edit.no-decrease", "&cOrder prices can only go up.")));
        }
        ItemKeys.Spec spec = new ItemKeys.Spec(seen.template(), seen.mode(), seen.itemKey());
        Optional<String> priceProblem = checkPrice(spec, price);
        if (priceProblem.isPresent()) return done(ActionResult.failure(priceProblem.get()));
        long wanted = (long) seen.amount() + Math.max(0, addAmount);
        if (addAmount > 0 && wanted > rules.maxAmount(owner)) {
            return done(ActionResult.failure(msg("create.amount-range", "&cThe amount must be between 1 and %max%.",
                    "max", String.valueOf(rules.maxAmount(owner)))));
        }
        int amount = (int) wanted;
        long now = System.currentTimeMillis();
        long expires = Math.min(now + rules.maxDuration(owner), seen.expiresAt() + Math.max(0L, extendMillis));
        if (expires < seen.expiresAt()) expires = seen.expiresAt();
        double escrow = Money.times(amount - seen.delivered(), price);
        if (escrow > rules.maxTotal()) {
            return done(ActionResult.failure(msg("create.total-too-high", "&cAn order may be worth at most %max%.", "max", format(rules.maxTotal()))));
        }
        double charge = Money.minus(escrow, seen.escrow());
        double refund = Money.minus(seen.escrow(), escrow);
        if (!busyPlayers.add(owner.getUniqueId())) return done(ActionResult.failure(busy()));
        if (charge > 0 && !payments.withdraw(owner, charge)) {
            busyPlayers.remove(owner.getUniqueId());
            return done(ActionResult.failure(msg("create.cannot-afford", "&cThis order costs %cost% and you have %balance%.",
                    "cost", format(charge), "balance", format(payments.balance(owner)))));
        }
        long finalExpires = expires;
        CompletableFuture<Optional<Order>> saved;
        try {
            saved = repository.edit(seen.id(), seen.version(), amount, price, escrow, expires, now);
        } catch (RuntimeException rejected) {
            saved = CompletableFuture.failedFuture(rejected);
        }
        return saved.handle((updated, failure) -> {
            busyPlayers.remove(owner.getUniqueId());
            if (failure != null || updated == null || updated.isEmpty()) {
                if (charge > 0) payments.pay(owner.getUniqueId(), charge, "edit not saved " + seen.shortId());
                if (failure != null) plugin.getLogger().log(Level.WARNING, "Could not edit order " + seen.shortId(), failure);
                refresh(seen.id());
                return ActionResult.failure(msg("edit.changed", "&cThat order changed in the meantime. Nothing was charged; try again."));
            }
            cache.apply(updated.get());
            sync.publish(SyncBus.Kind.ORDER, seen.id());
            if (refund > 0) payments.pay(owner.getUniqueId(), refund, "edit " + seen.shortId());
            insights.remove(seen.itemKey());
            log.log("EDIT", "order", seen.id(), "owner", owner.getName(), "amount", seen.amount() + "->" + amount,
                    "price", seen.price() + "->" + price, "expires", finalExpires, "charged", charge, "refunded", refund);
            String money = charge > 0 ? msg("edit.charged", " &7%amount% was added to the order.", "amount", format(charge))
                    : refund > 0 ? msg("edit.refunded", " &7%amount% was refunded.", "amount", format(refund)) : "";
            return ActionResult.success(msg("edit.saved", "&aOrder updated.%money%", "money", MessageUtil.trusted(money)));
        });
    }

    // ================================================================== sync and maintenance

    /** Re-reads one order (after a change elsewhere). */
    public void refresh(UUID id) {
        repository.find(id).thenAccept(found -> {
            if (found.isPresent()) cache.apply(found.get());
            else cache.remove(id);
        }).exceptionally(failure -> null);
    }

    private void poll() {
        long since = lastSync.get() - 2_000L;
        repository.changedSince(since).thenAccept(changed -> {
            long newest = lastSync.get();
            for (Order order : changed) {
                cache.apply(order);
                if (!order.live()) cache.remove(order.id());
                newest = Math.max(newest, order.updatedAt());
            }
            lastSync.set(newest);
        }).exceptionally(failure -> {
            plugin.getLogger().fine("Order poll failed: " + failure.getMessage());
            return null;
        });
        if (!sync.live()) notifier.pollNotices();
    }

    private void retryOwed() {
        repository.playersOwed().thenAccept(players -> {
            for (UUID id : players) {
                Player online = Bukkit.getPlayer(id);
                if (online != null) payments.settleOwed(online, paid -> messages().sendFormatted(online, "notify.owed-paid",
                        "<green>You received <gold>%amount%</gold> from your orders.", "amount", format(paid)));
            }
        }).exceptionally(failure -> null);
    }

    private void purge() {
        long days = config().getLong("history.keep-days", 60L);
        if (days <= 0) return;
        repository.purge(System.currentTimeMillis() - days * 86_400_000L).thenAccept(removed -> {
            if (removed > 0) plugin.getLogger().info("Removed " + removed + " old closed orders from history.");
        }).exceptionally(failure -> null);
    }

    public CompletableFuture<Integer> purgeNow(int days) {
        return repository.purge(System.currentTimeMillis() - Math.max(0, days) * 86_400_000L);
    }

    public void onJoin(Player player) {
        notifier.welcome(player);
    }

    public void onQuit(Player player) {
        busyPlayers.remove(player.getUniqueId());
        rules.forget(player.getUniqueId());
        notifier.forget(player.getUniqueId());
    }

    // ================================================================== API

    public CompletableFuture<DonutOrdersApi.SellResult> sellForApi(Player seller, List<ItemStack> items) {
        return sellItems(seller, items, false).thenApply(summary ->
                new DonutOrdersApi.SellResult(summary.delivered(), summary.net(), summary.leftovers()));
    }

    // ================================================================== helpers

    private String refusal(OrderRules.Refusal refusal) {
        return msg(refusal.key(), refusal.fallback(), refusal.placeholders());
    }

    private String busy() {
        return msg("general.busy", "&cPlease wait a moment.");
    }

    private static <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }
}

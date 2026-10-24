package io.nightbeam.donutorders.service;

import io.nightbeam.donutorders.DonutOrdersPlugin;
import io.nightbeam.donutorders.model.Notice;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.storage.SqlOrderRepository;
import io.nightbeam.donutorders.sync.SyncBus;
import io.nightbeam.donutorders.util.ItemNames;
import io.nightbeam.donutorders.util.MessageUtil;
import io.nightbeam.donutorders.util.SchedulerAdapter;
import io.nightbeam.donutorders.util.Sounds;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/**
 * Tells players what happened to their orders. Deliveries are gathered for a few seconds so a busy order sends
 * one line, not fifty. Players who are offline (or on another server of the network) get a notice that is shown
 * as a summary when they join, or right away on the server they are on when Redis is set up.
 */
public final class Notifier {

    private static final class Batch {
        Order order;
        int amount;
        double value;
        final Set<String> sellers = new LinkedHashSet<>();
    }

    private final DonutOrdersPlugin plugin;
    private final SchedulerAdapter scheduler;
    private final SqlOrderRepository repository;
    private final SyncBus sync;
    private final Payments payments;
    private final java.util.function.DoubleFunction<String> money;
    private final NamespacedKey alertsKey;
    private final Map<UUID, Batch> batches = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBroadcast = new ConcurrentHashMap<>();

    public Notifier(DonutOrdersPlugin plugin, SchedulerAdapter scheduler, SqlOrderRepository repository, SyncBus sync, Payments payments,
                    java.util.function.DoubleFunction<String> money) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.repository = repository;
        this.sync = sync;
        this.payments = payments;
        this.money = money;
        this.alertsKey = new NamespacedKey(plugin, "alerts");
    }

    private MessageUtil messages() {
        return plugin.messages();
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    // ------------------------------------------------------------------ alerts toggle

    /** Delivery alerts and new-order broadcasts can be muted per player ({@code /order alerts}). */
    public boolean alertsOn(Player player) {
        try {
            Byte value = player.getPersistentDataContainer().get(alertsKey, PersistentDataType.BYTE);
            return value == null || value != 0;
        } catch (RuntimeException | LinkageError unavailable) {
            return true;
        }
    }

    public boolean toggleAlerts(Player player) {
        boolean on = !alertsOn(player);
        player.getPersistentDataContainer().set(alertsKey, PersistentDataType.BYTE, (byte) (on ? 1 : 0));
        return on;
    }

    // ------------------------------------------------------------------ new orders

    /** The clickable chat announcement of a new order. */
    public void created(Order order, Player creator) {
        FileConfiguration cfg = config();
        if (!cfg.getBoolean("broadcast.enabled", true) || order.total() < cfg.getDouble("broadcast.min-total", 0.0D)) return;
        long cooldown = Math.max(0L, cfg.getLong("broadcast.cooldown-seconds", 0L)) * 1000L;
        long now = System.currentTimeMillis();
        Long last = lastBroadcast.get(order.owner());
        if (cooldown > 0 && last != null && now - last < cooldown) return;
        lastBroadcast.put(order.owner(), now);
        Component line = messages().component("broadcast.new-order",
                "<gray>%player% <white>wants <yellow>%amount%× %item%</yellow> for <gold>%price%</gold> each.",
                "player", order.ownerName(), "amount", amountText(order), "item", ItemNames.fragment(order.template()),
                "price", money.apply(order.price()));
        Component button = messages().component("broadcast.button", " <green><b>[Deliver]</b>")
                .clickEvent(ClickEvent.runCommand("/orders open " + order.shortId()))
                .hoverEvent(HoverEvent.showText(messages().component("broadcast.hover", "<gray>Click to deliver to this order")));
        Component message = messages().component(messages().raw("prefix", "")).append(line).append(button);
        UUID skip = creator == null ? order.owner() : creator.getUniqueId();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(skip)) continue;
            scheduler.runEntity(online, () -> {
                if (alertsOn(online) && online.hasPermission("donutorders.use")) online.sendMessage(message);
            });
        }
    }

    // ------------------------------------------------------------------ to the owner

    /** Items arrived at an order. Gathered per order for {@code notifications.batch-seconds}. */
    public void delivered(Order order, String sellerName, int amount, double value) {
        if (order.serverOrder()) return;
        Batch batch = batches.computeIfAbsent(order.id(), id -> {
            long seconds = Math.max(0L, config().getLong("notifications.batch-seconds", 3L));
            scheduler.runGlobalLater(() -> flush(id), Math.max(1L, seconds * 20L));
            return new Batch();
        });
        synchronized (batch) {
            batch.order = batch.order == null || batch.order.version() < order.version() ? order : batch.order;
            batch.amount += amount;
            batch.value += value;
            batch.sellers.add(sellerName);
        }
    }

    private void flush(UUID orderId) {
        Batch batch = batches.remove(orderId);
        if (batch == null) return;
        Order order;
        int amount;
        double value;
        String sellers;
        synchronized (batch) {
            order = batch.order;
            amount = batch.amount;
            value = batch.value;
            List<String> names = List.copyOf(batch.sellers);
            sellers = names.size() == 1 ? names.get(0)
                    : messages().format("notify.many-sellers", "%first% and %count% others", "first", names.get(0),
                    "count", String.valueOf(names.size() - 1));
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("order", order.id().toString());
        values.put("item", ItemNames.plain(order.template()));
        values.put("amount", String.valueOf(amount));
        values.put("value", String.valueOf(value));
        values.put("sellers", sellers);
        Player owner = Bukkit.getPlayer(order.owner());
        if (owner == null || !owner.isOnline()) {
            store(order.owner(), Notice.Kind.DELIVERED, values);
            return;
        }
        Order snapshot = order;
        scheduler.runEntity(owner, () -> {
            if (!alertsOn(owner)) return;
            String mode = config().getString("notifications.delivery-alert", "chat");
            Component text = messages().component(messages().format("notify.delivered",
                    "<green>%sellers% delivered <white>%amount%× %item%</white> to your order <gray>(%delivered%/%wanted%)</gray>. <yellow>/order</yellow> to collect.",
                    "sellers", sellers, "amount", String.valueOf(amount), "item", ItemNames.fragment(snapshot.template()),
                    "delivered", String.valueOf(snapshot.delivered()), "wanted", amountText(snapshot)));
            if (mode.equalsIgnoreCase("chat") || mode.equalsIgnoreCase("both")) {
                owner.sendMessage(messages().component(messages().raw("prefix", "")).append(text));
            }
            if (mode.equalsIgnoreCase("actionbar") || mode.equalsIgnoreCase("both")) owner.sendActionBar(text);
            Sounds.play(owner, config(), "delivery-received");
        });
    }

    public void filled(Order order) {
        if (order.serverOrder()) return;
        Player owner = Bukkit.getPlayer(order.owner());
        if (owner == null || !owner.isOnline()) {
            store(order.owner(), Notice.Kind.FILLED, Map.of("order", order.id().toString(), "item", ItemNames.plain(order.template()),
                    "amount", String.valueOf(order.amount())));
            return;
        }
        scheduler.runEntityLater(owner, () -> {
            String item = ItemNames.fragment(order.template());
            messages().sendFormatted(owner, "notify.filled",
                    "<green>Your order for <white>%amount%× %item%</white> is complete! <yellow>/order</yellow> to collect.",
                    "amount", amountText(order), "item", item);
            if (config().getBoolean("notifications.title-on-filled", true)) {
                owner.showTitle(Title.title(messages().component("notify.filled-title", "<gradient:#49d6a0:#b8f5dc><b>ORDER FILLED</b>"),
                        messages().component("notify.filled-subtitle", "<white>%amount%× %item%", "amount", amountText(order), "item", item),
                        Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2500), Duration.ofMillis(600))));
            }
            Sounds.play(owner, config(), "order-filled");
        }, Math.max(1L, config().getLong("notifications.batch-seconds", 3L) * 20L + 2L));
    }

    public void expired(Order order, double refund) {
        closed(order, refund, Notice.Kind.EXPIRED, "notify.expired",
                "<gray>Your order for <white>%item%</white> expired. <gold>%refund%</gold> was refunded.", "");
    }

    public void cancelledByStaff(Order order, double refund, String reason) {
        String shown = reason == null || reason.isBlank() ? ""
                : messages().format("notify.staff-reason", " (%reason%)", "reason", reason);
        closed(order, refund, Notice.Kind.CANCELLED_BY_STAFF, "notify.cancelled-by-staff",
                "<red>Staff cancelled your order for <white>%item%</white>%reason%. <gold>%refund%</gold> was refunded.", shown);
    }

    private void closed(Order order, double refund, Notice.Kind kind, String key, String fallback, String reason) {
        if (order.serverOrder()) return;
        Player owner = Bukkit.getPlayer(order.owner());
        if (owner == null || !owner.isOnline()) {
            store(order.owner(), kind, Map.of("order", order.id().toString(), "item", ItemNames.plain(order.template()),
                    "refund", String.valueOf(refund)));
            return;
        }
        scheduler.runEntity(owner, () -> {
            messages().sendFormatted(owner, key, fallback, "item", ItemNames.fragment(order.template()),
                    "refund", money.apply(refund), "reason", MessageUtil.trusted(reason));
            if (order.waiting() > 0) {
                messages().sendFormatted(owner, "notify.items-waiting", "<gray>%count% delivered items wait for you in <yellow>/order</yellow>.",
                        "count", String.valueOf(order.waiting()));
            }
        });
    }

    private void store(UUID player, Notice.Kind kind, Map<String, String> values) {
        repository.addNotice(new Notice(0L, player, kind, values, System.currentTimeMillis()))
                .thenRun(() -> sync.publish(SyncBus.Kind.NOTICE, player))
                .exceptionally(failure -> {
                    plugin.getLogger().warning("Could not save a notification for " + player + ": " + failure.getMessage());
                    return null;
                });
    }

    // ------------------------------------------------------------------ joining

    /**
     * The summary of what happened while a player was away (or on another server), then any money owed and the
     * items waiting for them.
     */
    public void welcome(Player player) {
        long delay = Math.max(1L, config().getLong("notifications.join-delay-seconds", 3L) * 20L);
        scheduler.runEntityLater(player, () -> {
            showNotices(player);
            payments.settleOwed(player, paid -> messages().sendFormatted(player, "notify.owed-paid",
                    "<green>You received <gold>%amount%</gold> from your orders.", "amount", money.apply(paid)));
            repository.waitingFor(player.getUniqueId()).thenAccept(waiting -> {
                if (waiting <= 0 || !config().getBoolean("notifications.remind-waiting-items", true)) return;
                scheduler.runEntity(player, () -> messages().sendFormatted(player, "notify.items-waiting",
                        "<gray>%count% delivered items wait for you in <yellow>/order</yellow>.", "count", String.valueOf(waiting)));
            });
        }, delay);
    }

    /** Takes a player's waiting notices and shows them as one summary. */
    public void showNotices(Player player) {
        repository.takeNotices(List.of(player.getUniqueId())).thenAccept(notices -> {
            if (notices.isEmpty()) return;
            scheduler.runEntity(player, () -> summarize(player, notices), () -> notices.forEach(notice ->
                    store(notice.player(), notice.kind(), notice.values())));
        }).exceptionally(failure -> {
            plugin.getLogger().warning("Could not read notifications for " + player.getName() + ": " + failure.getMessage());
            return null;
        });
    }

    private void summarize(Player player, List<Notice> notices) {
        int deliveries = 0;
        long items = 0L;
        double value = 0.0D;
        int filled = 0;
        int expired = 0;
        int staff = 0;
        double refunds = 0.0D;
        for (Notice notice : notices) {
            Map<String, String> values = notice.values();
            switch (notice.kind()) {
                case DELIVERED -> {
                    deliveries++;
                    items += parseLong(values.get("amount"));
                    value += parseDouble(values.get("value"));
                }
                case FILLED -> filled++;
                case EXPIRED -> {
                    expired++;
                    refunds += parseDouble(values.get("refund"));
                }
                case CANCELLED_BY_STAFF -> {
                    staff++;
                    refunds += parseDouble(values.get("refund"));
                }
                case PAID -> refunds += parseDouble(values.get("amount"));
            }
        }
        messages().sendRaw(player, "notify.summary-header", "<#49d6a0><b>While you were away</b>");
        if (deliveries > 0) {
            player.sendMessage(messages().component("notify.summary-deliveries",
                    " <dark_gray>•</dark_gray> <white>%items%</white> items delivered to your orders <gray>(%value%)",
                    "items", String.valueOf(items), "value", money.apply(value)));
        }
        if (filled > 0) {
            player.sendMessage(messages().component("notify.summary-filled", " <dark_gray>•</dark_gray> <green>%count%</green> orders filled",
                    "count", String.valueOf(filled)));
        }
        if (expired + staff > 0) {
            player.sendMessage(messages().component("notify.summary-closed",
                    " <dark_gray>•</dark_gray> <gray>%count% orders ended, <gold>%refund%</gold> refunded",
                    "count", String.valueOf(expired + staff), "refund", money.apply(refunds)));
        }
        Sounds.play(player, config(), "delivery-received");
    }

    /** Delivers notices to players online here (cross-server notifications without Redis). */
    public void pollNotices() {
        List<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
        if (online.isEmpty()) return;
        repository.takeNotices(online).thenAccept(notices -> {
            Map<UUID, List<Notice>> byPlayer = new LinkedHashMap<>();
            for (Notice notice : notices) byPlayer.computeIfAbsent(notice.player(), id -> new java.util.ArrayList<>()).add(notice);
            byPlayer.forEach((id, list) -> {
                Player player = Bukkit.getPlayer(id);
                if (player == null) {
                    list.forEach(notice -> store(notice.player(), notice.kind(), notice.values()));
                    return;
                }
                scheduler.runEntity(player, () -> summarize(player, list), () -> list.forEach(notice ->
                        store(notice.player(), notice.kind(), notice.values())));
            });
        }).exceptionally(failure -> null);
    }

    public void forget(UUID player) {
        lastBroadcast.remove(player);
    }

    static String amountText(Order order) {
        return order.unlimited() ? "∞" : String.valueOf(order.amount());
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private static double parseDouble(String value) {
        try {
            return value == null ? 0.0D : Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return 0.0D;
        }
    }
}

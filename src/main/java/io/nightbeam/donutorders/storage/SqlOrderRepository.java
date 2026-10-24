package io.nightbeam.donutorders.storage;

import io.nightbeam.donutorders.model.Delivery;
import io.nightbeam.donutorders.model.MatchMode;
import io.nightbeam.donutorders.model.Notice;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.model.OrderStatus;
import io.nightbeam.donutorders.model.PlayerStats;
import io.nightbeam.donutorders.model.PriceInsight;
import io.nightbeam.donutorders.model.StoredStack;
import io.nightbeam.donutorders.util.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.bukkit.inventory.ItemStack;

/**
 * All order data lives here; the in-memory cache is only a view. Every change to an order is a conditional
 * update on its {@code version}, so two servers, threads or clicks can never both win: the loser re-reads and
 * tries again or gives up. Money columns move in the same statement as the status or counts they belong to.
 */
public final class SqlOrderRepository {

    static final int SCHEMA_VERSION = 1;
    private static final int RETRIES = 5;

    /** What happened to a delivery. */
    public enum DeliveryStatus { DELIVERED, NOT_FOUND, CLOSED, EXPIRED, FULL, BUSY }

    /**
     * {@code accepted} items were delivered for {@code payout} (before tax). {@code overflow} are offered items the
     * order no longer wanted; {@code dust} is escrow left over by rounding when the order filled, owed to its owner.
     */
    public record DeliveryOutcome(DeliveryStatus status, Order order, int accepted, double payout, double tax, double dust,
                                  List<ItemStack> overflow) {
    }

    /** {@code changed} is false when the order was already closed; {@code refund} is the escrow that was released. */
    public record CloseOutcome(boolean changed, Order order, double refund) {
    }

    public record Payout(long id, UUID player, double amount, String reason) {
    }

    private final DataSource dataSource;
    private final DatabaseType type;
    private final Executor executor;
    private final Logger logger;
    private final Set<String> unreadable = ConcurrentHashMap.newKeySet();

    public SqlOrderRepository(DataSource dataSource, DatabaseType type, Executor executor, Logger logger) {
        this.dataSource = dataSource;
        this.type = type;
        this.executor = executor;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ schema

    public CompletableFuture<Void> initialize() {
        return run(connection -> {
            String id = type == DatabaseType.MYSQL ? "BIGINT PRIMARY KEY AUTO_INCREMENT" : "INTEGER PRIMARY KEY AUTOINCREMENT";
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS donut_orders (
                            order_id VARCHAR(36) PRIMARY KEY,
                            owner_uuid VARCHAR(36) NOT NULL,
                            owner_name VARCHAR(64) NOT NULL,
                            server_order INT NOT NULL DEFAULT 0,
                            item_data LONGTEXT NOT NULL,
                            item_key VARCHAR(191) NOT NULL,
                            material VARCHAR(64) NOT NULL,
                            match_mode VARCHAR(16) NOT NULL,
                            amount INT NOT NULL,
                            delivered INT NOT NULL DEFAULT 0,
                            collected INT NOT NULL DEFAULT 0,
                            price DOUBLE NOT NULL,
                            escrow DOUBLE NOT NULL,
                            created_at BIGINT NOT NULL,
                            expires_at BIGINT NOT NULL,
                            status VARCHAR(16) NOT NULL,
                            closed_at BIGINT NOT NULL DEFAULT 0,
                            updated_at BIGINT NOT NULL,
                            version BIGINT NOT NULL DEFAULT 0
                        )""");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS donut_order_items (id " + id + ", order_id VARCHAR(36) NOT NULL,"
                        + " item_data LONGTEXT NOT NULL, amount INT NOT NULL, created_at BIGINT NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS donut_order_deliveries (id " + id + ", order_id VARCHAR(36) NOT NULL,"
                        + " seller_uuid VARCHAR(36) NOT NULL, seller_name VARCHAR(64) NOT NULL, owner_uuid VARCHAR(36) NOT NULL,"
                        + " owner_name VARCHAR(64) NOT NULL, item_key VARCHAR(191) NOT NULL, material VARCHAR(64) NOT NULL,"
                        + " amount INT NOT NULL, price DOUBLE NOT NULL, tax DOUBLE NOT NULL, delivered_at BIGINT NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS donut_order_mail (id " + id + ", player_uuid VARCHAR(36) NOT NULL,"
                        + " item_data LONGTEXT NOT NULL, amount INT NOT NULL, reason VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS donut_order_notices (id " + id + ", player_uuid VARCHAR(36) NOT NULL,"
                        + " kind VARCHAR(32) NOT NULL, payload TEXT NOT NULL, created_at BIGINT NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS donut_order_payouts (id " + id + ", player_uuid VARCHAR(36) NOT NULL,"
                        + " amount DOUBLE NOT NULL, reason VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS donut_orders_meta (meta_key VARCHAR(64) PRIMARY KEY, meta_value VARCHAR(255) NOT NULL)");
            }
            ensureIndex(connection, "donut_orders", "idx_donut_orders_status", "status, expires_at");
            ensureIndex(connection, "donut_orders", "idx_donut_orders_owner", "owner_uuid, status");
            ensureIndex(connection, "donut_orders", "idx_donut_orders_updated", "updated_at");
            ensureIndex(connection, "donut_order_items", "idx_donut_order_items_order", "order_id");
            ensureIndex(connection, "donut_order_deliveries", "idx_donut_order_deliveries_key", "item_key, delivered_at");
            ensureIndex(connection, "donut_order_deliveries", "idx_donut_order_deliveries_seller", "seller_uuid, delivered_at");
            ensureIndex(connection, "donut_order_deliveries", "idx_donut_order_deliveries_owner", "owner_uuid, delivered_at");
            ensureIndex(connection, "donut_order_deliveries", "idx_donut_order_deliveries_order", "order_id");
            ensureIndex(connection, "donut_order_mail", "idx_donut_order_mail_player", "player_uuid");
            ensureIndex(connection, "donut_order_notices", "idx_donut_order_notices_player", "player_uuid");
            ensureIndex(connection, "donut_order_payouts", "idx_donut_order_payouts_player", "player_uuid");
            if (meta(connection, "schema_version") == null) setMeta(connection, "schema_version", String.valueOf(SCHEMA_VERSION));
            return null;
        });
    }

    private void ensureIndex(Connection connection, String table, String name, String columns) throws SQLException {
        if (hasIndex(connection, table, name)) return;
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX " + name + " ON " + table + "(" + columns + ")");
        } catch (SQLException exception) {
            // Another server created it at the same moment.
            if (!hasIndex(connection, table, name)) throw exception;
        }
    }

    private static boolean hasIndex(Connection connection, String table, String name) throws SQLException {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (indexes.next()) if (name.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
            return false;
        }
    }

    static boolean hasTable(Connection connection, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, table, null)) {
            return tables.next();
        }
    }

    String meta(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT meta_value FROM donut_orders_meta WHERE meta_key = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    void setMeta(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE donut_orders_meta SET meta_value = ? WHERE meta_key = ?")) {
            update.setString(1, value);
            update.setString(2, key);
            if (update.executeUpdate() > 0) return;
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO donut_orders_meta(meta_key, meta_value) VALUES(?, ?)")) {
            insert.setString(1, key);
            insert.setString(2, value);
            insert.executeUpdate();
        } catch (SQLException exception) {
            if (meta(connection, key) == null) throw exception;
        }
    }

    public CompletableFuture<String> meta(String key) {
        return run(connection -> meta(connection, key));
    }

    public CompletableFuture<Void> setMeta(String key, String value) {
        return run(connection -> {
            setMeta(connection, key, value);
            return null;
        });
    }

    // ------------------------------------------------------------------ orders

    /** Orders that are open or still hold items for their owner. */
    public CompletableFuture<List<Order>> loadLive() {
        return run(connection -> queryOrders(connection, "SELECT * FROM donut_orders WHERE status = 'ACTIVE' OR collected < delivered"));
    }

    /** Orders changed since a timestamp (other servers' changes, for the cache). */
    public CompletableFuture<List<Order>> changedSince(long since) {
        return run(connection -> queryOrders(connection, "SELECT * FROM donut_orders WHERE updated_at > ?", since));
    }

    public CompletableFuture<Optional<Order>> find(UUID id) {
        return run(connection -> Optional.ofNullable(select(connection, id)));
    }

    /** A player's closed orders with nothing left to collect, newest first. */
    public CompletableFuture<List<Order>> history(UUID owner, int offset, int limit) {
        return run(connection -> queryOrders(connection, "SELECT * FROM donut_orders WHERE owner_uuid = ? AND status <> 'ACTIVE'"
                + " AND collected >= delivered ORDER BY closed_at DESC LIMIT ? OFFSET ?", owner.toString(), limit, offset));
    }

    public CompletableFuture<Integer> historyCount(UUID owner) {
        return run(connection -> count(connection, "SELECT COUNT(*) FROM donut_orders WHERE owner_uuid = ? AND status <> 'ACTIVE'"
                + " AND collected >= delivered", owner.toString()));
    }

    public CompletableFuture<Void> insert(Order order) {
        return run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO donut_orders(order_id, owner_uuid, owner_name,"
                    + " server_order, item_data, item_key, material, match_mode, amount, delivered, collected, price, escrow, created_at,"
                    + " expires_at, status, closed_at, updated_at, version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                int i = 1;
                statement.setString(i++, order.id().toString());
                statement.setString(i++, order.owner().toString());
                statement.setString(i++, order.ownerName());
                statement.setInt(i++, order.serverOrder() ? 1 : 0);
                statement.setString(i++, ItemStackSerializer.serialize(order.template()));
                statement.setString(i++, order.itemKey());
                statement.setString(i++, order.material());
                statement.setString(i++, order.mode().name());
                statement.setInt(i++, order.amount());
                statement.setInt(i++, order.delivered());
                statement.setInt(i++, order.collected());
                statement.setDouble(i++, order.price());
                statement.setDouble(i++, order.escrow());
                statement.setLong(i++, order.createdAt());
                statement.setLong(i++, order.expiresAt());
                statement.setString(i++, order.status().name());
                statement.setLong(i++, order.closedAt());
                statement.setLong(i++, order.updatedAt());
                statement.setLong(i, order.version());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Delivers matching items to an order. The order is re-read and the count capped to what it still wants;
     * the update only lands if nobody changed the order in between (otherwise it is retried). Accepted stacks are
     * stored for the owner (server orders keep nothing) and the delivery is recorded, all in one transaction.
     */
    public CompletableFuture<DeliveryOutcome> deliver(UUID orderId, List<ItemStack> matched, double taxRate, UUID seller,
                                                      String sellerName, long now) {
        return transaction(connection -> {
            int offered = units(matched);
            for (int attempt = 0; attempt < RETRIES; attempt++) {
                Order current = select(connection, orderId);
                if (current == null) return refused(DeliveryStatus.NOT_FOUND, null, matched);
                if (current.status() != OrderStatus.ACTIVE) return refused(DeliveryStatus.CLOSED, current, matched);
                if (current.expiresAt() <= now) return refused(DeliveryStatus.EXPIRED, current, matched);
                int accepted = Math.min(offered, current.remaining());
                if (accepted <= 0) return refused(DeliveryStatus.FULL, current, matched);

                double payout = Money.times(accepted, current.price());
                int delivered = current.delivered() + accepted;
                boolean filled = !current.unlimited() && delivered >= current.amount();
                double escrow = current.serverOrder() ? 0.0D : Money.minus(current.escrow(), payout);
                double dust = 0.0D;
                if (filled && escrow > 0.0D) {
                    dust = escrow;
                    escrow = 0.0D;
                }
                OrderStatus status = filled ? OrderStatus.FILLED : OrderStatus.ACTIVE;
                int collected = current.serverOrder() ? current.collected() + accepted : current.collected();
                try (PreparedStatement update = connection.prepareStatement("UPDATE donut_orders SET delivered = ?, collected = ?,"
                        + " escrow = ?, status = ?, closed_at = ?, updated_at = ?, version = version + 1"
                        + " WHERE order_id = ? AND version = ? AND status = 'ACTIVE'")) {
                    update.setInt(1, delivered);
                    update.setInt(2, collected);
                    update.setDouble(3, escrow);
                    update.setString(4, status.name());
                    update.setLong(5, filled ? now : 0L);
                    update.setLong(6, now);
                    update.setString(7, orderId.toString());
                    update.setLong(8, current.version());
                    if (update.executeUpdate() != 1) {
                        // Someone else changed the order: read it again.
                        continue;
                    }
                }
                Split split = split(matched, accepted);
                if (!current.serverOrder()) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO donut_order_items(order_id, item_data, amount, created_at) VALUES(?,?,?,?)")) {
                        for (ItemStack stack : split.accepted()) {
                            insert.setString(1, orderId.toString());
                            insert.setString(2, ItemStackSerializer.serialize(stack));
                            insert.setInt(3, stack.getAmount());
                            insert.setLong(4, now);
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                }
                double tax = Money.round(payout * Math.max(0.0D, Math.min(1.0D, taxRate)));
                try (PreparedStatement audit = connection.prepareStatement("INSERT INTO donut_order_deliveries(order_id, seller_uuid,"
                        + " seller_name, owner_uuid, owner_name, item_key, material, amount, price, tax, delivered_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                    audit.setString(1, orderId.toString());
                    audit.setString(2, seller.toString());
                    audit.setString(3, sellerName);
                    audit.setString(4, current.owner().toString());
                    audit.setString(5, current.ownerName());
                    audit.setString(6, current.itemKey());
                    audit.setString(7, current.material());
                    audit.setInt(8, accepted);
                    audit.setDouble(9, current.price());
                    audit.setDouble(10, tax);
                    audit.setLong(11, now);
                    audit.executeUpdate();
                }
                Order updated = new Order(current.id(), current.owner(), current.ownerName(), current.serverOrder(), current.template(),
                        current.itemKey(), current.material(), current.mode(), current.amount(), delivered, collected, current.price(),
                        escrow, current.createdAt(), current.expiresAt(), status, filled ? now : 0L, now, current.version() + 1);
                return new DeliveryOutcome(DeliveryStatus.DELIVERED, updated, accepted, payout, tax, dust, split.overflow());
            }
            return refused(DeliveryStatus.BUSY, select(connection, orderId), matched);
        });
    }

    private static DeliveryOutcome refused(DeliveryStatus status, Order order, List<ItemStack> matched) {
        return new DeliveryOutcome(status, order, 0, 0.0D, 0.0D, 0.0D, matched);
    }

    /**
     * Closes an open order and releases its escrow: the update only lands while the order is still open and
     * unchanged, so the stored escrow is refunded exactly once.
     */
    public CompletableFuture<CloseOutcome> close(UUID orderId, OrderStatus target, long now) {
        if (target == OrderStatus.ACTIVE) throw new IllegalArgumentException("Cannot close to ACTIVE");
        return transaction(connection -> {
            for (int attempt = 0; attempt < RETRIES; attempt++) {
                Order current = select(connection, orderId);
                if (current == null || current.status() != OrderStatus.ACTIVE) return new CloseOutcome(false, current, 0.0D);
                try (PreparedStatement update = connection.prepareStatement("UPDATE donut_orders SET status = ?, escrow = 0, closed_at = ?,"
                        + " updated_at = ?, version = version + 1 WHERE order_id = ? AND version = ? AND status = 'ACTIVE'")) {
                    update.setString(1, target.name());
                    update.setLong(2, now);
                    update.setLong(3, now);
                    update.setString(4, orderId.toString());
                    update.setLong(5, current.version());
                    if (update.executeUpdate() == 1) {
                        double refund = current.serverOrder() ? 0.0D : Money.round(current.escrow());
                        return new CloseOutcome(true, current.closed(target, now), refund);
                    }
                }
            }
            return new CloseOutcome(false, select(connection, orderId), 0.0D);
        });
    }

    /**
     * Changes amount, price and expiry of an open order, if it is still the version the player saw. The caller
     * has already charged (or will refund) the escrow difference.
     */
    public CompletableFuture<Optional<Order>> edit(UUID orderId, long expectedVersion, int amount, double price, double escrow,
                                                   long expiresAt, long now) {
        return transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement("UPDATE donut_orders SET amount = ?, price = ?, escrow = ?,"
                    + " expires_at = ?, updated_at = ?, version = version + 1 WHERE order_id = ? AND version = ? AND status = 'ACTIVE'"
                    + " AND delivered <= ?")) {
                update.setInt(1, amount);
                update.setDouble(2, price);
                update.setDouble(3, escrow);
                update.setLong(4, expiresAt);
                update.setLong(5, now);
                update.setString(6, orderId.toString());
                update.setLong(7, expectedVersion);
                update.setInt(8, amount);
                if (update.executeUpdate() != 1) return Optional.<Order>empty();
            }
            return Optional.ofNullable(select(connection, orderId));
        });
    }

    // ------------------------------------------------------------------ stored items

    public CompletableFuture<List<StoredStack>> itemsOf(UUID orderId) {
        return run(connection -> stacks(connection, "SELECT id, item_data FROM donut_order_items WHERE order_id = ? ORDER BY id",
                orderId.toString()));
    }

    /**
     * Takes stored stacks out of an order for its owner: each row is deleted on its own, and only rows this call
     * deleted are returned, so two servers or clicks can never hand out the same stack.
     */
    public CompletableFuture<List<StoredStack>> takeItems(UUID orderId, List<StoredStack> wanted, long now) {
        return transaction(connection -> {
            List<StoredStack> taken = takeRows(connection, "donut_order_items", wanted);
            int units = 0;
            for (StoredStack stack : taken) units += stack.item().getAmount();
            if (units > 0) {
                try (PreparedStatement update = connection.prepareStatement("UPDATE donut_orders SET collected = collected + ?,"
                        + " updated_at = ?, version = version + 1 WHERE order_id = ?")) {
                    update.setInt(1, units);
                    update.setLong(2, now);
                    update.setString(3, orderId.toString());
                    update.executeUpdate();
                }
            }
            return taken;
        });
    }

    public CompletableFuture<List<StoredStack>> mailOf(UUID player) {
        return run(connection -> stacks(connection, "SELECT id, item_data FROM donut_order_mail WHERE player_uuid = ? ORDER BY id",
                player.toString()));
    }

    public CompletableFuture<Integer> mailCount(UUID player) {
        return run(connection -> count(connection, "SELECT COALESCE(SUM(amount), 0) FROM donut_order_mail WHERE player_uuid = ?",
                player.toString()));
    }

    public CompletableFuture<List<StoredStack>> takeMail(List<StoredStack> wanted) {
        return transaction(connection -> takeRows(connection, "donut_order_mail", wanted));
    }

    /** Puts items in a player's mailbox (anything that could not be handed over). */
    public CompletableFuture<Void> addMail(UUID player, Collection<ItemStack> items, String reason, long now) {
        if (items.isEmpty()) return CompletableFuture.completedFuture(null);
        return transaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO donut_order_mail(player_uuid, item_data, amount, reason, created_at) VALUES(?,?,?,?,?)")) {
                for (ItemStack item : items) {
                    if (item == null || item.getType().isAir()) continue;
                    insert.setString(1, player.toString());
                    insert.setString(2, ItemStackSerializer.serialize(item));
                    insert.setInt(3, item.getAmount());
                    insert.setString(4, reason);
                    insert.setLong(5, now);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return null;
        });
    }

    /** Writes mail on the calling thread; only for shutdown, when the database executor is gone. */
    public void addMailNow(UUID player, Collection<ItemStack> items, String reason, long now) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO donut_order_mail(player_uuid, item_data, amount, reason, created_at) VALUES(?,?,?,?,?)")) {
            for (ItemStack item : items) {
                if (item == null || item.getType().isAir()) continue;
                insert.setString(1, player.toString());
                insert.setString(2, ItemStackSerializer.serialize(item));
                insert.setInt(3, item.getAmount());
                insert.setString(4, reason);
                insert.setLong(5, now);
                insert.executeUpdate();
            }
        }
    }

    /** Records money owed on the calling thread; only for shutdown. */
    public void addPayoutNow(UUID player, double amount, String reason, long now) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO donut_order_payouts(player_uuid, amount, reason, created_at) VALUES(?,?,?,?)")) {
            insert.setString(1, player.toString());
            insert.setDouble(2, Money.round(amount));
            insert.setString(3, reason);
            insert.setLong(4, now);
            insert.executeUpdate();
        }
    }

    private List<StoredStack> takeRows(Connection connection, String table, List<StoredStack> wanted) throws SQLException {
        List<StoredStack> taken = new ArrayList<>();
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            for (StoredStack stack : wanted) {
                delete.setLong(1, stack.id());
                if (delete.executeUpdate() == 1) taken.add(stack);
            }
        }
        return taken;
    }

    private List<StoredStack> stacks(Connection connection, String sql, Object... parameters) throws SQLException {
        List<StoredStack> stacks = new ArrayList<>();
        try (PreparedStatement statement = prepare(connection, sql, parameters); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong("id");
                try {
                    stacks.add(new StoredStack(id, ItemStackSerializer.deserialize(rows.getString("item_data"))));
                } catch (RuntimeException exception) {
                    if (unreadable.add("stack-" + id)) {
                        logger.warning("A stored item (row " + id + ") cannot be read on this server version and is skipped: "
                                + exception.getMessage());
                    }
                }
            }
        }
        return stacks;
    }

    /** Total items waiting in a player's orders and mailbox. */
    public CompletableFuture<Integer> waitingFor(UUID player) {
        return run(connection -> count(connection, "SELECT COALESCE(SUM(delivered - collected), 0) FROM donut_orders WHERE owner_uuid = ?",
                player.toString()) + count(connection, "SELECT COALESCE(SUM(amount), 0) FROM donut_order_mail WHERE player_uuid = ?",
                player.toString()));
    }

    // ------------------------------------------------------------------ notices and payouts

    public CompletableFuture<Void> addNotice(Notice notice) {
        return run(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO donut_order_notices(player_uuid, kind, payload, created_at) VALUES(?,?,?,?)")) {
                insert.setString(1, notice.player().toString());
                insert.setString(2, notice.kind().name());
                insert.setString(3, encode(notice.values()));
                insert.setLong(4, notice.at());
                insert.executeUpdate();
            }
            return null;
        });
    }

    /** Removes and returns the waiting notices of the given players. */
    public CompletableFuture<List<Notice>> takeNotices(Collection<UUID> players) {
        if (players.isEmpty()) return CompletableFuture.completedFuture(List.of());
        return transaction(connection -> {
            List<Notice> notices = new ArrayList<>();
            String marks = String.join(",", java.util.Collections.nCopies(players.size(), "?"));
            try (PreparedStatement statement = prepare(connection, "SELECT * FROM donut_order_notices WHERE player_uuid IN (" + marks
                    + ") ORDER BY id", players.stream().map(UUID::toString).toArray()); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Notice.Kind kind;
                    try {
                        kind = Notice.Kind.valueOf(rows.getString("kind"));
                    } catch (IllegalArgumentException unknown) {
                        continue;
                    }
                    notices.add(new Notice(rows.getLong("id"), UUID.fromString(rows.getString("player_uuid")), kind,
                            decode(rows.getString("payload")), rows.getLong("created_at")));
                }
            }
            List<Notice> taken = new ArrayList<>();
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM donut_order_notices WHERE id = ?")) {
                for (Notice notice : notices) {
                    delete.setLong(1, notice.id());
                    if (delete.executeUpdate() == 1) taken.add(notice);
                }
            }
            return taken;
        });
    }

    public CompletableFuture<Void> addPayout(UUID player, double amount, String reason, long now) {
        return run(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO donut_order_payouts(player_uuid, amount, reason, created_at) VALUES(?,?,?,?)")) {
                insert.setString(1, player.toString());
                insert.setDouble(2, Money.round(amount));
                insert.setString(3, reason);
                insert.setLong(4, now);
                insert.executeUpdate();
            }
            return null;
        });
    }

    /** Removes and returns money owed to a player (each row exactly once). */
    public CompletableFuture<List<Payout>> takePayouts(UUID player) {
        return transaction(connection -> {
            List<Payout> payouts = new ArrayList<>();
            try (PreparedStatement statement = prepare(connection, "SELECT * FROM donut_order_payouts WHERE player_uuid = ? ORDER BY id",
                    player.toString()); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    payouts.add(new Payout(rows.getLong("id"), player, rows.getDouble("amount"), rows.getString("reason")));
                }
            }
            List<Payout> taken = new ArrayList<>();
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM donut_order_payouts WHERE id = ?")) {
                for (Payout payout : payouts) {
                    delete.setLong(1, payout.id());
                    if (delete.executeUpdate() == 1) taken.add(payout);
                }
            }
            return taken;
        });
    }

    /** Players with money waiting (for the periodic retry). */
    public CompletableFuture<List<UUID>> playersOwed() {
        return run(connection -> {
            List<UUID> players = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT DISTINCT player_uuid FROM donut_order_payouts")) {
                while (rows.next()) players.add(UUID.fromString(rows.getString(1)));
            }
            return players;
        });
    }

    // ------------------------------------------------------------------ insight and statistics

    /** Deliveries of an item since a timestamp: count, items, weighted average, range and last price. */
    public CompletableFuture<PriceInsight> insight(String itemKey, long since) {
        return run(connection -> {
            try (PreparedStatement statement = prepare(connection, "SELECT COUNT(*), COALESCE(SUM(amount), 0), COALESCE(SUM(amount * price), 0),"
                    + " COALESCE(MIN(price), 0), COALESCE(MAX(price), 0) FROM donut_order_deliveries WHERE item_key = ? AND delivered_at >= ?",
                    itemKey, since); ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) == 0) return PriceInsight.empty(itemKey);
                int deliveries = rows.getInt(1);
                long items = rows.getLong(2);
                double average = items > 0 ? rows.getDouble(3) / items : 0.0D;
                double min = rows.getDouble(4);
                double max = rows.getDouble(5);
                double last = 0.0D;
                try (PreparedStatement latest = prepare(connection, "SELECT price FROM donut_order_deliveries WHERE item_key = ?"
                        + " ORDER BY delivered_at DESC, id DESC LIMIT 1", itemKey); ResultSet row = latest.executeQuery()) {
                    if (row.next()) last = row.getDouble(1);
                }
                return new PriceInsight(itemKey, deliveries, items, Money.round(average), min, max, last, 0.0D, 0L, 0);
            }
        });
    }

    public CompletableFuture<PlayerStats> stats(UUID player) {
        return run(connection -> {
            long delivered = 0L;
            double earned = 0.0D;
            long received = 0L;
            double spent = 0.0D;
            try (PreparedStatement statement = prepare(connection, "SELECT COALESCE(SUM(amount), 0), COALESCE(SUM(amount * price - tax), 0)"
                    + " FROM donut_order_deliveries WHERE seller_uuid = ?", player.toString()); ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    delivered = rows.getLong(1);
                    earned = rows.getDouble(2);
                }
            }
            try (PreparedStatement statement = prepare(connection, "SELECT COALESCE(SUM(amount), 0), COALESCE(SUM(amount * price), 0)"
                    + " FROM donut_order_deliveries WHERE owner_uuid = ?", player.toString()); ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    received = rows.getLong(1);
                    spent = rows.getDouble(2);
                }
            }
            return new PlayerStats(delivered, Money.round(earned), received, Money.round(spent));
        });
    }

    /** Recent deliveries to one order (who filled it), newest first. */
    public CompletableFuture<List<Delivery>> deliveriesTo(UUID orderId, int limit) {
        return run(connection -> deliveries(connection, "SELECT * FROM donut_order_deliveries WHERE order_id = ?"
                + " ORDER BY delivered_at DESC, id DESC LIMIT ?", orderId.toString(), limit));
    }

    /** A player's own deliveries to other players' orders, newest first. */
    public CompletableFuture<List<Delivery>> deliveriesBy(UUID seller, int offset, int limit) {
        return run(connection -> deliveries(connection, "SELECT * FROM donut_order_deliveries WHERE seller_uuid = ?"
                + " ORDER BY delivered_at DESC, id DESC LIMIT ? OFFSET ?", seller.toString(), limit, offset));
    }

    private List<Delivery> deliveries(Connection connection, String sql, Object... parameters) throws SQLException {
        List<Delivery> deliveries = new ArrayList<>();
        try (PreparedStatement statement = prepare(connection, sql, parameters); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                deliveries.add(new Delivery(rows.getLong("id"), UUID.fromString(rows.getString("order_id")),
                        UUID.fromString(rows.getString("seller_uuid")), rows.getString("seller_name"),
                        UUID.fromString(rows.getString("owner_uuid")), rows.getString("owner_name"), rows.getString("item_key"),
                        rows.getString("material"), rows.getInt("amount"), rows.getDouble("price"), rows.getDouble("tax"),
                        rows.getLong("delivered_at")));
            }
        }
        return deliveries;
    }

    /** Deletes closed, fully collected orders and deliveries older than the cutoff. Returns the orders removed. */
    public CompletableFuture<Integer> purge(long closedBefore) {
        return transaction(connection -> {
            int removed;
            try (PreparedStatement orders = connection.prepareStatement("DELETE FROM donut_orders WHERE status <> 'ACTIVE'"
                    + " AND collected >= delivered AND closed_at > 0 AND closed_at < ?")) {
                orders.setLong(1, closedBefore);
                removed = orders.executeUpdate();
            }
            try (PreparedStatement deliveries = connection.prepareStatement("DELETE FROM donut_order_deliveries WHERE delivered_at < ?")) {
                deliveries.setLong(1, closedBefore);
                deliveries.executeUpdate();
            }
            try (PreparedStatement notices = connection.prepareStatement("DELETE FROM donut_order_notices WHERE created_at < ?")) {
                notices.setLong(1, closedBefore);
                notices.executeUpdate();
            }
            return removed;
        });
    }

    public CompletableFuture<Map<String, Integer>> counts() {
        return run(connection -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT status, COUNT(*) FROM donut_orders GROUP BY status")) {
                while (rows.next()) counts.put(rows.getString(1), rows.getInt(2));
            }
            counts.put("STORED_STACKS", count(connection, "SELECT COUNT(*) FROM donut_order_items"));
            counts.put("MAIL_STACKS", count(connection, "SELECT COUNT(*) FROM donut_order_mail"));
            counts.put("DELIVERIES", count(connection, "SELECT COUNT(*) FROM donut_order_deliveries"));
            counts.put("PAYOUTS_OWED", count(connection, "SELECT COUNT(*) FROM donut_order_payouts"));
            return counts;
        });
    }

    public int unreadableRows() {
        return unreadable.size();
    }

    // ------------------------------------------------------------------ helpers

    Order select(Connection connection, UUID id) throws SQLException {
        List<Order> orders = queryOrders(connection, "SELECT * FROM donut_orders WHERE order_id = ?", id.toString());
        return orders.isEmpty() ? null : orders.get(0);
    }

    private List<Order> queryOrders(Connection connection, String sql, Object... parameters) throws SQLException {
        List<Order> orders = new ArrayList<>();
        try (PreparedStatement statement = prepare(connection, sql, parameters); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                Order order = readOrder(rows);
                if (order != null) orders.add(order);
            }
        }
        return orders;
    }

    private Order readOrder(ResultSet rows) throws SQLException {
        String id = rows.getString("order_id");
        ItemStack template;
        try {
            template = ItemStackSerializer.deserialize(rows.getString("item_data"));
        } catch (RuntimeException exception) {
            if (unreadable.add(id)) {
                logger.warning("Order " + id + " has an item this server cannot read and is skipped: " + exception.getMessage());
            }
            return null;
        }
        return new Order(UUID.fromString(id), UUID.fromString(rows.getString("owner_uuid")), rows.getString("owner_name"),
                rows.getInt("server_order") != 0, template, rows.getString("item_key"), rows.getString("material"),
                MatchMode.parse(rows.getString("match_mode")), rows.getInt("amount"), rows.getInt("delivered"), rows.getInt("collected"),
                rows.getDouble("price"), rows.getDouble("escrow"), rows.getLong("created_at"), rows.getLong("expires_at"),
                OrderStatus.parse(rows.getString("status")), rows.getLong("closed_at"), rows.getLong("updated_at"), rows.getLong("version"));
    }

    private static int count(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters); ResultSet rows = statement.executeQuery()) {
            return rows.next() ? (int) Math.min(Integer.MAX_VALUE, rows.getLong(1)) : 0;
        }
    }

    static PreparedStatement prepare(Connection connection, String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < parameters.length; i++) {
            Object value = parameters[i];
            if (value instanceof Integer number) statement.setInt(i + 1, number);
            else if (value instanceof Long number) statement.setLong(i + 1, number);
            else if (value instanceof Double number) statement.setDouble(i + 1, number);
            else statement.setString(i + 1, String.valueOf(value));
        }
        return statement;
    }

    static int units(List<ItemStack> stacks) {
        int units = 0;
        for (ItemStack stack : stacks) if (stack != null && !stack.getType().isAir()) units += stack.getAmount();
        return units;
    }

    /** The first {@code units} items of the stacks, and the rest. Stacks are copied, never modified. */
    public record Split(List<ItemStack> accepted, List<ItemStack> overflow) {
    }

    public static Split split(List<ItemStack> stacks, int units) {
        List<ItemStack> accepted = new ArrayList<>();
        List<ItemStack> overflow = new ArrayList<>();
        int left = Math.max(0, units);
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getType().isAir()) continue;
            int amount = stack.getAmount();
            if (left >= amount) {
                accepted.add(stack.clone());
                left -= amount;
            } else if (left > 0) {
                ItemStack part = stack.clone();
                part.setAmount(left);
                accepted.add(part);
                ItemStack rest = stack.clone();
                rest.setAmount(amount - left);
                overflow.add(rest);
                left = 0;
            } else {
                overflow.add(stack.clone());
            }
        }
        return new Split(accepted, overflow);
    }

    /** Notice values as "key=value" lines; values are escaped so they can hold anything. */
    static String encode(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        values.forEach((key, value) -> out.append(escape(key)).append('=').append(escape(value)).append('\n'));
        return out.toString();
    }

    static Map<String, String> decode(String payload) {
        Map<String, String> values = new LinkedHashMap<>();
        if (payload == null) return values;
        for (String line : payload.split("\n")) {
            int split = -1;
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == '\\') i++;
                else if (line.charAt(i) == '=') {
                    split = i;
                    break;
                }
            }
            if (split > 0) values.put(unescape(line.substring(0, split)), unescape(line.substring(split + 1)));
        }
        return values;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\=");
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                out.append(next == 'n' ? '\n' : next);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    DataSource dataSource() {
        return dataSource;
    }

    DatabaseType type() {
        return type;
    }

    Logger logger() {
        return logger;
    }

    @FunctionalInterface
    interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    <T> CompletableFuture<T> run(SqlWork<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return work.apply(connection);
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    /** Runs the work in one transaction: committed if it returns, rolled back if it throws. */
    <T> CompletableFuture<T> transaction(SqlWork<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    T result = work.apply(connection);
                    connection.commit();
                    return result;
                } catch (SQLException | RuntimeException exception) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollback) {
                        exception.addSuppressed(rollback);
                    }
                    throw exception;
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }
}

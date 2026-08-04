package com.donutorders.storage;

import com.donutorders.DonutOrders;
import com.donutorders.model.Order;
import com.donutorders.model.OrderStatus;
import com.donutorders.scheduler.FoliaScheduler;
import com.donutorders.util.ItemUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages all persistence for DonutOrders.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>SQLite database with WAL mode — single connection pool (size = 1) as
 *       required for SQLite's WAL concurrency model.</li>
 *   <li>In-memory cache ({@link ConcurrentHashMap}) is the primary read path;
 *       cache reads are always synchronous and safe for Folia region threads.</li>
 *   <li>All database <em>writes</em> are dispatched via {@link FoliaScheduler#runAsync}
 *       so they never block any region thread.</li>
 *   <li>All callbacks that touch Bukkit API must be re-scheduled onto the correct
 *       thread by the caller (OrderManager does this via runAtEntity).</li>
 * </ul>
 *
 * <h2>Schema</h2>
 * <pre>
 * orders(
 *   order_id       TEXT PRIMARY KEY,
 *   buyer_uuid     TEXT NOT NULL,
 *   buyer_name     TEXT NOT NULL,
 *   item_data      TEXT NOT NULL,   -- Base64-serialised ItemStack
 *   amount_req     INTEGER NOT NULL,
 *   amount_filled  INTEGER NOT NULL,
 *   price_per_item REAL NOT NULL,
 *   remaining_funds REAL NOT NULL,
 *   created_at     INTEGER NOT NULL,
 *   expires_at     INTEGER NOT NULL,
 *   status         TEXT NOT NULL
 * )
 *
 * order_stash(
 *   order_id   TEXT NOT NULL REFERENCES orders(order_id),
 *   stash_data TEXT NOT NULL,       -- Base64-serialised ItemStack[]
 *   PRIMARY KEY (order_id)
 * )
 * </pre>
 */
public class StorageManager {

    private static final Logger LOG = DonutOrders.getInstance().getLogger();

    private final HikariDataSource dataSource;

    // ── In-memory cache ───────────────────────────────────────────────────────

    /** Primary cache: orderId → Order. Thread-safe for concurrent reads. */
    private final ConcurrentHashMap<UUID, Order> orderCache = new ConcurrentHashMap<>();

    /**
     * Secondary index: buyerUUID → list of orderIds.
     * The inner List is wrapped in a copy-on-write pattern (reassigned atomically).
     */
    private final ConcurrentHashMap<UUID, List<UUID>> playerOrderIndex = new ConcurrentHashMap<>();

    // ── Constructor / startup ─────────────────────────────────────────────────

    public StorageManager(File dataFolder) throws SQLException {
        File dbFile = new File(dataFolder, DonutOrders.getInstance()
                .getConfig().getString("database.filename", "donutorders.db"));

        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());

        // SQLite must use pool size of exactly 1 to avoid write-lock contention.
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(30_000);
        cfg.setIdleTimeout(0);        // never evict the single connection
        cfg.setMaxLifetime(0);        // never recycle it
        cfg.setPoolName("DonutOrders-SQLite");

        // Enable WAL mode + set a busy timeout so concurrent readers don't
        // immediately fail when the writer holds the lock.
        cfg.setConnectionInitSql(
                "PRAGMA journal_mode=WAL; " +
                "PRAGMA busy_timeout=30000; " +
                "PRAGMA synchronous=NORMAL;");

        this.dataSource = new HikariDataSource(cfg);
        createTables();
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    private void createTables() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS orders (" +
                "  order_id        TEXT PRIMARY KEY," +
                "  buyer_uuid      TEXT NOT NULL," +
                "  buyer_name      TEXT NOT NULL," +
                "  item_data       TEXT NOT NULL," +
                "  amount_req      INTEGER NOT NULL," +
                "  amount_filled   INTEGER NOT NULL," +
                "  price_per_item  REAL NOT NULL," +
                "  remaining_funds REAL NOT NULL," +
                "  created_at      INTEGER NOT NULL," +
                "  expires_at      INTEGER NOT NULL," +
                "  status          TEXT NOT NULL," +
                "  claimed_at      INTEGER DEFAULT 0," +
                "  claimed_by      TEXT DEFAULT NULL" +
                ")");
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS order_stash (" +
                "  order_id   TEXT PRIMARY KEY," +
                "  stash_data TEXT NOT NULL" +
                ")");

            // Execute schema upgrades for existing installations
            try {
                stmt.executeUpdate("ALTER TABLE orders ADD COLUMN claimed_at INTEGER DEFAULT 0");
            } catch (SQLException ignored) {}
            try {
                stmt.executeUpdate("ALTER TABLE orders ADD COLUMN claimed_by TEXT DEFAULT NULL");
            } catch (SQLException ignored) {}
        }
    }

    // ── Startup load ──────────────────────────────────────────────────────────

    /**
     * Loads all orders from the database into the in-memory cache.
     * Runs asynchronously; {@code onDone} is called on the global/main thread
     * after loading is complete.
     */
    public void loadAll(Runnable onDone) {
        Runnable finish = () -> {
            try {
                onDone.run();
            } catch (Throwable t) {
                LOG.log(Level.SEVERE, "Order load completion callback failed", t);
            }
        };

        try {
            FoliaScheduler.runAsync(() -> {
                loadAllIntoCache();
                FoliaScheduler.runGlobal(finish);
            });
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING,
                    "Async scheduler unavailable during startup; loading orders synchronously", ex);
            loadAllIntoCache();
            FoliaScheduler.runGlobal(finish);
        }
    }

    /** Reads every order row from SQLite into the in-memory cache. */
    private void loadAllIntoCache() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM orders")) {

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Order order = rowToOrder(rs);
                if (order != null) {
                    putToCache(order);
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Failed to load orders from database", e);
        }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    /**
     * Inserts a brand-new order. Updates cache immediately; persists async.
     * {@code callback} is called on the async thread — callers must re-schedule
     * to a game thread before touching Bukkit API.
     */
    public void saveOrder(Order order, Runnable callback) {
        // Update cache first so the order is instantly visible.
        putToCache(order);

        FoliaScheduler.runAsync(() -> {
            final String sql =
                "INSERT OR REPLACE INTO orders " +
                "(order_id, buyer_uuid, buyer_name, item_data, amount_req, " +
                " amount_filled, price_per_item, remaining_funds, created_at, " +
                " expires_at, status, claimed_at, claimed_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                bindOrder(ps, order);
                ps.executeUpdate();
            } catch (SQLException e) {
                LOG.log(Level.SEVERE, "Failed to save order " + order.getOrderId(), e);
            }
            if (callback != null) callback.run();
        });
    }

    /**
     * Updates an existing order's mutable fields in the database.
     * Cache is updated synchronously before the async write.
     */
    public void updateOrder(Order order, Runnable callback) {
        // Cache is already up-to-date (object mutation is in-place) but we
        // still call putToCache to refresh the player index if status changed.
        putToCache(order);

        FoliaScheduler.runAsync(() -> {
            final String sql =
                "UPDATE orders SET " +
                "  amount_filled=?, remaining_funds=?, status=?, claimed_at=?, claimed_by=? " +
                "WHERE order_id=?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, order.getAmountFulfilled());
                ps.setDouble(2, order.getRemainingFunds());
                ps.setString(3, order.getStatus().name());
                ps.setLong(4, order.getClaimedAt());
                ps.setString(5, order.getClaimedBy() != null ? order.getClaimedBy().toString() : null);
                ps.setString(6, order.getOrderId().toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                LOG.log(Level.SEVERE, "Failed to update order " + order.getOrderId(), e);
            }
            if (callback != null) callback.run();
        });
    }

    /**
     * Saves (upserts) a 54-slot stash for an order. Async.
     *
     * @param orderId  the order whose stash to save
     * @param stash    54-element ItemStack array (nulls = empty slots)
     * @param callback called on the async thread after write completes
     */
    public void saveStash(UUID orderId, ItemStack[] stash, Runnable callback) {
        String data = ItemUtils.serializeItemArray(stash);
        FoliaScheduler.runAsync(() -> {
            final String sql =
                "INSERT OR REPLACE INTO order_stash (order_id, stash_data) VALUES (?,?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, orderId.toString());
                ps.setString(2, data);
                ps.executeUpdate();
            } catch (SQLException e) {
                LOG.log(Level.SEVERE, "Failed to save stash for " + orderId, e);
            }
            if (callback != null) callback.run();
        });
    }

    /**
     * Loads the stash for an order asynchronously, then delivers the result
     * to {@code callback} on the async thread. Callers must re-schedule to
     * the correct game thread before opening inventories.
     */
    public void loadStash(UUID orderId, Consumer<ItemStack[]> callback) {
        FoliaScheduler.runAsync(() -> {
            ItemStack[] result = new ItemStack[54];
            final String sql = "SELECT stash_data FROM order_stash WHERE order_id=?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, orderId.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    result = ItemUtils.deserializeItemArray(rs.getString("stash_data"), 54);
                }
            } catch (SQLException e) {
                LOG.log(Level.SEVERE, "Failed to load stash for " + orderId, e);
            }
            callback.accept(result);
        });
    }

    /**
     * Clears the stash row for an order (called when items are fully collected
     * or the order is cleaned up). Async.
     */
    public void clearStash(UUID orderId, Runnable callback) {
        FoliaScheduler.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM order_stash WHERE order_id=?")) {
                ps.setString(1, orderId.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                LOG.log(Level.SEVERE, "Failed to clear stash for " + orderId, e);
            }
            if (callback != null) callback.run();
        });
    }

    // ── Cache accessors (synchronous — safe for region threads) ───────────────

    /** Returns the Order for the given id, or {@code null} if not found. */
    public Order getOrder(UUID orderId) {
        return orderCache.get(orderId);
    }

    /**
     * Returns an unmodifiable snapshot of all orders belonging to a player.
     * The list may be empty but is never null.
     */
    public List<Order> getPlayerOrders(UUID playerUUID) {
        List<UUID> ids = playerOrderIndex.getOrDefault(playerUUID, Collections.emptyList());
        List<Order> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Order o = orderCache.get(id);
            if (o != null) result.add(o);
        }
        return result;
    }

    /**
     * Returns an unmodifiable snapshot of all currently ACTIVE orders across
     * all players, sorted by creation time (oldest first).
     */
    public List<Order> getAllActiveOrders() {
        List<Order> active = new ArrayList<>();
        for (Order o : orderCache.values()) {
            if (o.getStatus() == OrderStatus.ACTIVE) {
                active.add(o);
            }
        }
        active.sort(Comparator.comparingLong(Order::getCreatedAt));
        return active;
    }

    /**
     * Returns every order in the cache across all statuses.
     * Used by the expiry checker.
     */
    public Collection<Order> getAllOrders() {
        return Collections.unmodifiableCollection(orderCache.values());
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    /**
     * Gracefully shuts down the HikariCP connection pool.
     * Call this from {@code onDisable}.
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Adds / refreshes an order in both caches. */
    private void putToCache(Order order) {
        orderCache.put(order.getOrderId(), order);

        playerOrderIndex.compute(order.getBuyerUUID(), (k, existing) -> {
            if (existing == null) {
                List<UUID> list = new ArrayList<>();
                list.add(order.getOrderId());
                return list;
            }
            if (!existing.contains(order.getOrderId())) {
                List<UUID> list = new ArrayList<>(existing);
                list.add(order.getOrderId());
                return list;
            }
            return existing;
        });
    }

    /** Binds all Order fields to a PreparedStatement for the INSERT statement. */
    private void bindOrder(PreparedStatement ps, Order o) throws SQLException {
        ps.setString(1, o.getOrderId().toString());
        ps.setString(2, o.getBuyerUUID().toString());
        ps.setString(3, o.getBuyerName());
        ps.setString(4, ItemUtils.serializeItem(o.getItemTemplate()));
        ps.setInt(5, o.getAmountRequested());
        ps.setInt(6, o.getAmountFulfilled());
        ps.setDouble(7, o.getPricePerItem());
        ps.setDouble(8, o.getRemainingFunds());
        ps.setLong(9, o.getCreatedAt());
        ps.setLong(10, o.getExpiresAt());
        ps.setString(11, o.getStatus().name());
        ps.setLong(12, o.getClaimedAt());
        ps.setString(13, o.getClaimedBy() != null ? o.getClaimedBy().toString() : null);
    }

    /** Converts a ResultSet row to an Order. Returns null on parse failure. */
    private Order rowToOrder(ResultSet rs) {
        try {
            UUID orderId   = UUID.fromString(rs.getString("order_id"));
            UUID buyerUUID = UUID.fromString(rs.getString("buyer_uuid"));
            String buyerName = rs.getString("buyer_name");
            ItemStack template = ItemUtils.deserializeItem(rs.getString("item_data"));
            if (template == null) template = new ItemStack(Material.PAPER);

            int amountReq    = rs.getInt("amount_req");
            int amountFilled = rs.getInt("amount_filled");
            double price     = rs.getDouble("price_per_item");
            double funds     = rs.getDouble("remaining_funds");
            long createdAt   = rs.getLong("created_at");
            long expiresAt   = rs.getLong("expires_at");
            OrderStatus status = OrderStatus.valueOf(rs.getString("status"));

            long claimedAt   = rs.getLong("claimed_at");
            String claimedByStr = rs.getString("claimed_by");
            UUID claimedBy = (claimedByStr != null && !claimedByStr.isEmpty()) ? UUID.fromString(claimedByStr) : null;

            return new Order(orderId, buyerUUID, buyerName, template,
                    amountReq, amountFilled, price, funds,
                    createdAt, expiresAt, status, claimedAt, claimedBy);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse order row", e);
            return null;
        }
    }
}

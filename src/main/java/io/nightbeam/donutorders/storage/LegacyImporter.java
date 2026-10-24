package io.nightbeam.donutorders.storage;

import io.nightbeam.donutorders.model.MatchMode;
import io.nightbeam.donutorders.model.OrderStatus;
import io.nightbeam.donutorders.service.ItemKeys;
import io.nightbeam.donutorders.util.Enchants;
import io.nightbeam.donutorders.util.Money;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;

/**
 * Imports DonutOrders 1.x data ({@code orders} and {@code order_stash}) once. The 1.x tables are left as they
 * were, so a downgrade still finds them.
 *
 * <p>1.x semantics: ACTIVE rows hold their escrow in {@code remaining_funds}; PENDING rows are closed orders whose
 * stash was not collected yet (cancelled ones were refunded at once, expired ones were refunded on collection);
 * CLAIMED rows were collected and refunded. Expired orders that still hold money are imported as open orders past
 * their expiry, so 2.0 expires them through the normal path and refunds the stored escrow exactly once.
 */
public final class LegacyImporter {

    public record Result(int orders, int stacks, int skipped) {
    }

    /** Reads a 1.x stash column into its stacks. */
    @FunctionalInterface
    public interface StashDecoder {
        List<ItemStack> decode(String data) throws IOException, ClassNotFoundException;
    }

    private final SqlOrderRepository repository;
    private final StashDecoder stashDecoder;

    public LegacyImporter(SqlOrderRepository repository) {
        this(repository, LegacyImporter::decodeStash);
    }

    LegacyImporter(SqlOrderRepository repository, StashDecoder stashDecoder) {
        this.repository = repository;
        this.stashDecoder = stashDecoder;
    }

    public CompletableFuture<Result> importOnce() {
        return repository.transaction(connection -> {
            if (repository.meta(connection, "legacy_import") != null) return new Result(0, 0, 0);
            if (!SqlOrderRepository.hasTable(connection, "orders")) {
                repository.setMeta(connection, "legacy_import", "none");
                return new Result(0, 0, 0);
            }
            Map<String, List<ItemStack>> stashes = SqlOrderRepository.hasTable(connection, "order_stash") ? stashes(connection) : Map.of();
            int orders = 0;
            int stacks = 0;
            int skipped = 0;
            long now = System.currentTimeMillis();
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM orders")) {
                while (rows.next()) {
                    String id = rows.getString("order_id");
                    if (exists(connection, id)) continue;
                    ItemStack template;
                    try {
                        template = ItemStackSerializer.deserialize(rows.getString("item_data"));
                    } catch (RuntimeException unreadable) {
                        repository.logger().warning("1.x order " + id + " has an item this server cannot read; it was not imported.");
                        skipped++;
                        continue;
                    }
                    template.setAmount(1);
                    MatchMode mode = template.getType() == Material.ENCHANTED_BOOK && Enchants.stored(template).size() == 1
                            ? MatchMode.BOOK : MatchMode.TYPE;
                    ItemKeys.Spec spec = ItemKeys.of(template, mode);
                    int amount = Math.max(1, rows.getInt("amount_req"));
                    int delivered = Math.max(0, Math.min(amount, rows.getInt("amount_filled")));
                    double price = rows.getDouble("price_per_item");
                    double funds = Money.round(rows.getDouble("remaining_funds"));
                    long expires = rows.getLong("expires_at");
                    String old = String.valueOf(rows.getString("status")).toUpperCase(java.util.Locale.ROOT);
                    List<ItemStack> stash = old.equals("CLAIMED") ? List.of() : stashes.getOrDefault(id, List.of());
                    int stashed = 0;
                    for (ItemStack stack : stash) stashed += stack.getAmount();
                    int collected = Math.max(0, delivered - stashed);

                    OrderStatus status;
                    double escrow = 0.0D;
                    double owed = 0.0D;
                    long closedAt = Math.max(rows.getLong("updated_at"), rows.getLong("claimed_at"));
                    if (closedAt <= 0) closedAt = now;
                    if (old.equals("ACTIVE") || (old.equals("PENDING") && delivered < amount && Money.positive(funds))) {
                        // Open, or expired with its money still held: 2.0's expiry refunds it.
                        status = OrderStatus.ACTIVE;
                        escrow = funds;
                        closedAt = 0L;
                    } else if (delivered >= amount) {
                        status = OrderStatus.FILLED;
                        if (!old.equals("CLAIMED") && Money.positive(funds)) owed = funds;
                    } else {
                        status = expires <= now ? OrderStatus.EXPIRED : OrderStatus.CANCELLED;
                    }

                    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO donut_orders(order_id, owner_uuid, owner_name,"
                            + " server_order, item_data, item_key, material, match_mode, amount, delivered, collected, price, escrow,"
                            + " created_at, expires_at, status, closed_at, updated_at, version) VALUES(?,?,?,0,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)")) {
                        int i = 1;
                        insert.setString(i++, id);
                        insert.setString(i++, rows.getString("buyer_uuid"));
                        insert.setString(i++, String.valueOf(rows.getString("buyer_name")));
                        insert.setString(i++, ItemStackSerializer.serialize(spec.template()));
                        insert.setString(i++, spec.key());
                        insert.setString(i++, spec.template().getType().name());
                        insert.setString(i++, spec.mode().name());
                        insert.setInt(i++, amount);
                        insert.setInt(i++, delivered);
                        insert.setInt(i++, collected);
                        insert.setDouble(i++, price);
                        insert.setDouble(i++, escrow);
                        insert.setLong(i++, rows.getLong("created_at"));
                        insert.setLong(i++, expires);
                        insert.setString(i++, status.name());
                        insert.setLong(i++, closedAt);
                        insert.setLong(i, now);
                        insert.executeUpdate();
                    }
                    try (PreparedStatement item = connection.prepareStatement(
                            "INSERT INTO donut_order_items(order_id, item_data, amount, created_at) VALUES(?,?,?,?)")) {
                        for (ItemStack stack : stash) {
                            item.setString(1, id);
                            item.setString(2, ItemStackSerializer.serialize(stack));
                            item.setInt(3, stack.getAmount());
                            item.setLong(4, now);
                            item.addBatch();
                            stacks++;
                        }
                        item.executeBatch();
                    }
                    if (owed > 0.0D) {
                        try (PreparedStatement payout = connection.prepareStatement(
                                "INSERT INTO donut_order_payouts(player_uuid, amount, reason, created_at) VALUES(?,?,?,?)")) {
                            payout.setString(1, rows.getString("buyer_uuid"));
                            payout.setDouble(2, owed);
                            payout.setString(3, "1.x refund");
                            payout.setLong(4, now);
                            payout.executeUpdate();
                        }
                    }
                    orders++;
                }
            }
            repository.setMeta(connection, "legacy_import", now + ":" + orders);
            return new Result(orders, stacks, skipped);
        });
    }

    private static boolean exists(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM donut_orders WHERE order_id = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private Map<String, List<ItemStack>> stashes(Connection connection) throws SQLException {
        Map<String, List<ItemStack>> stashes = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT order_id, stash_data FROM order_stash")) {
            while (rows.next()) {
                String id = rows.getString("order_id");
                try {
                    stashes.put(id, stashDecoder.decode(rows.getString("stash_data")));
                } catch (IOException | ClassNotFoundException | RuntimeException exception) {
                    repository.logger().warning("The 1.x stash of order " + id + " cannot be read: " + exception.getMessage());
                }
            }
        }
        return stashes;
    }

    /** 1.x stash format: Base64 of a Bukkit object stream holding the slot count and then each slot (or null). */
    static List<ItemStack> decodeStash(String data) throws IOException, ClassNotFoundException {
        List<ItemStack> stacks = new ArrayList<>();
        if (data == null || data.isBlank()) return stacks;
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)))) {
            int length = in.readInt();
            for (int i = 0; i < length; i++) {
                Object value = in.readObject();
                if (value instanceof ItemStack stack && !stack.getType().isAir() && stack.getAmount() > 0) stacks.add(stack);
            }
        }
        return stacks;
    }
}

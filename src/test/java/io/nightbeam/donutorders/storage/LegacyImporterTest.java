package io.nightbeam.donutorders.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import be.seeseemelk.mockbukkit.MockBukkit;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.model.OrderStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

class LegacyImporterTest {

    private SQLiteDataSource dataSource;
    private SqlOrderRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        SqlOrderRepositoryTest.testCodec();
        Path directory = Files.createTempDirectory("donutorders-legacy");
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("donutorders.db"));
        repository = new SqlOrderRepository(dataSource, DatabaseType.SQLITE, Runnable::run, Logger.getLogger("test"));
    }

    @AfterEach
    void tearDown() {
        ItemStackSerializer.useTestCodec(null, null);
        MockBukkit.unmock();
    }

    private static String item(ItemStack item) {
        return ItemStackSerializer.serialize(item);
    }

    /** The real 1.x stash format needs a server to read; the column handling is what is tested here. */
    private static String stash(ItemStack... slots) {
        StringBuilder out = new StringBuilder();
        for (ItemStack slot : slots) out.append(ItemStackSerializer.serialize(slot)).append(';');
        return out.toString();
    }

    private static List<ItemStack> readStash(String data) {
        List<ItemStack> stacks = new java.util.ArrayList<>();
        for (String part : data.split(";")) if (!part.isBlank()) stacks.add(ItemStackSerializer.deserialize(part));
        return stacks;
    }

    @Test
    void importsOnceWithoutRefundingTwice() throws Exception {
        long now = System.currentTimeMillis();
        String buyer = UUID.randomUUID().toString();
        String open = UUID.randomUUID().toString();
        String expired = UUID.randomUUID().toString();
        String cancelled = UUID.randomUUID().toString();
        String filled = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE orders (order_id TEXT PRIMARY KEY, buyer_uuid TEXT, buyer_name TEXT, item_data TEXT,"
                    + " amount_req INTEGER, amount_filled INTEGER, price_per_item REAL, remaining_funds REAL, created_at INTEGER,"
                    + " expires_at INTEGER, status TEXT, claimed_at INTEGER DEFAULT 0, claimed_by TEXT, updated_at INTEGER)");
            statement.executeUpdate("CREATE TABLE order_stash (order_id TEXT PRIMARY KEY, stash_data TEXT)");
            Object[][] rows = {
                    {open, 20, 5, 10.0, 150.0, now + 60_000L, "ACTIVE"},
                    {expired, 10, 2, 5.0, 40.0, now - 60_000L, "PENDING"},
                    {cancelled, 10, 1, 5.0, 0.0, now + 60_000L, "PENDING"},
                    {filled, 4, 4, 7.0, 0.0, now - 60_000L, "CLAIMED"},
            };
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO orders VALUES(?,?,?,?,?,?,?,?,?,?,?,0,NULL,?)")) {
                for (Object[] row : rows) {
                    insert.setString(1, (String) row[0]);
                    insert.setString(2, buyer);
                    insert.setString(3, "Old");
                    insert.setString(4, item(new ItemStack(Material.DIAMOND)));
                    insert.setInt(5, (int) row[1]);
                    insert.setInt(6, (int) row[2]);
                    insert.setDouble(7, (double) row[3]);
                    insert.setDouble(8, (double) row[4]);
                    insert.setLong(9, now - 120_000L);
                    insert.setLong(10, (long) row[5]);
                    insert.setString(11, (String) row[6]);
                    insert.setLong(12, now);
                    insert.executeUpdate();
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO order_stash VALUES(?,?)")) {
                insert.setString(1, open);
                insert.setString(2, stash(new ItemStack(Material.DIAMOND, 5)));
                insert.executeUpdate();
                insert.setString(1, filled);
                insert.setString(2, stash(new ItemStack(Material.DIAMOND, 4)));
                insert.executeUpdate();
            }
        }
        repository.initialize().join();
        LegacyImporter.Result result = new LegacyImporter(repository, LegacyImporterTest::readStash).importOnce().join();
        assertEquals(4, result.orders());
        assertEquals(1, result.stacks());
        assertEquals(0, new LegacyImporter(repository, LegacyImporterTest::readStash).importOnce().join().orders());

        Order openOrder = repository.find(UUID.fromString(open)).join().orElseThrow();
        assertEquals(OrderStatus.ACTIVE, openOrder.status());
        assertEquals(150.0D, openOrder.escrow());
        assertEquals(5, openOrder.waiting());
        Order expiredOrder = repository.find(UUID.fromString(expired)).join().orElseThrow();
        // Expired with money still held: open past its end, so 2.0's expiry refunds the 40 once.
        assertEquals(OrderStatus.ACTIVE, expiredOrder.status());
        assertEquals(40.0D, expiredOrder.escrow());
        assertEquals(OrderStatus.CANCELLED, repository.find(UUID.fromString(cancelled)).join().orElseThrow().status());
        Order filledOrder = repository.find(UUID.fromString(filled)).join().orElseThrow();
        assertEquals(OrderStatus.FILLED, filledOrder.status());
        assertEquals(0, filledOrder.waiting());
        assertEquals(1, repository.itemsOf(UUID.fromString(open)).join().size());
        assertEquals(List.of(), repository.takePayouts(UUID.fromString(buyer)).join());
    }
}

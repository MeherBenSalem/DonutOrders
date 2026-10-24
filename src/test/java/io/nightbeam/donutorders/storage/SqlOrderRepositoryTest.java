package io.nightbeam.donutorders.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import io.nightbeam.donutorders.model.MatchMode;
import io.nightbeam.donutorders.model.Notice;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.model.OrderStatus;
import io.nightbeam.donutorders.model.StoredStack;
import io.nightbeam.donutorders.storage.SqlOrderRepository.DeliveryOutcome;
import io.nightbeam.donutorders.storage.SqlOrderRepository.DeliveryStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

/**
 * The rules the whole plugin relies on, against a real SQLite database, or MySQL/MariaDB when
 * {@code -Dorders.test.mysql.url=jdbc:mysql://host/db} is set (CI; user {@code orders.test.mysql.user}).
 */
class SqlOrderRepositoryTest {

    private Path directory;
    private SqlOrderRepository repository;
    private final UUID owner = UUID.randomUUID();
    private final UUID seller = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        testCodec();
        directory = Files.createTempDirectory("donutorders-test");
        String mysql = System.getProperty("orders.test.mysql.url");
        if (mysql == null) {
            SQLiteDataSource dataSource = new SQLiteDataSource();
            dataSource.setUrl("jdbc:sqlite:" + directory.resolve("orders.db"));
            repository = new SqlOrderRepository(dataSource, DatabaseType.SQLITE, Runnable::run, Logger.getLogger("test"));
        } else {
            com.mysql.cj.jdbc.MysqlDataSource dataSource = new com.mysql.cj.jdbc.MysqlDataSource();
            dataSource.setUrl(mysql);
            dataSource.setUser(System.getProperty("orders.test.mysql.user", "root"));
            dataSource.setPassword(System.getProperty("orders.test.mysql.password", "test"));
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                for (String table : new String[] {"donut_orders", "donut_order_items", "donut_order_deliveries", "donut_order_mail",
                        "donut_order_notices", "donut_order_payouts", "donut_orders_meta"}) {
                    statement.executeUpdate("DROP TABLE IF EXISTS " + table);
                }
            }
            repository = new SqlOrderRepository(dataSource, DatabaseType.MYSQL, Runnable::run, Logger.getLogger("test"));
        }
        repository.initialize().join();
    }

    @AfterEach
    void tearDown() {
        ItemStackSerializer.useTestCodec(null, null);
        MockBukkit.unmock();
    }

    /** "test:MATERIAL:amount": the repository only needs the material and amount back. */
    static void testCodec() {
        ItemStackSerializer.useTestCodec(item -> "test:" + item.getType().name() + ":" + item.getAmount(), data -> {
            String[] parts = data.split(":");
            return new ItemStack(Material.valueOf(parts[1]), Integer.parseInt(parts[2]));
        });
    }


    private Order order(int amount, double price) {
        long now = System.currentTimeMillis();
        Order order = new Order(UUID.randomUUID(), owner, "Kiri", false, new ItemStack(Material.DIAMOND), "DIAMOND", "DIAMOND", MatchMode.TYPE,
                amount, 0, 0, price, io.nightbeam.donutorders.util.Money.times(amount, price), now, now + 86_400_000L,
                OrderStatus.ACTIVE, 0L, now, 0L);
        repository.insert(order).join();
        return order;
    }

    private DeliveryOutcome deliver(Order order, int amount) {
        return repository.deliver(order.id(), List.of(new ItemStack(Material.DIAMOND, amount)), 0.1D, seller, "Seller",
                System.currentTimeMillis()).join();
    }

    @Test
    void deliveriesAreCappedAndTheOverflowComesBack() {
        Order order = order(10, 3.33D);
        DeliveryOutcome first = deliver(order, 4);
        assertEquals(DeliveryStatus.DELIVERED, first.status());
        assertEquals(4, first.accepted());
        assertEquals(13.32D, first.payout());
        assertEquals(1.33D, first.tax());
        assertEquals(19.98D, first.order().escrow());

        DeliveryOutcome second = deliver(order, 64);
        assertEquals(6, second.accepted());
        assertEquals(OrderStatus.FILLED, second.order().status());
        assertEquals(0.0D, second.order().escrow());
        assertEquals(1, second.overflow().size());
        assertEquals(58, second.overflow().get(0).getAmount());

        DeliveryOutcome third = deliver(order, 1);
        assertEquals(DeliveryStatus.CLOSED, third.status());
        assertEquals(1, third.overflow().get(0).getAmount());
    }

    @Test
    void storedItemsAreHandedOutOnce() {
        Order order = order(128, 1.0D);
        deliver(order, 64);
        deliver(order, 32);
        List<StoredStack> stored = repository.itemsOf(order.id()).join();
        assertEquals(2, stored.size());
        List<StoredStack> taken = repository.takeItems(order.id(), stored, System.currentTimeMillis()).join();
        assertEquals(2, taken.size());
        assertTrue(repository.takeItems(order.id(), stored, System.currentTimeMillis()).join().isEmpty());
        Order after = repository.find(order.id()).join().orElseThrow();
        assertEquals(96, after.collected());
        assertEquals(0, after.waiting());
    }

    @Test
    void closingRefundsTheEscrowExactlyOnce() {
        Order order = order(10, 5.0D);
        deliver(order, 3);
        SqlOrderRepository.CloseOutcome first = repository.close(order.id(), OrderStatus.CANCELLED, System.currentTimeMillis()).join();
        assertTrue(first.changed());
        assertEquals(35.0D, first.refund());
        SqlOrderRepository.CloseOutcome second = repository.close(order.id(), OrderStatus.EXPIRED, System.currentTimeMillis()).join();
        assertFalse(second.changed());
        assertEquals(0.0D, second.refund());
        assertEquals(DeliveryStatus.CLOSED, deliver(order, 1).status());
        // Delivered items stay collectable after the order closed.
        Order closed = repository.find(order.id()).join().orElseThrow();
        assertEquals(3, closed.waiting());
        assertTrue(repository.loadLive().join().stream().anyMatch(live -> live.id().equals(order.id())));
    }

    @Test
    void editsOnlyLandOnTheVersionThePlayerSaw() {
        Order order = order(10, 5.0D);
        Order seen = repository.find(order.id()).join().orElseThrow();
        deliver(order, 2);
        assertTrue(repository.edit(order.id(), seen.version(), 20, 6.0D, 108.0D, seen.expiresAt(), System.currentTimeMillis()).join().isEmpty());
        Order now = repository.find(order.id()).join().orElseThrow();
        Order edited = repository.edit(order.id(), now.version(), 20, 6.0D, 108.0D, now.expiresAt(), System.currentTimeMillis()).join().orElseThrow();
        assertEquals(20, edited.amount());
        assertEquals(6.0D, edited.price());
        assertEquals(108.0D, edited.escrow());
        // An amount below what was already delivered is refused.
        assertTrue(repository.edit(order.id(), edited.version(), 1, 6.0D, 0.0D, now.expiresAt(), System.currentTimeMillis()).join().isEmpty());
    }

    @Test
    void serverOrdersKeepNothingAndHoldNoMoney() {
        long now = System.currentTimeMillis();
        Order order = new Order(UUID.randomUUID(), Order.SERVER, "Server", true, new ItemStack(Material.WHEAT), "WHEAT", "WHEAT", MatchMode.TYPE,
                Order.UNLIMITED, 0, 0, 2.0D, 0.0D, now, now + 60_000L, OrderStatus.ACTIVE, 0L, now, 0L);
        repository.insert(order).join();
        DeliveryOutcome outcome = repository.deliver(order.id(), List.of(new ItemStack(Material.WHEAT, 64)), 0.0D, seller, "Seller", now).join();
        assertEquals(64, outcome.accepted());
        assertEquals(128.0D, outcome.payout());
        assertEquals(0, outcome.order().waiting());
        assertTrue(repository.itemsOf(order.id()).join().isEmpty());
        assertEquals(0.0D, repository.close(order.id(), OrderStatus.CANCELLED, now).join().refund());
    }

    @Test
    void expiredOrdersRefuseDeliveries() {
        long now = System.currentTimeMillis();
        Order order = new Order(UUID.randomUUID(), owner, "Kiri", false, new ItemStack(Material.DIAMOND), "DIAMOND", "DIAMOND", MatchMode.TYPE,
                5, 0, 0, 1.0D, 5.0D, now - 10_000L, now - 1_000L, OrderStatus.ACTIVE, 0L, now, 0L);
        repository.insert(order).join();
        assertEquals(DeliveryStatus.EXPIRED, deliver(order, 1).status());
    }

    @Test
    void mailNoticesPayoutsAndInsight() {
        repository.addMail(owner, List.of(new ItemStack(Material.STONE, 5)), "returned", 1L).join();
        List<StoredStack> mail = repository.mailOf(owner).join();
        assertEquals(1, mail.size());
        assertEquals(5, repository.mailCount(owner).join());
        assertEquals(1, repository.takeMail(mail).join().size());
        assertTrue(repository.takeMail(mail).join().isEmpty());

        repository.addNotice(new Notice(0L, owner, Notice.Kind.DELIVERED, Map.of("sellers", "A=B\nC", "amount", "3"), 1L)).join();
        List<Notice> notices = repository.takeNotices(List.of(owner)).join();
        assertEquals("A=B\nC", notices.get(0).values().get("sellers"));
        assertTrue(repository.takeNotices(List.of(owner)).join().isEmpty());

        repository.addPayout(owner, 12.345D, "test", 1L).join();
        assertEquals(12.35D, repository.takePayouts(owner).join().get(0).amount());
        assertTrue(repository.takePayouts(owner).join().isEmpty());

        Order order = order(100, 2.0D);
        deliver(order, 10);
        Order second = order(100, 4.0D);
        deliver(second, 30);
        var insight = repository.insight("DIAMOND", 0L).join();
        assertEquals(2, insight.deliveries());
        assertEquals(40L, insight.items());
        assertEquals(3.5D, insight.average());
        assertEquals(2.0D, insight.min());
        assertEquals(4.0D, insight.max());
        var stats = repository.stats(seller).join();
        assertEquals(40L, stats.itemsDelivered());
        assertEquals(126.0D, stats.earned());
        assertEquals(40L, repository.stats(owner).join().itemsReceived());
    }

    @Test
    void splitsStacksWithoutChangingThem() {
        ItemStack stack = new ItemStack(Material.DIAMOND, 64);
        SqlOrderRepository.Split split = SqlOrderRepository.split(List.of(stack, new ItemStack(Material.DIAMOND, 10)), 70);
        assertEquals(64, stack.getAmount());
        assertEquals(70, split.accepted().stream().mapToInt(ItemStack::getAmount).sum());
        assertEquals(4, split.overflow().stream().mapToInt(ItemStack::getAmount).sum());
    }
}

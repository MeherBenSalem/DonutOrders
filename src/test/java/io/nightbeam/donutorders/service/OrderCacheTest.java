package io.nightbeam.donutorders.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import io.nightbeam.donutorders.model.BrowseRequest;
import io.nightbeam.donutorders.model.MatchMode;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.model.OrderCategory;
import io.nightbeam.donutorders.model.OrderPage;
import io.nightbeam.donutorders.model.OrderSort;
import io.nightbeam.donutorders.model.OrderStatus;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderCacheTest {

    private final OrderCache cache = new OrderCache();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final long now = System.currentTimeMillis();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Order add(UUID owner, String name, Material material, int amount, int delivered, double price, long created) {
        Order order = new Order(UUID.randomUUID(), owner, name, false, new ItemStack(material), material.name(), material.name(), MatchMode.TYPE,
                amount, delivered, 0, price, (amount - delivered) * price, created, now + 3_600_000L, OrderStatus.ACTIVE, 0L, now, 0L);
        cache.apply(order);
        return order;
    }

    @Test
    void browsesWithSortsFiltersSearchAndPages() {
        add(alice, "Alice", Material.DIAMOND, 10, 2, 100.0D, now - 3000);
        Order cheap = add(bob, "Bob", Material.DIAMOND, 10, 9, 50.0D, now - 1000);
        add(bob, "Bob", Material.OAK_LOG, 500, 0, 2.0D, now - 2000);
        add(alice, "Alice", Material.DIAMOND_PICKAXE, 1, 0, 900.0D, now);

        OrderPage byPrice = cache.browse(BrowseRequest.initial(OrderSort.MONEY_PER_ITEM), 45, now, order -> true);
        assertEquals(4, byPrice.total());
        assertEquals(Material.DIAMOND_PICKAXE, byPrice.orders().get(0).template().getType());
        assertEquals(cheap.id(), cache.browse(BrowseRequest.initial(OrderSort.MOST_DELIVERED), 45, now, order -> true).orders().get(0).id());
        assertEquals(2, cache.browse(BrowseRequest.initial(OrderSort.MONEY_PER_ITEM).withSearch("bob"), 45, now, order -> true).total());
        assertEquals(1, cache.browse(BrowseRequest.initial(OrderSort.MONEY_PER_ITEM).withCategory(OrderCategory.TOOLS), 45, now, order -> true).total());
        assertEquals(2, cache.browse(BrowseRequest.initial(OrderSort.MONEY_PER_ITEM).withOwner(alice, "Alice"), 45, now, order -> true).total());
        OrderPage second = cache.browse(BrowseRequest.initial(OrderSort.RECENTLY_LISTED).withPage(2), 3, now, order -> true);
        assertEquals(2, second.page());
        assertEquals(2, second.pages());
        assertEquals(1, second.orders().size());
    }

    @Test
    void bestOrdersComeFirstAndOwnOrdersAreSkipped() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        ItemMatcher matcher = new ItemMatcher(() -> config);
        Order older = add(alice, "Alice", Material.DIAMOND, 10, 0, 100.0D, now - 5000);
        add(alice, "Alice", Material.DIAMOND, 10, 0, 100.0D, now - 1000);
        add(bob, "Bob", Material.DIAMOND, 10, 0, 150.0D, now);
        List<Order> best = cache.bestFor(new ItemStack(Material.DIAMOND), bob, matcher, now);
        assertEquals(2, best.size());
        assertEquals(older.id(), best.get(0).id());
        double[] demand = cache.demand("DIAMOND", now);
        assertEquals(150.0D, demand[0]);
        assertEquals(30.0D, demand[1]);
    }

    @Test
    void olderSnapshotsNeverReplaceNewerOnes() {
        Order order = add(alice, "Alice", Material.DIAMOND, 10, 0, 1.0D, now);
        Order newer = order.withProgress(4, 6.0D, OrderStatus.ACTIVE, 0L, now);
        cache.apply(newer);
        cache.apply(order);
        assertEquals(4, cache.get(order.id()).orElseThrow().delivered());
        Order closed = newer.closed(OrderStatus.CANCELLED, now).withCollected(4, now);
        cache.apply(closed);
        assertTrue(cache.get(order.id()).isEmpty());
        assertTrue(cache.find(order.shortId()).isEmpty());
    }
}

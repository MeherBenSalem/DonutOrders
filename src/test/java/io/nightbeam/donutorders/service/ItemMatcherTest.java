package io.nightbeam.donutorders.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import io.nightbeam.donutorders.model.MatchMode;
import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.model.OrderStatus;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ItemMatcherTest {

    private YamlConfiguration config;
    private ItemMatcher matcher;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        config = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        matcher = new ItemMatcher(() -> config);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    static Order order(ItemKeys.Spec spec, int amount) {
        long now = System.currentTimeMillis();
        return new Order(UUID.randomUUID(), UUID.randomUUID(), "Kiri", false, spec.template(), spec.key(), spec.template().getType().name(),
                spec.mode(), amount, 0, 0, 1.0D, amount, now, now + 60_000L, OrderStatus.ACTIVE, 0L, now, 0L);
    }

    @Test
    void plainOrdersTakePlainItems() {
        Order order = order(ItemKeys.material(Material.DIAMOND_SWORD), 5);
        assertTrue(matcher.matches(order, new ItemStack(Material.DIAMOND_SWORD)));
        assertFalse(matcher.matches(order, new ItemStack(Material.IRON_SWORD)));

        ItemStack renamed = new ItemStack(Material.DIAMOND_SWORD);
        renamed.editMeta(meta -> meta.displayName(Component.text("Excalibur")));
        assertFalse(matcher.matches(order, renamed));
        config.set("matching.accept-renamed", true);
        assertTrue(matcher.matches(order, renamed));

        ItemStack worn = new ItemStack(Material.DIAMOND_SWORD);
        worn.editMeta(meta -> ((Damageable) meta).setDamage(100));
        assertFalse(matcher.matches(order, worn));
        config.set("matching.accept-damaged", true);
        assertTrue(matcher.matches(order, worn));

        ItemStack lore = new ItemStack(Material.DIAMOND_SWORD);
        lore.editMeta(meta -> meta.lore(List.of(Component.text("Sells for $5"))));
        assertFalse(matcher.matches(order, lore));
        config.set("matching.ignore-lore", true);
        assertTrue(matcher.matches(order, lore));
    }

    @Test
    void exactOrdersCompareTheWholeItem() {
        ItemStack named = new ItemStack(Material.PAPER);
        named.editMeta(meta -> meta.displayName(Component.text("Crate key")));
        Order order = order(ItemKeys.exact(named), 3);
        assertEquals(MatchMode.EXACT, order.mode());
        ItemStack stack = named.clone();
        stack.setAmount(16);
        assertTrue(matcher.matches(order, stack));
        assertFalse(matcher.matches(order, new ItemStack(Material.PAPER)));
    }

    @Test
    void extractionTakesWhatIsWantedAndReturnsTheRest() {
        Order order = order(ItemKeys.material(Material.DIAMOND), 70);
        List<ItemStack> offered = List.of(new ItemStack(Material.DIAMOND, 64), new ItemStack(Material.DIRT, 5), new ItemStack(Material.DIAMOND, 64));
        assertEquals(128, matcher.count(order, offered));
        ItemMatcher.Extraction extraction = matcher.extract(order, offered, 70);
        assertEquals(70, extraction.units());
        assertEquals(63, extraction.returned().stream().mapToInt(ItemStack::getAmount).sum());
        assertEquals(64, offered.get(0).getAmount());
    }

    @Test
    void heldItemsBecomeTheRightKindOfOrder() {
        assertEquals(MatchMode.TYPE, ItemKeys.fromHeld(new ItemStack(Material.OAK_LOG, 12)).mode());
        assertEquals("OAK_LOG", ItemKeys.fromHeld(new ItemStack(Material.OAK_LOG, 12)).key());
        ItemStack worn = new ItemStack(Material.IRON_PICKAXE);
        worn.editMeta(meta -> ((Damageable) meta).setDamage(20));
        assertEquals(MatchMode.TYPE, ItemKeys.fromHeld(worn).mode());
        assertEquals(MatchMode.EXACT, ItemKeys.fromHeld(new ItemStack(Material.POTION)).mode());
        ItemStack named = new ItemStack(Material.STICK);
        named.editMeta(meta -> meta.displayName(Component.text("Wand")));
        assertEquals(MatchMode.EXACT, ItemKeys.fromHeld(named).mode());
        assertEquals(1, ItemKeys.fromHeld(new ItemStack(Material.OAK_LOG, 12)).template().getAmount());
    }
}

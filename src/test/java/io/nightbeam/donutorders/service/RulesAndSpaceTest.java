package io.nightbeam.donutorders.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import io.nightbeam.donutorders.util.InventorySpace;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RulesAndSpaceTest {

    private ServerMock server;
    private YamlConfiguration config;
    private OrderRules rules;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        config = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        rules = new OrderRules(() -> config);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void blacklistWildcardsAndWhitelist() {
        PlayerMock player = server.addPlayer();
        assertTrue(rules.checkItem(player, new ItemStack(Material.ZOMBIE_SPAWN_EGG)).isPresent());
        assertTrue(rules.checkItem(player, new ItemStack(Material.BEDROCK)).isPresent());
        assertFalse(rules.checkItem(player, new ItemStack(Material.DIAMOND)).isPresent());
        config.set("whitelist.enabled", true);
        config.set("whitelist.materials", List.of("DIAMOND"));
        assertFalse(rules.checkItem(player, new ItemStack(Material.DIAMOND)).isPresent());
        assertTrue(rules.checkItem(player, new ItemStack(Material.EMERALD)).isPresent());
        assertTrue(OrderRules.matchesName("SHULKER_*", "SHULKER_SHELL"));
        assertTrue(OrderRules.matchesName("*SWORD*", "DIAMOND_SWORD"));
        assertFalse(OrderRules.matchesName("*_SPAWN_EGG", "EGG"));
    }

    @Test
    void limitsDurationsAndPriceWindows() {
        PlayerMock player = server.addPlayer();
        assertEquals(18, rules.orderLimit(player));
        player.addAttachment(MockBukkit.createMockPlugin(), "donutorders.limit.25", true);
        assertEquals(25, rules.orderLimit(player));
        player.addAttachment(MockBukkit.createMockPlugin(), "donutorders.limit.vip", true);
        assertEquals(45, rules.orderLimit(player));
        assertEquals(7L * 86_400_000L, rules.defaultDuration());
        assertEquals(List.of(3_600_000L, 12L * 3_600_000L, 86_400_000L, 3L * 86_400_000L, 7L * 86_400_000L, 14L * 86_400_000L,
                30L * 86_400_000L), rules.durationOptions(player));
        config.set("orders.price-limits.DIAMOND.min", 50);
        assertEquals(50.0D, rules.minPrice("DIAMOND", Material.DIAMOND));
        assertEquals(0.01D, rules.minPrice("EMERALD", Material.EMERALD));
    }

    @Test
    void wholeStacksOnlyWhenTheyFit() {
        ItemStack[] storage = new ItemStack[36];
        for (int i = 0; i < 35; i++) storage[i] = new ItemStack(Material.STONE, 64);
        storage[35] = new ItemStack(Material.DIAMOND, 60);
        List<Integer> fit = InventorySpace.plan(storage, List.of(new ItemStack(Material.DIAMOND, 4), new ItemStack(Material.DIAMOND, 1),
                new ItemStack(Material.DIRT, 1)));
        assertEquals(List.of(0), fit);
    }
}

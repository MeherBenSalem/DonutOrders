package io.nightbeam.donutorders.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class OrderCategoryTest {

    @Test
    void everyCategoryLoadsAndHasAnIcon() {
        for (OrderCategory category : OrderCategory.values()) {
            assertTrue(category.icon().isItem(), category.name());
            assertEquals(category, OrderCategory.parse(category.key()).orElseThrow());
        }
    }

    @Test
    void sortsMaterialsIntoCategories() {
        assertTrue(OrderCategory.TOOLS.matches(Material.DIAMOND_PICKAXE));
        assertTrue(OrderCategory.TOOLS.matches(Material.IRON_AXE));
        assertFalse(OrderCategory.TOOLS.matches(Material.DIAMOND_SWORD));
        assertTrue(OrderCategory.COMBAT.matches(Material.DIAMOND_SWORD));
        assertTrue(OrderCategory.COMBAT.matches(Material.NETHERITE_CHESTPLATE));
        assertTrue(OrderCategory.FOOD.matches(Material.GOLDEN_CARROT));
        assertFalse(OrderCategory.FOOD.matches(Material.ROTTEN_FLESH));
        assertTrue(OrderCategory.BLOCKS.matches(Material.STONE));
        assertFalse(OrderCategory.BLOCKS.matches(Material.ENDER_CHEST));
        assertTrue(OrderCategory.UTILITIES.matches(Material.ENDER_CHEST));
        assertTrue(OrderCategory.UTILITIES.matches(Material.RED_SHULKER_BOX));
        assertTrue(OrderCategory.POTIONS.matches(Material.SPLASH_POTION));
        assertTrue(OrderCategory.BOOKS.matches(Material.ENCHANTED_BOOK));
        assertTrue(OrderCategory.INGREDIENTS.matches(Material.BLAZE_ROD));
        assertTrue(OrderCategory.INGREDIENTS.matches(Material.LIME_DYE));
        assertTrue(OrderCategory.SPAWNERS.matches(Material.SPAWNER));
        assertTrue(OrderCategory.SPAWNERS.matches(Material.ZOMBIE_SPAWN_EGG));
        assertFalse(OrderCategory.BLOCKS.matches(Material.SPAWNER));
    }

    @Test
    void cyclesBothWays() {
        assertEquals(OrderCategory.BLOCKS, OrderCategory.ALL.next());
        assertEquals(OrderCategory.SPAWNERS, OrderCategory.ALL.previous());
        assertEquals(OrderSort.RECENTLY_LISTED, OrderSort.MONEY_PER_ITEM.next());
        assertEquals(OrderSort.MONEY_PER_ITEM, OrderSort.ENDING_SOON.next());
    }

    @Test
    void readsOldAndUnknownStatuses() {
        assertEquals(OrderStatus.FILLED, OrderStatus.parse("COMPLETED"));
        assertEquals(OrderStatus.CANCELLED, OrderStatus.parse("PENDING"));
        assertEquals(OrderStatus.ACTIVE, OrderStatus.parse("active"));
        assertEquals(MatchMode.TYPE, MatchMode.parse("weird"));
    }
}

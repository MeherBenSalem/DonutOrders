package io.nightbeam.donutorders.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import be.seeseemelk.mockbukkit.MockBukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ItemNamesTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void plainNames() {
        assertEquals("Golden Carrot", ItemNames.plain(new ItemStack(Material.GOLDEN_CARROT)));
        assertEquals("Enchanted Book", ItemNames.plain(new ItemStack(Material.ENCHANTED_BOOK)));
    }

    @Test
    void booksNameTheirEnchantment() {
        assertEquals("Enchanted Book (Mending)", ItemNames.plain(Enchants.book(Enchantment.MENDING, 1)));
        assertEquals("Enchanted Book (Sharpness V)", ItemNames.plain(Enchants.book(Enchantment.DAMAGE_ALL, 5)));
        assertEquals("<lang:item.minecraft.enchanted_book> (<lang:enchantment.minecraft.mending>)",
                MessageUtil.escape(ItemNames.fragment(Enchants.book(Enchantment.MENDING, 1))));
    }
}

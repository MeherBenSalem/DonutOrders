package com.donutorders.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class EnchantOrderUtilsTest {

    @Test
    void needsEnchantPickerOnlyForEnchantedBook() {
        assertTrue(EnchantOrderUtils.needsEnchantPicker(Material.ENCHANTED_BOOK));
        assertFalse(EnchantOrderUtils.needsEnchantPicker(Material.DIAMOND));
    }

    @Test
    void toRomanUsesStandardNumeralsForLowLevels() {
        assertEquals("I", EnchantOrderUtils.toRoman(1));
        assertEquals("V", EnchantOrderUtils.toRoman(5));
        assertEquals("X", EnchantOrderUtils.toRoman(10));
    }

    @Test
    void toRomanFallsBackToDecimalAboveTen() {
        assertEquals("11", EnchantOrderUtils.toRoman(11));
    }
}

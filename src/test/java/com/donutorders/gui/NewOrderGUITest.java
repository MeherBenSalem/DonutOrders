package com.donutorders.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

class NewOrderGUITest {

    @Test
    void filterMaterialsReturnsAllWhenQueryBlank() {
        List<Material> materials = Arrays.asList(Material.DIAMOND, Material.IRON_INGOT);
        assertEquals(materials, NewOrderGUI.filterMaterials(materials, null));
        assertEquals(materials, NewOrderGUI.filterMaterials(materials, "   "));
    }

    @Test
    void filterMaterialsMatchesEnumNameCaseInsensitive() {
        List<Material> materials = Arrays.asList(
                Material.DIAMOND,
                Material.IRON_INGOT,
                Material.OAK_LOG);
        List<Material> filtered = NewOrderGUI.filterMaterials(materials, "iron");
        assertEquals(List.of(Material.IRON_INGOT), filtered);
    }

    @Test
    void filterMaterialsMatchesPrettyName() {
        List<Material> materials = List.of(Material.ENCHANTED_BOOK);
        List<Material> filtered = NewOrderGUI.filterMaterials(materials, "enchanted");
        assertEquals(materials, filtered);
    }

    @Test
    void filterMaterialsReturnsEmptyWhenNoMatch() {
        List<Material> materials = List.of(Material.DIAMOND);
        assertTrue(NewOrderGUI.filterMaterials(materials, "emerald").isEmpty());
    }
}

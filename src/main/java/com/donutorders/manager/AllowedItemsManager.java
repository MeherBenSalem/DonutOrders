package com.donutorders.manager;

import com.donutorders.DonutOrders;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Loads and validates the allowed order materials from {@code items.yml}.
 */
public class AllowedItemsManager {

    private final DonutOrders plugin;
    private List<Material> allowedMaterials = List.of();

    public AllowedItemsManager(DonutOrders plugin) {
        this.plugin = plugin;
    }

    /** Reloads allowed materials from disk, migrating legacy config if needed. */
    public void reload() {
        File itemsFile = new File(plugin.getDataFolder(), "items.yml");
        if (!itemsFile.exists()) {
            List<String> legacy = plugin.getConfig().getStringList("allowed-materials");
            if (!legacy.isEmpty()) {
                FileConfiguration itemsConfig = new YamlConfiguration();
                itemsConfig.set("allowed-items", new ArrayList<>(legacy));
                try {
                    itemsConfig.save(itemsFile);
                    plugin.getLogger().info(
                            "[DonutOrders] Created items.yml from legacy config.yml allowed-materials.");
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "[DonutOrders] Failed to create items.yml from legacy config.", e);
                    plugin.saveResource("items.yml", false);
                }
            } else {
                plugin.saveResource("items.yml", false);
            }
        }

        FileConfiguration itemsConfig = YamlConfiguration.loadConfiguration(itemsFile);
        List<String> names = itemsConfig.getStringList("allowed-items");

        if (names.isEmpty()) {
            migrateFromLegacyConfig(itemsConfig, itemsFile);
            names = itemsConfig.getStringList("allowed-items");
        }

        allowedMaterials = Collections.unmodifiableList(parseMaterials(names));
        plugin.getLogger().info("[DonutOrders] Loaded " + allowedMaterials.size()
                + " allowed order material(s) from items.yml.");
    }

    public List<Material> getAllowedMaterials() {
        return allowedMaterials;
    }

    public boolean isAllowed(Material material) {
        return material != null && allowedMaterials.contains(material);
    }

    private void migrateFromLegacyConfig(FileConfiguration itemsConfig, File itemsFile) {
        List<String> legacy = plugin.getConfig().getStringList("allowed-materials");
        if (legacy.isEmpty()) {
            return;
        }

        itemsConfig.set("allowed-items", new ArrayList<>(legacy));
        try {
            itemsConfig.save(itemsFile);
            plugin.getLogger().info("[DonutOrders] Migrated allowed-materials from config.yml to items.yml.");
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "[DonutOrders] Failed to write migrated items.yml.", e);
        }
    }

    private List<Material> parseMaterials(List<String> names) {
        Set<Material> unique = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                unique.add(Material.valueOf(name.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[DonutOrders] Invalid material in items.yml: " + name);
            }
        }
        return new ArrayList<>(unique);
    }
}

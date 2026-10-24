package io.nightbeam.donutorders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigMigratorTest {

    @Test
    void carriesOneXSettingsOver() throws Exception {
        YamlConfiguration old = new YamlConfiguration();
        old.loadFromString("""
                update-check:
                  enabled: false
                  notify-ops-on-join: false
                orders:
                  default-limit: 3
                  default-expiry-days: 14
                  tax-percent: 2.5
                  broadcast-on-create: false
                database:
                  type: mysql
                  filename: old.db
                  mysql:
                    host: db.example
                    port: 3307
                    database: shop
                    username: shop
                    password: secret
                    pool-size: 4
                  sync-interval-seconds: 7
                rank-limits:
                  default: 2
                  vip: 5
                """);
        YamlConfiguration items = new YamlConfiguration();
        items.set("allowed-items", List.of("DIAMOND", "EMERALD"));
        YamlConfiguration fresh = YamlConfiguration.loadConfiguration(
                new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));

        List<String> notes = ConfigMigrator.carryOver(old, items, fresh);

        assertEquals(3, fresh.getInt("limits.default"));
        assertEquals(5, fresh.getInt("limits.permissions.group.vip"));
        assertEquals("14d", fresh.getString("durations.default"));
        assertTrue(fresh.getBoolean("economy.seller-tax.enabled"));
        assertEquals(2.5D, fresh.getDouble("economy.seller-tax.percent"));
        assertEquals(false, fresh.getBoolean("broadcast.enabled"));
        assertEquals("MYSQL", fresh.getString("storage.type"));
        assertEquals("old.db", fresh.getString("storage.sqlite.file"));
        assertEquals("db.example", fresh.getString("storage.mysql.host"));
        assertEquals(3307, fresh.getInt("storage.mysql.port"));
        assertEquals("secret", fresh.getString("storage.mysql.password"));
        assertEquals(7, fresh.getInt("sync.poll-seconds"));
        assertEquals(false, fresh.getBoolean("update-checker.enabled"));
        assertTrue(fresh.getBoolean("whitelist.enabled"));
        assertEquals(List.of("DIAMOND", "EMERALD"), fresh.getStringList("whitelist.materials"));
        assertEquals(1, notes.size());
    }
}

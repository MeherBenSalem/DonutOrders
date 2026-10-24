package io.nightbeam.donutorders;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Carries a 1.x setup over to 2.0 once: the old config.yml, messages.yml and items.yml move to
 * {@code legacy-1.x/}, and their settings are written into the new config.yml.
 */
final class ConfigMigrator {

    private ConfigMigrator() {
    }

    /** Returns lines to log, or an empty list when there was nothing to migrate. */
    static List<String> migrate(DonutOrdersPlugin plugin) {
        File folder = plugin.getDataFolder();
        File config = new File(folder, "config.yml");
        if (!config.isFile()) return List.of();
        YamlConfiguration old = YamlConfiguration.loadConfiguration(config);
        if (old.contains("config-version")) return List.of();
        List<String> notes = new ArrayList<>();
        File legacy = new File(folder, "legacy-1.x");
        legacy.mkdirs();
        boolean hadMessages = new File(folder, "messages.yml").isFile();
        try {
            for (String name : new String[] {"config.yml", "messages.yml", "items.yml"}) {
                File file = new File(folder, name);
                if (file.isFile()) Files.move(file.toPath(), new File(legacy, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not move the 1.x configuration to legacy-1.x/: " + exception.getMessage());
            return List.of();
        }
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        YamlConfiguration items = YamlConfiguration.loadConfiguration(new File(legacy, "items.yml"));
        notes.addAll(carryOver(old, items, plugin.getConfig()));
        plugin.saveConfig();
        notes.add(0, "Moved the 1.x configuration to legacy-1.x/ and carried its settings into the new config.yml (limits, rank"
                + " limits, expiry, tax, broadcast, database, update check).");
        if (hadMessages) notes.add("Messages were reset to the 2.0 defaults; your 1.x texts are in legacy-1.x/messages.yml.");
        return notes;
    }

    /** Writes the 1.x settings into the new configuration. Returns notes for the console. */
    static List<String> carryOver(Configuration old, Configuration items,
                                  Configuration fresh) {
        List<String> notes = new ArrayList<>();

        int defaultLimit = old.getInt("orders.default-limit", old.getInt("orders.max-per-player", 2));
        ConfigurationSection ranks = old.getConfigurationSection("rank-limits");
        if (ranks != null) {
            for (String rank : ranks.getKeys(false)) {
                if (rank.equalsIgnoreCase("default")) {
                    defaultLimit = Math.max(defaultLimit, ranks.getInt(rank, defaultLimit));
                } else {
                    fresh.set("limits.permissions.group." + rank.toLowerCase(Locale.ROOT), ranks.getInt(rank));
                }
            }
        }
        fresh.set("limits.default", defaultLimit);
        int days = old.getInt("orders.default-expiry-days", 7);
        if (days > 0) {
            fresh.set("durations.default", days + "d");
            if (days > 30) fresh.set("durations.max", days + "d");
        }
        double tax = old.getDouble("orders.tax-percent", 0.0D);
        if (tax > 0) {
            fresh.set("economy.seller-tax.enabled", true);
            fresh.set("economy.seller-tax.percent", tax);
        }
        fresh.set("broadcast.enabled", old.getBoolean("orders.broadcast-on-create", true));
        String type = old.getString("database.type", "sqlite");
        fresh.set("storage.type", type.equalsIgnoreCase("mysql") ? "MYSQL" : "SQLITE");
        fresh.set("storage.sqlite.file", old.getString("database.filename", "donutorders.db"));
        for (String key : new String[] {"host", "port", "database", "username", "password", "pool-size"}) {
            if (old.contains("database.mysql." + key)) fresh.set("storage.mysql." + key, old.get("database.mysql." + key));
        }
        if (old.contains("database.sync-interval-seconds")) fresh.set("sync.poll-seconds", old.getInt("database.sync-interval-seconds"));
        fresh.set("update-checker.enabled", old.getBoolean("update-check.enabled", true));
        fresh.set("update-checker.notify-admins", old.getBoolean("update-check.notify-ops-on-join", true));
        List<String> allowed = items.getStringList("allowed-items");
        if (!allowed.isEmpty()) {
            fresh.set("whitelist.enabled", true);
            fresh.set("whitelist.materials", allowed);
            notes.add("Your 1.x item list (" + allowed.size() + " items) is kept as whitelist.materials. Set whitelist.enabled to false"
                    + " in config.yml to allow every item.");
        }
        return notes;
    }
}

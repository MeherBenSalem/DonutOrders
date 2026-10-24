package io.nightbeam.donutorders.service;

import io.nightbeam.donutorders.model.Order;
import io.nightbeam.donutorders.util.Durations;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;

/**
 * What may be ordered, by whom, where, for how much and for how long: the blacklist and optional whitelist,
 * disabled worlds and game modes, price, amount and total caps (per material too), the creation cooldown, the
 * active-order limit and the durations a player may pick.
 */
public final class OrderRules {

    /** Why something was refused: a messages.yml key, its fallback and placeholders. */
    public record Refusal(String key, String fallback, String... placeholders) {
    }

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Supplier<FileConfiguration> config;
    private final Map<UUID, Long> lastCreated = new ConcurrentHashMap<>();
    private volatile boolean frozen;

    public OrderRules(Supplier<FileConfiguration> config) {
        this.config = config;
    }

    // ------------------------------------------------------------------ market switch

    /** Staff can freeze the market: nobody creates or delivers until it is unfrozen. */
    public boolean frozen() {
        return frozen || config.get().getBoolean("market.frozen", false);
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    // ------------------------------------------------------------------ where and how

    /** World and game mode, checked for creating and delivering. */
    public Optional<Refusal> checkPlace(Player player) {
        if (player.hasPermission("donutorders.bypass.restrictions")) return Optional.empty();
        FileConfiguration cfg = config.get();
        if (frozen() && !player.hasPermission("donutorders.admin")) {
            return Optional.of(new Refusal("restrictions.frozen", "&cThe order market is closed for now."));
        }
        String world = player.getWorld().getName();
        if (cfg.getStringList("restrictions.disabled-worlds").stream().anyMatch(name -> name.equalsIgnoreCase(world))) {
            return Optional.of(new Refusal("restrictions.world", "&cOrders cannot be used in this world."));
        }
        GameMode mode = player.getGameMode();
        if (cfg.getStringList("restrictions.blocked-gamemodes").stream().anyMatch(name -> name.equalsIgnoreCase(mode.name()))) {
            return Optional.of(new Refusal("restrictions.gamemode", "&cOrders cannot be used in %gamemode% mode.",
                    "gamemode", mode.name().toLowerCase(Locale.ROOT)));
        }
        return Optional.empty();
    }

    public Optional<Refusal> checkCooldown(Player player) {
        long cooldown = Math.max(0L, config.get().getLong("orders.cooldown-seconds", 0L)) * 1000L;
        Long last = lastCreated.get(player.getUniqueId());
        if (cooldown <= 0 || last == null || player.hasPermission("donutorders.bypass.cooldown")) return Optional.empty();
        long left = last + cooldown - System.currentTimeMillis();
        if (left <= 0) return Optional.empty();
        return Optional.of(new Refusal("restrictions.cooldown", "&cWait %seconds%s before creating another order.",
                "seconds", String.valueOf((left + 999) / 1000)));
    }

    public void recordCreated(UUID player) {
        lastCreated.put(player, System.currentTimeMillis());
    }

    public void forget(UUID player) {
        lastCreated.remove(player);
    }

    // ------------------------------------------------------------------ what

    /** Blacklist and whitelist for the item an order asks for. */
    public Optional<Refusal> checkItem(Player player, ItemStack item) {
        if (item == null || item.getType().isAir() || !item.getType().isItem()) return Optional.of(blocked());
        if (player != null && player.hasPermission("donutorders.bypass.blacklist")) return Optional.empty();
        FileConfiguration cfg = config.get();
        String material = item.getType().name();
        if (cfg.getBoolean("whitelist.enabled", false)
                && cfg.getStringList("whitelist.materials").stream().noneMatch(name -> name.equalsIgnoreCase(material))) {
            return Optional.of(blocked());
        }
        if (cfg.getStringList("blacklist.materials").stream().anyMatch(name -> matchesName(name, material))) return Optional.of(blocked());
        ItemMeta meta;
        try {
            meta = item.hasItemMeta() ? item.getItemMeta() : null;
        } catch (RuntimeException | LinkageError unreadable) {
            return Optional.of(blocked());
        }
        if (meta == null) return Optional.empty();
        if (meta.hasDisplayName() && contains(PLAIN.serialize(meta.displayName()), cfg.getStringList("blacklist.name-contains"))) {
            return Optional.of(blocked());
        }
        List<Component> lore = meta.hasLore() ? meta.lore() : null;
        if (lore != null) {
            List<String> needles = cfg.getStringList("blacklist.lore-contains");
            for (Component line : lore) if (contains(PLAIN.serialize(line), needles)) return Optional.of(blocked());
        }
        List<Integer> models = cfg.getIntegerList("blacklist.custom-model-data");
        try {
            if (!models.isEmpty() && meta.hasCustomModelData() && models.contains(meta.getCustomModelData())) return Optional.of(blocked());
        } catch (RuntimeException | LinkageError ignored) {
            // Custom model data became a component in 1.21.4.
        }
        for (String key : cfg.getStringList("blacklist.plugin-data")) {
            NamespacedKey namespaced = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
            if (namespaced != null && meta.getPersistentDataContainer().getKeys().contains(namespaced)) return Optional.of(blocked());
        }
        return Optional.empty();
    }

    /** {@code *_SPAWN_EGG} style wildcards are allowed in material lists. */
    static boolean matchesName(String pattern, String material) {
        if (pattern == null || pattern.isBlank()) return false;
        String upper = pattern.trim().toUpperCase(Locale.ROOT);
        if (upper.startsWith("*") && upper.endsWith("*") && upper.length() > 2) return material.contains(upper.substring(1, upper.length() - 1));
        if (upper.startsWith("*")) return material.endsWith(upper.substring(1));
        if (upper.endsWith("*")) return material.startsWith(upper.substring(0, upper.length() - 1));
        return upper.equals(material);
    }

    // ------------------------------------------------------------------ how much

    public double minPrice(String itemKey, Material material) {
        return limit(itemKey, material, "min", Math.max(0.01D, config.get().getDouble("orders.min-price", 0.01D)));
    }

    public double maxPrice(String itemKey, Material material) {
        return limit(itemKey, material, "max", config.get().getDouble("orders.max-price", 1.0E9D));
    }

    public int maxAmount(Player player) {
        int max = Math.max(1, config.get().getInt("orders.max-amount", 100_000));
        return player != null && player.hasPermission("donutorders.bypass.amount") ? Order.UNLIMITED - 1 : max;
    }

    public double maxTotal() {
        double max = config.get().getDouble("orders.max-total", 1.0E12D);
        return Double.isFinite(max) && max > 0 ? max : 1.0E12D;
    }

    /** Per-item limits: by full key (books, exact items) first, then by material name. */
    private double limit(String itemKey, Material material, String bound, double global) {
        ConfigurationSection limits = config.get().getConfigurationSection("orders.price-limits");
        if (limits == null) return global;
        for (String candidate : new String[] {itemKey, material.name(), material.name().toLowerCase(Locale.ROOT)}) {
            if (candidate == null) continue;
            // Keys with dots or colons cannot be YAML paths; look them up among the section's own keys.
            for (String key : limits.getKeys(false)) {
                if (!key.equalsIgnoreCase(candidate)) continue;
                ConfigurationSection entry = limits.getConfigurationSection(key);
                if (entry != null && entry.contains(bound)) {
                    double value = entry.getDouble(bound, global);
                    return Double.isFinite(value) && value > 0 ? value : global;
                }
            }
        }
        return global;
    }

    // ------------------------------------------------------------------ how many

    /** The most open orders a player may have; -1 is unlimited. */
    public int orderLimit(Player player) {
        FileConfiguration cfg = config.get();
        if (player.hasPermission("donutorders.limit.unlimited")) return -1;
        int limit = cfg.getInt("limits.default", 18);
        ConfigurationSection tiers = cfg.getConfigurationSection("limits.permissions");
        if (tiers != null) {
            for (String permission : tiers.getKeys(true)) {
                if (tiers.isConfigurationSection(permission)) continue;
                if (player.hasPermission(permission)) {
                    int value = tiers.getInt(permission, limit);
                    if (value < 0) return -1;
                    limit = Math.max(limit, value);
                }
            }
        }
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String permission = info.getPermission().toLowerCase(Locale.ROOT);
            if (!permission.startsWith("donutorders.limit.")) continue;
            try {
                limit = Math.max(limit, Integer.parseInt(permission.substring("donutorders.limit.".length())));
            } catch (NumberFormatException notNumber) {
                // donutorders.limit.unlimited is handled above.
            }
        }
        return limit;
    }

    // ------------------------------------------------------------------ how long

    public long defaultDuration() {
        return Durations.parse(config.get().getString("durations.default", "7d"), 7L * 86_400_000L);
    }

    /** The longest duration this player may pick. */
    public long maxDuration(Player player) {
        FileConfiguration cfg = config.get();
        long max = Durations.parse(cfg.getString("durations.max", "30d"), 30L * 86_400_000L);
        ConfigurationSection tiers = cfg.getConfigurationSection("durations.permissions");
        if (player != null && tiers != null) {
            for (String permission : tiers.getKeys(true)) {
                if (tiers.isConfigurationSection(permission) || !player.hasPermission(permission)) continue;
                max = Math.max(max, Durations.parse(tiers.getString(permission, ""), 0L));
            }
        }
        return max;
    }

    public long minDuration() {
        return Durations.parse(config.get().getString("durations.min", "1h"), 3_600_000L);
    }

    /** The picker's choices this player may use, shortest first. */
    public List<Long> durationOptions(Player player) {
        long max = maxDuration(player);
        List<Long> options = new ArrayList<>();
        for (String option : config.get().getStringList("durations.options")) {
            long millis = Durations.parse(option, -1L);
            if (millis > 0 && millis <= max && !options.contains(millis)) options.add(millis);
        }
        if (options.isEmpty()) options.add(Math.min(max, defaultDuration()));
        options.sort(Long::compare);
        return options;
    }

    public long clampDuration(Player player, long millis) {
        return Math.max(minDuration(), Math.min(maxDuration(player), millis));
    }

    // ------------------------------------------------------------------ helpers

    private static Refusal blocked() {
        return new Refusal("restrictions.blacklisted", "&cThat item cannot be ordered.");
    }

    private static boolean contains(String text, List<String> needles) {
        if (text == null || needles.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && lower.contains(needle.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}

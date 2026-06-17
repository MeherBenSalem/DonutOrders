package com.donutorders.manager;

import com.donutorders.DonutOrders;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves and caches per-player maximum active order limits.
 *
 * <p>Resolution priority:
 * <ol>
 *   <li>Highest granted {@code donutorders.limit.&lt;n&gt;} permission.</li>
 *   <li>Highest matching {@code rank-limits} entry via {@code group.&lt;rank&gt;}.</li>
 *   <li>{@code orders.default-limit}, legacy {@code orders.max-per-player}, or
 *       {@code rank-limits.default}.</li>
 * </ol>
 */
public class OrderLimitManager {

    private static final String LIMIT_PREFIX = "donutorders.limit.";
    private static final Pattern LIMIT_PATTERN =
            Pattern.compile("donutorders\\.limit\\.(\\d+)");

    private final Map<UUID, ResolvedLimit> cache = new ConcurrentHashMap<>();

    /**
     * Returns the cached or freshly resolved limit for an online player.
     */
    public ResolvedLimit getLimit(Player player) {
        return cache.computeIfAbsent(player.getUniqueId(),
                uuid -> resolveLimit(player));
    }

    /**
     * Returns the numeric limit for an online player.
     */
    public int getLimitValue(Player player) {
        return getLimit(player).limit();
    }

    /**
     * Resolves a limit without using the cache (for offline inspection).
     */
    public ResolvedLimit resolveLimit(Permissible permissible) {
        return resolveFromPermissions(collectEffectivePermissions(permissible),
                permissible::hasPermission);
    }

    /**
     * Resolves a limit from a permission map (e.g. LuckPerms offline user data).
     */
    public ResolvedLimit resolveFromPermissions(Map<String, Boolean> permissions,
                                                java.util.function.Predicate<String> hasPermission) {
        int highestPerm = -1;
        String bestPerm = null;

        for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) {
                continue;
            }
            String permission = entry.getKey();
            if (!permission.startsWith(LIMIT_PREFIX)) {
                continue;
            }
            Matcher matcher = LIMIT_PATTERN.matcher(permission);
            if (!matcher.matches()) {
                continue;
            }
            int value = Integer.parseInt(matcher.group(1));
            if (value > highestPerm) {
                highestPerm = value;
                bestPerm = permission;
            }
        }

        if (bestPerm != null) {
            return new ResolvedLimit(highestPerm, bestPerm);
        }

        int highestRank = -1;
        String bestRank = null;
        ConfigurationSection rankLimits = DonutOrders.getInstance().getConfig()
                .getConfigurationSection("rank-limits");
        if (rankLimits != null) {
            for (String rank : rankLimits.getKeys(false)) {
                if (rank.equalsIgnoreCase("default")) {
                    continue;
                }
                if (!hasPermission.test("group." + rank)) {
                    continue;
                }
                int value = rankLimits.getInt(rank);
                if (value > highestRank) {
                    highestRank = value;
                    bestRank = "rank-limits." + rank;
                }
            }
        }

        if (bestRank != null) {
            return new ResolvedLimit(highestRank, bestRank);
        }

        int configDefault = getConfigDefaultLimit();
        return new ResolvedLimit(configDefault, "config (default: " + configDefault + ")");
    }

    /** Returns the configured default limit (no permissions applied). */
    public int getConfigDefaultLimit() {
        return getConfigDefaultLimitInternal();
    }

    private Map<String, Boolean> collectEffectivePermissions(Permissible permissible) {
        Map<String, Boolean> permissions = new java.util.HashMap<>();
        for (PermissionAttachmentInfo attachment : permissible.getEffectivePermissions()) {
            permissions.put(attachment.getPermission(), attachment.getValue());
        }
        return permissions;
    }

    /**
     * Refreshes the cached limit for a player (e.g. on join or permission change).
     */
    public void refresh(Player player) {
        cache.put(player.getUniqueId(), resolveLimit(player));
    }

    /**
     * Removes a player from the cache (e.g. on quit).
     */
    public void evict(UUID playerId) {
        cache.remove(playerId);
    }

    /**
     * Clears the entire cache (e.g. on config reload).
     */
    public void clearCache() {
        cache.clear();
    }

  /**
   * Rebuilds cached limits for all online players.
   */
    public void refreshAllOnline() {
        cache.clear();
        for (Player player : DonutOrders.getInstance().getServer().getOnlinePlayers()) {
            refresh(player);
        }
    }

    private int getConfigDefaultLimitInternal() {
        var config = DonutOrders.getInstance().getConfig();
        if (config.contains("orders.default-limit")) {
            return config.getInt("orders.default-limit");
        }
        if (config.contains("orders.max-per-player")) {
            return config.getInt("orders.max-per-player");
        }
        ConfigurationSection rankLimits = config.getConfigurationSection("rank-limits");
        if (rankLimits != null && rankLimits.contains("default")) {
            return rankLimits.getInt("default");
        }
        return 10;
    }

    /**
     * A resolved order limit and its source description.
     *
     * @param limit  maximum active orders allowed
     * @param source permission node, rank key, or config description
     */
    public record ResolvedLimit(int limit, String source) {}
}

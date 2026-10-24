package io.nightbeam.donutorders.economy;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Resolves known Vault-compatible economy bridges without requiring a plugin
 * literally named {@code Vault}.
 *
 * <p>Folia servers commonly use {@code VaultUnlocked} instead of classic Vault.
 * Hard-depending on {@code Vault} prevents DonutOrders from enabling even
 * when an Economy service is available.
 */
public final class EconomyBridgeDetector {

    public static final List<String> KNOWN_BRIDGE_PLUGIN_NAMES = List.of(
            "Vault",
            "VaultUnlocked"
    );

    private EconomyBridgeDetector() {
    }

    /**
     * Returns the first installed known bridge plugin name.
     * Prefers enabled bridges, then falls back to any installed known bridge.
     */
    public static Optional<String> findInstalledBridgeName(
            Function<String, Boolean> isInstalled,
            Function<String, Boolean> isEnabled
    ) {
        for (String name : KNOWN_BRIDGE_PLUGIN_NAMES) {
            if (Boolean.TRUE.equals(isInstalled.apply(name)) && Boolean.TRUE.equals(isEnabled.apply(name))) {
                return Optional.of(name);
            }
        }
        for (String name : KNOWN_BRIDGE_PLUGIN_NAMES) {
            if (Boolean.TRUE.equals(isInstalled.apply(name))) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the first installed known bridge plugin, if any.
     */
    public static Optional<Plugin> findInstalledBridge(PluginManager pluginManager) {
        if (pluginManager == null) {
            return Optional.empty();
        }
        return findInstalledBridgeName(
                name -> pluginManager.getPlugin(name) != null,
                name -> {
                    Plugin plugin = pluginManager.getPlugin(name);
                    return plugin != null && plugin.isEnabled();
                }
        ).map(pluginManager::getPlugin);
    }

    /**
     * True when any known bridge plugin name is present (enabled or not).
     */
    public static boolean isKnownBridgePresent(PluginManager pluginManager) {
        return findInstalledBridge(pluginManager).isPresent();
    }

    /**
     * Human-readable label for logs / diagnostics.
     */
    public static String describeBridge(PluginManager pluginManager) {
        return findInstalledBridge(pluginManager)
                .map(plugin -> plugin.getName() + " v" + plugin.getDescription().getVersion())
                .orElse("none (looking for Vault or VaultUnlocked)");
    }

    /**
     * Human-readable label when only plugin names are known (unit tests / pre-checks).
     */
    public static String describeBridgeName(Optional<String> bridgeName) {
        return bridgeName.orElse("none (looking for Vault or VaultUnlocked)");
    }
}

package io.nightbeam.donutorders.economy;

import java.util.Optional;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class VaultEconomyProvider {

    private final Economy economy;
    private final String bridgeLabel;

    private VaultEconomyProvider(Economy economy, String bridgeLabel) {
        this.economy = economy;
        this.bridgeLabel = bridgeLabel;
    }

    public static Optional<VaultEconomyProvider> create(JavaPlugin plugin) {
        String bridgeLabel = EconomyBridgeDetector.describeBridge(plugin.getServer().getPluginManager());
        Optional<Plugin> bridge = EconomyBridgeDetector.findInstalledBridge(plugin.getServer().getPluginManager());

        RegisteredServiceProvider<Economy> provider =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider == null || provider.getProvider() == null) {
            if (bridge.isEmpty()) {
                plugin.getLogger().severe(
                        "No Vault-compatible economy bridge found. Install Vault or VaultUnlocked "
                                + "plus an economy plugin (EssentialsX, CMI, etc.), then restart.");
            } else {
                plugin.getLogger().severe(
                        "Found economy bridge " + bridgeLabel
                                + " but no Economy service is registered yet. "
                                + "Ensure your economy plugin is installed and enabled.");
            }
            return Optional.empty();
        }

        Economy economy = provider.getProvider();
        plugin.getLogger().info(
                "Economy linked via " + bridgeLabel
                        + " using provider " + economy.getName() + ".");
        return Optional.of(new VaultEconomyProvider(economy, bridgeLabel));
    }

    public String bridgeLabel() {
        return bridgeLabel;
    }

    public boolean has(OfflinePlayer player, double amount) {
        return economy.has(player, amount);
    }

    public EconomyResponse withdraw(OfflinePlayer player, double amount) {
        return economy.withdrawPlayer(player, amount);
    }

    public EconomyResponse deposit(OfflinePlayer player, double amount) {
        return economy.depositPlayer(player, amount);
    }

    public String format(double amount) {
        return economy.format(amount);
    }

    public String currencyName() {
        return economy.currencyNamePlural();
    }

    public double getBalance(OfflinePlayer player) {
        return economy.getBalance(player);
    }
}

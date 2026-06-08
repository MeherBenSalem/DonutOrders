package com.donutorders;

import com.donutorders.command.OrdersCommand;
import com.donutorders.listener.ChatListener;
import com.donutorders.listener.InventoryListener;
import com.donutorders.listener.PlayerQuitListener;
import com.donutorders.manager.ChatInputHandler;
import com.donutorders.manager.GUIManager;
import com.donutorders.manager.OrderManager;
import com.donutorders.scheduler.FoliaScheduler;
import com.donutorders.storage.StorageManager;
import com.donutorders.util.ModrinthUpdateChecker;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.Collections;
import java.util.logging.Level;

/**
 * DonutOrders — Main plugin class.
 *
 * <h2>Enable sequence</h2>
 * <ol>
 *   <li>Detect Folia / Paper via {@link FoliaScheduler}.</li>
 *   <li>Save default config and messages files.</li>
 *   <li>Connect to Vault Economy.</li>
 *   <li>Initialise {@link StorageManager} (opens HikariCP + creates tables).</li>
 *   <li>Load all orders from DB <em>asynchronously</em>; when done, continue
 *       on the global thread to finish initialisation.</li>
 *   <li>Create managers, register commands and listeners, start scheduled tasks.</li>
 * </ol>
 *
 * <h2>Disable sequence</h2>
 * Close the HikariCP connection pool. All in-memory mutations are already
 * persisted immediately when they happen, so no flush is needed.
 */
public class DonutOrders extends JavaPlugin {

    private static DonutOrders instance;

    private Economy           economy;
    private StorageManager    storageManager;
    private OrderManager      orderManager;
    private ChatInputHandler  chatInputHandler;
    private GUIManager        guiManager;

    /** The loaded messages.yml configuration. */
    private FileConfiguration messages;
    private boolean pluginReady;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        instance = this;

        // Detect server variant
        getLogger().info(FoliaScheduler.IS_FOLIA
            ? "[DonutOrders] Folia detected — using regionised schedulers."
            : "[DonutOrders] Paper/Spigot detected — using BukkitScheduler.");

        // Save default resources on first start
        saveDefaultConfig();
        saveResourceIfAbsent("messages.yml");
        reloadMessages();

        // Vault must be present to operate.
        if (!setupEconomy()) {
            getLogger().severe("[DonutOrders] Vault economy not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialise storage (creates DB file + tables synchronously on enable thread)
        try {
            storageManager = new StorageManager(getDataFolder());
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "[DonutOrders] Failed to initialise database.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Register a temporary /orders handler immediately so the command
        // does not fail during async startup or in degraded load states.
        registerStartupOrdersCommand();

        // Load all persisted orders async, then complete enable on global thread
        storageManager.loadAll(this::completeEnable);
    }

    /**
     * Called on the global/main thread after the async DB load completes.
     * Initialises managers, registers commands and listeners, starts tasks.
     */
    private void completeEnable() {
        chatInputHandler = new ChatInputHandler();
        orderManager     = new OrderManager(storageManager, economy);
        guiManager       = new GUIManager(storageManager, orderManager, chatInputHandler);

        // Register commands
        var ordersCmd = getCommand("orders");
        if (ordersCmd != null) {
            OrdersCommand executor = new OrdersCommand(guiManager);
            ordersCmd.setExecutor(executor);
            ordersCmd.setTabCompleter(executor);
        } else {
            getLogger().severe("[DonutOrders] /orders command was not found in plugin.yml.");
        }
        pluginReady = true;

        // Register listeners
        var pm = getServer().getPluginManager();
        pm.registerEvents(new InventoryListener(guiManager), this);
        pm.registerEvents(new ChatListener(chatInputHandler), this);
        pm.registerEvents(new PlayerQuitListener(guiManager, chatInputHandler), this);

        // Expiry check: every 60 seconds (20 ticks * 60)
        FoliaScheduler.runGlobalRepeating(
            () -> orderManager.runExpiryCheck(), 20L * 60, 20L * 60);

        // Chat input timeout cleanup: every 30 seconds
        FoliaScheduler.runGlobalRepeating(
            () -> chatInputHandler.tickTimeouts(), 20L * 30, 20L * 30);

        getLogger().info("[DonutOrders] Enabled successfully. "
            + storageManager.getAllOrders().size() + " orders loaded.");

        ModrinthUpdateChecker.checkAsync(this);
    }

    private void registerStartupOrdersCommand() {
        var ordersCmd = getCommand("orders");
        if (ordersCmd == null) {
            getLogger().severe("[DonutOrders] /orders command was not found in plugin.yml.");
            return;
        }

        ordersCmd.setExecutor((sender, command, label, args) -> {
            sender.sendMessage(DonutOrders.colorize(
                getMessages().getString("plugin-starting",
                    "&eDonutOrders is still starting. Please try again in a moment.")));
            return true;
        });
        ordersCmd.setTabCompleter((sender, command, alias, args) -> Collections.emptyList());
    }

    @Override
    public void onDisable() {
        if (storageManager != null) {
            storageManager.close();
        }
        getLogger().info("[DonutOrders] Disabled. Database connection closed.");
    }

    // ── Configuration helpers ─────────────────────────────────────────────────

    /**
     * Reloads both {@code config.yml} and {@code messages.yml} from disk.
     * Called on plugin enable and from the admin reload command.
     */
    public void reloadPluginConfig() {
        reloadConfig();
        reloadMessages();
    }

    private void reloadMessages() {
        saveResourceIfAbsent("messages.yml");
        File file = new File(getDataFolder(), "messages.yml");
        messages = YamlConfiguration.loadConfiguration(file);
    }

    /** Returns the loaded messages.yml configuration. */
    public FileConfiguration getMessages() {
        return messages;
    }

    /**
     * Saves a resource from the plugin jar to the data folder if it does not
     * already exist there.
     */
    private void saveResourceIfAbsent(String resourceName) {
        File target = new File(getDataFolder(), resourceName);
        if (!target.exists()) {
            saveResource(resourceName, false);
        }
    }

    // ── Vault setup ───────────────────────────────────────────────────────────

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp =
            getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    // ── Static accessors ──────────────────────────────────────────────────────

    /** Returns the singleton plugin instance. */
    public static DonutOrders getInstance() {
        return instance;
    }

    /** Returns the active Vault Economy provider. */
    public Economy getEconomy() {
        return economy;
    }

    /**
     * Translates {@code &} color codes and {@code #RRGGBB} hex codes into the
     * Minecraft § encoding accepted by {@link org.bukkit.entity.Player#sendMessage}.
     *
     * <p>Hex support uses Paper's {@link net.md_5.bungee.api.ChatColor} which
     * is available on both Paper and Folia 1.20+.
     */
    public static String colorize(String text) {
        if (text == null) return "";
        // Translate hex color codes first (#RRGGBB → §x§R§R§G§G§B§B)
        text = translateHex(text);
        // Then translate standard & codes
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', text);
    }

    /**
     * Replaces {@code #RRGGBB} patterns with the Bukkit hex color sequence.
     */
    private static String translateHex(String text) {
        // Pattern: #[0-9a-fA-F]{6}
        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile("#[a-fA-F0-9]{6}").matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String hex   = matcher.group();
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.substring(1).toCharArray()) {
                replacement.append('§').append(c);
            }
            matcher.appendReplacement(sb, replacement.toString());
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}

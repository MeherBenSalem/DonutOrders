package io.nightbeam.donutorders;

import io.nightbeam.donutorders.api.DonutOrdersApi;
import io.nightbeam.donutorders.command.AdminCommand;
import io.nightbeam.donutorders.command.OrderCommand;
import io.nightbeam.donutorders.command.OrdersCommand;
import io.nightbeam.donutorders.economy.VaultEconomyProvider;
import io.nightbeam.donutorders.gui.GuiManager;
import io.nightbeam.donutorders.hook.OrdersPlaceholders;
import io.nightbeam.donutorders.integration.DiscordWebhook;
import io.nightbeam.donutorders.listener.MenuListener;
import io.nightbeam.donutorders.service.ApiProvider;
import io.nightbeam.donutorders.service.FeeCalculator;
import io.nightbeam.donutorders.service.OrderRules;
import io.nightbeam.donutorders.service.OrderService;
import io.nightbeam.donutorders.service.Payments;
import io.nightbeam.donutorders.service.TransactionLog;
import io.nightbeam.donutorders.storage.DatabaseManager;
import io.nightbeam.donutorders.storage.SqlOrderRepository;
import io.nightbeam.donutorders.sync.SyncBus;
import io.nightbeam.donutorders.sync.SyncBusFactory;
import io.nightbeam.donutorders.util.Enchants;
import io.nightbeam.donutorders.util.MessageUtil;
import io.nightbeam.donutorders.util.SchedulerAdapter;
import io.nightbeam.donutorders.util.UpdateChecker;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class DonutOrdersPlugin extends JavaPlugin {

    private SchedulerAdapter scheduler;
    private MessageUtil messages;
    private FileConfiguration messagesConfig;
    private VaultEconomyProvider economy;
    private DatabaseManager database;
    private SyncBus sync;
    private TransactionLog transactionLog;
    private DiscordWebhook webhook;
    private OrderService orders;
    private GuiManager gui;

    @Override
    public void onEnable() {
        if (!isPaperFamily()) {
            getLogger().severe("DonutOrders needs Paper or a Paper fork (Purpur, Folia, Pufferfish...). This server ("
                    + Bukkit.getName() + ") is missing Paper's API, so the plugin is disabled. Paper is a drop-in replacement for"
                    + " Spigot: https://papermc.io/downloads");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        List<String> migrated = ConfigMigrator.migrate(this);
        saveDefaultConfig();
        applyConfigDefaults();
        reloadMessagesConfig();
        this.scheduler = new SchedulerAdapter(this);
        this.messages = new MessageUtil(this);
        migrated.forEach(note -> getLogger().info(note));

        Optional<VaultEconomyProvider> found = VaultEconomyProvider.create(this);
        if (found.isEmpty()) {
            getLogger().severe("DonutOrders needs a Vault economy (Vault or VaultUnlocked plus an economy plugin). Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.economy = found.get();
        this.database = DatabaseManager.fromConfig(this);
        SqlOrderRepository repository = new SqlOrderRepository(database.dataSource(), database.getDatabaseType(),
                scheduler.asyncExecutor(), getLogger());
        FeeCalculator fees = new FeeCalculator(this::getConfig);
        OrderRules rules = new OrderRules(this::getConfig);
        Payments payments = new Payments(scheduler, economy, repository, fees, getLogger());
        this.sync = SyncBusFactory.create(this);
        this.transactionLog = new TransactionLog(new File(getDataFolder(), "logs").toPath(),
                () -> getConfig().getBoolean("transaction-log.enabled", true), getLogger());
        this.webhook = new DiscordWebhook(this::getConfig, getLogger());
        this.orders = new OrderService(this, scheduler, repository, database.getDatabaseType(), payments, fees, rules, sync,
                transactionLog, webhook);
        this.gui = new GuiManager(this, orders);

        getServer().getPluginManager().registerEvents(new MenuListener(gui), this);
        register("orders", new OrdersCommand(this, gui, orders));
        register("order", new OrderCommand(this, gui, orders));
        register("orderadmin", new AdminCommand(this, gui, orders));
        getServer().getServicesManager().register(DonutOrdersApi.class, new ApiProvider(orders, gui), this, ServicePriority.Normal);

        orders.start().whenComplete((ignored, failure) -> {
            if (failure != null) {
                getLogger().log(Level.SEVERE, "DonutOrders could not open its database. Orders are unavailable until this is fixed.", failure);
                return;
            }
            getLogger().info("DonutOrders ready: open orders " + orders.cache().active(System.currentTimeMillis()).size() + ", "
                    + database.getDatabaseType().name() + " storage, economy via " + economy.bridgeLabel()
                    + (scheduler.folia() ? ", Folia regions" : "") + (sync.live() ? ", Redis sync" : "") + ".");
            // Players already online after a reload.
            scheduler.runGlobal(() -> Bukkit.getOnlinePlayers().forEach(orders::onJoin));
        });

        registerPlaceholders();
        UpdateChecker updates = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(updates, this);
        updates.checkNow();
        startMetrics();
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (gui != null) gui.closeAll();
        if (orders != null) orders.shutdown();
        else if (scheduler != null) scheduler.shutdown(2_000L);
        if (sync != null) sync.shutdown();
        if (webhook != null) webhook.shutdown();
        if (transactionLog != null) transactionLog.shutdown();
        if (database != null) database.shutdown();
    }

    /** Paper's API (Adventure components, region schedulers) is required; plain Spigot lacks it. */
    private static boolean isPaperFamily() {
        try {
            Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
            Class.forName("net.kyori.adventure.text.minimessage.MiniMessage");
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    private void register(String name, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) throw new IllegalStateException("Missing command in plugin.yml: " + name);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerPlaceholders() {
        if (!getConfig().getBoolean("placeholderapi.enabled", true) || !getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
        try {
            if (new OrdersPlaceholders(this, orders).register()) getLogger().info("PlaceholderAPI found: %donutorders_...% placeholders registered.");
        } catch (LinkageError | RuntimeException exception) {
            getLogger().warning("Could not register PlaceholderAPI placeholders: " + exception.getMessage());
        }
    }

    private void startMetrics() {
        if (!getConfig().getBoolean("metrics.enabled", true)) return;
        try {
            Metrics metrics = new Metrics(this, 33559);
            metrics.addCustomChart(new SimplePie("server_software", () -> Bukkit.getServer().getName()));
            metrics.addCustomChart(new SimplePie("storage_type", () -> database.getDatabaseType().name()));
            metrics.addCustomChart(new SimplePie("seller_tax", () -> String.valueOf(getConfig().getBoolean("economy.seller-tax.enabled", false))));
            metrics.addCustomChart(new SimplePie("creation_fee", () -> String.valueOf(getConfig().getBoolean("economy.creation-fee.enabled", false))));
            metrics.addCustomChart(new SimplePie("redis_sync", () -> String.valueOf(sync.live())));
        } catch (Throwable throwable) {
            getLogger().warning("bStats metrics failed to load (the plugin continues): " + throwable.getMessage());
        }
    }

    /** {@code /orderadmin reload}: config, messages and every cache derived from them. */
    public void reloadEverything() {
        reloadConfig();
        applyConfigDefaults();
        reloadMessagesConfig();
        if (messages != null) messages.reload();
        Enchants.clearCache();
        if (gui != null) gui.clearCaches();
    }

    public void reloadMessagesConfig() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) saveResource("messages.yml", false);
        messagesConfig = YamlConfiguration.loadConfiguration(file);
        InputStream defaults = getResource("messages.yml");
        if (defaults != null) {
            messagesConfig.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
    }

    /** New settings from the bundled config.yml are added to the server's file; existing values are never changed. */
    public void applyConfigDefaults() {
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    public FileConfiguration getMessagesConfig() {
        return messagesConfig;
    }

    public MessageUtil messages() {
        return messages;
    }

    public SchedulerAdapter schedulerAdapter() {
        return scheduler;
    }

    public VaultEconomyProvider economy() {
        return economy;
    }

    public OrderService orders() {
        return orders;
    }
}

package io.nightbeam.donutorders.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.util.Locale;
import javax.sql.DataSource;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class DatabaseManager {

    private final DatabaseType databaseType;
    private final HikariDataSource dataSource;

    private DatabaseManager(DatabaseType databaseType, HikariDataSource dataSource) {
        this.databaseType = databaseType;
        this.dataSource = dataSource;
    }

    public static DatabaseManager fromConfig(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        String configured = config.getString("storage.type", "SQLITE").toUpperCase(Locale.ROOT);
        DatabaseType databaseType = configured.equals("MYSQL") || configured.equals("MARIADB") ? DatabaseType.MYSQL : DatabaseType.SQLITE;
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("DonutOrdersPool");
        hikari.setConnectionTimeout(10_000L);
        hikari.setValidationTimeout(5_000L);
        hikari.setLeakDetectionThreshold(30_000L);

        if (databaseType == DatabaseType.SQLITE) {
            plugin.getDataFolder().mkdirs();
            File file = new File(plugin.getDataFolder(), config.getString("storage.sqlite.file", "donutorders.db"));
            hikari.setDriverClassName("org.sqlite.JDBC");
            hikari.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
            hikari.setMaximumPoolSize(2);
            hikari.setMinimumIdle(1);
            hikari.addDataSourceProperty("journal_mode", "WAL");
            hikari.addDataSourceProperty("synchronous", "NORMAL");
            hikari.addDataSourceProperty("busy_timeout", "10000");
            // Write transactions take the lock when they begin, so two never deadlock upgrading a read lock.
            hikari.addDataSourceProperty("transaction_mode", "IMMEDIATE");
        } else {
            String host = config.getString("storage.mysql.host", "localhost");
            int port = config.getInt("storage.mysql.port", 3306);
            String database = config.getString("storage.mysql.database", "donutorders");
            String parameters = config.getString("storage.mysql.parameters",
                    "useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8");
            hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hikari.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + (parameters == null || parameters.isBlank() ? "" : "?" + parameters));
            hikari.setUsername(config.getString("storage.mysql.username", "donutorders"));
            hikari.setPassword(config.getString("storage.mysql.password", ""));
            hikari.setMaximumPoolSize(Math.max(2, config.getInt("storage.mysql.pool-size", 10)));
            hikari.setMinimumIdle(1);
        }
        return new DatabaseManager(databaseType, new HikariDataSource(hikari));
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public DatabaseType getDatabaseType() {
        return databaseType;
    }

    public void shutdown() {
        dataSource.close();
    }
}

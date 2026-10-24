package io.nightbeam.donutorders.sync;

import java.util.UUID;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class SyncBusFactory {

    private SyncBusFactory() {
    }

    public static SyncBus create(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("sync.redis.enabled", false)) return SyncBus.noop();
        RedisSyncBus bus = new RedisSyncBus(plugin.getLogger(), UUID.randomUUID().toString(),
                config.getString("sync.redis.host", "localhost"), config.getInt("sync.redis.port", 6379),
                config.getString("sync.redis.password", ""), config.getString("sync.redis.channel", "donutorders:sync"));
        bus.start();
        return bus;
    }
}

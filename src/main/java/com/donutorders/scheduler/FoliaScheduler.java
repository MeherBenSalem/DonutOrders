package com.donutorders.scheduler;

import com.donutorders.DonutOrders;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Folia-first scheduler abstraction.
 *
 * <p>At plugin startup we probe for the class
 * {@code io.papermc.paper.threadedregions.RegionizedServer}. If it exists we
 * are running under Folia and use the regionised schedulers. Otherwise we
 * fall back to the standard BukkitScheduler so the same codebase works on
 * Paper / Spigot without any changes.
 *
 * <p><b>Thread-safety contract</b>:
 * <ul>
 *   <li>{@link #runAtEntity} — always executes on the thread owning the entity's
 *       region. Safe to call Bukkit API, open inventories, send messages.</li>
 *   <li>{@link #runAtLocation} — executes on the thread owning that chunk.</li>
 *   <li>{@link #runGlobal} — executes on the server's main / global thread.
 *       Suitable for day-cycle logic, console messages, non-world tasks.</li>
 *   <li>{@link #runAsync} — executes off the main thread. Must NEVER call the
 *       Bukkit API directly. Use for database I/O, then bounce back via one of
 *       the above methods before touching any Bukkit objects.</li>
 * </ul>
 */
public final class FoliaScheduler {

    /** True when this server is Folia (regionalised threading). */
    public static final boolean IS_FOLIA;

    static {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            folia = false;
        }
        IS_FOLIA = folia;
    }

    private FoliaScheduler() {}

    // ──────────────────────────────────────────────────────────────────────────
    //  Entity-bound scheduling
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Run {@code task} on the thread that owns the entity's region.
     *
     * @param entity  the entity whose region thread should execute the task
     * @param task    work to perform (safe to call Bukkit API here)
     * @param retired called if the entity leaves the world before the task fires;
     *                keep this short — no I/O, no chunk loads
     */
    public static void runAtEntity(Entity entity, Runnable task, Runnable retired) {
        Plugin plugin = DonutOrders.getInstance();
        if (IS_FOLIA) {
            // EntityScheduler.run returns null if the entity is not reachable;
            // in that case neither task nor retired will fire — acceptable.
            entity.getScheduler().run(plugin, t -> task.run(), retired);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Run {@code task} on the entity's region thread after a delay.
     *
     * @param entity  target entity
     * @param task    task to run
     * @param retired retired callback (entity left world before delay elapsed)
     * @param delayTicks delay in server ticks
     */
    public static void runAtEntityDelayed(Entity entity, Runnable task,
                                          Runnable retired, long delayTicks) {
        Plugin plugin = DonutOrders.getInstance();
        if (IS_FOLIA) {
            entity.getScheduler().runDelayed(plugin, t -> task.run(), retired, delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Location / region-bound scheduling
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Run {@code task} on the thread owning the chunk at {@code location}.
     */
    public static void runAtLocation(Location location, Runnable task) {
        Plugin plugin = DonutOrders.getInstance();
        if (IS_FOLIA) {
            Bukkit.getRegionScheduler().run(plugin, location, t -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Global / server-tick scheduling
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Run {@code task} on the global region thread (day cycle, console, etc.).
     * On Paper this is the main thread.
     */
    public static void runGlobal(Runnable task) {
        Plugin plugin = DonutOrders.getInstance();
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Run {@code task} on the global region thread after {@code delayTicks}.
     */
    public static void runGlobalDelayed(Runnable task, long delayTicks) {
        Plugin plugin = DonutOrders.getInstance();
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    /**
     * Run {@code task} on the global region thread repeatedly.
     *
     * @param task         repeating task
     * @param initialTicks delay before first execution (ticks)
     * @param periodTicks  period between executions (ticks)
     */
    public static void runGlobalRepeating(Runnable task, long initialTicks, long periodTicks) {
        Plugin plugin = DonutOrders.getInstance();
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                    plugin, t -> task.run(), initialTicks, periodTicks);
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, task, initialTicks, periodTicks);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Async scheduling
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Run {@code task} asynchronously (off the main/region thread).
     * <b>Never</b> call any Bukkit API from within this task. On completion,
     * bounce back to the correct thread via {@link #runAtEntity} etc.
     */
    public static void runAsync(Runnable task) {
        Plugin plugin = DonutOrders.getInstance();
        if (IS_FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }
}

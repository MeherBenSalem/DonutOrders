package com.donutorders.scheduler;

import com.donutorders.DonutOrders;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;
import java.util.logging.Logger;

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
        IS_FOLIA = detectFolia();
    }

    private FoliaScheduler() {}

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            // Some forks expose regional schedulers without that exact class name.
            String serverClass = Bukkit.getServer().getClass().getName().toLowerCase();
            return serverClass.contains("folia") || serverClass.contains("regionized");
        }
    }

    private static Plugin plugin() {
        return DonutOrders.getInstance();
    }

    private static Logger log() {
        DonutOrders instance = DonutOrders.getInstance();
        return instance != null ? instance.getLogger() : Logger.getLogger("DonutOrders");
    }

    private static void safeRun(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            log().log(Level.SEVERE, "Scheduled task failed", t);
        }
    }

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
        Plugin plugin = plugin();
        if (IS_FOLIA) {
            entity.getScheduler().run(plugin, t -> safeRun(task), retired);
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (UnsupportedOperationException ex) {
            entity.getScheduler().run(plugin, t -> safeRun(task), retired);
        }
    }

    /** Convenience overload without a retired callback. */
    public static void runAtEntity(Entity entity, Runnable task) {
        runAtEntity(entity, task, () -> {});
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
        Plugin plugin = plugin();
        if (IS_FOLIA) {
            entity.getScheduler().runDelayed(plugin, t -> safeRun(task), retired, delayTicks);
            return;
        }
        try {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        } catch (UnsupportedOperationException ex) {
            entity.getScheduler().runDelayed(plugin, t -> safeRun(task), retired, delayTicks);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Location / region-bound scheduling
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Run {@code task} on the thread owning the chunk at {@code location}.
     */
    public static void runAtLocation(Location location, Runnable task) {
        Plugin plugin = plugin();
        if (IS_FOLIA) {
            Bukkit.getRegionScheduler().run(plugin, location, t -> safeRun(task));
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (UnsupportedOperationException ex) {
            Bukkit.getRegionScheduler().run(plugin, location, t -> safeRun(task));
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
        Plugin plugin = plugin();
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> safeRun(task));
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (UnsupportedOperationException ex) {
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> safeRun(task));
        }
    }

    /**
     * Run {@code task} on the global region thread after {@code delayTicks}.
     */
    public static void runGlobalDelayed(Runnable task, long delayTicks) {
        Plugin plugin = plugin();
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> safeRun(task), delayTicks);
            return;
        }
        try {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        } catch (UnsupportedOperationException ex) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> safeRun(task), delayTicks);
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
        Plugin plugin = plugin();
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                    plugin, t -> safeRun(task), initialTicks, periodTicks);
            return;
        }
        try {
            Bukkit.getScheduler().runTaskTimer(plugin, task, initialTicks, periodTicks);
        } catch (UnsupportedOperationException ex) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                    plugin, t -> safeRun(task), initialTicks, periodTicks);
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
        Plugin plugin = plugin();
        if (IS_FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> safeRun(task));
            return;
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } catch (UnsupportedOperationException ex) {
            // Regionalised server missed by static detection — never use CraftScheduler async.
            Bukkit.getAsyncScheduler().runNow(plugin, t -> safeRun(task));
        }
    }
}

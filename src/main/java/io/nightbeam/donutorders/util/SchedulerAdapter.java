package io.nightbeam.donutorders.util;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class SchedulerAdapter {

    private final Plugin plugin;
    private final ExecutorService asyncExecutor;
    private final boolean regionScheduler;

    public SchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
        this.asyncExecutor = Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors() / 2), new NamedThreadFactory());
        this.regionScheduler = hasGlobalRegionScheduler();
    }

    public Executor asyncExecutor() {
        return asyncExecutor;
    }

    public void runAsync(Runnable runnable) {
        asyncExecutor.execute(runnable);
    }

    public void runGlobal(Runnable runnable) {
        if (regionScheduler) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    public void runEntity(Entity entity, Runnable runnable) {
        runEntity(entity, runnable, null);
    }

    public void runEntity(Entity entity, Runnable runnable, Runnable retired) {
        if (regionScheduler) {
            entity.getScheduler().execute(plugin, runnable, retired, 1L);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    /** Runs on the entity's thread after a delay; dropped if the entity leaves (logs out) first. */
    public void runEntityLater(Entity entity, Runnable runnable, long delayTicks) {
        long delay = Math.max(1L, delayTicks);
        if (regionScheduler) {
            entity.getScheduler().runDelayed(plugin, task -> runnable.run(), null, delay);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, runnable, delay);
    }

    /** Runs on the global thread (the main thread on Paper) after a delay. */
    public void runGlobalLater(Runnable runnable, long delayTicks) {
        long delay = Math.max(1L, delayTicks);
        if (regionScheduler) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> runnable.run(), delay);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, runnable, delay);
    }

    public boolean folia() {
        return regionScheduler && isFolia();
    }

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException missing) {
            return false;
        }
    }

    public CancellableTask runGlobalRepeating(Runnable runnable, long initialDelayTicks, long periodTicks) {
        if (regionScheduler) {
            io.papermc.paper.threadedregions.scheduler.ScheduledTask paperTask =
                    Bukkit.getGlobalRegionScheduler()
                            .runAtFixedRate(plugin, task -> runnable.run(), initialDelayTicks, periodTicks);
            return paperTask::cancel;
        }
        BukkitTask bukkitTask = Bukkit.getScheduler()
                .runTaskTimer(plugin, runnable, initialDelayTicks, periodTicks);
        return bukkitTask::cancel;
    }

    /** Lets queued database work finish (up to the timeout), then stops the pool. */
    public void shutdown(long timeoutMillis) {
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) asyncExecutor.shutdownNow();
        } catch (InterruptedException interrupted) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static boolean hasGlobalRegionScheduler() {
        try {
            Bukkit.class.getMethod("getGlobalRegionScheduler");
            return true;
        } catch (NoSuchMethodException exception) {
            return false;
        }
    }

    @FunctionalInterface
    public interface CancellableTask {
        void cancel();
    }

    private static final class NamedThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "DonutOrders-Async-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}

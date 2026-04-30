package org.mvplugins.multiverse.core.utils;

import io.vavr.control.Try;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

/**
 * Compatibility layer for Folia's region-based threading model.
 * Provides scheduler methods that work on both Folia and non-Folia servers.
 */
public final class FoliaCompat {

    private static final boolean FOLIA;

    static {
        FOLIA = Try.of(() -> Class.forName("io.papermc.paper.threadedregions.RegionizedServer"))
                .map(clazz -> true)
                .getOrElse(false);
    }

    private FoliaCompat() {
    }

    /**
     * Checks if the server is running Folia.
     *
     * @return True if the server is Folia.
     */
    public static boolean isFolia() {
        return FOLIA;
    }

    /**
     * Schedules a task on the global region thread.
     * On Folia, uses the global region scheduler. On non-Folia, uses the main thread.
     *
     * @param plugin   The plugin owning the task.
     * @param runnable The task to run.
     */
    public static void runOnGlobalRegion(@NotNull Plugin plugin, @NotNull Runnable runnable) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> runnable.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /**
     * Schedules a delayed task on the global region thread.
     *
     * @param plugin   The plugin owning the task.
     * @param runnable The task to run.
     * @param delay    The delay in ticks.
     */
    public static void runOnGlobalRegionLater(@NotNull Plugin plugin, @NotNull Runnable runnable, long delay) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> runnable.run(), delay);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, runnable, delay);
        }
    }

    /**
     * Schedules a task on the region thread that owns the given entity.
     * On Folia, uses the entity's region scheduler. On non-Folia, uses the main thread.
     *
     * @param plugin   The plugin owning the task.
     * @param entity   The entity whose region the task should run on.
     * @param runnable The task to run.
     */
    public static void runOnEntityRegion(@NotNull Plugin plugin, @NotNull Entity entity, @NotNull Runnable runnable) {
        if (FOLIA) {
            entity.getScheduler().run(plugin, task -> runnable.run(), null);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /**
     * Schedules a delayed task on the region thread that owns the given entity.
     *
     * @param plugin      The plugin owning the task.
     * @param entity      The entity whose region the task should run on.
     * @param runnable    The task to run.
     * @param delayTicks  The delay in ticks.
     */
    public static void runOnEntityRegionLater(@NotNull Plugin plugin, @NotNull Entity entity,
                                              @NotNull Runnable runnable, long delayTicks) {
        if (FOLIA) {
            entity.getScheduler().runDelayed(plugin, task -> runnable.run(), null, delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
        }
    }

    /**
     * Schedules a task on the region thread that owns the given location.
     * On Folia, uses the location-based region scheduler. On non-Folia, uses the main thread.
     *
     * @param plugin   The plugin owning the task.
     * @param location The location whose region the task should run on.
     * @param runnable The task to run.
     */
    public static void runOnLocationRegion(@NotNull Plugin plugin, @NotNull Location location,
                                           @NotNull Runnable runnable) {
        if (FOLIA) {
            Bukkit.getRegionScheduler().run(plugin, location, task -> runnable.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /**
     * Schedules a delayed task on the region thread that owns the given location.
     *
     * @param plugin   The plugin owning the task.
     * @param location The location whose region the task should run on.
     * @param runnable The task to run.
     * @param delay    The delay in ticks.
     */
    public static void runOnLocationRegionLater(@NotNull Plugin plugin, @NotNull Location location,
                                                @NotNull Runnable runnable, long delay) {
        if (FOLIA) {
            Bukkit.getRegionScheduler().runDelayed(plugin, location, task -> runnable.run(), delay);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, runnable, delay);
        }
    }

    /**
     * Schedules an async task.
     * On Folia, uses the async scheduler. On non-Folia, uses the Bukkit async scheduler.
     *
     * @param plugin   The plugin owning the task.
     * @param runnable The task to run.
     */
    public static void runAsync(@NotNull Plugin plugin, @NotNull Runnable runnable) {
        if (FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, task -> runnable.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
        }
    }

    /**
     * Schedules a delayed async task.
     *
     * @param plugin   The plugin owning the task.
     * @param runnable The task to run.
     * @param delay    The delay.
     * @param unit     The time unit for the delay.
     */
    public static void runAsyncLater(@NotNull Plugin plugin, @NotNull Runnable runnable, long delay,
                                     @NotNull TimeUnit unit) {
        if (FOLIA) {
            Bukkit.getAsyncScheduler().runDelayed(plugin, task -> runnable.run(), delay, unit);
        } else {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, unitToTicks(delay, unit));
        }
    }

    private static long unitToTicks(long duration, TimeUnit unit) {
        return TimeUnit.MILLISECONDS.convert(duration, unit) / 50;
    }
}

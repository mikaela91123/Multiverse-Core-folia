package org.mvplugins.multiverse.core.utils;

import io.vavr.control.Try;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Callable;

/**
 * Compatibility layer for Folia's region-based threading model.
 * Provides scheduler methods that work on both Folia and non-Folia servers.
 */
public final class FoliaCompat {

    private static final boolean FOLIA;
    private static final Method IS_GLOBAL_TICK_THREAD;

    static {
        FOLIA = Try.of(() -> Class.forName("io.papermc.paper.threadedregions.RegionizedServer"))
                .map(clazz -> true)
                .getOrElse(false);
        IS_GLOBAL_TICK_THREAD = Try.of(() -> Bukkit.class.getMethod("isGlobalTickThread")).getOrNull();
    }

    private FoliaCompat() {
    }

    /**
     * Checks if the current thread is the global region tick thread on Folia.
     * On non-Folia, this is equivalent to {@link Bukkit#isPrimaryThread()}.
     *
     * @return True if the current thread can safely perform global server operations.
     */
    public static boolean isGlobalTickThread() {
        if (!FOLIA) {
            return Bukkit.isPrimaryThread();
        }
        if (IS_GLOBAL_TICK_THREAD == null) {
            return false;
        }
        return Try.of(() -> (boolean) IS_GLOBAL_TICK_THREAD.invoke(null)).getOrElse(false);
    }

    /**
     * Runs a callable on the global region thread and waits for the result.
     * Use this for operations that mutate global server state (e.g. world creation/unloading).
     * <p>
     * If already on the global tick thread, the callable is invoked directly to avoid deadlock.
     * Otherwise, the call is dispatched to the global region scheduler and the calling thread
     * blocks until completion (or the timeout elapses).
     *
     * @param plugin   The plugin owning the task.
     * @param callable The operation to invoke.
     * @param <T>      The return type of the callable.
     * @return The result of the callable.
     * @throws Exception If the callable threw, or the wait timed out / was interrupted.
     */
    public static <T> T callOnGlobalRegion(@NotNull Plugin plugin, @NotNull Callable<T> callable) throws Exception {
        if (!FOLIA || isGlobalTickThread()) {
            return callable.call();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            try {
                future.complete(callable.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        } catch (TimeoutException e) {
            throw new RuntimeException("Timed out waiting for global region task", e);
        }
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

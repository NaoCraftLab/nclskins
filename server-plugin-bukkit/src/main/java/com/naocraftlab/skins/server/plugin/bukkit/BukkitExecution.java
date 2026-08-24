package com.naocraftlab.skins.server.plugin.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;


final class BukkitExecution {
    private final Plugin plugin;
    private final boolean regionized;

    BukkitExecution(Plugin plugin, boolean regionized) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.regionized = regionized;
    }

    CompletableFuture<Void> player(Player player, Runnable action) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(action, "action");
        CompletableFuture<Void> completion = new CompletableFuture<>();
        Runnable guarded = () -> {
            try {
                action.run();
                completion.complete(null);
            } catch (RuntimeException failure) {
                completion.completeExceptionally(failure);
            } catch (Error fatal) {
                completion.completeExceptionally(fatal);
                throw fatal;
            }
        };
        if (!regionized) {
            Bukkit.getScheduler().runTask(plugin, guarded);
            return completion;
        }
        try {
            Object scheduler = player.getClass().getMethod("getScheduler").invoke(player);
            Consumer<Object> task = ignored -> guarded.run();
            invokePublicInterface(
                    scheduler,
                    schedulerInterface(scheduler),
                    "run",
                    new Class<?>[]{Plugin.class, Consumer.class, Runnable.class},
                    plugin,
                    task,
                    (Runnable) () -> completion.completeExceptionally(
                            new IllegalStateException("Player retired before NCL publication")));
        } catch (ReflectiveOperationException failure) {
            completion.completeExceptionally(unwrap(failure));
        }
        return completion;
    }

    void nextTick(Player player, Runnable action) {
        if (!regionized) {
            Bukkit.getScheduler().runTask(plugin, action);
            return;
        }
        try {
            Object scheduler = player.getClass().getMethod("getScheduler").invoke(player);
            invokePublicInterface(
                    scheduler,
                    schedulerInterface(scheduler),
                    "runDelayed",
                    new Class<?>[]{Plugin.class, Consumer.class, Runnable.class, long.class},
                    plugin,
                    (Consumer<Object>) ignored -> action.run(),
                    (Runnable) () -> { },
                    1L);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Folia entity scheduler ABI is unavailable", unwrap(failure));
        }
    }

    boolean isRegionized() {
        return regionized;
    }

    static Object invokePublicInterface(
            Object target,
            Class<?> publicInterface,
            String name,
            Class<?>[] parameterTypes,
            Object... arguments) throws ReflectiveOperationException {
        Method method = publicInterface.getMethod(name, parameterTypes);
        return method.invoke(target, arguments);
    }

    private static Class<?> schedulerInterface(Object scheduler) throws ClassNotFoundException {
        return Class.forName(
                "io.papermc.paper.threadedregions.scheduler.EntityScheduler",
                false,
                scheduler.getClass().getClassLoader());
    }

    private static Throwable unwrap(ReflectiveOperationException failure) {
        return failure instanceof InvocationTargetException invocation
                && invocation.getCause() != null ? invocation.getCause() : failure;
    }
}

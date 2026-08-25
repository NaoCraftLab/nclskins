package com.naocraftlab.skins.server.plugin.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

final class ClassicBukkitExecutionStrategy implements BukkitExecutionStrategy {
    private final Plugin plugin;

    ClassicBukkitExecutionStrategy(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public CompletableFuture<Void> player(Player player, Runnable action) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, guarded(action, completion));
        return completion;
    }

    @Override
    public void nextTick(Player player, Runnable action) {
        Bukkit.getScheduler().runTask(plugin, action);
    }

    static Runnable guarded(Runnable action, CompletableFuture<Void> completion) {
        Objects.requireNonNull(action, "action");
        return () -> {
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
    }
}

package com.naocraftlab.skins.server.plugin.bukkit;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

final class FoliaExecutionStrategy implements BukkitExecutionStrategy {
    private final Plugin plugin;

    FoliaExecutionStrategy(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public CompletableFuture<Void> player(Player player, Runnable action) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        player.getScheduler().run(
                plugin,
                ignored -> ClassicBukkitExecutionStrategy.guarded(action, completion).run(),
                () -> completion.completeExceptionally(
                        new IllegalStateException("Player retired before NCL publication")));
        return completion;
    }

    @Override
    public void nextTick(Player player, Runnable action) {
        player.getScheduler().runDelayed(plugin, ignored -> action.run(), () -> { }, 1L);
    }
}

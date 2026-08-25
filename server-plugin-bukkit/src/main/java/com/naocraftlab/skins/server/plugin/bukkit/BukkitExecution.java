package com.naocraftlab.skins.server.plugin.bukkit;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;


final class BukkitExecution {
    private final BukkitExecutionStrategy strategy;
    private final boolean regionized;

    BukkitExecution(Plugin plugin, boolean regionized) {
        Plugin checkedPlugin = Objects.requireNonNull(plugin, "plugin");
        this.regionized = regionized;
        strategy = regionized
                ? new FoliaExecutionStrategy(checkedPlugin)
                : new ClassicBukkitExecutionStrategy(checkedPlugin);
    }

    CompletableFuture<Void> player(Player player, Runnable action) {
        return strategy.player(
                Objects.requireNonNull(player, "player"),
                Objects.requireNonNull(action, "action"));
    }

    void nextTick(Player player, Runnable action) {
        strategy.nextTick(player, action);
    }

    boolean isRegionized() {
        return regionized;
    }

}

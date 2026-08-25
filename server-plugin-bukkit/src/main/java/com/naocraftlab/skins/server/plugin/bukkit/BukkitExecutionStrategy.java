package com.naocraftlab.skins.server.plugin.bukkit;

import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

interface BukkitExecutionStrategy {
    CompletableFuture<Void> player(Player player, Runnable action);

    void nextTick(Player player, Runnable action);
}

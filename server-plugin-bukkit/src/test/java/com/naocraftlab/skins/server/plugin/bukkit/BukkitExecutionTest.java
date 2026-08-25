package com.naocraftlab.skins.server.plugin.bukkit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


final class BukkitExecutionTest {
    @Test
    void guardedActionCompletesItsFuture() {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        ClassicBukkitExecutionStrategy.guarded(() -> { }, completion).run();
        assertTrue(completion.isDone());
        assertNull(completion.join());
    }
}

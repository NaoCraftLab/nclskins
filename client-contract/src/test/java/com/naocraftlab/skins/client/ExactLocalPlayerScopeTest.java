package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactLocalPlayerScopeTest {
    @Test
    void appliesOnlyToTheExactObjectOnTheOwnerThread() throws InterruptedException {
        Object localPlayer = new Object();
        ExactLocalPlayerScope scope = new ExactLocalPlayerScope(localPlayer);
        AtomicBoolean foreignThreadMatch = new AtomicBoolean(true);

        Thread foreignThread = new Thread(
                () -> foreignThreadMatch.set(scope.appliesTo(localPlayer)),
                "preview-scope-test");
        foreignThread.start();
        foreignThread.join();

        assertTrue(scope.appliesTo(localPlayer));
        assertFalse(scope.appliesTo(new Object()));
        assertFalse(foreignThreadMatch.get());
    }

    @Test
    void tryWithResourcesClosesTheScopeAfterAnException() {
        Object localPlayer = new Object();
        ExactLocalPlayerScope scope = new ExactLocalPlayerScope(localPlayer);

        assertThrows(IllegalStateException.class, () -> {
            try (scope) {
                assertTrue(scope.appliesTo(localPlayer));
                throw new IllegalStateException("render failure");
            }
        });

        assertFalse(scope.appliesTo(localPlayer));
    }
}

package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorPreviewLayerGuardTest {
    @Test
    void handlesRuntimeFailureOnlyWhileScopeIsActive() {
        RuntimeException failure = new RuntimeException("renderer");
        AtomicReference<RuntimeException> handled = new AtomicReference<>();

        assertFalse(EditorPreviewLayerGuard.handle(failure));
        try (EditorPreviewLayerGuard guard = EditorPreviewLayerGuard.open(handled::set)) {
            assertNotNull(guard);
            assertTrue(EditorPreviewLayerGuard.handle(failure));
            assertEquals(failure, handled.get());
        }
        assertFalse(EditorPreviewLayerGuard.handle(failure));
    }

    @Test
    void closesAfterExceptionAndRejectsNestedScope() {
        assertThrows(RuntimeException.class, () -> {
            try (EditorPreviewLayerGuard guard = EditorPreviewLayerGuard.open(failure -> {
            })) {
                assertNotNull(guard);
                assertThrows(
                        IllegalStateException.class,
                        () -> EditorPreviewLayerGuard.open(failure -> {
                        }));
                throw new RuntimeException("render");
            }
        });

        try (EditorPreviewLayerGuard guard = EditorPreviewLayerGuard.open(failure -> {
        })) {
            assertNotNull(guard);
            assertTrue(EditorPreviewLayerGuard.handle(new RuntimeException("next frame")));
        }
    }
}

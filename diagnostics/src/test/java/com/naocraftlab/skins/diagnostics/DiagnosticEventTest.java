package com.naocraftlab.skins.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


final class DiagnosticEventTest {
    @Test
    void everyEventHasAUniqueFixedSingleLineTemplate() {
        Set<String> messages = new HashSet<>();
        for (DiagnosticEvent event : DiagnosticEvent.values()) {
            assertFalse(event.message().isBlank());
            assertFalse(event.message().contains("\n"));
            assertFalse(event.message().contains("\r"));
            assertTrue(messages.add(event.message()), () -> "duplicate message for " + event);
        }
    }

    @Test
    void infoIsReservedForReadinessMarkers() {
        for (DiagnosticEvent event : DiagnosticEvent.values()) {
            if (event.level() == DiagnosticLevel.INFO) {
                assertTrue(event == DiagnosticEvent.PLUGIN_READY
                        || event == DiagnosticEvent.PROXY_READY);
            }
        }
    }
}

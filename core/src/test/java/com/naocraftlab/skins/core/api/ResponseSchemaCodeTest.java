package com.naocraftlab.skins.core.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResponseSchemaCodeTest {
    @Test
    void everyExposedCodeIsAStaticSchemaOnlyIdentifier() {
        for (ResponseSchemaCode code : ResponseSchemaCode.values()) {
            assertTrue(
                    code.diagnosticName().matches("[a-z][a-z0-9.-]{0,63}"),
                    () -> "Unsafe diagnostic schema code: " + code.name());
        }
    }
}

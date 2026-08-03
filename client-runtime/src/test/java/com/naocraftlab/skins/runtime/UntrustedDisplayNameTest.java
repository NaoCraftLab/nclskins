package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

final class UntrustedDisplayNameTest {
    @Test
    void removesControlBidiAndIsolatedSurrogateCharacters() {
        String sanitized = UntrustedDisplayName.sanitize(
                "  safe\n\u202eevil\u2066\ud800 name  ", "fallback");

        assertEquals("safe evil name", sanitized);
        assertFalse(sanitized.codePoints().anyMatch(Character::isISOControl));
    }

    @Test
    void preservesOrdinaryUnicodeAndCapsAutomaticNames() {
        assertEquals("Мой скин.png", UntrustedDisplayName.sanitizePngFileName(" Мой  скин.PNG "));
        assertEquals(128, UntrustedDisplayName.sanitize("x".repeat(200), "fallback").length());
        assertEquals("fallback", UntrustedDisplayName.sanitize("\n\u202e", "fallback"));
    }
}

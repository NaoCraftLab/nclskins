package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CpmPublicationNameTest {
    @Test
    void requestPreservesUnicodePunctuationSpacingCaseAndExtensionPrivately() {
        String name = "  Лесная é e\u0301 😀 +% (V2)_.cpmproject  ";
        byte[] wire = {1, 2};
        var request = new CpmModelPublication.Request(wire, 1, name);
        wire[0] = 9;
        request.wire()[0] = 8;
        assertArrayEquals(new byte[]{1, 2}, request.wire());
        assertEquals(name, request.name());
        assertEquals("CpmModelPublication.Request[private]", request.toString());
    }

    @Test
    void requestReplacesEveryFramingControlWithoutCollapsingOrdinarySpaces() {
        for (int control = 0; control <= 127; control++) {
            if (control >= 32 && control != 127) continue;
            assertEquals("a  b.cpmmodel", new CpmModelPublication.Request(new byte[]{1}, 1,
                    "a " + (char) control + "b.cpmmodel").name());
        }
    }

    @Test
    void requestAppliesOnlyNativeUiBoundWithoutSplittingSurrogatePair() {
        int cap = 1024 * 1024;
        for (int size : new int[]{4097, cap - 1, cap, cap + 1}) {
            String input = "я".repeat(size);
            assertEquals(input.substring(0, Math.min(cap, size)),
                    new CpmModelPublication.Request(new byte[]{1}, 1, input).name());
        }
        assertEquals("x".repeat(cap - 1), new CpmModelPublication.Request(new byte[]{1}, 1,
                "x".repeat(cap - 1) + "😀").name());
        String fits = "x".repeat(cap - 2) + "😀";
        assertEquals(fits, new CpmModelPublication.Request(new byte[]{1}, 1, fits).name());
    }

    @Test
    void requestUsesFallbackForMissingOrFramingOnlyName() {
        for (String name : new String[]{null, "", "   ", "\r\n\u0000\u007f"})
            assertEquals("NCL-Skins-model", new CpmModelPublication.Request(new byte[]{1}, 1, name).name());
    }
}

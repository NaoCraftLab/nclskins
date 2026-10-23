package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CpmModelPublicationCallerTest {
    @Test void applicationExposesExplicitPublicationWithoutChangingSessionContract() {
        assertTrue(java.util.Arrays.stream(ClientRuntime.class.getMethods())
                .anyMatch(method -> method.getName().equals("publishCpmModel")),
                "Missing explicit production publication caller; no UI dispatch or token wrapper may substitute");
        assertFalse(java.util.Arrays.stream(com.naocraftlab.skins.client.GameSessionTokenSource.class.getMethods())
                .anyMatch(method -> method.getName().toLowerCase().contains("eligib")));
    }
}

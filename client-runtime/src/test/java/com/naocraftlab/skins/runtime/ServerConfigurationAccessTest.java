package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


final class ServerConfigurationAccessTest {
    @Test
    void titleScreenCanConfigureTheNextLocalServerWithoutRestart() {
        ServerConfigurationAccess access = ServerConfigurationAccess.from(false, false);

        assertEquals(ServerConfigurationAccess.BEFORE_SERVER_START, access);
        assertTrue(access.visible());
        assertFalse(access.restartRequired());
    }

    @Test
    void runningIntegratedServerCanBeConfiguredButRequiresRestart() {
        ServerConfigurationAccess access = ServerConfigurationAccess.from(true, true);

        assertEquals(ServerConfigurationAccess.INTEGRATED_SERVER_RUNNING, access);
        assertTrue(access.visible());
        assertTrue(access.restartRequired());
    }

    @Test
    void remoteServerHidesLocalServerConfiguration() {
        ServerConfigurationAccess access = ServerConfigurationAccess.from(true, false);

        assertEquals(ServerConfigurationAccess.REMOTE_SERVER, access);
        assertFalse(access.visible());
        assertFalse(access.restartRequired());
    }
}

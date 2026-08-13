package com.naocraftlab.skins.server;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerRefreshCommandProtocolTest {
    @Test
    void commandNameIsExactVersionedAndCarriesNoAccountPayload() {
        assertEquals("nclskin", ServerRefreshCommandProtocol.ROOT_COMMAND);
        assertEquals(
                "_refresh_official_profile_v1",
                ServerRefreshCommandProtocol.REFRESH_COMMAND);
        assertEquals(
                "nclskin _refresh_official_profile_v1",
                ServerRefreshCommandProtocol.COMMAND);
        assertEquals(
                "nclskinsplugin:nclskin",
                ServerRefreshCommandProtocol.BUKKIT_ROOT_COMMAND);
        assertEquals(
                "nclskinsplugin:nclskin _refresh_official_profile_v1",
                ServerRefreshCommandProtocol.BUKKIT_COMMAND);

        String commandPath = (ServerRefreshCommandProtocol.COMMAND + " "
                + ServerRefreshCommandProtocol.BUKKIT_COMMAND).toLowerCase(Locale.ROOT);
        assertFalse(commandPath.contains("account"));
        assertFalse(commandPath.contains("profile_id"));
        assertFalse(commandPath.contains("uuid"));
        assertFalse(commandPath.contains("token"));
    }

    @Test
    void advertisementRequiresOnlyAPlayerAndLiveService() {
        assertTrue(ServerRefreshCommandProtocol.advertised(true, true));
        assertFalse(ServerRefreshCommandProtocol.advertised(false, true));
        assertFalse(ServerRefreshCommandProtocol.advertised(true, false));
        assertFalse(ServerRefreshCommandProtocol.advertised(false, false));
    }

    @Test
    void onlyAcceptedAndCoalescedAdmissionsSucceed() {
        assertEquals(1, ServerRefreshCommandProtocol.result(Admission.ACCEPTED));
        assertEquals(1, ServerRefreshCommandProtocol.result(Admission.COALESCED));
        assertEquals(0, ServerRefreshCommandProtocol.result(Admission.INELIGIBLE));
        assertEquals(0, ServerRefreshCommandProtocol.result(Admission.OVERLOADED));
        assertEquals(0, ServerRefreshCommandProtocol.result(Admission.CLOSED));
        assertThrows(NullPointerException.class, () -> ServerRefreshCommandProtocol.result(null));
    }
}

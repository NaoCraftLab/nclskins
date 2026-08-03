package com.naocraftlab.skins.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

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

        String commandPath = ServerRefreshCommandProtocol.COMMAND.toLowerCase(Locale.ROOT);
        assertFalse(commandPath.contains("account"));
        assertFalse(commandPath.contains("profile_id"));
        assertFalse(commandPath.contains("uuid"));
        assertFalse(commandPath.contains("token"));
    }

    @Test
    void eligibilityRequiresAPlayerLiveServiceAndPolicyApproval() {
        assertTrue(ServerRefreshCommandProtocol.eligible(true, true, true));
        assertFalse(ServerRefreshCommandProtocol.eligible(false, true, true));
        assertFalse(ServerRefreshCommandProtocol.eligible(true, false, true));
        assertFalse(ServerRefreshCommandProtocol.eligible(true, true, false));
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

package com.naocraftlab.skins.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.server.Admission;
import com.naocraftlab.skins.server.BatchAppearancePublisher;
import com.naocraftlab.skins.server.BatchPublicationResult;
import com.naocraftlab.skins.server.ConnectionKey;
import com.naocraftlab.skins.server.ConnectionSnapshot;
import com.naocraftlab.skins.server.IdentityAssurance;
import com.naocraftlab.skins.server.OfficialProfileResolver;
import com.naocraftlab.skins.server.PublicationOutcome;
import com.naocraftlab.skins.server.PublicationRequest;
import com.naocraftlab.skins.server.RefreshResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

final class ServerAppearanceRefreshServiceTest {
    @Test
    void eligibilityRequiresOnlineIdentityOrExplicitAttestedProxyOptIn() {
        ServerAppearanceRefreshService safe = service(false);
        ConnectionSnapshot online = connection(IdentityAssurance.ONLINE);
        ConnectionSnapshot proxy = connection(IdentityAssurance.TRUSTED_PROXY);
        ConnectionSnapshot offline = connection(IdentityAssurance.OFFLINE);

        assertTrue(safe.eligible(online));
        assertFalse(safe.eligible(proxy));
        assertFalse(safe.eligible(offline));
        assertTrue(safe.eligible(IdentityAssurance.ONLINE));
        assertFalse(safe.eligible(IdentityAssurance.TRUSTED_PROXY));
        assertFalse(safe.eligible(IdentityAssurance.OFFLINE));
        assertEquals(Admission.INELIGIBLE, safe.request(proxy).admission());
        assertEquals(
                RefreshResult.INELIGIBLE,
                safe.request(offline).completion().toCompletableFuture().join());
        safe.close();
        assertEquals(Admission.CLOSED, safe.request(online).admission());

        ServerAppearanceRefreshService trusted = service(true);
        assertTrue(trusted.eligible(proxy));
        assertTrue(trusted.eligible(IdentityAssurance.TRUSTED_PROXY));
        assertEquals(Admission.ACCEPTED, trusted.request(proxy).admission());
        trusted.disconnected(proxy.key());
        trusted.close();
    }

    private static ServerAppearanceRefreshService service(boolean trustedProxy) {
        ServerRefreshPolicy policy = ServerRefreshPolicy.defaults(trustedProxy, 100);
        BatchAppearancePublisher publisher = new BatchAppearancePublisher() {
            @Override
            public CompletionStage<BatchPublicationResult> publishBatch(
                    List<PublicationRequest> requests) {
                return CompletableFuture.completedFuture(BatchPublicationResult.all(
                        requests, PublicationOutcome.STALE));
            }

            @Override
            public void supersede(ConnectionKey ignored) {

            }
        };
        ServerAppearanceRefreshCoordinator coordinator =
                new ServerAppearanceRefreshCoordinator(
                        ignored -> OfficialProfileResolver.completed(
                                OfficialProfileResolver.Resolution.rejected()),
                        publisher,
                        policy);
        return new ServerAppearanceRefreshService(policy, coordinator);
    }

    private static ConnectionSnapshot connection(IdentityAssurance assurance) {
        UUID profileId = UUID.nameUUIDFromBytes(
                "service-player".getBytes(StandardCharsets.UTF_8));
        return new ConnectionSnapshot(
                new ConnectionKey(profileId, 1L),
                "Player",
                assurance);
    }
}

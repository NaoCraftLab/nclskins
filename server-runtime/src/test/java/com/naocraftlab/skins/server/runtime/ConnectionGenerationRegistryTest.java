package com.naocraftlab.skins.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.server.ConnectionKey;
import com.naocraftlab.skins.server.IdentityAssurance;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ConnectionGenerationRegistryTest {
    @Test
    void sameListenerRegistrationIsIdempotentAndIdentityBound() {
        ConnectionGenerationRegistry<EqualListener> registry =
                new ConnectionGenerationRegistry<>();
        UUID profileId = UUID.randomUUID();
        EqualListener listener = new EqualListener("shared-equality");

        ConnectionGenerationRegistry.Registration first = registry.connected(
                listener, profileId, "Player", IdentityAssurance.ONLINE);
        ConnectionGenerationRegistry.Registration repeated = registry.connected(
                listener, profileId, "Player", IdentityAssurance.ONLINE);

        assertEquals(first.snapshot().key(), repeated.snapshot().key());
        assertTrue(repeated.superseded().isEmpty());
        assertEquals(first.snapshot().key(), registry.keyFor(listener).orElseThrow());
        assertTrue(registry.matches(first.snapshot().key(), listener));
        assertFalse(registry.matches(
                first.snapshot().key(), new EqualListener("shared-equality")));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.connected(
                        listener,
                        UUID.randomUUID(),
                        "Other",
                        IdentityAssurance.ONLINE));
    }

    @Test
    void changedAssuranceRotatesGenerationAndSupersedesInFlightTrust() {
        ConnectionGenerationRegistry<Object> registry = new ConnectionGenerationRegistry<>();
        UUID profileId = UUID.randomUUID();
        Object listener = new Object();
        ConnectionKey online = registry.connected(
                listener, profileId, "Player", IdentityAssurance.ONLINE)
                .snapshot()
                .key();

        ConnectionGenerationRegistry.Registration downgraded = registry.connected(
                listener, profileId, "Player", IdentityAssurance.OFFLINE);

        assertNotEquals(online, downgraded.snapshot().key());
        assertEquals(online, downgraded.superseded().orElseThrow());
        assertEquals(IdentityAssurance.OFFLINE, downgraded.snapshot().assurance());
        assertTrue(registry.snapshot(online).isEmpty());
        assertEquals(downgraded.snapshot().key(), registry.keyFor(listener).orElseThrow());
    }

    @Test
    void reconnectSupersedesOldGenerationAndLateDisconnectCannotRemoveNewBinding() {
        ConnectionGenerationRegistry<Object> registry = new ConnectionGenerationRegistry<>();
        UUID profileId = UUID.randomUUID();
        Object oldListener = new Object();
        Object newListener = new Object();

        ConnectionKey oldKey = registry.connected(
                oldListener, profileId, "Player", IdentityAssurance.ONLINE)
                .snapshot()
                .key();
        ConnectionGenerationRegistry.Registration replacement = registry.connected(
                newListener, profileId, "Player", IdentityAssurance.ONLINE);
        ConnectionKey newKey = replacement.snapshot().key();

        assertNotEquals(oldKey, newKey);
        assertEquals(oldKey, replacement.superseded().orElseThrow());
        assertTrue(registry.snapshot(oldKey).isEmpty());
        assertTrue(registry.keyFor(oldListener).isEmpty());
        assertTrue(registry.disconnected(oldListener).isEmpty());
        assertEquals(newKey, registry.keyFor(newListener).orElseThrow());
        assertTrue(registry.matches(newKey, newListener));

        assertEquals(newKey, registry.disconnected(newListener).orElseThrow());
        assertTrue(registry.snapshot(newKey).isEmpty());
    }

    private record EqualListener(String value) {}
}

package com.naocraftlab.skins.server.plugin.common;

import com.naocraftlab.skins.server.SignedTexturesProperty;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


final class PluginProtocolTest {
    @Test
    void recognizesTheOfficialBungeeGuard140DescriptorVersionExactly() {
        assertTrue(BungeeGuardCompatibility.isSupportedVersion("1.4-SNAPSHOT"));
        assertTrue(BungeeGuardCompatibility.isSupportedVersion("1.4.0"));
        assertTrue(BungeeGuardCompatibility.isSupportedVersion("1.5.0"));
        assertFalse(BungeeGuardCompatibility.isSupportedVersion("1.3.9"));
        assertFalse(BungeeGuardCompatibility.isSupportedVersion("1.3-SNAPSHOT"));
        assertFalse(BungeeGuardCompatibility.isSupportedVersion("1.5-SNAPSHOT"));
        assertFalse(BungeeGuardCompatibility.isSupportedVersion("unknown"));
    }

    @Test
    void capabilityResponseIsBoundedVersionedAndCompatible() {
        ServerCapabilityProtocol protocol = new ServerCapabilityProtocol();
        ServerCapabilityProtocol.Response response = new ServerCapabilityProtocol.Response(
                SemanticVersion.parse("1.2.0-beta.2"),
                List.of("command-v1", "capabilities-v1", "proxy-refresh-v1"));

        byte[] encoded = protocol.encodeResponse(response);

        assertTrue(encoded.length <= ServerCapabilityProtocol.MAX_RESPONSE_BYTES);
        assertEquals(response, protocol.decodeResponse(encoded));
        assertTrue(protocol.compatibility(
                SemanticVersion.parse("1.1.0"), Set.of("capabilities-v1"), response).compatible());
        assertFalse(protocol.compatibility(
                SemanticVersion.parse("1.3.0"), Set.of("capabilities-v1"), response).compatible());
        assertFalse(protocol.compatibility(
                SemanticVersion.parse("1.1.0"), Set.of("capabilities-v2"), response).compatible());
    }

    @Test
    void capabilityDecoderRejectsOversizeUnknownAndTrailingPayloads() {
        ServerCapabilityProtocol protocol = new ServerCapabilityProtocol();
        assertThrows(ServerCapabilityProtocol.ProtocolException.class,
                () -> protocol.decodeResponse(new byte[ServerCapabilityProtocol.MAX_RESPONSE_BYTES + 1]));
        assertThrows(ServerCapabilityProtocol.ProtocolException.class,
                () -> protocol.decodeResponse(new byte[]{2, 1, '1', 1, 1, 'a'}));
        byte[] valid = protocol.encodeResponse(new ServerCapabilityProtocol.Response(
                SemanticVersion.parse("1.0.0"), List.of("command-v1")));
        assertThrows(ServerCapabilityProtocol.ProtocolException.class,
                () -> protocol.decodeResponse(Arrays.copyOf(valid, valid.length + 1)));
        assertThrows(ServerCapabilityProtocol.ProtocolException.class,
                () -> protocol.decodeResponse(new byte[]{1, 2, (byte) 0xc3, 0x28, 1, 1, 'a'}));
    }

    @Test
    void proxyMessagesRoundTripWithoutIdentityFields() {
        ProxyRefreshProtocol protocol = new ProxyRefreshProtocol();
        byte[] nonce = nonce();
        List<ProxyRefreshProtocol.Message> messages = List.of(
                new ProxyRefreshProtocol.Bind(
                        nonce, List.of("proxy-refresh-v1"), SemanticVersion.parse("1.2.0")),
                new ProxyRefreshProtocol.Dirty(nonce, 1),
                new ProxyRefreshProtocol.State(nonce, 2, Optional.of(
                        new SignedTexturesProperty("signed-value", "signed-signature"))),
                new ProxyRefreshProtocol.State(nonce, 3, Optional.empty()),
                new ProxyRefreshProtocol.Refresh(nonce, 4));

        for (ProxyRefreshProtocol.Message message : messages) {
            byte[] encoded = protocol.encode(message);
            assertTrue(encoded.length <= ProxyRefreshProtocol.MAX_PAYLOAD_BYTES);
            ProxyRefreshProtocol.Message decoded = protocol.decode(encoded);
            assertEquals(message.type(), decoded.type());
            assertArrayEquals(message.nonce(), decoded.nonce());
        }
    }

    @Test
    void relayFenceRejectsStaleNonceAndRevision() {
        RelayRevisionFence fence = new RelayRevisionFence();
        byte[] nonce = nonce();
        byte[] other = nonce();
        other[0]++;

        fence.bind(nonce);

        assertFalse(fence.acceptDirty(other, 1));
        assertTrue(fence.acceptDirty(nonce, 1));
        assertTrue(fence.isDirty());
        assertFalse(fence.acceptDirty(nonce, 1));
        assertTrue(fence.acceptState(nonce, 1));
        assertFalse(fence.acceptState(nonce, 1));
        assertFalse(fence.acceptDirty(nonce, 0));
        assertTrue(fence.acceptDirty(nonce, 2));
        assertEquals(2, fence.revision());
        fence.clear();
        assertFalse(fence.acceptDirty(nonce, 3));
    }

    @Test
    void bungeeSwitchCarriesPublishedRevisionAcrossANewConnectionFence() {
        RelayRevisionFence fence = new RelayRevisionFence();
        byte[] lobbyNonce = nonce();
        byte[] targetNonce = nonce();
        targetNonce[0]++;

        fence.bind(lobbyNonce);
        assertTrue(fence.acceptDirty(lobbyNonce, 7));
        assertTrue(fence.acceptState(lobbyNonce, 7));

        fence.bind(targetNonce);
        assertFalse(fence.acceptDirty(lobbyNonce, 7));
        assertTrue(fence.acceptDirty(targetNonce, 7));
        assertTrue(fence.acceptState(targetNonce, 7));
    }

    @Test
    void semanticVersionOrdersPrereleasesBeforeStable() {
        assertTrue(SemanticVersion.parse("1.0.0-alpha.2")
                .compareTo(SemanticVersion.parse("1.0.0-beta.1")) < 0);
        assertTrue(SemanticVersion.parse("1.0.0-beta.1")
                .compareTo(SemanticVersion.parse("1.0.0")) < 0);
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("v1.0.0"));
    }

    @Test
    void exactSelectorLoadsOneExactLeafLazilyAndFailsClosed() {
        AtomicInteger creations = new AtomicInteger();
        ServerRuntimeIdentity paper1201 = new ServerRuntimeIdentity(
                "1.20.1",
                ServerRuntimeIdentity.Family.PAPER,
                ServerRuntimeIdentity.ThreadingModel.CLASSIC);
        ExactAdapterSelector<String> selector = new ExactAdapterSelector<>(Map.of(
                paper1201, () -> "adapter-" + creations.incrementAndGet()));

        ExactAdapterSelector.Selection<String> selected = selector.select(paper1201);
        assertTrue(selected.supported());
        assertEquals(0, creations.get());
        assertEquals("adapter-1", selected.load());
        assertEquals("adapter-1", selected.load());
        assertEquals(1, creations.get());

        ExactAdapterSelector.Selection<String> unsupported = selector.select(
                new ServerRuntimeIdentity(
                        "1.21.1",
                        ServerRuntimeIdentity.Family.PAPER,
                        ServerRuntimeIdentity.ThreadingModel.CLASSIC));
        assertFalse(unsupported.supported());
        assertThrows(UnsupportedOperationException.class, unsupported::load);
        assertThrows(IllegalArgumentException.class, () -> new ServerRuntimeIdentity(
                "1.20.1",
                ServerRuntimeIdentity.Family.PAPER,
                ServerRuntimeIdentity.ThreadingModel.REGIONIZED));
    }

    private static byte[] nonce() {
        byte[] result = new byte[ProxyRefreshProtocol.NONCE_BYTES];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) (index + 1);
        }
        return result;
    }
}

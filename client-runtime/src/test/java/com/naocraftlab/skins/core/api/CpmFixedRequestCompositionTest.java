package com.naocraftlab.skins.core.api;

import com.naocraftlab.skins.client.CpmModelPublication;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.runtime.composition.ClientCompositionRoot;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class CpmFixedRequestCompositionTest {
    @Test
    void rootPublicationUsesRealFixedAdmissionForOpaqueSessionAndJoin() throws Exception {
        String opaque = "synthetic/session+value=";
        String name = "Лес+ é 😀 (V2) ".repeat(400) + ".cpmproject";
        var effects = new ArrayList<String>();
        var resolutions = new AtomicInteger();
        var reads = new AtomicInteger();
        var transport = new PinnedHttpsTransport() {
            @Override Response fixed(URI uri, String host, List<InetAddress> addresses, Duration timeout,
                    int maximum, String method, Map<String, String> headers, byte[] body,
                    java.util.function.BooleanSupplier beforeSend) throws java.io.IOException {
                assertTrue(beforeSend.getAsBoolean());
                if (beforeSend instanceof PublicHttpsImageFetcher.FixedRequestScope scope) assertTrue(scope.beforeSend());
                assertEquals(List.of(InetAddress.getByName("8.8.8.8")), addresses);
                String path = uri.getPath();
                effects.add(path);
                if (path.equals("/session/minecraft/join")) {
                    assertFalse(headers.containsKey("File-Name"));
                    assertEquals("sessionserver.mojang.com", host);
                    assertEquals("POST", method);
                    assertFalse(headers.containsKey("Session"));
                    return new Response(204, uri, Map.of(), new byte[0]);
                }
                assertEquals("paste.tom5454.com", host);
                if (!path.equals("/api/connect")) assertEquals(opaque, headers.get("Session"));
                assertFalse(new String(body, StandardCharsets.US_ASCII).contains("synthetic-non-secret-placeholder"));
                if (path.equals("/api/upload")) {
                    assertEquals("POST", method);
                    assertEquals(name, headers.get("File-Name"));
                    assertEquals("8", headers.get("Content-Length"));
                    assertArrayEquals("AAECAwQF".getBytes(StandardCharsets.US_ASCII), body);
                } else {
                    assertFalse(headers.containsKey("File-Name"));
                    assertEquals("GET", method);
                    assertEquals(0, body.length);
                }
                String response = switch (path) {
                    case "/api/connect" -> "{\"id\":\"" + opaque + "\",\"key\":\"AQID\"}";
                    case "/api/session" -> "{}";
                    case "/api/list" -> "{\"maxSize\":64,\"maxFiles\":1,\"files\":[]}";
                    case "/api/upload" -> "{\"id\":\"synthetic-public-id\"}";
                    default -> throw new AssertionError("Unexpected fixed endpoint");
                };
                return new Response(200, uri, Map.of(), response.getBytes(StandardCharsets.US_ASCII));
            }
        };
        var admission = new SafeRemotePngFetcher(transport, new PngValidator(), host -> {
            resolutions.incrementAndGet();
            return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
        });
        var fetcher = new PublicHttpsImageFetcher(admission, transport, System::nanoTime);
        var sessions = new GameSessionTokenSource() {
            public SessionIdentity currentSession() {
                return new SessionIdentity(new UUID(0, 1), "Player");
            }
            public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                throw new AssertionError("Expected scoped session callback");
            }
            public <T, E extends Exception> T withSession(SessionRequest<T, E> request) throws E {
                reads.incrementAndGet();
                return request.execute(currentSession(), "synthetic-non-secret-placeholder");
            }
        };
        var publication = ClientCompositionRoot.cpmPublication(sessions,
                com.naocraftlab.skins.runtime.CpmPastePublicationAdapter.fixedExchange(fetcher), System::nanoTime);
        var result = publication.publish(new CpmModelPublication.Request(new byte[]{0, 1, 2, 3, 4, 5}, 1, name), () -> true);
        assertEquals(CpmModelPublication.Status.CONFIRMED, result.status());
        assertEquals(CpmModelPublication.Reason.NONE, result.reason());
        assertEquals(Optional.of("synthetic-public-id"), result.pasteId());
        assertEquals(List.of("/api/connect", "/session/minecraft/join", "/api/session", "/api/list", "/api/upload"), effects);
        assertEquals(5, resolutions.get());
        assertEquals(1, reads.get());
    }
}

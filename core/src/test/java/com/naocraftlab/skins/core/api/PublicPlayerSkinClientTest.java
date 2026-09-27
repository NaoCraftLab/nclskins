package com.naocraftlab.skins.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PublicPlayerSkinClientTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";
    private static final String HASH = "a".repeat(64);

    @Test
    void acceptsOnlyVerifierApprovedSignedTexturePayload() throws Exception {
        String payload = Base64.getEncoder().encodeToString(("""
                {"profileId":"%s","profileName":"Player","textures":{"SKIN":{
                  "url":"http://textures.minecraft.net/texture/%s","metadata":{"model":"slim"}
                }}}
                """).formatted(ID, HASH).getBytes(StandardCharsets.UTF_8));
        try (Fixture fixture = new Fixture(payload)) {
            PublicPlayerSkinClient client = fixture.client((value, signature) ->
                    "approved".equals(signature) ? Optional.of(value) : Optional.empty());

            var result = client.lookup("Player");

            assertEquals("Player", result.canonicalName());
            assertEquals(SkinVariant.SLIM, result.variant());
            assertEquals(URI.create("https://textures.minecraft.net/texture/" + HASH),
                    ((PublicPlayerSkinClient.VerifiedPublicTexture) result.texture().orElseThrow()).uri());
            assertTrue(result.defaultSkinId().isEmpty());
            assertEquals(1, fixture.lookupCalls.get());
            assertEquals(1, fixture.sessionCalls.get());
        }
    }

    @Test
    void rejectsTexturePayloadWhenNativeSignatureVerificationFails() throws Exception {
        String payload = Base64.getEncoder().encodeToString(("""
                {"profileId":"%s","profileName":"Player","textures":{}}
                """).formatted(ID).getBytes(StandardCharsets.UTF_8));
        try (Fixture fixture = new Fixture(payload)) {
            PublicSkinImportException failure = assertThrows(PublicSkinImportException.class,
                    () -> fixture.client((value, signature) -> Optional.empty()).lookup(ID));

            assertEquals(PublicSkinImportException.Code.PROFILE_REJECTED, failure.code());
            assertEquals(0, fixture.lookupCalls.get());
            assertEquals(1, fixture.sessionCalls.get());
        }
    }

    @Test
    void retryAfterBlocksRepeatedCallsWithoutAnotherRequest() throws Exception {
        try (Fixture fixture = new Fixture("ignored", true)) {
            PublicPlayerSkinClient client = fixture.client((value, signature) -> Optional.empty());

            PublicSkinImportException first = assertThrows(
                    PublicSkinImportException.class, () -> client.lookup("Player"));
            PublicSkinImportException second = assertThrows(
                    PublicSkinImportException.class, () -> client.lookup("Player"));

            assertEquals(PublicSkinImportException.Code.RATE_LIMITED, first.code());
            assertEquals(PublicSkinImportException.Code.RATE_LIMITED, second.code());
            assertEquals(1, fixture.lookupCalls.get());
            assertEquals(0, fixture.sessionCalls.get());
        }
    }

    @Test
    void uuidDefaultAndInvalidIdentifierKeepTheirOutcomes() throws Exception {
        String payload = Base64.getEncoder().encodeToString(("{\"profileId\":\"" + ID
                + "\",\"profileName\":\"Player\",\"textures\":{}}").getBytes(StandardCharsets.UTF_8));
        try (Fixture fixture = new Fixture(payload)) {
            var client = fixture.client((value, signature) -> Optional.of(value));
            var result = client.lookup("01234567-89ab-cdef-0123-456789abcdef");
            var expected = com.naocraftlab.skins.core.model.AccountDefaultSkin.forProfile(
                    java.util.UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"));
            assertEquals(expected.skinId(), result.defaultSkinId().orElseThrow());
            assertEquals(expected.variant(), result.variant());
            assertEquals("Player", result.canonicalName());
            assertEquals(0, fixture.lookupCalls.get());
            assertEquals(PublicSkinImportException.Code.INVALID_IDENTIFIER,
                    assertThrows(PublicSkinImportException.class, () -> client.lookup("invalid query!")).code());
            assertEquals(1, fixture.sessionCalls.get());
        }
    }

    @Test
    void rejectsMismatchedIdentityAndUntrustedTextureReferences() throws Exception {
        for (String texture : new String[] {
                "https://example.com/texture/" + HASH,
                "https://textures.minecraft.net/texture/" + HASH + "?query=1",
                "https://textures.minecraft.net/texture/not-a-hash"}) {
            String payload = Base64.getEncoder().encodeToString(("""
                    {"profileId":"%s","profileName":"Player","textures":{"SKIN":{"url":"%s"}}}
                    """).formatted(ID, texture).getBytes(StandardCharsets.UTF_8));
            try (Fixture fixture = new Fixture(payload)) {
                assertEquals(PublicSkinImportException.Code.PROFILE_REJECTED,
                        assertThrows(PublicSkinImportException.class,
                                () -> fixture.client((value, signature) -> Optional.of(value)).lookup(ID)).code());
            }
        }
        for (String identity : new String[] {
                "\"profileId\":\"ffffffffffffffffffffffffffffffff\",\"profileName\":\"Player\"",
                "\"profileId\":\"" + ID + "\",\"profileName\":\"Different\""}) {
            String payload = Base64.getEncoder().encodeToString(("{" + identity + ",\"textures\":{}}")
                    .getBytes(StandardCharsets.UTF_8));
            try (Fixture fixture = new Fixture(payload)) {
                assertEquals(PublicSkinImportException.Code.PROFILE_REJECTED,
                        assertThrows(PublicSkinImportException.class,
                                () -> fixture.client((value, signature) -> Optional.of(value)).lookup(ID)).code());
            }
        }
    }

    @Test
    void lookupStatusDistinctionsSurviveThePort() throws Exception {
        for (int status : new int[]{204, 404, 429, 500, 302}) {
            for (boolean byUuid : new boolean[]{false, true}) {
                try (Fixture fixture = new Fixture("ignored")) {
                    if (byUuid) fixture.sessionStatus = status;
                    else fixture.lookupStatus = status;
                    var failure = assertThrows(PublicSkinImportException.class, () ->
                            fixture.client((value, signature) -> Optional.of(value)).lookup(byUuid ? ID : "Player"));
                    assertEquals(switch (status) {
                        case 204, 404 -> PublicSkinImportException.Code.PROFILE_NOT_FOUND;
                        case 429 -> PublicSkinImportException.Code.RATE_LIMITED;
                        default -> PublicSkinImportException.Code.SERVICE_UNAVAILABLE;
                    }, failure.code());
                }
            }
        }
    }

    @Test
    void unavailableTransportIsNotNotFound() throws Exception {
        try (Fixture fixture = new Fixture("ignored")) {
            var client = fixture.client((value, signature) -> Optional.of(value));
            fixture.server.stop(0);
            assertEquals(PublicSkinImportException.Code.NETWORK_FAILURE,
                    assertThrows(PublicSkinImportException.class, () -> client.lookup("Player")).code());
        }
    }

    static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private int lookupStatus = 200;
        private int sessionStatus = 200;
        private final AtomicInteger lookupCalls = new AtomicInteger();
        private final AtomicInteger sessionCalls = new AtomicInteger();

        Fixture(String payload) throws IOException {
            this(payload, false);
        }

        Fixture(String payload, boolean rateLimited) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/minecraft/profile/lookup", exchange -> {
                lookupCalls.incrementAndGet();
                if (lookupStatus != 200) {
                    exchange.sendResponseHeaders(lookupStatus, -1);
                    exchange.close();
                    return;
                }
                if (rateLimited) {
                    exchange.getResponseHeaders().set("Retry-After", "120");
                    exchange.sendResponseHeaders(429, -1);
                    exchange.close();
                } else {
                    respond(exchange, "{\"id\":\"" + ID + "\",\"name\":\"Player\"}");
                }
            });
            server.createContext("/session/minecraft/profile", exchange -> {
                sessionCalls.incrementAndGet();
                if (sessionStatus != 200) {
                    exchange.sendResponseHeaders(sessionStatus, -1);
                    exchange.close();
                    return;
                }
                respond(exchange, "{\"id\":\"" + ID + "\",\"name\":\"Player\",\"properties\":[{"
                        + "\"name\":\"textures\",\"value\":\"" + payload
                        + "\",\"signature\":\"approved\"}]}");
            });
            server.start();
        }

        PublicPlayerSkinClient client(com.naocraftlab.skins.client.SignedTextureVerifier verifier) {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            return new PublicPlayerSkinClient(HttpClient.newHttpClient(), verifier, base, base);
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void respond(HttpExchange exchange, String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}

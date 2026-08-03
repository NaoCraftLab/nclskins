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

            PublicPlayerSkinClient.Result result = client.lookup("Player");

            assertEquals("Player", result.canonicalName());
            assertEquals(SkinVariant.SLIM, result.variant());
            assertEquals(URI.create("https://textures.minecraft.net/texture/" + HASH),
                    result.textureUri().orElseThrow());
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

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger lookupCalls = new AtomicInteger();
        private final AtomicInteger sessionCalls = new AtomicInteger();

        Fixture(String payload) throws IOException {
            this(payload, false);
        }

        Fixture(String payload, boolean rateLimited) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/minecraft/profile/lookup", exchange -> {
                lookupCalls.incrementAndGet();
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

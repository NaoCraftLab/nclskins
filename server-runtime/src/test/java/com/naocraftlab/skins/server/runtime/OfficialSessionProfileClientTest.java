package com.naocraftlab.skins.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.server.ServerPlayerIdentity;
import com.naocraftlab.skins.server.SignedTexturesProperty;
import com.naocraftlab.skins.server.ConnectionKey;
import com.naocraftlab.skins.server.ConnectionSnapshot;
import com.naocraftlab.skins.server.IdentityAssurance;
import com.naocraftlab.skins.server.OfficialProfileResolver;
import com.naocraftlab.skins.server.TextureAppearance;
import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class OfficialSessionProfileClientTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-31T10:15:30Z"), ZoneOffset.UTC);

    private final AtomicReference<Responder> responder = new AtomicReference<>();
    private final AtomicReference<URI> requestUri = new AtomicReference<>();
    private HttpServer server;
    private ExecutorService serverExecutor;
    private ServerPlayerIdentity identity;
    private String undashedId;

    @BeforeEach
    void startServer() throws IOException {
        identity = new ServerPlayerIdentity(
                UUID.nameUUIDFromBytes("http-profile-test".getBytes(StandardCharsets.UTF_8)),
                "TestPlayer");
        undashedId = identity.profileId().toString().replace("-", "");
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/session/minecraft/profile/", exchange -> {
            requestUri.set(exchange.getRequestURI());
            try {
                responder.get().respond(exchange);
            } catch (IOException ignored) {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void resolvesOneSignedPropertyThroughTheExactUnsignedFalsePath() {
        respondJson(200, profileJson(
                identity.profileName(),
                "[{\"name\":\"textures\",\"value\":\"value-secret\","
                        + "\"signature\":\"signature-secret\"}]"));

        OfficialSessionProfileClient.Result result = client().fetch(identity);

        assertEquals(OfficialSessionProfileClient.Result.Status.RESOLVED, result.status());
        OfficialSessionProfileClient.FetchedProfile profile = result.profile().orElseThrow();
        SignedTexturesProperty textures = profile.textures().orElseThrow();
        assertEquals("value-secret", textures.value());
        assertEquals("signature-secret", textures.signature());
        assertEquals(
                "/session/minecraft/profile/" + undashedId,
                requestUri.get().getPath());
        assertEquals("unsigned=false", requestUri.get().getQuery());
        assertEquals("FetchedProfile[redacted]", profile.toString());
        assertEquals("SignedTexturesProperty[redacted]", textures.toString());
        assertFalse(result.toString().contains(undashedId));
        assertFalse(result.toString().contains("value-secret"));
        assertFalse(result.toString().contains("signature-secret"));
    }

    @Test
    void asynchronousFetchCompletesWithTheSameRedactedResult() {
        respondJson(200, profileJson(
                identity.profileName(),
                "[{\"name\":\"textures\",\"value\":\"value-secret\","
                        + "\"signature\":\"signature-secret\"}]"));

        OfficialSessionProfileClient.Result result =
                client().fetchAsync(identity).toCompletableFuture().join();

        assertEquals(OfficialSessionProfileClient.Result.Status.RESOLVED, result.status());
        assertFalse(result.toString().contains("value-secret"));
    }

    @Test
    void portableResolutionComposesFetchAndSignatureVerification() {
        respondJson(200, profileJson(
                identity.profileName(),
                "[{\"name\":\"textures\",\"value\":\"value-secret\","
                        + "\"signature\":\"signature-secret\"}]"));
        TextureAppearance appearance = TextureAppearance.verified(
                Optional.of("a".repeat(64)),
                Optional.of(TextureAppearance.SkinModel.CLASSIC),
                Optional.empty(),
                Optional.empty());
        OfficialProfileResolutionService resolver = new OfficialProfileResolutionService(
                client(),
                (property, expected) -> {
                    assertEquals(identity, expected);
                    assertEquals("value-secret", property.value());
                    return Optional.of(appearance);
                });

        OfficialProfileResolver.Resolution result = resolver.resolve(connection())
                .toCompletableFuture()
                .join();

        assertEquals(OfficialProfileResolver.Resolution.Status.RESOLVED, result.status());
        assertEquals(appearance, result.profile().orElseThrow().appearance());
        assertTrue(result.profile().orElseThrow().textures().isPresent());
    }

    @Test
    void portableResolutionAcceptsDefaultAndRejectsFailedSignatureVerification() {
        respondJson(200, "{\"id\":\"" + undashedId + "\",\"name\":\"TestPlayer\"}");
        OfficialProfileResolutionService defaultResolver = new OfficialProfileResolutionService(
                client(),
                (property, expected) -> {
                    throw new AssertionError("Default profile must not invoke signature verifier");
                });
        OfficialProfileResolver.Resolution accountDefault = defaultResolver.resolve(connection())
                .toCompletableFuture()
                .join();
        assertTrue(accountDefault.profile().orElseThrow().appearance().isAccountDefault());

        respondJson(200, profileJson(
                identity.profileName(),
                "[{\"name\":\"textures\",\"value\":\"signed-empty\","
                        + "\"signature\":\"signed-empty-signature\"}]"));
        OfficialProfileResolutionService signedDefaultResolver =
                new OfficialProfileResolutionService(
                        client(),
                        (property, expected) -> Optional.of(TextureAppearance.accountDefault()));
        VerifiedOfficialProfile signedAccountDefault = signedDefaultResolver.resolve(connection())
                .toCompletableFuture()
                .join()
                .profile()
                .orElseThrow();
        assertTrue(signedAccountDefault.appearance().isAccountDefault());
        assertTrue(signedAccountDefault.textures().isEmpty());

        respondJson(200, profileJson(
                identity.profileName(),
                "[{\"name\":\"textures\",\"value\":\"value-secret\","
                        + "\"signature\":\"signature-secret\"}]"));
        OfficialProfileResolutionService rejecting = new OfficialProfileResolutionService(
                client(),
                (property, expected) -> Optional.empty());
        assertEquals(
                OfficialProfileResolver.Resolution.Status.REJECTED,
                rejecting.resolve(connection()).toCompletableFuture().join().status());
    }

    @Test
    void acceptsAValidAccountDefaultWithNoTextureProperty() {
        respondJson(200, "{\"id\":\"" + undashedId + "\",\"name\":\"TestPlayer\"}");
        assertTrue(client().fetch(identity).profile().orElseThrow().textures().isEmpty());

        respondJson(200, profileJson(identity.profileName(), "[]"));
        assertTrue(client().fetch(identity).profile().orElseThrow().textures().isEmpty());
    }

    @Test
    void rejectsIdentityPropertyAndJsonShapeViolations() {
        String otherId = (undashedId.charAt(0) == '0' ? "1" : "0") + undashedId.substring(1);
        String texture = "{\"name\":\"textures\",\"value\":\"v\",\"signature\":\"s\"}";
        for (String invalid : new String[] {
            "{",
            "[]",
            profileJson(identity.profileName(), "{}"),
            "{\"id\":\"" + otherId + "\",\"name\":\"TestPlayer\",\"properties\":[]}",
            profileJson("OtherPlayer", "[]"),
            profileJson(identity.profileName(), "[" + texture + ',' + texture + "]"),
            profileJson(
                    identity.profileName(),
                    "[{\"name\":\"textures\",\"value\":\"v\"}]")
        }) {
            respondJson(200, invalid);
            assertEquals(
                    OfficialSessionProfileClient.Result.Status.REJECTED,
                    client().fetch(identity).status());
        }

        responder.set(exchange -> send(
                exchange,
                200,
                new byte[] {(byte) 0xc3, (byte) 0x28},
                Map.of("Content-Type", "application/json")));
        assertEquals(
                OfficialSessionProfileClient.Result.Status.REJECTED,
                client().fetch(identity).status());
    }

    @Test
    void mapsTransientAndPermanentStatusesAndNeverFollowsRedirects() {
        for (int status : new int[] {408, 500, 503, 599}) {
            respond(status, Map.of(), new byte[0]);
            assertEquals(
                    OfficialSessionProfileClient.Result.Status.TRANSIENT_FAILURE,
                    client().fetch(identity).status());
        }
        for (int status : new int[] {204, 400, 401, 403, 404}) {
            respond(status, Map.of(), new byte[0]);
            assertEquals(
                    OfficialSessionProfileClient.Result.Status.REJECTED,
                    client().fetch(identity).status());
        }

        AtomicInteger followed = new AtomicInteger();
        server.createContext("/redirect-target", exchange -> {
            followed.incrementAndGet();
            send(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8), Map.of());
        });
        respond(
                302,
                Map.of("Location", endpoint().resolve("../../../../redirect-target").toString()),
                new byte[0]);
        assertEquals(
                OfficialSessionProfileClient.Result.Status.REJECTED,
                client().fetch(identity).status());
        assertEquals(0, followed.get());
    }

    @Test
    void parsesRetryAfterDeltaAndHttpDateWithSafeFallback() {
        respond(429, Map.of("Retry-After", "17"), new byte[0]);
        assertEquals(
                Duration.ofSeconds(17),
                client().fetch(identity).retryAfter().orElseThrow());

        String retryDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(FIXED_CLOCK.instant().plusSeconds(42), ZoneOffset.UTC));
        respond(429, Map.of("Retry-After", retryDate), new byte[0]);
        assertEquals(
                Duration.ofSeconds(42),
                client().fetch(identity).retryAfter().orElseThrow());

        respond(429, Map.of("Retry-After", "not-a-date"), new byte[0]);
        assertEquals(
                Duration.ofSeconds(60),
                client().fetch(identity).retryAfter().orElseThrow());
        respond(429, Map.of(), new byte[0]);
        assertEquals(
                Duration.ofSeconds(60),
                client().fetch(identity).retryAfter().orElseThrow());
    }

    @Test
    void rejectsOversizedBodiesWithoutRetainingTheirContent() {
        byte[] oversized = "x".repeat(65).getBytes(StandardCharsets.UTF_8);
        respond(200, Map.of("Content-Type", "application/json"), oversized);

        OfficialSessionProfileClient.Result result = client(64, Duration.ofSeconds(2)).fetch(identity);

        assertEquals(OfficialSessionProfileClient.Result.Status.REJECTED, result.status());
        assertFalse(result.toString().contains("xxxx"));
    }

    @Test
    void requestTimeoutIsTransientAndDoesNotExposeTheRequestUri() {
        responder.set(exchange -> {
            exchange.sendResponseHeaders(200, 2L);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(250L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.getResponseBody().write('}');
            exchange.close();
        });

        OfficialSessionProfileClient.Result result =
                client(1_024, Duration.ofMillis(50)).fetch(identity);

        assertEquals(
                OfficialSessionProfileClient.Result.Status.TRANSIENT_FAILURE,
                result.status());
        assertFalse(result.toString().contains(undashedId));
    }

    private OfficialSessionProfileClient client() {
        return client(4_096, Duration.ofSeconds(2));
    }

    private ConnectionSnapshot connection() {
        return new ConnectionSnapshot(
                new ConnectionKey(identity.profileId(), 1L),
                identity.profileName(),
                IdentityAssurance.ONLINE);
    }

    private OfficialSessionProfileClient client(int maxResponseBytes, Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new OfficialSessionProfileClient(
                httpClient,
                endpoint(),
                FIXED_CLOCK,
                timeout,
                maxResponseBytes);
    }

    private URI endpoint() {
        return URI.create(
                "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ':'
                        + server.getAddress().getPort() + "/session/minecraft/profile/");
    }

    private void respondJson(int status, String json) {
        respond(
                status,
                Map.of("Content-Type", "application/json"),
                json.getBytes(StandardCharsets.UTF_8));
    }

    private void respond(int status, Map<String, String> headers, byte[] body) {
        responder.set(exchange -> send(exchange, status, body, headers));
    }

    private String profileJson(String name, String properties) {
        return "{\"id\":\"" + undashedId + "\",\"name\":\"" + name
                + "\",\"properties\":" + properties + '}';
    }

    private static void send(
            HttpExchange exchange,
            int status,
            byte[] body,
            Map<String, String> headers) throws IOException {
        headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }
}

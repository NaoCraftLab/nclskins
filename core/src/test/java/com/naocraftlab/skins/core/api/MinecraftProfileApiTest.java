package com.naocraftlab.skins.core.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.core.model.RemoteAssetState;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.test.TestPng;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MinecraftProfileApiTest {
    private static final String TOKEN = "test-access-token-never-persist";
    private static final UUID PROFILE_ID = UUID.fromString("12345678-1234-5678-9abc-def012345678");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void defaultConstructorUsesAllowlistedOfficialEndpoint() {
        new MinecraftProfileApi();
    }

    @Test
    void readsProfileWithRequestScopedBearer() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        server = server(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("/minecraft/profile", exchange.getRequestURI().getPath());
            respond(exchange, 200, profileJson(true));
        });
        MinecraftProfileApi api = api(Duration.ofSeconds(2), duration -> {});

        var profile = api.getProfile(TOKEN);

        assertEquals(PROFILE_ID, profile.id());
        assertEquals("Player", profile.name());
        assertEquals(SkinVariant.SLIM, profile.activeSkin().orElseThrow().variant());
        assertEquals(RemoteAssetState.ACTIVE, profile.activeCape().orElseThrow().state());
        assertEquals(2, profile.capes().size());
        assertEquals("Bearer " + TOKEN, authorization.get());
        assertFalse(profile.toString().contains(TOKEN));
    }

    @Test
    void boundedProfileObservationNeverRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 503, "provider body must stay private");
        });
        MinecraftProfileApi api = api(Duration.ofSeconds(2), duration -> {});

        ProfileApiException exception = assertThrows(
                ProfileApiException.class,
                () -> api.getProfileOnce(TOKEN, Duration.ofMillis(250)));

        assertEquals(ApiFailureKind.SERVER_ERROR, exception.kind());
        assertEquals(1, calls.get());
        assertFalse(exception.toString().contains(TOKEN));
    }

    @Test
    void uploadUsesMultipartAndMutationIsNeverRetriedOnServerError() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        server = server(exchange -> {
            calls.incrementAndGet();
            requestBody.set(exchange.getRequestBody().readAllBytes());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            respond(exchange, 500, "provider body must stay private");
        });
        MinecraftProfileApi api = api(Duration.ofSeconds(2), duration -> {});

        ProfileApiException exception = assertThrows(
                ProfileApiException.class,
                () -> api.uploadSkin(TOKEN, SkinVariant.SLIM, TestPng.create(64, 64)));

        assertEquals(ApiFailureKind.SERVER_ERROR, exception.kind());
        assertTrue(exception.mutationMayHaveApplied());
        assertEquals(1, calls.get());
        assertTrue(contentType.get().startsWith("multipart/form-data; boundary="));
        String bodyText = new String(requestBody.get(), StandardCharsets.ISO_8859_1);
        assertTrue(bodyText.contains("name=\"variant\""));
        assertTrue(bodyText.contains("\r\nslim\r\n"));
        assertTrue(bodyText.contains("filename=\"skin.png\""));
        assertFalse(exception.toString().contains("provider body"));
        assertFalse(exception.toString().contains(TOKEN));
    }

    @Test
    void retriesBoundedGetButNotAuthenticationFailures() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<Duration> sleeps = new ArrayList<>();
        server = server(exchange -> {
            if (calls.incrementAndGet() < 3) {
                respond(exchange, 503, "temporary");
            } else {
                respond(exchange, 200, profileJson(false));
            }
        });
        MinecraftProfileApi api = api(Duration.ofSeconds(2), sleeps::add);

        assertEquals(PROFILE_ID, api.getProfile(TOKEN).id());
        assertEquals(3, calls.get());
        assertEquals(List.of(Duration.ofMillis(1), Duration.ofMillis(2)), sleeps);
    }

    @Test
    void respectsLongRetryAfterByReturningCooldownWithoutEarlyRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<Duration> sleeps = new ArrayList<>();
        server = server(exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "120");
            respond(exchange, 429, "slow down");
        });
        MinecraftProfileApi api = api(Duration.ofSeconds(2), sleeps::add);

        ProfileApiException exception = assertThrows(ProfileApiException.class, () -> api.getProfile(TOKEN));

        assertEquals(ApiFailureKind.RATE_LIMITED, exception.kind());
        assertEquals(Duration.ofSeconds(120), exception.retryAfter().orElseThrow());
        assertEquals(1, calls.get());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void doesNotRetryImmediate429InsideTheSameGet() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = server(exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                respond(exchange, 429, "slow down");
            } else {
                respond(exchange, 200, profileJson(false));
            }
        });
        MinecraftProfileApi api = api(Duration.ofSeconds(2), duration -> {});

        ProfileApiException limited = assertThrows(ProfileApiException.class, () -> api.getProfile(TOKEN));
        assertEquals(ApiFailureKind.RATE_LIMITED, limited.kind());
        assertEquals(1, calls.get());


        api.getProfile(TOKEN);
        assertEquals(2, calls.get());
    }

    @Test
    void missingRetryAfterUsesLocalCooldownAndDoesNotSendAgain() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 429, "slow down");
        });
        MinecraftProfileApi api = api(Duration.ofSeconds(2), duration -> {});

        ProfileApiException first = assertThrows(ProfileApiException.class, () -> api.getProfile(TOKEN));
        ProfileApiException blocked = assertThrows(ProfileApiException.class, () -> api.getProfile(TOKEN));
        ProfileApiException blockedMutation = assertThrows(
                ProfileApiException.class,
                () -> api.activateCape(TOKEN, "cape-a"));

        assertEquals(Duration.ofSeconds(60), first.retryAfter().orElseThrow());
        assertEquals(ApiFailureKind.RATE_LIMITED, blocked.kind());
        assertEquals(ApiFailureKind.RATE_LIMITED, blockedMutation.kind());
        assertEquals(Duration.ofSeconds(60), blocked.retryAfter().orElseThrow());
        assertEquals(1, calls.get());
    }

    @Test
    void redacts401ResponseAndLatchesSessionMeaning() throws Exception {
        server = server(exchange -> respond(exchange, 401, "body contains " + TOKEN));
        MinecraftProfileApi api = api(Duration.ofSeconds(2), duration -> {});

        ProfileApiException exception = assertThrows(ProfileApiException.class, () -> api.getProfile(TOKEN));

        assertTrue(exception.sessionExpired());
        assertEquals(401, exception.statusCode().orElseThrow());
        assertFalse(exception.getMessage().contains(TOKEN));
        assertFalse(exception.toString().contains("body contains"));
    }

    @Test
    void mapsProfile403And404WithoutIncludingProviderBodies() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = server(exchange -> {
            if (calls.getAndIncrement() == 0) {
                respond(exchange, 403, "private forbidden diagnostics");
            } else {
                respond(exchange, 404, "private missing diagnostics");
            }
        });
        MinecraftProfileApi api = api(Duration.ofSeconds(2), duration -> {});

        ProfileApiException forbidden = assertThrows(
                ProfileApiException.class,
                () -> api.getProfile(TOKEN));
        ProfileApiException missing = assertThrows(
                ProfileApiException.class,
                () -> api.getProfile(TOKEN));

        assertEquals(ApiFailureKind.FORBIDDEN, forbidden.kind());
        assertEquals(ApiFailureKind.NOT_FOUND, missing.kind());
        assertFalse(forbidden.toString().contains("private forbidden"));
        assertFalse(missing.toString().contains("private missing"));
    }

    @Test
    void accepts204MutationAndUsesExactCapeEndpoint() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        server = server(exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            respond(exchange, 204, "");
        });
        MinecraftProfileApi api = api(Duration.ofSeconds(2), duration -> {});

        api.deactivateCape(TOKEN);

        assertEquals("DELETE", method.get());
        assertEquals("/minecraft/profile/capes/active", path.get());
    }

    @Test
    void mutationTimeoutIsUnknownAndNotRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = server(exchange -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            try {
                respond(exchange, 204, "");
            } catch (IOException clientGone) {
                exchange.close();
            }
        });
        MinecraftProfileApi api = api(Duration.ofMillis(30), duration -> {});

        ProfileApiException exception = assertThrows(
                ProfileApiException.class,
                () -> api.deactivateCape(TOKEN));

        assertEquals(ApiFailureKind.NETWORK, exception.kind());
        assertTrue(exception.mutationMayHaveApplied());
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsRedirectWithoutFollowingIt() throws Exception {
        server = server(exchange -> {
            exchange.getResponseHeaders().add("Location", "https://evil.example/steal");
            respond(exchange, 302, "");
        });
        MinecraftProfileApi api = api(Duration.ofSeconds(2), duration -> {});

        ProfileApiException exception = assertThrows(ProfileApiException.class, () -> api.getProfile(TOKEN));
        assertEquals(ApiFailureKind.REDIRECT_REJECTED, exception.kind());
    }

    @Test
    void skipsNonAllowlistedTextureUrlsWithoutExposingThem() throws Exception {
        server = server(exchange -> respond(
                exchange,
                200,
                profileJson(false).replace("textures.minecraft.net", "evil.example")));
        MinecraftProfileApi api = api(Duration.ofSeconds(2), duration -> {});

        var profile = api.getProfile(TOKEN);

        assertTrue(profile.skins().isEmpty());
        assertTrue(profile.capes().isEmpty());
        assertFalse(profile.toString().contains("evil.example"));
    }

    @Test
    void acceptsMissingNullOrMalformedAssetCollectionsAsEmpty() throws Exception {
        List<String> bodies = List.of(
                """
                        {
                          "id":"12345678123456789abcdef012345678",
                          "name":"Player",
                          "skins":null,
                          "profileActions":null
                        }
                        """,
                """
                        {
                          "id":"12345678123456789abcdef012345678",
                          "name":"Player",
                          "skins":{},
                          "capes":false,
                          "profileActions":[]
                        }
                        """);
        AtomicInteger calls = new AtomicInteger();
        server = server(exchange -> respond(exchange, 200, bodies.get(calls.getAndIncrement())));
        MinecraftProfileApi api = api(Duration.ofSeconds(2), duration -> {});

        var missingAndNullProfile = api.getProfile(TOKEN);
        var malformedCollectionsProfile = api.getProfile(TOKEN);

        assertTrue(missingAndNullProfile.skins().isEmpty());
        assertTrue(missingAndNullProfile.capes().isEmpty());
        assertTrue(missingAndNullProfile.profileActions().isEmpty());
        assertTrue(malformedCollectionsProfile.skins().isEmpty());
        assertTrue(malformedCollectionsProfile.capes().isEmpty());
    }

    @Test
    void isolatesMalformedAssetsAndNormalizesAllowlistedHttpTextures() throws Exception {
        server = server(exchange -> respond(exchange, 200, """
                {
                  "id":"12345678123456789abcdef012345678",
                  "name":"Player",
                  "skins":[
                    false,
                    {"id":"missing-url","state":"ACTIVE","variant":"CLASSIC"},
                    {"id":"object-state","state":{},"url":"https://textures.minecraft.net/texture/object-state","variant":"CLASSIC"},
                    {"id":"unknown-state","state":"FUTURE","url":"https://textures.minecraft.net/texture/unknown","variant":"CLASSIC"},
                    {"id":"untrusted","state":"ACTIVE","url":"https://evil.example/texture/stolen","variant":"SLIM"},
                    {"id":"skin-ok","state":"ACTIVE","url":"http://textures.minecraft.net:80/texture/skin-ok?source=profile","variant":"SLIM","alias":{},"textureKey":"ignored-future-field"}
                  ],
                  "capes":[
                    null,
                    {"id":"bad-alias","state":"INACTIVE","url":"https://textures.minecraft.net/texture/bad-alias","alias":{}},
                    {"id":"untrusted-cape","state":"ACTIVE","url":"https://evil.example/texture/cape","alias":"EVIL"},
                    {"id":"cape-ok","state":"ACTIVE","url":"http://textures.minecraft.net/texture/cape-ok","alias":"MIGRATOR"}
                  ],
                  "profileActions":[]
                }
                """));
        MinecraftProfileApi api = api(Duration.ofSeconds(2), duration -> {});

        var profile = api.getProfile(TOKEN);

        assertEquals(1, profile.skins().size());
        assertEquals("skin-ok", profile.skins().get(0).id());
        assertEquals(
                URI.create("https://textures.minecraft.net/texture/skin-ok?source=profile"),
                profile.skins().get(0).textureUri());
        assertTrue(profile.skins().get(0).optionalAlias().isEmpty());
        assertEquals(2, profile.capes().size());
        assertEquals("bad-alias", profile.capes().get(0).id());
        assertTrue(profile.capes().get(0).optionalAlias().isEmpty());
        assertEquals("cape-ok", profile.capes().get(1).id());
        assertEquals(
                URI.create("https://textures.minecraft.net/texture/cape-ok"),
                profile.capes().get(1).textureUri());
        assertFalse(profile.toString().contains("evil.example"));
    }

    @Test
    void parsesProfileActionsFailClosedWithoutExposingUnknownObjectData() throws Exception {
        List<String> bodies = List.of(
                profileWithoutActions(),
                profileWithActions("null"),
                profileWithActions("[]"),
                profileWithActions("{}"),
                profileWithActions("{\"provider-private-action\":false}"),
                profileWithActions("false"),
                profileWithActions("[\"BLOCKED\",{}]"));
        AtomicInteger calls = new AtomicInteger();
        server = server(exchange -> respond(exchange, 200, bodies.get(calls.getAndIncrement())));
        MinecraftProfileApi api = api(Duration.ofSeconds(2), duration -> {});

        var missingActionsProfile = api.getProfile(TOKEN);
        var nullActionsProfile = api.getProfile(TOKEN);
        var emptyArrayProfile = api.getProfile(TOKEN);
        var emptyObjectProfile = api.getProfile(TOKEN);
        var unknownObjectProfile = api.getProfile(TOKEN);
        ProfileApiException scalarFailure = assertThrows(
                ProfileApiException.class,
                () -> api.getProfile(TOKEN));
        ProfileApiException entryFailure = assertThrows(
                ProfileApiException.class,
                () -> api.getProfile(TOKEN));

        assertTrue(missingActionsProfile.profileActions().isEmpty());
        assertTrue(nullActionsProfile.profileActions().isEmpty());
        assertTrue(emptyArrayProfile.profileActions().isEmpty());
        assertTrue(emptyObjectProfile.profileActions().isEmpty());
        assertFalse(unknownObjectProfile.profileActions().isEmpty());
        assertFalse(unknownObjectProfile.toString().contains("provider-private-action"));
        assertEquals(ApiFailureKind.INVALID_RESPONSE, scalarFailure.kind());
        assertEquals(
                ResponseSchemaCode.PROFILE_ACTIONS,
                scalarFailure.responseSchemaCode().orElseThrow());
        assertEquals(ResponseSchemaCode.PROFILE_ACTION, entryFailure.responseSchemaCode().orElseThrow());
        assertFalse(scalarFailure.toString().contains(TOKEN));
        assertFalse(entryFailure.toString().contains("BLOCKED"));
    }

    @Test
    void reportsSafeSchemaCodesForFatalProfileStructureFailures() throws Exception {
        List<String> bodies = List.of(
                "{",
                "[]",
                "{\"name\":\"Player\"}",
                "{\"id\":{},\"name\":\"Player\"}",
                "{\"id\":\"12345678123456789abcdef012345678\"}",
                "{\"id\":\"12345678123456789abcdef012345678\",\"name\":{}}");
        List<ResponseSchemaCode> expectedCodes = List.of(
                ResponseSchemaCode.JSON_DOCUMENT,
                ResponseSchemaCode.PROFILE_ROOT,
                ResponseSchemaCode.PROFILE_ID,
                ResponseSchemaCode.PROFILE_ID,
                ResponseSchemaCode.PROFILE_NAME,
                ResponseSchemaCode.PROFILE_NAME);
        AtomicInteger calls = new AtomicInteger();
        server = server(exchange -> respond(exchange, 200, bodies.get(calls.getAndIncrement())));
        MinecraftProfileApi api = api(Duration.ofSeconds(2), duration -> {});

        for (ResponseSchemaCode expectedCode : expectedCodes) {
            ProfileApiException exception = assertThrows(
                    ProfileApiException.class,
                    () -> api.getProfile(TOKEN));
            assertEquals(ApiFailureKind.INVALID_RESPONSE, exception.kind());
            assertEquals(expectedCode, exception.responseSchemaCode().orElseThrow());
            assertFalse(exception.toString().contains("provider-private"));
            assertFalse(exception.toString().contains(TOKEN));
        }
    }

    private MinecraftProfileApi api(Duration timeout, MinecraftProfileApi.Sleeper sleeper) {
        return new MinecraftProfileApi(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                new GetRetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(4)),
                timeout,
                new PngValidator(),
                sleeper,
                CLOCK,
                true);
    }

    private HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer result = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        result.createContext("/", exchange -> handler.handle(exchange));
        result.start();
        return result;
    }

    private static String profileJson(boolean activeCape) {
        return """
                {
                  "id":"12345678123456789abcdef012345678",
                  "name":"Player",
                  "skins":[{
                    "id":"skin-id",
                    "state":"ACTIVE",
                    "url":"https://textures.minecraft.net/texture/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "variant":"SLIM",
                    "alias":"ALEX"
                  }],
                  "capes":[
                    {"id":"cape-a","state":"%s","url":"http://textures.minecraft.net/texture/cape-a","alias":"A"},
                    {"id":"cape-b","state":"INACTIVE","url":"https://textures.minecraft.net/texture/cape-b","alias":"B"}
                  ],
                  "profileActions":[]
                }
                """.formatted(activeCape ? "ACTIVE" : "INACTIVE");
    }

    private static String profileWithActions(String actionsJson) {
        return """
                {
                  "id":"12345678123456789abcdef012345678",
                  "name":"Player",
                  "profileActions":%s
                }
                """.formatted(actionsJson);
    }

    private static String profileWithoutActions() {
        return """
                {
                  "id":"12345678123456789abcdef012345678",
                  "name":"Player"
                }
                """;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}

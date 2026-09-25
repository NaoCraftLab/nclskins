package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.png.PngValidator;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkinMcCapeReaderTest {
    private static final UUID PROFILE = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    @Test
    void directPngAndJpegUseOnlyFixedPublicEndpointAndCanonicalCape() throws Exception {
        for (String format : List.of("png", "jpeg")) {
            byte[] image = image(format);
            AtomicReference<URI> requested = new AtomicReference<>();
            var reader = new SkinMcCapeReader((uri, timeout, maxBytes) -> {
                requested.set(uri);
                assertEquals(Duration.ofSeconds(10), timeout);
                assertEquals(PngValidator.DEFAULT_MAX_BYTES, maxBytes);
                return response(200, image, Map.of("Content-Type", List.of("image/" + format)));
            }, new PngValidator(), Clock.systemUTC());
            var outcome = reader.read(PROFILE);
            assertEquals(SkinMcCapeReader.Kind.PRESENT, outcome.kind());
            assertFreshWire(requested.get(), PROFILE);
            assertTrue(outcome.cape().bytes().length > 0);
            if (format.equals("jpeg")) assertTrue(outcome.cape().hasElytra());
        }
    }

    @Test
    void allFourPngCapeSizesAreAccepted() throws Exception {
        for (int[] size : List.of(new int[] {46, 22}, new int[] {64, 32},
                new int[] {92, 44}, new int[] {128, 64})) {
            BufferedImage image = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, 0xff224466);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            var outcome = reader(response(200, output.toByteArray(),
                    Map.of("Content-Type", List.of("image/png")))).read(PROFILE);
            assertEquals(SkinMcCapeReader.Kind.PRESENT, outcome.kind());
        }
    }

    @Test
    void absentHttpRedirectAndMalformedMediaStayDistinct() throws Exception {
        for (int status : List.of(204, 404)) {
            assertEquals(SkinMcCapeReader.Kind.ABSENT,
                    reader(response(status, new byte[0], Map.of())).read(PROFILE).kind());
        }
        for (int status : List.of(301, 302, 307, 401, 500)) {
            assertEquals(SkinMcCapeReader.Failure.HTTP,
                    reader(response(status, new byte[0], Map.of("Location", List.of("https://example.org/"))))
                            .read(PROFILE).failure());
        }
        assertEquals(SkinMcCapeReader.Failure.HTTP,
                reader(response(500, image("png"), Map.of())).read(PROFILE).failure());
        assertEquals(SkinMcCapeReader.Failure.INVALID_IMAGE,
                reader(response(200, "<html>".getBytes(StandardCharsets.UTF_8),
                        Map.of("Content-Type", List.of("image/png")))).read(PROFILE).failure());
        assertEquals(SkinMcCapeReader.Kind.PRESENT,
                reader(response(200, image("png"), Map.of("Content-Type", List.of("text/html"))))
                        .read(PROFILE).kind());
        assertEquals(SkinMcCapeReader.Failure.OVERSIZED,
                reader(response(200, new byte[PngValidator.DEFAULT_MAX_BYTES + 1],
                        Map.of("Content-Type", List.of("image/png")))).read(PROFILE).failure());
    }

    @Test
    void directPresenceAbsenceAndRateLimitUseOneFreshWirePerAdmittedRead() throws Exception {
        MutableClock clock = new MutableClock();
        List<URI> requested = new ArrayList<>();
        byte[] png = image("png");
        var reader = new SkinMcCapeReader((uri, timeout, maxBytes) -> {
            requested.add(uri);
            return switch (requested.size()) {
                case 1, 5 -> response(200, png, Map.of("Content-Type", List.of("image/png")));
                case 2 -> response(204, new byte[0], Map.of("Cache-Control", List.of("private, max-age=7200")));
                case 3 -> response(404, new byte[0], Map.of("Cache-Control", List.of("private, max-age=7200")));
                case 4 -> response(429, new byte[0], Map.of("Retry-After", List.of("120")));
                default -> throw new AssertionError("Unexpected physical read");
            };
        }, new PngValidator(), clock);
        assertEquals(SkinMcCapeReader.Kind.PRESENT, reader.read(PROFILE).kind());
        assertEquals(SkinMcCapeReader.Kind.ABSENT, reader.read(PROFILE).kind());
        assertEquals(SkinMcCapeReader.Kind.ABSENT, reader.read(PROFILE).kind());
        assertEquals(SkinMcCapeReader.Failure.RATE_LIMITED, reader.read(PROFILE).failure());
        assertEquals(SkinMcCapeReader.Failure.RATE_LIMITED, reader.read(PROFILE).failure());
        assertEquals(4, requested.size(), "cooldown must not create another physical read");
        clock.advanceSeconds(121);
        assertEquals(SkinMcCapeReader.Kind.PRESENT, reader.read(PROFILE).kind());
        assertEquals(5, requested.size());
        long previous = 0;
        for (URI wire : requested) {
            long current = assertFreshWire(wire, PROFILE);
            assertTrue(current > previous);
            previous = current;
        }
    }

    @Test
    void unsafeAndAmbiguousJsonDoesNotFetchImage() {
        for (String url : List.of("http://skinmc.net/image.png", "https://127.0.0.1/a.png",
                "https://user@skinmc.net/a.png", "https://skinmc.net/a.png#fragment")) {
            AtomicInteger requests = new AtomicInteger();
            var reader = new SkinMcCapeReader((uri, timeout, maxBytes) -> {
                requests.incrementAndGet();
                return response(200, ("{\"url\":\"" + url + "\"}").getBytes(StandardCharsets.UTF_8),
                        Map.of("Content-Type", List.of("application/json")));
            }, new PngValidator(), Clock.systemUTC());
            var outcome = reader.read(PROFILE);
            assertEquals(SkinMcCapeReader.Kind.FAILURE, outcome.kind());
            assertEquals(1, requests.get());
        }
        var conflicting = reader(response(200,
                "{\"url\":\"https://example.org/a\",\"texture\":\"https://example.org/b\"}"
                        .getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", List.of("application/json"))));
        assertEquals(SkinMcCapeReader.Failure.INVALID_JSON, conflicting.read(PROFILE).failure());
    }

    @Test
    void retryAfterSecondsDateFallbackAndQuotaResetSuppressLaterHttp() {
        for (Map<String, List<String>> headers : List.of(
                Map.of("Retry-After", List.of("120")),
                Map.of("Retry-After", List.of(DateTimeFormatter.RFC_1123_DATE_TIME.format(
                        Instant.parse("2026-09-25T10:02:00Z").atZone(ZoneOffset.UTC)))),
                Map.of("Retry-After", List.of("invalid")))) {
            MutableClock clock = new MutableClock();
            AtomicInteger requests = new AtomicInteger();
            var reader = new SkinMcCapeReader((uri, timeout, maxBytes) -> {
                requests.incrementAndGet();
                return response(429, new byte[0], headers);
            }, new PngValidator(), clock);
            assertEquals(SkinMcCapeReader.Failure.RATE_LIMITED, reader.read(PROFILE).failure());
            assertEquals(SkinMcCapeReader.Failure.RATE_LIMITED, reader.read(PROFILE).failure());
            assertEquals(1, requests.get());
            assertTrue(reader.cooldownRemaining().orElseThrow().compareTo(Duration.ofSeconds(1)) >= 0);
        }
        MutableClock clock = new MutableClock();
        AtomicInteger requests = new AtomicInteger();
        var quota = new SkinMcCapeReader((uri, timeout, maxBytes) -> {
            requests.incrementAndGet();
            return response(404, new byte[0], Map.of("X-RateLimit-Remaining", List.of("0"),
                    "X-RateLimit-Reset", List.of("1790330520")));
        }, new PngValidator(), clock);
        quota.read(PROFILE);
        quota.read(PROFILE);
        assertEquals(1, requests.get());
        clock.advanceSeconds(121);
        quota.read(PROFILE);
        assertEquals(2, requests.get());
    }

    @Test
    void accountChangeKeepsDeadlineAndRejectsOldAdmission() {
        MutableClock clock = new MutableClock();
        AtomicInteger requests = new AtomicInteger();
        var reader = new SkinMcCapeReader((uri, timeout, maxBytes) -> {
            requests.incrementAndGet();
            return response(429, new byte[0], Map.of("Retry-After", List.of("120")));
        }, new PngValidator(), clock);
        long oldEpoch = reader.accountEpoch();
        reader.read(PROFILE, oldEpoch);
        reader.accountChanged();
        assertEquals(Duration.ofSeconds(120), reader.cooldownRemaining().orElseThrow());
        assertEquals(SkinMcCapeReader.Failure.RATE_LIMITED, reader.read(UUID.randomUUID()).failure());
        assertEquals(1, requests.get());
        clock.advanceSeconds(120);
        assertEquals(SkinMcCapeReader.Failure.RATE_LIMITED, reader.read(PROFILE, oldEpoch).failure());
        assertEquals(1, requests.get());
        reader.read(UUID.randomUUID());
        assertEquals(2, requests.get());
    }

    @Test
    void delayedOldAccountRateLimitCannotExtendCurrentDeadline() {
        MutableClock clock = new MutableClock();
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<SkinMcCapeReader> current = new AtomicReference<>();
        var reader = new SkinMcCapeReader((uri, timeout, maxBytes) -> {
            if (requests.incrementAndGet() == 1) {
                current.get().accountChanged();
                return response(429, new byte[0], Map.of("Retry-After", List.of("86400")));
            }
            return response(404, new byte[0], Map.of());
        }, new PngValidator(), clock);
        current.set(reader);
        assertEquals(SkinMcCapeReader.Failure.RATE_LIMITED, reader.read(PROFILE).failure());
        assertTrue(reader.cooldownRemaining().isEmpty());
        assertEquals(SkinMcCapeReader.Kind.ABSENT, reader.read(UUID.randomUUID()).kind());
        assertEquals(2, requests.get());
    }

    @Test
    void nestedAliasesResolveOneSignedImageHopWithGenericMime() throws Exception {
        byte[] png = image("png");
        URI signed = URI.create("https://cdn.example.net/cape.png?sig=a%2Fb&expiry=42");
        String json = "{\"meta\":[0,{\"capeUrl\":\"" + signed + "\"}],"
                + "\"data\":{\"nested\":[{\"texture\":\"" + signed + "\"}]}}";
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        var reader = new SkinMcCapeReader((uri, timeout, maxBytes) -> {
            firstCalls.incrementAndGet();
            assertFreshWire(uri, PROFILE);
            return response(200, json.getBytes(StandardCharsets.UTF_8), Map.of());
        }, (uri, timeout, maxBytes) -> {
            secondCalls.incrementAndGet();
            assertEquals(signed, uri);
            assertEquals("sig=a%2Fb&expiry=42", uri.getRawQuery());
            assertEquals(PngValidator.DEFAULT_MAX_BYTES, maxBytes);
            return response(200, png, Map.of("Content-Type", List.of("application/octet-stream")));
        }, new PngValidator(), Clock.systemUTC(), System::nanoTime);
        assertEquals(SkinMcCapeReader.Kind.PRESENT, reader.read(PROFILE).kind());
        assertEquals(1, firstCalls.get());
        assertEquals(1, secondCalls.get());
    }

    @Test
    void malformedAmbiguousAndOverComplexJsonNeverStartsImageHop() {
        String good = "https://cdn.example.net/a.png";
        List<String> documents = List.of(
                "{\"url\":\"" + good + "\",\"cape_url\":\"https://cdn.example.net/b.png\"}",
                "{\"data\":[{\"url\":3}]}",
                "{\"url\":\"" + good + "\"",
                "{\"url\":\"" + good + "\"} trailing",
                "{\"deep\":".repeat(13) + "0" + "}".repeat(13),
                "[" + "0,".repeat(129) + "0]");
        for (String document : documents) {
            AtomicInteger secondCalls = new AtomicInteger();
            var reader = new SkinMcCapeReader((uri, timeout, maxBytes) ->
                    response(200, document.getBytes(StandardCharsets.UTF_8), Map.of()),
                    (uri, timeout, maxBytes) -> {
                        secondCalls.incrementAndGet();
                        throw new AssertionError("invalid JSON reached image transport");
                    }, new PngValidator(), Clock.systemUTC(), System::nanoTime);
            assertEquals(SkinMcCapeReader.Failure.INVALID_JSON, reader.read(PROFILE).failure(), document);
            assertEquals(0, secondCalls.get());
        }
    }

    @Test
    void secondHopStatusesAndPayloadNeverBecomeAbsenceOrThirdHop() throws Exception {
        String json = "{\"url\":\"https://cdn.example.net/a.png\"}";
        byte[] png = image("png");
        for (int status : List.of(204, 404, 302, 500)) {
            AtomicInteger secondCalls = new AtomicInteger();
            var reader = new SkinMcCapeReader((uri, timeout, maxBytes) ->
                    response(200, json.getBytes(StandardCharsets.UTF_8), Map.of()),
                    (uri, timeout, maxBytes) -> {
                        secondCalls.incrementAndGet();
                        return response(status, png, Map.of("Location", List.of("https://elsewhere/a")));
                    }, new PngValidator(), Clock.systemUTC(), System::nanoTime);
            assertEquals(SkinMcCapeReader.Failure.HTTP, reader.read(PROFILE).failure());
            assertEquals(1, secondCalls.get());
        }
        for (byte[] payload : List.of(json.getBytes(StandardCharsets.UTF_8),
                "<html>".getBytes(StandardCharsets.UTF_8), new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            AtomicInteger secondCalls = new AtomicInteger();
            var reader = new SkinMcCapeReader((uri, timeout, maxBytes) ->
                    response(200, json.getBytes(StandardCharsets.UTF_8), Map.of()),
                    (uri, timeout, maxBytes) -> {
                        secondCalls.incrementAndGet();
                        return response(200, payload, Map.of());
                    }, new PngValidator(), Clock.systemUTC(), System::nanoTime);
            assertEquals(SkinMcCapeReader.Failure.INVALID_IMAGE, reader.read(PROFILE).failure());
            assertEquals(1, secondCalls.get());
        }
    }

    @Test
    void secondHopRateLimitSuppressesOnlySkinMc() throws Exception {
        MutableClock clock = new MutableClock();
        byte[] json = "{\"url\":\"https://cdn.example.net/a.png\"}".getBytes(StandardCharsets.UTF_8);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        byte[] png = image("png");
        var reader = new SkinMcCapeReader((uri, timeout, maxBytes) -> {
            firstCalls.incrementAndGet();
            return response(200, json, Map.of());
        }, (uri, timeout, maxBytes) -> {
            secondCalls.incrementAndGet();
            return response(429, png, Map.of("Retry-After", List.of("120")));
        }, new PngValidator(), clock, System::nanoTime);
        assertEquals(SkinMcCapeReader.Failure.RATE_LIMITED, reader.read(PROFILE).failure());
        assertEquals(Duration.ofSeconds(120), reader.cooldownRemaining().orElseThrow());
        assertEquals(SkinMcCapeReader.Failure.RATE_LIMITED, reader.read(PROFILE).failure());
        assertEquals(1, firstCalls.get());
        assertEquals(1, secondCalls.get());
        var optifine = new OptifineCapeReader((uri, timeout, maxBytes) ->
                response(404, new byte[0], Map.of()), new PngValidator(), clock);
        assertEquals(OptifineCapeReader.Kind.ABSENT, optifine.read("Example").kind());
    }

    @Test
    void secondHopRateLimitHeadersUseBoundedSecondsDateQuotaAndFallback() {
        byte[] json = "{\"url\":\"https://cdn.example.net/a.png\"}".getBytes(StandardCharsets.UTF_8);
        List<Map<String, List<String>>> headers = List.of(
                Map.of("Retry-After", List.of("120")),
                Map.of("Retry-After", List.of(DateTimeFormatter.RFC_1123_DATE_TIME.format(
                        Instant.parse("2026-09-25T10:02:00Z").atZone(ZoneOffset.UTC)))),
                Map.of("X-RateLimit-Remaining", List.of("0"),
                        "X-RateLimit-Reset", List.of("1790330520")),
                Map.of("Retry-After", List.of("not-a-date")));
        for (int index = 0; index < headers.size(); index++) {
            MutableClock clock = new MutableClock();
            Map<String, List<String>> current = headers.get(index);
            var reader = new SkinMcCapeReader((uri, timeout, maxBytes) ->
                    response(200, json, Map.of()),
                    (uri, timeout, maxBytes) -> response(429, new byte[0], current),
                    new PngValidator(), clock, System::nanoTime);
            assertEquals(SkinMcCapeReader.Failure.RATE_LIMITED, reader.read(PROFILE).failure());
            assertEquals(Duration.ofSeconds(index == 3 ? 60 : 120),
                    reader.cooldownRemaining().orElseThrow());
        }
    }

    @Test
    void firstJsonQuotaExhaustionDefersImageUntilFreshReadAfterExpiry() throws Exception {
        MutableClock clock = new MutableClock();
        UUID latestProfile = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        byte[] json = "{\"url\":\"https://cdn.example.net/a.png\"}".getBytes(StandardCharsets.UTF_8);
        byte[] png = image("png");
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger imageCalls = new AtomicInteger();
        List<URI> firstUris = new ArrayList<>();
        var reader = new SkinMcCapeReader((uri, timeout, maxBytes) -> {
            firstUris.add(uri);
            return firstCalls.incrementAndGet() == 1
                    ? response(200, json, Map.of("X-RateLimit-Remaining", List.of("0"),
                            "X-RateLimit-Reset", List.of("1790330520")))
                    : response(200, json, Map.of());
        }, (uri, timeout, maxBytes) -> {
            imageCalls.incrementAndGet();
            return response(200, png, Map.of());
        }, new PngValidator(), clock, System::nanoTime);

        assertEquals(SkinMcCapeReader.Failure.RATE_LIMITED, reader.read(PROFILE).failure());
        assertEquals(Duration.ofSeconds(120), reader.cooldownRemaining().orElseThrow());
        assertEquals(0, imageCalls.get());
        assertEquals(SkinMcCapeReader.Failure.RATE_LIMITED, reader.read(PROFILE).failure());
        assertEquals(SkinMcCapeReader.Failure.RATE_LIMITED, reader.read(latestProfile).failure());
        assertEquals(1, firstCalls.get());
        clock.advanceSeconds(120);
        assertEquals(SkinMcCapeReader.Kind.PRESENT, reader.read(latestProfile).kind());
        assertEquals(2, firstCalls.get());
        assertEquals(1, imageCalls.get());
        assertFreshWire(firstUris.get(1), latestProfile);
        assertTrue(reader.cooldownRemaining().isEmpty());
    }

    @Test
    void accountChangeDuringFirstJsonResponsePreventsOldImageHop() {
        byte[] json = "{\"url\":\"https://cdn.example.net/a.png\"}".getBytes(StandardCharsets.UTF_8);
        AtomicReference<SkinMcCapeReader> current = new AtomicReference<>();
        AtomicInteger imageCalls = new AtomicInteger();
        var reader = new SkinMcCapeReader((uri, timeout, maxBytes) -> {
            current.get().accountChanged();
            return response(200, json, Map.of());
        }, (uri, timeout, maxBytes) -> {
            imageCalls.incrementAndGet();
            return response(404, new byte[0], Map.of());
        }, new PngValidator(), Clock.systemUTC(), System::nanoTime);
        current.set(reader);

        assertEquals(SkinMcCapeReader.Failure.RATE_LIMITED, reader.read(PROFILE).failure());
        assertEquals(0, imageCalls.get());
    }

    @Test
    void chainDeadlineAndSecondBodyLimitAreEnforced() {
        byte[] json = "{\"url\":\"https://cdn.example.net/a.png\"}".getBytes(StandardCharsets.UTF_8);
        AtomicLong now = new AtomicLong();
        var late = new SkinMcCapeReader((uri, timeout, maxBytes) -> {
            now.addAndGet(Duration.ofSeconds(19).toNanos());
            return response(200, json, Map.of());
        }, (uri, timeout, maxBytes) -> {
            assertEquals(Duration.ofSeconds(1), timeout);
            now.addAndGet(Duration.ofSeconds(2).toNanos());
            return response(200, new byte[0], Map.of());
        }, new PngValidator(), Clock.systemUTC(), now::get);
        assertEquals(SkinMcCapeReader.Failure.NETWORK, late.read(PROFILE).failure());
        var oversized = new SkinMcCapeReader((uri, timeout, maxBytes) ->
                response(200, json, Map.of()),
                (uri, timeout, maxBytes) -> {
                    assertEquals(128, maxBytes);
                    return response(200, new byte[129], Map.of());
                }, new PngValidator(128), Clock.systemUTC(), System::nanoTime);
        assertEquals(SkinMcCapeReader.Failure.OVERSIZED, oversized.read(PROFILE).failure());
    }

    @Test
    void streamingFirstResponseRejectsJsonBeforeTextureAllowance() throws Exception {
        HttpResponse.BodySubscriber<byte[]> body = OptifineCapeReader.HttpTransport.skinMcBody(
                PngValidator.DEFAULT_MAX_BYTES);
        AtomicInteger cancelled = new AtomicInteger();
        body.onSubscribe(new Flow.Subscription() {
            @Override public void request(long count) {}
            @Override public void cancel() { cancelled.incrementAndGet(); }
        });
        body.onNext(List.of(ByteBuffer.wrap(("{\"pad\":\"" + "x".repeat(2048))
                .getBytes(StandardCharsets.UTF_8))));
        assertThrows(ExecutionException.class, () -> body.getBody().toCompletableFuture().get());
        assertEquals(1, cancelled.get());
    }

    private static SkinMcCapeReader reader(OptifineCapeReader.Response response) {
        return new SkinMcCapeReader((uri, timeout, maxBytes) -> response,
                new PngValidator(), Clock.systemUTC());
    }

    private static long assertFreshWire(URI wire, UUID profileId) {
        URI canonical = SkinMcFreshnessQuery.canonical(profileId);
        assertEquals("https", wire.getScheme());
        assertEquals("skinmc.net", wire.getHost());
        assertEquals(canonical.getRawPath(), wire.getRawPath());
        assertTrue(wire.getRawQuery().matches("t=[0-9]{1,19}"));
        long nonce = Long.parseLong(wire.getRawQuery().substring(2));
        SkinMcFreshnessQuery.requireExact(profileId, canonical, wire, nonce);
        return nonce;
    }

    private static OptifineCapeReader.Response response(int status, byte[] body,
            Map<String, List<String>> headers) {
        return new OptifineCapeReader.Response(status, body, headers);
    }

    private static byte[] image(String format) throws Exception {
        BufferedImage image = new BufferedImage(64, 32,
                format.equals("jpeg") ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff224466);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong seconds = new AtomicLong(Instant.parse("2026-09-25T10:00:00Z").getEpochSecond());

        void advanceSeconds(long count) { seconds.addAndGet(count); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return Instant.ofEpochSecond(seconds.get()); }
    }
}

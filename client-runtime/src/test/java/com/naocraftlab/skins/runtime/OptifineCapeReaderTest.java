package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.png.CapeAssetDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.net.URI;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OptifineCapeReaderTest {
    @TempDir Path scratch;

    @Test
    void preparedReadUsesCallerOwnedFileAndReleasesEncodedStage() throws Exception {
        byte[] png = capePng();
        OptifineCapeReader.Transport transport = new OptifineCapeReader.Transport() {
            @Override public OptifineCapeReader.FileResponse getToFile(URI uri,
                    Duration timeout, long maxBytes, Path destination) throws IOException {
                assertEquals(CapeAssetDecoder.MAX_ENCODED_BYTES, maxBytes);
                assertEquals(URI.create("https://optifine.net/capes/Player.png"), uri);
                Files.write(destination, png);
                return new OptifineCapeReader.FileResponse(200, png.length, Map.of());
            }
        };
        var reader = new OptifineCapeReader(transport, new CapeAssetDecoder(), scratch, Clock.systemUTC());

        var result = reader.read("Player");
        assertEquals(OptifineCapeReader.Kind.PRESENT, result.kind());
        assertEquals(64, result.asset().width());
        assertEquals(32, result.asset().height());
        assertEquals(new PngValidator().projectImportedCape(png).renderSha256(),
                result.asset().visualSha256());
        try (var staged = Files.list(scratch)) {
            assertEquals(List.of(result.asset().backing()), staged.toList());
        }
        Files.delete(result.asset().backing());
    }

    @Test
    void inclusiveEncodedCeilingReachesDecoderAndPlusOneIsRejected() throws Exception {
        for (long size : new long[] {CapeAssetDecoder.MAX_ENCODED_BYTES,
                CapeAssetDecoder.MAX_ENCODED_BYTES + 1}) {
            OptifineCapeReader.Transport transport = new OptifineCapeReader.Transport() {
                @Override public OptifineCapeReader.FileResponse getToFile(URI uri,
                        Duration timeout, long maxBytes, Path destination) throws IOException {
                    try (FileChannel file = FileChannel.open(destination,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        file.position(size - 1);
                        file.write(ByteBuffer.wrap(new byte[] {0}));
                    }
                    return new OptifineCapeReader.FileResponse(200, size, Map.of());
                }
            };
            var reader = new OptifineCapeReader(transport, new CapeAssetDecoder(), scratch, Clock.systemUTC());
            assertEquals(size == CapeAssetDecoder.MAX_ENCODED_BYTES
                            ? OptifineCapeReader.Failure.INVALID_PNG
                            : OptifineCapeReader.Failure.OVERSIZED,
                    reader.read("Player").failure());
            try (var staged = Files.list(scratch)) { assertEquals(0, staged.count()); }
        }
    }

    @Test
    void preparedRateLimitBlocksAnotherRequest() {
        AtomicInteger calls = new AtomicInteger();
        OptifineCapeReader.Transport transport = new OptifineCapeReader.Transport() {
            @Override public OptifineCapeReader.FileResponse getToFile(URI uri,
                    Duration timeout, long maxBytes, Path destination) {
                calls.incrementAndGet();
                return new OptifineCapeReader.FileResponse(429, 0,
                        Map.of("Retry-After", List.of("120")));
            }
        };
        var reader = new OptifineCapeReader(transport, new CapeAssetDecoder(), scratch, Clock.systemUTC());
        assertEquals(OptifineCapeReader.Failure.RATE_LIMITED, reader.read("Player").failure());
        assertEquals(OptifineCapeReader.Failure.RATE_LIMITED, reader.read("Player").failure());
        assertEquals(1, calls.get());
    }

    @Test
    void publicReadUsesOnlyCanonicalNameAndNormalizesValidPng() throws Exception {
        byte[] png = capePng();
        AtomicReference<URI> requested = new AtomicReference<>();
        var reader = new OptifineCapeReader(mock((uri, timeout, maxBytes) -> {
            requested.set(uri);
            assertEquals(Duration.ofSeconds(10), timeout);
            assertEquals(CapeAssetDecoder.MAX_ENCODED_BYTES, maxBytes);
            return new OptifineCapeReader.Response(200, png);
        }), scratch, Clock.systemUTC());
        var result = reader.read("Player_123");
        assertEquals(OptifineCapeReader.Kind.PRESENT, result.kind());
        assertEquals(URI.create("https://optifine.net/capes/Player_123.png"), requested.get());
        assertEquals(new PngValidator().projectImportedCape(png).renderSha256(),
                result.asset().visualSha256());
        assertNull(result.failure());
    }

    @Test
    void absentAndFailuresRemainDistinct() throws Exception {
        byte[] png = capePng();
        assertEquals(OptifineCapeReader.Kind.ABSENT, reader(404, new byte[0]).read("Player").kind());
        for (int status : new int[] {301, 302, 401, 403, 500}) {
            assertEquals(OptifineCapeReader.Failure.HTTP, reader(status, png).read("Player").failure());
        }
        assertEquals(OptifineCapeReader.Failure.INVALID_PNG,
                reader(200, new byte[] {1, 2, 3}).read("Player").failure());
        assertEquals(OptifineCapeReader.Failure.NETWORK,
                new OptifineCapeReader((uri, timeout, maxBytes, destination) -> {
                    throw new IOException("timeout");
                }, scratch, Clock.systemUTC()).read("Player").failure());
        assertEquals(OptifineCapeReader.Failure.INVALID_NAME,
                reader(200, png).read("bad/name").failure());
    }

    @Test
    void optifineDimensionsArePaddedBeforePublication() throws Exception {
        BufferedImage image = new BufferedImage(46, 22, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(45, 21, 0x80112233);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        var result = reader(200, output.toByteArray()).read("Player");
        assertEquals(OptifineCapeReader.Kind.PRESENT, result.kind());
        BufferedImage normalized = ImageIO.read(new ByteArrayInputStream(result.asset().framePng(0)));
        assertEquals(64, normalized.getWidth());
        assertEquals(32, normalized.getHeight());
        assertEquals(0x80112233, normalized.getRGB(45, 21));
        assertEquals(0, normalized.getRGB(46, 21));
    }

    @Test
    void doubleResolutionCapesRetainPixelsAlphaAndOnePixelElytra() throws Exception {
        for (int width : new int[] {92, 128}) {
            BufferedImage original = new BufferedImage(width, width == 92 ? 44 : 64,
                    BufferedImage.TYPE_INT_ARGB);
            original.setRGB(3, 7, 0x80112233);
            original.setRGB(4, 7, 0x44224466);
            original.setRGB(48, 0, 0x01010203);
            original.setRGB(width - 1, original.getHeight() - 1, 0x7f556677);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(original, "png", output);

            var result = reader(200, output.toByteArray()).read("Player");
            assertEquals(OptifineCapeReader.Kind.PRESENT, result.kind());
            assertEquals(true, result.asset().hasElytra());
            BufferedImage canonical = ImageIO.read(new ByteArrayInputStream(result.asset().framePng(0)));
            assertEquals(128, canonical.getWidth());
            assertEquals(64, canonical.getHeight());
            assertEquals(0x80112233, canonical.getRGB(3, 7));
            assertEquals(0x44224466, canonical.getRGB(4, 7));
            assertEquals(0x01010203, canonical.getRGB(48, 0));
            assertEquals(0x7f556677, canonical.getRGB(width - 1, original.getHeight() - 1));
            if (width == 92) assertEquals(0, canonical.getRGB(92, 43));
        }
    }

    @Test
    void providerCooldownSuppressesHttpAndUsesBoundedRetryAfter() {
        MutableClock clock = new MutableClock();
        AtomicInteger requests = new AtomicInteger();
        var reader = new OptifineCapeReader(mock((uri, timeout, maxBytes) -> {
            requests.incrementAndGet();
            return new OptifineCapeReader.Response(429, new byte[0],
                    Map.of("Retry-After", List.of("120")));
        }), scratch, clock);
        assertEquals(OptifineCapeReader.Failure.RATE_LIMITED, reader.read("Player").failure());
        assertEquals(OptifineCapeReader.Failure.RATE_LIMITED, reader.read("Player").failure());
        assertEquals(1, requests.get());
        assertEquals(Duration.ofSeconds(120), reader.cooldownRemaining().orElseThrow());
        clock.advanceSeconds(120);
        reader.read("Player");
        assertEquals(2, requests.get());
    }

    @Test
    void invalidAndOverflowRetryAfterUseFiniteFallback() {
        for (String value : List.of("-5", "999999999999999999999999999999", "garbage")) {
            MutableClock clock = new MutableClock();
            var reader = new OptifineCapeReader(mock((uri, timeout, maxBytes) ->
                    new OptifineCapeReader.Response(429, new byte[0],
                            Map.of("Retry-After", List.of(value)))), scratch, clock);
            reader.read("Player");
            assertEquals(Duration.ofSeconds(60), reader.cooldownRemaining().orElseThrow());
        }
    }

    @Test
    void accountChangeKeepsDeadlineAndRejectsOldAdmission() {
        MutableClock clock = new MutableClock();
        AtomicInteger requests = new AtomicInteger();
        var reader = new OptifineCapeReader(mock((uri, timeout, maxBytes) -> {
            requests.incrementAndGet();
            return new OptifineCapeReader.Response(429, new byte[0],
                    Map.of("Retry-After", List.of("120")));
        }), scratch, clock);
        long oldEpoch = reader.accountEpoch();
        reader.read("Old", oldEpoch);
        reader.accountChanged();
        assertEquals(Duration.ofSeconds(120), reader.cooldownRemaining().orElseThrow());
        assertEquals(OptifineCapeReader.Failure.RATE_LIMITED, reader.read("New").failure());
        assertEquals(1, requests.get());
        clock.advanceSeconds(120);
        assertEquals(OptifineCapeReader.Failure.RATE_LIMITED, reader.read("Old", oldEpoch).failure());
        assertEquals(1, requests.get());
        reader.read("New");
        assertEquals(2, requests.get());
    }

    @Test
    void delayedOldAccountRateLimitCannotExtendCurrentDeadline() {
        MutableClock clock = new MutableClock();
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<OptifineCapeReader> current = new AtomicReference<>();
        var reader = new OptifineCapeReader(mock((uri, timeout, maxBytes) -> {
            if (requests.incrementAndGet() == 1) {
                current.get().accountChanged();
                return new OptifineCapeReader.Response(429, new byte[0],
                        Map.of("Retry-After", List.of("86400")));
            }
            return new OptifineCapeReader.Response(404, new byte[0]);
        }), scratch, clock);
        current.set(reader);
        assertEquals(OptifineCapeReader.Failure.RATE_LIMITED, reader.read("Old").failure());
        assertEquals(true, reader.cooldownRemaining().isEmpty());
        assertEquals(OptifineCapeReader.Kind.ABSENT, reader.read("New").kind());
        assertEquals(2, requests.get());
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong seconds = new AtomicLong(Instant.parse("2026-09-25T10:00:00Z").getEpochSecond());

        void advanceSeconds(long count) { seconds.addAndGet(count); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return Instant.ofEpochSecond(seconds.get()); }
    }

    private OptifineCapeReader reader(int status, byte[] body) {
        return new OptifineCapeReader(mock((uri, timeout, maxBytes) ->
                new OptifineCapeReader.Response(status, body)), scratch, Clock.systemUTC());
    }

    @FunctionalInterface
    private interface ResponseFixture {
        OptifineCapeReader.Response response(URI uri, Duration timeout, long maxBytes) throws IOException;
    }

    private static OptifineCapeReader.Transport mock(ResponseFixture fixture) {
        return (uri, timeout, maxBytes, destination) -> {
            OptifineCapeReader.Response response = fixture.response(uri, timeout, maxBytes);
            if (response.status() == 200) {
                if (response.bytes().length > maxBytes) {
                    throw new OptifineCapeReader.OversizedResponseException();
                }
                Files.write(destination, response.bytes());
            }
            return new OptifineCapeReader.FileResponse(response.status(), response.bytes().length,
                    response.headers());
        };
    }

    private static byte[] capePng() throws IOException {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x7f123456);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}

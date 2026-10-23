package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.png.CapeAssetDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftCapesCapeReaderTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TEXTURE = "https://textures.minecraftcapes.net/capes/" + "a".repeat(64);
    private static final String GIF = "https://textures.minecraftcapes.net/animated/" + "b".repeat(64);

    @TempDir Path scratch;

    @Test void largeLegacyTextureIsSkippedWithoutMaterializingOrLosingSelectedMetadata()
            throws Exception {
        byte[] prefix = json("{\"textures\":{\"cape\":\"");
        byte[] suffix = json("\",\"ears\":null}," + profile(GIF, TEXTURE, true).substring(1));
        InputStream legacy = new InputStream() {
            int remaining = 970_736;
            @Override public int read() { return remaining-- > 0 ? 'A' : -1; }
            @Override public int read(byte[] target, int offset, int length) {
                if (remaining <= 0) return -1;
                int count = Math.min(length, remaining);
                java.util.Arrays.fill(target, offset, offset + count, (byte) 'A');
                remaining -= count;
                return count;
            }
        };
        InputStream stream = new SequenceInputStream(
                new SequenceInputStream(new ByteArrayInputStream(prefix), legacy),
                new ByteArrayInputStream(suffix));
        var parsed = MinecraftCapesCapeReader.parse(stream);
        assertEquals(URI.create(GIF), parsed.image());
        assertTrue(parsed.animated());
        assertTrue(parsed.glint());
    }

    @Test void oversizedNonLegacyProfileTokensNeverBecomeUnboundedStrings() {
        assertThrows(IOException.class, () -> MinecraftCapesCapeReader.parse(json(
                "{\"" + "x".repeat(100_000) + "\":0}")));
        assertThrows(IOException.class, () -> MinecraftCapesCapeReader.parse(json(
                "{\"textures\":{\"other\":\"" + "x".repeat(100_000) + "\"}}")));
        StringBuilder metadata = new StringBuilder("{");
        for (int index = 0; index < 9; index++) {
            if (index != 0) metadata.append(',');
            metadata.append('"').append(index).append("\":\"").append("x".repeat(2048)).append('"');
        }
        assertThrows(IOException.class, () -> MinecraftCapesCapeReader.parse(
                json(metadata.append('}').toString())));
    }

    @Test void escapedUsefulMetadataWithinBudgetIsAccepted() throws Exception {
        String value = "\\u0041".repeat(2048);
        StringBuilder profile = new StringBuilder("{");
        for (int index = 0; index < 7; index++) {
            if (index != 0) profile.append(',');
            profile.append('"').append(index).append("\":\"").append(value).append('"');
        }
        profile.append('}');
        assertTrue(profile.length() > MinecraftCapesCapeReader.MAX_PROFILE_BYTES);
        assertEquals(null, MinecraftCapesCapeReader.parse(json(profile.toString())).image());
    }

    @Test void usefulMetadataIsBoundedAtExactlySixteenKibibytes() throws Exception {
        StringBuilder profile = new StringBuilder("{");
        for (int index = 0; index < 8; index++) {
            if (index != 0) profile.append(',');
            profile.append('"').append(index).append("\":\"")
                    .append("x".repeat(index == 7 ? 2040 : 2048)).append('"');
        }
        profile.append('}');
        assertEquals(null, MinecraftCapesCapeReader.parse(json(profile.toString())).image());
        String overLimit = profile.insert(profile.length() - 2, 'x').toString();
        assertThrows(IOException.class, () -> MinecraftCapesCapeReader.parse(json(overLimit)));
    }

    @Test void malformedUtf8InUnknownFieldIsAProfileFailure() {
        byte[] prefix = json("{\"other\":\"");
        byte[] suffix = json("\",\"cape_url\":\"" + TEXTURE + "\"}");
        byte[] malformed = new byte[prefix.length + 1 + suffix.length];
        System.arraycopy(prefix, 0, malformed, 0, prefix.length);
        malformed[prefix.length] = (byte) 0xff;
        System.arraycopy(suffix, 0, malformed, prefix.length + 1, suffix.length);
        assertThrows(IOException.class, () -> MinecraftCapesCapeReader.parse(malformed));
        FakeTransport transport = new FakeTransport();
        transport.profileBody = malformed;
        MinecraftCapesCapeReader reader = reader(transport);
        assertEquals(MinecraftCapesCapeReader.Kind.FAILURE,
                reader.read(ACTOR, reader.accountEpoch()).kind());
        assertTrue(transport.imageCalls.isEmpty());
    }

    @Test void largeLegacyProfileSelectsAnimatedImageAndGlint() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.profileBody = json("{\"textures\":{\"cape\":\"" + "A".repeat(970_736)
                + "\"}," + profile(GIF, TEXTURE, true).substring(1));
        transport.imageBytes = gif();
        MinecraftCapesCapeReader reader = reader(transport);
        var outcome = reader.read(ACTOR, reader.accountEpoch());
        assertEquals(MinecraftCapesCapeReader.Kind.PRESENT, outcome.kind());
        assertTrue(outcome.glint());
        assertEquals(2, outcome.asset().frameCount());
        assertEquals(GIF, withoutNonce(transport.imageCalls.get(0).uri()));
        Files.delete(outcome.asset().backing());
        assertTrue(emptyScratch());
    }

    @Test void profileSelectionIsStrictAboutMissingNullMalformedAndDuplicateFields() throws Exception {
        assertEquals(null, MinecraftCapesCapeReader.parse(json("{}" )).image());
        assertEquals(null, MinecraftCapesCapeReader.parse(json("{\"animated_cape_url\":null,\"cape_url\":null}")).image());
        assertEquals(null, MinecraftCapesCapeReader.parse(json("{\"textures\":{\"cape\":null}}")).image());
        assertEquals(URI.create(GIF), MinecraftCapesCapeReader.parse(json(profile(GIF, TEXTURE, true))).image());
        assertTrue(MinecraftCapesCapeReader.parse(json(profile(GIF, TEXTURE, true))).animated());
        assertFalse(MinecraftCapesCapeReader.parse(json("{\"cape_url\":\"" + TEXTURE + "\",\"animatedCape\":true}")).animated());
        for (String malformed : List.of(
                "[]", "null", "true", "42",
                "{\"animated_cape_url\":\"\",\"cape_url\":\"" + TEXTURE + "\"}",
                "{\"animated_cape_url\":17,\"cape_url\":\"" + TEXTURE + "\"}",
                "{\"animated_cape_url\":\"bad URI\",\"cape_url\":\"" + TEXTURE + "\"}",
                "{\"cape_url\":\"" + TEXTURE + "\",\"cape_url\":null}",
                "{\"capeGlint\":true,\"capeGlint\":false}",
                "{\"capeGlint\":\"true\"}",
                "{\"cape_url\":\"" + "x".repeat(2049) + "\"}")) {
            assertThrows(IOException.class, () -> MinecraftCapesCapeReader.parse(json(malformed)), malformed);
        }
        assertThrows(IOException.class, () -> MinecraftCapesCapeReader.parse(json("{\"deep\":" + "[".repeat(13) + "0" + "]".repeat(13) + "}")));
        assertThrows(IOException.class, () -> MinecraftCapesCapeReader.parse(json("[" + "0,".repeat(128) + "0]")));
    }

    @Test void imageUriWhitelistRejectsAliasesCredentialsEscapesAndOtherActors() {
        for (String path : List.of(TEXTURE, GIF,
                "https://textures.minecraftcapes.net/capes/" + "a".repeat(16),
                "https://textures.minecraftcapes.net/capes/" + "a".repeat(128),
                "https://textures.minecraftcapes.net/sprite/" + "c".repeat(64),
                "https://api.minecraftcapes.net/profile/" + ACTOR.toString().replace("-", "") + "/cape")) {
            assertEquals(URI.create(path), MinecraftCapesCapeReader.allowedImage(ACTOR, URI.create(path)));
        }
        for (String invalid : List.of(
                "http://textures.minecraftcapes.net/capes/" + "a".repeat(64),
                "https://textures.minecraftcapes.net:443/capes/" + "a".repeat(64),
                "https://user@textures.minecraftcapes.net/capes/" + "a".repeat(64),
                TEXTURE + "?query=1", TEXTURE + "#fragment",
                "https://textures.minecraftcapes.net.evil.test/capes/" + "a".repeat(64),
                "https://127.0.0.1/capes/" + "a".repeat(64),
                "https://textures.minecraftcapes.net/capes/" + "a".repeat(15),
                "https://textures.minecraftcapes.net/capes/" + "a".repeat(129),
                "https://textures.minecraftcapes.net/animated/" + "a".repeat(63),
                "https://textures.minecraftcapes.net/capes/%61" + "a".repeat(63),
                "https://textures.minecraftcapes.net/capes/" + "g".repeat(64),
                "https://api.minecraftcapes.net/profile/" + "f".repeat(32) + "/cape",
                "https://api.minecraftcapes.net/profile/" + ACTOR.toString().replace("-", "") + "/cape/")) {
            assertThrows(IllegalArgumentException.class,
                    () -> MinecraftCapesCapeReader.allowedImage(ACTOR, URI.create(invalid)), invalid);
        }
    }

    @Test void selectedGifFailureNeverFallsBackToPngAndPreservesReadError() {
        FakeTransport transport = new FakeTransport();
        transport.profileBody = json(profile(GIF, TEXTURE, true));
        transport.imageBytes = new byte[]{'G', 'I', 'F'};
        MinecraftCapesCapeReader reader = reader(transport);
        assertEquals(MinecraftCapesCapeReader.Kind.FAILURE, reader.read(ACTOR, reader.accountEpoch()).kind());
        assertEquals(1, transport.profileCalls.size());
        assertEquals(1, transport.imageCalls.size());
        assertEquals(GIF, withoutNonce(transport.imageCalls.get(0).uri()));
        assertTrue(emptyScratch());
    }

    @Test void preferredGifWithInvalidUriDoesNotRequestFallbackImage() {
        FakeTransport transport = new FakeTransport();
        transport.profileBody = json(profile("https://127.0.0.1/capes/" + "a".repeat(64), TEXTURE, false));
        MinecraftCapesCapeReader reader = reader(transport);
        assertEquals(MinecraftCapesCapeReader.Kind.FAILURE, reader.read(ACTOR, reader.accountEpoch()).kind());
        assertEquals(1, transport.profileCalls.size());
        assertTrue(transport.imageCalls.isEmpty());
        transport.profileBody = json(profile("https://textures.minecraftcapes.net/animated/" + "a".repeat(63), TEXTURE, false));
        assertEquals(MinecraftCapesCapeReader.Kind.FAILURE, reader.read(ACTOR, reader.accountEpoch()).kind());
        assertTrue(transport.imageCalls.isEmpty());
    }

    @Test void gifFieldCannotAcceptPngSignatureOrUsePngFallback() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.profileBody = json(profile(GIF, TEXTURE, false));
        transport.imageBytes = png(64, 32);
        MinecraftCapesCapeReader reader = reader(transport);
        assertEquals(MinecraftCapesCapeReader.Kind.FAILURE, reader.read(ACTOR, reader.accountEpoch()).kind());
        assertEquals(1, transport.imageCalls.size());
        assertEquals(GIF, withoutNonce(transport.imageCalls.get(0).uri()));
        assertTrue(emptyScratch());
    }

    @Test void pngReadUsesOneImageAndFreshNonceWithoutChangingVisualIdentity() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.profileBody = json(profile(null, TEXTURE, true));
        transport.imageBytes = png(64, 32);
        MinecraftCapesCapeReader reader = reader(transport);
        var first = reader.read(ACTOR, reader.accountEpoch());
        var second = reader.read(ACTOR, reader.accountEpoch());
        assertEquals(MinecraftCapesCapeReader.Kind.PRESENT, first.kind());
        assertEquals(MinecraftCapesCapeReader.Kind.PRESENT, second.kind());
        assertTrue(first.glint());
        assertEquals(1, first.asset().frameCount());
        assertEquals(first.asset().visualSha256(), second.asset().visualSha256());
        assertEquals(2, transport.profileCalls.size());
        assertEquals(2, transport.imageCalls.size());
        assertNotEquals(transport.profileCalls.get(0).uri(), transport.profileCalls.get(1).uri());
        assertNotEquals(transport.imageCalls.get(0).uri(), transport.imageCalls.get(1).uri());
        assertEquals(10, transport.profileCalls.get(0).timeout().toSeconds());
        assertEquals(120, transport.imageCalls.get(0).timeout().toSeconds());
        assertEquals(MinecraftCapesCapeReader.MAX_PROFILE_WIRE_BYTES,
                transport.profileCalls.get(0).maxBytes());
        assertEquals(CapeAssetDecoder.MAX_ENCODED_BYTES, transport.imageCalls.get(0).maxBytes());
        assertEquals(TEXTURE, withoutNonce(transport.imageCalls.get(0).uri()));
        Files.delete(first.asset().backing());
        Files.delete(second.asset().backing());
        assertTrue(emptyScratch());
    }

    @Test void confirmedAbsenceDiffersFromMalformedProfileAndImage404() {
        FakeTransport transport = new FakeTransport();
        MinecraftCapesCapeReader reader = reader(transport);
        for (int status : new int[]{204, 404}) {
            transport.profileStatus = status;
            assertEquals(MinecraftCapesCapeReader.Kind.ABSENT, reader.read(ACTOR, reader.accountEpoch()).kind());
        }
        transport.profileStatus = 200;
        transport.profileBody = json("{\"cape_url\":null}");
        assertEquals(MinecraftCapesCapeReader.Kind.ABSENT, reader.read(ACTOR, reader.accountEpoch()).kind());
        transport.profileBody = json("{\"cape_url\":\"\"}");
        assertEquals(MinecraftCapesCapeReader.Kind.FAILURE, reader.read(ACTOR, reader.accountEpoch()).kind());
        transport.profileBody = json(profile(null, TEXTURE, false));
        transport.imageStatus = 404;
        assertEquals(MinecraftCapesCapeReader.Kind.FAILURE, reader.read(ACTOR, reader.accountEpoch()).kind());
        assertTrue(emptyScratch());
    }

    @Test void redirectsAndServerErrorsNeverBecomeConfirmedAbsence() {
        FakeTransport transport = new FakeTransport();
        MinecraftCapesCapeReader reader = reader(transport);
        for (int status : new int[]{301, 302, 307, 308, 500}) {
            transport.profileStatus = status;
            transport.profileHeaders = Map.of("Location", List.of("https://127.0.0.1/private"));
            assertEquals(MinecraftCapesCapeReader.Kind.FAILURE,
                    reader.read(ACTOR, reader.accountEpoch()).kind());
            assertTrue(transport.imageCalls.isEmpty());
        }
        transport.profileStatus = 200;
        transport.profileBody = json(profile(null, TEXTURE, false));
        for (int status : new int[]{301, 302, 307, 308, 404, 500}) {
            transport.imageStatus = status;
            transport.imageHeaders = Map.of("Location", List.of("https://127.0.0.1/private"));
            assertEquals(MinecraftCapesCapeReader.Kind.FAILURE,
                    reader.read(ACTOR, reader.accountEpoch()).kind());
        }
        assertEquals(6, transport.imageCalls.size());
        assertTrue(emptyScratch());
    }

    @Test void cooldownFromEitherHopSuppressesAllLaterRequestsWithoutNonceGeneration() {
        FakeTransport transport = new FakeTransport();
        MutableClock clock = new MutableClock();
        CountingRandom random = new CountingRandom();
        MinecraftCapesCapeReader reader = reader(transport, clock, new AtomicLong(), random);
        transport.profileStatus = 429;
        transport.profileHeaders = Map.of("Retry-After", List.of("120"));
        assertTrue(reader.read(ACTOR, reader.accountEpoch()).rateLimited());
        int calls = transport.profileCalls.size();
        int nonces = random.calls;
        assertTrue(reader.read(ACTOR, reader.accountEpoch()).rateLimited());
        assertEquals(calls, transport.profileCalls.size());
        assertEquals(nonces, random.calls);
        assertEquals(120, reader.cooldownRemaining().orElseThrow().toSeconds());
        clock.advance(Duration.ofSeconds(120));
        transport.profileStatus = 200;
        transport.profileHeaders = Map.of();
        transport.profileBody = json(profile(null, TEXTURE, false));
        transport.imageStatus = 429;
        transport.imageHeaders = Map.of("Retry-After", List.of("30"));
        assertTrue(reader.read(ACTOR, reader.accountEpoch()).rateLimited());
        assertEquals(1, transport.imageCalls.size());
        assertTrue(reader.read(ACTOR, reader.accountEpoch()).rateLimited());
        assertEquals(1, transport.imageCalls.size());
        assertEquals(30, reader.cooldownRemaining().orElseThrow().toSeconds());
    }

    @Test void retryAfterDateQuotaAndMalformedHeadersUseFiniteProviderCooldown() {
        FakeTransport transport = new FakeTransport();
        MutableClock clock = new MutableClock();
        MinecraftCapesCapeReader reader = reader(transport, clock, new AtomicLong(), new CountingRandom());
        transport.profileStatus = 429;
        transport.profileHeaders = Map.of("Retry-After", List.of("bad", "30"));
        assertTrue(reader.read(ACTOR, reader.accountEpoch()).rateLimited());
        assertEquals(60, reader.cooldownRemaining().orElseThrow().toSeconds());
        clock.advance(Duration.ofSeconds(60));
        transport.profileHeaders = Map.of("Retry-After", List.of(DateTimeFormatter.RFC_1123_DATE_TIME
                .format(clock.instant().plusSeconds(90).atZone(ZoneOffset.UTC))));
        assertTrue(reader.read(ACTOR, reader.accountEpoch()).rateLimited());
        assertEquals(90, reader.cooldownRemaining().orElseThrow().toSeconds());
        clock.advance(Duration.ofSeconds(90));
        transport.profileStatus = 200;
        transport.profileHeaders = Map.of("X-RateLimit-Remaining", List.of("0"),
                "X-RateLimit-Reset", List.of(Long.toString(clock.instant().plusSeconds(25).getEpochSecond())));
        transport.profileBody = json("{}");
        assertEquals(MinecraftCapesCapeReader.Kind.ABSENT, reader.read(ACTOR, reader.accountEpoch()).kind());
        assertEquals(25, reader.cooldownRemaining().orElseThrow().toSeconds());
    }

    @Test void profileAndImageBoundsAndDeadlineAreCheckedBeforeDecode() throws Exception {
        FakeTransport transport = new FakeTransport();
        AtomicLong nanos = new AtomicLong();
        MinecraftCapesCapeReader reader = reader(transport, new MutableClock(), nanos, new CountingRandom());
        transport.profileBody = json("{" + " ".repeat(MinecraftCapesCapeReader.MAX_PROFILE_BYTES - 2) + "}");
        assertEquals(MinecraftCapesCapeReader.MAX_PROFILE_BYTES, transport.profileBody.length);
        assertEquals(MinecraftCapesCapeReader.Kind.ABSENT, reader.read(ACTOR, reader.accountEpoch()).kind());
        transport.profileBody = json("{\"a\":\"" + "x".repeat(2048) + "\",\"b\":\""
                + "x".repeat(2048) + "\",\"c\":\"" + "x".repeat(2048)
                + "\",\"d\":\"" + "x".repeat(2048) + "\",\"e\":\""
                + "x".repeat(2048) + "\",\"f\":\"" + "x".repeat(2048)
                + "\",\"g\":\"" + "x".repeat(2048) + "\",\"h\":\""
                + "x".repeat(2048) + "\"}");
        assertEquals(MinecraftCapesCapeReader.Kind.FAILURE, reader.read(ACTOR, reader.accountEpoch()).kind());
        transport.profileBody = json(profile(null, TEXTURE, false));
        transport.afterProfile = () -> nanos.addAndGet(Duration.ofSeconds(131).toNanos());
        assertEquals(MinecraftCapesCapeReader.Kind.FAILURE, reader.read(ACTOR, reader.accountEpoch()).kind());
        assertTrue(transport.imageCalls.isEmpty());
        transport.afterProfile = () -> nanos.addAndGet(Duration.ofSeconds(15).toNanos());
        transport.imageStatus = 404;
        reader.read(ACTOR, reader.accountEpoch());
        assertEquals(115, transport.imageCalls.get(0).timeout().toSeconds());
        transport.imageStatus = 200;
        transport.afterProfile = () -> {};
        transport.sparseImageSize = CapeAssetDecoder.MAX_ENCODED_BYTES + 1;
        assertEquals(MinecraftCapesCapeReader.Kind.FAILURE, reader.read(ACTOR, reader.accountEpoch()).kind());
        assertTrue(emptyScratch());
    }

    @Test void accountEpochChangeDuringProfileCannotPublishConfirmedAbsence() {
        FakeTransport transport = new FakeTransport();
        transport.profileStatus = 204;
        MinecraftCapesCapeReader reader = reader(transport);
        transport.afterProfile = reader::accountChanged;
        assertEquals(MinecraftCapesCapeReader.Kind.FAILURE, reader.read(ACTOR, reader.accountEpoch()).kind());
        assertEquals(1, transport.profileCalls.size());
        assertEquals(MinecraftCapesCapeReader.Kind.FAILURE, reader.read(ACTOR, 0).kind());
        assertEquals(1, transport.profileCalls.size());
    }

    private MinecraftCapesCapeReader reader(FakeTransport transport) {
        return reader(transport, new MutableClock(), new AtomicLong(), new CountingRandom());
    }

    private MinecraftCapesCapeReader reader(FakeTransport transport, Clock clock,
            AtomicLong nanos, SecureRandom random) {
        return new MinecraftCapesCapeReader(transport, new CapeAssetDecoder(), clock,
                nanos::get, random, scratch);
    }

    private boolean emptyScratch() {
        try (var files = Files.list(scratch)) { return files.findAny().isEmpty(); }
        catch (IOException failure) { throw new AssertionError(failure); }
    }

    private static String withoutNonce(URI uri) {
        assertTrue(uri.getRawQuery().matches("nclskins_refresh=[0-9a-f]{32}"));
        return uri.toString().substring(0, uri.toString().indexOf('?'));
    }

    private static String profile(String gif, String png, boolean glint) {
        return "{\"animated_cape_url\":" + (gif == null ? "null" : "\"" + gif + "\"")
                + ",\"cape_url\":" + (png == null ? "null" : "\"" + png + "\"")
                + ",\"capeGlint\":" + glint + "}";
    }

    private static byte[] json(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, 0xff56aabb);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", bytes));
        return bytes.toByteArray();
    }

    private static byte[] gif() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var output = new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)) {
            var writer = ImageIO.getImageWritersByFormatName("gif").next();
            try {
                writer.setOutput(output);
                writer.prepareWriteSequence(null);
                for (int color : new int[]{0xff335577, 0xff668822}) {
                    BufferedImage frame = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
                    frame.setRGB(3, 5, color);
                    writer.writeToSequence(new javax.imageio.IIOImage(frame, null, null), null);
                }
                writer.endWriteSequence();
            } finally { writer.dispose(); }
        }
        return bytes.toByteArray();
    }

    private record Call(URI uri, Duration timeout, long maxBytes) {}

    private static final class FakeTransport implements MinecraftCapesCapeReader.Transport {
        final List<Call> profileCalls = new ArrayList<>();
        final List<Call> imageCalls = new ArrayList<>();
        int profileStatus = 200;
        int imageStatus = 200;
        byte[] profileBody = json("{}");
        byte[] imageBytes = new byte[0];
        long sparseImageSize;
        Map<String, List<String>> profileHeaders = Map.of();
        Map<String, List<String>> imageHeaders = Map.of();
        Runnable afterProfile = () -> {};

        public MinecraftCapesCapeReader.ProfileResponse profile(URI uri, Duration timeout,
                long maxBytes) throws IOException {
            profileCalls.add(new Call(uri, timeout, maxBytes));
            afterProfile.run();
            if (profileBody.length > maxBytes) throw new IOException("Profile wire limit exceeded");
            return new MinecraftCapesCapeReader.ProfileResponse(profileStatus,
                    profileStatus == 200 ? MinecraftCapesCapeReader.parse(profileBody) : null,
                    profileHeaders);
        }

        public MinecraftCapesCapeReader.Response image(URI uri, Duration timeout, long maxBytes,
                Path destination) throws IOException {
            imageCalls.add(new Call(uri, timeout, maxBytes));
            if (imageStatus == 200) {
                if (sparseImageSize > 0) {
                    try (FileChannel channel = FileChannel.open(destination,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        channel.position(sparseImageSize - 1);
                        channel.write(ByteBuffer.wrap(new byte[]{0}));
                    }
                } else Files.write(destination, imageBytes, StandardOpenOption.CREATE_NEW);
            }
            return new MinecraftCapesCapeReader.Response(imageStatus, new byte[0], imageHeaders);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-30T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class CountingRandom extends SecureRandom {
        private static final long serialVersionUID = 1L;
        int calls;
        @Override public void nextBytes(byte[] bytes) {
            calls++;
            java.util.Arrays.fill(bytes, (byte) calls);
        }
    }
}

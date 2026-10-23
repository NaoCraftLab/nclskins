package com.naocraftlab.skins.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.client.EncodedTextureLimit;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.test.TestPng;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextureCacheTest {
    private static final URI SOURCE = URI.create("https://textures.minecraft.net/texture/test-texture");

    @TempDir
    Path temporaryDirectory;

    @Test
    void largeEncodedCapeCacheFallbackPreservesRectangleAndPixels() throws Exception {
        TextureCache cache = cache(new FakeHttpClient(200, SOURCE, new byte[0]), TextureCache.DEFAULT_MAX_BYTES);
        Path path = cache.cachePath(SOURCE);
        Files.createDirectories(path.getParent());
        byte[] raw = new byte[32 * (1 + 64 * 4)];
        byte[] emptyBlocks = new byte[8190];
        for (int offset = 0; offset < emptyBlocks.length; offset += 5) {
            emptyBlocks[offset + 3] = (byte) 255;
            emptyBlocks[offset + 4] = (byte) 255;
        }
        int blocks = 14_000_000;
        int idatLength = 2 + blocks * 5 + 5 + raw.length + 4;
        var adler = new java.util.zip.Adler32(); adler.update(raw);
        try (var output = new java.io.DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(path)))) {
            output.write(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            output.write(pngChunk("IHDR", java.nio.ByteBuffer.allocate(13).putInt(64).putInt(32)
                    .put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0).array()));
            output.writeInt(idatLength);
            byte[] type = "IDAT".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            output.write(type);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(type);
            byte[] zlibHeader = {(byte) 0x78, 1}; output.write(zlibHeader); crc.update(zlibHeader);
            int remaining = blocks * 5;
            while (remaining > 0) {
                int count = Math.min(remaining, emptyBlocks.length);
                output.write(emptyBlocks, 0, count); crc.update(emptyBlocks, 0, count); remaining -= count;
            }
            byte[] finalHeader = {1, (byte) raw.length, (byte) (raw.length >>> 8),
                    (byte) ~raw.length, (byte) (~raw.length >>> 8)};
            output.write(finalHeader); crc.update(finalHeader);
            output.write(raw); crc.update(raw);
            byte[] checksum = java.nio.ByteBuffer.allocate(4).putInt((int) adler.getValue()).array();
            output.write(checksum); crc.update(checksum);
            output.writeInt((int) crc.getValue());
            output.write(pngChunk("IEND", new byte[0]));
        }
        assertTrue(Files.size(path) > 65 * 1024 * 1024);
        byte[] restored = cache.readIfCached(SOURCE).orElseThrow();
        var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(restored));
        assertEquals(64, image.getWidth());
        assertEquals(32, image.getHeight());
        assertEquals(0, image.getRGB(20, 20));
        assertTrue(restored.length < 1024);
    }

    private static byte[] pngChunk(String name, byte[] data) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        var output = new java.io.DataOutputStream(bytes);
        byte[] type = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        output.writeInt(data.length); output.write(type); output.write(data);
        var crc = new java.util.zip.CRC32(); crc.update(type); crc.update(data);
        output.writeInt((int) crc.getValue());
        return bytes.toByteArray();
    }

    @Test
    void rejectsHostBeforeAnyNetworkRequest() throws Exception {
        FakeHttpClient http = new FakeHttpClient(200, SOURCE, TestPng.create(64, 64));
        TextureCache cache = cache(http, TextureCache.DEFAULT_MAX_BYTES);

        TextureCacheException exception = assertThrows(
                TextureCacheException.class,
                () -> cache.get(URI.create("https://evil.example/texture/test")));

        assertEquals(TextureCacheException.Code.HOST_NOT_ALLOWLISTED, exception.code());
        assertEquals(0, http.calls.get());
    }

    @Test
    void observedCapeCachesNormalizedBytesWithoutCreatingPersonalCard() throws Exception {
        NclSkinsStorage storage = new NclSkinsStorage(temporaryDirectory,
                new PngValidator(), Clock.systemUTC());
        TextureCache cache = new TextureCache(storage);
        var cape = new PngValidator().projectImportedCape(TestPng.create(46, 22));
        String key = cache.storeObservedCape(cape);
        assertEquals(cape.renderSha256(), key);
        assertTrue(cache.readIfCached(key).isPresent());
        assertEquals(key, cache.storeObservedCape(cape));
        assertTrue(new TextureCache(new NclSkinsStorage(temporaryDirectory,
                new PngValidator(), Clock.systemUTC())).readIfCached(key).isPresent());
    }

    @Test
    void hdObservedCapeReplaysCanonicalPixelsAndVerifiesExistingCache() throws Exception {
        NclSkinsStorage storage = new NclSkinsStorage(temporaryDirectory,
                new PngValidator(), Clock.systemUTC());
        TextureCache cache = new TextureCache(storage);
        PngValidator validator = new PngValidator();
        var cape = validator.projectImportedCape(TestPng.create(92, 44));
        String key = cache.storeObservedCape(cape);
        byte[] restored = new TextureCache(new NclSkinsStorage(temporaryDirectory,
                validator, Clock.systemUTC())).readIfCached(key).orElseThrow();
        assertArrayEquals(cape.bytes(), restored);
        assertEquals(128, javax.imageio.ImageIO.read(new ByteArrayInputStream(restored)).getWidth());
        assertEquals(key, cache.storeObservedCape(cape));
        Files.write(cache.cachePath(key), TestPng.create(92, 44));
        assertThrows(PngValidationException.class, () -> cache.storeObservedCape(cape));
        Files.write(cache.cachePath(key), TestPng.create(128, 64));
        TextureCacheException failure = assertThrows(TextureCacheException.class,
                () -> cache.storeObservedCape(cape));
        assertEquals(TextureCacheException.Code.INVALID_TEXTURE, failure.code());
    }

    @Test
    void rejectsRedirectAndCrossHostResponse() throws Exception {
        FakeHttpClient redirect = new FakeHttpClient(302, SOURCE, new byte[0]);
        TextureCacheException redirectFailure = assertThrows(
                TextureCacheException.class,
                () -> cache(redirect, TextureCache.DEFAULT_MAX_BYTES).get(SOURCE));
        assertEquals(TextureCacheException.Code.REDIRECT_REJECTED, redirectFailure.code());

        FakeHttpClient crossHost = new FakeHttpClient(
                200,
                URI.create("https://evil.example/texture/test"),
                TestPng.create(64, 64));
        TextureCacheException hostFailure = assertThrows(
                TextureCacheException.class,
                () -> cache(crossHost, TextureCache.DEFAULT_MAX_BYTES).get(SOURCE));
        assertEquals(TextureCacheException.Code.REDIRECT_REJECTED, hostFailure.code());
    }

    @Test
    void rejectsOversizedResponseBeforeImageDecode() throws Exception {
        FakeHttpClient http = new FakeHttpClient(200, SOURCE, new byte[1025]);
        TextureCacheException exception = assertThrows(
                TextureCacheException.class,
                () -> cache(http, 1024).get(SOURCE));
        assertEquals(TextureCacheException.Code.OVERSIZED, exception.code());
    }

    @Test
    void rejectsAnOversizedEntryThatReplacesAnExistingCachePath() throws Exception {
        TextureCache cache = cache(new FakeHttpClient(new IOException("must not download")), 1024);
        Files.createDirectories(temporaryDirectory.resolve("nclskins/cache/textures"));
        Files.write(cache.cachePath(SOURCE), new byte[1025]);

        TextureCacheException exception = assertThrows(
                TextureCacheException.class, () -> cache.readIfCached(SOURCE));

        assertEquals(TextureCacheException.Code.OVERSIZED, exception.code());
    }

    @Test
    void persistedCacheUsesInclusiveSharedTextureBoundary() throws Exception {
        TextureCache cache = cache(new FakeHttpClient(new IOException("must not download")),
                TextureCache.DEFAULT_MAX_BYTES);
        Path path = cache.cachePath(SOURCE);
        Files.createDirectories(path.getParent());
        try (var file = new java.io.RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES);
        }
        assertEquals(TextureCacheException.Code.INVALID_TEXTURE,
                assertThrows(TextureCacheException.class, () -> cache.readIfCachedPath(SOURCE)).code());
        try (var file = new java.io.RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES + 1L);
        }
        assertEquals(TextureCacheException.Code.OVERSIZED,
                assertThrows(TextureCacheException.class,
                        () -> cache.readIfCachedPath(SOURCE)).code());
    }

    @Test
    void pathLookupsValidateCacheWithoutChangingEncodedIdentity() throws Exception {
        byte[] source = TestPng.create(64, 64);
        TextureCache cache = cache(new FakeHttpClient(200, SOURCE, source), TextureCache.DEFAULT_MAX_BYTES);
        CachedTexture cached = cache.get(SOURCE);
        assertEquals(cached.path(), cache.readPath(cached));
        assertEquals(cached.path(), cache.readIfCachedPath(SOURCE).orElseThrow());
        assertEquals(cached.path(), cache.readIfCachedPath(TextureCache.cacheKey(SOURCE)).orElseThrow());
        org.junit.jupiter.api.Assertions.assertArrayEquals(source, Files.readAllBytes(cached.path()));
        byte[] corrupt = source.clone(); corrupt[corrupt.length - 5] ^= 1;
        Files.write(cached.path(), corrupt);
        assertEquals(TextureCacheException.Code.INVALID_TEXTURE,
                assertThrows(TextureCacheException.class, () -> cache.readIfCachedPath(SOURCE)).code());
    }

    @Test
    void writesAtomicallyThenReusesCacheWithoutNetwork() throws Exception {
        byte[] png = TestPng.create(64, 32);
        FakeHttpClient http = new FakeHttpClient(200, SOURCE, png);
        TextureCache firstCache = cache(http, TextureCache.DEFAULT_MAX_BYTES);

        CachedTexture downloaded = firstCache.get(SOURCE);
        assertFalse(downloaded.cacheHit());
        assertEquals(1, http.calls.get());
        assertTrue(Files.isRegularFile(downloaded.path()));

        FakeHttpClient unavailable = new FakeHttpClient(new IOException("offline"));
        CachedTexture cached = cache(unavailable, TextureCache.DEFAULT_MAX_BYTES).get(SOURCE);
        assertTrue(cached.cacheHit());
        assertEquals(0, unavailable.calls.get());
        assertEquals(downloaded.path(), cached.path());
    }

    @Test
    void concurrentMissesShareOneDownload() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        FakeHttpClient http = new FakeHttpClient(
                200, SOURCE, TestPng.create(64, 64), requestStarted, releaseRequest);
        TextureCache cache = cache(http, TextureCache.DEFAULT_MAX_BYTES);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            CompletableFuture<CachedTexture> first = CompletableFuture.supplyAsync(() -> get(cache), workers);
            assertTrue(requestStarted.await(2, TimeUnit.SECONDS));
            var followers = java.util.stream.IntStream.range(0, 15)
                    .mapToObj(ignored -> CompletableFuture.supplyAsync(() -> get(cache), workers))
                    .toList();
            releaseRequest.countDown();

            assertFalse(first.get(2, TimeUnit.SECONDS).cacheHit());
            for (CompletableFuture<CachedTexture> follower : followers) {
                assertTrue(follower.get(2, TimeUnit.SECONDS).cacheHit());
            }
            assertEquals(1, http.calls.get());
        } finally {
            releaseRequest.countDown();
            workers.shutdownNow();
        }
    }

    private static CachedTexture get(TextureCache cache) {
        try {
            return cache.get(SOURCE);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private TextureCache cache(HttpClient client, int maxBytes) {
        NclSkinsStorage storage = new NclSkinsStorage(
                temporaryDirectory.resolve("nclskins"),
                new PngValidator(),
                Clock.systemUTC());
        return new TextureCache(storage, client, Duration.ofSeconds(1), maxBytes);
    }

    private static final class FakeHttpClient extends HttpClient {
        private final Integer status;
        private final URI responseUri;
        private final byte[] body;
        private final IOException failure;
        private final CountDownLatch requestStarted;
        private final CountDownLatch releaseRequest;
        private final AtomicInteger calls = new AtomicInteger();

        private FakeHttpClient(int status, URI responseUri, byte[] body) {
            this.status = status;
            this.responseUri = responseUri;
            this.body = body.clone();
            this.failure = null;
            this.requestStarted = null;
            this.releaseRequest = null;
        }

        private FakeHttpClient(
                int status,
                URI responseUri,
                byte[] body,
                CountDownLatch requestStarted,
                CountDownLatch releaseRequest) {
            this.status = status;
            this.responseUri = responseUri;
            this.body = body.clone();
            this.failure = null;
            this.requestStarted = requestStarted;
            this.releaseRequest = releaseRequest;
        }

        private FakeHttpClient(IOException failure) {
            this.status = null;
            this.responseUri = null;
            this.body = null;
            this.failure = failure;
            this.requestStarted = null;
            this.releaseRequest = null;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(1));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            calls.incrementAndGet();
            if (requestStarted != null) {
                requestStarted.countDown();
                releaseRequest.await();
            }
            if (failure != null) {
                throw failure;
            }
            return (HttpResponse<T>) new FakeResponse(request, status, responseUri, body);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            try {
                return CompletableFuture.completedFuture(send(request, responseBodyHandler));
            } catch (IOException exception) {
                return CompletableFuture.failedFuture(exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return CompletableFuture.failedFuture(exception);
            }
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }
    }

    private record FakeResponse(
            HttpRequest request,
            int statusCode,
            URI uri,
            byte[] bytes) implements HttpResponse<InputStream> {
        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (first, second) -> true);
        }

        @Override
        public InputStream body() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}

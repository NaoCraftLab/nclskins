package com.naocraftlab.skins.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.core.png.PngValidator;
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

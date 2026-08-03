package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.core.api.MinecraftServiceUriPolicy;
import com.naocraftlab.skins.core.model.RemoteCape;
import com.naocraftlab.skins.core.model.RemoteSkin;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import javax.imageio.ImageIO;


public final class TextureCache {
    public static final int DEFAULT_MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_PIXELS = 16L * 1024L * 1024L;
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private final NclSkinsStorage storage;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final int maxBytes;

    public TextureCache(NclSkinsStorage storage) {
        this(
                storage,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                Duration.ofSeconds(20),
                DEFAULT_MAX_BYTES);
    }

    TextureCache(
            NclSkinsStorage storage,
            HttpClient httpClient,
            Duration requestTimeout,
            int maxBytes) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (maxBytes < 1024) {
            throw new IllegalArgumentException("maxBytes is unreasonably small");
        }
        this.maxBytes = maxBytes;
    }

    public CachedTexture get(RemoteSkin skin) throws IOException {
        return get(Objects.requireNonNull(skin, "skin").textureUri());
    }

    public CachedTexture get(RemoteCape cape) throws IOException {
        return get(Objects.requireNonNull(cape, "cape").textureUri());
    }

    @SuppressWarnings("try")
    public CachedTexture get(URI source) throws IOException {
        Objects.requireNonNull(source, "source");
        if (!MinecraftServiceUriPolicy.isAllowedTextureUri(source)) {
            throw new TextureCacheException(
                    TextureCacheException.Code.HOST_NOT_ALLOWLISTED,
                    "Texture host is not allowlisted.");
        }
        storage.initialize();
        String cacheKey = cacheKey(source);
        Path cachedPath = storage.layout().textureCache().resolve(cacheKey + ".png");
        CachedTexture existing = readCached(source, cachedPath);
        if (existing != null) {
            return existing;
        }

        try (ProcessFileLock ignored = storage.acquireTextureCacheLock(cacheKey)) {
            CachedTexture raced = readCached(source, cachedPath);
            if (raced != null) {
                return raced;
            }
            return download(source, cachedPath);
        }
    }

    private CachedTexture download(URI source, Path cachedPath) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(source)
                .timeout(requestTimeout)
                .header("Accept", "image/png")
                .header("User-Agent", "NCL-Skin/0.1")
                .GET()
                .build();
        final HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TextureCacheException(
                    TextureCacheException.Code.NETWORK_FAILURE,
                    "Official texture could not be downloaded.");
        } catch (IOException exception) {
            throw new TextureCacheException(
                    TextureCacheException.Code.NETWORK_FAILURE,
                    "Official texture could not be downloaded.");
        }

        if (!sameOrigin(source, response.uri()) || response.statusCode() >= 300 && response.statusCode() < 400) {
            closeQuietly(response.body());
            throw new TextureCacheException(
                    TextureCacheException.Code.REDIRECT_REJECTED,
                    "Texture redirect was rejected.");
        }
        if (response.statusCode() != 200) {
            closeQuietly(response.body());
            throw new TextureCacheException(
                    TextureCacheException.Code.HTTP_FAILURE,
                    "Official texture service returned an unexpected status.");
        }

        byte[] body;
        try (InputStream input = response.body()) {
            body = input.readNBytes(maxBytes + 1);
        }
        if (body.length > maxBytes) {
            throw new TextureCacheException(
                    TextureCacheException.Code.OVERSIZED,
                    "Official texture exceeds the cache size limit.");
        }
        Dimensions dimensions = decodeDimensions(body);
        AtomicFileWriter.replace(cachedPath, body);
        return new CachedTexture(source, cachedPath, false, dimensions.width(), dimensions.height());
    }

    public Path cachePath(URI source) throws TextureCacheException {
        Objects.requireNonNull(source, "source");
        if (!MinecraftServiceUriPolicy.isAllowedTextureUri(source)) {
            throw new TextureCacheException(
                    TextureCacheException.Code.HOST_NOT_ALLOWLISTED,
                    "Texture host is not allowlisted.");
        }
        return storage.layout().textureCache().resolve(cacheKey(source) + ".png");
    }


    public byte[] read(CachedTexture cached) throws IOException {
        Objects.requireNonNull(cached, "cached");
        return readBounded(cached.path());
    }


    public java.util.Optional<byte[]> readIfCached(URI source) throws IOException {
        Path path = cachePath(source);
        if (!Files.isRegularFile(path)) {
            return java.util.Optional.empty();
        }
        byte[] bytes = readBounded(path);
        return bytes.length == 0 ? java.util.Optional.empty() : java.util.Optional.of(bytes);
    }


    public static String cacheKey(URI source) {
        Objects.requireNonNull(source, "source");
        return sha256(source.normalize().toASCIIString());
    }


    public java.util.Optional<byte[]> readIfCached(String cacheKey) throws IOException {
        Objects.requireNonNull(cacheKey, "cacheKey");
        if (!cacheKey.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("texture cache key is invalid");
        }
        Path path = storage.layout().textureCache().resolve(cacheKey + ".png");
        if (!Files.isRegularFile(path)) {
            return java.util.Optional.empty();
        }
        byte[] bytes = readBounded(path);
        decodeDimensions(bytes);
        return java.util.Optional.of(bytes);
    }


    public Path cachePath(String cacheKey) {
        Objects.requireNonNull(cacheKey, "cacheKey");
        if (!cacheKey.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("texture cache key is invalid");
        }
        return storage.layout().textureCache().resolve(cacheKey + ".png");
    }

    private CachedTexture readCached(URI source, Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            byte[] bytes = readBounded(path);
            if (bytes.length == 0) {
                return null;
            }
            Dimensions dimensions = decodeDimensions(bytes);
            return new CachedTexture(source, path, true, dimensions.width(), dimensions.height());
        } catch (TextureCacheException invalidCacheEntry) {
            return null;
        }
    }

    private byte[] readBounded(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new TextureCacheException(
                        TextureCacheException.Code.OVERSIZED,
                        "Official texture exceeds the cache size limit.");
            }
            return bytes;
        }
    }

    private static Dimensions decodeDimensions(byte[] bytes) throws TextureCacheException {
        if (bytes.length < PNG_SIGNATURE.length) {
            throw invalidTexture();
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (bytes[index] != PNG_SIGNATURE[index]) {
                throw invalidTexture();
            }
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null
                    || image.getWidth() <= 0
                    || image.getHeight() <= 0
                    || image.getWidth() > MAX_DIMENSION
                    || image.getHeight() > MAX_DIMENSION
                    || (long) image.getWidth() * image.getHeight() > MAX_PIXELS) {
                throw invalidTexture();
            }
            image.getRGB(0, 0);
            return new Dimensions(image.getWidth(), image.getHeight());
        } catch (IOException | RuntimeException exception) {
            throw invalidTexture();
        }
    }

    private static TextureCacheException invalidTexture() {
        return new TextureCacheException(
                TextureCacheException.Code.INVALID_TEXTURE,
                "Official texture response is not a supported PNG.");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    private static boolean sameOrigin(URI expected, URI actual) {
        return expected.getScheme().equalsIgnoreCase(actual.getScheme())
                && expected.getHost().equalsIgnoreCase(actual.getHost())
                && effectivePort(expected) == effectivePort(actual);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {

        }
    }

    private record Dimensions(int width, int height) {}
}

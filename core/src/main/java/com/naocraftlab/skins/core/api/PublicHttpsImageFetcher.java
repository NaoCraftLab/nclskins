package com.naocraftlab.skins.core.api;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class PublicHttpsImageFetcher {
    private static final int MAX_URI_BYTES = 2048;
    private static final int MAX_QUERY_BYTES = 1024;
    private final SafeRemotePngFetcher admission;
    private final PinnedHttpsTransport transport;
    private final LongSupplier nanoTime;

    public PublicHttpsImageFetcher() {
        this(new SafeRemotePngFetcher(), new PinnedHttpsTransport(), System::nanoTime);
    }

    PublicHttpsImageFetcher(SafeRemotePngFetcher admission,
            PinnedHttpsTransport transport, LongSupplier nanoTime) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public Response get(URI uri, Duration timeout, int maxBytes) throws IOException {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || maxBytes <= 0) {
            throw new IllegalArgumentException("Invalid image request bounds");
        }
        String exact = uri.toString();
        String query = uri.getRawQuery();
        if (!exact.equals(uri.toASCIIString()) || exact.length() > MAX_URI_BYTES
                || !"https".equals(uri.getScheme())
                || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() != -1 && uri.getPort() != 443
                || uri.getRawPath() == null || !uri.getRawPath().startsWith("/")
                || uri.getRawPath().length() > MAX_URI_BYTES
                || query != null && query.length() > MAX_QUERY_BYTES
                || uri.getRawAuthority() == null || uri.getRawAuthority().indexOf('@') >= 0) {
            throw new UnsafeImageUriException();
        }
        long started = nanoTime.getAsLong();
        SafeRemotePngFetcher.ValidatedUri validated;
        try {
            validated = admission.validate(exact, timeout);
        } catch (PublicSkinImportException invalid) {
            if (invalid.code() == PublicSkinImportException.Code.UNSAFE_URL) {
                throw new UnsafeImageUriException();
            }
            throw new IOException("Public image host resolution failed");
        }
        if (!uri.equals(validated.uri())) throw new UnsafeImageUriException();
        long remaining = timeout.toNanos() - (nanoTime.getAsLong() - started);
        if (remaining <= 0) throw new IOException("Public image request timed out");
        try {
            PinnedHttpsTransport.Response response = transport.get(uri, validated.asciiHost(),
                    validated.addresses(), Duration.ofNanos(remaining), maxBytes);
            if (!uri.equals(response.uri())) throw new IOException("Public image response URI changed");
            return new Response(response.statusCode(), response.body(), response.headers());
        } catch (PinnedHttpsTransport.BodyTooLargeException oversized) {
            throw new OversizedImageException();
        }
    }

    public record Response(int status, byte[] bytes, Map<String, List<String>> headers) {
        public Response {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        }

        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public static final class UnsafeImageUriException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    public static final class OversizedImageException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}

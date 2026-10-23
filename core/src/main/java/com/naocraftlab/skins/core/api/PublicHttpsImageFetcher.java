package com.naocraftlab.skins.core.api;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
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

    public PublicHttpsImageFetcher(Duration connectTimeout) {
        this(new SafeRemotePngFetcher(), new PinnedHttpsTransport(connectTimeout),
                System::nanoTime);
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

    public Response fixedRequest(String method, URI uri, Map<String,String> headers, byte[] body,
            Duration timeout, int maximum) throws IOException {
        return fixedRequest(method, uri, headers, body, timeout, maximum, () -> true);
    }

    public Response fixedRequest(String method, URI uri, Map<String,String> headers, byte[] body,
            Duration timeout, int maximum, java.util.function.BooleanSupplier beforeSend) throws IOException {
        Objects.requireNonNull(beforeSend);
        Objects.requireNonNull(uri);Objects.requireNonNull(headers);Objects.requireNonNull(body);Objects.requireNonNull(timeout);
        String exact=uri.toASCIIString();
        boolean paste=exact.equals("https://paste.tom5454.com/api/session")
                || exact.equals("https://paste.tom5454.com/api/list")
                || exact.equals("https://paste.tom5454.com/api/upload")
                || exact.matches("https://paste\\.tom5454\\.com/api/connect\\?name=[A-Za-z0-9_]{1,16}");
        boolean join=exact.equals("https://sessionserver.mojang.com/session/minecraft/join");
        boolean post=exact.endsWith("/api/upload") || join;
        if ((!paste && !join) || !exact.equals(uri.toString()) || !(post?"POST":"GET").equals(method)
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30))>0
                || maximum<=0 || maximum>(exact.endsWith("/api/list")?262144:65536)
                || !post && body.length!=0) throw new UnsafeImageUriException();
        java.util.Set<String> allowed=join?java.util.Set.of("Content-Type","Accept","Content-Length")
                :java.util.Set.of("Session","File-Name","Content-Length","Content-Type","Accept");
        for(var header:headers.entrySet()) {
            boolean filename = paste && post && header.getKey().equals("File-Name");
            if (!allowed.contains(header.getKey()) || (filename
                    ? !com.naocraftlab.skins.client.CpmPublicationName.valid(header.getValue())
                    : header.getKey().equals("File-Name") || header.getValue().length()>4096
                    || !header.getValue().matches("[\\x20-\\x7e]*"))) throw new UnsafeImageUriException();
        }
        if(post && !Integer.toString(body.length).equals(headers.get("Content-Length"))) throw new UnsafeImageUriException();
        if(paste && !exact.contains("/api/connect?") && (headers.get("Session")==null
                || !headers.get("Session").matches("[!-~]{1,1024}"))) throw new UnsafeImageUriException();
        if(paste && post && !com.naocraftlab.skins.client.CpmPublicationName.valid(headers.get("File-Name"))) throw new UnsafeImageUriException();
        long started=nanoTime.getAsLong();
        if (!beforeSend.getAsBoolean()) throw new FixedRequestException(false);
        SafeRemotePngFetcher.ValidatedUri validated;
        try { validated=admission.validate(exact,timeout); }
        catch(PublicSkinImportException rejected) { throw new FixedRequestException(false); }
        if(!uri.equals(validated.uri())) throw new UnsafeImageUriException();
        long remaining=timeout.toNanos()-(nanoTime.getAsLong()-started);
        if(remaining<=0) throw new FixedRequestException(false);
        if (!beforeSend.getAsBoolean()) throw new FixedRequestException(false);
        var response=transport.fixed(uri,validated.asciiHost(),validated.addresses(),Duration.ofNanos(remaining),maximum,method,headers,body,beforeSend);
        return new Response(response.statusCode(),response.body(),response.headers());
    }

    public static final class FixedRequestException extends IOException {
        private static final long serialVersionUID=1L;
        private final boolean possibleSend;
        public FixedRequestException(boolean possibleSend) { super("Fixed request failed");this.possibleSend=possibleSend; }
        public boolean possibleSend() { return possibleSend; }
    }

    public interface FixedRequestScope extends java.util.function.BooleanSupplier {
        boolean beforeSend();
    }

    public <T> StreamResponse<T> getStreaming(URI uri, Duration timeout, long maxBytes, BodyReader<T> reader) throws IOException {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(reader, "reader");
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
            PinnedHttpsTransport.StreamResponse<T> response = transport.getStreaming(uri, validated.asciiHost(),
                    validated.addresses(), Duration.ofNanos(remaining), maxBytes, reader);
            if (!uri.equals(response.uri())) throw new IOException("Public image response URI changed");
            return new StreamResponse<>(response.statusCode(), response.body(), response.headers());
        } catch (PinnedHttpsTransport.BodyTooLargeException oversized) {
            throw new OversizedImageException();
        }
    }

    public FileResponse getToFile(URI uri, Duration timeout, long maxBytes, Path destination)
            throws IOException {
        Objects.requireNonNull(destination, "destination");
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
            PinnedHttpsTransport.FileResponse response = transport.getToFile(uri,
                    validated.asciiHost(), validated.addresses(), Duration.ofNanos(remaining),
                    Math.min(maxBytes, com.naocraftlab.skins.client.EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES), destination);
            if (!uri.equals(response.uri())) throw new IOException("Public image response URI changed");
            return new FileResponse(response.statusCode(), response.bodyBytes(), response.headers());
        } catch (PinnedHttpsTransport.BodyTooLargeException oversized) {
            throw new OversizedImageException();
        }
    }

    @FunctionalInterface public interface BodyReader<T> {
        T read(java.io.InputStream body) throws IOException;
    }

    public record StreamResponse<T>(int status, T body, Map<String, List<String>> headers) {
        public StreamResponse { headers = Map.copyOf(Objects.requireNonNull(headers, "headers")); }
    }

    public record Response(int status, byte[] bytes, Map<String, List<String>> headers) {
        public Response {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        }

        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public record FileResponse(int status, long bytes, Map<String, List<String>> headers) {
        public FileResponse {
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        }
    }

    public static final class UnsafeImageUriException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    public static final class OversizedImageException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}

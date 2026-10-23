package com.naocraftlab.skins.core.api;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;


class PinnedHttpsTransport {
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_HEADER_COUNT = 64;
    private static final int MAX_ADDRESS_ATTEMPTS = 2;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final SSLSocketFactory sslSockets;
    private final SocketConnector connector;
    private final LongSupplier nanoTime;
    private final Duration connectTimeout;

    PinnedHttpsTransport() {
        this((SSLSocketFactory) SSLSocketFactory.getDefault(),
                SocketConnector.SYSTEM,
                System::nanoTime, CONNECT_TIMEOUT);
    }

    PinnedHttpsTransport(Duration connectTimeout) {
        this((SSLSocketFactory) SSLSocketFactory.getDefault(),
                SocketConnector.SYSTEM, System::nanoTime, connectTimeout);
    }

    PinnedHttpsTransport(SSLSocketFactory sslSockets, SocketConnector connector) {
        this(sslSockets, connector, System::nanoTime, CONNECT_TIMEOUT);
    }

    PinnedHttpsTransport(
            SSLSocketFactory sslSockets, SocketConnector connector, LongSupplier nanoTime) {
        this(sslSockets, connector, nanoTime, CONNECT_TIMEOUT);
    }

    PinnedHttpsTransport(SSLSocketFactory sslSockets, SocketConnector connector,
            LongSupplier nanoTime, Duration connectTimeout) {
        this.sslSockets = Objects.requireNonNull(sslSockets, "sslSockets");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
    }

    Response get(
            URI uri,
            String asciiHost,
            List<InetAddress> addresses,
            Duration timeout,
            int maxBodyBytes) throws IOException {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(asciiHost, "asciiHost");
        Objects.requireNonNull(addresses, "addresses");
        Objects.requireNonNull(timeout, "timeout");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean validPort = "https".equals(scheme)
                ? uri.getPort() == -1 || uri.getPort() == 443
                : "http".equals(scheme) && (uri.getPort() == -1 || uri.getPort() == 80);
        if (!validPort || addresses.isEmpty() || timeout.isZero() || timeout.isNegative()
                || maxBodyBytes <= 0) {
            throw new IllegalArgumentException("Pinned HTTP request bounds are invalid");
        }
        long deadline = saturatedAdd(nanoTime.getAsLong(), timeout.toNanos());
        IOException failure = null;
        int attempts = Math.min(addresses.size(), MAX_ADDRESS_ATTEMPTS);
        for (int index = 0; index < attempts; index++) {
            InetAddress address = Objects.requireNonNull(addresses.get(index), "addresses contains null");
            try {
                return exchange(uri, asciiHost, address, deadline, maxBodyBytes);
            } catch (ResponseObservedException observed) {
                throw observed;
            } catch (IOException candidate) {
                if (failure == null) {
                    failure = candidate;
                } else {
                    failure.addSuppressed(candidate);
                }
            }
        }
        throw failure == null ? new IOException("Pinned HTTP connection failed") : failure;
    }

    Response fixed(URI uri, String asciiHost, List<InetAddress> addresses, Duration timeout,
            int maximum, String method, Map<String,String> headers, byte[] body) throws IOException {
        return fixed(uri, asciiHost, addresses, timeout, maximum, method, headers, body, () -> true);
    }

    Response fixed(URI uri, String asciiHost, List<InetAddress> addresses, Duration timeout,
            int maximum, String method, Map<String,String> headers, byte[] body,
            java.util.function.BooleanSupplier beforeSend) throws IOException {
        if (!"https".equals(uri.getScheme()) || addresses.isEmpty() || timeout.isZero() || timeout.isNegative()
                || maximum <= 0 || maximum > 262144 || !(method.equals("GET") || method.equals("POST")))
            throw new IllegalArgumentException("Invalid fixed request bounds");
        long deadline = saturatedAdd(nanoTime.getAsLong(), timeout.toNanos());
        boolean[] possibleSend = {false};
        for (var header : headers.entrySet()) {
            boolean filename = "POST".equals(method)
                    && uri.toString().equals("https://paste.tom5454.com/api/upload")
                    && asciiHost.equals("paste.tom5454.com") && header.getKey().equals("File-Name");
            if (!header.getKey().matches("[A-Za-z-]+") || (filename
                    ? !com.naocraftlab.skins.client.CpmPublicationName.valid(header.getValue())
                    : header.getKey().equals("File-Name") || header.getValue().length() > 4096
                    || containsControl(header.getValue())
                    || !StandardCharsets.US_ASCII.newEncoder().canEncode(header.getValue())))
                throw new PublicHttpsImageFetcher.FixedRequestException(false);
        }
        InetAddress address = addresses.get(0);
        try (Socket plain = connector.connect(address, 443, remainingMillis(deadline, connectTimeout))) {
            CompletableFuture<Void> finished = new CompletableFuture<>();
            finished.orTimeout(remainingMillis(deadline, null), TimeUnit.MILLISECONDS).exceptionally(failure -> {
                abortFixed(plain);
                return null;
            });
            try {
            if (!(plain.getRemoteSocketAddress() instanceof InetSocketAddress remote) || !address.equals(remote.getAddress()))
                throw new IOException("Unexpected pinned address");
            try (SSLSocket tls = (SSLSocket) sslSockets.createSocket(plain, asciiHost, 443, true)) {
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");parameters.setApplicationProtocols(new String[]{"http/1.1"});
                parameters.setServerNames(List.of(new SNIHostName(asciiHost)));tls.setSSLParameters(parameters);
                tls.setSoTimeout(remainingMillis(deadline,null));tls.startHandshake();
                String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                if (containsControl(target) || containsControl(asciiHost)) throw new IOException("Invalid fixed target");
                StringBuilder request = new StringBuilder(method + " " + target + " HTTP/1.1\r\nHost: " + asciiHost
                        + "\r\nConnection: close\r\nAccept-Encoding: identity\r\n");
                for (var header : headers.entrySet()) {
                    request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
                }
                request.append("\r\n");
                OutputStream output = tls.getOutputStream();
                BufferedInputStream input = new BufferedInputStream(new DeadlineInputStream(tls,tls.getInputStream(),deadline));
                    remainingMillis(deadline,null);
                    if (!beforeSend.getAsBoolean()) throw new IOException("Fixed request retired");
                    if (beforeSend instanceof PublicHttpsImageFetcher.FixedRequestScope scope && !scope.beforeSend())
                        throw new IOException("Fixed request retired");
                    possibleSend[0] = true;
                    output.write(request.toString().getBytes(StandardCharsets.UTF_8));output.write(body);output.flush();
                    ResponseHead head = readHead(input);
                    byte[] response = new byte[0];
                    if (head.status() != 204 && head.status() >= 200 && head.status() < 300) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        copyResponseBody(input,head.headers(),bytes,maximum);response = bytes.toByteArray();
                    }
                    remainingMillis(deadline,null);
                    return new Response(head.status(),uri,head.headers(),response);
            }
            } finally { finished.complete(null); }
        } catch (IOException failure) { throw new PublicHttpsImageFetcher.FixedRequestException(possibleSend[0]); }
    }

    private static void abortFixed(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    <T> StreamResponse<T> getStreaming(URI uri, String asciiHost, List<InetAddress> addresses,
            Duration timeout, long maxBodyBytes, PublicHttpsImageFetcher.BodyReader<T> reader) throws IOException {
        Objects.requireNonNull(reader, "reader");
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        boolean validPort = secure ? uri.getPort() == -1 || uri.getPort() == 443
                : "http".equalsIgnoreCase(uri.getScheme()) && (uri.getPort() == -1 || uri.getPort() == 80);
        if (!validPort || addresses.isEmpty() || timeout.isZero() || timeout.isNegative()
                || maxBodyBytes <= 0) {
            throw new IllegalArgumentException("Pinned image request bounds are invalid");
        }
        long deadline = saturatedAdd(nanoTime.getAsLong(), timeout.toNanos());
        IOException failure = null;
        int attempts = Math.min(addresses.size(), MAX_ADDRESS_ATTEMPTS);
        for (int index = 0; index < attempts; index++) {
            InetAddress address = Objects.requireNonNull(addresses.get(index), "addresses contains null");
            try {
                return exchangeStreaming(uri, asciiHost, address, deadline,
                        maxBodyBytes, reader);
            } catch (ResponseObservedException observed) {
                throw observed;
            } catch (IOException candidate) {
                if (failure == null) failure = candidate;
                else failure.addSuppressed(candidate);
            }
        }
        throw failure == null ? new IOException("Pinned HTTP connection failed") : failure;
    }

    private <T> StreamResponse<T> exchangeStreaming(URI uri, String asciiHost, InetAddress address,
            long deadline, long maxBodyBytes, PublicHttpsImageFetcher.BodyReader<T> reader) throws IOException {
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        int port = secure ? 443 : 80;
        int connectMillis = remainingMillis(deadline, connectTimeout);
        try (Socket plain = connector.connect(address, port, connectMillis)) {
            if (!(plain.getRemoteSocketAddress() instanceof InetSocketAddress remote)
                    || !address.equals(remote.getAddress())) {
                throw new IOException("Pinned HTTP socket connected to an unexpected address");
            }
            if (secure) {
                try (SSLSocket tls = (SSLSocket) sslSockets.createSocket(plain, asciiHost, port, true)) {
                    SSLParameters parameters = tls.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    parameters.setApplicationProtocols(new String[]{"http/1.1"});
                    if (!isIpLiteral(asciiHost)) parameters.setServerNames(List.of(new SNIHostName(asciiHost)));
                    tls.setSSLParameters(parameters);
                    tls.setSoTimeout(remainingMillis(deadline, null));
                    tls.startHandshake();
                    tls.setSoTimeout(remainingMillis(deadline, null));
                    return exchangeStreamsStreaming(tls, uri, asciiHost, deadline, maxBodyBytes, reader);
                }
            }
            plain.setSoTimeout(remainingMillis(deadline, null));
            return exchangeStreamsStreaming(plain, uri, asciiHost, deadline, maxBodyBytes, reader);
        }
    }

    private <T> StreamResponse<T> exchangeStreamsStreaming(Socket socket, URI uri, String asciiHost,
            long deadline, long maxBodyBytes, PublicHttpsImageFetcher.BodyReader<T> reader) throws IOException {
        try (BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
             BufferedInputStream input = new BufferedInputStream(
                     new DeadlineInputStream(socket, socket.getInputStream(), deadline))) {
            writeRequest(output, uri, asciiHost, "https".equalsIgnoreCase(uri.getScheme()));
            output.flush();
            StreamResponse<T> response = readResponseStreaming(input, uri, maxBodyBytes, reader,
                    () -> remainingMillis(deadline, null));
            remainingMillis(deadline, null);
            return response;
        } catch (ResponseObservedException observed) {
            throw observed;
        } catch (IOException responseFailure) {
            throw new ResponseObservedException("Pinned HTTP response could not be read", responseFailure);
        }
    }

    FileResponse getToFile(URI uri, String asciiHost, List<InetAddress> addresses,
            Duration timeout, long maxBodyBytes, Path destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        boolean validPort = secure ? uri.getPort() == -1 || uri.getPort() == 443
                : "http".equalsIgnoreCase(uri.getScheme()) && (uri.getPort() == -1 || uri.getPort() == 80);
        if (!validPort || addresses.isEmpty() || timeout.isZero() || timeout.isNegative()
                || maxBodyBytes <= 0) {
            throw new IllegalArgumentException("Pinned image request bounds are invalid");
        }
        long deadline = saturatedAdd(nanoTime.getAsLong(), timeout.toNanos());
        IOException failure = null;
        int attempts = Math.min(addresses.size(), MAX_ADDRESS_ATTEMPTS);
        for (int index = 0; index < attempts; index++) {
            InetAddress address = Objects.requireNonNull(addresses.get(index), "addresses contains null");
            try {
                return exchangeToFile(uri, asciiHost, address, deadline,
                        Math.min(maxBodyBytes, com.naocraftlab.skins.client.EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES), destination);
            } catch (ResponseObservedException observed) {
                throw observed;
            } catch (IOException candidate) {
                if (failure == null) failure = candidate;
                else failure.addSuppressed(candidate);
            }
        }
        throw failure == null ? new IOException("Pinned HTTP connection failed") : failure;
    }

    private FileResponse exchangeToFile(URI uri, String asciiHost, InetAddress address,
            long deadline, long maxBodyBytes, Path destination) throws IOException {
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        int port = secure ? 443 : 80;
        int connectMillis = remainingMillis(deadline, connectTimeout);
        try (Socket plain = connector.connect(address, port, connectMillis)) {
            if (!(plain.getRemoteSocketAddress() instanceof InetSocketAddress remote)
                    || !address.equals(remote.getAddress())) {
                throw new IOException("Pinned HTTP socket connected to an unexpected address");
            }
            if (secure) {
                try (SSLSocket tls = (SSLSocket) sslSockets.createSocket(plain, asciiHost, port, true)) {
                    SSLParameters parameters = tls.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    parameters.setApplicationProtocols(new String[]{"http/1.1"});
                    if (!isIpLiteral(asciiHost)) parameters.setServerNames(List.of(new SNIHostName(asciiHost)));
                    tls.setSSLParameters(parameters);
                    tls.setSoTimeout(remainingMillis(deadline, null));
                    tls.startHandshake();
                    tls.setSoTimeout(remainingMillis(deadline, null));
                    return exchangeStreamsToFile(tls, uri, asciiHost, deadline, maxBodyBytes, destination);
                }
            }
            plain.setSoTimeout(remainingMillis(deadline, null));
            return exchangeStreamsToFile(plain, uri, asciiHost, deadline, maxBodyBytes, destination);
        }
    }

    private FileResponse exchangeStreamsToFile(Socket socket, URI uri, String asciiHost,
            long deadline, long maxBodyBytes, Path destination) throws IOException {
        try (BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
             BufferedInputStream input = new BufferedInputStream(
                     new DeadlineInputStream(socket, socket.getInputStream(), deadline))) {
            writeRequest(output, uri, asciiHost, "https".equalsIgnoreCase(uri.getScheme()));
            output.flush();
            FileResponse response = readResponseToFile(input, uri, maxBodyBytes, destination);
            remainingMillis(deadline, null);
            return response;
        } catch (ResponseObservedException observed) {
            throw observed;
        } catch (IOException responseFailure) {
            throw new ResponseObservedException("Pinned HTTP response could not be read", responseFailure);
        }
    }

    private Response exchange(
            URI uri,
            String asciiHost,
            InetAddress address,
            long deadline,
            int maxBodyBytes) throws IOException {
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        int port = secure ? 443 : 80;
        int connectMillis = remainingMillis(deadline, connectTimeout);
        try (Socket plain = connector.connect(address, port, connectMillis)) {
            if (!(plain.getRemoteSocketAddress() instanceof InetSocketAddress remote)
                    || !address.equals(remote.getAddress())) {
                throw new IOException("Pinned HTTP socket connected to an unexpected address");
            }
            if (secure) {
                try (SSLSocket tls = (SSLSocket) sslSockets.createSocket(
                        plain, asciiHost, port, true)) {
                    SSLParameters parameters = tls.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    parameters.setApplicationProtocols(new String[]{"http/1.1"});
                    if (!isIpLiteral(asciiHost)) {
                        parameters.setServerNames(List.of(new SNIHostName(asciiHost)));
                    }
                    tls.setSSLParameters(parameters);
                    tls.setSoTimeout(remainingMillis(deadline, null));
                    tls.startHandshake();
                    tls.setSoTimeout(remainingMillis(deadline, null));
                    return exchangeStreams(tls, uri, asciiHost, deadline, maxBodyBytes);
                }
            }
            plain.setSoTimeout(remainingMillis(deadline, null));
            return exchangeStreams(plain, uri, asciiHost, deadline, maxBodyBytes);
        }
    }

    private Response exchangeStreams(
            Socket socket, URI uri, String asciiHost, long deadline, int maxBodyBytes)
            throws IOException {
        try (BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
             BufferedInputStream input = new BufferedInputStream(
                     new DeadlineInputStream(socket, socket.getInputStream(), deadline))) {
            remainingMillis(deadline, null);
            writeRequest(output, uri, asciiHost,
                    uri.getRawQuery() != null && uri.getRawQuery().startsWith("nclskins_refresh="));
            output.flush();
            remainingMillis(deadline, null);
            try {
                Response response = readResponse(input, uri, maxBodyBytes);
                remainingMillis(deadline, null);
                return response;
            } catch (ResponseObservedException observed) {
                throw observed;
            } catch (IOException responseFailure) {
                throw new ResponseObservedException(
                        "Pinned HTTP response could not be read", responseFailure);
            }
        }
    }

    private static void writeRequest(BufferedOutputStream output, URI uri, String asciiHost)
            throws IOException {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (uri.getRawQuery() != null) {
            path += '?' + uri.getRawQuery();
        }
        if (containsControl(path) || containsControl(asciiHost)) {
            throw new IOException("Pinned HTTP request target is invalid");
        }
        String hostHeader = asciiHost.indexOf(':') >= 0 ? '[' + asciiHost + ']' : asciiHost;
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Accept: image/png, image/jpeg, application/octet-stream\r\n"
                + "Accept-Encoding: identity\r\n"
                + "User-Agent: NCL-Skin/0.1\r\n"
                + "Connection: close\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeRequest(BufferedOutputStream output, URI uri, String asciiHost,
            boolean freshness) throws IOException {
        if (!freshness) {
            writeRequest(output, uri, asciiHost);
            return;
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null) path += '?' + uri.getRawQuery();
        if (containsControl(path) || containsControl(asciiHost)) {
            throw new IOException("Pinned HTTP request target is invalid");
        }
        String hostHeader = asciiHost.indexOf(':') >= 0 ? '[' + asciiHost + ']' : asciiHost;
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Accept: image/png, image/gif, application/json, application/octet-stream\r\n"
                + "Accept-Encoding: identity\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Pragma: no-cache\r\n"
                + "User-Agent: NCL-Skin/0.1\r\n"
                + "Connection: close\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
    }

    private static ResponseHead readHead(InputStream input) throws IOException {
        HeaderReader reader = new HeaderReader(input);
        String statusLine = reader.line();
        if (statusLine == null || !statusLine.matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?")) {
            throw new IOException("Pinned HTTP response status is invalid");
        }
        int status = Integer.parseInt(statusLine.substring(9, 12));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        int count = 0;
        while (true) {
            String line = reader.line();
            if (line == null) {
                throw new EOFException("Pinned HTTP response headers are incomplete");
            }
            if (line.isEmpty()) {
                break;
            }
            if (++count > MAX_HEADER_COUNT || Character.isWhitespace(line.charAt(0))) {
                throw new IOException("Pinned HTTP response headers are invalid");
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IOException("Pinned HTTP response header is invalid");
            }
            String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if (!name.matches("[a-z0-9!#$%&'*+.^_`|~-]+") || containsControl(value)) {
                throw new IOException("Pinned HTTP response header is invalid");
            }
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        if (status >= 100 && status < 200) {
            throw new ResponseObservedException("Informational HTTP responses are unsupported");
        }
        return new ResponseHead(status, immutableHeaders(headers));
    }

    private record ResponseHead(int status, Map<String, List<String>> headers) {}

    static Response readResponse(InputStream input, URI requestUri, int maxBodyBytes)
            throws IOException {
        ResponseHead head = readHead(input);
        int status = head.status();
        Map<String, List<String>> headers = head.headers();
        byte[] body = status == 200 ? readBody(input, headers, maxBodyBytes) : new byte[0];
        return new Response(status, requestUri, immutableHeaders(headers), body);
    }

    static FileResponse readResponseToFile(InputStream input, URI requestUri,
            long maxBodyBytes, Path destination) throws IOException {
        ResponseHead head = readHead(input);
        int status = head.status();
        Map<String, List<String>> headers = head.headers();
        long bytes = 0;
        if (status == 200) {
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(destination,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                try {
                    bytes = copyResponseBody(input, headers, output, Math.min(maxBodyBytes,
                            com.naocraftlab.skins.client.EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES));
                } catch (IOException failure) {
                    try { Files.deleteIfExists(destination); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
        }
        return new FileResponse(status, requestUri, immutableHeaders(headers), bytes);
    }

    static long copyResponseBody(InputStream input, Map<String, List<String>> headers,
            OutputStream output, long maximum) throws IOException {
        try (FramedBodyInputStream body = new FramedBodyInputStream(input, headers, maximum, () -> {})) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = body.read(buffer)) != -1) {
                if (read != 0) output.write(buffer, 0, read);
            }
            return body.total;
        }
    }

    static <T> StreamResponse<T> readResponseStreaming(InputStream input, URI requestUri,
            long maximum, PublicHttpsImageFetcher.BodyReader<T> reader) throws IOException {
        return readResponseStreaming(input, requestUri, maximum, reader, () -> {});
    }

    private static <T> StreamResponse<T> readResponseStreaming(InputStream input, URI requestUri,
            long maximum, PublicHttpsImageFetcher.BodyReader<T> reader, IoCheck deadline) throws IOException {
        ResponseHead head = readHead(input);
        deadline.verify();
        if (head.status() != 200) return new StreamResponse<>(head.status(), requestUri, head.headers(), null);
        try (FramedBodyInputStream body = new FramedBodyInputStream(input, head.headers(), maximum, deadline)) {
            T value = reader.read(body);
            byte[] discard = new byte[8192];
            while (body.read(discard) != -1) { }
            deadline.verify();
            return new StreamResponse<>(head.status(), requestUri, head.headers(), value);
        }
    }

    @FunctionalInterface private interface IoCheck { void verify() throws IOException; }

    private static final class FramedBodyInputStream extends InputStream {
        private final InputStream input;
        private final long maximum;
        private final IoCheck deadline;
        private final boolean chunked;
        private final boolean fixed;
        private final HeaderReader chunks;
        private long remaining;
        private long total;
        private boolean delimiter;
        private boolean done;
        private boolean closed;

        private FramedBodyInputStream(InputStream input, Map<String, List<String>> headers,
                long maximum, IoCheck deadline) throws IOException {
            if (maximum <= 0) throw new IllegalArgumentException("Invalid HTTP body limit");
            this.input = Objects.requireNonNull(input);
            this.maximum = maximum;
            this.deadline = deadline;
            List<String> transfer = headers.getOrDefault("transfer-encoding", List.of());
            List<String> lengths = headers.getOrDefault("content-length", List.of());
            chunked = !transfer.isEmpty();
            fixed = !lengths.isEmpty();
            chunks = chunked ? new HeaderReader(input) : null;
            if (chunked) {
                if (transfer.size() != 1 || !"chunked".equalsIgnoreCase(transfer.get(0)) || fixed) {
                    throw new ResponseObservedException("HTTP response framing is ambiguous");
                }
            } else if (fixed) {
                if (lengths.size() != 1) throw new ResponseObservedException("HTTP response length is ambiguous");
                try { remaining = Long.parseLong(lengths.get(0)); }
                catch (NumberFormatException invalid) { throw new ResponseObservedException("HTTP response length is invalid", invalid); }
                if (remaining < 0 || remaining > maximum) throw new BodyTooLargeException();
            }
        }

        @Override public int read() throws IOException {
            byte[] value = new byte[1];
            return read(value, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(value[0]);
        }

        @Override public int read(byte[] bytes, int offset, int count) throws IOException {
            Objects.checkFromIndexSize(offset, count, bytes.length);
            if (closed) throw new IOException("HTTP body stream is closed");
            deadline.verify();
            if (count == 0) return 0;
            if (done) return -1;
            if (chunked && remaining == 0) nextChunk();
            if (done) return -1;
            if (fixed && remaining == 0) {
                int extra = input.read();
                deadline.verify();
                if (extra != -1) {
                    if (total == maximum) throw new BodyTooLargeException();
                    throw new ResponseObservedException("HTTP response exceeds its declared length");
                }
                done = true;
                return -1;
            }
            int requested = count;
            if (fixed || chunked) requested = (int) Math.min(requested, remaining);
            else if (maximum - total < requested) requested = (int) (maximum - total + 1);
            int read = input.read(bytes, offset, requested);
            deadline.verify();
            if (read < 0) {
                if (fixed || chunked) throw new EOFException("HTTP response body is incomplete");
                done = true;
                return -1;
            }
            if (read > maximum - total) throw new BodyTooLargeException();
            total += read;
            if (fixed || chunked) remaining -= read;
            return read;
        }

        private void nextChunk() throws IOException {
            if (delimiter && (input.read() != '\r' || input.read() != '\n')) {
                throw new ResponseObservedException("Chunked HTTP delimiter is invalid");
            }
            String line = chunks.line();
            if (line == null) throw new EOFException("Chunked HTTP response is incomplete");
            int extension = line.indexOf(';');
            String token = (extension >= 0 ? line.substring(0, extension) : line).trim();
            try { remaining = Long.parseLong(token, 16); }
            catch (NumberFormatException invalid) { throw new ResponseObservedException("Chunked HTTP response is invalid", invalid); }
            if (remaining < 0 || remaining > maximum - total) throw new BodyTooLargeException();
            if (remaining == 0) {
                while (true) {
                    String trailer = chunks.line();
                    if (trailer == null) throw new EOFException("Chunked HTTP trailers are incomplete");
                    if (trailer.isEmpty()) break;
                    if (trailer.indexOf(':') <= 0 || Character.isWhitespace(trailer.charAt(0))) {
                        throw new ResponseObservedException("Chunked HTTP trailer is invalid");
                    }
                }
                if (input.read() != -1) throw new ResponseObservedException("HTTP response exceeds its chunked framing");
                done = true;
            } else delimiter = true;
            deadline.verify();
        }

        @Override public void close() { closed = true; }
    }

    private static byte[] readBody(
            InputStream input, Map<String, List<String>> headers, int maxBodyBytes) throws IOException {
        int maximum = Math.min(maxBodyBytes, 64 * 1024);
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192));
        copyResponseBody(input, headers, output, maximum);
        return output.toByteArray();
    }

    private int remainingMillis(long deadline, Duration maximum) throws IOException {
        long remaining = deadline - nanoTime.getAsLong();
        if (remaining <= 0) {
            throw new IOException("Pinned HTTP request timed out");
        }
        long millis = Math.max(1L, (remaining + 999_999L) / 1_000_000L);
        if (maximum != null) {
            millis = Math.min(millis, maximum.toMillis());
        }
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x1f || character == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    private static long saturatedAdd(long left, long right) {
        return right > 0 && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static Map<String, List<String>> immutableHeaders(Map<String, List<String>> headers) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return Map.copyOf(copy);
    }

    record Response(int statusCode, URI uri, Map<String, List<String>> headers, byte[] body) {
        Response {
            Objects.requireNonNull(uri, "uri");
            headers = immutableHeaders(Objects.requireNonNull(headers, "headers"));
            body = Objects.requireNonNull(body, "body").clone();
        }

        String firstHeader(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of()).stream()
                    .findFirst()
                    .orElse("");
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    record StreamResponse<T>(int statusCode, URI uri, Map<String, List<String>> headers, T body) {
        StreamResponse { headers = immutableHeaders(headers); }
    }

    record FileResponse(int statusCode, URI uri, Map<String, List<String>> headers,
            long bodyBytes) {
        String firstHeader(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of()).stream()
                    .findFirst().orElse("");
        }
    }

    @FunctionalInterface
    interface SocketConnector {
        SocketConnector SYSTEM = (address, port, timeoutMillis) -> {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address, port), timeoutMillis);
                return socket;
            } catch (IOException | RuntimeException failure) {
                try {
                    socket.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        };

        Socket connect(InetAddress address, int port, int timeoutMillis) throws IOException;
    }

    private static final class HeaderReader {
        private final InputStream input;
        private int total;

        private HeaderReader(InputStream input) {
            this.input = Objects.requireNonNull(input, "input");
        }

        private String line() throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int previous = -1;
            while (true) {
                int value = input.read();
                if (value < 0) {
                    return null;
                }
                if (++total > MAX_HEADER_BYTES) {
                    throw new IOException("Pinned HTTP response headers exceed size limit");
                }
                if (previous == '\r' && value == '\n') {
                    byte[] bytes = output.toByteArray();
                    return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
                }
                if (previous == '\r') {
                    throw new IOException("Pinned HTTP response line ending is invalid");
                }
                if (value == 0 || value > 0x7f) {
                    throw new IOException("Pinned HTTP response headers are not ASCII");
                }
                output.write(value);
                previous = value;
            }
        }
    }

    private final class DeadlineInputStream extends InputStream {
        private final Socket socket;
        private final InputStream delegate;
        private final long deadline;

        private DeadlineInputStream(Socket socket, InputStream delegate, long deadline) {
            this.socket = Objects.requireNonNull(socket, "socket");
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.deadline = deadline;
        }

        @Override
        public int read() throws IOException {
            prepare();
            int value = delegate.read();
            verify();
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            prepare();
            int count = delegate.read(bytes, offset, length);
            verify();
            return count;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private void prepare() throws IOException {
            socket.setSoTimeout(remainingMillis(deadline, null));
        }

        private void verify() throws IOException {
            remainingMillis(deadline, null);
        }
    }

    private static class ResponseObservedException extends IOException {
        private static final long serialVersionUID = 1L;

        private ResponseObservedException(String message) {
            super(message);
        }

        private ResponseObservedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final class BodyTooLargeException extends ResponseObservedException {
        private static final long serialVersionUID = 1L;

        private BodyTooLargeException() {
            super("HTTP response exceeds size limit");
        }
    }
}

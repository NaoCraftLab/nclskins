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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;


class PinnedHttpsTransport {
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_HEADER_COUNT = 64;
    private static final int MAX_ADDRESS_ATTEMPTS = 2;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final SSLSocketFactory sslSockets;
    private final SocketConnector connector;
    private final LongSupplier nanoTime;

    PinnedHttpsTransport() {
        this((SSLSocketFactory) SSLSocketFactory.getDefault(),
                SocketConnector.SYSTEM,
                System::nanoTime);
    }

    PinnedHttpsTransport(SSLSocketFactory sslSockets, SocketConnector connector) {
        this(sslSockets, connector, System::nanoTime);
    }

    PinnedHttpsTransport(
            SSLSocketFactory sslSockets, SocketConnector connector, LongSupplier nanoTime) {
        this.sslSockets = Objects.requireNonNull(sslSockets, "sslSockets");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
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

    private Response exchange(
            URI uri,
            String asciiHost,
            InetAddress address,
            long deadline,
            int maxBodyBytes) throws IOException {
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        int port = secure ? 443 : 80;
        int connectMillis = remainingMillis(deadline, CONNECT_TIMEOUT);
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
            writeRequest(output, uri, asciiHost);
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
                + "Accept: image/png, application/octet-stream\r\n"
                + "Accept-Encoding: identity\r\n"
                + "User-Agent: NCL-Skin/0.1\r\n"
                + "Connection: close\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
    }

    static Response readResponse(InputStream input, URI requestUri, int maxBodyBytes)
            throws IOException {
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
        byte[] body = status == 200 ? readBody(input, headers, maxBodyBytes) : new byte[0];
        return new Response(status, requestUri, immutableHeaders(headers), body);
    }

    private static byte[] readBody(
            InputStream input, Map<String, List<String>> headers, int maxBodyBytes) throws IOException {
        List<String> transferEncoding = headers.getOrDefault("transfer-encoding", List.of());
        List<String> contentLength = headers.getOrDefault("content-length", List.of());
        if (!transferEncoding.isEmpty()) {
            if (transferEncoding.size() != 1
                    || !"chunked".equalsIgnoreCase(transferEncoding.get(0))) {
                throw new ResponseObservedException("HTTP transfer encoding is unsupported");
            }
            if (!contentLength.isEmpty()) {
                throw new ResponseObservedException("HTTP response framing is ambiguous");
            }
            return readChunked(input, maxBodyBytes);
        }
        if (!contentLength.isEmpty()) {
            if (contentLength.size() != 1) {
                throw new ResponseObservedException("HTTP response length is ambiguous");
            }
            final long length;
            try {
                length = Long.parseLong(contentLength.get(0));
            } catch (NumberFormatException invalid) {
                throw new ResponseObservedException("HTTP response length is invalid", invalid);
            }
            if (length < 0 || length > maxBodyBytes) {
                throw new BodyTooLargeException();
            }
            return readExact(input, (int) length);
        }
        return readUntilEof(input, maxBodyBytes);
    }

    private static byte[] readChunked(InputStream input, int maxBodyBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBodyBytes, 8192));
        HeaderReader reader = new HeaderReader(input);
        while (true) {
            String sizeLine = reader.line();
            if (sizeLine == null) {
                throw new EOFException("Chunked HTTP response is incomplete");
            }
            int extension = sizeLine.indexOf(';');
            String token = (extension >= 0 ? sizeLine.substring(0, extension) : sizeLine).trim();
            final long size;
            try {
                size = Long.parseLong(token, 16);
            } catch (NumberFormatException invalid) {
                throw new ResponseObservedException("Chunked HTTP response is invalid", invalid);
            }
            if (size < 0 || size > maxBodyBytes - output.size()) {
                throw new BodyTooLargeException();
            }
            if (size == 0) {
                while (true) {
                    String trailer = reader.line();
                    if (trailer == null) {
                        throw new EOFException("Chunked HTTP trailers are incomplete");
                    }
                    if (trailer.isEmpty()) {
                        return output.toByteArray();
                    }
                    if (trailer.indexOf(':') <= 0 || Character.isWhitespace(trailer.charAt(0))) {
                        throw new ResponseObservedException("Chunked HTTP trailer is invalid");
                    }
                }
            }
            output.writeBytes(readExact(input, (int) size));
            if (input.read() != '\r' || input.read() != '\n') {
                throw new ResponseObservedException("Chunked HTTP delimiter is invalid");
            }
        }
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("HTTP response body is incomplete");
        }
        return bytes;
    }

    private static byte[] readUntilEof(InputStream input, int maxBodyBytes) throws IOException {
        byte[] bytes = input.readNBytes(maxBodyBytes + 1);
        if (bytes.length > maxBodyBytes) {
            throw new BodyTooLargeException();
        }
        return bytes;
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

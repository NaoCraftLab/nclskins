package com.naocraftlab.skins.core.api;

import org.junit.jupiter.api.Test;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PinnedHttpsTransportTest {
    private static final URI REQUEST = URI.create("https://example.com/skin.png");

    @Test
    void parsesBoundedContentLengthBody() throws Exception {
        PinnedHttpsTransport.Response response = parse(
                "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nContent-Type: image/png\r\n\r\ntest", 4);

        assertEquals(200, response.statusCode());
        assertEquals("image/png", response.firstHeader("content-type"));
        assertArrayEquals("test".getBytes(StandardCharsets.US_ASCII), response.body());
    }

    @Test
    void parsesBoundedChunkedBody() throws Exception {
        PinnedHttpsTransport.Response response = parse(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + "2\r\nte\r\n2\r\nst\r\n0\r\n\r\n",
                4);

        assertArrayEquals("test".getBytes(StandardCharsets.US_ASCII), response.body());
    }

    @Test
    void rejectsAmbiguousOrOversizedResponseFraming() {
        assertThrows(IOException.class, () -> parse(
                "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + "0\r\n\r\n",
                4));
        assertThrows(PinnedHttpsTransport.BodyTooLargeException.class, () -> parse(
                "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\ntest!", 4));
    }

    @Test
    void doesNotReadRedirectBodies() throws Exception {
        byte[] headers = ("HTTP/1.1 302 Found\r\nLocation: /next\r\n"
                + "Content-Length: 1000000\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        InputStream failOnBody = new InputStream() {
            private int offset;

            @Override
            public int read() throws IOException {
                if (offset == headers.length) {
                    throw new IOException("redirect body was read");
                }
                return headers[offset++] & 0xff;
            }
        };

        PinnedHttpsTransport.Response response = PinnedHttpsTransport.readResponse(
                failOnBody, REQUEST, 1);

        assertEquals(302, response.statusCode());
        assertEquals("/next", response.firstHeader("Location"));
        assertArrayEquals(new byte[0], response.body());
    }

    @Test
    void plainHttpUsesPinnedPortAndCredentialFreeRequest() throws Exception {
        InetAddress address = InetAddress.getByName("8.8.8.8");
        AtomicInteger connectedPort = new AtomicInteger();
        FixtureSocket socket = new FixtureSocket(
                address,
                "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ntest");
        PinnedHttpsTransport transport = new PinnedHttpsTransport(
                (SSLSocketFactory) SSLSocketFactory.getDefault(),
                (connectedAddress, port, timeoutMillis) -> {
                    assertEquals(address, connectedAddress);
                    assertTrue(timeoutMillis > 0);
                    connectedPort.set(port);
                    return socket;
                });
        URI uri = URI.create("http://example.com/skin.png?download=1");

        PinnedHttpsTransport.Response response = transport.get(
                uri, "example.com", List.of(address), Duration.ofSeconds(1), 4);

        assertEquals(80, connectedPort.get());
        assertArrayEquals("test".getBytes(StandardCharsets.US_ASCII), response.body());
        String request = socket.written.toString(StandardCharsets.US_ASCII);
        assertTrue(request.startsWith("GET /skin.png?download=1 HTTP/1.1\r\n"));
        assertTrue(request.contains("Host: example.com\r\n"));
        assertTrue(request.contains("Accept-Encoding: identity\r\n"));
        assertFalse(request.toLowerCase().contains("authorization:"));
        assertFalse(request.toLowerCase().contains("cookie:"));
        assertFalse(request.toLowerCase().contains("referer:"));
    }

    @Test
    void httpsConfiguresSniAndEndpointIdentityBeforeHandshake() throws Exception {
        InetAddress address = InetAddress.getByName("8.8.8.8");
        FixtureSocket plain = new FixtureSocket(address, "");
        RecordingSslSocket tls = new RecordingSslSocket(
                "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ntest");
        RecordingSslSocketFactory sslFactory = new RecordingSslSocketFactory(tls);
        PinnedHttpsTransport transport = new PinnedHttpsTransport(
                sslFactory,
                (connectedAddress, port, timeoutMillis) -> {
                    assertEquals(address, connectedAddress);
                    assertEquals(443, port);
                    return plain;
                });

        PinnedHttpsTransport.Response response = transport.get(
                REQUEST, "example.com", List.of(address), Duration.ofSeconds(1), 4);

        assertEquals("example.com", sslFactory.peerHost);
        assertEquals(443, sslFactory.peerPort);
        assertTrue(sslFactory.autoClose);
        assertTrue(tls.handshakeStarted);
        assertEquals("HTTPS", tls.parameters.getEndpointIdentificationAlgorithm());
        assertEquals(1, tls.parameters.getServerNames().size());
        assertEquals("example.com",
                ((SNIHostName) tls.parameters.getServerNames().get(0)).getAsciiName());
        assertArrayEquals("test".getBytes(StandardCharsets.US_ASCII), response.body());
    }

    @Test
    void enforcesAbsoluteDeadlineWhileResponseKeepsProducingBytes() throws Exception {
        InetAddress address = InetAddress.getByName("8.8.8.8");
        AtomicLong now = new AtomicLong();
        byte[] response = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ntest"
                .getBytes(StandardCharsets.US_ASCII);
        InputStream slow = new ByteArrayInputStream(response) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length);
                now.addAndGet(Duration.ofSeconds(2).toNanos());
                return count;
            }
        };
        FixtureSocket socket = new FixtureSocket(address, slow);
        PinnedHttpsTransport transport = new PinnedHttpsTransport(
                (SSLSocketFactory) SSLSocketFactory.getDefault(),
                (connectedAddress, port, timeoutMillis) -> socket,
                now::get);

        assertThrows(IOException.class, () -> transport.get(
                URI.create("http://example.com/skin.png"),
                "example.com",
                List.of(address),
                Duration.ofSeconds(1),
                4));
    }

    private static PinnedHttpsTransport.Response parse(String response, int maxBodyBytes)
            throws IOException {
        return PinnedHttpsTransport.readResponse(
                new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII)),
                REQUEST,
                maxBodyBytes);
    }

    private static final class FixtureSocket extends Socket {
        private final InetSocketAddress remote;
        private final InputStream input;
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();

        private FixtureSocket(InetAddress address, String response) {
            this(address, new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII)));
        }

        private FixtureSocket(InetAddress address, InputStream response) {
            this.remote = new InetSocketAddress(address, 80);
            this.input = response;
        }

        @Override
        public InetSocketAddress getRemoteSocketAddress() {
            return remote;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public OutputStream getOutputStream() {
            return written;
        }

        @Override
        public synchronized void setSoTimeout(int timeout) {
        }

        @Override
        public synchronized void close() {
        }
    }

    private static final class RecordingSslSocketFactory extends SSLSocketFactory {
        private final RecordingSslSocket socket;
        private String peerHost;
        private int peerPort;
        private boolean autoClose;

        private RecordingSslSocketFactory(RecordingSslSocket socket) {
            this.socket = socket;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return new String[0];
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return new String[0];
        }

        @Override
        public Socket createSocket(Socket plain, String host, int port, boolean closePlain) {
            peerHost = host;
            peerPort = port;
            autoClose = closePlain;
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            throw new IOException("unsupported test overload");
        }

        @Override
        public Socket createSocket(
                String host, int port, InetAddress localAddress, int localPort) throws IOException {
            throw new IOException("unsupported test overload");
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            throw new IOException("unsupported test overload");
        }

        @Override
        public Socket createSocket(
                InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            throw new IOException("unsupported test overload");
        }
    }

    private static final class RecordingSslSocket extends SSLSocket {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private SSLParameters parameters = new SSLParameters();
        private boolean handshakeStarted;
        private String[] enabledCipherSuites = new String[0];
        private String[] enabledProtocols = new String[0];
        private boolean clientMode;
        private boolean needClientAuth;
        private boolean wantClientAuth;
        private boolean sessionCreation;

        private RecordingSslSocket(String response) {
            input = new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public SSLParameters getSSLParameters() {
            return parameters;
        }

        @Override
        public void setSSLParameters(SSLParameters parameters) {
            this.parameters = parameters;
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return new String[0];
        }

        @Override
        public String[] getEnabledCipherSuites() {
            return enabledCipherSuites.clone();
        }

        @Override
        public void setEnabledCipherSuites(String[] suites) {
            enabledCipherSuites = suites.clone();
        }

        @Override
        public String[] getSupportedProtocols() {
            return new String[0];
        }

        @Override
        public String[] getEnabledProtocols() {
            return enabledProtocols.clone();
        }

        @Override
        public void setEnabledProtocols(String[] protocols) {
            enabledProtocols = protocols.clone();
        }

        @Override
        public SSLSession getSession() {
            return null;
        }

        @Override
        public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
        }

        @Override
        public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
        }

        @Override
        public void startHandshake() {
            handshakeStarted = true;
        }

        @Override
        public void setUseClientMode(boolean mode) {
            clientMode = mode;
        }

        @Override
        public boolean getUseClientMode() {
            return clientMode;
        }

        @Override
        public void setNeedClientAuth(boolean need) {
            needClientAuth = need;
        }

        @Override
        public boolean getNeedClientAuth() {
            return needClientAuth;
        }

        @Override
        public void setWantClientAuth(boolean want) {
            wantClientAuth = want;
        }

        @Override
        public boolean getWantClientAuth() {
            return wantClientAuth;
        }

        @Override
        public void setEnableSessionCreation(boolean flag) {
            sessionCreation = flag;
        }

        @Override
        public boolean getEnableSessionCreation() {
            return sessionCreation;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public synchronized void setSoTimeout(int timeout) {
        }

        @Override
        public synchronized void close() {
        }
    }
}

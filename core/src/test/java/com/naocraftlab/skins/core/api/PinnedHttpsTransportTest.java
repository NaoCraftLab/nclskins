package com.naocraftlab.skins.core.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.nio.file.Files;
import java.nio.file.Path;
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

    @TempDir Path directory;

    static PinnedHttpsTransport fixedSocketTransport(String response, ByteArrayOutputStream output,
            AtomicInteger attempts) throws Exception {
        InetAddress first = InetAddress.getByName("8.8.8.8");
        RecordingSslSocket tls = new RecordingSslSocket(response);
        tls.outputOverride = output;
        return new PinnedHttpsTransport(new RecordingSslSocketFactory(tls), (address, port, timeout) -> {
            attempts.incrementAndGet();
            assertEquals(first, address);
            assertEquals(443, port);
            return new FixtureSocket(address, "");
        });
    }

    @Test
    void fixedRequestRechecksRetirementAfterTlsBeforeAnyWrite() throws Exception {
        for (String outcome : List.of("retired", "admission-rejected", "admitted")) {
            InetAddress address = InetAddress.getByName("8.8.8.8");
            FixtureSocket plain = new FixtureSocket(address, "");
            RecordingSslSocket tls = new RecordingSslSocket("HTTP/1.1 201 OK\r\nContent-Length: 2\r\n\r\n{}");
            var current = new java.util.concurrent.atomic.AtomicBoolean(true);
            AtomicInteger attempts = new AtomicInteger();
            AtomicInteger admissions = new AtomicInteger();
            tls.handshakeAction = () -> {
                assertTrue(current.get());
                if (outcome.equals("retired")) current.set(false);
            };
            var scope = new PublicHttpsImageFetcher.FixedRequestScope() {
                public boolean getAsBoolean() { return current.get(); }
                public boolean beforeSend() {
                    assertTrue(tls.handshakeStarted, "Admission must run after TLS");
                    assertEquals(0, tls.output.size(), "Admission must precede headers as well as model body");
                    admissions.incrementAndGet();
                    return !outcome.equals("admission-rejected");
                }
            };
            PinnedHttpsTransport transport = new PinnedHttpsTransport(new RecordingSslSocketFactory(tls),
                    (connected, port, timeout) -> {
                        attempts.incrementAndGet();
                        assertTrue(scope.getAsBoolean(), "Scope is current at connect");
                        return plain;
                    });
            if (outcome.equals("admitted")) {
                assertEquals(201, transport.fixed(REQUEST, "example.com", List.of(address), Duration.ofSeconds(2),
                        65536, "POST", java.util.Map.of("Content-Length", "4"),
                        "AAEC".getBytes(StandardCharsets.US_ASCII), scope).statusCode());
                assertTrue(tls.output.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\nAAEC"));
            } else {
                var failure = assertThrows(PublicHttpsImageFetcher.FixedRequestException.class,
                        () -> transport.fixed(REQUEST, "example.com", List.of(address), Duration.ofSeconds(2),
                                65536, "POST", java.util.Map.of("Content-Length", "4"),
                                "AAEC".getBytes(StandardCharsets.US_ASCII), scope));
                assertFalse(failure.possibleSend());
                assertEquals(0, tls.output.size());
            }
            assertTrue(tls.handshakeStarted);
            assertEquals(outcome.equals("retired") ? 0 : 1, admissions.get());
            assertEquals(1, attempts.get());
            assertTrue(tls.closed);
            assertTrue(plain.closed);
        }
    }

    @Test
    void fixedPublicationRequestHasOnePinnedAttemptAndExactBody() throws Exception {
        InetAddress address = InetAddress.getByName("8.8.8.8");
        FixtureSocket plain = new FixtureSocket(address, "");
        RecordingSslSocket tls = new RecordingSslSocket("HTTP/1.1 201 OK\r\nContent-Length: 2\r\n\r\n{}");
        AtomicInteger calls = new AtomicInteger();
        PinnedHttpsTransport transport = new PinnedHttpsTransport(new RecordingSslSocketFactory(tls),
                (connected, port, timeout) -> { calls.incrementAndGet(); return plain; });
        java.lang.reflect.Method operation;
        try { operation = PinnedHttpsTransport.class.getDeclaredMethod("fixed", URI.class, String.class,
                List.class, Duration.class, int.class, String.class, java.util.Map.class, byte[].class); }
        catch (NoSuchMethodException missing) { throw new AssertionError("Missing fixed no-replay operation", missing); }
        var response = (PinnedHttpsTransport.Response) operation.invoke(transport,
                URI.create("https://paste.tom5454.com/api/upload"), "paste.tom5454.com", List.of(address, InetAddress.getByName("1.1.1.1")),
                Duration.ofSeconds(1), 65536, "POST", java.util.Map.of("Session", "synthetic", "File-Name", "NCL-Skins-model", "Content-Length", "8"),
                "AAECAwQF".getBytes(StandardCharsets.US_ASCII));
        assertEquals(201, response.statusCode()); assertArrayEquals("{}".getBytes(StandardCharsets.US_ASCII), response.body());
        assertEquals(1, calls.get());
        String request=tls.output.toString(StandardCharsets.US_ASCII);
        assertTrue(request.startsWith("POST /api/upload HTTP/1.1\r\n"));assertTrue(request.endsWith("\r\n\r\nAAECAwQF"));
        assertTrue(request.contains("Content-Length: 8\r\n"));assertFalse(request.toLowerCase().contains("cookie:"));
    }

    @Test
    void directFixedWriterRejectsInvalidFilenameAndNonUploadUnicodeBeforeConnect() throws Exception {
        var output = new ByteArrayOutputStream();
        AtomicInteger attempts = new AtomicInteger();
        var transport = fixedSocketTransport("", output, attempts);
        URI upload = URI.create("https://paste.tom5454.com/api/upload");
        var invalid = new java.util.ArrayList<>(List.of("", " ", "a\u007fb", "x".repeat(1024 * 1024 + 1)));
        for (int control = 0; control < 32; control++) invalid.add("a" + (char) control + "b");
        for (String name : invalid) {
            var failure = assertThrows(PublicHttpsImageFetcher.FixedRequestException.class,
                    () -> transport.fixed(upload, "paste.tom5454.com", List.of(InetAddress.getByName("8.8.8.8")),
                            Duration.ofSeconds(5), 65536, "POST", java.util.Map.of("File-Name", name), new byte[0]));
            assertFalse(failure.possibleSend());
        }
        for (String header : List.of("File-Name", "Accept", "Session")) {
            assertThrows(PublicHttpsImageFetcher.FixedRequestException.class,
                    () -> transport.fixed(REQUEST, "example.com", List.of(InetAddress.getByName("8.8.8.8")),
                            Duration.ofSeconds(5), 65536, "POST", java.util.Map.of(header, "é 😀"), new byte[0]));
        }
        assertEquals(0, attempts.get());
        assertEquals(0, output.size());
    }

    @Test
    void fixedDeadlineClosesOwnedConnectionDuringBlockedHeaderOrBodyWithoutReplay() throws Exception {
        for (int blockingWrite : List.of(1, 2)) {
            InetAddress address = InetAddress.getByName("8.8.8.8");
            FixtureSocket plain = new FixtureSocket(address, "");
            RecordingSslSocket tls = new RecordingSslSocket("");
            var entered = new java.util.concurrent.CountDownLatch(1);
            var released = new java.util.concurrent.CountDownLatch(1);
            AtomicInteger writes = new AtomicInteger();
            AtomicInteger attempts = new AtomicInteger();
            plain.closing = released::countDown;
            tls.outputOverride = new OutputStream() {
                @Override public void write(int value) throws IOException { throw new IOException("unexpected scalar write"); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (writes.incrementAndGet() == blockingWrite) {
                        entered.countDown();
                        try { released.await(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
                        throw new IOException("owned connection closed");
                    }
                }
            };
            PinnedHttpsTransport transport = new PinnedHttpsTransport(new RecordingSslSocketFactory(tls),
                    (connected, port, timeout) -> { attempts.incrementAndGet(); return plain; });
            var call = new java.util.concurrent.FutureTask<>(() -> transport.fixed(
                    URI.create("https://paste.tom5454.com/api/upload"), "paste.tom5454.com",
                    List.of(address, InetAddress.getByName("1.1.1.1")), Duration.ofMillis(250), 65536,
                    "POST", java.util.Map.of("Content-Length", "16384"), new byte[16384]));
            Thread worker = new Thread(call, "fixed-deadline-fixture");
            worker.start();
            try {
                assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS), "write boundary not entered");
                var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> call.get(1, java.util.concurrent.TimeUnit.SECONDS), "deadline must terminate blocked output without fixture teardown");
                assertTrue(failed.getCause() instanceof PublicHttpsImageFetcher.FixedRequestException);
                assertTrue(((PublicHttpsImageFetcher.FixedRequestException) failed.getCause()).possibleSend());
                assertEquals(1, attempts.get());
                assertEquals(blockingWrite, writes.get(), "cleanup must not flush/replay output");
                assertTrue(plain.closed);
            } finally {
                released.countDown();
                worker.join(1500);
                assertFalse(worker.isAlive(), "fixture worker must stop");
            }
        }
    }

    @Test
    void completedFixedRequestRetiresTimerAndLaterRequestRemainsHealthy() throws Exception {
        InetAddress address = InetAddress.getByName("8.8.8.8");
        AtomicInteger closes = new AtomicInteger();
        FixtureSocket plain = new FixtureSocket(address, "");
        plain.closing = closes::incrementAndGet;
        RecordingSslSocket tls = new RecordingSslSocket("HTTP/1.1 204 OK\r\n\r\n");
        PinnedHttpsTransport transport = new PinnedHttpsTransport(new RecordingSslSocketFactory(tls),
                (connected, port, timeout) -> plain);
        assertEquals(204, transport.fixed(REQUEST, "example.com", List.of(address), Duration.ofMillis(250),
                64, "POST", java.util.Map.of(), new byte[]{1}).statusCode());
        assertEquals(1, closes.get());
        assertFalse(new java.util.concurrent.CountDownLatch(1).await(600, java.util.concurrent.TimeUnit.MILLISECONDS));
        assertEquals(1, closes.get(), "retired timer must not close again");
        FixtureSocket later = new FixtureSocket(address, "");
        RecordingSslSocket laterTls = new RecordingSslSocket("HTTP/1.1 204 OK\r\n\r\n");
        PinnedHttpsTransport laterTransport = new PinnedHttpsTransport(new RecordingSslSocketFactory(laterTls),
                (connected, port, timeout) -> later);
        assertEquals(204, laterTransport.fixed(REQUEST, "example.com", List.of(address), Duration.ofSeconds(1),
                64, "POST", java.util.Map.of(), new byte[]{2}).statusCode());
    }

    @Test
    void fixedDeadlineBeforeHandshakeCompletionIsNotSent() throws Exception {
        InetAddress address = InetAddress.getByName("8.8.8.8");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var released = new java.util.concurrent.CountDownLatch(1);
        FixtureSocket plain = new FixtureSocket(address, "");
        plain.closing = released::countDown;
        RecordingSslSocket tls = new RecordingSslSocket("");
        tls.handshakeAction = () -> {
            entered.countDown();
            try { released.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        };
        PinnedHttpsTransport transport = new PinnedHttpsTransport(new RecordingSslSocketFactory(tls),
                (connected, port, timeout) -> plain);
        var call = new java.util.concurrent.FutureTask<>(() -> transport.fixed(REQUEST, "example.com",
                List.of(address), Duration.ofMillis(250), 64, "POST", java.util.Map.of(), new byte[]{1}));
        Thread worker = new Thread(call, "fixed-handshake-fixture");
        worker.start();
        try {
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> call.get(1, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof PublicHttpsImageFetcher.FixedRequestException);
            assertFalse(((PublicHttpsImageFetcher.FixedRequestException) failure.getCause()).possibleSend());
            assertEquals(0, tls.output.size());
        } finally { released.countDown(); worker.join(1500); assertFalse(worker.isAlive()); }
    }

    @Test
    void fixedFailureNeverReplaysAndDistinguishesPossibleSend() throws Exception {
        InetAddress first = InetAddress.getByName("8.8.8.8"), second = InetAddress.getByName("1.1.1.1");
        AtomicInteger calls = new AtomicInteger();
        PinnedHttpsTransport before = new PinnedHttpsTransport((SSLSocketFactory) SSLSocketFactory.getDefault(),
                (address, port, timeout) -> { calls.incrementAndGet(); throw new IOException("synthetic connect failure"); });
        var notSent = assertThrows(PublicHttpsImageFetcher.FixedRequestException.class, () -> before.fixed(
                URI.create("https://paste.tom5454.com/api/upload"), "paste.tom5454.com", List.of(first,second),
                Duration.ofSeconds(1), 65536, "POST", java.util.Map.of(), new byte[]{1}));
        assertFalse(notSent.possibleSend()); assertEquals(1, calls.get());
        calls.set(0); FixtureSocket plain = new FixtureSocket(first, "");
        RecordingSslSocket tls = new RecordingSslSocket("");
        PinnedHttpsTransport after = new PinnedHttpsTransport(new RecordingSslSocketFactory(tls),
                (address, port, timeout) -> { calls.incrementAndGet(); return plain; });
        var uncertain = assertThrows(PublicHttpsImageFetcher.FixedRequestException.class, () -> after.fixed(
                URI.create("https://paste.tom5454.com/api/upload"), "paste.tom5454.com", List.of(first,second),
                Duration.ofSeconds(1), 65536, "POST", java.util.Map.of("Content-Length","1"), new byte[]{1}));
        assertTrue(uncertain.possibleSend());assertEquals(1,calls.get());assertTrue(tls.output.size()>0);assertTrue(tls.closed);assertTrue(plain.closed);
    }

    @Test
    void callbackFramingSupportsTheInclusiveLegacyWireBoundWithoutRetainingTheBody() throws Exception {
        long maximum = 4L * ((com.naocraftlab.skins.client.EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES + 2L) / 3L) + 16 * 1024;
        for (String framing : List.of("", "Content-Length: " + maximum + "\r\n", "Transfer-Encoding: chunked\r\n")) {
            boolean chunks = framing.startsWith("Transfer");
            InputStream exact = streamingFixture(framing, maximum, chunks ? Long.toHexString(maximum) : null);
            assertEquals("parsed", PinnedHttpsTransport.readResponseStreaming(exact, REQUEST, maximum,
                    body -> "parsed").body());
            InputStream extra = streamingFixture(framing.isEmpty() ? "" : chunks ? framing : "Content-Length: " + (maximum + 1) + "\r\n",
                    maximum + 1, chunks ? Long.toHexString(maximum + 1) : null);
            assertThrows(PinnedHttpsTransport.BodyTooLargeException.class,
                    () -> PinnedHttpsTransport.readResponseStreaming(extra, REQUEST, maximum, body -> "untrusted"));
        }
    }

    private static InputStream streamingFixture(String framing, long bytes, String chunk) {
        String prefix = "HTTP/1.1 200 OK\r\n" + framing + "\r\n" + (chunk == null ? "" : chunk + "\r\n");
        InputStream source = new java.io.SequenceInputStream(new ByteArrayInputStream(prefix.getBytes(StandardCharsets.US_ASCII)), new SyntheticBody(bytes));
        return chunk == null ? source : new java.io.SequenceInputStream(source,
                new ByteArrayInputStream("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void earlyCallbackCannotBypassTruncationMisleadingLengthsOrMalformedChunks() {
        for (String response : List.of(
                "Content-Length: 5\r\n\r\ntest",
                "Content-Length: 4\r\n\r\ntest!",
                "Content-Length: 4\r\nContent-Length: 4\r\n\r\ntest",
                "Transfer-Encoding: chunked\r\nContent-Length: 4\r\n\r\n",
                "Transfer-Encoding: chunked\r\n\r\n4\r\ntestX\n0\r\n\r\n",
                "Transfer-Encoding: chunked\r\n\r\n4\r\ntest\r\n0\r\n\r\nextra")) {
            assertThrows(IOException.class, () -> PinnedHttpsTransport.readResponseStreaming(
                    new ByteArrayInputStream(("HTTP/1.1 200 OK\r\n" + response).getBytes(StandardCharsets.US_ASCII)),
                    REQUEST, 8, body -> "premature"));
        }
    }

    @Test
    void callbackIsOnlyForSuccessAndItsStreamIsRetiredAfterReturnOrFailure() throws Exception {
        var escaped = new java.util.concurrent.atomic.AtomicReference<InputStream>();
        var result = PinnedHttpsTransport.readResponseStreaming(new ByteArrayInputStream(
                "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ntest".getBytes(StandardCharsets.US_ASCII)), REQUEST, 4,
                body -> { escaped.set(body); assertEquals('t', body.read()); return "accepted"; });
        assertEquals("accepted", result.body());
        assertThrows(IOException.class, () -> escaped.get().read());
        assertThrows(IOException.class, () -> PinnedHttpsTransport.readResponseStreaming(new ByteArrayInputStream(
                "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ntest".getBytes(StandardCharsets.US_ASCII)), REQUEST, 4,
                body -> { escaped.set(body); throw new IOException("synthetic callback failure"); }));
        assertThrows(IOException.class, () -> escaped.get().read());
        AtomicInteger called = new AtomicInteger();
        for (int status : List.of(302, 401, 404, 429, 500)) {
            var rejected = PinnedHttpsTransport.readResponseStreaming(new ByteArrayInputStream(
                    ("HTTP/1.1 " + status + " Status\r\nContent-Length: 999999999\r\n\r\nignored").getBytes(StandardCharsets.US_ASCII)), REQUEST, 4,
                    body -> { called.incrementAndGet(); return "invalid"; });
            assertEquals(status, rejected.statusCode());
            assertEquals(null, rejected.body());
        }
        assertEquals(0, called.get());
    }

    @Test
    void streamingTlsRunsSynchronouslyInsidePinnedConnectionAndClosesIt() throws Exception {
        InetAddress address = InetAddress.getByName("8.8.8.8");
        FixtureSocket plain = new FixtureSocket(address, "");
        RecordingSslSocket tls = new RecordingSslSocket("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ntest");
        RecordingSslSocketFactory factory = new RecordingSslSocketFactory(tls);
        PinnedHttpsTransport transport = new PinnedHttpsTransport(factory, (connected, port, timeout) -> {
            assertEquals(address, connected); assertEquals(443, port); return plain;
        });
        Thread caller = Thread.currentThread();
        var response = transport.getStreaming(REQUEST, "example.com", List.of(address), Duration.ofSeconds(1), 4, body -> {
            assertEquals(caller, Thread.currentThread()); assertFalse(tls.closed); assertFalse(plain.closed);
            return new String(body.readAllBytes(), StandardCharsets.US_ASCII);
        });
        assertEquals("test", response.body());
        assertTrue(tls.closed); assertTrue(plain.closed);
        assertEquals("example.com", factory.peerHost);
        assertEquals("HTTPS", tls.parameters.getEndpointIdentificationAlgorithm());
        assertArrayEquals(new String[]{"http/1.1"}, tls.applicationProtocolsAtHandshake);
        assertEquals("example.com", ((SNIHostName) tls.parameters.getServerNames().get(0)).getAsciiName());
        String request = tls.output.toString(StandardCharsets.US_ASCII).toLowerCase();
        assertFalse(request.contains("authorization:")); assertFalse(request.contains("cookie:"));
    }

    @Test
    void httpsFileRequestWithoutQueryKeepsItsExactTarget() throws Exception {
        InetAddress address = InetAddress.getByName("8.8.8.8");
        FixtureSocket plain = new FixtureSocket(address, "");
        RecordingSslSocket tls = new RecordingSslSocket("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ntest");
        PinnedHttpsTransport transport = new PinnedHttpsTransport(new RecordingSslSocketFactory(tls),
                (connected, port, timeout) -> plain);
        Path file = directory.resolve("plain-query-free-image");
        assertEquals(4, transport.getToFile(REQUEST, "example.com", List.of(address), Duration.ofSeconds(1), 4, file).bodyBytes());
        assertArrayEquals("test".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(file));
        assertTrue(tls.output.toString(StandardCharsets.US_ASCII).startsWith("GET /skin.png HTTP/1.1\r\n"));
        assertTrue(tls.closed); assertTrue(plain.closed);
    }

    @Test
    void callbackFailureDoesNotRetryAndBufferedReadsStillRespectDeadline() throws Exception {
        InetAddress first = InetAddress.getByName("8.8.8.8"), second = InetAddress.getByName("1.1.1.1");
        AtomicInteger connects = new AtomicInteger(); AtomicLong now = new AtomicLong();
        FixtureSocket socket = new FixtureSocket(first, "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ntest");
        PinnedHttpsTransport transport = new PinnedHttpsTransport((SSLSocketFactory) SSLSocketFactory.getDefault(),
                (address, port, timeout) -> { connects.incrementAndGet(); return socket; }, now::get);
        assertThrows(IOException.class, () -> transport.getStreaming(URI.create("http://example.com/a"), "example.com",
                List.of(first, second), Duration.ofSeconds(1), 4, body -> {
                    assertEquals('t', body.read()); now.addAndGet(Duration.ofSeconds(2).toNanos());
                    return body.read();
                }));
        assertEquals(1, connects.get()); assertTrue(socket.closed);
    }

    @Test
    void streamsTheSharedInclusiveMaximumWithoutEncodedBodyAllocation() throws Exception {
        long maximum = com.naocraftlab.skins.client.EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES;
        assertEquals(1_140_850_688L, maximum);
        assertEquals(maximum, PinnedHttpsTransport.copyResponseBody(new SyntheticBody(maximum),
                java.util.Map.of(), java.io.OutputStream.nullOutputStream(), maximum));
        assertThrows(PinnedHttpsTransport.BodyTooLargeException.class,
                () -> PinnedHttpsTransport.copyResponseBody(new SyntheticBody(maximum + 1),
                        java.util.Map.of(), java.io.OutputStream.nullOutputStream(), maximum));
        assertEquals(maximum, PinnedHttpsTransport.copyResponseBody(new SyntheticBody(maximum),
                java.util.Map.of("content-length", List.of(Long.toString(maximum))),
                java.io.OutputStream.nullOutputStream(), maximum));
    }

    @Test
    void rejectsMisleadingLengthAndRemovesOnlyItsPartialFile() throws Exception {
        Path output = directory.resolve("misleading");
        assertThrows(PinnedHttpsTransport.BodyTooLargeException.class,
                () -> PinnedHttpsTransport.readResponseToFile(new ByteArrayInputStream(
                        "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ntest!"
                                .getBytes(StandardCharsets.US_ASCII)), REQUEST, 4, output));
        assertFalse(Files.exists(output));
        Path existing = Files.write(directory.resolve("existing"), new byte[]{7});
        assertThrows(IOException.class, () -> PinnedHttpsTransport.readResponseToFile(new ByteArrayInputStream(
                "HTTP/1.1 200 OK\r\n\r\ntest".getBytes(StandardCharsets.US_ASCII)), REQUEST, 4, existing));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(existing));
    }

    private static final class SyntheticBody extends java.io.InputStream {
        private long remaining;
        private SyntheticBody(long remaining) { this.remaining = remaining; }
        @Override public int read() { if (remaining == 0) return -1; remaining--; return 0; }
        @Override public int read(byte[] bytes, int offset, int length) {
            if (remaining == 0) return -1;
            int count = (int) Math.min(length, remaining);
            java.util.Arrays.fill(bytes, offset, offset + count, (byte) 0);
            remaining -= count;
            return count;
        }
    }

    @Test
    void fileBodyStagesExactBoundaryAndRejectsTheNextByte() throws Exception {
        Path exact = directory.resolve("exact");
        var accepted = PinnedHttpsTransport.readResponseToFile(new ByteArrayInputStream(
                "HTTP/1.1 200 OK\r\n\r\ntest".getBytes(StandardCharsets.US_ASCII)),
                REQUEST, 4, exact);
        assertEquals(4, accepted.bodyBytes());
        assertArrayEquals("test".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(exact));

        assertThrows(PinnedHttpsTransport.BodyTooLargeException.class,
                () -> PinnedHttpsTransport.readResponseToFile(new ByteArrayInputStream(
                        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\ntest!\r\n0\r\n\r\n"
                                .getBytes(StandardCharsets.US_ASCII)),
                        REQUEST, 4, directory.resolve("overflow")));
        assertThrows(PinnedHttpsTransport.BodyTooLargeException.class,
                () -> PinnedHttpsTransport.readResponseToFile(new ByteArrayInputStream(
                        "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\ntest!"
                                .getBytes(StandardCharsets.US_ASCII)),
                        REQUEST, 4, directory.resolve("declared-overflow")));
    }

    @Test
    void fileBodyRejectsTruncatedAndAmbiguousFraming() {
        assertThrows(IOException.class,
                () -> PinnedHttpsTransport.readResponseToFile(new ByteArrayInputStream(
                        "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\ntest"
                                .getBytes(StandardCharsets.US_ASCII)),
                        REQUEST, 8, directory.resolve("truncated")));
        assertThrows(IOException.class,
                () -> PinnedHttpsTransport.readResponseToFile(new ByteArrayInputStream(
                        "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nContent-Length: 4\r\n\r\ntest"
                                .getBytes(StandardCharsets.US_ASCII)),
                        REQUEST, 8, directory.resolve("ambiguous")));
    }

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
    void doesNotReadAuthenticationFailureBodies() throws Exception {
        for (int status : List.of(401, 403)) {
            byte[] headers = ("HTTP/1.1 " + status + " Rejected\r\n"
                    + "Content-Length: 1000000\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            InputStream failOnBody = new InputStream() {
                private int offset;

                @Override
                public int read() throws IOException {
                    if (offset == headers.length) {
                        throw new IOException("authentication failure body was read");
                    }
                    return headers[offset++] & 0xff;
                }
            };

            PinnedHttpsTransport.Response response = PinnedHttpsTransport.readResponse(
                    failOnBody, REQUEST, 1);

            assertEquals(status, response.statusCode());
            assertArrayEquals(new byte[0], response.body());
        }
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
        assertArrayEquals(new String[]{"http/1.1"}, tls.parameters.getApplicationProtocols());
        assertArrayEquals(new String[]{"http/1.1"}, tls.applicationProtocolsAtHandshake);
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
        private boolean closed;
        private Runnable closing = () -> {};
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
            closed = true;
            closing.run();
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
        private boolean closed;
        private OutputStream outputOverride;
        private Runnable handshakeAction = () -> {};
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private SSLParameters parameters = new SSLParameters();
        private boolean handshakeStarted;
        private String[] applicationProtocolsAtHandshake = new String[0];
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
            handshakeAction.run();
            applicationProtocolsAtHandshake = parameters.getApplicationProtocols();
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
            return outputOverride == null ? output : outputOverride;
        }

        @Override
        public synchronized void setSoTimeout(int timeout) {
        }

        @Override
        public synchronized void close() {
            closed = true;
        }
    }
}

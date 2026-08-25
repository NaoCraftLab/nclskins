package com.naocraftlab.skins.runtime.update;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class UpdateCatalogClientTest {
    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void successfulCheckUsesOneExactFixedOriginRequest() {
        RecordingHttp http = RecordingHttp.response(200, validCatalog());
        UpdateCatalogClient client = client(http);

        UpdateCandidate candidate = client.check(
                "fabric-26.2", "1.0.0-beta.2", UpdateChannel.BETA).orElseThrow();

        assertEquals("1.0.0-beta.3", candidate.version().toString());
        assertEquals(UpdateCatalogClient.CATALOG_URI, http.uri);
        assertEquals(Duration.ofSeconds(10), http.timeout);
        assertEquals(1, http.calls);
    }

    @Test
    void redirectTimeoutAndMalformedJsonFailSilentWithoutRetry() {
        RecordingHttp redirect = RecordingHttp.response(302, validCatalog());
        assertTrue(client(redirect).check(
                "fabric-26.2", "1.0.0-beta.2", UpdateChannel.BETA).isEmpty());
        assertEquals(1, redirect.calls);
        assertEquals(0, redirect.body.readCount);

        RecordingHttp timeout = RecordingHttp.failure(new IOException("timeout detail"));
        assertTrue(client(timeout).check(
                "fabric-26.2", "1.0.0-beta.2", UpdateChannel.BETA).isEmpty());
        assertEquals(1, timeout.calls);

        RecordingHttp malformed = RecordingHttp.response(200, "{not json}");
        assertTrue(client(malformed).check(
                "fabric-26.2", "1.0.0-beta.2", UpdateChannel.BETA).isEmpty());
        assertEquals(1, malformed.calls);
    }

    @Test
    void interruptionRestoresFlagAndDoesNotRetry() {
        RecordingHttp interrupted = RecordingHttp.interrupted();

        Optional<UpdateCandidate> result = client(interrupted).check(
                "fabric-26.2", "1.0.0-beta.2", UpdateChannel.BETA);

        assertTrue(result.isEmpty());
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(1, interrupted.calls);
    }

    @Test
    void contentLengthAndStreamLimitsStopOversizedBodies() {
        CountingInputStream headerBody = new CountingInputStream(new byte[] {1});
        RecordingHttp header = RecordingHttp.response(
                200, OptionalLong.of(UpdateCatalogClient.MAX_BODY_BYTES + 1L), headerBody);
        assertTrue(client(header).check(
                "fabric-26.2", "1.0.0-beta.2", UpdateChannel.BETA).isEmpty());
        assertEquals(0, headerBody.readCount);

        byte[] oversized = new byte[UpdateCatalogClient.MAX_BODY_BYTES + 20];
        CountingInputStream streamBody = new CountingInputStream(oversized);
        RecordingHttp stream = RecordingHttp.response(
                200, OptionalLong.empty(), streamBody);
        assertTrue(client(stream).check(
                "fabric-26.2", "1.0.0-beta.2", UpdateChannel.BETA).isEmpty());
        assertEquals(UpdateCatalogClient.MAX_BODY_BYTES + 1, streamBody.readCount);
        assertEquals(1, stream.calls);
    }

    @Test
    void jdkBoundaryDisablesRedirectsAndBuildsCredentialFreeGet() {
        HttpClient http = JdkUpdateHttpBoundary.configuredHttpClient();
        HttpRequest request = JdkUpdateHttpBoundary.request(
                UpdateCatalogClient.CATALOG_URI, UpdateCatalogClient.REQUEST_TIMEOUT);

        assertEquals(HttpClient.Redirect.NEVER, http.followRedirects());
        assertEquals(Optional.of(Duration.ofSeconds(5)), http.connectTimeout());
        assertEquals("GET", request.method());
        assertEquals(UpdateCatalogClient.CATALOG_URI, request.uri());
        assertEquals(Optional.of(Duration.ofSeconds(10)), request.timeout());
        assertTrue(request.headers().map().isEmpty());
        assertFalse(request.bodyPublisher().isPresent());
    }

    private static UpdateCatalogClient client(UpdateHttpBoundary http) {
        return new UpdateCatalogClient(
                http, new UpdateCatalogParser(), new UpdateSelector());
    }

    private static String validCatalog() {
        return "{\"schemaVersion\":1,\"project\":\"nclskins\",\"releases\":{"
                + "\"1.0.0-beta.3\":{\"channel\":\"beta\",\"url\":"
                + "\"https://github.com/NaoCraftLab/nclskins/releases/tag/1.0.0-beta.3\"}},"
                + "\"targets\":{\"fabric-26.2\":{\"loader\":\"fabric\","
                + "\"minecraftVersion\":\"26.2\",\"versions\":[\"1.0.0-beta.3\"]}}}";
    }

    private static final class RecordingHttp implements UpdateHttpBoundary {
        private final int status;
        private final OptionalLong contentLength;
        private final CountingInputStream body;
        private final IOException failure;
        private final boolean interrupted;
        private int calls;
        private URI uri;
        private Duration timeout;

        private RecordingHttp(
                int status,
                OptionalLong contentLength,
                CountingInputStream body,
                IOException failure,
                boolean interrupted) {
            this.status = status;
            this.contentLength = contentLength;
            this.body = body;
            this.failure = failure;
            this.interrupted = interrupted;
        }

        static RecordingHttp response(int status, String body) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            return response(status, OptionalLong.of(bytes.length),
                    new CountingInputStream(bytes));
        }

        static RecordingHttp response(
                int status, OptionalLong contentLength, CountingInputStream body) {
            return new RecordingHttp(status, contentLength, body, null, false);
        }

        static RecordingHttp failure(IOException failure) {
            return new RecordingHttp(0, OptionalLong.empty(),
                    new CountingInputStream(new byte[0]), failure, false);
        }

        static RecordingHttp interrupted() {
            return new RecordingHttp(0, OptionalLong.empty(),
                    new CountingInputStream(new byte[0]), null, true);
        }

        @Override
        public UpdateHttpResponse get(URI uri, Duration timeout)
                throws IOException, InterruptedException {
            calls++;
            this.uri = uri;
            this.timeout = timeout;
            if (failure != null) {
                throw failure;
            }
            if (interrupted) {
                throw new InterruptedException("interrupted detail");
            }
            return new UpdateHttpResponse(status, contentLength, body);
        }
    }

    private static final class CountingInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private int readCount;

        private CountingInputStream(byte[] bytes) {
            delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() {
            int value = delegate.read();
            if (value >= 0) {
                readCount++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            int count = delegate.read(bytes, offset, length);
            if (count > 0) {
                readCount += count;
            }
            return count;
        }
    }
}

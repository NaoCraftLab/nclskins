package com.naocraftlab.skins.core.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

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
        assertThrows(IOException.class, () -> parse(
                "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\ntest!", 4));
    }

    private static PinnedHttpsTransport.Response parse(String response, int maxBodyBytes)
            throws IOException {
        return PinnedHttpsTransport.readResponse(
                new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII)),
                REQUEST,
                maxBodyBytes);
    }
}

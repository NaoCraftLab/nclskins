package com.naocraftlab.skins.core.api;

import com.naocraftlab.skins.core.png.PngValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PublicHttpsImageFetcherTest {
    @Test
    void sourceFilenameReachesPinnedWriterAsExactUtf8() throws Exception {
        for (String name : List.of("Лесная ведьма é 😀 + (v2).cpmproject", "  e\u0301 +% #.cpmmodel  ",
                "я".repeat(4097), "x".repeat(1024 * 1024 - 1), "x".repeat(1024 * 1024 - 2) + "😀")) {
            var output = new java.io.ByteArrayOutputStream();
            AtomicInteger attempts = new AtomicInteger(), resolutions = new AtomicInteger();
            var fetcher = fixedFetcher(PinnedHttpsTransportTest.fixedSocketTransport(
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}", output, attempts), resolutions);
            var headers = new java.util.LinkedHashMap<String, String>();
            headers.put("Session", "synthetic");
            headers.put("File-Name", name);
            headers.put("Content-Length", "4");
            assertEquals(200, fetcher.fixedRequest("POST", URI.create("https://paste.tom5454.com/api/upload"),
                    headers, "AAEC".getBytes(java.nio.charset.StandardCharsets.US_ASCII), Duration.ofSeconds(5), 65536).status());
            String expected = "POST /api/upload HTTP/1.1\r\nHost: paste.tom5454.com\r\nConnection: close\r\nAccept-Encoding: identity\r\n"
                    + "Session: synthetic\r\nFile-Name: " + name + "\r\nContent-Length: 4\r\n\r\nAAEC";
            org.junit.jupiter.api.Assertions.assertArrayEquals(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8), output.toByteArray());
            assertEquals(1, attempts.get());
            assertEquals(1, resolutions.get());
        }
    }

    @Test
    void unsafeRawFilenameOrOtherHeaderCannotReachDnsOrSocket() throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        AtomicInteger attempts = new AtomicInteger(), resolutions = new AtomicInteger();
        var fetcher = fixedFetcher(PinnedHttpsTransportTest.fixedSocketTransport("", output, attempts), resolutions);
        var invalid = new java.util.ArrayList<>(List.of("", "   ", "x".repeat(1024 * 1024 + 1), "a\u007fb"));
        for (int control = 0; control < 32; control++) invalid.add("a" + (char) control + "b");
        for (String name : invalid) assertThrows(PublicHttpsImageFetcher.UnsafeImageUriException.class,
                () -> fetcher.fixedRequest("POST", URI.create("https://paste.tom5454.com/api/upload"),
                        Map.of("Session", "synthetic", "File-Name", name, "Content-Length", "0"), new byte[0], Duration.ofSeconds(5), 65536));
        assertThrows(PublicHttpsImageFetcher.UnsafeImageUriException.class,
                () -> fetcher.fixedRequest("POST", URI.create("https://paste.tom5454.com/api/upload"),
                        Map.of("Session", "synthetic", "Content-Length", "0"), new byte[0], Duration.ofSeconds(5), 65536));
        for (String endpoint : List.of("connect?name=Player", "session", "list"))
            assertThrows(PublicHttpsImageFetcher.UnsafeImageUriException.class,
                    () -> fetcher.fixedRequest("GET", URI.create("https://paste.tom5454.com/api/" + endpoint),
                            Map.of("Session", "synthetic", "File-Name", "valid.cpmmodel"), new byte[0], Duration.ofSeconds(5), 65536));
        for (String header : List.of("Accept", "Content-Type")) for (String value : List.of("é", "x".repeat(4097), "a\r\nb"))
            assertThrows(PublicHttpsImageFetcher.UnsafeImageUriException.class,
                    () -> fetcher.fixedRequest("POST", URI.create("https://paste.tom5454.com/api/upload"),
                            Map.of("Session", "synthetic", "File-Name", "valid.cpmmodel", "Content-Length", "0", header, value),
                            new byte[0], Duration.ofSeconds(5), 65536));
        assertEquals(0, resolutions.get());
        assertEquals(0, attempts.get());
        assertEquals(0, output.size());
    }

    private static final URI SIGNED = URI.create(
            "https://cdn.example.net:443/cape%2Fone.png?sig=a%2Fb%2Bz&expires=123");

    @Test
    void fixedOpaqueSessionReachesRealWireUnchangedWithoutReplay() throws Exception {
        String printable = java.util.stream.IntStream.rangeClosed(33, 126)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        for (String session : List.of("synthetic/session+value=", "!", "~", printable, "~".repeat(1024))) {
            for (String endpoint : List.of("session", "list", "upload")) {
                var output = new java.io.ByteArrayOutputStream();
                AtomicInteger attempts = new AtomicInteger(), resolutions = new AtomicInteger();
                var transport = PinnedHttpsTransportTest.fixedSocketTransport(
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}", output, attempts);
                var fetcher = fixedFetcher(transport, resolutions);
                boolean upload = endpoint.equals("upload");
                byte[] body = upload ? "AAECAwQF".getBytes(java.nio.charset.StandardCharsets.US_ASCII) : new byte[0];
                var headers = new java.util.LinkedHashMap<String, String>();
                headers.put("Session", session);
                if (upload) {
                    headers.put("File-Name", "NCL-Skins-model");
                    headers.put("Content-Length", "8");
                }
                var response = fetcher.fixedRequest(upload ? "POST" : "GET",
                        URI.create("https://paste.tom5454.com/api/" + endpoint), headers, body, Duration.ofSeconds(5), 65536);
                assertEquals(200, response.status());
                assertEquals(1, resolutions.get());
                assertEquals(1, attempts.get());
                String expected = (upload ? "POST" : "GET") + " /api/" + endpoint + " HTTP/1.1\r\n"
                        + "Host: paste.tom5454.com\r\nConnection: close\r\nAccept-Encoding: identity\r\n"
                        + "Session: " + session + "\r\n"
                        + (upload ? "File-Name: NCL-Skins-model\r\nContent-Length: 8\r\n" : "")
                        + "\r\n" + (upload ? "AAECAwQF" : "");
                org.junit.jupiter.api.Assertions.assertArrayEquals(expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII), output.toByteArray());
            }
        }
    }

    @Test
    void fixedUnsafeSessionIsRejectedBeforeDnsOrSocket() throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        AtomicInteger attempts = new AtomicInteger(), resolutions = new AtomicInteger();
        var fetcher = fixedFetcher(PinnedHttpsTransportTest.fixedSocketTransport("", output, attempts), resolutions);
        var invalid = new java.util.ArrayList<>(List.of("", "~".repeat(1025), "synthetic value", " value", "value ",
                "value\u007f", "value\u0080", "value\u00a0", "value\u20ac", "value\ud83d\ude00"));
        for (int control = 0; control < 32; control++) invalid.add("value" + (char) control);
        for (String session : invalid) {
            for (String endpoint : List.of("session", "list", "upload")) {
                boolean upload = endpoint.equals("upload");
                Map<String, String> headers = upload
                        ? Map.of("Session", session, "File-Name", "NCL-Skins-model", "Content-Length", "0")
                        : Map.of("Session", session);
                assertThrows(PublicHttpsImageFetcher.UnsafeImageUriException.class, () -> fetcher.fixedRequest(
                        upload ? "POST" : "GET", URI.create("https://paste.tom5454.com/api/" + endpoint), headers,
                        new byte[0], Duration.ofSeconds(5), 65536));
            }
        }
        assertEquals(0, resolutions.get());
        assertEquals(0, attempts.get());
        assertEquals(0, output.size());
    }

    @Test
    void fixedOpaqueUploadFailureDoesNotReplayAnotherPublicAddress() throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        AtomicInteger attempts = new AtomicInteger(), resolutions = new AtomicInteger();
        var fetcher = fixedFetcher(PinnedHttpsTransportTest.fixedSocketTransport("", output, attempts), resolutions);
        var failure = assertThrows(PublicHttpsImageFetcher.FixedRequestException.class, () -> fetcher.fixedRequest(
                "POST", URI.create("https://paste.tom5454.com/api/upload"),
                Map.of("Session", "synthetic/session+value=", "File-Name", "NCL-Skins-model", "Content-Length", "8"),
                "AAECAwQF".getBytes(java.nio.charset.StandardCharsets.US_ASCII), Duration.ofSeconds(5), 65536));
        org.junit.jupiter.api.Assertions.assertTrue(failure.possibleSend());
        assertEquals(1, resolutions.get());
        assertEquals(1, attempts.get());
        org.junit.jupiter.api.Assertions.assertTrue(output.toString(java.nio.charset.StandardCharsets.US_ASCII)
                .endsWith("\r\n\r\nAAECAwQF"));
    }

    private static PublicHttpsImageFetcher fixedFetcher(PinnedHttpsTransport transport, AtomicInteger resolutions) {
        var admission = new SafeRemotePngFetcher(transport, new PngValidator(), host -> {
            resolutions.incrementAndGet();
            assertEquals("paste.tom5454.com", host);
            return new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("1.1.1.1")};
        });
        return new PublicHttpsImageFetcher(admission, transport, System::nanoTime);
    }

    @Test
    void fixedPublicationPolicyPinsPublicDnsAndRejectsEndpointsCookiesAndFallback() throws Exception {
        AtomicInteger calls=new AtomicInteger(), resolutions=new AtomicInteger();
        InetAddress address=InetAddress.getByName("8.8.8.8");
        PinnedHttpsTransport transport=new PinnedHttpsTransport() {
            @Override Response fixed(URI uri,String host,List<InetAddress> addresses,Duration timeout,int maximum,String method,Map<String,String> headers,byte[] body,
                    java.util.function.BooleanSupplier beforeSend) {
                calls.incrementAndGet();assertEquals(List.of(address),addresses);assertEquals("paste.tom5454.com",host);
                return new Response(302,uri,Map.of("location",List.of("https://cf-paste.tom5454.com/api/session")),new byte[0]);
            }
        };
        SafeRemotePngFetcher admission=new SafeRemotePngFetcher(transport,new PngValidator(),host->{resolutions.incrementAndGet();return new InetAddress[]{address};});
        var fetcher=new PublicHttpsImageFetcher(admission,transport,System::nanoTime);
        for(String target:List.of("https://cf-paste.tom5454.com/api/session","http://paste.tom5454.com/api/session","https://paste.tom5454.com:443/api/session","https://paste.tom5454.com/api/delete","https://paste.tom5454.com/api/session?x=1","https://user@paste.tom5454.com/api/session")) {
            assertThrows(PublicHttpsImageFetcher.UnsafeImageUriException.class,()->fetcher.fixedRequest("GET",URI.create(target),Map.of("Session","synthetic"),new byte[0],Duration.ofSeconds(1),65536));
        }
        URI session=URI.create("https://paste.tom5454.com/api/session");
        assertThrows(PublicHttpsImageFetcher.UnsafeImageUriException.class,()->fetcher.fixedRequest("GET",session,Map.of("Session","synthetic","Cookie","private"),new byte[0],Duration.ofSeconds(1),65536));
        assertEquals(0,resolutions.get());assertEquals(0,calls.get());
        assertEquals(302,fetcher.fixedRequest("GET",session,Map.of("Session","synthetic"),new byte[0],Duration.ofSeconds(1),65536).status());
        assertEquals(1,calls.get());assertEquals(1,resolutions.get());
        var privateDns=new SafeRemotePngFetcher(transport,new PngValidator(),host->new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        var denied=new PublicHttpsImageFetcher(privateDns,transport,System::nanoTime);
        var failure=assertThrows(PublicHttpsImageFetcher.FixedRequestException.class,()->denied.fixedRequest("GET",session,Map.of("Session","synthetic"),new byte[0],Duration.ofSeconds(1),65536));
        assertEquals(false,failure.possibleSend());assertEquals(1,calls.get());
    }

    @Test
    void streamingKeepsTheCallerWireBoundExactUriAndDnsBudget() throws Exception {
        InetAddress address = InetAddress.getByName("8.8.8.8");
        AtomicLong now = new AtomicLong(); AtomicInteger resolved = new AtomicInteger();
        long wireMaximum = 4L * ((com.naocraftlab.skins.client.EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES + 2L) / 3L) + 16 * 1024;
        PinnedHttpsTransport transport = new PinnedHttpsTransport() {
            @Override <T> StreamResponse<T> getStreaming(URI uri, String host, List<InetAddress> addresses,
                    Duration timeout, long maximum, PublicHttpsImageFetcher.BodyReader<T> reader) throws IOException {
                assertEquals(SIGNED, uri); assertEquals("cdn.example.net", host);
                assertEquals(List.of(address), addresses); assertEquals(Duration.ofSeconds(9), timeout);
                assertEquals(wireMaximum, maximum);
                return new StreamResponse<>(200, uri, Map.of("retry-after", List.of("60")),
                        reader.read(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3})));
            }
        };
        SafeRemotePngFetcher admission = new SafeRemotePngFetcher(transport, new PngValidator(), host -> {
            resolved.incrementAndGet(); now.addAndGet(Duration.ofSeconds(1).toNanos()); return new InetAddress[]{address};
        }, now::get);
        var response = new PublicHttpsImageFetcher(admission, transport, now::get).getStreaming(
                SIGNED, Duration.ofSeconds(10), wireMaximum, body -> body.readNBytes(3).length);
        assertEquals(200, response.status()); assertEquals(3, response.body());
        assertEquals(List.of("60"), response.headers().get("retry-after")); assertEquals(1, resolved.get());
    }

    @Test
    void streamingRejectsPrivateDnsAndElapsedResolutionBeforeConnecting() throws Exception {
        AtomicInteger connects = new AtomicInteger(); AtomicLong now = new AtomicLong();
        PinnedHttpsTransport transport = new PinnedHttpsTransport() {
            @Override <T> StreamResponse<T> getStreaming(URI uri, String host, List<InetAddress> addresses,
                    Duration timeout, long maximum, PublicHttpsImageFetcher.BodyReader<T> reader) {
                connects.incrementAndGet(); throw new AssertionError("unsafe request connected");
            }
        };
        SafeRemotePngFetcher unsafe = new SafeRemotePngFetcher(transport, new PngValidator(),
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        assertThrows(PublicHttpsImageFetcher.UnsafeImageUriException.class, () -> new PublicHttpsImageFetcher(unsafe, transport, now::get)
                .getStreaming(SIGNED, Duration.ofSeconds(10), 100, body -> 1));
        SafeRemotePngFetcher expired = new SafeRemotePngFetcher(transport, new PngValidator(), host -> {
            now.addAndGet(Duration.ofSeconds(11).toNanos()); return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
        }, now::get);
        assertThrows(IOException.class, () -> new PublicHttpsImageFetcher(expired, transport, now::get)
                .getStreaming(SIGNED, Duration.ofSeconds(10), 100, body -> 1));
        assertEquals(0, connects.get());
    }

    @Test
    void fileImageKeepsTheExactSignedUriAndSharedStreamingCeiling(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
            throws Exception {
        InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
        AtomicReference<URI> requested = new AtomicReference<>();
        PinnedHttpsTransport transport = new PinnedHttpsTransport() {
            @Override FileResponse getToFile(URI uri, String host, List<InetAddress> addresses,
                    Duration timeout, long maximum, java.nio.file.Path destination) throws IOException {
                requested.set(uri);
                assertEquals(List.of(publicAddress), addresses);
                assertEquals(com.naocraftlab.skins.client.EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES, maximum);
                java.nio.file.Files.write(destination, new byte[]{1, 2, 3});
                return new FileResponse(200, uri, Map.of("retry-after", List.of("60")), 3);
            }
        };
        SafeRemotePngFetcher admission = new SafeRemotePngFetcher(transport, new PngValidator(),
                host -> new InetAddress[]{publicAddress}, () -> 0);
        PublicHttpsImageFetcher fetcher = new PublicHttpsImageFetcher(admission, transport, () -> 0);
        var result = fetcher.getToFile(SIGNED, Duration.ofSeconds(10), Long.MAX_VALUE, directory.resolve("image"));
        assertEquals(SIGNED, requested.get());
        assertEquals(3, result.bytes());
        assertEquals(List.of("60"), result.headers().get("retry-after"));
    }

    @Test
    void preservesExactSignedQueryAndPinsAllPublicAnswersOnce() throws Exception {
        InetAddress first = InetAddress.getByName("8.8.8.8");
        InetAddress second = InetAddress.getByName("1.1.1.1");
        AtomicInteger resolutions = new AtomicInteger();
        AtomicReference<List<InetAddress>> pinned = new AtomicReference<>();
        AtomicReference<URI> requested = new AtomicReference<>();
        PinnedHttpsTransport transport = new PinnedHttpsTransport() {
            @Override Response get(URI uri, String host, List<InetAddress> addresses,
                    Duration timeout, int maxBodyBytes) {
                requested.set(uri);
                pinned.set(List.copyOf(addresses));
                assertEquals("cdn.example.net", host);
                assertEquals(Duration.ofSeconds(9), timeout);
                return new Response(200, uri, Map.of(), new byte[] {1, 2, 3});
            }
        };
        AtomicLong now = new AtomicLong();
        SafeRemotePngFetcher admission = new SafeRemotePngFetcher(transport,
                new PngValidator(), host -> {
                    resolutions.incrementAndGet();
                    now.addAndGet(Duration.ofSeconds(1).toNanos());
                    return new InetAddress[] {first, second};
                }, now::get);
        PublicHttpsImageFetcher fetcher = new PublicHttpsImageFetcher(admission, transport, now::get);

        assertEquals(200, fetcher.get(SIGNED, Duration.ofSeconds(10), 100).status());
        assertEquals(SIGNED, requested.get());
        assertEquals("sig=a%2Fb%2Bz&expires=123", requested.get().getRawQuery());
        assertEquals(List.of(first, second), pinned.get());
        assertEquals(1, resolutions.get());
    }

    @Test
    void rejectsUnsafeUriBeforeTransport() throws Exception {
        AtomicInteger connects = new AtomicInteger();
        PinnedHttpsTransport transport = new PinnedHttpsTransport() {
            @Override Response get(URI uri, String host, List<InetAddress> addresses,
                    Duration timeout, int maxBodyBytes) {
                connects.incrementAndGet();
                throw new AssertionError("unsafe URI reached transport");
            }
        };
        SafeRemotePngFetcher admission = new SafeRemotePngFetcher(transport,
                new PngValidator(), host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});
        PublicHttpsImageFetcher fetcher = new PublicHttpsImageFetcher(admission, transport, System::nanoTime);
        for (String url : List.of("http://cdn.example.net/a", "https://user@cdn.example.net/a",
                "https://cdn.example.net:444/a", "https://cdn.example.net/a#part",
                "https://localhost/a", "https://127.0.0.1/a", "https://127.1/a",
                "https://2130706433/a", "https://[::1]/a",
                "https://cdn.example.net/a?" + "x".repeat(1025))) {
            assertThrows(PublicHttpsImageFetcher.UnsafeImageUriException.class,
                    () -> fetcher.get(URI.create(url), Duration.ofSeconds(1), 100), url);
        }
        assertEquals(0, connects.get());
    }

    @Test
    void everyDnsAnswerMustBePublicAndPinningPreventsRebinding() throws Exception {
        AtomicInteger connects = new AtomicInteger();
        PinnedHttpsTransport transport = new PinnedHttpsTransport() {
            @Override Response get(URI uri, String host, List<InetAddress> addresses,
                    Duration timeout, int maxBodyBytes) {
                connects.incrementAndGet();
                assertEquals(1, addresses.size());
                assertEquals("8.8.8.8", addresses.get(0).getHostAddress());
                return new Response(302, uri, Map.of("location", List.of("https://private.example/a")),
                        new byte[0]);
            }
        };
        for (String unsafe : List.of("10.0.0.1", "169.254.1.1", "192.0.2.1",
                "100.64.1.1", "2001:db8::1")) {
            SafeRemotePngFetcher admission = new SafeRemotePngFetcher(transport,
                    new PngValidator(), host -> new InetAddress[]{InetAddress.getByName(unsafe)});
            PublicHttpsImageFetcher fetcher = new PublicHttpsImageFetcher(admission, transport,
                    System::nanoTime);
            assertThrows(PublicHttpsImageFetcher.UnsafeImageUriException.class,
                    () -> fetcher.get(SIGNED, Duration.ofSeconds(1), 100));
        }
        SafeRemotePngFetcher mixed = new SafeRemotePngFetcher(transport,
                new PngValidator(), host -> new InetAddress[]{InetAddress.getByName("8.8.8.8"),
                        InetAddress.getByName("127.0.0.1")});
        assertThrows(PublicHttpsImageFetcher.UnsafeImageUriException.class,
                () -> new PublicHttpsImageFetcher(mixed, transport, System::nanoTime)
                        .get(SIGNED, Duration.ofSeconds(1), 100));
        assertEquals(0, connects.get());
        AtomicInteger resolutions = new AtomicInteger();
        SafeRemotePngFetcher rebound = new SafeRemotePngFetcher(transport,
                new PngValidator(), host -> new InetAddress[]{InetAddress.getByName(
                        resolutions.incrementAndGet() == 1 ? "8.8.8.8" : "127.0.0.1")});
        assertEquals(302, new PublicHttpsImageFetcher(rebound, transport, System::nanoTime)
                .get(SIGNED, Duration.ofSeconds(1), 100).status());
        assertEquals(1, resolutions.get());
        assertEquals(1, connects.get());
    }

    @Test
    void dnsAndConnectShareOneDeadline() throws Exception {
        AtomicLong now = new AtomicLong();
        AtomicInteger connects = new AtomicInteger();
        PinnedHttpsTransport transport = new PinnedHttpsTransport() {
            @Override Response get(URI uri, String host, List<InetAddress> addresses,
                    Duration timeout, int maxBodyBytes) {
                connects.incrementAndGet();
                throw new AssertionError("expired request connected");
            }
        };
        SafeRemotePngFetcher admission = new SafeRemotePngFetcher(transport,
                new PngValidator(), host -> {
                    now.addAndGet(Duration.ofSeconds(11).toNanos());
                    return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
                }, now::get);
        PublicHttpsImageFetcher fetcher = new PublicHttpsImageFetcher(admission, transport, now::get);
        assertThrows(IOException.class,
                () -> fetcher.get(SIGNED, Duration.ofSeconds(10), 100));
        assertEquals(0, connects.get());
    }
}

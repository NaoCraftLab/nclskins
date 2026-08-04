package com.naocraftlab.skins.core.api;

import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.test.TestPng;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SafeRemotePngFetcherTest {
    @Test
    void acceptsPublicAddressesAndRejectsLocalAndReservedRanges() throws Exception {
        assertTrue(SafeRemotePngFetcher.isPublic(InetAddress.getByName("8.8.8.8")));
        assertTrue(SafeRemotePngFetcher.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
        assertFalse(SafeRemotePngFetcher.isPublic(InetAddress.getByName("127.0.0.1")));
        assertFalse(SafeRemotePngFetcher.isPublic(InetAddress.getByName("10.0.0.1")));
        assertFalse(SafeRemotePngFetcher.isPublic(InetAddress.getByName("100.64.0.1")));
        assertFalse(SafeRemotePngFetcher.isPublic(InetAddress.getByName("169.254.1.1")));
        assertFalse(SafeRemotePngFetcher.isPublic(InetAddress.getByName("192.0.2.1")));
        assertFalse(SafeRemotePngFetcher.isPublic(InetAddress.getByName("198.51.100.1")));
        assertFalse(SafeRemotePngFetcher.isPublic(InetAddress.getByName("203.0.113.1")));
        assertFalse(SafeRemotePngFetcher.isPublic(InetAddress.getByName("192.88.99.1")));
        assertFalse(SafeRemotePngFetcher.isPublic(InetAddress.getByName("::1")));
        assertFalse(SafeRemotePngFetcher.isPublic(InetAddress.getByName("fd00::1")));
        assertFalse(SafeRemotePngFetcher.isPublic(InetAddress.getByName("2001:db8::1")));
        assertFalse(SafeRemotePngFetcher.isPublic(InetAddress.getByName("100::1")));
        assertFalse(SafeRemotePngFetcher.isPublic(InetAddress.getByName("2001:1::1")));
        assertFalse(SafeRemotePngFetcher.isPublic(InetAddress.getByName("2002::1")));
        assertFalse(SafeRemotePngFetcher.isPublic(InetAddress.getByName("3ffe::1")));
        assertFalse(SafeRemotePngFetcher.isPublic(InetAddress.getByName("4000::1")));
    }

    @Test
    void validatesPublicHttpAndHttpsDefaultPortsAndRejectsMixedDns() throws Exception {
        SafeRemotePngFetcher safe = new SafeRemotePngFetcher(
                new PinnedHttpsTransport(), new PngValidator(),
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        assertEquals("example.com", safe.validate("https://example.com/skin.png?download=1").asciiHost());
        assertEquals("example.com", safe.validate("http://example.com/skin.png").asciiHost());
        assertEquals(80, safe.validate("http://example.com:80/skin.png").uri().getPort());
        assertEquals(443, safe.validate("https://example.com:443/skin.png").uri().getPort());
        assertEquals("2606:4700:4700::1111",
                safe.validate("https://[2606:4700:4700::1111]/skin.png").asciiHost());
        assertEquals(PublicSkinImportException.Code.UNSAFE_URL,
                assertThrows(PublicSkinImportException.class,
                                () -> safe.validate("https://user@example.com/skin.png"))
                        .code());
        for (String unsafe : List.of(
                "http://example.com:81/skin.png",
                "https://example.com:444/skin.png",
                "https://example.com/skin.png#fragment",
                "https://localhost/skin.png",
                "https://127.1/skin.png",
                "https://2130706433/skin.png")) {
            assertEquals(PublicSkinImportException.Code.UNSAFE_URL,
                    assertThrows(PublicSkinImportException.class, () -> safe.validate(unsafe)).code(),
                    unsafe);
        }

        SafeRemotePngFetcher mixed = new SafeRemotePngFetcher(
                new PinnedHttpsTransport(), new PngValidator(),
                host -> new InetAddress[] {
                    InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")
                });
        assertEquals(PublicSkinImportException.Code.UNSAFE_URL,
                assertThrows(PublicSkinImportException.class,
                                () -> mixed.validate("https://example.com/skin.png"))
                        .code());
    }

    @Test
    void fetchConnectsOnlyToTheValidatedAddressSnapshot() throws Exception {
        InetAddress first = InetAddress.getByName("8.8.8.8");
        InetAddress second = InetAddress.getByName("1.1.1.1");
        AtomicReference<List<InetAddress>> connected = new AtomicReference<>();
        PinnedHttpsTransport transport = new PinnedHttpsTransport() {
            @Override
            Response get(
                    URI uri,
                    String asciiHost,
                    List<InetAddress> addresses,
                    Duration timeout,
                    int maxBodyBytes) throws IOException {
                connected.set(List.copyOf(addresses));
                return new Response(200, uri, Map.of("content-type", List.of("image/png")),
                        TestPng.create(64, 64));
            }
        };
        SafeRemotePngFetcher fetcher = new SafeRemotePngFetcher(
                transport, new PngValidator(), ignored -> new InetAddress[] {first, second});

        fetcher.fetch("https://example.com/skin.png");

        assertEquals(List.of(first, second), connected.get());
    }

    @Test
    void followsFiveRelativeAndCrossOriginRedirectsWithFreshDnsAndOneDeadline()
            throws Exception {
        URI start = URI.create("http://one.example/start");
        URI one = URI.create("http://one.example/one");
        URI two = URI.create("https://two.example/two");
        URI three = URI.create("https://two.example/three");
        URI four = URI.create("https://two.example/four");
        URI end = URI.create("https://two.example/final");
        byte[] png = TestPng.create(64, 64);
        Map<URI, PinnedHttpsTransport.Response> responses = new LinkedHashMap<>();
        responses.put(start, redirect(301, start, "/one"));
        responses.put(one, redirect(302, one, "https://two.example/two"));
        responses.put(two, redirect(303, two, "/three"));
        responses.put(three, redirect(307, three, "/four"));
        responses.put(four, redirect(308, four, "/final"));
        responses.put(end, ok(end, png));

        AtomicLong now = new AtomicLong();
        ScriptedTransport transport = new ScriptedTransport(responses, now);
        AtomicInteger oneResolutions = new AtomicInteger();
        List<String> resolvedHosts = new ArrayList<>();
        SafeRemotePngFetcher fetcher = new SafeRemotePngFetcher(
                transport,
                new PngValidator(),
                host -> {
                    resolvedHosts.add(host);
                    if (host.equals("one.example") && oneResolutions.getAndIncrement() == 0) {
                        return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
                    }
                    return new InetAddress[]{InetAddress.getByName("1.1.1.1")};
                },
                now::get);

        assertArrayEquals(png, fetcher.fetch(start.toString()));
        assertEquals(List.of(
                "one.example", "one.example", "two.example", "two.example", "two.example",
                "two.example"), resolvedHosts);
        assertEquals(List.of(InetAddress.getByName("8.8.8.8")),
                transport.calls.get(0).addresses());
        assertEquals(List.of(InetAddress.getByName("1.1.1.1")),
                transport.calls.get(1).addresses());
        assertEquals(6, transport.calls.size());
        for (int index = 1; index < transport.calls.size(); index++) {
            assertTrue(transport.calls.get(index).timeout()
                    .compareTo(transport.calls.get(index - 1).timeout()) < 0);
        }
        assertEquals(Duration.ofSeconds(15), transport.calls.get(5).timeout());
    }

    @Test
    void slowDnsAfterRedirectUsesOnlyTheRemainingOverallDeadline() throws Exception {
        URI start = URI.create("http://one.example/start");
        URI target = URI.create("http://two.example/skin.png");
        AtomicLong now = new AtomicLong();
        AtomicInteger resolutions = new AtomicInteger();
        CountDownLatch releaseSlowLookup = new CountDownLatch(1);
        ExecutorService dnsExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "nclskins-test-dns-resolver");
            thread.setDaemon(true);
            return thread;
        });
        PinnedHttpsTransport transport = new PinnedHttpsTransport() {
            @Override
            Response get(
                    URI uri,
                    String asciiHost,
                    List<InetAddress> addresses,
                    Duration timeout,
                    int maxBodyBytes) throws IOException {
                assertEquals(start, uri);
                return redirect(302, start, target.toString());
            }
        };
        SafeRemotePngFetcher.DeadlineHostResolver resolver =
                SafeRemotePngFetcher.asynchronousResolver(host -> {
                    if (resolutions.incrementAndGet() == 1) {
                        now.set(Duration.ofMillis(150).toNanos());
                        return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
                    }
                    try {
                        releaseSlowLookup.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("test lookup interrupted", interrupted);
                    }
                    return new InetAddress[]{InetAddress.getByName("1.1.1.1")};
                }, dnsExecutor);
        SafeRemotePngFetcher fetcher = new SafeRemotePngFetcher(
                transport,
                new PngValidator(),
                resolver,
                now::get,
                Duration.ofMillis(200));

        long started = System.nanoTime();
        try {
            assertEquals(PublicSkinImportException.Code.NETWORK_FAILURE,
                    assertThrows(PublicSkinImportException.class,
                            () -> fetcher.fetch(start.toString())).code());
            assertEquals(2, resolutions.get());
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(1).toNanos());
        } finally {
            releaseSlowLookup.countDown();
            dnsExecutor.shutdownNow();
        }
    }

    @Test
    void checksOverallDeadlineAfterSuccessfulPngNormalization() throws Exception {
        URI uri = URI.create("https://one.example/skin.png");
        byte[] png = TestPng.create(64, 64);
        AtomicInteger clockReads = new AtomicInteger();
        long expired = Duration.ofSeconds(21).toNanos();
        SafeRemotePngFetcher fetcher = new SafeRemotePngFetcher(
                new PinnedHttpsTransport() {
                    @Override
                    Response get(
                            URI request,
                            String asciiHost,
                            List<InetAddress> addresses,
                            Duration timeout,
                            int maxBodyBytes) {
                        return ok(request, png);
                    }
                },
                new PngValidator(),
                (host, timeout) -> new InetAddress[]{InetAddress.getByName("8.8.8.8")},
                () -> clockReads.incrementAndGet() >= 6 ? expired : 0L,
                Duration.ofSeconds(20));

        assertEquals(PublicSkinImportException.Code.NETWORK_FAILURE,
                assertThrows(PublicSkinImportException.class,
                        () -> fetcher.fetch(uri.toString())).code());
        assertEquals(6, clockReads.get());
    }

    @Test
    void rejectsSixthRedirectDowngradeLoopAndUnsafeDestination() throws Exception {
        URI start = URI.create("https://one.example/start");
        Map<URI, PinnedHttpsTransport.Response> tooMany = new LinkedHashMap<>();
        URI current = start;
        for (int index = 1; index <= 6; index++) {
            URI next = URI.create("https://one.example/" + index);
            tooMany.put(current, redirect(302, current, next.toString()));
            current = next;
        }
        assertEquals(PublicSkinImportException.Code.REDIRECT_REJECTED,
                assertThrows(PublicSkinImportException.class,
                        () -> fetcher(tooMany, publicResolver()).fetch(start.toString())).code());

        Map<URI, PinnedHttpsTransport.Response> downgrade = Map.of(
                start, redirect(302, start, "http://one.example/skin.png"));
        assertEquals(PublicSkinImportException.Code.REDIRECT_REJECTED,
                assertThrows(PublicSkinImportException.class,
                        () -> fetcher(downgrade, publicResolver()).fetch(start.toString())).code());

        Map<URI, PinnedHttpsTransport.Response> loop = Map.of(
                start, redirect(302, start, start.toString()));
        assertEquals(PublicSkinImportException.Code.REDIRECT_REJECTED,
                assertThrows(PublicSkinImportException.class,
                        () -> fetcher(loop, publicResolver()).fetch(start.toString())).code());

        URI privateTarget = URI.create("https://private.example/skin.png");
        Map<URI, PinnedHttpsTransport.Response> privateRedirect = Map.of(
                start, redirect(302, start, privateTarget.toString()));
        SafeRemotePngFetcher.HostResolver privateResolver = host -> new InetAddress[]{
                InetAddress.getByName(host.equals("private.example") ? "127.0.0.1" : "8.8.8.8")
        };
        assertEquals(PublicSkinImportException.Code.REDIRECT_REJECTED,
                assertThrows(PublicSkinImportException.class,
                        () -> fetcher(privateRedirect, privateResolver).fetch(start.toString()))
                        .code());
    }

    @Test
    void rejectsMissingMultipleAndUnsupportedRedirectLocations() throws Exception {
        URI start = URI.create("http://one.example/start");
        for (PinnedHttpsTransport.Response response : List.of(
                new PinnedHttpsTransport.Response(302, start, Map.of(), new byte[0]),
                new PinnedHttpsTransport.Response(302, start,
                        Map.of("location", List.of("/one", "/two")), new byte[0]),
                new PinnedHttpsTransport.Response(304, start,
                        Map.of("location", List.of("/one")), new byte[0]))) {
            assertEquals(PublicSkinImportException.Code.REDIRECT_REJECTED,
                    assertThrows(PublicSkinImportException.class,
                            () -> fetcher(Map.of(start, response), publicResolver())
                                    .fetch(start.toString())).code());
        }
    }

    @Test
    void mapsTransportSizeLimitToOversized() throws Exception {
        URI uri = URI.create("https://one.example/skin.png");
        PinnedHttpsTransport oversized = new PinnedHttpsTransport() {
            @Override
            Response get(
                    URI request,
                    String asciiHost,
                    List<InetAddress> addresses,
                    Duration timeout,
                    int maxBodyBytes) throws IOException {
                String headers = "HTTP/1.1 200 OK\r\nContent-Length: "
                        + (maxBodyBytes + 1L) + "\r\n\r\n";
                return readResponse(
                        new java.io.ByteArrayInputStream(
                                headers.getBytes(StandardCharsets.US_ASCII)),
                        request,
                        maxBodyBytes);
            }
        };
        SafeRemotePngFetcher fetcher = new SafeRemotePngFetcher(
                oversized, new PngValidator(), publicResolver());

        assertEquals(PublicSkinImportException.Code.OVERSIZED,
                assertThrows(PublicSkinImportException.class,
                        () -> fetcher.fetch(uri.toString())).code());
    }

    @Test
    void mapsRateLimitAndServerFailuresToTypedCodes() throws Exception {
        URI uri = URI.create("https://one.example/skin.png");
        assertEquals(PublicSkinImportException.Code.RATE_LIMITED,
                assertThrows(PublicSkinImportException.class,
                        () -> fetcher(Map.of(uri,
                                new PinnedHttpsTransport.Response(
                                        429, uri, Map.of(), new byte[0])), publicResolver())
                                .fetch(uri.toString())).code());
        assertEquals(PublicSkinImportException.Code.SERVICE_UNAVAILABLE,
                assertThrows(PublicSkinImportException.class,
                        () -> fetcher(Map.of(uri,
                                new PinnedHttpsTransport.Response(
                                        503, uri, Map.of(), new byte[0])), publicResolver())
                                .fetch(uri.toString())).code());
    }

    private static SafeRemotePngFetcher fetcher(
            Map<URI, PinnedHttpsTransport.Response> responses,
            SafeRemotePngFetcher.HostResolver resolver) {
        return new SafeRemotePngFetcher(
                new ScriptedTransport(responses, new AtomicLong()),
                new PngValidator(),
                resolver);
    }

    private static SafeRemotePngFetcher.HostResolver publicResolver() {
        return ignored -> new InetAddress[]{InetAddress.getByName("8.8.8.8")};
    }

    private static PinnedHttpsTransport.Response redirect(int status, URI uri, String location) {
        return new PinnedHttpsTransport.Response(
                status, uri, Map.of("location", List.of(location)), new byte[0]);
    }

    private static PinnedHttpsTransport.Response ok(URI uri, byte[] png) {
        return new PinnedHttpsTransport.Response(
                200, uri, Map.of("content-type", List.of("image/png")), png);
    }

    private record Call(URI uri, List<InetAddress> addresses, Duration timeout) {
    }

    private static final class ScriptedTransport extends PinnedHttpsTransport {
        private final Map<URI, PinnedHttpsTransport.Response> responses;
        private final AtomicLong now;
        private final List<Call> calls = new ArrayList<>();

        private ScriptedTransport(
                Map<URI, PinnedHttpsTransport.Response> responses, AtomicLong now) {
            this.responses = new LinkedHashMap<>(responses);
            this.now = now;
        }

        @Override
        Response get(
                URI uri,
                String asciiHost,
                List<InetAddress> addresses,
                Duration timeout,
                int maxBodyBytes) throws IOException {
            calls.add(new Call(uri, List.copyOf(addresses), timeout));
            PinnedHttpsTransport.Response response = responses.get(uri);
            if (response == null) {
                throw new IOException("No scripted response");
            }
            now.addAndGet(Duration.ofSeconds(1).toNanos());
            return response;
        }
    }
}

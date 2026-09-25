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
    private static final URI SIGNED = URI.create(
            "https://cdn.example.net:443/cape%2Fone.png?sig=a%2Fb%2Bz&expires=123");

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

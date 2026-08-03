package com.naocraftlab.skins.core.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.test.TestPng;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

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
    void validatesOnlyDirectPublicHttpsAndRejectsMixedDns() throws Exception {
        SafeRemotePngFetcher safe = new SafeRemotePngFetcher(
                new PinnedHttpsTransport(), new PngValidator(),
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        assertEquals("example.com", safe.validate("https://example.com/skin.png?download=1").asciiHost());
        assertEquals("2606:4700:4700::1111",
                safe.validate("https://[2606:4700:4700::1111]/skin.png").asciiHost());
        assertEquals(PublicSkinImportException.Code.UNSAFE_URL,
                assertThrows(PublicSkinImportException.class,
                                () -> safe.validate("http://example.com/skin.png"))
                        .code());
        assertEquals(PublicSkinImportException.Code.UNSAFE_URL,
                assertThrows(PublicSkinImportException.class,
                                () -> safe.validate("https://user@example.com/skin.png"))
                        .code());
        for (String unsafe : List.of(
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
}

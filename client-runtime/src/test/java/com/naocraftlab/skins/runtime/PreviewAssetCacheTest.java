package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.client.TextureRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PreviewAssetCacheTest {
    @Test
    void oneKeyStartsOneLoadAndRetainsTheRegisteredHandle() {
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache =
                new PreviewAssetCache<>(registry, TextureRegistry.TextureKind.PLAYER_SKIN);
        AtomicInteger loads = new AtomicInteger();

        cache.request("skin", () -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of(new byte[] {1, 2, 3}));
        }, () -> {});
        cache.request("skin", () -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of(new byte[] {4}));
        }, () -> {});

        assertEquals(1, loads.get());
        assertEquals("texture/1", cache.handle("skin").orElseThrow().location());
    }

    @Test
    void staleAsyncCompletionCannotInstallAfterTheKeyWasReleased() {
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache =
                new PreviewAssetCache<>(registry, TextureRegistry.TextureKind.IMAGE);
        CompletableFuture<Optional<byte[]>> load = new CompletableFuture<>();
        cache.request("cape", () -> load, () -> {});

        cache.retain(Set.of());
        load.complete(Optional.of(new byte[] {1}));

        assertTrue(cache.handle("cape").isEmpty());
        assertEquals(0, registry.registrations);
    }

    @Test
    void retainAndCloseReleaseEachOwnedReferenceOnce() {
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache =
                new PreviewAssetCache<>(registry, TextureRegistry.TextureKind.IMAGE);
        cache.request(
                "first",
                () -> CompletableFuture.completedFuture(Optional.of(new byte[] {1})),
                () -> {});
        cache.request(
                "second",
                () -> CompletableFuture.completedFuture(Optional.of(new byte[] {2})),
                () -> {});

        cache.retain(Set.of("second"));
        cache.close();
        cache.close();

        assertEquals(Set.of("texture/1", "texture/2"), Set.copyOf(registry.released));
        assertEquals(2, registry.released.size());
    }

    @Test
    void distinctConsumerKeysEachReceiveRegistrationFailure() {
        FakeRegistry registry = new FakeRegistry();
        registry.failRegistration = true;
        PreviewAssetCache<String> cache =
                new PreviewAssetCache<>(registry, TextureRegistry.TextureKind.PLAYER_SKIN);
        AtomicInteger failures = new AtomicInteger();

        cache.request(
                "gallery:shared",
                () -> CompletableFuture.completedFuture(Optional.of(new byte[] {1, 2, 3})),
                failures::incrementAndGet);
        cache.request(
                "editor:shared",
                () -> CompletableFuture.completedFuture(Optional.of(new byte[] {1, 2, 3})),
                failures::incrementAndGet);

        assertEquals(2, registry.registrations);
        assertEquals(2, failures.get());
    }

    @Test
    void releaseFailureDoesNotStrandRemainingHandlesOrRegistryCleanup() {
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache =
                new PreviewAssetCache<>(registry, TextureRegistry.TextureKind.IMAGE);
        cache.request(
                "first",
                () -> CompletableFuture.completedFuture(Optional.of(new byte[] {1})),
                () -> {});
        cache.request(
                "second",
                () -> CompletableFuture.completedFuture(Optional.of(new byte[] {2})),
                () -> {});
        cache.request(
                "kept",
                () -> CompletableFuture.completedFuture(Optional.of(new byte[] {3})),
                () -> {});
        registry.failRelease = "texture/1";

        try {
            cache.retain(Set.of("kept"));
            assertTrue(cache.handle("first").isEmpty());
            assertTrue(cache.handle("second").isEmpty());
            assertEquals("texture/3", cache.handle("kept").orElseThrow().location());
            cache.close();
        } finally {
            registry.close();
        }

        assertEquals(
                Set.of("texture/1", "texture/2", "texture/3"),
                Set.copyOf(registry.released));
        assertEquals(3, registry.released.size());
        assertTrue(cache.handle("first").isEmpty());
        assertTrue(cache.handle("second").isEmpty());
        assertTrue(cache.handle("kept").isEmpty());
        assertTrue(registry.closed);
        assertFalse(registry.open);
    }

    private static final class FakeRegistry implements TextureRegistry {
        private final java.util.ArrayList<String> released = new java.util.ArrayList<>();
        private int registrations;
        private boolean failRegistration;
        private String failRelease;
        private boolean closed;
        private boolean open = true;

        @Override
        public TextureHandle register(TextureKind kind, String sha256, Path pngFile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TextureHandle register(TextureKind kind, String sha256, byte[] pngBytes)
                throws IOException {
            registrations++;
            if (failRegistration) {
                throw new IOException("registration failed");
            }
            return new TextureHandle("texture/" + registrations, 64, 64);
        }

        @Override
        public void release(TextureHandle handle) {
            released.add(handle.location());
            if (handle.location().equals(failRelease)) {
                throw new IllegalStateException("release failed");
            }
        }

        @Override
        public void close() {
            closed = true;
            open = false;
        }
    }
}

package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.TextureRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void emptyAndExceptionalLoadsRetryAtTheDeterministicBackoffBoundaries() {
        FakeRegistry registry = new FakeRegistry();
        FakeMonotonicClock clock = new FakeMonotonicClock();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(
                registry, TextureRegistry.TextureKind.IMAGE, clock::now);
        AtomicInteger loads = new AtomicInteger();

        cache.request("cape", () -> result(loads, Optional.empty()), () -> {
        });
        clock.advanceMillis(249L);
        cache.request("cape", () -> result(loads, Optional.of(new byte[]{1})), () -> {
        });
        assertEquals(1, loads.get());

        clock.advanceMillis(1L);
        cache.request("cape", () -> failed(loads), () -> {
        });
        clock.advanceMillis(999L);
        cache.request("cape", () -> result(loads, Optional.of(new byte[]{1})), () -> {
        });
        assertEquals(2, loads.get());

        clock.advanceMillis(1L);
        cache.request("cape", () -> result(loads, Optional.empty()), () -> {
        });
        clock.advanceMillis(4_999L);
        cache.request("cape", () -> result(loads, Optional.of(new byte[]{1})), () -> {
        });
        assertEquals(3, loads.get());

        clock.advanceMillis(1L);
        cache.request("cape", () -> result(loads, Optional.of(new byte[]{1})), () -> {
        });

        assertEquals(4, loads.get());
        assertEquals("texture/1", cache.handle("cape").orElseThrow().location());
    }

    @Test
    void onlyOneLoadPerKeyCanBeInFlightDuringRetries() {
        FakeRegistry registry = new FakeRegistry();
        FakeMonotonicClock clock = new FakeMonotonicClock();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(
                registry, TextureRegistry.TextureKind.IMAGE, clock::now);
        AtomicInteger loads = new AtomicInteger();
        CompletableFuture<Optional<byte[]>> pending = new CompletableFuture<>();

        cache.request("cape", () -> {
            loads.incrementAndGet();
            return pending;
        }, () -> {
        });
        clock.advanceMillis(10_000L);
        cache.request("cape", () -> result(loads, Optional.of(new byte[]{1})), () -> {
        });

        assertEquals(1, loads.get());
        pending.complete(Optional.empty());
        cache.request("cape", () -> result(loads, Optional.of(new byte[]{1})), () -> {
        });
        assertEquals(1, loads.get());
        clock.advanceMillis(250L);
        cache.request("cape", () -> result(loads, Optional.of(new byte[]{1})), () -> {
        });
        assertEquals(2, loads.get());
    }

    @Test
    void firstRequestDoesNotAssumeThatTheMonotonicClockIsPositive() {
        FakeRegistry registry = new FakeRegistry();
        FakeMonotonicClock clock = new FakeMonotonicClock(-1L);
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(
                registry, TextureRegistry.TextureKind.IMAGE, clock::now);

        cache.request(
                "cape",
                () -> CompletableFuture.completedFuture(Optional.of(new byte[]{1})),
                () -> {
                });

        assertEquals("texture/1", cache.handle("cape").orElseThrow().location());
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
    void staleCompletionCannotReplaceARecreatedKeyOrReleaseItsHandleTwice() {
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache =
                new PreviewAssetCache<>(registry, TextureRegistry.TextureKind.IMAGE);
        CompletableFuture<Optional<byte[]>> stale = new CompletableFuture<>();
        cache.request("cape", () -> stale, () -> {
        });

        cache.retain(Set.of());
        cache.request(
                "cape",
                () -> CompletableFuture.completedFuture(Optional.of(new byte[]{2})),
                () -> {
                });
        stale.complete(Optional.of(new byte[]{1}));
        cache.close();
        cache.close();

        assertEquals(1, registry.registrations);
        assertEquals(List.of("texture/1"), registry.released);
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
    void registrationFailureBackoffIsInstalledBeforeAnIsolatedCallbackRuns() {
        FakeRegistry registry = new FakeRegistry();
        registry.failRegistration = true;
        FakeMonotonicClock clock = new FakeMonotonicClock();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(
                registry, TextureRegistry.TextureKind.IMAGE, clock::now);
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();

        cache.request(
                "cape",
                () -> result(loads, Optional.of(new byte[]{1})),
                () -> {
                    callbacks.incrementAndGet();
                    cache.request(
                            "cape",
                            () -> result(loads, Optional.of(new byte[]{2})),
                            () -> {
                            });
                    throw new IllegalStateException("consumer callback failed");
                });

        assertEquals(1, loads.get());
        assertEquals(1, registry.registrations);
        assertEquals(1, callbacks.get());

        clock.advanceMillis(249L);
        cache.request(
                "cape",
                () -> result(loads, Optional.of(new byte[]{3})),
                () -> {
                });
        assertEquals(1, loads.get());

        registry.failRegistration = false;
        clock.advanceMillis(1L);
        cache.request(
                "cape",
                () -> result(loads, Optional.of(new byte[]{4})),
                () -> {
                });

        assertEquals(2, loads.get());
        assertEquals("texture/2", cache.handle("cape").orElseThrow().location());
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

    private static CompletableFuture<Optional<byte[]>> result(
            AtomicInteger loads, Optional<byte[]> result) {
        loads.incrementAndGet();
        return CompletableFuture.completedFuture(result);
    }

    private static CompletableFuture<Optional<byte[]>> failed(AtomicInteger loads) {
        loads.incrementAndGet();
        return CompletableFuture.failedFuture(new IOException("preview unavailable"));
    }

    private static final class FakeMonotonicClock {
        private long now;

        private FakeMonotonicClock() {
        }

        private FakeMonotonicClock(long now) {
            this.now = now;
        }

        private long now() {
            return now;
        }

        private void advanceMillis(long millis) {
            now += TimeUnit.MILLISECONDS.toNanos(millis);
        }
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

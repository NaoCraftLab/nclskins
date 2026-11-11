package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PreviewAssetLoaderTest {
    private final Queue<Runnable> work = new ArrayDeque<>();
    private final Executor worker = work::add;
    private final ClientExecutor client = new ClientExecutor() {
        public boolean isClientThread() { return true; }
        public void execute(Runnable action) { action.run(); }
    };

    @Test
    void coalescesAndReturnsIndependentBytesWithoutOwningWorker() {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        var loads = new AtomicInteger();
        PreviewAssets.Source source = () -> {
            loads.incrementAndGet();
            return Optional.of(new byte[]{7});
        };
        var first = loader.requestPreview("skin", source);
        var second = loader.requestPreview("skin", source);
        assertEquals(1, work.size());
        work.remove().run();
        first.join().orElseThrow()[0] = 42;
        assertEquals(7, second.join().orElseThrow()[0]);
        assertEquals(7, loader.requestPreview("skin", source).join().orElseThrow()[0]);
        assertEquals(1, loads.get());
        loader.close();
        worker.execute(() -> loads.incrementAndGet());
        work.remove().run();
        assertEquals(2, loads.get());
    }

    @Test
    void asynchronousComparisonNeverOwnsImageReadinessAndIsCancelledOnLoaderClose() {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        var preparation = new java.util.concurrent.CompletableFuture<Optional<PreviewAssets.CapeResult>>();
        var typed = loader.requestCapePreview("cape", new PreviewAssets.CapeSource() {
            public Optional<PreviewAssets.CapeResult> get() { throw new AssertionError("Synchronous full comparison"); }
            public java.util.concurrent.CompletableFuture<Optional<PreviewAssets.CapeResult>> getAsync(Executor executor) {
                return preparation;
            }
        });
        work.remove().run();
        var image = loader.requestPreview("cape", () -> Optional.of(new byte[]{7}));
        work.remove().run();
        assertEquals(7, image.join().orElseThrow()[0]);
        assertFalse(typed.isDone());
        assertTrue(loader.cachedCapeComparison("cape").isEmpty());
        assertEquals(0, work.size());
        loader.close();
        assertTrue(preparation.isCancelled());
        assertTrue(typed.join().isEmpty());
        assertTrue(loader.cachedCapeComparison("cape").isEmpty());
    }

    @Test void rejectedComparisonHasTerminalUnknownMetadata() {
        var loader = new PreviewAssetLoader(client, command -> {
            throw new java.util.concurrent.RejectedExecutionException("Synthetic unavailable worker");
        }, DiagnosticSinks.discarding());
        try {
            assertTrue(loader.requestCapePreview("cape", Optional::empty).join().isEmpty());
            assertEquals(Optional.of(new PreviewAssets.CapeComparison(Optional.empty())), loader.cachedCapeComparison("cape"));
        } finally { loader.close(); }
    }

    @Test void successfulTypedLoadsEvictComparisonWithoutLosingImageOrIdentity() throws Exception {
        Queue<Runnable> callbacks = new ArrayDeque<>();
        ClientExecutor queuedClient = new ClientExecutor() {
            public boolean isClientThread() { return false; }
            public void execute(Runnable action) { callbacks.add(action); }
        };
        var loader = new PreviewAssetLoader(queuedClient, worker, DiagnosticSinks.discarding());
        var calls = new AtomicInteger();
        try {
            for (int i = 0; i < 129; i++) {
                int index = i;
                var result = loader.requestCapePreview("cape-" + i, () -> {
                    calls.incrementAndGet();
                    return Optional.of(new PreviewAssets.CapeResult(new byte[]{(byte) index}, Optional.of("visual-" + index),
                            new PreviewAssets.CapeComparison(Optional.of("comparison-" + index))));
                });
                work.remove().run();
                callbacks.remove().run();
                assertEquals(Optional.of("comparison-" + i), result.join().orElseThrow().comparison().key());
            }
            var warm = loader.requestCapePreview("cape-128", () -> { throw new AssertionError("warm reload"); });
            callbacks.remove().run();
            assertEquals(Optional.of("comparison-128"), warm.join().orElseThrow().comparison().key());
            assertEquals(129, calls.get());
            assertTrue(loader.cachedCapeComparison("cape-0").isEmpty());
            var image = loader.requestPreview("cape-0", () -> { throw new AssertionError("image reload"); });
            callbacks.remove().run();
            assertEquals(0, image.join().orElseThrow()[0]);
            assertEquals(Optional.of("visual-0"), loader.cachedCapeVisualIdentity("cape-0"));
            var reload = loader.requestCapePreview("cape-0", () -> {
                calls.incrementAndGet();
                return Optional.of(new PreviewAssets.CapeResult(new byte[]{0}, Optional.of("visual-0"),
                        new PreviewAssets.CapeComparison(Optional.of("comparison-0"))));
            });
            assertFalse(reload.isDone());
            assertEquals(1, work.size());
            assertEquals(reload, loader.requestCapePreview("cape-0", Optional::empty));
            work.remove().run();
            callbacks.remove().run();
            assertEquals(Optional.of("comparison-0"), reload.join().orElseThrow().comparison().key());
            assertEquals(130, calls.get());
            assertTrue(work.isEmpty());
            assertTrue(callbacks.isEmpty());
            var field = PreviewAssetLoader.class.getDeclaredField("previewBytes");
            field.setAccessible(true);
            var stored = (java.util.Map<?, ?>) field.get(loader);
            assertEquals(129, stored.size());
            for (Object value : stored.values()) {
                assertFalse(value instanceof PreviewAssets.CapeResult);
                for (var member : value.getClass().getDeclaredFields()) {
                    assertFalse(member.getType().equals(PreviewAssets.CapeComparison.class));
                    assertFalse(member.getType().equals(PreviewAssets.CapeResult.class));
                }
            }
            var comparisons = PreviewAssetLoader.class.getDeclaredField("capeComparisons");
            comparisons.setAccessible(true);
            assertEquals(128, ((java.util.Map<?, ?>) comparisons.get(loader)).size());
        } finally { loader.close(); }
    }

    @Test void comparisonRetentionIsBoundedAndRetiresWithSourceBytes() {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        try {
            for (int i = 0; i < 129; i++) loader.rememberCapeComparison("cape-" + i,
                    new PreviewAssets.CapeComparison(Optional.of("synthetic-key")));
            assertTrue(loader.cachedCapeComparison("cape-0").isEmpty());
            assertTrue(loader.cachedCapeComparison("cape-128").isPresent());
            assertTrue(work.isEmpty());
            loader.installWarmed(java.util.Map.of("cape-128", new byte[]{7}), false);
            assertTrue(loader.cachedCapeComparison("cape-128").isEmpty());
            loader.rememberCapeComparison("catalog:0:cape", new PreviewAssets.CapeComparison(Optional.empty()));
            loader.invalidateCatalogPreviews();
            assertTrue(loader.cachedCapeComparison("catalog:0:cape").isEmpty());
        } finally { loader.close(); }
        assertTrue(loader.cachedCapeComparison("cape-127").isEmpty());
    }

    @Test
    void cachedIdentityIsPureTypedMetadataAndRetiresWithItsBytes() {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        loader.installWarmed(java.util.Map.of("cape", new byte[]{7}), false);
        assertTrue(loader.cachedCapeVisualIdentity("cape").isEmpty());
        var typed = loader.requestCapePreview("cape", () -> Optional.of(
                new PreviewAssets.CapeResult(new byte[]{7}, Optional.of("full"))));
        var coalesced = loader.requestCapePreview("cape", () -> { throw new AssertionError("duplicate lookup"); });
        org.junit.jupiter.api.Assertions.assertSame(typed, coalesced);
        assertTrue(loader.cachedCapeVisualIdentity("cape").isEmpty());
        work.remove().run();
        for (int i = 0; i < 5; i++) assertEquals(Optional.of("full"), loader.cachedCapeVisualIdentity("cape"));
        assertTrue(work.isEmpty());
        loader.installWarmed(java.util.Map.of("cape", new byte[]{8}), false);
        assertTrue(loader.cachedCapeVisualIdentity("cape").isEmpty());
        loader.requestCapePreview("catalog:0:cape", () -> Optional.of(
                new PreviewAssets.CapeResult(new byte[]{9}, Optional.of("catalog"))));
        work.remove().run();
        assertEquals(Optional.of("catalog"), loader.cachedCapeVisualIdentity("catalog:0:cape"));
        loader.invalidateCatalogPreviews();
        assertTrue(loader.cachedCapeVisualIdentity("catalog:0:cape").isEmpty());
        loader.close();
        assertTrue(loader.cachedCapeVisualIdentity("cape").isEmpty());
        assertTrue(loader.cachedCapeVisualIdentity("absent").isEmpty());
    }

    @Test
    void rejectedTypedUpgradePreservesWarmImageAndAllowsRetry() {
        var reject = new java.util.concurrent.atomic.AtomicBoolean(true);
        var loader = new PreviewAssetLoader(client, action -> {
            if (reject.get()) throw new java.util.concurrent.RejectedExecutionException("fixture");
            work.add(action);
        }, DiagnosticSinks.discarding());
        loader.installWarmed(java.util.Map.of("cape", new byte[]{7}), false);
        PreviewAssets.CapeSource source = () -> Optional.of(new PreviewAssets.CapeResult(new byte[]{7}, Optional.of("full")));
        assertTrue(loader.requestCapePreview("cape", source).join().isEmpty());
        assertEquals(7, loader.requestPreview("cape", Optional::empty).join().orElseThrow()[0]);
        assertTrue(loader.cachedCapeVisualIdentity("cape").isEmpty());
        reject.set(false);
        var retry = loader.requestCapePreview("cape", source);
        assertFalse(retry.isDone());
        work.remove().run();
        assertEquals(Optional.of("full"), retry.join().orElseThrow().visualSha256());
        loader.close();
    }

    @Test
    void capeMetadataFollowsSharedBytesThroughCoalescingAndCache() {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        var loads = new AtomicInteger();
        PreviewAssets.CapeSource source = () -> {
            loads.incrementAndGet();
            return Optional.of(new PreviewAssets.CapeResult(new byte[]{7}, Optional.of("identity")));
        };
        var first = loader.requestCapePreview("cape", source);
        var bytes = loader.requestPreview("cape", () -> Optional.of(new byte[]{7}));
        assertEquals(2, work.size(), "Image work must be independent of full comparison metadata");
        work.remove().run();
        work.remove().run();
        assertEquals(Optional.of("identity"), first.join().orElseThrow().visualSha256());
        assertEquals(7, bytes.join().orElseThrow()[0]);
        assertEquals(Optional.of("identity"), loader.requestCapePreview("cape", source).join().orElseThrow().visualSha256());
        assertEquals(1, loads.get());
        loader.close();
    }

    @Test
    void rawInFlightDoesNotSuppressTypedIdentity() {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        var raw = loader.requestPreview("cape", () -> Optional.of(new byte[]{7}));
        var loads = new AtomicInteger();
        var typed = loader.requestCapePreview("cape", () -> {
            loads.incrementAndGet();
            return Optional.of(new PreviewAssets.CapeResult(new byte[]{7}, Optional.of("identity")));
        });
        while (!work.isEmpty()) work.remove().run();
        assertEquals(7, raw.join().orElseThrow()[0]);
        assertEquals(Optional.of("identity"), typed.join().orElseThrow().visualSha256());
        assertEquals(1, loads.get());
        loader.close();
    }

    @Test
    void warmedBytesDoNotSuppressFullCapeIdentityLookup() {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        loader.installWarmed(java.util.Map.of("cape", new byte[]{7}), false);
        var loads = new AtomicInteger();
        var result = loader.requestCapePreview("cape", () -> {
            loads.incrementAndGet();
            return Optional.of(new PreviewAssets.CapeResult(new byte[]{7}, Optional.of("identity")));
        });
        while (!work.isEmpty()) work.remove().run();
        assertEquals(Optional.of("identity"), result.join().orElseThrow().visualSha256());
        assertEquals(1, loads.get());
        loader.close();
    }

    @Test
    void replacedWarmedResourceCannotBeOverwrittenByOldSameKeyCompletion() {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        var old = loader.requestCapePreview("resource:cape:x", () -> Optional.of(
                new PreviewAssets.CapeResult(new byte[]{1}, Optional.of("old"))));
        loader.installWarmed(java.util.Map.of("resource:cape:x", new byte[]{2}), true);
        work.remove().run();
        var lookup = loader.requestCapePreview("resource:cape:x", Optional::empty);
        while (!work.isEmpty()) work.remove().run();
        var current = lookup.join().orElseThrow();
        assertEquals(2, current.bytes()[0]);
        assertTrue(current.visualSha256().isEmpty());
        loader.close();
    }

    @Test
    void reentrantWarmedReplacementKeepsNewerBytes() {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        var pending = loader.requestCapePreview("resource:cape:x", Optional::empty);
        pending.thenRun(() -> loader.installWarmed(java.util.Map.of("resource:cape:x", new byte[]{3}), true));
        loader.installWarmed(java.util.Map.of("resource:cape:x", new byte[]{2}), true);
        work.remove().run();
        assertEquals(3, loader.requestPreview("resource:cape:x", Optional::empty).join().orElseThrow()[0]);
        loader.close();
    }

    @Test
    void reentrantCloseLeavesNoWarmedBytes() throws Exception {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        var pending = loader.requestCapePreview("resource:cape:x", Optional::empty);
        pending.thenRun(loader::close);
        loader.installWarmed(java.util.Map.of("resource:cape:x", new byte[]{2}), true);
        work.remove().run();
        var field = PreviewAssetLoader.class.getDeclaredField("previewBytes");
        field.setAccessible(true);
        assertTrue(((java.util.Map<?, ?>) field.get(loader)).isEmpty());
        assertThrows(IllegalStateException.class, () -> loader.requestCapePreview("resource:cape:x", Optional::empty));
    }

    @Test
    void closeSettlesPendingDeliveryAndRejectsNewRequests() {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        var pending = loader.requestPreview("skin", () -> Optional.of(new byte[]{7}));
        assertFalse(pending.isDone());
        loader.close();
        assertTrue(pending.join().isEmpty());
        work.remove().run();
        assertTrue(pending.join().isEmpty());
        assertThrows(IllegalStateException.class,
                () -> loader.requestPreview("skin", Optional::empty));
    }

    @Test
    void warmedImageIsImmediateWhileFullMetadataRemainsPending() {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        loader.installWarmed(java.util.Map.of("cape", new byte[]{7}), false);
        var metadata = loader.requestCapePreview("cape", () -> Optional.of(
                new PreviewAssets.CapeResult(new byte[]{7}, Optional.of("identity"))));
        var image = loader.requestPreview("cape", () -> { throw new AssertionError("cached image reloaded"); });
        assertTrue(image.isDone());
        assertEquals(7, image.join().orElseThrow()[0]);
        assertFalse(metadata.isDone());
        work.remove().run();
        assertEquals(Optional.of("identity"), metadata.join().orElseThrow().visualSha256());
        loader.close();
    }

    @Test
    void completedUnknownFullMetadataDoesNotRepeatWorkerLookup() {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        var calls = new AtomicInteger();
        PreviewAssets.CapeSource source = () -> {
            calls.incrementAndGet();
            return Optional.of(new PreviewAssets.CapeResult(new byte[]{7}, Optional.empty()));
        };
        var first = loader.requestCapePreview("cape", source);
        work.remove().run();
        assertTrue(first.join().orElseThrow().visualSha256().isEmpty());
        assertTrue(loader.requestCapePreview("cape", source).join().orElseThrow().visualSha256().isEmpty());
        assertTrue(work.isEmpty());
        assertEquals(1, calls.get());
        loader.close();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void retiringRawAndTypedRequestsPreventsLateSources(boolean close) {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        var calls = new AtomicInteger();
        var raw = loader.requestPreview("cape", () -> {
            calls.incrementAndGet();
            return Optional.of(new byte[]{1});
        });
        var typed = loader.requestCapePreview("cape", () -> {
            calls.incrementAndGet();
            return Optional.of(new PreviewAssets.CapeResult(new byte[]{1}, Optional.of("retired")));
        });
        if (close) loader.close();
        else loader.installWarmed(java.util.Map.of("cape", new byte[]{2}), false);
        assertTrue(raw.join().isEmpty());
        assertTrue(typed.join().isEmpty());
        while (!work.isEmpty()) work.remove().run();
        assertEquals(0, calls.get());
        if (!close) assertEquals(2, loader.requestPreview("cape", Optional::empty).join().orElseThrow()[0]);
        loader.close();
    }

    @Test
    void closeSettlesInvalidatedCatalogRequestsWhenWorkerDiscardsQueuedTasks() {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        var first = loader.requestPreview("catalog:0:skin", () -> Optional.of(new byte[]{7}));
        var coalesced = loader.requestPreview("catalog:0:skin", Optional::empty);
        loader.invalidateCatalogPreviews();
        var current = loader.requestPreview("catalog:1:skin", Optional::empty);
        loader.close();
        work.clear();
        assertTrue(first.isDone());
        assertTrue(coalesced.isDone());
        assertTrue(current.isDone());
        assertTrue(first.join().isEmpty());
        assertTrue(coalesced.join().isEmpty());
        assertTrue(current.join().isEmpty());
    }

    @Test
    void failuresAreRetryableAndOldCatalogGenerationCannotPopulateNewCache() {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        var failed = loader.requestPreview("skin", () -> { throw new java.io.IOException(); });
        work.remove().run();
        assertTrue(failed.join().isEmpty());
        var retry = loader.requestPreview("skin", () -> Optional.of(new byte[]{9}));
        work.remove().run();
        assertEquals(9, retry.join().orElseThrow()[0]);
        var old = loader.requestPreview("catalog:0:skin", () -> Optional.of(new byte[]{1}));
        loader.invalidateCatalogPreviews();
        var current = loader.requestPreview("catalog:1:skin", () -> Optional.of(new byte[]{2}));
        work.remove().run();
        work.remove().run();
        assertEquals(1, old.join().orElseThrow()[0]);
        assertEquals(2, current.join().orElseThrow()[0]);
        assertEquals(2, loader.requestPreview("catalog:1:skin", Optional::empty).join().orElseThrow()[0]);
    }

    @Test
    void savedCapePosterIsReadyBeforeWorkerAndRetiredWithAccountEpoch() {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        var poster = new ProviderCapePoster(1, 1, new byte[]{1, 2, 3, (byte) 255},
                "0".repeat(64), CapePreparationQueue.tryReserveCpu(4));
        loader.installWarmedProviderPosters(java.util.Map.of("a".repeat(64), poster));
        var ready = loader.requestProviderPoster("a".repeat(64), Optional::empty);
        assertTrue(ready.isDone());
        assertEquals(1, ready.join().orElseThrow().rgba8()[0]);
        assertTrue(work.isEmpty(), "a saved poster must not wait for a new worker job");
        loader.invalidateProviderPosters();
        var missing = loader.requestProviderPoster("a".repeat(64), Optional::empty);
        work.remove().run();
        assertTrue(missing.join().isEmpty());
        loader.close();
    }

    @Test
    void invalidatedPosterWorkerCannotRepublishOldAccountOrCorruptAsset() {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        var old = new ProviderCapePoster(1, 1, new byte[]{4, 5, 6, (byte) 255},
                "1".repeat(64), CapePreparationQueue.tryReserveCpu(4));
        var pending = loader.requestProviderPoster("a".repeat(64), () -> Optional.of(old));
        loader.invalidateProviderPoster("a".repeat(64));
        assertTrue(pending.join().isEmpty());
        work.remove().run();
        var fresh = loader.requestProviderPoster("a".repeat(64), Optional::empty);
        work.remove().run();
        assertTrue(fresh.join().isEmpty());
        loader.close();
        try (var all = CapePreparationQueue.tryReserveCpu(64L * 1024 * 1024)) {
            assertTrue(all != null, "stale poster completion must release its CPU lease");
        }
    }

    @Test
    void offscreenPosterCacheEvictsOldestWithinItsQuota() {
        var loader = new PreviewAssetLoader(client, worker, DiagnosticSinks.discarding());
        for (int index = 0; index < 34; index++) {
            byte[] rgba = new byte[512 * 256 * 4];
            rgba[0] = (byte) index;
            String key = "%064x".formatted(index);
            var poster = new ProviderCapePoster(512, 256, rgba, key,
                    CapePreparationQueue.tryReserveCpu(rgba.length));
            loader.installWarmedProviderPosters(java.util.Map.of(key, poster));
        }
        assertTrue(loader.requestProviderPoster("%064x".formatted(33), Optional::empty).isDone());
        var evicted = loader.requestProviderPoster("%064x".formatted(0), Optional::empty);
        assertFalse(evicted.isDone(), "old offscreen poster must not stay cached without bound");
        work.remove().run();
        assertTrue(evicted.join().isEmpty());
        loader.close();
        try (var all = CapePreparationQueue.tryReserveCpu(64L * 1024 * 1024)) {
            assertTrue(all != null);
        }
    }
}

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
}

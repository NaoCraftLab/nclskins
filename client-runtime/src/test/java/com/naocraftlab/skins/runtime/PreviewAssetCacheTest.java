package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.core.png.CapeAssetDecoder;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PreviewAssetCacheTest {
    private static final DiagnosticSink DIAGNOSTICS = DiagnosticSinks.discarding();

    @Test
    void unrelatedPosterInvalidationKeepsCurrentRowLoadAndSameKeyReplacementRejectsOldLoad() {
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS);
        java.util.ArrayDeque<Runnable> jobs = new java.util.ArrayDeque<>();
        ClientExecutor client = new ClientExecutor() {
            public boolean isClientThread() { return true; }
            public void execute(Runnable action) { action.run(); }
        };
        PreviewAssetLoader loader = new PreviewAssetLoader(client, jobs::add, DIAGNOSTICS);
        var old = new ProviderCapePoster(1, 1, new byte[]{1, 2, 3, (byte) 255},
                "1".repeat(64), CapePreparationQueue.tryReserveCpu(4));
        var current = new ProviderCapePoster(1, 1, new byte[]{4, 5, 6, (byte) 255},
                "2".repeat(64), CapePreparationQueue.tryReserveCpu(4));
        try {
            var stale = loader.requestProviderPoster("skinmc", () -> Optional.of(old));
            cache.requestCapeWithPoster("minecraftcapes-row", "minecraftcapes",
                    CompletableFuture::new,
                    () -> loader.requestProviderPoster("minecraftcapes", () -> Optional.of(current)),
                    false, true, () -> {});
            loader.invalidateProviderPoster("skinmc");
            var replacement = loader.requestProviderPoster("skinmc", Optional::empty);
            while (!jobs.isEmpty()) jobs.remove().run();
            assertTrue(stale.join().isEmpty());
            assertTrue(replacement.join().isEmpty());
            assertTrue(cache.handle("minecraftcapes-row").isPresent(),
                    "another provider invalidation must not discard a current row poster");
            assertTrue(cache.capeGlint("minecraftcapes-row"));
            assertEquals(1, registry.registrations);
            assertTrue(loader.requestProviderPoster("minecraftcapes", Optional::empty).join().isPresent());
        } finally {
            cache.close();
            loader.close();
            old.close();
            current.close();
        }
    }

    @Test
    void emptyPreparedOpenCompletesBeforeLatePosterWithoutLosingCurrentRow() {
        FakeRegistry registry = new FakeRegistry();
        try (PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS);
             ProviderCapePoster poster = new ProviderCapePoster(1, 1,
                     new byte[]{7, 8, 9, (byte) 128}, "3".repeat(64),
                     CapePreparationQueue.tryReserveCpu(4))) {
            CompletableFuture<Optional<CapePreviewSource>> opening = new CompletableFuture<>();
            CompletableFuture<Optional<ProviderCapePoster>> loading = new CompletableFuture<>();
            cache.requestCapeWithPoster("sneaky-row", "current-skin", () -> opening,
                    () -> loading, false, false, () -> {});
            opening.complete(Optional.empty());
            assertTrue(cache.handle("sneaky-row").isEmpty());
            loading.complete(Optional.of(poster));
            assertTrue(cache.handle("sneaky-row").isPresent());
            cache.requestCapeWithPoster("sneaky-row", "current-skin", () -> {
                throw new AssertionError("same row must retain resolved readiness");
            }, () -> {
                throw new AssertionError("same row must retain its poster");
            }, false, false, () -> {});
            assertEquals(1, registry.registrations);
            assertEquals(128, registry.registeredAlpha.get(
                    cache.handle("sneaky-row").orElseThrow().location()));
        }
    }

    @Test
    void latePosterCannotInstallAfterSkinReplacementRowRemovalOrClose() {
        for (String transition : List.of("replacement", "removal", "close")) {
            FakeRegistry registry = new FakeRegistry();
            PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                    TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS);
            try (ProviderCapePoster poster = new ProviderCapePoster(1, 1,
                         new byte[]{1, 2, 3, (byte) 255}, "4".repeat(64),
                         CapePreparationQueue.tryReserveCpu(4))) {
                CompletableFuture<Optional<CapePreviewSource>> opening = new CompletableFuture<>();
                CompletableFuture<Optional<ProviderCapePoster>> loading = new CompletableFuture<>();
                cache.requestCapeWithPoster("row", "old-skin", () -> opening,
                        () -> loading, false, false, () -> {});
                opening.complete(Optional.empty());
                switch (transition) {
                    case "replacement" -> cache.requestCapeWithPoster("row", "new-skin",
                            CompletableFuture::new, CompletableFuture::new, false, false, () -> {});
                    case "removal" -> cache.retain(Set.of());
                    case "close" -> cache.close();
                    default -> throw new AssertionError(transition);
                }
                loading.complete(Optional.of(poster));
                assertTrue(cache.handle("row").isEmpty(), transition);
                assertEquals(0, registry.registrations, transition);
            } finally {
                cache.close();
            }
        }
    }

    @Test
    void savedPosterInstallsBeforeDeferredPreparedOpenAndReopensWithoutSourceRead() {
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS);
        ProviderCapePoster poster = new ProviderCapePoster(1, 1,
                new byte[]{7, 8, 9, (byte) 255}, "0".repeat(64),
                CapePreparationQueue.tryReserveCpu(4));
        AtomicInteger loads = new AtomicInteger();
        CompletableFuture<Optional<CapePreviewSource>> opening = new CompletableFuture<>();
        var source = (java.util.function.Supplier<CompletableFuture<Optional<ProviderCapePoster>>>) () -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of(poster));
        };
        try {
            cache.requestCapeWithPoster("row", "asset", () -> opening, source,
                    false, true, () -> {});
            assertTrue(cache.handle("row").isPresent(), "saved pixels must be visible before prepared work");
            assertTrue(cache.capeGlint("row"));
            cache.retain(Set.of());
            cache.requestCapeWithPoster("row", "asset", CompletableFuture::new, source,
                    false, true, () -> {});
            assertTrue(cache.handle("row").isPresent());
            assertEquals(2, loads.get());
            assertEquals(255, registry.registeredAlpha.get(cache.handle("row").orElseThrow().location()));
        } finally {
            cache.close();
            poster.close();
        }
    }

    @Test
    void fifteenDistinctMaximumPostersFitAlongsidePlayerAndDecoderReservations() {
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS);
        var posters = new java.util.ArrayList<ProviderCapePoster>();
        byte[] pixels = new byte[512 * 256 * 4];
        var decoder = CapePreparationQueue.tryReserveCpu(32L * 1024 * 1024);
        var player = CapePreparationQueue.tryReserveCpu(16L * 1024 * 1024);
        assertTrue(decoder != null && player != null);
        try {
            for (int i = 0; i < 15; i++) {
                var cached = CapePreparationQueue.tryReserveCpu(pixels.length);
                assertTrue(cached != null, "each offscreen poster is quota-held");
                byte[] distinct = pixels.clone();
                distinct[0] = (byte) i;
                ProviderCapePoster poster = new ProviderCapePoster(512, 256, distinct,
                        "%064x".formatted(i), cached);
                posters.add(poster);
                String key = "row" + i;
                cache.requestCapeWithPoster(key, key, CompletableFuture::new,
                        () -> CompletableFuture.completedFuture(Optional.of(poster)),
                        false, i % 2 == 0, () -> {});
                assertTrue(cache.handle(key).isPresent());
            }
            assertEquals(15, registry.registrations);
            cache.retain(Set.of());
            assertEquals(15, registry.released.size(), "offscreen native buffers must be released");
        } finally {
            cache.close();
            posters.forEach(ProviderCapePoster::close);
            player.close();
            decoder.close();
        }
        try (var all = CapePreparationQueue.tryReserveCpu(64L * 1024 * 1024)) {
            assertTrue(all != null, "posters and native buffers must release all reservations");
        }
    }

    @Test
    void failedPreparedIntegrityRetiresCachedPosterInsteadOfShowingStalePixels(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS);
        ProviderCapePoster poster = new ProviderCapePoster(1, 1,
                new byte[]{1, 2, 3, (byte) 255}, "0".repeat(64),
                CapePreparationQueue.tryReserveCpu(4));
        CompletableFuture<Optional<CapePreviewSource>> opening = new CompletableFuture<>();
        AtomicInteger invalidations = new AtomicInteger();
        try {
            cache.requestCapeWithPoster("row", "asset", () -> opening,
                    () -> CompletableFuture.completedFuture(Optional.of(poster)),
                    false, true, invalidations::incrementAndGet);
            assertTrue(cache.handle("row").isPresent());
            BufferedImage strip = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Path source = directory.resolve("corrupt.png");
            assertTrue(ImageIO.write(strip, "png", source.toFile()));
            PreparedCapeAsset asset = new CapeAssetDecoder().prepare(source, directory.resolve("corrupt.frames"));
            try (var file = new java.io.RandomAccessFile(asset.backing().toFile(), "rw")) {
                file.seek(asset.frameBytes() + 4);
                file.write(1);
            }
            IOException corruption = assertThrows(com.naocraftlab.skins.core.png.CapeIntegrityException.class, asset::openBackingLease);
            opening.completeExceptionally(corruption);
            assertTrue(cache.handle("row").isEmpty());
            assertEquals(1, invalidations.get());
        } finally {
            cache.close();
            poster.close();
        }
    }

    @Test
    void deferredProviderThumbnailNeverLoadsFullStaticPosterAndCanReopenAfterTabSwitch() {
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS);
        CompletableFuture<Optional<CapePreviewSource>> opening = new CompletableFuture<>();
        AtomicInteger staticLoads = new AtomicInteger();
        try {
            cache.requestCape("provider-row", "saved-cape", () -> opening,
                    () -> { staticLoads.incrementAndGet(); return CompletableFuture.completedFuture(
                            Optional.of(new byte[] {1, 2, 3})); },
                    false, true, true, () -> {});
            assertTrue(cache.handle("provider-row").isEmpty());
            assertEquals(0, staticLoads.get());
            opening.complete(Optional.of(frameSource("a".repeat(64), new AtomicInteger())));
            assertTrue(cache.handle("provider-row").isPresent());
            assertTrue(cache.capeGlint("provider-row"));
            cache.retain(Set.of());
            assertTrue(cache.handle("provider-row").isEmpty());
            CompletableFuture<Optional<CapePreviewSource>> reopening = new CompletableFuture<>();
            cache.requestCape("provider-row", "saved-cape", () -> reopening,
                    () -> { staticLoads.incrementAndGet(); return CompletableFuture.completedFuture(
                            Optional.of(new byte[] {4, 5, 6})); },
                    false, true, true, () -> {});
            assertTrue(cache.handle("provider-row").isEmpty());
            assertEquals(0, staticLoads.get());
            reopening.complete(Optional.of(frameSource("b".repeat(64), new AtomicInteger())));
            assertTrue(cache.handle("provider-row").isPresent());
            assertEquals(0, staticLoads.get());
        } finally {
            cache.close();
        }
    }

    @Test
    void playerCapeReplacementKeepsOldHandleUntilNewFrameIsRegistered() {
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS);
        AtomicInteger oldCloses = new AtomicInteger();
        AtomicInteger newCloses = new AtomicInteger();
        CapePreviewSource old = frameSource("a".repeat(64), oldCloses);
        CompletableFuture<Optional<CapePreviewSource>> opening = new CompletableFuture<>();
        CompletableFuture<Optional<CapePreviewSource.Frame>> nextFrame = new CompletableFuture<>();
        CapePreviewSource replacement = new CapePreviewSource() {
            @Override public CompletableFuture<Optional<Frame>> nextFrame(int installedIndex) {
                return nextFrame;
            }
            @Override public void installed(int index) {}
            @Override public void close() { newCloses.incrementAndGet(); }
        };
        try {
            cache.requestCape("editor.preview", "account:old",
                    () -> CompletableFuture.completedFuture(Optional.of(old)),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
            String first = cache.handle("editor.preview").orElseThrow().location();
            cache.requestCape("editor.preview", "account:new", () -> opening,
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
            assertEquals(first, cache.handle("editor.preview").orElseThrow().location());
            assertTrue(registry.released.isEmpty());
            opening.complete(Optional.of(replacement));
            assertEquals(first, cache.handle("editor.preview").orElseThrow().location());
            assertTrue(registry.released.isEmpty());
            nextFrame.complete(Optional.of(new CapePreviewSource.Frame(0, "b".repeat(64), 1, 1,
                    new byte[]{1, 2, 3, (byte) 255}, CapePreparationQueue.tryReserveCpu(8))));
            String second = cache.handle("editor.preview").orElseThrow().location();
            assertFalse(first.equals(second));
            assertEquals(List.of(first), registry.released);
            cache.retain(Set.of());
            assertEquals(List.of(first, second), registry.released);
            assertEquals(1, oldCloses.get());
            assertEquals(1, newCloses.get());
        } finally {
            cache.close();
        }
    }

    @Test
    void replacementKeepsInstalledFrameAndGlintTogetherUntilSuccessOrRetirement() {
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS);
        CompletableFuture<Optional<CapePreviewSource>> pending = new CompletableFuture<>();
        try {
            cache.requestCape("player", "cape-a", () -> CompletableFuture.completedFuture(
                    Optional.of(frameSource("a".repeat(64), new AtomicInteger()))),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")),
                    true, true, true, () -> {});
            String first = cache.handle("player").orElseThrow().location();
            assertTrue(cache.capeGlint("player"));
            assertTrue(cache.capeHasElytra("player"));

            cache.requestCape("player", "cape-b", () -> pending,
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")),
                    true, false, false, () -> {});
            assertEquals(first, cache.handle("player").orElseThrow().location());
            assertTrue(cache.capeGlint("player"));
            assertTrue(cache.capeHasElytra("player"));
            pending.complete(Optional.of(frameSource("b".repeat(64), new AtomicInteger())));
            String second = cache.handle("player").orElseThrow().location();
            assertFalse(first.equals(second));
            assertFalse(cache.capeGlint("player"));
            assertFalse(cache.capeHasElytra("player"));

            cache.requestCape("player", "cape-c", () -> CompletableFuture.failedFuture(
                    new IOException("unavailable")),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")),
                    true, true, true, () -> {});
            assertEquals(second, cache.handle("player").orElseThrow().location());
            assertFalse(cache.capeGlint("player"));
            assertFalse(cache.capeHasElytra("player"));
            cache.requestCape("player", "cape-d", () -> CompletableFuture.completedFuture(
                    Optional.of(frameSource("d".repeat(64), new AtomicInteger()))),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")),
                    true, true, true, () -> {});
            assertFalse(second.equals(cache.handle("player").orElseThrow().location()));
            assertTrue(cache.capeGlint("player"));
            assertTrue(cache.capeHasElytra("player"));
            cache.retain(Set.of());
            assertTrue(cache.handle("player").isEmpty());
            assertFalse(cache.capeGlint("player"));
        } finally {
            cache.close();
        }
    }

    @Test
    void successfulRawUploadDropsTheTransientPixelArrayButRetainsTheNativeLease() {
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS);
        AtomicLong retained = new AtomicLong(-1L);
        AtomicInteger closed = new AtomicInteger();
        TextureRegistry.CpuLease lease = new TextureRegistry.CpuLease() {
            @Override public void sourcePixelsReleased(long nativeBytes) { retained.set(nativeBytes); }
            @Override public void close() { closed.incrementAndGet(); }
        };
        CapePreviewSource.Frame frame = new CapePreviewSource.Frame(0, "f".repeat(64), 1, 1,
                new byte[]{1, 2, 3, (byte) 255}, lease);
        CapePreviewSource source = new CapePreviewSource() {
            @Override public CompletableFuture<Optional<Frame>> nextFrame(int installedIndex) {
                return CompletableFuture.completedFuture(Optional.of(frame));
            }
            @Override public void installed(int index) {}
            @Override public void close() {}
        };
        cache.requestCape("player", () -> CompletableFuture.completedFuture(Optional.of(source)),
                () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
        assertEquals(4L, retained.get());
        assertEquals(null, frame.rgba8());
        assertEquals(0, closed.get());
        cache.close();
        assertEquals(1, closed.get());
    }

    @Test
    void transientPreparedFrameRetainsVerifiedPeersAndRecoversAtBoundedDelay() {
        FakeRegistry registry = new FakeRegistry();
        AtomicLong now = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        CapePreviewSource source = new CapePreviewSource() {
            @Override public CompletableFuture<Optional<Frame>> nextFrame(int index) {
                int call = calls.incrementAndGet();
                if (call == 2) return CompletableFuture.failedFuture(new IOException("transient"));
                return CompletableFuture.completedFuture(Optional.of(new Frame(call - 1,
                        Integer.toString(call).repeat(64), 1, 1,
                        new byte[]{1, 2, 3, (byte) 255}, CapePreparationQueue.tryReserveCpu(8))));
            }
            @Override public void installed(int index) {}
            @Override public void close() { closes.incrementAndGet(); }
        };
        try (PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS, now::get)) {
            cache.requestCape("peer", "shared", () -> CompletableFuture.completedFuture(Optional.of(frameSource("a".repeat(64), closes))),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
            cache.requestCape("cape", "shared", () -> CompletableFuture.completedFuture(Optional.of(source)),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
            String trusted = cache.handle("cape").orElseThrow().location();
            cache.requestCape("cape", "shared", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
            assertEquals(trusted, cache.handle("cape").orElseThrow().location());
            assertTrue(cache.handle("peer").isPresent());
            assertEquals(0, closes.get());
            now.set(TimeUnit.MILLISECONDS.toNanos(249));
            cache.requestCape("cape", "shared", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
            assertEquals(2, calls.get());
            now.set(TimeUnit.MILLISECONDS.toNanos(250));
            cache.requestCape("cape", "shared", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
            assertEquals(3, calls.get());
            assertFalse(trusted.equals(cache.handle("cape").orElseThrow().location()));
        }
    }

    @Test
    void preparedRetriesExhaustWithoutRepaintOrHoverResetAndNewAssetCanRecover() {
        FakeRegistry registry = new FakeRegistry();
        AtomicLong now = new AtomicLong();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        CapePreviewSource source = new CapePreviewSource() {
            public CompletableFuture<Optional<Frame>> nextFrame(int index) {
                reads.incrementAndGet();
                if (index < 0) return frameSource("a".repeat(64), closes).nextFrame(index);
                return CompletableFuture.failedFuture(new java.util.concurrent.CompletionException(new IOException("transient")));
            }
            public void installed(int index) {}
            public void close() { closes.incrementAndGet(); }
        };
        try (PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS, now::get)) {
            cache.requestCape("cape", "asset", () -> CompletableFuture.completedFuture(Optional.of(source)),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, true, () -> {});
            String trusted = cache.handle("cape").orElseThrow().location();
            for (long ms : new long[]{0, 249, 250, 1249, 1250, 6249, 6250, 126250}) {
                now.set(TimeUnit.MILLISECONDS.toNanos(ms));
                for (int i = 0; i < 20; i++) {
                    cache.requestCape("cape", "asset", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                            () -> CompletableFuture.failedFuture(new AssertionError("fallback")), i % 2 == 0, true, () -> {});
                }
            }
            assertEquals(5, reads.get());
            assertEquals(trusted, cache.handle("cape").orElseThrow().location());
            assertTrue(cache.capeGlint("cape"));
            assertEquals(0, closes.get());
            cache.requestCape("cape", "new-asset", () -> CompletableFuture.completedFuture(Optional.of(frameSource("b".repeat(64), closes))),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, false, () -> {});
            assertFalse(trusted.equals(cache.handle("cape").orElseThrow().location()));
            assertFalse(cache.capeGlint("cape"));
            assertEquals(1, closes.get());
        }
        try (var released = CapePreparationQueue.tryReserveCpu(64L * 1024 * 1024)) { assertTrue(released != null); }
    }

    @Test
    void preparedOpenRetriesExhaustWithoutHoverResetAndKeepTrustedPoster() {
        FakeRegistry registry = new FakeRegistry();
        AtomicLong now = new AtomicLong();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger invalidations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        java.util.function.Supplier<CompletableFuture<Optional<CapePreviewSource>>> opener = () -> {
            opens.incrementAndGet();
            return CompletableFuture.failedFuture(new java.util.concurrent.CompletionException(
                    new IOException("transient open")));
        };
        try (PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS, now::get);
             ProviderCapePoster poster = new ProviderCapePoster(1, 1,
                     new byte[]{7, 8, 9, (byte) 128}, "3".repeat(64),
                     CapePreparationQueue.tryReserveCpu(4))) {
            cache.requestCapeWithPoster("cape", "asset", opener,
                    () -> CompletableFuture.completedFuture(Optional.of(poster)),
                    true, true, invalidations::incrementAndGet);
            String trusted = cache.handle("cape").orElseThrow().location();
            for (long ms : new long[]{0, 249, 250, 1249, 1250, 6249, 6250, 126250}) {
                now.set(TimeUnit.MILLISECONDS.toNanos(ms));
                for (int i = 0; i < 20; i++) {
                    cache.requestCapeWithPoster("cape", "asset", opener,
                            () -> CompletableFuture.failedFuture(new AssertionError("reload poster")),
                            i % 2 == 0, true, invalidations::incrementAndGet);
                }
            }
            assertEquals(4, opens.get(), "one failed open plus three bounded retries");
            assertEquals(trusted, cache.handle("cape").orElseThrow().location());
            assertTrue(cache.capeGlint("cape"));
            assertEquals(0, invalidations.get());
            cache.requestCape("cape", "new-asset", () -> CompletableFuture.completedFuture(
                            Optional.of(frameSource("b".repeat(64), closes))),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")),
                    true, false, () -> {});
            assertFalse(trusted.equals(cache.handle("cape").orElseThrow().location()));
            assertFalse(cache.capeGlint("cape"));
        }
    }

    @Test
    void synchronousWrappedIntegrityFailureInvalidatesSameAssetPeers() {
        for (boolean duringOpen : List.of(true, false)) {
            AtomicInteger closes = new AtomicInteger();
            var corruption = new java.io.UncheckedIOException(
                    new com.naocraftlab.skins.core.png.CapeIntegrityException("frame mismatch"));
            CapePreviewSource source = new CapePreviewSource() {
                public CompletableFuture<Optional<Frame>> nextFrame(int index) {
                    if (index >= 0) throw corruption;
                    return frameSource("b".repeat(64), closes).nextFrame(index);
                }
                public void installed(int index) {}
                public void close() { closes.incrementAndGet(); }
            };
            try (PreviewAssetCache<String> cache = new PreviewAssetCache<>(new FakeRegistry(),
                    TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS)) {
                cache.requestCape("peer", "asset", () -> CompletableFuture.completedFuture(
                                Optional.of(frameSource("a".repeat(64), closes))),
                        () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
                cache.requestCape("cape", "asset", () -> {
                    if (duringOpen) throw corruption;
                    return CompletableFuture.completedFuture(Optional.of(source));
                }, () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
                if (!duringOpen) cache.requestCape("cape", "asset",
                        () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                        () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
                assertTrue(cache.handle("peer").isEmpty(), "confirmed integrity must invalidate peers");
                assertTrue(cache.handle("cape").isEmpty());
                assertEquals(duringOpen ? 1 : 2, closes.get());
            }
        }
    }

    @Test
    void hoverKeepsOnlyOneQueuedPreparedReadAndDiscardsFrameAfterPause(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        BufferedImage strip = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        strip.setRGB(1, 1, 0xff112233);
        strip.setRGB(1, 33, 0x80445566);
        Path source = directory.resolve("hover.png");
        assertTrue(ImageIO.write(strip, "png", source.toFile()));
        var asset = new CapeAssetDecoder().prepare(source, directory.resolve("hover.frames"));
        AtomicLong now = new AtomicLong();
        java.util.ArrayDeque<Runnable> jobs = new java.util.ArrayDeque<>();
        ClientExecutor client = new ClientExecutor() {
            public boolean isClientThread() { return true; }
            public void execute(Runnable action) { action.run(); }
        };
        PreparedCapePreview preview = PreparedCapePreview.open(new CatalogRead.PreparedCapeLease() {
            public PreparedCapeAsset asset() { return asset; }
            public void close() {}
        }, jobs::add, client, now::get, true);
        FakeRegistry registry = new FakeRegistry();
        try (PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS, now::get)) {
            cache.requestCape("cape", () -> CompletableFuture.completedFuture(Optional.of(preview)),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), () -> {});
            jobs.remove().run();
            String first = cache.handle("cape").orElseThrow().location();
            now.set(TimeUnit.MILLISECONDS.toNanos(100));
            cache.requestCape("cape", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
            for (int i = 0; i < 20; i++) {
                now.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
                cache.requestCape("cape", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                        () -> CompletableFuture.failedFuture(new AssertionError("fallback")),
                        i % 2 != 0, () -> {});
                if (i % 2 != 0) {
                    now.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
                    cache.requestCape("cape", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                            () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
                }
            }
            cache.requestCape("cape", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), false, () -> {});
            assertEquals(1, jobs.size(), "hover must retain the actual pending read");
            jobs.remove().run();
            assertEquals(first, cache.handle("cape").orElseThrow().location());
            assertEquals(1, registry.registrations, "a frame requested before pause must not replace its poster");
            cache.requestCape("cape", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
            cache.requestCape("cape", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
            assertEquals(1, jobs.size());
            jobs.remove().run();
            assertFalse(first.equals(cache.handle("cape").orElseThrow().location()));
        }
        try (var released = CapePreparationQueue.tryReserveCpu(64L * 1024 * 1024)) { assertTrue(released != null); }
    }

    @Test
    void closedPreparedBackingReopensOnlyOnWorkerAndKeepsTrustedFrameUntilRecovery(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        BufferedImage strip = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        strip.setRGB(1, 1, 0xff112233);
        strip.setRGB(1, 33, 0x80445566);
        Path source = directory.resolve("reopen.png");
        assertTrue(ImageIO.write(strip, "png", source.toFile()));
        var asset = new CapeAssetDecoder().prepare(source, directory.resolve("reopen.frames"));
        var fault = new com.naocraftlab.skins.core.png.FrameReadFixture(asset);
        AtomicLong now = new AtomicLong();
        java.util.ArrayDeque<Runnable> jobs = new java.util.ArrayDeque<>();
        ClientExecutor client = new ClientExecutor() {
            public boolean isClientThread() { return true; }
            public void execute(Runnable action) { action.run(); }
        };
        PreparedCapePreview preview = PreparedCapePreview.open(new CatalogRead.PreparedCapeLease() {
            public PreparedCapeAsset asset() { return fault.asset; }
            public void close() {}
        }, jobs::add, client, now::get);
        try (PreviewAssetCache<String> cache = new PreviewAssetCache<>(new FakeRegistry(),
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS, now::get)) {
            cache.requestCape("cape", () -> CompletableFuture.completedFuture(Optional.of(preview)),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), () -> {});
            jobs.remove().run();
            String trusted = cache.handle("cape").orElseThrow().location();
            fault.closeNextRead.set(true);
            now.set(TimeUnit.MILLISECONDS.toNanos(100));
            cache.requestCape("cape", () -> CompletableFuture.failedFuture(new AssertionError("reopen source")),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), () -> {});
            jobs.remove().run();
            assertEquals(trusted, cache.handle("cape").orElseThrow().location());
            now.set(TimeUnit.MILLISECONDS.toNanos(350));
            cache.requestCape("cape", () -> CompletableFuture.failedFuture(new AssertionError("reopen source")),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), () -> {});
            assertEquals(1, fault.opens.get());
            jobs.remove().run();
            assertEquals(2, fault.opens.get());
            assertFalse(trusted.equals(cache.handle("cape").orElseThrow().location()));
        }
    }

    private static CapePreviewSource frameSource(String hash, AtomicInteger closes) {
        return new CapePreviewSource() {
            @Override public CompletableFuture<Optional<Frame>> nextFrame(int installedIndex) {
                return installedIndex >= 0 ? CompletableFuture.completedFuture(Optional.empty())
                        : CompletableFuture.completedFuture(Optional.of(new Frame(0, hash, 1, 1,
                                new byte[]{1, 2, 3, (byte) 255}, CapePreparationQueue.tryReserveCpu(8))));
            }
            @Override public void installed(int index) {}
            @Override public void close() { closes.incrementAndGet(); }
        };
    }

    @Test
    void cardHoverRestartsWithoutAnimatingPlayerOrStaticTab(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        BufferedImage strip = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            strip.setRGB(x, y, y < 32 ? 0x80ff0000 : 0x400000ff);
        }
        Path encoded = directory.resolve("animated.png");
        assertTrue(ImageIO.write(strip, "png", encoded.toFile()));
        PreparedCapeAsset asset = new CapeAssetDecoder().prepare(encoded, directory.resolve("animated.frames"));
        AtomicLong now = new AtomicLong();
        ClientExecutor client = new ClientExecutor() {
            @Override public boolean isClientThread() { return true; }
            @Override public void execute(Runnable action) { action.run(); }
        };
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS, now::get);
        try {
            for (String key : List.of("card", "player", "tab")) {
                PreparedCapePreview source = PreparedCapePreview.open(new CatalogRead.PreparedCapeLease() {
                    @Override public PreparedCapeAsset asset() { return asset; }
                    @Override public void close() {}
                }, Runnable::run, client, now::get, !key.equals("player"));
                cache.requestCape(key, () -> CompletableFuture.completedFuture(Optional.of(source)),
                        () -> CompletableFuture.failedFuture(new AssertionError("no static fallback")),
                        key.equals("player"), () -> {});
            }
            String first = registry.registeredHashes.get(cache.handle("card").orElseThrow().location());
            assertEquals(first, registry.registeredHashes.get(cache.handle("tab").orElseThrow().location()));
            assertEquals(0x80, registry.registeredAlpha.get(cache.handle("card").orElseThrow().location()));
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
            cache.requestCape("player", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
            String second = registry.registeredHashes.get(cache.handle("player").orElseThrow().location());
            assertFalse(first.equals(second));
            assertEquals(first, registry.registeredHashes.get(cache.handle("card").orElseThrow().location()));
            assertEquals(first, registry.registeredHashes.get(cache.handle("tab").orElseThrow().location()));
            cache.requestCape("card", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
            assertEquals(first, registry.registeredHashes.get(cache.handle("card").orElseThrow().location()));
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
            cache.requestCape("card", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
            assertEquals(second, registry.registeredHashes.get(cache.handle("card").orElseThrow().location()));
            cache.requestCape("card", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), false, () -> {});
            assertEquals(first, registry.registeredHashes.get(cache.handle("card").orElseThrow().location()));
            cache.requestCape("card", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback")), true, () -> {});
            assertEquals(first, registry.registeredHashes.get(cache.handle("card").orElseThrow().location()));
            assertEquals(first, registry.registeredHashes.get(cache.handle("tab").orElseThrow().location()));
        } finally {
            cache.close();
        }
    }

    @Test
    @SuppressWarnings("try")
    void fifteenDistinctMaximumHdCardsFitBesideDecoderAndPlayer(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        ClientExecutor client = new ClientExecutor() {
            @Override public boolean isClientThread() { return true; }
            @Override public void execute(Runnable action) { action.run(); }
        };
        FakeRegistry registry = new FakeRegistry();
        java.util.ArrayDeque<Runnable> jobs = new java.util.ArrayDeque<>();
        AtomicLong now = new AtomicLong();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS, now::get);
        var decoder = CapePreparationQueue.tryReserveCpu(32L * 1024 * 1024);
        var player = CapePreparationQueue.tryReserveCpu(16L * 1024 * 1024);
        CapePreparationQueue.Reservation pressure = null;
        try {
            assertTrue(decoder != null && player != null);
            for (int index = 0; index < 15; index++) {
                BufferedImage image = new BufferedImage(2048, 2048, BufferedImage.TYPE_INT_ARGB);
                image.setRGB(index * 4, 0, 0x7f000000 | index + 1);
                image.setRGB(index * 4, 1024, 0x7f110000 | index + 1);
                Path encoded = directory.resolve("card-" + index + ".png");
                assertTrue(ImageIO.write(image, "png", encoded.toFile()));
                PreparedCapeAsset asset = new CapeAssetDecoder().prepare(
                        encoded, directory.resolve("card-" + index + ".frames"));
                assertEquals(2, asset.frameCount());
                PreparedCapePreview source = PreparedCapePreview.open(new CatalogRead.PreparedCapeLease() {
                    @Override public PreparedCapeAsset asset() { return asset; }
                    @Override public void close() {}
                }, jobs::add, client, now::get, true);
                String key = "card-" + index;
                cache.requestCape(key, () -> CompletableFuture.completedFuture(Optional.of(source)),
                        () -> CompletableFuture.failedFuture(new AssertionError("no fallback")), false, () -> {});
                jobs.remove().run();
                assertEquals(512, cache.handle(key).orElseThrow().width());
                assertEquals(256, cache.handle(key).orElseThrow().height());
            }
            assertEquals(15, Set.copyOf(registry.registeredHashes.values()).size(),
                    "each source must yield a distinct sampled thumbnail");
            assertEquals(15, registry.rawLeases.size());
            pressure = CapePreparationQueue.tryReserveCpu(8L * 1024 * 1024);
            assertTrue(pressure != null, "thumbnail-native buffers leave room beside decoder and player");
            var unavailable = (java.util.function.Supplier<CompletableFuture<Optional<CapePreviewSource>>>)
                    () -> CompletableFuture.failedFuture(new AssertionError("source reopened"));
            var fallback = (java.util.function.Supplier<CompletableFuture<Optional<byte[]>>>)
                    () -> CompletableFuture.failedFuture(new AssertionError("static fallback"));
            String first = cache.handle("card-0").orElseThrow().location();
            cache.requestCape("card-0", unavailable, fallback, true, () -> {});
            jobs.remove().run();
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
            cache.requestCape("card-0", unavailable, fallback, true, () -> {});
            jobs.remove().run();
            assertEquals(first, cache.handle("card-0").orElseThrow().location());
            assertEquals(15, registry.rawLeases.size());
            decoder.close();
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
            cache.requestCape("card-0", unavailable, fallback, true, () -> {});
            jobs.remove().run();
            String animated = cache.handle("card-0").orElseThrow().location();
            assertFalse(first.equals(animated));
            assertEquals(16, registry.rawLeases.size(), "first frame stays available for immediate exit");
            cache.requestCape("card-0", unavailable, fallback, false, () -> {});
            assertEquals(first, cache.handle("card-0").orElseThrow().location());
            assertTrue(jobs.isEmpty());
            assertEquals(15, registry.rawLeases.size());
            pressure.close();
            pressure = null;
            try (var remaining = CapePreparationQueue.tryReserveCpu(33L * 1024 * 1024)) {
                assertTrue(remaining != null, "deferred and retired frames return their CPU reservations");
            }
        } finally {
            cache.close();
            if (pressure != null) pressure.close();
            if (decoder != null) decoder.close();
            if (player != null) player.close();
        }
        try (var all = CapePreparationQueue.tryReserveCpu(64L * 1024 * 1024)) {
            assertTrue(all != null);
        }
    }

    @Test
    void lateTailCorruptionInvalidatesEarlierVerifiedCardWithoutFallback(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        BufferedImage strip = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            strip.setRGB(x, y, y < 32 ? 0xff223344 : 0xff556677);
        }
        Path encoded = directory.resolve("two.png");
        assertTrue(ImageIO.write(strip, "png", encoded.toFile()));
        Path backing = directory.resolve("two.frames");
        PreparedCapeAsset asset = new CapeAssetDecoder().prepare(encoded, backing);
        ClientExecutor client = new ClientExecutor() {
            @Override public boolean isClientThread() { return true; }
            @Override public void execute(Runnable action) { action.run(); }
        };
        PreparedCapePreview card = PreparedCapePreview.open(new CatalogRead.PreparedCapeLease() {
            @Override public PreparedCapeAsset asset() { return asset; }
            @Override public void close() {}
        }, Runnable::run, client, System::nanoTime, true);
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS);
        AtomicInteger fallbacks = new AtomicInteger();
        try {
            cache.requestCape("card", "shared", () -> CompletableFuture.completedFuture(Optional.of(card)),
                    () -> { fallbacks.incrementAndGet(); return CompletableFuture.completedFuture(Optional.empty()); },
                    false, () -> {});
            assertTrue(cache.handle("card").isPresent());
            try (var writer = new java.io.RandomAccessFile(backing.toFile(), "rw")) {
                writer.seek(asset.frameBytes() + 4);
                writer.write(1);
            }
            IOException integrity = assertThrows(com.naocraftlab.skins.core.png.CapeIntegrityException.class, asset::openBackingLease);
            cache.requestCape("player", "shared",
                    () -> CompletableFuture.failedFuture(new java.util.concurrent.CompletionException(integrity)),
                    () -> { fallbacks.incrementAndGet(); return CompletableFuture.completedFuture(Optional.empty()); },
                    true, () -> {});
            assertTrue(cache.handle("card").isEmpty());
            assertTrue(cache.handle("player").isEmpty());
            assertEquals(0, fallbacks.get());
        } finally {
            cache.close();
        }
    }

    @Test
    void leavingHoverDiscardsQueuedAnimatedFrameAndItsReservation(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        BufferedImage strip = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            strip.setRGB(x, y, y < 32 ? 0xff112233 : 0xff445566);
        }
        Path encoded = directory.resolve("queued.png");
        assertTrue(ImageIO.write(strip, "png", encoded.toFile()));
        PreparedCapeAsset asset = new CapeAssetDecoder().prepare(encoded, directory.resolve("queued.frames"));
        java.util.ArrayDeque<Runnable> jobs = new java.util.ArrayDeque<>();
        AtomicLong now = new AtomicLong();
        ClientExecutor client = new ClientExecutor() {
            @Override public boolean isClientThread() { return true; }
            @Override public void execute(Runnable action) { action.run(); }
        };
        PreparedCapePreview source = PreparedCapePreview.open(new CatalogRead.PreparedCapeLease() {
            @Override public PreparedCapeAsset asset() { return asset; }
            @Override public void close() {}
        }, jobs::add, client, now::get, true);
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS, now::get);
        try {
            var opener = (java.util.function.Supplier<CompletableFuture<Optional<CapePreviewSource>>>)
                    () -> CompletableFuture.completedFuture(Optional.of(source));
            var fallback = (java.util.function.Supplier<CompletableFuture<Optional<byte[]>>>)
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback"));
            cache.requestCape("card", opener, fallback, false, () -> {});
            jobs.remove().run();
            String first = cache.handle("card").orElseThrow().location();
            cache.requestCape("card", opener, fallback, true, () -> {});
            jobs.remove().run();
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
            cache.requestCape("card", opener, fallback, true, () -> {});
            cache.requestCape("card", opener, fallback, false, () -> {});
            jobs.remove().run();
            assertEquals(first, cache.handle("card").orElseThrow().location());
            assertEquals(1, registry.registrations);
            try (var remaining = CapePreparationQueue.tryReserveCpu(
                    64L * 1024 * 1024 - 2L * asset.frameBytes())) {
                assertTrue(remaining != null);
            }
        } finally {
            cache.close();
        }
    }

    @Test
    void leavingHoverRestoresInstalledFirstFrameBeforeAnyWorkerRuns(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        BufferedImage strip = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            strip.setRGB(x, y, y < 32 ? 0xff112233 : 0xff445566);
        }
        Path encoded = directory.resolve("animated.png");
        assertTrue(ImageIO.write(strip, "png", encoded.toFile()));
        PreparedCapeAsset asset = new CapeAssetDecoder().prepare(encoded, directory.resolve("animated.frames"));
        java.util.ArrayDeque<Runnable> jobs = new java.util.ArrayDeque<>();
        AtomicLong now = new AtomicLong();
        ClientExecutor client = new ClientExecutor() {
            @Override public boolean isClientThread() { return true; }
            @Override public void execute(Runnable action) { action.run(); }
        };
        PreparedCapePreview source = PreparedCapePreview.open(new CatalogRead.PreparedCapeLease() {
            @Override public PreparedCapeAsset asset() { return asset; }
            @Override public void close() {}
        }, jobs::add, client, now::get, true);
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry,
                TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS, now::get);
        try {
            var opener = (java.util.function.Supplier<CompletableFuture<Optional<CapePreviewSource>>>)
                    () -> CompletableFuture.completedFuture(Optional.of(source));
            var fallback = (java.util.function.Supplier<CompletableFuture<Optional<byte[]>>>)
                    () -> CompletableFuture.failedFuture(new AssertionError("fallback"));
            cache.requestCape("card", opener, fallback, false, () -> {});
            jobs.remove().run();
            String first = cache.handle("card").orElseThrow().location();
            cache.requestCape("card", opener, fallback, true, () -> {});
            jobs.remove().run();
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
            cache.requestCape("card", opener, fallback, true, () -> {});
            jobs.remove().run();
            String animated = cache.handle("card").orElseThrow().location();
            assertFalse(first.equals(animated));

            cache.requestCape("card", opener, fallback, false, () -> {});
            assertEquals(first, cache.handle("card").orElseThrow().location());
            assertTrue(jobs.isEmpty(), "mouse exit must not queue a frame-zero read");
            assertEquals(List.of(animated), registry.released);
            cache.requestCape("card", opener, fallback, true, () -> {});
            assertEquals(first, cache.handle("card").orElseThrow().location());
            cache.retain(Set.of());
            assertEquals(List.of(animated, first), registry.released);
        } finally {
            cache.close();
        }
        try (var all = CapePreparationQueue.tryReserveCpu(64L * 1024 * 1024)) {
            assertTrue(all != null);
        }
    }

    @Test
    void preparedCapeAdvancesFramesAndReleasesPreviousHandleAndBackingLease(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        BufferedImage strip = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            strip.setRGB(x, y, y < 32 ? 0xffff0000 : 0xff0000ff);
        }
        Path encoded = directory.resolve("two-frames.png");
        assertTrue(ImageIO.write(strip, "png", encoded.toFile()));
        PreparedCapeAsset asset = new CapeAssetDecoder().prepare(encoded, directory.resolve("two.frames"));
        AtomicInteger ownerCloses = new AtomicInteger();
        CatalogRead.PreparedCapeLease owner = new CatalogRead.PreparedCapeLease() {
            @Override public PreparedCapeAsset asset() { return asset; }
            @Override public void close() { ownerCloses.incrementAndGet(); }
        };
        AtomicLong now = new AtomicLong();
        ClientExecutor client = new ClientExecutor() {
            @Override public boolean isClientThread() { return true; }
            @Override public void execute(Runnable action) { action.run(); }
        };
        PreparedCapePreview preview = PreparedCapePreview.open(owner, Runnable::run, client, now::get);
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(
                registry, TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS, now::get);
        cache.requestCape("cape", () -> CompletableFuture.completedFuture(Optional.of(preview)),
                () -> CompletableFuture.failedFuture(new AssertionError("static fallback")), () -> {});
        assertEquals("texture/1", cache.handle("cape").orElseThrow().location());
        assertEquals(0, ownerCloses.get());

        try (CapePreparationQueue.Reservation occupied = CapePreparationQueue.tryReserveCpu(
                64L * 1024 * 1024 - 2L * asset.frameBytes())) {
            assertTrue(occupied != null);
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
            cache.requestCape("cape", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                    () -> CompletableFuture.failedFuture(new AssertionError("static fallback")), () -> {});
            assertEquals("texture/1", cache.handle("cape").orElseThrow().location(),
                    "memory pressure must retain the last valid preview frame");
        }
        cache.requestCape("cape", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                () -> CompletableFuture.failedFuture(new AssertionError("static fallback")), () -> {});
        assertEquals("texture/1", cache.handle("cape").orElseThrow().location(),
                "saturated frame demand must back off instead of retrying every render");
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
        cache.requestCape("cape", () -> CompletableFuture.failedFuture(new AssertionError("reopen")),
                () -> CompletableFuture.failedFuture(new AssertionError("static fallback")), () -> {});
        assertEquals("texture/2", cache.handle("cape").orElseThrow().location());
        assertEquals(List.of("texture/1"), registry.released);

        cache.retain(Set.of());
        assertEquals(1, ownerCloses.get());
        assertEquals(List.of("texture/1", "texture/2"), registry.released);
        try (CapePreparationQueue.Reservation allCpu = CapePreparationQueue.tryReserveCpu(64L * 1024 * 1024)) {
            assertTrue(allCpu != null, "native raw pixel leases must return on final unload");
        }
        Files.delete(encoded);
    }

    @Test
    @SuppressWarnings("try")
    void fiveSmallPreparedCapesDoNotExhaustTheSharedCpuBudget(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        ClientExecutor client = new ClientExecutor() {
            @Override public boolean isClientThread() { return true; }
            @Override public void execute(Runnable action) { action.run(); }
        };
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(
                registry, TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS);
        try {
            for (int index = 0; index < 5; index++) {
                BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0, 0, 0xff000000 | index + 1);
                Path encoded = directory.resolve("cape-" + index + ".png");
                assertTrue(ImageIO.write(image, "png", encoded.toFile()));
                PreparedCapeAsset asset = new CapeAssetDecoder().prepare(
                        encoded, directory.resolve("cape-" + index + ".frames"));
                CatalogRead.PreparedCapeLease owner = new CatalogRead.PreparedCapeLease() {
                    @Override public PreparedCapeAsset asset() { return asset; }
                    @Override public void close() {}
                };
                PreparedCapePreview preview = PreparedCapePreview.open(owner, Runnable::run, client);
                String key = "cape-" + index;
                cache.requestCape(key, () -> CompletableFuture.completedFuture(Optional.of(preview)),
                        () -> CompletableFuture.failedFuture(new AssertionError("static fallback")), () -> {});
                assertTrue(cache.handle(key).isPresent(), "small cape " + index + " should upload");
            }
            try (var remaining = CapePreparationQueue.tryReserveCpu(
                    64L * 1024 * 1024 - 5L * 2 * 64 * 32 * 4)) {
                assertTrue(remaining != null, "five small native images should reserve their real frame size");
            }
        } finally {
            cache.close();
        }
        try (var all = CapePreparationQueue.tryReserveCpu(64L * 1024 * 1024)) {
            assertTrue(all != null, "retiring previews must release every native pixel lease");
        }
    }
    @Test
    void managedPlayerSkinIsHiddenUntilNativeUploadIsReady() {
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache =
                new PreviewAssetCache<>(
                        registry, TextureRegistry.TextureKind.PLAYER_SKIN, DIAGNOSTICS);

        cache.request(
                "skin",
                () -> CompletableFuture.completedFuture(Optional.of(new byte[]{1})),
                () -> {
                });
        NativePlayerSkinLifecycle.Registration registration =
                NativePlayerSkinLifecycle.pending("texture/1");

        assertTrue(cache.handle("skin").isEmpty());
        registration.ready();
        assertEquals("texture/1", cache.handle("skin").orElseThrow().location());

        cache.close();
        registration.retire();
    }

    @Test
    void failedNativePlayerSkinIsReleasedAndRetriedWithBackoff() {
        FakeRegistry registry = new FakeRegistry();
        FakeMonotonicClock clock = new FakeMonotonicClock();
        PreviewAssetCache<String> cache = new PreviewAssetCache<>(
                registry, TextureRegistry.TextureKind.PLAYER_SKIN, DIAGNOSTICS, clock::now);
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        cache.request(
                "skin",
                () -> result(loads, Optional.of(new byte[]{1})),
                failures::incrementAndGet);
        NativePlayerSkinLifecycle.Registration failed =
                NativePlayerSkinLifecycle.pending("texture/1");
        failed.failed();

        assertTrue(cache.handle("skin").isEmpty());
        cache.request(
                "skin",
                () -> result(loads, Optional.of(new byte[]{2})),
                failures::incrementAndGet);
        assertEquals(List.of("texture/1"), registry.released);
        assertEquals(1, failures.get());
        assertEquals(1, loads.get());

        failed.retire();
        clock.advanceMillis(250L);
        cache.request(
                "skin",
                () -> result(loads, Optional.of(new byte[]{2})),
                failures::incrementAndGet);

        assertEquals(2, loads.get());
        assertEquals("texture/2", cache.handle("skin").orElseThrow().location());
        cache.close();
    }

    @Test
    void oneKeyStartsOneLoadAndRetainsTheRegisteredHandle() {
        FakeRegistry registry = new FakeRegistry();
        PreviewAssetCache<String> cache =
                new PreviewAssetCache<>(
                        registry, TextureRegistry.TextureKind.PLAYER_SKIN, DIAGNOSTICS);
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
                registry, TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS, clock::now);
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
                registry, TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS, clock::now);
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
                registry, TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS, clock::now);

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
                new PreviewAssetCache<>(registry, TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS);
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
                new PreviewAssetCache<>(registry, TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS);
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
                new PreviewAssetCache<>(registry, TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS);
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
                new PreviewAssetCache<>(
                        registry, TextureRegistry.TextureKind.PLAYER_SKIN, DIAGNOSTICS);
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
                registry, TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS, clock::now);
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
                new PreviewAssetCache<>(registry, TextureRegistry.TextureKind.IMAGE, DIAGNOSTICS);
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
        private final Map<String, CpuLease> rawLeases = new HashMap<>();
        private final Map<String, String> registeredHashes = new HashMap<>();
        private final Map<String, Integer> registeredAlpha = new HashMap<>();
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
        public TextureHandle registerRgba(TextureKind kind, String sha256, int width,
                int height, byte[] rgba8, CpuLease cpuLease) throws IOException {
            registrations++;
            if (failRegistration) {
                cpuLease.close();
                throw new IOException("registration failed");
            }
            String location = "texture/" + registrations;
            rawLeases.put(location, cpuLease);
            registeredHashes.put(location, sha256);
            registeredAlpha.put(location, rgba8[3] & 255);
            return new TextureHandle(location, width, height);
        }

        @Override
        public void release(TextureHandle handle) {
            released.add(handle.location());
            CpuLease raw = rawLeases.remove(handle.location());
            if (raw != null) raw.close();
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

package com.naocraftlab.skins.runtime.composition;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.config.ClientConfiguration;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.service.LibraryService;
import com.naocraftlab.skins.core.storage.*;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import com.naocraftlab.skins.runtime.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class PreviewModelFitCompositionTest {
    @TempDir Path directory;
    private static final UUID ACCOUNT = new UUID(0, 700);
    private static final UUID PRESET = new UUID(0, 701);
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final PreviewModelFitStore.Value FIRST = new PreviewModelFitStore.Value(2, 0.25, 0.95, -0.125,
            PreviewModelNormalization.full(2, 5, 1, 3, 2, 4, 1));

    @org.junit.jupiter.api.BeforeEach void realTemporaryRoot() throws Exception {
        directory = directory.toRealPath();
    }

    @Test void runtimeWriterThenFreshCompositionReadsTheExactFirstValue() throws Exception {
        byte[] png = png();
        var storage = new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
        var assets = new LibraryStorageAdapter(storage);
        var library = new LibraryService(assets, assets, Clock.systemUTC());
        var asset = library.importSkin(ACCOUNT, "Synthetic", SkinVariant.SLIM, SkinSource.IMPORTED, png).asset();
        storage.updateAccount(ACCOUNT, old -> new AccountState(old.schemaVersion(), ACCOUNT, old.skinAssets(),
                List.of(new AppearancePreset(PRESET, "Synthetic", SkinReference.asset(asset.id()), null, NOW, NOW)), NOW));
        var first = new Fixture(png);
        try {
            first.open();
            var preview = first.preview();
            assertEquals(PRESET, preview.presetId().orElseThrow());
            assertTrue(first.runtime.galleryPreviewFrameCurrent(preview));
            var future = first.runtime.preparePreviewModelFit(preview, () -> CompletableFuture.completedFuture(FIRST));
            assertFalse(future.isDone(), "Admitted numeric lookup must be queued");
            first.drain();
            assertEquals(PreviewAssets.NumericFitStatus.STORED, future.join().status());
            assertEquals(FIRST, future.join().value());
            assertEquals(0.4, storage.loadPreviewModelFit(ACCOUNT, asset.sha256(), SkinVariant.SLIM).fit().normalization());
        } finally { first.runtime.close(); }
        var second = new Fixture(png, new SkinExtensionEnvironmentSource.Snapshot(7,
                Map.of(SkinExtensionEnvironmentSource.Consumer.EARS, SkinExtensionEnvironmentSource.State.ACTIVE,
                        SkinExtensionEnvironmentSource.Consumer.CUSTOMIZABLE_PLAYER_MODELS, SkinExtensionEnvironmentSource.State.ACTIVE), true),
                ClientConfiguration.defaults().withHideIncompatibleCatalogSkins(true));
        try {
            second.open();
            var future = second.runtime.preparePreviewModelFit(second.preview(), () -> fail("Stored fit must bypass measurement"));
            second.drain();
            assertEquals(PreviewAssets.NumericFitStatus.STORED, future.join().status());
            assertEquals(FIRST, future.join().value());
        } finally { second.runtime.close(); }
        assertArrayEquals(png, library.resolveSkin(library.load(ACCOUNT), asset.id()).pngBytes());
    }

    private final class Fixture {
        final Deque<Runnable> workers = new ArrayDeque<>();
        final Deque<Runnable> clients = new ArrayDeque<>();
        boolean workerLane;
        final ClientRuntime runtime;
        Fixture(byte[] png) {
            this(png, SkinExtensionEnvironmentSource.Snapshot.unknown(0), ClientConfiguration.defaults());
        }
        Fixture(byte[] png, SkinExtensionEnvironmentSource.Snapshot environment, ClientConfiguration configuration) {
            ClientExecutor client = new ClientExecutor() {
                public boolean isClientThread() { return !workerLane; }
                public void execute(Runnable action) { if (workerLane) clients.add(action); else action.run(); }
            };
            GameSessionTokenSource tokens = new GameSessionTokenSource() {
                public SessionIdentity currentSession() { return new SessionIdentity(ACCOUNT, "Synthetic"); }
                public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) {
                    throw new AssertionError("No transport or credentials permitted");
                }
            };
            ClientCapabilitySet capabilities = new ClientCapabilitySet(tokens, (collection, skin, model) -> png,
                    () -> new CurrentPlayerAppearanceSource.PlayerAppearance(
                            new TextureRegistry.TextureHandle("synthetic-default", 64, 64), SkinModel.CLASSIC, Optional.empty()), client,
                    () -> CompletableFuture.completedFuture(Optional.empty()), SignedTextureVerifier.rejecting(),
                    resolved -> PlayerAppearanceSink.ApplyResult.UPDATED, visibility -> {}, ServerAppearanceRefreshNotifier.NO_OP, () -> environment);
            runtime = ClientCompositionRoot.createRuntime(capabilities, UiMessage::key, directory,
                    () -> configuration, DiagnosticSinks.discarding(), name -> new ClientCompositionRoot.Worker(new Worker(), false));
        }
        void open() {
            runtime.initialize(); drain();
            assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
            runtime.dispatchWidget("gallery.card." + PRESET);
            runtime.view(854, 480, 0, 0);
        }
        ViewSpec.Preview preview() {
            return runtime.galleryPreviewCandidate().orElseThrow();
        }
        void drain() {
            while (!workers.isEmpty() || !clients.isEmpty()) {
                if (!workers.isEmpty()) {
                    workerLane = true;
                    try { workers.remove().run(); } finally { workerLane = false; }
                }
                while (!clients.isEmpty()) clients.remove().run();
            }
        }
        final class Worker extends AbstractExecutorService {
            public void shutdown() {}
            public List<Runnable> shutdownNow() { return List.of(); }
            public boolean isShutdown() { return false; }
            public boolean isTerminated() { return false; }
            public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
            public void execute(Runnable action) { workers.add(action); }
        }
    }

    private static byte[] png() throws Exception {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(2, 3, 0xff224488);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }
}

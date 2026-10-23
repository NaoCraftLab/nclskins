package com.naocraftlab.skins.runtime.composition;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.api.ProfileApi;
import com.naocraftlab.skins.core.config.ClientConfiguration;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.png.SneakyCapeDecoder;
import com.naocraftlab.skins.core.provider.*;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import com.naocraftlab.skins.runtime.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

final class SneakyEditorOverlapCompositionTest {
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(value = SkinExtensionEnvironmentSource.Consumer.class, names = {"FRESH_MOVES", "JUST_EXPRESSIONS"})
    void exactLocalAssetAndImportedDraftSurviveQueuedCompositionAndFreshScopes(SkinExtensionEnvironmentSource.Consumer consumer) throws Exception {
        UUID account = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AtomicInteger credentialRequests = new AtomicInteger();
        GameSessionTokenSource tokens = new GameSessionTokenSource() {
            public SessionIdentity currentSession() { return new SessionIdentity(account, "Synthetic"); }
            public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                credentialRequests.incrementAndGet();
                throw new GameSessionTokenUnavailableException();
            }
        };
        ProfileApi forbiddenApi = (ProfileApi) java.lang.reflect.Proxy.newProxyInstance(ProfileApi.class.getClassLoader(),
                new Class<?>[]{ProfileApi.class}, (proxy, method, arguments) -> { throw new AssertionError("unexpected remote operation"); });
        byte[] stored = skin(0x7f123456), imported = skin(0x7f654321);
        Path png = directory.resolve("synthetic-draft.png");
        Files.write(png, imported);
        var storage = new NclSkinsStorage(directory.resolve("data"), new PngValidator(), Clock.systemUTC());
        var seed = ClientCompositionRoot.operations(tokens, forbiddenApi, storage, (collection, skin, model) -> stored.clone(), Clock.systemUTC(), null).operations();
        seed.initialize();
        var saved = seed.saveEditor(new LibraryEditorPort.EditorSaveRequest(Optional.empty(), "Synthetic draft",
                SkinReference.accountDefault(), SkinVariant.CLASSIC, SkinVariant.CLASSIC, Optional.empty(), Optional.of(stored)));
        seed.enableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY);
        Queues queues = new Queues();
        AtomicReference<SkinExtensionEnvironmentSource.State> consumerState = new AtomicReference<>(SkinExtensionEnvironmentSource.State.ACTIVE);
        AtomicReference<Runnable> environmentChanged = new AtomicReference<>(() -> {});
        SkinExtensionEnvironmentSource environment = new SkinExtensionEnvironmentSource() {
            public Snapshot snapshot() { return new Snapshot(1, Map.of(consumer, consumerState.get())); }
            public Subscription subscribe(Runnable callback) { environmentChanged.set(callback); return () -> environmentChanged.set(() -> {}); }
        };
        ClientCapabilitySet capabilities = new ClientCapabilitySet(tokens, (collection, skin, model) -> stored.clone(),
                () -> null, queues,
                () -> CompletableFuture.completedFuture(Optional.of(png)), SignedTextureVerifier.rejecting(),
                resolved -> PlayerAppearanceSink.ApplyResult.UPDATED, visibility -> {}, ServerAppearanceRefreshNotifier.NO_OP, environment);
        try (ClientRuntime runtime = ClientCompositionRoot.createRuntime(capabilities, UiMessage::key, directory.resolve("data"),
                ClientConfiguration::defaults, DiagnosticSinks.discarding(), name -> {
                    QueuedWorker worker = new QueuedWorker();
                    queues.workers.add(worker);
                    return new ClientCompositionRoot.Worker(worker, true);
                })) {
            runtime.initialize();
            queues.drain();
            List<String> publication = new ArrayList<>();
            List<String> publishedViews = new ArrayList<>();
            runtime.subscribe(snapshot -> snapshot.editor().ifPresent(editor -> {
                publication.add(editor.capeCatalog().sneakyReadiness().name());
                long cards = runtime.view(854, 480, 0, 0).widgets().stream()
                        .filter(widget -> widget.id().startsWith("editor.cape_item.SNEAKY.")).count();
                publishedViews.add(editor.capeCatalog().sneakyReadiness().name() + ":" + cards);
            }));

            runtime.dispatchWidget("gallery.preset." + saved.presetId() + ".edit");
            queues.drainUntil(() -> runtime.snapshot().editor().isPresent());
            assertEquals("PENDING", runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyReadiness().name());
            runtime.closeScreen();
            queues.drain();
            assertTrue(runtime.snapshot().editor().isEmpty());
            runtime.reopen();
            queues.drain();
            runtime.dispatchWidget("gallery.preset." + saved.presetId() + ".edit");
            queues.drainUntil(() -> runtime.snapshot().editor().isPresent());
            runtime.dispatchWidget("editor.tab.cape");
            queues.drain();
            assertTrue(runtime.snapshot().editor().orElseThrow().skin().optionalAssetId().isPresent());
            assertTrue(runtime.snapshot().editor().orElseThrow().png().isEmpty());
            assertReady(runtime, "VALUE", stored);
            assertWarning(runtime);
            var storedCard = runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream()
                    .filter(value -> value.provider() == BuiltinProvider.SNEAKY).findFirst().orElseThrow();
            runtime.dispatchWidget(storedCard.widgetId());
            assertEquals(BuiltinProvider.SNEAKY, runtime.snapshot().editor().orElseThrow().capeCatalog().inspected().provider());
            runtime.dispatchWidget("editor.tab.cape");
            assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().inspected());
            var preset = runtime.snapshot().account().orElseThrow().presets().stream()
                    .filter(value -> value.id().equals(saved.presetId())).findFirst().orElseThrow();
            assertTrue(runtime.snapshot().compatibilityFor(preset).activeConflicts().contains(
                    consumer == SkinExtensionEnvironmentSource.Consumer.FRESH_MOVES
                            ? com.naocraftlab.skins.core.compatibility.SkinConflictReason.SNEAKY_FRESH_MOVES_OVERLAP
                            : com.naocraftlab.skins.core.compatibility.SkinConflictReason.SNEAKY_JUST_EXPRESSIONS_OVERLAP));
            var accountBefore = runtime.snapshot().account();
            var providersBefore = runtime.snapshot().providers();
            var storageBefore = storage.loadOrCreateAccount(account);
            var appearanceBefore = storage.loadAppearance(account);
            assertEquals("VALUE:1", publishedViews.get(publishedViews.size() - 1));
            consumerState.set(SkinExtensionEnvironmentSource.State.UNKNOWN);
            queues.offClient(environmentChanged.get());
            queues.drain();
            assertReady(runtime, "VALUE", stored);
            assertTrue(queues.workerRuns > 0);
            assertTrue(queues.clientRuns > 0);
            int credentialsBefore = credentialRequests.get();
            for (var state : List.of(SkinExtensionEnvironmentSource.State.ACTIVE, SkinExtensionEnvironmentSource.State.INACTIVE,
                    SkinExtensionEnvironmentSource.State.MISSING_PREREQUISITE, SkinExtensionEnvironmentSource.State.UNKNOWN)) {
                consumerState.set(state);
                queues.offClient(environmentChanged.get());
                queues.drain();
                assertReady(runtime, "VALUE", stored);
                String expectedPublication = "VALUE:1";
                assertEquals(expectedPublication, publishedViews.get(publishedViews.size() - 1));
                if (state == SkinExtensionEnvironmentSource.State.ACTIVE) assertWarning(runtime);
            }
            assertTrue(publication.contains("VALUE"));
            runtime.capeWorldChanged();
            queues.drain();
            assertReady(runtime, "VALUE", stored);
            runtime.resourcesReloaded();
            queues.drain();
            assertReady(runtime, "VALUE", stored);
            runtime.closeScreen();
            queues.drain();
            assertTrue(runtime.snapshot().editor().isEmpty());
            runtime.reopen();
            queues.drain();
            runtime.dispatchWidget("gallery.preset." + saved.presetId() + ".edit");
            queues.drainUntil(() -> runtime.snapshot().editor().isPresent());
            runtime.dispatchWidget("editor.tab.cape");
            queues.drain();
            assertReady(runtime, "VALUE", stored);
            consumerState.set(SkinExtensionEnvironmentSource.State.ACTIVE);
            queues.offClient(environmentChanged.get());
            queues.drain();
            runtime.dispatchWidget("editor.png.choose");
            queues.drain();
            assertTrue(runtime.snapshot().editor().orElseThrow().png().isPresent());
            assertReady(runtime, "VALUE", imported);
            assertEquals("VALUE:1", publishedViews.get(publishedViews.size() - 1));
            assertWarning(runtime);
            var editor = runtime.snapshot().editor().orElseThrow();
            var card = editor.capeCatalog().cards().stream().filter(value -> value.provider() == BuiltinProvider.SNEAKY).findFirst().orElseThrow();
            runtime.dispatchWidget(card.widgetId());
            assertEquals(BuiltinProvider.SNEAKY, runtime.snapshot().editor().orElseThrow().capeCatalog().inspected().provider());
            runtime.dispatchWidget("editor.tab.cape");
            assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().inspected());
            assertReady(runtime, "VALUE", imported);
            assertEquals(editor.skin(), runtime.snapshot().editor().orElseThrow().skin());
            assertEquals(editor.capeId(), runtime.snapshot().editor().orElseThrow().capeId());
            assertEquals(accountBefore, runtime.snapshot().account());
            assertEquals(providersBefore, runtime.snapshot().providers());
            assertEquals(storageBefore, storage.loadOrCreateAccount(account));
            assertEquals(appearanceBefore, storage.loadAppearance(account));
            var accept = ClientRuntime.class.getDeclaredMethod("acceptProviderChange", ClientOperations.DurableAppearance.class);
            accept.setAccessible(true);
            accept.invoke(runtime, seed.disableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY));
            queues.drain();
            assertTrue(runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream().noneMatch(value -> value.provider() == BuiltinProvider.SNEAKY));
            assertTrue(runtime.view(854, 480, 0, 0).widgets().stream().noneMatch(widget -> widget.id().startsWith("editor.cape_item.SNEAKY.")));
            runtime.dispatchWidget(card.widgetId());
            assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().inspected());
            assertTrue(runtime.loadCapePreview(card.texture().orElseThrow()).join().isEmpty());
            accept.invoke(runtime, seed.enableProvider(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY));
            queues.drain();
            assertReady(runtime, "VALUE", imported);
            runtime.dispatchWidget(card.widgetId());
            assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().inspected());
            assertEquals(credentialsBefore, credentialRequests.get());
            assertArrayEquals(imported, Files.readAllBytes(png));
        }
        queues.drain();
    }

    private static void assertReady(ClientRuntime runtime, String expected, byte[] exact) throws Exception {
        var catalog = runtime.snapshot().editor().orElseThrow().capeCatalog();
        assertEquals(expected, catalog.sneakyReadiness().name());
        assertNotNull(catalog.sneaky());
        assertArrayEquals(new SneakyCapeDecoder().decode(exact).orElseThrow().bytes(), catalog.sneaky().atlas());
        var cards = catalog.cards().stream().filter(card -> card.provider() == BuiltinProvider.SNEAKY).toList();
        var view = runtime.view(854, 480, 0, 0);
        assertEquals(1, cards.size());
        assertEquals(BuiltinProvider.SNEAKY, catalog.defaultBundle().source());
        assertEquals(cards.get(0).texture(), view.previews().get(0).capeId());
        assertTrue(view.widgets().stream().anyMatch(widget -> widget.id().equals(cards.get(0).widgetId())));
        assertArrayEquals(new SneakyCapeDecoder().decode(exact).orElseThrow().bytes(),
                runtime.loadCapePreview(cards.get(0).texture().orElseThrow()).join().orElseThrow());
    }

    private static void assertWarning(ClientRuntime runtime) {
        assertEquals(Optional.of(GuiIcon.STATUS_COMPATIBILITY_INCOMPATIBLE), runtime.view(854, 480, 0, 0)
                .widget("editor.compatibility").orElseThrow().icon());
    }

    private static byte[] skin(int pixel) throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int[] marker = {0xfffff42f, 0xffffffff, 0xff9c59d1, 0xff292929};
        for (int index = 0; index < marker.length; index++) image.setRGB(60, 48 + index, marker[index]);
        image.setRGB(56, 16, pixel);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "png", encoded);
        return encoded.toByteArray();
    }

    private static final class Queues implements ClientExecutor {
        final ArrayDeque<Runnable> clients = new ArrayDeque<>();
        final List<QueuedWorker> workers = new ArrayList<>();
        boolean clientThread = true;
        int workerRuns, clientRuns;
        public boolean isClientThread() { return clientThread; }
        public void execute(Runnable action) { clients.add(action); }
        void offClient(Runnable action) {
            clientThread = false;
            try { action.run(); } finally { clientThread = true; }
        }
        void drain() { drainUntil(() -> clients.isEmpty() && workers.stream().allMatch(value -> value.jobs.isEmpty())); }
        void drainUntil(java.util.function.BooleanSupplier stop) {
            for (int budget = 0; budget < 2000; budget++) {
                if (stop.getAsBoolean()) return;
                if (!clients.isEmpty()) { clientRuns++; clients.remove().run(); continue; }
                var worker = workers.stream().filter(value -> !value.jobs.isEmpty()).findFirst();
                if (worker.isEmpty()) return;
                workerRuns++;
                offClient(worker.orElseThrow().jobs.remove());
            }
            fail("queues did not quiesce");
        }
    }

    private static final class QueuedWorker extends AbstractExecutorService {
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        boolean closed;
        public void execute(Runnable command) { if (closed) throw new RejectedExecutionException(); jobs.add(command); }
        public void shutdown() { closed = true; }
        public List<Runnable> shutdownNow() { closed = true; var pending = List.copyOf(jobs); jobs.clear(); return pending; }
        public boolean isShutdown() { return closed; }
        public boolean isTerminated() { return closed && jobs.isEmpty(); }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}

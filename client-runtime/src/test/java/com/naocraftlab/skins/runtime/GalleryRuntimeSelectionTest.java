package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.api.ProfileApi;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

final class GalleryRuntimeSelectionTest {
    @TempDir Path directory;
    static final UUID ACCOUNT = GalleryApplyTest.ACCOUNT;
    static final UUID A = GalleryApplyTest.A;
    static final UUID B = GalleryApplyTest.B;
    final Deque<Runnable> workers = new ArrayDeque<>();
    final Deque<Runnable> clients = new ArrayDeque<>();
    final AtomicReference<GameSessionTokenSource.SessionIdentity> identity = new AtomicReference<>(
            new GameSessionTokenSource.SessionIdentity(ACCOUNT, "Fixture"));
    final AtomicInteger reconciliations = new AtomicInteger();
    boolean workerLane;
    boolean inline;
    boolean rejectWorker;
    boolean expectedProvidersParent;
    Runnable afterApply = () -> {};
    java.util.function.Consumer<String> beforeOperation = ignored -> {};
    java.util.function.Consumer<String> afterOperation = ignored -> {};
    final List<UiPreferencesPort.GallerySectionsUpdate> sectionUpdates = new ArrayList<>();
    NclSkinsStorage storage;
    DefaultClientOperations operations;
    ClientRuntime runtime;
    Optional<CurrentPlayerAppearanceSource> currentAppearance = Optional.empty();
    Optional<OuterLayerVisibilityController> layerController = Optional.empty();
    boolean initiallyActive = true;

    com.naocraftlab.skins.core.storage.GalleryStorageFixture.Fault storageFault = stage -> {};

    void setup() throws Exception {
        storage = com.naocraftlab.skins.core.storage.GalleryStorageFixture.create(directory, GalleryApplyTest.CLOCK, storageFault);
        storage.initialize();
        storage.updateAccount(ACCOUNT, old -> new AccountState(old.schemaVersion(), ACCOUNT, List.of(),
                List.of(new AppearancePreset(A, "A", SkinReference.accountDefault(), null, GalleryApplyTest.NOW, GalleryApplyTest.NOW),
                        new AppearancePreset(B, "B", SkinReference.accountDefault(), null, OuterLayerVisibility.noneVisible(),
                                GalleryApplyTest.NOW, GalleryApplyTest.NOW)), GalleryApplyTest.NOW));
        GameSessionTokenSource tokens = new GameSessionTokenSource() {
            public SessionIdentity currentSession() { return identity.get(); }
            public <T,E extends Exception> T withAccessToken(TokenRequest<T,E> request) {
                throw new IllegalStateException("offline fixture");
            }
        };
        ProfileApi api = (ProfileApi) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ProfileApi.class},
                (p,m,a) -> {
                    if (m.getName().equals("rateLimitRemaining")) return Optional.empty();
                    throw new AssertionError("Unexpected HTTP: " + m.getName());
                });
        byte[] png = Files.readAllBytes(Path.of("../core/src/test/resources/cpm/classic-a.png"));
        var graph = TestClientGraph.create(tokens, api, storage, (BundledSkinSource) model -> png, GalleryApplyTest.CLOCK);
        operations = graph.operations();
        if (initiallyActive) operations.usePreset(A);
        ClientOperations port = (ClientOperations) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ClientOperations.class},
                (p,m,a) -> {
                    if (m.getName().equals("reconcileAppearance")) {
                        reconciliations.incrementAndGet();
                        if (expectedProvidersParent) assertEquals("providers", runtime.view(854,480,0,0).screenId());
                        else assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle(), "Parent must close before reconciliation");
                    }
                    try {
                        beforeOperation.accept(m.getName());
                        if (m.getName().equals("updateGallerySections")) sectionUpdates.add((UiPreferencesPort.GallerySectionsUpdate) a[0]);
                        Object result = m.invoke(operations, a);
                        if (m.getName().equals("applyGalleryDraft")) afterApply.run();
                        afterOperation.accept(m.getName());
                        return result;
                    }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        ClientExecutor client = new ClientExecutor() {
            public boolean isClientThread() { return !workerLane; }
            public void execute(Runnable action) { if (!workerLane || inline) action.run(); else clients.add(action); }
        };
        graph.attachProviders(resolved -> PlayerAppearanceSink.ApplyResult.UPDATED, client);
        runtime = new ClientRuntime(new DefaultRuntimeServices(), port, client,
                () -> CompletableFuture.completedFuture(Optional.empty()),
                action -> {
                    if (rejectWorker) throw new RejectedExecutionException("fixture rejection");
                    if (inline) action.run(); else workers.add(action);
                }, UiMessage::key,
                currentAppearance, Optional.empty(), layerController, Optional.empty(), DiagnosticSinks.discarding());
        runtime.subscribe(snapshot -> assertFalse(workerLane, "Publication belongs to client executor"));
        runtime.initialize();
        drain();
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        runtime.view(854,480,0,0);
        reconciliations.set(0);
    }

    void work() { workerLane = true; try { workers.remove().run(); } finally { workerLane = false; } }
    void drain() { while (!workers.isEmpty() || !clients.isEmpty()) { if (!workers.isEmpty()) work(); while (!clients.isEmpty()) clients.remove().run(); } }
    String card(UUID id) { return "gallery.card." + id; }
    ViewSpec view() { return runtime.view(854,480,0,0); }
    void selectB() { runtime.dispatchWidget(card(B), false, InteractionOrigin.POINTER); }

    @Test void deletedQueuedSelectionFailsOpenAndResetReadsIndependentDeletion() throws Exception {
        setup();
        selectB();
        runtime.dispatchWidget("gallery.apply");
        operations.deletePreset(new LibraryEditorPort.GalleryScope(operations.sessionIdentity().profileId(), 0, () -> true), B);
        var before = ScopedEditorSaveTest.inventory(directory);
        drain();
        assertEquals(before, ScopedEditorSaveTest.inventory(directory));
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        assertFalse(runtime.snapshot().busy());
        assertEquals(B, runtime.snapshot().selectedPresetId().orElseThrow());
        assertEquals(A, runtime.snapshot().activePresetId().orElseThrow());
        assertEquals(UiMessage.Severity.ERROR, runtime.snapshot().status().severity());
        assertTrue(view().widget("gallery.apply").orElseThrow().enabled());
        assertEquals(0, reconciliations.get());
        runtime.dispatchWidget("gallery.reset");
        drain();
        assertEquals(A, runtime.snapshot().selectedPresetId().orElseThrow());
        assertTrue(runtime.snapshot().account().orElseThrow().presets().stream().noneMatch(p -> p.id().equals(B)));
        assertTrue(view().widget("gallery.done").isPresent());
        assertEquals(before, ScopedEditorSaveTest.inventory(directory));
        runtime.close();
    }

    @Test void queuedSelectionUsesLatestMetadataAndMaskWithoutAnUnrequestedPatch() throws Exception {
        setup();
        selectB();
        runtime.dispatchWidget("gallery.apply");
        storage.updateAccount(ACCOUNT, old -> new AccountState(old.schemaVersion(), ACCOUNT, old.skinAssets(),
                old.presets().stream().map(p -> p.id().equals(B) ? new AppearancePreset(B, "Renamed", p.skin(),
                        p.capeId(), OuterLayerVisibility.allVisible(), p.createdAt(), p.updatedAt(), p.offlineCape(), true) : p).toList(),
                old.updatedAt()));
        drain();
        var saved = storage.loadOrCreateAccount(ACCOUNT).presets().stream().filter(p -> p.id().equals(B)).findFirst().orElseThrow();
        assertEquals("Renamed", saved.name());
        assertTrue(saved.favorite());
        assertEquals(OuterLayerVisibility.allVisible(), saved.outerLayerVisibility());
        assertEquals(B, storage.loadAppearance(ACCOUNT).activePresetId());
        assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
        assertEquals(1, reconciliations.get());
        runtime.close();
    }

    @Test void deletingActiveWhileAnotherDraftIsSelectedDoesNotSelectNavigationSuccessor() throws Exception {
        setup();
        selectB();
        runtime.dispatchWidget("gallery.preset." + A + ".more");
        runtime.dispatchWidget("gallery.preset." + A + ".delete", false, InteractionOrigin.KEYBOARD);
        runtime.dispatchWidget("gallery.preset." + A + ".delete_confirm", false, InteractionOrigin.KEYBOARD);
        drain();
        assertEquals(B, runtime.snapshot().selectedPresetId().orElseThrow());
        assertTrue(runtime.snapshot().activePresetId().isEmpty());
        assertTrue(view().widget("gallery.apply").isPresent());
        assertEquals(OuterLayerVisibility.noneVisible(), view().previews().stream()
                .filter(p -> p.id().equals("gallery.preview")).findFirst().orElseThrow().outerLayerVisibility());
        assertEquals(card(B), view().focusRequest().orElseThrow().widgetId());
        runtime.close();
    }

    @Test void deletingSelectedInactiveDraftReturnsToRemainingDurableActive() throws Exception {
        setup();
        selectB();
        long revision = storage.loadAppearance(ACCOUNT).intentRevision();
        runtime.dispatchWidget("gallery.preset." + B + ".more");
        runtime.dispatchWidget("gallery.preset." + B + ".delete", false, InteractionOrigin.KEYBOARD);
        runtime.dispatchWidget("gallery.preset." + B + ".delete_confirm", false, InteractionOrigin.KEYBOARD);
        drain();
        assertEquals(A, storage.loadAppearance(ACCOUNT).activePresetId());
        assertEquals(revision, storage.loadAppearance(ACCOUNT).intentRevision());
        assertEquals(A, runtime.snapshot().selectedPresetId().orElseThrow());
        assertTrue(view().widget("gallery.done").isPresent());
        assertTrue(view().widget("gallery.apply").isEmpty());
        assertEquals(OuterLayerVisibility.allVisible(), view().previews().stream()
                .filter(p -> p.id().equals("gallery.preview")).findFirst().orElseThrow().outerLayerVisibility());
        assertEquals(card(A), view().focusRequest().orElseThrow().widgetId());
        assertEquals(0, reconciliations.get());
        runtime.close();
    }

    @Test void responseIdentityChangeWithoutTickDoesNotPublishLocalResult() throws Exception {
        setup();
        selectB();
        afterApply = () -> identity.set(new GameSessionTokenSource.SessionIdentity(new UUID(0,99), "Other"));
        runtime.dispatchWidget("gallery.apply");
        work();
        assertTrue(runtime.snapshot().busy());
        assertEquals(A,runtime.snapshot().activePresetId().orElseThrow());
        assertEquals(B,storage.loadAppearance(ACCOUNT).activePresetId());
        while (!clients.isEmpty()) clients.remove().run();
        assertFalse(runtime.snapshot().busy());
        assertEquals(A,runtime.snapshot().activePresetId().orElseThrow());
        assertEquals(0,reconciliations.get());
        identity.set(new GameSessionTokenSource.SessionIdentity(ACCOUNT,"Fixture"));
        afterApply = () -> {};
        runtime.dispatchWidget("gallery.reset");
        drain();
        assertEquals(B,runtime.snapshot().activePresetId().orElseThrow());
        runtime.close();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"edit", "duplicate"})
    void admittedChildEditorCancelDiscardsSelectedGalleryDraft(String action) throws Exception {
        setup();
        selectB();
        runtime.dispatchWidget("gallery.preset."+B+".more",false,InteractionOrigin.POINTER);
        var edit = view().widget("gallery.preset."+B+"."+action).orElseThrow();
        assertTrue(edit.visible() && edit.enabled());
        var before = ScopedEditorSaveTest.inventory(directory);
        runtime.dispatchWidget(edit.id(),false,InteractionOrigin.POINTER);
        drain();
        assertTrue(runtime.snapshot().editor().isPresent());
        assertEquals(action.equals("edit") ? Optional.of(B) : Optional.empty(),runtime.snapshot().editor().orElseThrow().originalPresetId());
        assertEquals(OuterLayerVisibility.noneVisible(),runtime.snapshot().editor().orElseThrow().preview().outerLayerVisibility());
        runtime.dispatchWidget("editor.cancel");
        assertEquals("gallery",view().screenId());
        assertEquals(Optional.of(A),runtime.snapshot().selectedPresetId());
        assertTrue(view().widget("gallery.done").isPresent());
        assertTrue(view().widget("gallery.apply").isEmpty());
        assertEquals(0,reconciliations.get());
        assertEquals(before,ScopedEditorSaveTest.inventory(directory));
        runtime.close();
    }

    @Test void warmedPreferencesReenterInitializingThenReadyWithoutWrites() throws Exception {
        setup();
        operations.setPresetFavorite(new LibraryEditorPort.PresetFavoriteUpdate(
                new LibraryEditorPort.GalleryScope(ACCOUNT,0,()->true),B,true));
        runtime.closeScreen(); runtime.reopen(); drain();
        var collapsed = view().widget("gallery.your_appearances").orElseThrow();
        assertTrue(collapsed.visible() && collapsed.enabled());
        runtime.dispatchWidget(collapsed.id(),false,InteractionOrigin.POINTER);
        drain();
        assertEquals(1,sectionUpdates.size());
        var before = ScopedEditorSaveTest.inventory(directory);
        runtime.closeScreen();
        runtime.reopen();
        assertEquals(ClientSnapshot.Lifecycle.INITIALIZING,runtime.snapshot().lifecycle());
        assertTrue(runtime.snapshot().account().isPresent());
        view();
        assertEquals(before,ScopedEditorSaveTest.inventory(directory));
        drain();
        assertEquals(ClientSnapshot.Lifecycle.READY,runtime.snapshot().lifecycle());
        assertTrue(view().widget(card(A)).isPresent());
        assertEquals(1,sectionUpdates.size());
        assertEquals(before,ScopedEditorSaveTest.inventory(directory));
        runtime.close();
    }

    @Test void warmedInitializingRejectsApplyWithoutConsumingNextReadyAction() throws Exception {
        setup();
        runtime.closeScreen();
        runtime.reopen();
        assertTrue(runtime.snapshot().account().isPresent());
        assertEquals(ClientSnapshot.Lifecycle.INITIALIZING,runtime.snapshot().lifecycle());
        var before = ScopedEditorSaveTest.inventory(directory);
        runtime.dispatchWidget(card(B));
        runtime.dispatchWidget("gallery.apply");
        assertEquals(1,workers.size());
        assertEquals(before,ScopedEditorSaveTest.inventory(directory));
        drain();
        selectB();
        runtime.dispatchWidget("gallery.apply");
        drain();
        assertEquals(B,storage.loadAppearance(ACCOUNT).activePresetId());
        runtime.close();
    }

    @Test void arrowFocusIsSeparateFromDraftAndEnterSelectsOnlyDraft() throws Exception {
        setup();
        assertTrue(runtime.dispatchNavigation(ViewSpec.NavigationCommand.RIGHT, card(A)));
        assertEquals(card(B), view().focusRequest().orElseThrow().widgetId());
        assertEquals("gallery.preset."+B+".more", ViewNavigationPolicy.target(view(),card(B),ViewSpec.NavigationCommand.TAB_FORWARD).orElseThrow().id());
        assertEquals(A, runtime.snapshot().selectedPresetId().orElseThrow());
        var before = ScopedEditorSaveTest.inventory(directory);
        assertTrue(runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, card(B)));
        assertEquals(B, runtime.snapshot().selectedPresetId().orElseThrow());
        assertEquals(before, ScopedEditorSaveTest.inventory(directory));
        runtime.close();
    }

    @Test void verticalScrollbarDragUsesYAndPreservesDraft() throws Exception {
        setup();
        storage.updateAccount(ACCOUNT, old -> {
            var presets = new ArrayList<>(old.presets());
            for (int i=10; i<70; i++) presets.add(new AppearancePreset(new UUID(0,i), "Card " + i,
                    SkinReference.accountDefault(), null, GalleryApplyTest.NOW, GalleryApplyTest.NOW));
            return new AccountState(old.schemaVersion(), ACCOUNT, old.skinAssets(), presets, old.updatedAt());
        });
        selectB();
        runtime.dispatchWidget("gallery.reset");
        drain();
        var bar = view().scrollbar().orElseThrow();
        assertTrue(bar.maximum() > 0);
        runtime.pointerPressed(bar.thumb().x()+1, bar.thumb().y()+1, 0);
        double y = bar.track().y() + (bar.track().height()-bar.thumb().height())*0.3 + 1;
        runtime.pointerDragged(bar.thumb().x()+1, y, 0, 0, y-bar.thumb().y());
        runtime.pointerReleased(0);
        assertEquals((int)Math.round(bar.maximum()*0.3), runtime.snapshot().galleryOffset(), 1);
        assertEquals(A, runtime.snapshot().selectedPresetId().orElseThrow());
        runtime.close();
    }

    @Test void cardActionsDoNotSelectDraftAndDeleteDefaultsToCancel() throws Exception {
        setup();
        runtime.dispatchWidget("gallery.preset."+B+".more",false,InteractionOrigin.POINTER);
        assertEquals(A, runtime.snapshot().selectedPresetId().orElseThrow());
        assertTrue(view().widget("gallery.preset."+B+".delete").isPresent());
        runtime.dispatchWidget("gallery.preset."+B+".delete",false,InteractionOrigin.KEYBOARD);
        assertEquals("gallery.preset."+B+".delete_cancel",view().focusRequest().orElseThrow().widgetId());
        assertEquals(A, runtime.snapshot().selectedPresetId().orElseThrow());
        runtime.close();
    }

    @Test void inlineWorkerClosesParentBeforeSelectionReconciliation() throws Exception {
        setup();
        selectB();
        inline = true;
        runtime.dispatchWidget("gallery.apply");
        assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
        assertEquals(B, storage.loadAppearance(ACCOUNT).activePresetId());
        assertEquals(1, reconciliations.get());
        runtime.close();
    }

    @Test void committedResponseForClosedReplacedGalleryDoesNotPublishToNewWork() throws Exception {
        setup();
        selectB();
        runtime.dispatchWidget("gallery.apply");
        work();
        assertTrue(runtime.snapshot().busy());
        runtime.closeScreen();
        runtime.reopen();
        runtime.view(854,480,0,0);
        var generation = runtime.snapshot().generation();
        while (!clients.isEmpty()) clients.remove().run();
        assertEquals(generation,runtime.snapshot().generation());
        assertEquals(ClientSnapshot.Lifecycle.INITIALIZING,runtime.snapshot().lifecycle());
        assertTrue(runtime.snapshot().busy());
        assertEquals(A,runtime.snapshot().activePresetId().orElseThrow());
        assertEquals(0,reconciliations.get());
        drain();
        assertEquals(ClientSnapshot.Lifecycle.READY,runtime.snapshot().lifecycle());
        assertEquals(B,runtime.snapshot().activePresetId().orElseThrow());
        runtime.close();
    }

    @Test void applyCommitsLocallyThenClosesBeforeReconciliation() throws Exception {
        setup();
        selectB();
        runtime.dispatchWidget("gallery.apply");
        assertTrue(runtime.snapshot().busy());
        assertEquals(A, storage.loadAppearance(ACCOUNT).activePresetId());
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        work();
        assertEquals(B, storage.loadAppearance(ACCOUNT).activePresetId());
        assertEquals(A, runtime.snapshot().activePresetId().orElseThrow());
        assertTrue(runtime.snapshot().busy());
        clients.remove().run();
        assertEquals(B, runtime.snapshot().activePresetId().orElseThrow());
        assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
        drain();
        assertEquals(1, reconciliations.get());
        runtime.close();
    }

    @Test void resetReadsFreshDurableSelectionWithoutAssignmentOrHttp() throws Exception {
        setup();
        selectB();
        operations.usePreset(B);
        var before = ScopedEditorSaveTest.inventory(directory);
        runtime.dispatchWidget("gallery.reset");
        assertTrue(runtime.snapshot().busy());
        drain();
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        assertEquals(B, runtime.snapshot().activePresetId().orElseThrow());
        assertEquals(B, runtime.snapshot().selectedPresetId().orElseThrow());
        assertTrue(view().widget("gallery.done").isPresent());
        assertEquals(before, ScopedEditorSaveTest.inventory(directory));
        assertEquals(0, reconciliations.get());
        runtime.close();
    }

    @Test void lockedActiveEditDoesNotCreateSelectionReconciliation() throws Exception {
        setup();
        selectB();
        runtime.dispatchWidget("gallery.apply");
        operations.usePreset(B);
        var before = ScopedEditorSaveTest.inventory(directory);
        drain();
        assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
        assertEquals(B, runtime.snapshot().activePresetId().orElseThrow());
        assertEquals(0, reconciliations.get());
        assertEquals(before, ScopedEditorSaveTest.inventory(directory));
        runtime.close();
    }

    @Test void queuedIdentityChangeWithoutTickRejectsWritesAndRetiresOnlyOnClient() throws Exception {
        setup();
        selectB();
        var before = ScopedEditorSaveTest.inventory(directory);
        runtime.dispatchWidget("gallery.apply");
        identity.set(new GameSessionTokenSource.SessionIdentity(UUID.fromString("00000000-0000-0000-0000-000000000009"), "Other"));
        work();
        assertEquals(before, ScopedEditorSaveTest.inventory(directory));
        assertTrue(runtime.snapshot().busy(), "Worker must not mutate client state");
        identity.set(new GameSessionTokenSource.SessionIdentity(ACCOUNT, "Fixture"));
        while (!clients.isEmpty()) clients.remove().run();
        assertFalse(runtime.snapshot().busy());
        assertEquals(A, runtime.snapshot().activePresetId().orElseThrow());
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        runtime.dispatchWidget("gallery.apply");
        drain();
        assertEquals(B, runtime.snapshot().activePresetId().orElseThrow());
        assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
        runtime.close();
    }

    @Test void cardActivationSelectsDraftWithoutDurableOrOptionsEffects() throws Exception {
        setup();
        var before = ScopedEditorSaveTest.inventory(directory);
        selectB();
        assertEquals(B, runtime.snapshot().selectedPresetId().orElseThrow());
        assertEquals(A, runtime.snapshot().activePresetId().orElseThrow());
        assertEquals(OuterLayerVisibility.noneVisible(), view().previews().stream().filter(p -> p.id().equals("gallery.preview")).findFirst().orElseThrow().outerLayerVisibility());
        assertTrue(view().widget("gallery.apply").isPresent());
        assertTrue(view().widget("gallery.done").isEmpty());
        assertEquals(before, ScopedEditorSaveTest.inventory(directory));
        assertTrue(workers.isEmpty());
        runtime.close();
    }
}

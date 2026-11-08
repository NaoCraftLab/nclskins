package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.service.LibraryService;
import com.naocraftlab.skins.core.storage.LibraryStorageAdapter;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

final class GalleryDisplayTransitionJoinTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectionRetainsPublishedLeftCompositionWithoutGrantingDraftAuthority(boolean inline) throws Exception {
        var f = fixture(inline);
        var registry = new GalleryPreviewSourceJoinTest.Registry();
        try (var cache = new PreviewAssetCache<String>(registry, TextureRegistry.TextureKind.PLAYER_SKIN,
                DiagnosticSinks.discarding())) {
            var a = main(f.view());
            var aRequest = load(f, cache, a);
            var gallery = new EditorPreviewSession.Gallery();
            var session = gallery.prepare(f.runtime.previewBodySource(a), false, true, () -> true);
            session.displayAuthority(aRequest.appearance(), this, f);
            var callback = f.runtime.observePreviewBody(a, () -> displayKind(session), () -> gallery.confirm(f.runtime.previewBodySource(a)));
            gallery.observeBody(callback);
            session.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(
                    PreviewRenderer.BodyStatus.ORDINARY, () -> true));
            var shown = f.view();
            assertEquals(PreviewRenderer.DisplayKind.ORDINARY, displayKind(session));
            var head = shown.widget("gallery.outer_layer.head").orElseThrow();
            var publications = new ArrayList<ViewSpec>();
            var subscription = f.runtime.subscribe(ignored -> publications.add(f.view()));
            publications.clear();
            try {
                f.selectB();
                assertEquals(GalleryRuntimeSelectionTest.B, f.runtime.snapshot().selectedPresetId().orElseThrow());
                var desired = f.runtime.galleryPreviewCandidate().orElseThrow();
                var bRequest = load(f, cache, desired);
                assertNotEquals(aRequest.appearance().skin(), bRequest.appearance().skin());
                assertNotEquals(aRequest.appearance().model(), bRequest.appearance().model());
                assertNotEquals(aRequest.appearance().outerLayerVisibility(), bRequest.appearance().outerLayerVisibility());
                assertArrayEquals(f.operations.loadSkinPreview(a.skin().assetId()),
                        registry.bytes.get(aRequest.appearance().skin().location()));
                assertArrayEquals(f.operations.loadSkinPreview(desired.skin().assetId()),
                        registry.bytes.get(bRequest.appearance().skin().location()));
                callback.accept(PreviewRenderer.BodyStatus.ORDINARY);
                f.runtime.dispatchWidget("gallery.outer_layer.head");
                assertEquals(OuterLayerVisibility.noneVisible(), f.runtime.galleryPreviewCandidate().orElseThrow().outerLayerVisibility(),
                        "Old draw cannot authorize a layer mutation of desired B");
                assertFalse(publications.isEmpty());
                assertAll(publications.stream().map(view -> (org.junit.jupiter.api.function.Executable) () -> {
                    assertEquals(a.skin(), main(view).skin(), "Before successor draw every publication retains displayed A");
                    assertEquals(head.bounds(), view.widget(head.id()).orElseThrow().bounds());
                    assertTrue(view.widget(head.id()).orElseThrow().enabled(), "No disabled-style dimming");
                    assertFalse(view.widget(head.id()).orElseThrow().activationEnabled(), "No stale action authority");
                    assertTrue(view.widget("gallery.apply").orElseThrow().enabled(), "Rest UI remains responsive");
                }));
                var predecessor = session.body();
                var candidate = gallery.prepare(f.runtime.previewBodySource(desired), false, true, () -> true);
                candidate.displayAuthority(bRequest.appearance(), this, f);
                var successor = f.runtime.observePreviewBody(desired, () -> displayKind(candidate), () -> gallery.confirm(f.runtime.previewBodySource(desired)));
                gallery.observeBody(successor);
                assertEquals(a.skin(), main(f.view()).skin());
                candidate.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(
                        PreviewRenderer.BodyStatus.ORDINARY, () -> true));
                f.drain();
                assertEquals(desired.skin(), main(f.view()).skin());
                assertEquals(desired.outerLayerVisibility(), main(f.view()).outerLayerVisibility());
                assertTrue(f.view().widget(head.id()).orElseThrow().activationEnabled());
                assertFalse(predecessor.current().getAsBoolean(), "Commit retires predecessor lease");
            } finally { subscription.close(); }
        } finally { f.runtime.close(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realSessionPreparationCannotYetKeepPredecessorDrawLease(boolean inline) throws Exception {
        var f = fixture(inline);
        var registry = new GalleryPreviewSourceJoinTest.Registry();
        try (var cache = new PreviewAssetCache<String>(registry, TextureRegistry.TextureKind.PLAYER_SKIN,
                DiagnosticSinks.discarding())) {
            var a = main(f.view());
            var aRequest = load(f, cache, a);
            var gallery = new EditorPreviewSession.Gallery();
            var session = gallery.prepare(f.runtime.previewBodySource(a), false, true, () -> true);
            session.displayAuthority(aRequest.appearance(), this, f);
            session.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(
                    PreviewRenderer.BodyStatus.ORDINARY, () -> true));
            gallery.confirm(f.runtime.previewBodySource(a));
            var predecessor = session.body();
            assertTrue(predecessor.current().getAsBoolean());
            f.selectB();
            var b = f.runtime.galleryPreviewCandidate().orElseThrow();
            var bRequest = load(f, cache, b);
            assertEquals(SkinModel.SLIM, bRequest.appearance().model());
            var candidate = gallery.prepare(f.runtime.previewBodySource(b), true, true, () -> true);
            assertEquals(PreviewRenderer.BodyStatus.PENDING, candidate.bodyStatus());
            assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, displayKind(candidate));
            assertTrue(predecessor.current().getAsBoolean(),
                    "Preparing B must preserve only A drawing lease, independently of B action authority");
        } finally { f.runtime.close(); }
    }

    @Test void originalCpmFirstBodyHidingTimeoutAndLateReadyRemainSafetyConstraints() {
        var clock = new AtomicLong();
        var session = new EditorPreviewSession(clock::get);
        session.beginBody("first-cpm", true, true);
        assertEquals(PreviewRenderer.BodyStatus.PENDING, session.bodyStatus());
        assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, displayKind(session));
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, displayKind(session), "Preparation is not draw");
        session.beginBody("next-cpm", true, true);
        clock.set(java.time.Duration.ofSeconds(60).toNanos());
        assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, session.bodyStatus());
        session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        assertEquals(PreviewRenderer.BodyStatus.TIMED_OUT, session.bodyStatus());
        assertEquals(PreviewRenderer.DisplayKind.UNCONFIRMED, displayKind(session));
    }

    @Test void cleanRepeatedSelectionKeepsLoadedAppearanceAndBodyIdentity() throws Exception {
        var f = fixture(false);
        var registry = new GalleryPreviewSourceJoinTest.Registry();
        try (var cache = new PreviewAssetCache<String>(registry, TextureRegistry.TextureKind.PLAYER_SKIN,
                DiagnosticSinks.discarding())) {
            GalleryInspectLayersTest.ordinary(f);
            var before = main(f.view());
            var loaded = load(f, cache, before);
            f.runtime.dispatchWidget(f.card(before.presetId().orElseThrow()), false, InteractionOrigin.POINTER);
            var desired = f.runtime.galleryPreviewCandidate().orElseThrow();
            var repeated = load(f, cache, desired);
            assertEquals(loaded.appearance(), repeated.appearance(), "Clean repeat keeps the exact loaded bitmap/model/mask");
            assertEquals(before.imageRevision(), desired.imageRevision(), "Identity churn is not image revision churn");
            assertEquals(f.runtime.previewBodySource(before), f.runtime.previewBodySource(desired),
                    "CLEAN_REPEAT_IDENTITY: unchanged loaded appearance must not create another BODY source");
        } finally { f.runtime.close(); }
    }

    @Test void dirtyRepeatedSelectionStillResetsMaskAndInspection() throws Exception {
        var f = fixture(false);
        try {
            GalleryInspectLayersTest.ordinary(f);
            var saved = main(f.view());
            f.runtime.dispatchWidget("gallery.outer_layer.head", false, InteractionOrigin.POINTER);
            GalleryInspectLayersTest.ordinary(f);
            f.runtime.dispatchWidget("gallery.inspect.MINECRAFT", false, InteractionOrigin.POINTER);
            GalleryInspectLayersTest.ordinary(f);
            var dirty = f.runtime.galleryPreviewCandidate().orElseThrow();
            assertNotEquals(saved.outerLayerVisibility(), dirty.outerLayerVisibility());
            assertTrue(dirty.bodySource().orElseThrow().inspectionIdentity().isPresent());
            f.runtime.dispatchWidget(f.card(saved.presetId().orElseThrow()), false, InteractionOrigin.POINTER);
            var reset = f.runtime.galleryPreviewCandidate().orElseThrow();
            assertEquals(saved.outerLayerVisibility(), reset.outerLayerVisibility());
            assertTrue(reset.bodySource().orElseThrow().inspectionIdentity().isEmpty());
            assertNotEquals(f.runtime.previewBodySource(dirty), f.runtime.previewBodySource(reset));
        } finally { f.runtime.close(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void maskAndInspectRetainWholePublishedBundleUntilTheirOwnCompletedPass(boolean inline) throws Exception {
        var f = fixture(inline);
        var registry = new GalleryPreviewSourceJoinTest.Registry();
        try (var cache = new PreviewAssetCache<String>(registry, TextureRegistry.TextureKind.PLAYER_SKIN,
                DiagnosticSinks.discarding()); var gallery = new EditorPreviewSession.Gallery()) {
            var selected = main(f.view());
            var request = load(f, cache, selected);
            var session = gallery.prepare(f.runtime.previewBodySource(selected), false, true, () -> true);
            session.displayAuthority(request.appearance(), this, f);
            var callback = f.runtime.observePreviewBody(selected, () -> displayKind(session), () -> gallery.confirm(f.runtime.previewBodySource(selected)));
            gallery.observeBody(callback);
            session.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
            for (String action : List.of("gallery.outer_layer.head", "gallery.inspect.MINECRAFT")) {
                var before = f.view();
                var shown = main(before);
                var control = before.widget(action).orElseThrow();
                assertTrue(control.enabled() && control.activationEnabled());
                var publications = new ArrayList<ViewSpec>();
                var subscription = f.runtime.subscribe(ignored -> publications.add(f.view()));
                publications.clear();
                try {
                    f.runtime.dispatchWidget(action, false, InteractionOrigin.POINTER);
                    var desired = f.runtime.galleryPreviewCandidate().orElseThrow();
                    assertNotEquals(shown.bodySource(), desired.bodySource());
                    var desiredRequest = load(f, cache, desired);
                    callback.accept(PreviewRenderer.BodyStatus.ORDINARY);
                    f.drain();
                    f.runtime.dispatchWidget("gallery.outer_layer.body");
                    f.runtime.dispatchWidget("gallery.inspect.MINECRAFT");
                    assertEquals(desired, f.runtime.galleryPreviewCandidate().orElseThrow(), "Pending clicks are rejected, never queued");
                    var candidate = gallery.prepare(f.runtime.previewBodySource(desired), false, true, () -> true);
                    candidate.displayAuthority(desiredRequest.appearance(), this, f);
                    var successor = f.runtime.observePreviewBody(desired, () -> displayKind(candidate), () -> gallery.confirm(f.runtime.previewBodySource(desired)));
                    gallery.observeBody(successor);
                    assertFalse(publications.isEmpty());
                    for (var view : publications) {
                        assertEquals(shown, main(view), "Retain appearance/mask/cape/transform/source/anchor");
                        for (var widget : before.widgets()) if (widget.id().startsWith("gallery.outer_layer.")
                                || widget.id().startsWith("gallery.inspect.") || widget.id().equals("gallery.preview_mode")) {
                            var retained = view.widget(widget.id()).orElseThrow();
                            assertEquals(widget.bounds(), retained.bounds());
                            assertEquals(widget.label(), retained.label());
                            assertEquals(widget.enabled(), retained.enabled());
                            assertFalse(retained.activationEnabled());
                        }
                        assertTrue(view.widget("gallery.providers").orElseThrow().enabled());
                        assertTrue(view.widget("gallery.search").orElseThrow().enabled());
                    }
                    candidate.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
                    f.drain();
                    assertEquals(desired.outerLayerVisibility(), main(f.view()).outerLayerVisibility());
                    assertEquals(desired.capeId(), main(f.view()).capeId());
                    assertTrue(f.view().widget(action).orElseThrow().activationEnabled());
                    callback = successor;
                } finally { subscription.close(); }
            }
        } finally { f.runtime.close(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void bToCToBLoadsOwnSourcesAndRejectsQueuedOldCallbacksAtEveryPublication(boolean inline) throws Exception {
        var f = fixture(inline);
        var registry = new GalleryPreviewSourceJoinTest.Registry();
        UUID c = new UUID(0,77);
        try (var cache = new PreviewAssetCache<String>(registry,TextureRegistry.TextureKind.PLAYER_SKIN,DiagnosticSinks.discarding());
                var gallery = new EditorPreviewSession.Gallery()) {
            var adapter = new LibraryStorageAdapter(f.storage);
            var asset = new LibraryService(adapter,adapter,GalleryApplyTest.CLOCK).importSkin(GalleryRuntimeSelectionTest.ACCOUNT,
                    "C source",SkinVariant.CLASSIC,SkinSource.IMPORTED,GalleryPreviewSourceJoinTest.png(0xFF992277)).asset();
            f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT,old -> {
                var presets = new ArrayList<>(old.presets());
                presets.add(new AppearancePreset(c,"C",SkinReference.asset(asset.id()),"cape-c",OuterLayerVisibility.allVisible(),GalleryApplyTest.NOW,GalleryApplyTest.NOW));
                return new AccountState(old.schemaVersion(),old.accountId(),old.skinAssets(),presets,old.updatedAt());
            });
            f.runtime.dispatchWidget("gallery.reset"); f.drain();
            var a = main(f.view());
            var aRequest = load(f,cache,a);
            var aSession = gallery.prepare(f.runtime.previewBodySource(a),false,true, () -> true);
            aSession.displayAuthority(aRequest.appearance(),this,f);
            gallery.observeBody(f.runtime.observePreviewBody(a,()->displayKind(aSession), () -> gallery.confirm(f.runtime.previewBodySource(a))));
            aSession.ordinaryDisplay(()->true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY,()->true));
            var shown = main(f.view());
            var views = new ArrayList<ViewSpec>();
            var subscription = f.runtime.subscribe(ignored -> views.add(f.view())); views.clear();
            var callbacks = new ArrayList<java.util.function.Consumer<PreviewRenderer.BodyStatus>>();
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            EditorPreviewSession last = null;
            ViewSpec.Preview desired = null;
            var textures = new ArrayList<String>();
            for (UUID id : List.of(GalleryRuntimeSelectionTest.B,c,GalleryRuntimeSelectionTest.B)) {
                f.runtime.dispatchWidget(f.card(id),false,InteractionOrigin.POINTER);
                desired = f.runtime.galleryPreviewCandidate().orElseThrow();
                var request = load(f,cache,desired);
                textures.add(request.appearance().skin().location());
                var session = gallery.prepare(f.runtime.previewBodySource(desired),false,true, () -> true);
                session.displayAuthority(request.appearance(),this,f);
                String source = f.runtime.previewBodySource(desired);
                var callback = f.runtime.observePreviewBody(desired,()-> { reads.incrementAndGet(); return displayKind(session); }, () -> gallery.confirm(source));
                gallery.observeBody(callback);
                int before = reads.get();
                f.workerLane = true;
                try { callbacks.forEach(old -> old.accept(PreviewRenderer.BodyStatus.ORDINARY)); }
                finally { f.workerLane = false; }
                f.drain();
                assertEquals(before,reads.get(),"Superseded suppliers are not read, including queued callbacks");
                callbacks.add(callback); last = session;
            }
            assertNotEquals(textures.get(0),textures.get(1)); assertEquals(textures.get(0),textures.get(2));
            assertFalse(views.isEmpty());
            for (var view : views) assertEquals(shown,main(view));
            last.ordinaryDisplay(()->true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY,()->true));
            f.drain();
            assertEquals(desired.bodySource(),main(f.view()).bodySource());
            subscription.close();
        } finally { f.runtime.close(); }
    }

    @Test void retainedCapeControlsDoNotReadNewDesiredPresetDuringSameIdReload() throws Exception {
        var f = fixture(false);
        try {
            GalleryInspectLayersTest.ordinary(f);
            var shown = main(f.view());
            var mode = f.view().widget("gallery.preview_mode").orElseThrow();
            f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> new AccountState(old.schemaVersion(),
                    old.accountId(),old.skinAssets(),old.presets().stream().map(p -> p.id().equals(GalleryRuntimeSelectionTest.A)
                    ? new AppearancePreset(p.id(),p.name(),p.skin(),null,p.outerLayerVisibility(),p.createdAt(),p.updatedAt()) : p).toList(),old.updatedAt()));
            f.runtime.dispatchWidget("gallery.reset"); f.drain();
            assertNotEquals(shown.bodySource(),f.runtime.galleryPreviewCandidate().orElseThrow().bodySource());
            assertEquals(shown,main(f.view()));
            assertEquals(mode.bounds(),f.view().widget(mode.id()).orElseThrow().bounds(),"Retained cape controls belong to shown A");
        } finally { f.runtime.close(); }
    }

    @Test void retainedNativeAccessoriesDoNotReadSuccessorCapabilities() throws Exception {
        var f = fixture(false);
        try {
            f.operations.useCpmPresence(() -> true);
            GallerySneakyPreparationJoinTest.change(f, f.operations.enableProvider(
                    com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE,
                    com.naocraftlab.skins.core.provider.BuiltinProvider.CPM));
            f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> new AccountState(old.schemaVersion(),
                    old.accountId(), old.skinAssets(), old.presets().stream().map(p -> p.id().equals(GalleryRuntimeSelectionTest.A)
                    ? new AppearancePreset(p.id(),p.name(),p.skin(),null,p.outerLayerVisibility(),p.createdAt(),p.updatedAt()) : p).toList(),old.updatedAt()));
            f.runtime.dispatchWidget("gallery.reset"); f.drain();
            var environment = ClientRuntime.class.getDeclaredField("skinExtensionEnvironment"); environment.setAccessible(true);
            environment.set(f.runtime,new com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment(0,Map.of(),true));
            var a = main(f.view());
            var accessories = new PreviewAccessories(new UUID(0,71),1,PreviewAccessories.Availability.CONFIRMED,
                    true,true,PreviewRenderer.LiveReason.AVAILABLE);
            var old = f.runtime.observePreviewAccessories(a,()->accessories);
            GalleryInspectLayersTest.ordinary(f);
            var shown = main(f.view());
            assertTrue(shown.nativeAccessories());
            var mode = f.view().widget("gallery.preview_mode").orElseThrow();
            f.selectB();
            f.runtime.observePreviewAccessories(f.runtime.galleryPreviewCandidate().orElseThrow(),
                    ()->PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LOADING));
            old.accept(accessories);
            assertEquals(shown,main(f.view()),"Retained A owns native cape presence, not desired B");
            assertEquals(mode.bounds(),f.view().widget(mode.id()).orElseThrow().bounds());
        } finally { f.runtime.close(); }
    }

    private GalleryRuntimeSelectionTest fixture(boolean inline) throws Exception {
        var f = new GalleryRuntimeSelectionTest();
        f.directory = directory;
        f.inline = inline;
        var initialized = new java.util.concurrent.atomic.AtomicBoolean();
        f.layerController = Optional.of(new OuterLayerVisibilityController() {
            public OuterLayerVisibility current() { return OuterLayerVisibility.allVisible(); }
            public void applyDurable(OuterLayerVisibility value) {
                assertFalse(initialized.get(), "Selection must not write Options");
            }
        });
        f.setup();
        initialized.set(true);
        var adapter = new LibraryStorageAdapter(f.storage);
        var library = new LibraryService(adapter, adapter, GalleryApplyTest.CLOCK);
        var a = library.importSkin(GalleryRuntimeSelectionTest.ACCOUNT, "A source", SkinVariant.CLASSIC,
                SkinSource.IMPORTED, GalleryPreviewSourceJoinTest.png(0xFF225588)).asset();
        var b = library.importSkin(GalleryRuntimeSelectionTest.ACCOUNT, "B source", SkinVariant.SLIM,
                SkinSource.IMPORTED, GalleryPreviewSourceJoinTest.png(0xFF779922)).asset();
        f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> new AccountState(old.schemaVersion(),
                old.accountId(), old.skinAssets(), List.of(
                new AppearancePreset(GalleryRuntimeSelectionTest.A, "A", SkinReference.asset(a.id()), "cape-a",
                        OuterLayerVisibility.allVisible(), GalleryApplyTest.NOW, GalleryApplyTest.NOW),
                new AppearancePreset(GalleryRuntimeSelectionTest.B, "B", SkinReference.asset(b.id()), "cape-b",
                        OuterLayerVisibility.noneVisible(), GalleryApplyTest.NOW, GalleryApplyTest.NOW)), old.updatedAt()));
        f.runtime.dispatchWidget("gallery.reset");
        f.drain();
        return f;
    }

    private static ViewSpec.Preview main(ViewSpec view) {
        return view.previews().stream().filter(p -> p.id().equals("gallery.preview")).findFirst().orElseThrow();
    }

    private static PreviewRenderer.DisplayKind displayKind(EditorPreviewSession session) {
        var body = session.body();
        if (!body.current().getAsBoolean()) return PreviewRenderer.DisplayKind.UNCONFIRMED;
        return body.status() == PreviewRenderer.BodyStatus.ORDINARY
                ? PreviewRenderer.DisplayKind.ORDINARY : PreviewRenderer.DisplayKind.CPM;
    }

    private static PreviewRenderer.PreviewRequest load(GalleryRuntimeSelectionTest f, PreviewAssetCache<String> cache,
            ViewSpec.Preview preview) {
        String key = preview.id() + ":" + preview.imageRevision();
        cache.request(key, () -> f.runtime.loadSkinPreview(preview), () -> fail("Texture registration"));
        f.drain();
        cache.request(key, () -> f.runtime.loadSkinPreview(preview), () -> fail("Texture relookup"));
        var skin = cache.handle(key).orElseThrow();
        var appearance = new PreviewRenderer.PreviewAppearance(skin,
                preview.variant() == SkinVariant.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC,
                Optional.empty(), PreviewRenderer.CapeMode.OFF, preview.outerLayerVisibility(), false, false);
        return ViewHostPolicy.previewRequest(preview, Optional.of(appearance), Optional.empty()).orElseThrow();
    }
}

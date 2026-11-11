package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class GalleryPreviewInteractionJoinTest {
    @TempDir Path directory;

    GalleryRuntimeSelectionTest fixture(GalleryInspectLayersTest.Layers layers) throws Exception {
        var support = new GalleryInspectLayersTest(); support.directory = directory;
        var f = support.fixture(layers, true);
        f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> new AccountState(old.schemaVersion(), old.accountId(),
                old.skinAssets(), old.presets().stream().map(p -> p.id().equals(GalleryRuntimeSelectionTest.A)
                ? p.withOfflineCape(new LocalCapeReference(null, "b".repeat(64), true)) : p).toList(), old.updatedAt()));
        f.runtime.dispatchWidget("gallery.preset." + GalleryRuntimeSelectionTest.A + ".more");
        f.runtime.dispatchWidget("gallery.preset." + GalleryRuntimeSelectionTest.A + ".favorite"); f.drain();
        f.runtime.dispatchWidget("gallery.reset"); f.drain();
        return f;
    }

    @Test void retainedDisplayedLeftKeepsGlobalTransformLiveWhileDesiredSourceIsPending() throws Exception {
        var layers=new GalleryInspectLayersTest.Layers();var f=fixture(layers);
        try {
            GalleryInspectLayersTest.ordinary(f);
            var displayed=GalleryInspectLayersTest.main(f);
            f.selectB();
            var before=GalleryInspectLayersTest.main(f);
            var desired=f.runtime.galleryPreviewCandidate().orElseThrow();
            assertEquals(displayed.bodySource(),before.bodySource());
            assertNotEquals(before.bodySource(),desired.bodySource());
            assertEquals(java.util.Optional.of(GalleryRuntimeSelectionTest.B),desired.presetId());
            var files=ScopedEditorSaveTest.inventory(directory);int writes=layers.writes;
            var b=before.anchorBounds();double x=b.x()+b.width()/2.0,y=b.y()+b.height()/2.0;
            f.runtime.pointerPressed(x,y,0);f.runtime.pointerDragged(x+10,y-10,0,10,-10);f.runtime.pointerReleased(0);
            var rotated=GalleryInspectLayersTest.main(f);
            assertEquals(before.yawDegrees()+8,rotated.yawDegrees(),0.001,"retained display must not freeze global rotation");
            assertEquals(before.pitchDegrees()+6,request(rotated).pitchDegrees(),0.001);
            f.runtime.pointerScrolled(x,y,0,1);
            var zoomed=GalleryInspectLayersTest.main(f);
            assertTrue(request(zoomed).scale()>request(rotated).scale());
            assertEquals(zoomed.yawDegrees(),f.runtime.galleryPreviewCandidate().orElseThrow().yawDegrees());
            assertEquals(displayed.bodySource(),zoomed.bodySource());
            assertEquals(desired.bodySource(),f.runtime.galleryPreviewCandidate().orElseThrow().bodySource());
            assertFalse(f.view().widget("gallery.outer_layer.head").orElseThrow().activationEnabled());
            f.runtime.dispatchWidget("gallery.outer_layer.head",false,InteractionOrigin.POINTER);
            assertEquals(desired.outerLayerVisibility(),f.runtime.galleryPreviewCandidate().orElseThrow().outerLayerVisibility());
            assertEquals(writes,layers.writes);assertEquals(files,ScopedEditorSaveTest.inventory(directory));
        } finally {f.runtime.close();}
    }

    @Test void pendingRetainedProjectionSurvivesLeftScrollResizeClamp() throws Exception {
        var f = fixture(new GalleryInspectLayersTest.Layers());
        try {
            GalleryInspectLayersTest.ordinary(f);
            var displayed = GalleryInspectLayersTest.main(f);
            f.runtime.view(240,150,0,0);
            f.runtime.nativeScrollPositionChanged("gallery.stage", Double.MAX_VALUE);
            var small = f.runtime.view(240,150,0,0);
            assertTrue(small.scrollSurface("gallery.stage").orElseThrow().offsetPixels() > 0);
            f.selectB();
            var desired = f.runtime.galleryPreviewCandidate().orElseThrow();
            assertNotEquals(displayed.bodySource(), desired.bodySource());
            var resized = f.runtime.view(854,480,0,0);
            assertEquals(displayed.bodySource(), resized.previews().get(0).bodySource(), "COMMON-03 retained body lost by clamp");
            assertEquals(displayed.outerLayerVisibility(), resized.previews().get(0).outerLayerVisibility());
            assertFalse(resized.widget("gallery.outer_layer.head").orElseThrow().activationEnabled());
            GalleryInspectLayersTest.ordinary(f);
            assertEquals(desired.bodySource(), GalleryInspectLayersTest.main(f).bodySource());
            assertTrue(f.view().widget("gallery.outer_layer.head").orElseThrow().activationEnabled());
        } finally { f.runtime.close(); }
    }

    @Test void pointerTransformAndIndependentSurfacesReachRequestsAndRespectLimits() throws Exception {
        var layers = new GalleryInspectLayersTest.Layers(); var f = fixture(layers); int writes = layers.writes;
        try {
            var initial = GalleryInspectLayersTest.main(f); var b = initial.anchorBounds();
            var inventory = ScopedEditorSaveTest.inventory(directory);
            f.runtime.pointerPressed(b.x()+30, b.y()+40, 0);
            f.runtime.pointerDragged(b.x()+40, b.y()+50, 0, 10, -10);
            f.runtime.pointerReleased(0);
            var rotated = GalleryInspectLayersTest.main(f);
            assertEquals(initial.yawDegrees()+8, rotated.yawDegrees(), 0.001, "pointer must rotate Gallery request");
            assertEquals(initial.pitchDegrees()+6, rotated.pitchDegrees(), 0.001);
            assertEquals(rotated.yawDegrees(), request(rotated).yawDegrees());
            assertEquals(rotated.pitchDegrees(), request(rotated).pitchDegrees());
            f.runtime.pointerScrolled(b.x()+30,b.y()+40,0,100);
            assertEquals(PreviewInteractionModel.MAX_SCALE, GalleryInspectLayersTest.main(f).scale());
            assertEquals(PreviewInteractionModel.MAX_SCALE, request(GalleryInspectLayersTest.main(f)).scale());
            f.runtime.pointerScrolled(b.x()+30,b.y()+40,0,-100);
            assertEquals(PreviewInteractionModel.MIN_SCALE, GalleryInspectLayersTest.main(f).scale());
            GalleryInspectLayersTest.ordinary(f);
            var small = f.runtime.view(240,150,0,0); var left = small.scrollSurface("gallery.stage").orElseThrow();
            assertTrue(left.maximumPixels()>0);
            f.runtime.pointerScrolled(left.viewport().x()+3,left.viewport().y()+3,0,-0.5);
            var shifted = f.runtime.view(240,150,0,0);
            assertTrue(shifted.scrollSurface("gallery.stage").orElseThrow().offsetPixels()>0, "left column must scroll");
            int delta=(int)Math.round(shifted.scrollSurface("gallery.stage").orElseThrow().offsetPixels())-(int)Math.round(left.offsetPixels());
            assertEquals(small.previews().get(0).anchorBounds(),shifted.previews().get(0).anchorBounds());
            for(String id:java.util.List.of("gallery.outer_layer.head","gallery.outer_layer.body","gallery.outer_layer.legs","gallery.inspect.OFFLINE"))
                assertEquals(small.widget(id).orElseThrow().bounds().y()-delta,shifted.widget(id).orElseThrow().bounds().y());
            for(String id:java.util.List.of("header","footer"))
                assertEquals(small.panels().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(),
                        shifted.panels().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow());
            assertEquals(small.scrollSurface("gallery.cards").orElseThrow().offsetPixels(), shifted.scrollSurface("gallery.cards").orElseThrow().offsetPixels());
            f.runtime.nativeScrollPositionChanged("gallery.stage", Double.MAX_VALUE);
            var clamped = f.runtime.view(240,150,0,0).scrollSurface("gallery.stage").orElseThrow();
            assertEquals(clamped.maximumPixels(),clamped.offsetPixels());
            var right = f.runtime.view(240,150,0,0).scrollSurface("gallery.cards").orElseThrow();
            assertTrue(right.maximumPixels()>0);
            f.runtime.pointerScrolled(right.viewport().x()+2,right.viewport().y()+2,0,-0.5);
            var independent = f.runtime.view(240,150,0,0);
            assertTrue(independent.scrollSurface("gallery.cards").orElseThrow().offsetPixels()>0);
            assertEquals(clamped.offsetPixels(),independent.scrollSurface("gallery.stage").orElseThrow().offsetPixels());
            assertEquals(writes,layers.writes); assertEquals(inventory,ScopedEditorSaveTest.inventory(directory));
        } finally { f.runtime.close(); }
    }

    @Test void staleRotationBusyModeAndKeyboardStageRevealUseExistingAdmission() throws Exception {
        var layers = new GalleryInspectLayersTest.Layers(); var f = fixture(layers); int writes = layers.writes;
        try {
            var b = GalleryInspectLayersTest.main(f).anchorBounds();
            f.runtime.pointerPressed(b.x()+30,b.y()+40,0); f.selectB();
            var selected = GalleryInspectLayersTest.main(f);
            f.runtime.pointerDragged(b.x()+40,b.y()+50,0,20,20);
            assertEquals(selected.yawDegrees(), GalleryInspectLayersTest.main(f).yawDegrees());
            f.runtime.dispatchWidget("gallery.reset"); f.drain();
            f.runtime.dispatchWidget("gallery.reset");
            assertTrue(f.runtime.snapshot().busy());
            var busy = GalleryInspectLayersTest.main(f);
            f.runtime.dispatchWidget("gallery.preview_mode",false,InteractionOrigin.POINTER);
            f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE,"gallery.preview_mode");
            f.runtime.pointerScrolled(b.x()+30,b.y()+40,0,10);
            assertEquals(busy.capeMode(), GalleryInspectLayersTest.main(f).capeMode());
            assertEquals(busy.scale(), GalleryInspectLayersTest.main(f).scale());
            f.drain(); GalleryInspectLayersTest.ordinary(f);
            var small = f.runtime.view(240,150,0,0);
            assertTrue(small.scrollSurface("gallery.stage").orElseThrow().maximumPixels()>0);
            f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD,"gallery.outer_layer.legs");
            var revealed = f.runtime.view(240,150,0,0);
            assertEquals("gallery.inspect.OFFLINE", revealed.focusRequest().orElseThrow().widgetId());
            assertTrue(revealed.scrollSurface("gallery.stage").orElseThrow().offsetPixels()>0,"Tab must reveal left stage control");
            assertEquals(small.scrollSurface("gallery.cards").orElseThrow().offsetPixels(),revealed.scrollSurface("gallery.cards").orElseThrow().offsetPixels());
            assertEquals(writes,layers.writes);
        } finally { f.runtime.close(); }
    }

    @Test void admittedModeChangesRequestAndRevokesOldBodyWithoutOptionsOrStorageEffects() throws Exception {
        var layers = new GalleryInspectLayersTest.Layers(); var f = fixture(layers); int writes = layers.writes;
        try {
            var old = GalleryInspectLayersTest.ordinary(f);
            var before = GalleryInspectLayersTest.main(f);
            assertTrue(f.view().widget("gallery.preview_mode").orElseThrow().enabled());
            var inventory = ScopedEditorSaveTest.inventory(directory); var providers = f.runtime.snapshot().providers();
            f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, "gallery.preview_mode");
            var after = GalleryInspectLayersTest.main(f);
            assertNotEquals(before.capeMode(), after.capeMode(), "admitted mode must reach preview producer");
            assertNotEquals(before.bodySource(), after.bodySource(), "mode must retire captured native source");
            assertFalse(f.view().widget("gallery.outer_layer.head").orElseThrow().activationEnabled());
            old.accept(PreviewRenderer.BodyStatus.ORDINARY);
            assertFalse(f.view().widget("gallery.outer_layer.head").orElseThrow().activationEnabled());
            assertEquals(after.capeMode(), request(after).appearance().capeMode());
            assertTrue(f.view().widget("gallery.done").isPresent());
            assertEquals(writes, layers.writes); assertEquals(inventory, ScopedEditorSaveTest.inventory(directory));
            assertEquals(providers, f.runtime.snapshot().providers()); assertEquals(0, f.reconciliations.get());
            f.runtime.dispatchWidget("gallery.preview_mode");
            assertEquals(before.capeMode(), GalleryInspectLayersTest.main(f).capeMode());
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"layers", "mode", "header"})
    void disappearingPendingFocusMovesToNearestEligibleScopedSlot(String change) throws Exception {
        var layers = new GalleryInspectLayersTest.Layers(); var f = fixture(layers);
        try {
            GalleryInspectLayersTest.ordinary(f);
            f.runtime.view(240,150,0,0);
            f.runtime.nativeScrollPositionChanged("gallery.cards",0);
            var before = f.runtime.view(240, 150, 0, 0);
            String removed = switch (change) {
                case "layers" -> "gallery.outer_layer.head";
                case "mode" -> "gallery.preview_mode";
                default -> "gallery.favorites";
            };
            var ordered = before.navigationNodes().stream().filter(ViewSpec.NavigationNode::enabled)
                    .filter(n -> n.tabOrder() >= 0).sorted(java.util.Comparator.comparingInt(ViewSpec.NavigationNode::tabOrder)).toList();
            var old = before.navigationNode(removed).orElseThrow();
            int index = ordered.indexOf(old);
            assertTrue(index > 0);
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, ordered.get(index - 1).id()));
            assertEquals(removed, f.runtime.view(240, 150, 0, 0).focusRequest().orElseThrow().widgetId());
            var oldRequest = f.runtime.view(240, 150, 0, 0).focusRequest().orElseThrow();
            var inventory = ScopedEditorSaveTest.inventory(directory); int writes = layers.writes;
            var selected = f.runtime.snapshot().selectedPresetId();
            var mask = GalleryInspectLayersTest.main(f).outerLayerVisibility();
            switch (change) {
                case "layers" -> f.runtime.observePreviewBody(GalleryInspectLayersTest.main(f), () -> PreviewRenderer.DisplayKind.CPM)
                        .accept(PreviewRenderer.BodyStatus.READY);
                case "mode" -> f.runtime.dispatchWidget(f.card(GalleryRuntimeSelectionTest.B));
                default -> f.runtime.dispatchText("gallery.search", "B");
            }
            if (change.equals("mode")) GalleryInspectLayersTest.ordinary(f);
            var after = f.runtime.view(240, 150, 0, 0);
            assertTrue(after.navigationNode(removed).isEmpty());
            assertTrue(after.widget(removed).isEmpty());
            var expected = after.navigationNodes().stream().filter(ViewSpec.NavigationNode::enabled)
                    .filter(n -> n.tabOrder() >= 0)
                    .min(java.util.Comparator.comparingInt((ViewSpec.NavigationNode n) -> Math.abs(n.tabOrder() - old.tabOrder()))
                            .thenComparingInt(ViewSpec.NavigationNode::documentOrder)).orElseThrow();
            var request = after.focusRequest().orElseThrow();
            assertEquals(expected.id(), request.widgetId());
            assertEquals(change.equals("header") ? "gallery.your_appearances"
                    : change.equals("mode") ? "gallery.outer_layer.head" : "gallery.inspect.OFFLINE", request.widgetId());
            assertNotEquals(oldRequest.token(), request.token());
            f.runtime.acknowledgeFocusApplied(after.screenId(), oldRequest);
            assertEquals(request, f.runtime.view(240, 150, 0, 0).focusRequest().orElseThrow());
            assertTrue(after.widget(request.widgetId()).orElseThrow().enabled());
            var node = after.navigationNode(request.widgetId()).orElseThrow();
            node.surfaceId().flatMap(after::scrollSurface).ifPresent(surface -> {
                assertTrue(node.bounds().y() >= surface.viewport().y());
                assertTrue(node.bounds().bottom() <= surface.viewport().bottom());
            });
            f.runtime.acknowledgeFocusApplied(after.screenId(), request);
            assertTrue(f.runtime.view(240, 150, 0, 0).focusRequest().isEmpty());
            assertEquals(change.equals("mode") ? java.util.Optional.of(GalleryRuntimeSelectionTest.B) : selected, f.runtime.snapshot().selectedPresetId());
            assertEquals(change.equals("mode") ? OuterLayerVisibility.noneVisible() : mask, GalleryInspectLayersTest.main(f).outerLayerVisibility());
            assertEquals(writes, layers.writes); assertEquals(inventory, ScopedEditorSaveTest.inventory(directory));
            assertEquals(0, f.reconciliations.get());
        } finally { f.runtime.close(); }
    }

    @Test void noSelectedNativeAccessoryModeResetAndVisibilityNeverWriteOptions() throws Exception {
        var layers = new GalleryInspectLayersTest.Layers(); var support = new GalleryInspectLayersTest(); support.directory = directory;
        var f = support.fixture(layers, false);
        try {
            f.operations.useCpmPresence(() -> true);
            GallerySneakyPreparationJoinTest.change(f, f.operations.enableProvider(
                    com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE,
                    com.naocraftlab.skins.core.provider.BuiltinProvider.CPM));
            var environment = ClientRuntime.class.getDeclaredField("skinExtensionEnvironment"); environment.setAccessible(true);
            environment.set(f.runtime, new com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment(0, java.util.Map.of(), true));
            var capability = new PreviewAccessories(new java.util.UUID(0, 70), 0, PreviewAccessories.Availability.CONFIRMED,
                    true, true, PreviewRenderer.LiveReason.AVAILABLE);
            var oldPreview = GalleryInspectLayersTest.main(f);
            var accessoryCallback = f.runtime.observePreviewAccessories(oldPreview, () -> capability);
            var bodyCallback = GalleryInspectLayersTest.ordinary(f);
            assertTrue(f.view().widget("gallery.preview_mode").orElseThrow().enabled());
            var inventory = ScopedEditorSaveTest.inventory(directory); int writes = layers.writes;
            var before = GalleryInspectLayersTest.main(f);
            f.runtime.dispatchWidget("gallery.preview_mode",false,InteractionOrigin.POINTER);
            bodyCallback.accept(PreviewRenderer.BodyStatus.ORDINARY); accessoryCallback.accept(capability);
            assertFalse(f.view().widget("gallery.outer_layer.head").orElseThrow().activationEnabled());
            assertNotEquals(before.bodySource(),GalleryInspectLayersTest.main(f).bodySource());
            f.runtime.observePreviewAccessories(GalleryInspectLayersTest.main(f), () -> capability);
            assertEquals(before.capeMode() == PreviewRenderer.CapeMode.ELYTRA ? PreviewRenderer.CapeMode.CAPE
                    : PreviewRenderer.CapeMode.ELYTRA,GalleryInspectLayersTest.main(f).capeMode());
            var desired = GalleryInspectLayersTest.main(f).capeMode();
            f.runtime.dispatchWidget("gallery.reset"); f.drain();
            f.runtime.resourcesReloaded();
            assertEquals(writes,layers.writes);
            var afterFiles = ScopedEditorSaveTest.inventory(directory);
            String uiPath = directory.relativize(f.storage.layout().accountUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT)).toString();
            inventory.remove(uiPath); afterFiles.remove(uiPath);
            assertEquals(inventory, afterFiles);
            assertEquals(CapePreviewMode.valueOf(desired.name()), f.storage.loadUiPreferences(
                    GalleryRuntimeSelectionTest.ACCOUNT).preferences().preferredCapeMode());
            assertTrue(f.view().widget("gallery.done").isPresent());
        } finally { f.runtime.close(); }
    }

    @Test void dirtyDraftInspectionTransformAndModePreserveActualCommitPayload() throws Exception {
        var layers = new GalleryInspectLayersTest.Layers(); var f = fixture(layers);
        try {
            f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> new AccountState(old.schemaVersion(), old.accountId(), old.skinAssets(),
                    old.presets().stream().map(p -> p.id().equals(GalleryRuntimeSelectionTest.B)
                            ? p.withOfflineCape(new LocalCapeReference(null, "c".repeat(64), true)) : p).toList(), old.updatedAt()));
            f.runtime.dispatchWidget("gallery.reset"); f.drain(); f.selectB();
            f.drain();
            var original = f.storage.loadOrCreateAccount(GalleryRuntimeSelectionTest.ACCOUNT).presets().stream()
                    .filter(p -> p.id().equals(GalleryRuntimeSelectionTest.B)).findFirst().orElseThrow();
            GalleryInspectLayersTest.ordinary(f);
            f.runtime.dispatchWidget("gallery.outer_layer.head", false, InteractionOrigin.KEYBOARD);
            GalleryInspectLayersTest.ordinary(f);
            var dirty = GalleryInspectLayersTest.main(f);
            var desired = dirty.outerLayerVisibility();
            assertNotEquals(original.outerLayerVisibility(), desired);
            assertTrue(f.view().widget("gallery.apply").orElseThrow().enabled());
            var inventory = ScopedEditorSaveTest.inventory(directory); int writes = layers.writes;
            var bounds = dirty.anchorBounds();
            f.runtime.pointerPressed(bounds.x() + 30, bounds.y() + 40, 0);
            f.runtime.pointerDragged(bounds.x() + 40, bounds.y() + 50, 0, 10, -10); f.runtime.pointerReleased(0);
            f.runtime.pointerScrolled(bounds.x() + 30, bounds.y() + 40, 0, 1);
            var transformed = GalleryInspectLayersTest.main(f);
            assertNotEquals(dirty.yawDegrees(), transformed.yawDegrees());
            assertNotEquals(dirty.scale(), transformed.scale());
            assertTrue(f.view().widget("gallery.preview_mode").orElseThrow().enabled());
            f.runtime.dispatchWidget("gallery.preview_mode", false, InteractionOrigin.KEYBOARD);
            assertNotEquals(transformed.capeMode(), GalleryInspectLayersTest.main(f).capeMode());
            GalleryInspectLayersTest.ordinary(f);
            f.runtime.dispatchWidget("gallery.inspect.OFFLINE", false, InteractionOrigin.KEYBOARD);
            assertTrue(f.runtime.galleryPreviewCandidate().orElseThrow().bodySource().orElseThrow().inspectionIdentity().isPresent());
            assertEquals(desired, GalleryInspectLayersTest.main(f).outerLayerVisibility());
            assertEquals(inventory, ScopedEditorSaveTest.inventory(directory)); assertEquals(writes, layers.writes);
            var flowField = ClientRuntime.class.getDeclaredField("galleryFlow"); flowField.setAccessible(true);
            var flow = (GalleryFlow) flowField.get(f.runtime);
            var contextField = GalleryFlow.class.getDeclaredField("context"); contextField.setAccessible(true);
            var delegate = (GalleryFlow.Context) contextField.get(flow);
            var captured = new java.util.concurrent.atomic.AtomicReference<LibraryEditorPort.GalleryApplyRequest>();
            var observer = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{GalleryFlow.Context.class}, (proxy, method, args) -> {
                if (method.getName().equals("submitGalleryApply")) captured.set((LibraryEditorPort.GalleryApplyRequest) args[0]);
                try { return method.invoke(delegate, args); }
                catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            });
            contextField.set(flow, observer);
            f.runtime.dispatchWidget("gallery.apply", false, InteractionOrigin.KEYBOARD);
            var payload = captured.get(); assertNotNull(payload);
            assertEquals(original.id(), payload.presetId());
            assertEquals(new LibraryEditorPort.GalleryMaskPatch(original.outerLayerVisibility(), desired), payload.maskPatch().orElseThrow());
            f.drain();
            var saved = f.storage.loadOrCreateAccount(GalleryRuntimeSelectionTest.ACCOUNT).presets().stream()
                    .filter(p -> p.id().equals(original.id())).findFirst().orElseThrow();
            assertEquals(desired, saved.outerLayerVisibility()); assertEquals(original.skin(), saved.skin());
            assertEquals(original.capeId(), saved.capeId()); assertEquals(original.offlineCape(), saved.offlineCape());
            assertEquals(original.id(), f.storage.loadAppearance(GalleryRuntimeSelectionTest.ACCOUNT).activePresetId());
        } finally { f.runtime.close(); }
    }

    @Test void repeatedInspectReopenAndSourceChangeClearOverrideAndRejectOldAccessories() throws Exception {
        var layers = new GalleryInspectLayersTest.Layers(); var f = fixture(layers);
        try {
            var environment = ClientRuntime.class.getDeclaredField("skinExtensionEnvironment"); environment.setAccessible(true);
            environment.set(f.runtime, new com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment(0, java.util.Map.of(), true));
            var capability = new PreviewAccessories(new java.util.UUID(0, 70), 0, PreviewAccessories.Availability.CONFIRMED,
                    true, true, PreviewRenderer.LiveReason.AVAILABLE);
            var oldReads = new java.util.concurrent.atomic.AtomicInteger();
            var old = f.runtime.observePreviewAccessories(GalleryInspectLayersTest.main(f), () -> { oldReads.incrementAndGet(); return capability; });
            for (int i = 0; i < 3; i++) {
                f.runtime.dispatchWidget("gallery.inspect.OFFLINE", false, InteractionOrigin.KEYBOARD);
                assertTrue(GalleryInspectLayersTest.main(f).bodySource().orElseThrow().inspectionIdentity().isPresent());
                f.runtime.dispatchWidget("gallery.inspect.OFFLINE", false, InteractionOrigin.KEYBOARD);
                assertTrue(GalleryInspectLayersTest.main(f).bodySource().orElseThrow().inspectionIdentity().isEmpty());
            }
            var inventory = ScopedEditorSaveTest.inventory(directory); int writes = layers.writes;
            f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
            var reopened = GalleryInspectLayersTest.main(f);
            assertTrue(reopened.bodySource().orElseThrow().inspectionIdentity().isEmpty());
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            var current = f.runtime.observePreviewAccessories(reopened, () -> { reads.incrementAndGet(); return capability; });
            current.accept(capability); assertTrue(reads.get() > 0);
            int retiredReads = oldReads.get(); var before = GalleryInspectLayersTest.main(f);
            old.accept(capability);
            assertEquals(retiredReads, oldReads.get()); assertEquals(before, GalleryInspectLayersTest.main(f));
            f.runtime.dispatchWidget("gallery.inspect.OFFLINE", false, InteractionOrigin.KEYBOARD);
            f.selectB();
            var selected = GalleryInspectLayersTest.main(f);
            assertTrue(selected.bodySource().orElseThrow().inspectionIdentity().isEmpty());
            old.accept(capability); current.accept(capability);
            assertEquals(selected, GalleryInspectLayersTest.main(f));
            assertEquals(retiredReads, oldReads.get());
            assertEquals(writes, layers.writes); assertEquals(inventory, ScopedEditorSaveTest.inventory(directory));
            assertEquals(0, f.reconciliations.get());
        } finally { f.runtime.close(); }
    }

    private static PreviewRenderer.PreviewRequest request(ViewSpec.Preview preview) {
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:mode", 64, 64),
                SkinModel.CLASSIC, java.util.Optional.of(new TextureRegistry.TextureHandle("fixture:cape", 64, 32)), preview.capeMode(), preview.outerLayerVisibility());
        return ViewHostPolicy.previewRequest(preview, java.util.Optional.of(appearance), java.util.Optional.of(appearance)).orElseThrow();
    }
}

package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class GalleryInspectLayersTest {
    @TempDir Path directory;

    static final class Layers implements OuterLayerVisibilityController {
        OuterLayerVisibility value = OuterLayerVisibility.allVisible();
        int writes;
        public OuterLayerVisibility current() { return value; }
        public void applyDurable(OuterLayerVisibility next) { value = next; writes++; }
    }

    GalleryRuntimeSelectionTest fixture(Layers layers, boolean active) throws Exception {
        var f = new GalleryRuntimeSelectionTest(); f.directory = directory;
        f.layerController = Optional.ofNullable(layers); f.initiallyActive = active; f.setup(); return f;
    }

    static ViewSpec.Preview main(GalleryRuntimeSelectionTest f) {
        return f.view().previews().stream().filter(p -> p.id().equals("gallery.preview")).findFirst().orElseThrow();
    }

    static java.util.function.Consumer<PreviewRenderer.BodyStatus> ordinary(GalleryRuntimeSelectionTest f) {
        var preview = f.runtime.galleryPreviewCandidate().orElseThrow();
        var gallery = new EditorPreviewSession.Gallery();
        var session = gallery.prepare(f.runtime.previewBodySource(preview), false, true, () -> true);
        var callback = f.runtime.observePreviewBody(preview, () -> session.body().current().getAsBoolean()
                ? PreviewRenderer.DisplayKind.ORDINARY : PreviewRenderer.DisplayKind.UNCONFIRMED,
                () -> gallery.confirm(f.runtime.previewBodySource(preview)));
        gallery.observeBody(callback);
        session.ordinaryDisplay(() -> true).accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        return callback;
    }

    static void retained(GalleryRuntimeSelectionTest f, ViewSpec.Preview shown) {
        assertEquals(shown, main(f));
        for (String group : List.of("head", "body", "legs")) {
            var widget = f.view().widget("gallery.outer_layer." + group).orElseThrow();
            assertTrue(widget.enabled());
            assertFalse(widget.activationEnabled());
            var desired = f.runtime.galleryPreviewCandidate().orElseThrow();
            f.runtime.dispatchWidget(widget.id(), true, InteractionOrigin.POINTER);
            f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, widget.id());
            assertEquals(desired, f.runtime.galleryPreviewCandidate().orElseThrow());
        }
    }

    static void hidden(GalleryRuntimeSelectionTest f) {
        var v = f.view();
        assertTrue(v.texts().stream().noneMatch(t -> t.id().equals("gallery.layers")));
        assertTrue(v.widgets().stream().noneMatch(w -> w.id().startsWith("gallery.outer_layer.")));
        assertTrue(v.navigationNodes().stream().noneMatch(n -> n.id().startsWith("gallery.outer_layer.")));
    }

    GalleryRuntimeSelectionTest twoCapes(Layers layers, boolean selected) throws Exception {
        var f = fixture(layers, selected);
        var cape = new com.naocraftlab.skins.core.model.LocalCapeReference(null, "b".repeat(64), true);
        if (selected) {
            f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT, old -> new com.naocraftlab.skins.core.model.AccountState(
                    old.schemaVersion(), old.accountId(), old.skinAssets(), old.presets().stream().map(p ->
                    p.id().equals(GalleryRuntimeSelectionTest.A) ? new com.naocraftlab.skins.core.model.AppearancePreset(
                            p.id(), p.name(), p.skin(), "fixture-official", p.outerLayerVisibility(), p.createdAt(), p.updatedAt(), cape, p.favorite()) : p).toList(), old.updatedAt()));
            f.runtime.dispatchWidget("gallery.reset");
            f.drain();
        } else {
            var current = f.runtime.snapshot().providers();
            var channel = current.cape().select(current.cape().intentRevision() + 1,
                    new com.naocraftlab.skins.core.provider.ProviderCape("fixture-offline", cape.sha256(), true))
                    .observeMinecraft(new com.naocraftlab.skins.core.provider.ProviderCape("fixture-official", null, true));
            providers(f, new com.naocraftlab.skins.core.provider.AppearanceProviders(current.skin(), channel));
        }
        f.view(); f.drain(); ordinary(f);
        assertTrue(f.view().widget("gallery.inspect.OFFLINE").isPresent());
        assertTrue(f.view().widget("gallery.inspect.MINECRAFT").orElseThrow().activationEnabled());
        f.runtime.dispatchWidget("gallery.inspect.MINECRAFT");
        ordinary(f);
        assertEquals(Optional.of("MINECRAFT"), main(f).bodySource().orElseThrow().inspectionIdentity());
        assertEquals(Optional.of("fixture-official"), main(f).capeId());
        return f;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void accessoryFactsDoNotResolveComparisonScopes(boolean selected) throws Exception {
        var f = twoCapes(new Layers(), selected);
        var flowField = ClientRuntime.class.getDeclaredField("galleryFlow"); flowField.setAccessible(true);
        var flow = (GalleryFlow) flowField.get(f.runtime);
        var contextField = GalleryFlow.class.getDeclaredField("context"); contextField.setAccessible(true);
        var delegate = contextField.get(flow);
        try {
            var preview = f.runtime.galleryPreviewCandidate().orElseThrow();
            String input = flow.accessoryInput();
            var comparisonScopes = new java.util.concurrent.atomic.AtomicInteger();
            var directSources = new java.util.concurrent.atomic.AtomicInteger();
            contextField.set(flow, java.lang.reflect.Proxy.newProxyInstance(GalleryFlow.Context.class.getClassLoader(),
                    new Class<?>[]{GalleryFlow.Context.class}, (proxy, method, args) -> {
                        if (method.getName().equals("previewSource")) {
                            var callers = StackWalker.getInstance().walk(frames -> frames
                                    .filter(frame -> frame.getClassName().equals(GalleryFlow.class.getName()))
                                    .map(StackWalker.StackFrame::getMethodName).toList());
                            if (callers.contains("accessoryInput")) {
                                if (callers.contains("capeIdentityScope")) comparisonScopes.incrementAndGet();
                                else directSources.incrementAndGet();
                            }
                        }
                        try { return method.invoke(delegate, args); }
                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    }));
            for (int i = 0; i < 5; i++) assertEquals(input, flow.accessoryInput());
            assertTrue(directSources.get() >= 5, "Every accessory-facts read must retain its live source check");
            assertEquals(0, comparisonScopes.get(), "Accessory-facts identity must not rebuild visual comparison scopes");
            f.identity.set(new GameSessionTokenSource.SessionIdentity(new UUID(0, 987), "Replacement"));
            assertNotEquals(input, flow.accessoryInput());
            assertFalse(flow.accessoryStillCurrent(preview), "An unobserved session replacement must revoke the old accessory request");
        } finally {
            contextField.set(flow, delegate); f.runtime.close();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"MINECRAFT", "OFFLINE"})
    void semanticMembershipDoesNotRebuildNativeAccessoryInput(String inspected) throws Exception {
        var f = twoCapes(new Layers(), true);
        var flowField = ClientRuntime.class.getDeclaredField("galleryFlow"); flowField.setAccessible(true);
        var flow = (GalleryFlow) flowField.get(f.runtime);
        var contextField = GalleryFlow.class.getDeclaredField("context"); contextField.setAccessible(true);
        var delegate = contextField.get(flow);
        try {
            var environment = ClientRuntime.class.getDeclaredField("skinExtensionEnvironment"); environment.setAccessible(true);
            environment.set(f.runtime, new com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment(0, Map.of(), true));
            providers(f, f.runtime.snapshot().providers());
            var component = com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE;
            f.operations.useCpmPresence(() -> true);
            GallerySneakyPreparationJoinTest.change(f, f.operations.enableProvider(component,
                    com.naocraftlab.skins.core.provider.BuiltinProvider.CPM));
            var minecraft = com.naocraftlab.skins.core.provider.BuiltinProvider.MINECRAFT;
            if (inspected.equals("OFFLINE")) while (f.runtime.snapshot().providers().cape().order().indexOf(minecraft) > 0)
                GallerySneakyPreparationJoinTest.change(f, f.operations.moveProvider(component, minecraft, -1));
            ordinary(f);
            f.runtime.dispatchWidget("gallery.inspect." + inspected); ordinary(f);
            var preview = f.runtime.galleryPreviewCandidate().orElseThrow();
            assertEquals(Optional.of(inspected), preview.bodySource().orElseThrow().inspectionIdentity());
            assertTrue(f.runtime.snapshot().skinExtensionEnvironment().cpmPresent());
            assertTrue(f.runtime.snapshot().providers().cape().enabled(com.naocraftlab.skins.core.provider.BuiltinProvider.CPM));
            var nestedAccessoryReads = new java.util.concurrent.atomic.AtomicInteger();
            contextField.set(flow, java.lang.reflect.Proxy.newProxyInstance(GalleryFlow.Context.class.getClassLoader(),
                    new Class<?>[]{GalleryFlow.Context.class}, (proxy, method, args) -> {
                        if (method.getName().equals("previewSource")) {
                            var callers = StackWalker.getInstance().walk(frames -> frames
                                    .filter(frame -> frame.getClassName().equals(GalleryFlow.class.getName()))
                                    .map(StackWalker.StackFrame::getMethodName).toList());
                            if (callers.contains("inspectionIdentity") && callers.contains("accessoryInput"))
                                nestedAccessoryReads.incrementAndGet();
                        }
                        try { return method.invoke(delegate, args); }
                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    }));
            for (int i = 0; i < 5; i++) {
                assertTrue(flow.previewStillCurrent(preview));
                assertTrue(flow.accessoryStillCurrent(preview));
                var admitted = flow.admissionPreview().orElseThrow();
                assertEquals(preview.bodySource(), admitted.bodySource());
                assertEquals(preview.capeId(), admitted.capeId());
            }
            assertEquals(0, nestedAccessoryReads.get(), "Ordinary semantic membership must not recursively rebuild native accessory input");
            GallerySneakyPreparationJoinTest.change(f, f.operations.disableProvider(component,
                    com.naocraftlab.skins.core.provider.BuiltinProvider.valueOf(inspected)));
            assertFalse(flow.previewStillCurrent(preview));
            assertTrue(flow.admissionPreview().orElseThrow().bodySource().orElseThrow().inspectionIdentity().isEmpty());
        } finally {
            contextField.set(flow, delegate); f.runtime.close();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "true,false,false,POINTER", "true,false,true,POINTER", "true,false,false,KEYBOARD", "true,false,true,KEYBOARD",
            "false,false,false,POINTER", "false,false,true,POINTER", "false,false,false,KEYBOARD", "false,false,true,KEYBOARD",
            "true,true,false,POINTER", "true,true,true,POINTER", "true,true,false,KEYBOARD", "true,true,true,KEYBOARD",
            "false,true,false,POINTER", "false,true,true,POINTER", "false,true,false,KEYBOARD", "false,true,true,KEYBOARD"})
    void layerCyclesKeepExactInspectionAfterOwnAck(boolean selected, boolean elytra, boolean reverse, InteractionOrigin origin) throws Exception {
        var layers = new Layers(); var f = twoCapes(layers, selected);
        try {
            if (elytra) {
                assertTrue(f.view().widget("gallery.preview_mode").orElseThrow().activationEnabled());
                f.runtime.dispatchWidget("gallery.preview_mode"); f.drain(); ordinary(f);
            }
            var initial = main(f);
            var anchor = initial.anchorBounds();
            double x = anchor.x() + anchor.width() / 2.0, y = anchor.y() + anchor.height() / 2.0;
            f.runtime.pointerPressed(x, y, 0);
            f.runtime.pointerDragged(x + 13, y + 7, 0, 13, 7); f.runtime.pointerReleased(0);
            f.runtime.pointerScrolled(x, y, 0, 1.25);
            var transformed = main(f);
            assertNotEquals(initial.yawDegrees(), transformed.yawDegrees());
            assertNotEquals(initial.scale(), transformed.scale());
            assertEquals(elytra ? PreviewRenderer.CapeMode.ELYTRA : PreviewRenderer.CapeMode.CAPE, transformed.capeMode());
            var inventory = ScopedEditorSaveTest.inventory(directory);
            var providerState = f.runtime.snapshot().providers();
            var saved = f.storage.loadOrCreateAccount(GalleryRuntimeSelectionTest.ACCOUNT);
            var appearance = f.storage.loadAppearance(GalleryRuntimeSelectionTest.ACCOUNT);
            for (String group : List.of("head", "body", "legs")) {
                var start = main(f).outerLayerVisibility();
                int steps = group.equals("head") ? 2 : group.equals("body") ? 8 : 4;
                for (int step = 0; step < steps; step++) {
                    var before = main(f);
                    var old = ordinary(f);
                    String id = "gallery.outer_layer." + group;
                    assertTrue(f.view().widget(id).orElseThrow().activationEnabled());
                    var expected = EditorOuterLayerCycle.cycle(group, before.outerLayerVisibility(), reverse ? -1 : 1);
                    int writes = layers.writes;
                    if (origin.keyboard() && !reverse) assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, id));
                    else f.runtime.dispatchWidget(id, reverse, origin);
                    var desired = f.runtime.galleryPreviewCandidate().orElseThrow();
                    assertEquals(expected, desired.outerLayerVisibility());
                    assertEquals(Optional.of("MINECRAFT"), desired.bodySource().orElseThrow().inspectionIdentity());
                    assertEquals(before.capeId(), desired.capeId());
                    if (selected) assertEquals(before.bodySource().orElseThrow().draftGeneration() + 1,
                            desired.bodySource().orElseThrow().draftGeneration());
                    assertEquals(writes + (selected ? 0 : 1), layers.writes);
                    retained(f, before);
                    old.accept(PreviewRenderer.BodyStatus.ORDINARY);
                    retained(f, before);
                    ordinary(f);
                    for (int lookup = 0; lookup < 3; lookup++) {
                        var shown = main(f);
                        assertEquals(expected, shown.outerLayerVisibility());
                        assertEquals(desired.bodySource(), shown.bodySource());
                        assertEquals(Optional.of("fixture-official"), shown.capeId());
                        assertEquals(Optional.of("active"), f.view().widget("gallery.inspect.MINECRAFT").orElseThrow().value());
                        assertEquals(transformed.yawDegrees(), shown.yawDegrees());
                        assertEquals(transformed.pitchDegrees(), shown.pitchDegrees());
                        assertEquals(transformed.scale(), shown.scale());
                        assertEquals(transformed.capeMode(), shown.capeMode());
                    }
                    assertEquals(inventory, ScopedEditorSaveTest.inventory(directory));
                    assertEquals(saved, f.storage.loadOrCreateAccount(GalleryRuntimeSelectionTest.ACCOUNT));
                    assertEquals(appearance, f.storage.loadAppearance(GalleryRuntimeSelectionTest.ACCOUNT));
                    assertEquals(providerState, f.runtime.snapshot().providers());
                    assertEquals(0, f.reconciliations.get());
                    assertEquals(!selected || expected.equals(OuterLayerVisibility.allVisible()), f.view().widget("gallery.done").isPresent());
                }
                assertEquals(start, main(f).outerLayerVisibility());
            }
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"source", "missing", "policy", "order", "world", "resources", "account", "reopen"})
    void maskInspectionCannotSurviveRealInvalidationOrOldCompletion(String boundary) throws Exception {
        var layers = new Layers(); var f = twoCapes(layers, false);
        try {
            var old = ordinary(f);
            f.runtime.dispatchWidget("gallery.outer_layer.body"); ordinary(f);
            assertEquals(Optional.of("MINECRAFT"), main(f).bodySource().orElseThrow().inspectionIdentity());
            var before = ScopedEditorSaveTest.inventory(directory);
            int writes = layers.writes;
            var current = f.runtime.snapshot().providers();
            var channel = current.cape();
            switch (boundary) {
                case "source" -> channel = channel.observeMinecraft(new com.naocraftlab.skins.core.provider.ProviderCape("replacement", null, true));
                case "missing" -> channel = channel.observeMinecraft(null);
                case "policy" -> channel = channel.disable(com.naocraftlab.skins.core.provider.BuiltinProvider.MINECRAFT);
                case "order" -> channel = channel.move(com.naocraftlab.skins.core.provider.BuiltinProvider.MINECRAFT, -1);
                case "world" -> f.runtime.importWorldChanged();
                case "resources" -> f.runtime.resourcesReloaded();
                case "account" -> f.identity.set(new GameSessionTokenSource.SessionIdentity(new UUID(0, 909), "Other"));
                case "reopen" -> { f.runtime.closeScreen(); f.runtime.reopen(); }
            }
            if (!channel.equals(current.cape())) providers(f, new com.naocraftlab.skins.core.provider.AppearanceProviders(current.skin(), channel));
            old.accept(PreviewRenderer.BodyStatus.ORDINARY);
            f.runtime.dispatchWidget("gallery.outer_layer.head", true, InteractionOrigin.POINTER);
            f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, "gallery.outer_layer.head");
            assertEquals(writes, layers.writes);
            if (boundary.equals("account")) {
                f.runtime.tick();
                f.identity.set(new GameSessionTokenSource.SessionIdentity(GalleryRuntimeSelectionTest.ACCOUNT, "Fixture"));
            }
            f.drain();
            for (int i = 0; i < 3; i++) {
                f.view();
                assertTrue(f.runtime.galleryPreviewCandidate().stream().allMatch(p -> p.bodySource().orElseThrow().inspectionIdentity().isEmpty()));
                old.accept(PreviewRenderer.BodyStatus.ORDINARY);
            }
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            assertEquals(writes, layers.writes);
        } finally { f.runtime.close(); }
    }

    @Test void applyMaskDoesNotCommitInspectedCape() throws Exception {
        var layers = new Layers(); var f = twoCapes(layers, true);
        try {
            var saved = f.storage.loadOrCreateAccount(GalleryRuntimeSelectionTest.ACCOUNT).presets().get(0);
            f.runtime.dispatchWidget("gallery.outer_layer.body"); ordinary(f);
            var mask = main(f).outerLayerVisibility();
            assertEquals(Optional.of("fixture-official"), main(f).capeId());
            int writes = layers.writes;
            f.runtime.dispatchWidget("gallery.apply"); f.drain();
            var committed = f.storage.loadOrCreateAccount(GalleryRuntimeSelectionTest.ACCOUNT).presets().stream()
                    .filter(p -> p.id().equals(saved.id())).findFirst().orElseThrow();
            assertEquals(mask, committed.outerLayerVisibility());
            assertEquals(saved.capeId(), committed.capeId());
            assertEquals(saved.offlineCape(), committed.offlineCape());
            assertEquals(mask, layers.value);
            assertEquals(writes + 1, layers.writes);
        } finally { f.runtime.close(); }
    }

    @Test void selectedLayersRequireOwnDrawAndOnlyChangeDraftUntilApply() throws Exception {
        var layers = new Layers(); var f = fixture(layers, true);
        try {
            hidden(f); ordinary(f);
            assertTrue(f.view().widget("gallery.outer_layer.body").orElseThrow().enabled());
            var before = ScopedEditorSaveTest.inventory(directory); int writes = layers.writes;
            var shownBeforeMask = main(f);
            f.runtime.dispatchWidget("gallery.outer_layer.body");
            assertEquals(EditorOuterLayerCycle.cycle("body", OuterLayerVisibility.allVisible(), 1), f.runtime.galleryPreviewCandidate().orElseThrow().outerLayerVisibility());
            assertTrue(f.view().widget("gallery.apply").isPresent());
            assertEquals(before, ScopedEditorSaveTest.inventory(directory)); assertEquals(writes,layers.writes);
            retained(f, shownBeforeMask); ordinary(f);
            f.runtime.dispatchWidget("gallery.outer_layer.body", true, InteractionOrigin.KEYBOARD);
            ordinary(f);
            assertEquals(OuterLayerVisibility.allVisible(), main(f).outerLayerVisibility());
            assertTrue(f.view().widget("gallery.done").isPresent());
            var old = ordinary(f); var shown = main(f); f.selectB(); retained(f, shown);
            old.accept(PreviewRenderer.BodyStatus.ORDINARY); retained(f, shown);
            ordinary(f); f.runtime.dispatchWidget("gallery.outer_layer.head");
            ordinary(f);
            assertEquals(EditorOuterLayerCycle.cycle("head", OuterLayerVisibility.noneVisible(), 1),main(f).outerLayerVisibility());
            assertEquals(writes,layers.writes); assertEquals(before,ScopedEditorSaveTest.inventory(directory));
        } finally { f.runtime.close(); }
    }

    @Test void noSelectedCyclesFreshOptionsImmediatelyAndReopenDoesNotUndoThem() throws Exception {
        var layers = new Layers(); var f = fixture(layers, false);
        try {
            assertTrue(f.runtime.snapshot().selectedPresetId().isEmpty());
            assertEquals(0,layers.writes); hidden(f); ordinary(f);
            assertTrue(f.view().widget("gallery.outer_layer.body").orElseThrow().enabled());
            var before = ScopedEditorSaveTest.inventory(directory);
            var providers = f.runtime.snapshot().providers();
            for (String group : List.of("head","body","legs")) for(boolean reverse : List.of(false,true)) {
                layers.value = reverse ? OuterLayerVisibility.noneVisible() : OuterLayerVisibility.allVisible();
                ordinary(f); var expected = EditorOuterLayerCycle.cycle(group,layers.value,reverse ? -1 : 1);
                int writes = layers.writes;
                f.runtime.dispatchWidget("gallery.outer_layer."+group,reverse,InteractionOrigin.KEYBOARD);
                assertEquals(expected,layers.value); assertEquals(writes+1,layers.writes);
                ordinary(f);
                assertEquals(expected,main(f).outerLayerVisibility());
                assertTrue(f.view().widget("gallery.done").isPresent());
                assertTrue(f.view().widget("gallery.apply").isEmpty());
                assertEquals(providers,f.runtime.snapshot().providers());
                assertEquals(before,ScopedEditorSaveTest.inventory(directory)); assertEquals(0,f.reconciliations.get());
            }
            var saved=layers.value; int writes=layers.writes;
            f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
            assertEquals(saved,layers.value); assertEquals(writes,layers.writes); hidden(f);
            ordinary(f); var shown = main(f); f.selectB(); retained(f, shown); ordinary(f);
            f.runtime.dispatchWidget("gallery.outer_layer.body");
            ordinary(f);
            assertEquals(EditorOuterLayerCycle.cycle("body",OuterLayerVisibility.noneVisible(),1),main(f).outerLayerVisibility());
            assertEquals(saved,layers.value); assertEquals(writes,layers.writes);
        } finally { f.runtime.close(); }
    }

    @Test void firstProviderKnownNoneCannotBorrowLowerPriorityCape() throws Exception {
        var f=fixture(new Layers(),true);
        try {
            f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT,old->new com.naocraftlab.skins.core.model.AccountState(
                    old.schemaVersion(),old.accountId(),old.skinAssets(),old.presets().stream().map(p->p.id().equals(GalleryRuntimeSelectionTest.B)
                    ? new com.naocraftlab.skins.core.model.AppearancePreset(p.id(),p.name(),p.skin(),"official-b",p.outerLayerVisibility(),p.createdAt(),p.updatedAt()) :p).toList(),old.updatedAt()));
            f.selectB(); f.runtime.dispatchWidget("gallery.reset");f.drain();f.selectB();
            assertEquals(Optional.of("official-b"),main(f).capeId());
            var before=ScopedEditorSaveTest.inventory(directory);
            assertTrue(f.view().widget("gallery.inspect.OFFLINE").isEmpty());
            f.runtime.dispatchWidget("gallery.inspect.OFFLINE");
            assertEquals(Optional.of("official-b"), main(f).capeId()); assertFalse(main(f).nativeAccessories());
            f.runtime.dispatchWidget("gallery.inspect.MINECRAFT");
            assertEquals(Optional.of("official-b"),main(f).capeId());
            assertEquals(before,ScopedEditorSaveTest.inventory(directory));
        } finally { f.runtime.close(); }
    }

    @Test void noControllerHasNoSectionOrHitTooltipFocusOrLayoutReservationEvenAfterOrdinaryDisplay() throws Exception {
        var f=fixture(null,true);
        try {
            var initial=f.view(); hidden(f); ordinary(f); hidden(f);
            var next=f.view();
            assertEquals(initial.widgets().stream().filter(w->w.id().startsWith("gallery.inspect.")).map(ViewSpec.Widget::bounds).toList(),
                    next.widgets().stream().filter(w->w.id().startsWith("gallery.inspect.")).map(ViewSpec.Widget::bounds).toList());
            var before=ScopedEditorSaveTest.inventory(directory);
            for(String group:List.of("head","body","legs")) {
                f.runtime.dispatchWidget("gallery.outer_layer."+group,true,InteractionOrigin.POINTER);
                f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE,"gallery.outer_layer."+group);
            }
            assertEquals(before,ScopedEditorSaveTest.inventory(directory));
            assertEquals(OuterLayerVisibility.allVisible(),main(f).outerLayerVisibility());
        } finally { f.runtime.close(); }
    }

    @Test void selectedMaskApplyReachesControllerOnlyAtLocalCommit() throws Exception {
        var layers=new Layers();var f=fixture(layers,false);
        try {
            f.selectB();ordinary(f);
            f.runtime.dispatchWidget("gallery.outer_layer.body");
            var expected=f.runtime.galleryPreviewCandidate().orElseThrow().outerLayerVisibility();assertEquals(0,layers.writes);
            assertEquals(OuterLayerVisibility.noneVisible(),f.storage.loadOrCreateAccount(GalleryRuntimeSelectionTest.ACCOUNT)
                    .presets().stream().filter(p->p.id().equals(GalleryRuntimeSelectionTest.B)).findFirst().orElseThrow().outerLayerVisibility());
            f.runtime.dispatchWidget("gallery.apply");f.drain();
            assertEquals(expected,layers.value);assertEquals(1,layers.writes);
            assertEquals(expected,f.storage.loadOrCreateAccount(GalleryRuntimeSelectionTest.ACCOUNT).presets().stream()
                    .filter(p->p.id().equals(GalleryRuntimeSelectionTest.B)).findFirst().orElseThrow().outerLayerVisibility());
        } finally { f.runtime.close(); }
    }

    @Test void fallbackUnknownIsNotKnownNoneAndReadOnlyProvidersNeverGainInspect() throws Exception {
        var f=fixture(new Layers(),false);
        try {
            var current=f.runtime.snapshot().providers();
            var channel=com.naocraftlab.skins.core.provider.ProviderChannel.<com.naocraftlab.skins.core.provider.ProviderCape>initial()
                    .enable(com.naocraftlab.skins.core.provider.BuiltinProvider.OPTIFINE).observeOptifine(
                            new com.naocraftlab.skins.core.provider.ProviderCape("foreign","f".repeat(64),true));
            providers(f,new com.naocraftlab.skins.core.provider.AppearanceProviders(current.skin(),channel));
            assertTrue(f.view().widgets().stream().noneMatch(w->w.id().startsWith("gallery.inspect.")));
            channel=channel.observeMinecraft(null);
            providers(f,new com.naocraftlab.skins.core.provider.AppearanceProviders(current.skin(),channel));
            assertEquals(List.of("gallery.inspect.none"),f.view().widgets().stream()
                    .filter(w->w.id().startsWith("gallery.inspect.")).map(ViewSpec.Widget::id).toList());
            var before=ScopedEditorSaveTest.inventory(directory); var state=f.runtime.snapshot().providers();
            f.runtime.dispatchWidget("gallery.inspect.none");
            assertTrue(main(f).capeId().isEmpty()); assertFalse(main(f).nativeAccessories());
            assertEquals(state,f.runtime.snapshot().providers()); assertEquals(before,ScopedEditorSaveTest.inventory(directory));
            f.runtime.resourcesReloaded();
            assertTrue(main(f).bodySource().orElseThrow().inspectionIdentity().isEmpty());
        } finally { f.runtime.close(); }
    }

    @Test void busyCpmInvalidationAndMissingControllerRejectPointerAndKeyboardWithoutEffects() throws Exception {
        var layers=new Layers(); var f=fixture(layers,true);
        try {
            ordinary(f); var control=f.view().widget("gallery.outer_layer.body").orElseThrow();
            var before=ScopedEditorSaveTest.inventory(directory); int writes=layers.writes;
            var callback=f.runtime.observePreviewBody(main(f),()->PreviewRenderer.DisplayKind.CPM);
            callback.accept(PreviewRenderer.BodyStatus.READY); hidden(f);
            f.runtime.dispatchWidget(control.id(),true,InteractionOrigin.POINTER);
            f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE,control.id());
            assertEquals(OuterLayerVisibility.allVisible(),main(f).outerLayerVisibility());
            ordinary(f); f.runtime.dispatchWidget("gallery.reset");
            assertTrue(f.runtime.snapshot().busy());
            assertFalse(f.view().widget(control.id()).orElseThrow().enabled());
            f.runtime.dispatchWidget(control.id(),false,InteractionOrigin.POINTER);
            f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE,control.id());
            assertEquals(OuterLayerVisibility.allVisible(),main(f).outerLayerVisibility());
            assertEquals(writes,layers.writes); assertEquals(before,ScopedEditorSaveTest.inventory(directory));
            f.drain(); ordinary(f); f.runtime.resourcesReloaded(); hidden(f);
            f.runtime.dispatchWidget(control.id());
            assertEquals(writes,layers.writes);
        } finally { f.runtime.close(); }
    }

    @Test void selectedBCapeAndElytraAreOwnNotActiveAAndNoneNeverBorrowsNative() throws Exception {
        var f=fixture(new Layers(),true);
        try {
            f.storage.updateAccount(GalleryRuntimeSelectionTest.ACCOUNT,old->new com.naocraftlab.skins.core.model.AccountState(
                    old.schemaVersion(),old.accountId(),old.skinAssets(),old.presets().stream().map(p->p.id().equals(GalleryRuntimeSelectionTest.B)
                    ? p.withOfflineCape(new com.naocraftlab.skins.core.model.LocalCapeReference(null,"b".repeat(64),false)):p).toList(),old.updatedAt()));
            f.selectB(); f.runtime.dispatchWidget("gallery.reset"); f.drain(); f.selectB();
            assertEquals(Optional.of("provider:cape:"+"b".repeat(64)),main(f).capeId());
            assertFalse(main(f).capeHasElytra());
            var before=ScopedEditorSaveTest.inventory(directory); var state=f.runtime.snapshot().providers();
            assertTrue(f.view().widget("gallery.inspect.MINECRAFT").isEmpty());
            f.runtime.dispatchWidget("gallery.inspect.MINECRAFT");
            assertEquals(Optional.of("provider:cape:"+"b".repeat(64)), main(f).capeId()); assertFalse(main(f).nativeAccessories());
            f.runtime.dispatchWidget("gallery.inspect.OFFLINE");
            assertEquals(Optional.of("provider:cape:"+"b".repeat(64)),main(f).capeId());
            assertFalse(main(f).capeHasElytra());
            assertEquals(state,f.runtime.snapshot().providers()); assertEquals(before,ScopedEditorSaveTest.inventory(directory));
            f.runtime.dispatchWidget("gallery.inspect.MINECRAFT");
            f.runtime.dispatchWidget("gallery.reset"); f.drain();
            assertTrue(main(f).bodySource().orElseThrow().inspectionIdentity().isEmpty());
        } finally { f.runtime.close(); }
    }

    static void providers(GalleryRuntimeSelectionTest f, com.naocraftlab.skins.core.provider.AppearanceProviders value) throws Exception {
        var field=ClientRuntime.class.getDeclaredField("state");field.setAccessible(true);var state=field.get(f.runtime);
        var providers=state.getClass().getDeclaredField("providers");providers.setAccessible(true);providers.set(state,value);
        var publish=ClientRuntime.class.getDeclaredMethod("publish");publish.setAccessible(true);publish.invoke(f.runtime);
    }

    @Test void selectedCapeRowsAreWritablePriorityKnownNoneAndInspectDoesNotCommit() throws Exception {
        var f=fixture(new Layers(),true);
        try {
            var rows=f.view().widgets().stream().filter(w->w.id().startsWith("gallery.inspect.")).toList();
            assertEquals(List.of("gallery.inspect.none"),rows.stream().map(ViewSpec.Widget::id).toList());
            assertTrue(rows.stream().allMatch(ViewSpec.Widget::enabled));
            var before=ScopedEditorSaveTest.inventory(directory); var providers=f.runtime.snapshot().providers();
            var original=main(f); String source=f.runtime.previewBodySource(original);
            ordinary(f); f.runtime.dispatchWidget("gallery.inspect.none");
            assertNotEquals(source,f.runtime.previewBodySource(f.runtime.galleryPreviewCandidate().orElseThrow()));
            retained(f, original); ordinary(f);
            assertTrue(main(f).capeId().isEmpty()); assertFalse(main(f).nativeAccessories());
            assertEquals(providers,f.runtime.snapshot().providers()); assertEquals(before,ScopedEditorSaveTest.inventory(directory));
            assertTrue(f.view().widget("gallery.done").isPresent()); assertEquals(0,f.reconciliations.get());
            f.runtime.dispatchWidget("gallery.inspect.none");
            ordinary(f);
            assertEquals(original.capeId(),main(f).capeId());
            f.runtime.dispatchWidget("gallery.inspect.none"); f.selectB();
            assertTrue(f.runtime.galleryPreviewCandidate().orElseThrow().bodySource().orElseThrow().inspectionIdentity().isEmpty());
        } finally { f.runtime.close(); }
    }
}

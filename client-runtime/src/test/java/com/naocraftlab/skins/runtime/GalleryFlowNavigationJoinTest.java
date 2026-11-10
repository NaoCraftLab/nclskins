package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.naocraftlab.skins.runtime.GalleryRuntimeSelectionTest.*;
import static com.naocraftlab.skins.runtime.GalleryInspectLayersTest.*;

final class GalleryFlowNavigationJoinTest {
    @TempDir Path directory;

    GalleryRuntimeSelectionTest fixture() throws Exception {
        var f = new GalleryRuntimeSelectionTest();
        f.directory = directory;
        f.setup();
        return f;
    }

    void tabCycle(GalleryRuntimeSelectionTest f, boolean reverse) throws Exception {
        var nodes = f.view().navigationNodes().stream().filter(ViewSpec.NavigationNode::enabled)
                .filter(n -> n.tabOrder() >= 0).sorted(Comparator.comparingInt(ViewSpec.NavigationNode::tabOrder)
                        .thenComparingInt(ViewSpec.NavigationNode::documentOrder)).map(ViewSpec.NavigationNode::id).toList();
        assertEquals(nodes.size(), new HashSet<>(nodes).size());
        String focused = null;
        for (int i = 0; i < nodes.size(); i++) {
            assertTrue(f.runtime.dispatchNavigation(reverse ? ViewSpec.NavigationCommand.TAB_BACKWARD
                    : ViewSpec.NavigationCommand.TAB_FORWARD, focused));
            var view = f.view();
            focused = view.focusRequest().orElseThrow().widgetId();
            assertEquals(nodes.get(reverse ? nodes.size()-1-i : i), focused);
            assertTrue(view.widget(focused).orElseThrow().enabled(), "Focused node must be materialized");
            var request = view.focusRequest().orElseThrow();
            f.runtime.acknowledgeFocusApplied(view.screenId(), request);
            assertTrue(f.view().focusRequest().isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"full", "saved", "partial", "sections"})
    void searchTabAfterWheelEntersFirstLogicalGroupWithoutSelectingDraft(String scenario) throws Exception {
        var f = fixture();
        try {
            int height = scenario.equals("partial") ? 150 : 292;
            f.storage.updateAccount(ACCOUNT, old -> {
                var presets = new ArrayList<>(old.presets());
                for (int i = 10; i < 90; i++) presets.add(new AppearancePreset(new UUID(0, i), "Grid " + i,
                        SkinReference.accountDefault(), null, OuterLayerVisibility.allVisible(),
                        GalleryApplyTest.NOW, GalleryApplyTest.NOW, null, scenario.equals("sections") && i == 10));
                return new AccountState(old.schemaVersion(), ACCOUNT, old.skinAssets(), presets, old.updatedAt());
            });
            f.selectB(); f.runtime.dispatchWidget("gallery.reset"); f.drain();
            var initial = f.runtime.view(427, height, 0, 0);
            var surface = initial.scrollSurface("gallery.cards").orElseThrow();
            assertTrue(surface.maximumPixels() > surface.viewport().height());
            f.runtime.pointerScrolled(surface.viewport().x() + 5, surface.viewport().y() + 5, 0, -5.137);
            f.runtime.tick();
            var scrolled = f.runtime.view(427, height, 0, 0);
            var beforeSurface = scrolled.scrollSurface("gallery.cards").orElseThrow();
            assertTrue(beforeSurface.offsetPixels() > 0);
            assertNotEquals(Math.rint(beforeSurface.offsetPixels()), beforeSurface.offsetPixels());
            var viewport = beforeSurface.viewport();
            var visible = scrolled.navigationNodes().stream()
                    .filter(n -> n.enabled() && n.pattern() == ViewSpec.NavigationPattern.GRID)
                    .filter(n -> AppearanceCollections.intersects(n.bounds(), viewport))
                    .sorted(Comparator.comparingInt(ViewSpec.NavigationNode::documentOrder)).toList();
            var full = visible.stream()
                    .filter(n -> n.bounds().y() >= viewport.y() && n.bounds().bottom() <= viewport.bottom()).toList();
            if (!scenario.equals("sections")) assertEquals(scenario.equals("partial"), full.isEmpty());
            var expected = full.isEmpty() ? visible.get(0) : full.get(0);
            if (scenario.equals("saved")) {
                assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.RIGHT, expected.id()));
                var rememberedView = f.runtime.view(427, height, 0, 0);
                var remembered = rememberedView.focusRequest().orElseThrow();
                expected = rememberedView.navigationNode(remembered.widgetId()).orElseThrow();
                assertNotEquals(visible.get(0).id(), expected.id());
                assertTrue(AppearanceCollections.intersects(expected.bounds(), viewport));
                f.runtime.acknowledgeFocusApplied(rememberedView.screenId(), remembered);
            }
            if (!scenario.equals("sections")) {
                assertNotEquals(f.card(A), expected.id());
                assertTrue(scrolled.widget(f.card(A)).isEmpty(), "Remembered active must be offscreen before dispatch");
            }
            var before = ScopedEditorSaveTest.inventory(directory);
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, "gallery.search"));
            var focused = f.runtime.view(427, height, 0, 0);
            assertEquals("gallery.sections", focused.focusRequest().orElseThrow().widgetId());
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD,"gallery.sections"));
            String header = scenario.equals("sections") ? "gallery.favorites" : "gallery.your_appearances";
            assertEquals(header,f.runtime.view(427,height,0,0).focusRequest().orElseThrow().widgetId());
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD,header));
            focused=f.runtime.view(427,height,0,0);
            expected=focused.navigationNode(f.card(scenario.equals("sections") ? new UUID(0,10) : A)).orElseThrow();
            assertEquals(expected.id(), focused.focusRequest().orElseThrow().widgetId());
            assertTrue(focused.scrollSurface("gallery.cards").orElseThrow().offsetPixels() < beforeSurface.offsetPixels());
            assertTrue(focused.widget(expected.id()).orElseThrow().enabled());
            var request = focused.focusRequest().orElseThrow();
            f.runtime.acknowledgeFocusApplied(focused.screenId(), request);
            assertTrue(f.runtime.view(427, height, 0, 0).focusRequest().isEmpty());
            assertEquals(A, f.runtime.snapshot().selectedPresetId().orElseThrow());
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            assertTrue(f.workers.isEmpty());
            assertEquals(0, f.reconciliations.get());
        } finally { f.runtime.close(); }
    }

    @Test void firstGroupRowReachesOwnDisclosureWithoutSelectingDraft() throws Exception {
        var f = fixture();
        try {
            f.storage.updateAccount(ACCOUNT, old -> {
                var ps = new ArrayList<>(old.presets()); ps.set(0,ps.get(0).withFavorite(true));
                return new AccountState(old.schemaVersion(),ACCOUNT,old.skinAssets(),ps,old.updatedAt());
            });
            f.selectB(); f.runtime.dispatchWidget("gallery.reset"); f.drain();
            var v=f.view(); var before=ScopedEditorSaveTest.inventory(directory);
            var first=v.navigationNodes().stream().filter(n -> n.id().startsWith("gallery.card."))
                    .filter(n -> n.documentOrder()>v.navigationNode("gallery.your_appearances").orElseThrow().documentOrder()).findFirst().orElseThrow();
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.UP,first.id()));
            assertEquals("gallery.your_appearances",f.view().focusRequest().orElseThrow().widgetId());
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.DOWN,"gallery.your_appearances"));
            assertEquals(first.id(),f.view().focusRequest().orElseThrow().widgetId());
            assertEquals(A,f.runtime.snapshot().selectedPresetId().orElseThrow());
            assertEquals(before,ScopedEditorSaveTest.inventory(directory));
        } finally {f.runtime.close();}
    }

    @Test void namebarSingleDispatchNeverSelectsDraftAndCapeModeReachesOtherCards() throws Exception {
        var f=fixture();
        try {
            f.storage.updateAccount(ACCOUNT, old -> new AccountState(old.schemaVersion(),ACCOUNT,old.skinAssets(),
                    old.presets().stream().map(p -> new AppearancePreset(p.id(),p.name(),p.skin(),"fixture-cape",
                            p.outerLayerVisibility(),p.createdAt(),p.updatedAt(),p.offlineCape(),p.favorite())).toList(),old.updatedAt()));
            f.selectB();f.runtime.dispatchWidget("gallery.reset");f.drain();
            var before=ScopedEditorSaveTest.inventory(directory); var initial=f.view();
            String prefix="gallery.preset."+B; var bar=initial.widget(prefix+".more").orElseThrow().bounds();
            var owner=ViewHostPolicy.pointerOwnerAt(initial,bar.x()+3,bar.y()+6).orElseThrow();
            assertEquals(prefix+".more",owner.id());
            f.runtime.dispatchWidget(owner.id(),false,InteractionOrigin.POINTER);
            assertTrue(f.view().widget(prefix+".favorite").isPresent());
            assertEquals(A,f.runtime.snapshot().selectedPresetId().orElseThrow());
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE,prefix+".more"));
            assertTrue(f.view().widget(prefix+".favorite").isEmpty());
            var prior=f.view().previews().stream().filter(p -> p.id().startsWith("gallery.preset.") && p.capeId().isPresent()).toList();
            assertFalse(prior.isEmpty());
            assertTrue(f.view().widget("gallery.preview_mode").orElseThrow().enabled());
            f.runtime.dispatchWidget("gallery.preview_mode");
            var after=f.view();
            for(var p:prior) {
                var next=after.previews().stream().filter(q -> q.id().equals(p.id())).findFirst().orElseThrow();
                assertNotEquals(p.capeMode(),next.capeMode());
                assertEquals(p.skin(),next.skin());assertEquals(p.outerLayerVisibility(),next.outerLayerVisibility());
                assertEquals(p.yawDegrees(),next.yawDegrees());assertEquals(p.pitchDegrees(),next.pitchDegrees());assertEquals(p.imageRevision(),next.imageRevision());
            }
            assertEquals(before,ScopedEditorSaveTest.inventory(directory));assertEquals(1,f.workers.size());
            f.drain();
            var afterFiles = ScopedEditorSaveTest.inventory(directory);
            String uiPath = directory.relativize(f.storage.layout().accountUiPreferences(ACCOUNT)).toString();
            before.remove(uiPath); afterFiles.remove(uiPath);
            assertEquals(before, afterFiles);
            assertEquals(com.naocraftlab.skins.core.model.CapePreviewMode.valueOf(
                    after.previews().stream().filter(p -> p.id().equals("gallery.preview")).findFirst().orElseThrow().capeMode().name()),
                    f.storage.loadUiPreferences(ACCOUNT).preferences().preferredCapeMode());
        } finally {f.runtime.close();}
    }

    @ParameterizedTest @ValueSource(strings = {"OFFLINE", "MINECRAFT"})
    void providerEditUsesFirstActualViewportForActiveReveal(String provider) throws Exception {
        var f = fixture();
        try {
            f.storage.updateAccount(ACCOUNT, old -> {
                var ps = new ArrayList<>(old.presets());
                for (int i = 10; i < 150; i++) ps.add(new AppearancePreset(new UUID(0,i), "Grid " + i,
                        SkinReference.accountDefault(), null, GalleryApplyTest.NOW, GalleryApplyTest.NOW));
                return new AccountState(old.schemaVersion(), ACCOUNT, old.skinAssets(), ps, old.updatedAt());
            });
            UUID activeId = new UUID(0,100);
            f.operations.usePreset(activeId);
            f.selectB(); f.runtime.dispatchWidget("gallery.reset"); f.drain();
            f.runtime.view(854,480,0,0);
            f.runtime.dispatchWidget("gallery.providers"); f.drain();
            var before = ScopedEditorSaveTest.inventory(directory);
            for (int entry = 0; entry < 2; entry++) {
                f.runtime.dispatchWidget("providers.edit." + provider, false, InteractionOrigin.KEYBOARD);
                var view = f.runtime.view(320,240,0,0);
                var surface = view.scrollSurface("gallery.cards").orElseThrow();
                var active = view.widget(f.card(activeId)).orElseThrow().bounds();
                assertTrue(active.y() >= surface.viewport().y());
                assertTrue(active.bottom() <= surface.viewport().bottom());
                f.runtime.pointerScrolled(surface.viewport().x()+5,surface.viewport().y()+5,0,-0.137);
                double offset = f.runtime.view(320,240,0,0).scrollSurface("gallery.cards").orElseThrow().offsetPixels();
                assertTrue(offset > surface.offsetPixels());
                assertNotEquals(Math.rint(offset),offset);
                assertEquals(offset,f.runtime.view(320,240,0,0).scrollSurface("gallery.cards").orElseThrow().offsetPixels());
                f.runtime.escapePressed(); f.runtime.view(854,480,0,0);
            }
            assertEquals(before,ScopedEditorSaveTest.inventory(directory));
            assertEquals(0,f.reconciliations.get());
        } finally { f.runtime.close(); }
    }

    @Test void explicitDraftSelectionStopsEntryRevealAcrossResize() throws Exception {
        var f=fixture();
        try {
            f.storage.updateAccount(ACCOUNT,old -> {
                var ps=new ArrayList<>(old.presets());
                for(int i=10;i<150;i++) ps.add(new AppearancePreset(new UUID(0,i),"Grid "+i,SkinReference.accountDefault(),null,
                        GalleryApplyTest.NOW,GalleryApplyTest.NOW));
                return new AccountState(old.schemaVersion(),ACCOUNT,old.skinAssets(),ps,old.updatedAt());
            });
            UUID active=new UUID(0,100);f.operations.usePreset(active);
            f.selectB();f.runtime.dispatchWidget("gallery.reset");f.drain();
            var first=f.runtime.view(854,480,0,0);
            var selected=first.widgets().stream().filter(w -> w.kind()==ViewSpec.WidgetKind.CATALOG_CARD
                    && !w.id().equals(f.card(active))).findFirst().orElseThrow();
            assertTrue(selected.enabled() && selected.visible());
            double offset=first.scrollSurface("gallery.cards").orElseThrow().offsetPixels();assertTrue(offset>0);
            var files=ScopedEditorSaveTest.inventory(directory);
            f.runtime.dispatchWidget(selected.id(),false,InteractionOrigin.POINTER);
            var resized=f.runtime.view(320,240,0,0);
            assertEquals(offset,resized.scrollSurface("gallery.cards").orElseThrow().offsetPixels(),"entry reveal must stop after explicit draft selection");
            assertEquals(selected.id(),f.card(f.runtime.snapshot().selectedPresetId().orElseThrow()));
            assertTrue(resized.widget("gallery.apply").orElseThrow().enabled());
            assertEquals(files,ScopedEditorSaveTest.inventory(directory));
        } finally {f.runtime.close();}
    }

    @Test void repeatedProviderEditRevealsActiveAfterWheelAndBack() throws Exception {
        var f=fixture();
        try {
            f.storage.updateAccount(ACCOUNT,old -> {
                var ps=new ArrayList<>(old.presets());
                for(int i=10;i<150;i++) ps.add(new AppearancePreset(new UUID(0,i),"Grid "+i,SkinReference.accountDefault(),null,
                        GalleryApplyTest.NOW,GalleryApplyTest.NOW));
                return new AccountState(old.schemaVersion(),ACCOUNT,old.skinAssets(),ps,old.updatedAt());
            });
            f.selectB();f.runtime.dispatchWidget("gallery.reset");f.drain();
            f.runtime.dispatchWidget("gallery.providers");f.drain();
            var before=ScopedEditorSaveTest.inventory(directory);
            for(int entry=0;entry<3;entry++) {
                f.runtime.dispatchWidget("providers.edit.OFFLINE",false,InteractionOrigin.KEYBOARD);
                var v=f.view();var surface=v.scrollSurface("gallery.cards").orElseThrow();
                assertTrue(surface.maximumPixels()>surface.viewport().height());
                var active=v.widget(f.card(A)).orElseThrow().bounds();
                assertTrue(active.y()>=surface.viewport().y());assertTrue(active.bottom()<=surface.viewport().bottom());
                assertEquals(A,f.runtime.snapshot().selectedPresetId().orElseThrow());
                f.runtime.pointerScrolled(surface.viewport().x()+3,surface.viewport().y()+3,0,-100);f.runtime.tick();
                assertTrue(f.view().widget(f.card(A)).isEmpty());
                f.runtime.escapePressed();assertEquals("providers",f.view().screenId());
            }
            assertEquals(before,ScopedEditorSaveTest.inventory(directory));assertTrue(f.workers.isEmpty());
        } finally {f.runtime.close();}
    }

    @Test void searchWithNoEligibleCardsKeepsDraftAndSkipsGridInBothDirections() throws Exception {
        var f = fixture();
        try {
            var before = ScopedEditorSaveTest.inventory(directory);
            f.runtime.dispatchText("gallery.search", "No matching preset");
            assertTrue(f.view().navigationNodes().stream().noneMatch(n -> n.pattern() == ViewSpec.NavigationPattern.GRID));
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_BACKWARD, "gallery.search"));
            assertEquals("gallery.add", f.view().focusRequest().orElseThrow().widgetId());
            f.runtime.acknowledgeFocusApplied("gallery", f.view().focusRequest().orElseThrow());
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, "gallery.search"));
            assertNotEquals(ViewSpec.NavigationPattern.GRID, f.view().navigationNode(f.view().focusRequest().orElseThrow().widgetId()).orElseThrow().pattern());
            assertEquals(A, f.runtime.snapshot().selectedPresetId().orElseThrow());
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            assertTrue(f.workers.isEmpty());
        } finally { f.runtime.close(); }
    }

    @Test void fullTabGraphAndDeleteCancelRoundTripReachEnabledControlsOnly() throws Exception {
        var f = fixture();
        try {
            f.selectB();
            String prefix = "gallery.preset." + B;
            f.runtime.dispatchWidget(prefix + ".more", false, InteractionOrigin.KEYBOARD);
            var before = ScopedEditorSaveTest.inventory(directory);
            tabCycle(f, false); tabCycle(f, true);
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, prefix + ".delete"));
            assertEquals(prefix + ".delete_cancel", f.view().focusRequest().orElseThrow().widgetId());
            assertFalse(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, f.card(A)));
            tabCycle(f, false); tabCycle(f, true);
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, prefix + ".delete_cancel"));
            assertEquals(prefix + ".delete", f.view().focusRequest().orElseThrow().widgetId());
            assertEquals(B, f.runtime.snapshot().selectedPresetId().orElseThrow());
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            assertTrue(f.workers.isEmpty());
        } finally { f.runtime.close(); }
    }

    @Test void spatialRaggedGridRevealsBothAxesAndRebuildsAfterResizeAndCollapse() throws Exception {
        var f = fixture();
        try {
            f.storage.updateAccount(ACCOUNT, old -> {
                var presets = new ArrayList<>(old.presets());
                for (int i=10;i<61;i++) presets.add(new AppearancePreset(new UUID(0,i), "Grid " + i,
                        SkinReference.accountDefault(), null, OuterLayerVisibility.allVisible(),
                        GalleryApplyTest.NOW, GalleryApplyTest.NOW, null, i == 10));
                return new AccountState(old.schemaVersion(), ACCOUNT, old.skinAssets(), presets, old.updatedAt());
            });
            f.selectB(); f.runtime.dispatchWidget("gallery.reset"); f.drain();
            var before = ScopedEditorSaveTest.inventory(directory);
            for (int width : List.of(854, 427, 320)) {
                var v = f.runtime.view(width,240,0,0);
                var grid = v.navigationNodes().stream().filter(n -> n.pattern() == ViewSpec.NavigationPattern.GRID).toList();
                assertTrue(v.scrollSurface("gallery.cards").orElseThrow().maximumPixels() > 0);
                for (var command : List.of(ViewSpec.NavigationCommand.RIGHT, ViewSpec.NavigationCommand.LEFT,
                        ViewSpec.NavigationCommand.DOWN, ViewSpec.NavigationCommand.UP)) {
                    int admitted = 0;
                    for (var node : grid) {
                        v = f.runtime.view(width,240,0,0);
                        var expected = ViewNavigationPolicy.target(v,node.id(),command);
                        if (expected.isEmpty()) continue;
                        assertTrue(f.runtime.dispatchNavigation(command,node.id()));
                        var focused = f.runtime.view(width,240,0,0);
                        assertEquals(expected.orElseThrow().id(), focused.focusRequest().orElseThrow().widgetId());
                        var actual = focused.navigationNode(expected.orElseThrow().id()).orElseThrow();
                        var viewport = focused.scrollSurface("gallery.cards").orElseThrow().viewport();
                        assertTrue(actual.bounds().y() >= viewport.y());
                        assertTrue(actual.bounds().bottom() <= viewport.bottom());
                        admitted++;
                    }
                    assertTrue(admitted > 0, command.toString());
                }
            }
            assertEquals(A,f.runtime.snapshot().selectedPresetId().orElseThrow());
            assertEquals(before,ScopedEditorSaveTest.inventory(directory));
            f.runtime.dispatchWidget("gallery.sections",false,InteractionOrigin.KEYBOARD); f.drain();
            assertTrue(f.view().navigationNodes().stream().noneMatch(n -> n.id().startsWith("gallery.card.")));
            tabCycle(f,false);
            assertFalse(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE,f.card(B)));
        } finally { f.runtime.close(); }
    }

    @ParameterizedTest @ValueSource(strings={"back","escape","providers","add","editor","duplicate"})
    void everyChildExitDiscardsDraftWithoutMaskTransfer(String exit) throws Exception {
        var f = new GalleryRuntimeSelectionTest(); f.directory=directory;
        var layers = new Layers(); f.layerController=Optional.of(layers); f.setup();
        try {
            f.selectB(); ordinary(f);
            f.runtime.dispatchWidget("gallery.outer_layer.head",false,InteractionOrigin.POINTER);
            ordinary(f);
            assertNotEquals(OuterLayerVisibility.noneVisible(), main(f).outerLayerVisibility());
            assertTrue(f.view().widget("gallery.apply").orElseThrow().enabled());
            var dirtyMask=main(f).outerLayerVisibility();
            var before=ScopedEditorSaveTest.inventory(directory);
            String prefix="gallery.preset."+B;
            switch(exit) {
                case "back" -> f.runtime.dispatchWidget("gallery.back",false,InteractionOrigin.KEYBOARD);
                case "escape" -> f.runtime.escapePressed();
                case "providers" -> f.runtime.dispatchWidget("gallery.providers",false,InteractionOrigin.KEYBOARD);
                case "add" -> f.runtime.dispatchWidget("gallery.add",false,InteractionOrigin.KEYBOARD);
                default -> {
                    f.runtime.dispatchWidget(prefix+".more",false,InteractionOrigin.KEYBOARD);
                    f.runtime.dispatchWidget(prefix+"."+(exit.equals("editor") ? "edit" : exit),false,InteractionOrigin.KEYBOARD);
                }
            }
            f.drain();
            switch(exit) {
                case "back", "escape" -> {
                    assertEquals(ClientSnapshot.Lifecycle.CLOSED,f.runtime.snapshot().lifecycle());
                    f.runtime.reopen(); f.drain();
                }
                case "providers" -> {
                    assertEquals("providers",f.view().screenId());
                    f.runtime.dispatchWidget("providers.back");
                }
                case "add" -> {
                    assertTrue(f.runtime.snapshot().addSource().isPresent());
                    f.runtime.escapePressed();
                }
                default -> {
                    assertTrue(f.runtime.snapshot().editor().isPresent());
                    assertEquals(OuterLayerVisibility.noneVisible(),f.runtime.snapshot().editor().orElseThrow().preview().outerLayerVisibility());
                    assertEquals(exit.equals("duplicate"),f.runtime.snapshot().editor().orElseThrow().originalPresetId().isEmpty());
                    f.runtime.escapePressed();
                }
            }
            f.drain();
            assertEquals("gallery",f.view().screenId());
            assertEquals(A,f.runtime.snapshot().selectedPresetId().orElseThrow());
            assertEquals(OuterLayerVisibility.allVisible(),main(f).outerLayerVisibility());
            assertTrue(f.view().widget("gallery.done").isPresent());
            assertTrue(f.view().widget("gallery.apply").isEmpty());
            assertEquals(before,ScopedEditorSaveTest.inventory(directory));
            assertEquals(0,f.reconciliations.get());
        } finally { f.runtime.close(); }
    }

    @Test void queuedMaskConflictStaysOpenAndResetLoadsLatestWithoutOptionsReplay() throws Exception {
        var f = new GalleryRuntimeSelectionTest(); f.directory=directory;
        var layers = new Layers(); f.layerController=Optional.of(layers); f.setup();
        try {
            f.selectB(); ordinary(f); f.runtime.dispatchWidget("gallery.outer_layer.head");
            var draft=main(f).outerLayerVisibility();
            f.runtime.dispatchWidget("gallery.apply");
            f.storage.updateAccount(ACCOUNT, old -> new AccountState(old.schemaVersion(),ACCOUNT,old.skinAssets(),
                    old.presets().stream().map(p -> p.id().equals(B) ? new AppearancePreset(B,"Latest",p.skin(),p.capeId(),
                            OuterLayerVisibility.allVisible(),p.createdAt(),p.updatedAt(),p.offlineCape(),true) : p).toList(),old.updatedAt()));
            var before=ScopedEditorSaveTest.inventory(directory);
            int writes=layers.writes;
            f.drain();
            assertEquals(ClientSnapshot.Lifecycle.READY,f.runtime.snapshot().lifecycle());
            assertEquals(UiMessage.Severity.ERROR,f.runtime.snapshot().status().severity());
            assertEquals(draft,main(f).outerLayerVisibility());
            assertEquals(B,f.runtime.snapshot().selectedPresetId().orElseThrow());
            assertEquals(A,f.storage.loadAppearance(ACCOUNT).activePresetId());
            assertEquals(before,ScopedEditorSaveTest.inventory(directory));
            assertEquals(writes,layers.writes); assertEquals(0,f.reconciliations.get());
            f.runtime.dispatchWidget("gallery.reset"); f.drain();
            assertEquals(A,f.runtime.snapshot().selectedPresetId().orElseThrow());
            assertTrue(f.runtime.snapshot().account().orElseThrow().presets().stream().filter(p -> p.id().equals(B)).findFirst().orElseThrow().favorite());
            assertEquals(before,ScopedEditorSaveTest.inventory(directory));
        } finally { f.runtime.close(); }
    }

    @ParameterizedTest @ValueSource(strings={"BEFORE_JOURNAL","AFTER_JOURNAL","AFTER_APPEARANCE"})
    void journalFailureKeepsDraftThenRealResetRecoversPairWithoutFalsePublish(String boundary) throws Exception {
        var armed=new java.util.concurrent.atomic.AtomicBoolean();
        var f = new GalleryRuntimeSelectionTest(); f.directory=directory;
        var layers=new Layers(); f.layerController=Optional.of(layers);
        f.storageFault=stage -> {
            if (stage.equals(boundary) && armed.compareAndSet(true,false)) throw new java.io.IOException("fixture journal fault");
        };
        f.setup();
        try {
            f.selectB(); ordinary(f); f.runtime.dispatchWidget("gallery.outer_layer.head");
            var draft=main(f).outerLayerVisibility(); int writes=layers.writes;
            armed.set(true); f.runtime.dispatchWidget("gallery.apply"); f.drain();
            assertFalse(armed.get());
            assertEquals(ClientSnapshot.Lifecycle.READY,f.runtime.snapshot().lifecycle());
            assertEquals(UiMessage.Severity.ERROR,f.runtime.snapshot().status().severity());
            assertEquals(A,f.runtime.snapshot().activePresetId().orElseThrow());
            assertEquals(draft,main(f).outerLayerVisibility());
            assertEquals(writes,layers.writes); assertEquals(0,f.reconciliations.get());
            f.runtime.dispatchWidget("gallery.reset"); f.drain();
            var expected=boundary.equals("BEFORE_JOURNAL") ? A : B;
            assertEquals(expected,f.storage.loadAppearance(ACCOUNT).activePresetId());
            assertEquals(expected,f.runtime.snapshot().selectedPresetId().orElseThrow());
            assertEquals(expected,f.runtime.snapshot().activePresetId().orElseThrow());
            assertEquals(f.storage.loadAppearance(ACCOUNT).outerLayerVisibility(), f.storage.loadOrCreateAccount(ACCOUNT)
                    .presets().stream().filter(p -> p.id().equals(expected)).findFirst().orElseThrow().outerLayerVisibility());
            assertTrue(f.view().widget("gallery.done").isPresent());
            assertEquals(writes,layers.writes); assertEquals(0,f.reconciliations.get());
        } finally { f.runtime.close(); }
    }

    @ParameterizedTest @ValueSource(strings={"back","escape","apply","inline-apply"})
    void providersParentIsRestoredByRealSkinEditRouteBeforeReconciliation(String exit) throws Exception {
        var f=fixture();
        try {
            f.runtime.dispatchWidget("gallery.providers"); f.drain();
            assertEquals("providers",f.view().screenId());
            assertTrue(f.view().widget("providers.edit.OFFLINE").orElseThrow().enabled());
            f.runtime.dispatchWidget("providers.edit.OFFLINE",false,InteractionOrigin.KEYBOARD);
            assertEquals("gallery",f.view().screenId());
            f.selectB(); var before=ScopedEditorSaveTest.inventory(directory);
            if (exit.endsWith("apply")) {
                f.expectedProvidersParent=true; f.inline=exit.equals("inline-apply");
                f.runtime.dispatchWidget("gallery.apply");
            } else if (exit.equals("escape")) f.runtime.escapePressed();
            else f.runtime.dispatchWidget("gallery.back");
            f.drain();
            assertEquals("providers",f.view().screenId());
            assertEquals(exit.endsWith("apply") ? B : A,f.storage.loadAppearance(ACCOUNT).activePresetId());
            assertEquals(exit.endsWith("apply") ? 1 : 0,f.reconciliations.get());
            if (!exit.endsWith("apply")) assertEquals(before,ScopedEditorSaveTest.inventory(directory));
        } finally { f.runtime.close(); }
    }

    @ParameterizedTest @ValueSource(strings={"PARTIAL","UNKNOWN","PENDING"})
    void runtimeActiveMaskOnlyPreservesUnresolvedDeliveryAndNeverReconciles(String status) throws Exception {
        var f=new GalleryRuntimeSelectionTest(); f.directory=directory;
        var layers=new Layers(); f.layerController=Optional.of(layers); f.setup();
        try {
            f.storage.updateAppearance(ACCOUNT,old -> new AccountAppearanceState(old.schemaVersion(),ACCOUNT,
                    old.intentRevision(),old.activePresetId(),old.skinSha256(),old.skinVariant(),old.capeId(),
                    old.outerLayerVisibility(),AppearanceSyncStatus.valueOf(status),old.settledRevision(),old.updatedAt(),old.providers()));
            f.selectB(); f.runtime.dispatchWidget("gallery.reset"); f.drain();
            var before=f.storage.loadAppearance(ACCOUNT);
            ordinary(f); f.runtime.dispatchWidget("gallery.outer_layer.body");
            var mask=f.runtime.galleryPreviewCandidate().orElseThrow().outerLayerVisibility(); int writes=layers.writes;
            f.runtime.dispatchWidget("gallery.apply"); f.drain();
            var after=f.storage.loadAppearance(ACCOUNT);
            assertEquals(ClientSnapshot.Lifecycle.CLOSED,f.runtime.snapshot().lifecycle());
            assertEquals(mask,after.outerLayerVisibility()); assertEquals(mask,layers.value);
            assertEquals(writes+1,layers.writes); assertEquals(0,f.reconciliations.get());
            assertEquals(before.providers().skin().minecraftDelivery(),after.providers().skin().minecraftDelivery());
            assertEquals(before.providers().cape().minecraftDelivery(),after.providers().cape().minecraftDelivery());
            assertEquals(before.syncStatus(),after.syncStatus()); assertEquals(before.settledRevision(),after.settledRevision());
        } finally { f.runtime.close(); }
    }

    @ParameterizedTest @ValueSource(strings={"online","offline","cooldown","inline"})
    void actualApplySelectionReachesProductionReconciliationAfterLocalClose(String mode) throws Exception {
        var f=new GalleryDoneRecoveryTest(); f.directory=directory; f.setup();
        try {
            f.storage.updateAccount(ACCOUNT,old -> {
                var presets=new ArrayList<>(old.presets());
                presets.add(new AppearancePreset(B,"Selection B",SkinReference.accountDefault(),null,
                        OuterLayerVisibility.noneVisible(),GalleryApplyTest.NOW,GalleryApplyTest.NOW));
                return new AccountState(old.schemaVersion(),ACCOUNT,old.skinAssets(),presets,old.updatedAt());
            });
            f.runtime.dispatchWidget("gallery.card."+A);
            f.runtime.dispatchWidget("gallery.reset"); f.drain();
            f.runtime.view(854,480,0,0);
            f.runtime.dispatchWidget("gallery.card."+B,false,InteractionOrigin.POINTER);
            assertTrue(f.runtime.view(854,480,0,0).widget("gallery.apply").orElseThrow().enabled());
            f.online=!mode.equals("offline"); f.limited=mode.equals("cooldown"); f.inline=mode.equals("inline");
            f.runtime.dispatchWidget("gallery.apply");
            if (!f.inline) {
                assertTrue(f.runtime.snapshot().busy()); assertEquals(A,f.storage.loadAppearance(ACCOUNT).activePresetId());
                f.work();
                assertEquals(B,f.storage.loadAppearance(ACCOUNT).activePresetId());
                assertEquals(A,f.runtime.snapshot().activePresetId().orElseThrow());
                assertEquals(0,f.tokens.get());
                f.client();
            }
            assertEquals(ClientSnapshot.Lifecycle.CLOSED,f.runtime.snapshot().lifecycle());
            f.drain();
            assertEquals(B,f.storage.loadAppearance(ACCOUNT).activePresetId());
            assertEquals(1,f.optionWrites.get());
            if (mode.equals("cooldown")) {
                assertEquals(0,f.tokens.get()); assertEquals(0,f.gets.get());
                f.limited=false; f.runtime.tick(); f.drain();
                assertEquals(1,f.gets.get());
            } else if (mode.equals("offline")) {
                assertEquals(0,f.gets.get()); assertEquals(0,f.mutations.get());
                assertNotEquals(AppearanceSyncStatus.OFFICIAL,f.storage.loadAppearance(ACCOUNT).syncStatus());
            } else assertEquals(1,f.gets.get());
            assertEquals(1,f.optionWrites.get(),"Remote completion must not replay Options");
        } finally { f.runtime.close(); }
    }

    @Test void hiddenPhysicalCardActionsCannotOpenEditorOrDeleteAndLegacyApplyCannotCommit() throws Exception {
        var f = fixture();
        try {
            var before = ScopedEditorSaveTest.inventory(directory);
            String prefix = "gallery.preset." + GalleryRuntimeSelectionTest.B;
            for (var origin : List.of(InteractionOrigin.POINTER, InteractionOrigin.KEYBOARD)) {
                for (String action : List.of("edit", "duplicate", "delete", "delete_confirm")) {
                    assertTrue(f.view().widget(prefix + "." + action).isEmpty());
                    f.runtime.dispatchWidget(prefix + "." + action, false, origin);
                    assertEquals("gallery", f.view().screenId(), "Hidden " + action + " must not route");
                    assertTrue(f.runtime.snapshot().editor().isEmpty());
                    assertTrue(f.workers.isEmpty());
                }
            }
            for (var origin : List.of(InteractionOrigin.POINTER, InteractionOrigin.KEYBOARD)) {
                f.runtime.dispatchWidget(prefix + ".apply", false, origin);
                assertTrue(f.workers.isEmpty(), "Removed per-card Apply must not queue usePreset");
            }
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            assertEquals(GalleryRuntimeSelectionTest.A, f.runtime.snapshot().selectedPresetId().orElseThrow());
            f.runtime.dispatchWidget(prefix + ".more", false, InteractionOrigin.POINTER);
            assertTrue(f.view().widget(prefix + ".edit").orElseThrow().enabled());
            f.runtime.dispatchWidget(prefix + ".edit", false, InteractionOrigin.POINTER);
            f.drain();
            assertEquals("preset_editor", f.runtime.view(854,480,0,0).screenId());
        } finally { f.runtime.close(); }
    }
}

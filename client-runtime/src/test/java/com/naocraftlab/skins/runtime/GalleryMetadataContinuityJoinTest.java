package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.naocraftlab.skins.runtime.GalleryRuntimeSelectionTest.*;

final class GalleryMetadataContinuityJoinTest {
    @TempDir Path directory;

    GalleryRuntimeSelectionTest fixture() throws Exception {
        var f = new GalleryRuntimeSelectionTest();
        f.directory = directory;
        f.setup();
        return f;
    }

    void star(GalleryRuntimeSelectionTest f, UUID id) {
        String prefix = "gallery.preset." + id;
        if (f.view().widget(prefix + ".favorite").isEmpty()) f.runtime.dispatchWidget(prefix + ".more");
        var action = f.view().widget(prefix + ".favorite").orElseThrow();
        assertTrue(action.visible() && action.enabled() && action.activationEnabled());
        f.runtime.dispatchWidget(action.id(), false, InteractionOrigin.POINTER);
    }

    @Test void oldCollapseCompletionCannotClearRepeatedProvidersEditOpeningReveal() throws Exception {
        var f = fixture();
        try {
            f.operations.setPresetFavorite(new LibraryEditorPort.PresetFavoriteUpdate(
                    new LibraryEditorPort.GalleryScope(ACCOUNT,0,()->true), B,true));
            f.storage.updateGallerySections(ACCOUNT, Map.of("gallery.your_appearances",true), () -> true);
            f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
            f.runtime.dispatchWidget("gallery.providers"); f.drain();
            f.runtime.dispatchWidget("providers.edit.OFFLINE",false,InteractionOrigin.KEYBOARD);
            assertEquals("gallery", f.view().screenId());
            assertTrue(f.view().widget(f.card(A)).isPresent());
            f.runtime.dispatchWidget("gallery.your_appearances",false,InteractionOrigin.KEYBOARD);
            assertFalse(f.workers.isEmpty(), "Real independent collapse worker must be held");
            f.runtime.escapePressed();
            assertEquals("providers",f.view().screenId());
            f.runtime.dispatchWidget("providers.edit.OFFLINE",false,InteractionOrigin.KEYBOARD);
            var before = f.view();
            assertTrue(before.widget(f.card(A)).isPresent());
            var focus = before.focusRequest();
            var scroll = before.scrollSurface("gallery.cards").orElseThrow().offsetPixels();
            f.drain();
            assertTrue(f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections().contains("gallery.your_appearances"));
            var after = f.view();
            assertTrue(after.widget(f.card(A)).isPresent(), "COMMON-04 old collapse cleared new opening reveal");
            assertEquals(focus,after.focusRequest());
            assertEquals(scroll,after.scrollSurface("gallery.cards").orElseThrow().offsetPixels());
        } finally { f.runtime.close(); }
    }

    @Test void queuedDesiredFavoriteTogglesRemainResponsiveAndLastIntentWins() throws Exception {
        var f = fixture();
        try {
            f.beforeOperation = name -> {
                if (name.equals("setPresetFavorite") || name.equals("reloadEditorAccount"))
                    assertTrue(f.workerLane, "Durable operations belong to worker");
            };
            var views = new ArrayList<ViewSpec>();
            var subscription = f.runtime.subscribe(ignored -> views.add(f.view()));
            long generation = f.runtime.snapshot().generation();
            star(f, B); star(f, B);
            assertFalse(f.runtime.snapshot().busy());
            assertFalse(f.storage.loadOrCreateAccount(ACCOUNT).presets().get(1).favorite(), "No optimistic success");
            f.work();
            assertTrue(f.storage.loadOrCreateAccount(ACCOUNT).presets().get(1).favorite(), "First admitted desired=true");
            f.drain();
            assertFalse(f.storage.loadOrCreateAccount(ACCOUNT).presets().get(1).favorite(), "Second click desired=false, not stale true");
            assertFalse(f.runtime.snapshot().account().orElseThrow().presets().get(1).favorite());
            assertEquals(generation, f.runtime.snapshot().generation());
            for (var view : views) {
                assertTrue(view.widget("gallery.providers").orElseThrow().enabled());
                assertTrue(view.widget("gallery.search").orElseThrow().enabled());
            }
            subscription.close();
        } finally { f.runtime.close(); }
    }

    @Test void queuedIndividualAndBulkSectionsUseLatestDesiredFieldsWithoutOptimisticCollapse() throws Exception {
        var f = fixture();
        try {
            f.operations.setPresetFavorite(new LibraryEditorPort.PresetFavoriteUpdate(
                    new LibraryEditorPort.GalleryScope(ACCOUNT,0,()->true), B,true));
            f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
            f.runtime.dispatchWidget("gallery.your_appearances");
            f.runtime.dispatchWidget("gallery.your_appearances");
            assertTrue(f.view().widget(f.card(A)).isPresent());
            assertFalse(f.runtime.snapshot().busy());
            f.drain();
            assertEquals(List.of(Map.of("gallery.your_appearances",true),Map.of("gallery.your_appearances",false)),
                    f.sectionUpdates.stream().map(UiPreferencesPort.GallerySectionsUpdate::collapsed).toList());
            assertTrue(f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections().isEmpty());
            f.runtime.dispatchWidget("gallery.sections");
            f.runtime.dispatchWidget("gallery.sections");
            f.drain();
            assertEquals(4,f.sectionUpdates.size(), "One bulk patch per click");
            assertEquals(Map.of("gallery.favorites",false,"gallery.your_appearances",false),f.sectionUpdates.get(3).collapsed());
            assertTrue(f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections().isEmpty());
        } finally { f.runtime.close(); }
    }

    @Test void queuedSingleGroupCommandsUseLatestDesiredFieldsAcrossQueryChanges() throws Exception {
        var f = fixture();
        try {
            f.operations.setPresetFavorite(new LibraryEditorPort.PresetFavoriteUpdate(
                    new LibraryEditorPort.GalleryScope(ACCOUNT,0,()->true),B,true));
            f.storage.updateGallerySections(ACCOUNT,Map.of("gallery.your_appearances",true),()->true);
            f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
            f.selectB();
            var candidate = f.runtime.galleryPreviewCandidate().orElseThrow();
            f.runtime.dispatchText("gallery.search","b");
            for (String action : List.of("gallery.favorites","gallery.sections","gallery.favorites","gallery.sections")) {
                f.runtime.dispatchWidget(action);
                assertTrue(f.view().widget(f.card(B)).isPresent());
                assertFalse(f.runtime.snapshot().busy());
                assertEquals(Optional.of(GuiIcon.ACTION_COLLAPSE_ALL),f.view().widget("gallery.sections").orElseThrow().icon());
            }
            assertFalse(f.workers.isEmpty());
            f.runtime.dispatchText("gallery.search","a");
            f.runtime.dispatchWidget("gallery.sections");
            f.runtime.dispatchText("gallery.search","missing");
            var focus = f.view().focusRequest();
            f.runtime.dispatchWidget("gallery.sections"); f.drain();
            assertEquals(List.of(Map.of("gallery.favorites",true),Map.of("gallery.favorites",false),
                    Map.of("gallery.favorites",true),Map.of("gallery.favorites",false),Map.of("gallery.your_appearances",true)),
                    f.sectionUpdates.stream().map(UiPreferencesPort.GallerySectionsUpdate::collapsed).toList());
            assertEquals(Set.of("gallery.your_appearances"),f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections());
            assertEquals(Optional.of("missing"),f.view().widget("gallery.search").orElseThrow().value());
            assertEquals(focus,f.view().focusRequest());
            assertEquals(candidate,f.runtime.galleryPreviewCandidate().orElseThrow());
            assertEquals(Optional.of(B),f.runtime.snapshot().selectedPresetId());
            assertEquals(Optional.of(A),f.runtime.snapshot().activePresetId());
            assertEquals(0,f.reconciliations.get());
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false,true})
    void lastMemberMovePreservesDraftAndFocusesCollapsedDestinationWithoutReopen(boolean toFavorite) throws Exception {
        var f = fixture();
        try {
            var scope = new LibraryEditorPort.GalleryScope(ACCOUNT,0,()->true);
            f.operations.setPresetFavorite(new LibraryEditorPort.PresetFavoriteUpdate(scope,A,toFavorite));
            f.operations.setPresetFavorite(new LibraryEditorPort.PresetFavoriteUpdate(scope,B,!toFavorite));
            f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
            f.selectB();
            var candidate = f.runtime.galleryPreviewCandidate().orElseThrow();
            var appearance = f.storage.loadAppearance(ACCOUNT);
            var before = f.storage.loadOrCreateAccount(ACCOUNT);
            String destination = toFavorite ? "gallery.favorites" : "gallery.your_appearances";
            String removed = toFavorite ? "gallery.your_appearances" : "gallery.favorites";
            f.runtime.dispatchWidget(destination); f.drain();
            String prefix = "gallery.preset." + B;
            f.runtime.dispatchWidget(prefix+".more");
            assertTrue(f.view().widget(prefix+".favorite").orElseThrow().enabled());
            f.runtime.dispatchWidget(prefix+".favorite",false,InteractionOrigin.KEYBOARD);
            assertFalse(f.workers.isEmpty()); f.drain();
            var view = f.view();
            assertEquals(Optional.of(B),f.runtime.snapshot().selectedPresetId());
            assertEquals(Optional.of(A),f.runtime.snapshot().activePresetId());
            assertEquals(candidate,f.runtime.galleryPreviewCandidate().orElseThrow());
            assertTrue(view.widget("gallery.apply").orElseThrow().enabled());
            assertTrue(view.navigationNode(removed).isEmpty());
            assertTrue(view.navigationNode(f.card(B)).isEmpty());
            assertEquals(destination,view.focusRequest().orElseThrow().widgetId());
            assertTrue(view.widget(destination).orElseThrow().enabled());
            assertEquals(Set.of(destination),f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections());
            var saved = f.storage.loadOrCreateAccount(ACCOUNT);
            assertEquals(toFavorite,saved.presets().get(1).favorite());
            assertEquals(before.updatedAt(),saved.updatedAt());
            assertEquals(before.presets().get(1).updatedAt(),saved.presets().get(1).updatedAt());
            assertEquals(appearance,f.storage.loadAppearance(ACCOUNT));
            assertEquals(1,f.sectionUpdates.size(),"Regrouping itself writes no section preferences");
            assertEquals(0,f.reconciliations.get());
        } finally { f.runtime.close(); }
    }

    @Test void oldFavoriteCompletionRefreshesOnlyItsFieldWithoutRetargetingNewDraft() throws Exception {
        var f = fixture();
        try {
            star(f,B);
            f.selectB();
            var candidate = f.runtime.galleryPreviewCandidate().orElseThrow();
            f.runtime.dispatchText("gallery.search","B");
            f.work();
            assertTrue(f.storage.loadOrCreateAccount(ACCOUNT).presets().get(1).favorite());
            f.drain();
            assertEquals(B,f.runtime.snapshot().selectedPresetId().orElseThrow());
            assertEquals(candidate,f.runtime.galleryPreviewCandidate().orElseThrow());
            assertEquals(Optional.of("B"),f.view().widget("gallery.search").orElseThrow().value());
            assertTrue(f.runtime.snapshot().account().orElseThrow().presets().get(1).favorite());
            assertEquals(0,f.reconciliations.get());
        } finally { f.runtime.close(); }
    }
}

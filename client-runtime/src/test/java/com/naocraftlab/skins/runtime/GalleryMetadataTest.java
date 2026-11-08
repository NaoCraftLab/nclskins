package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static com.naocraftlab.skins.runtime.GalleryRuntimeSelectionTest.*;

final class GalleryMetadataTest {
    @TempDir Path directory;

    GalleryRuntimeSelectionTest fixture() throws Exception {
        var f = new GalleryRuntimeSelectionTest();
        f.directory = directory;
        f.setup();
        return f;
    }

    LibraryEditorPort.GalleryScope scope() { return new LibraryEditorPort.GalleryScope(ACCOUNT, 0, () -> true); }

    void favorite(GalleryRuntimeSelectionTest f, java.util.UUID id) {
        f.runtime.dispatchWidget("gallery.preset." + id + ".more");
        f.runtime.dispatchWidget("gallery.preset." + id + ".favorite", false, InteractionOrigin.KEYBOARD);
    }

    void reopen(GalleryRuntimeSelectionTest f) { f.runtime.closeScreen(); f.runtime.reopen(); f.drain(); }

    @Test void absentOrFilteredSectionCannotAcquireWriteAuthority() throws Exception {
        var f = fixture();
        try {
            var before = ScopedEditorSaveTest.inventory(directory);
            f.runtime.dispatchWidget("gallery.sections");
            f.runtime.dispatchWidget("gallery.favorites"); f.drain();
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            assertTrue(f.sectionUpdates.isEmpty());
            f.operations.setPresetFavorite(new LibraryEditorPort.PresetFavoriteUpdate(scope(), B, true)); reopen(f);
            f.runtime.dispatchText("gallery.search", "a");
            assertTrue(f.view().widget("gallery.sections").isPresent(), "Topology comes from library before query");
            assertTrue(f.view().widget("gallery.favorites").isEmpty(), "Filtered empty section is hidden");
            int writes = f.sectionUpdates.size();
            f.runtime.dispatchWidget("gallery.favorites"); f.drain();
            assertEquals(writes, f.sectionUpdates.size());
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 99})
    void corruptAndFuturePreferencesKeepRawBytesAndNeverConfirmCollapse(int schema) throws Exception {
        var f = fixture();
        try {
            f.operations.setPresetFavorite(new LibraryEditorPort.PresetFavoriteUpdate(scope(), B, true)); reopen(f);
            Path preferences = directory.resolve("accounts").resolve(ACCOUNT.toString()).resolve("ui-v1.json");
            String raw = schema == 0 ? "{broken preferences" : "{\"schemaVersion\":99,\"accountId\":\"" + ACCOUNT + "\"}";
            java.nio.file.Files.writeString(preferences, raw);
            f.runtime.dispatchWidget("gallery.your_appearances"); f.drain();
            assertEquals(raw, java.nio.file.Files.readString(preferences));
            assertTrue(f.view().widget(f.card(A)).isPresent());
            assertEquals(UiMessage.Severity.ERROR, f.runtime.snapshot().status().severity());
        } finally { f.runtime.close(); }
    }

    @Test void accountRoundTripEpochRejectsOldFavoriteEvenAfterIdentityReturns() throws Exception {
        var f = fixture();
        try {
            favorite(f, B);
            var before = ScopedEditorSaveTest.inventory(directory);
            f.identity.set(new com.naocraftlab.skins.client.GameSessionTokenSource.SessionIdentity(new java.util.UUID(0,99), "Other"));
            f.runtime.tick();
            f.identity.set(new com.naocraftlab.skins.client.GameSessionTokenSource.SessionIdentity(ACCOUNT, "Fixture"));
            f.runtime.tick(); f.drain();
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            assertFalse(f.runtime.snapshot().account().orElseThrow().presets().get(1).favorite());
        } finally { f.runtime.close(); }
    }

    @Test void committedFavoriteForChangedAccountDoesNotPublishWithoutClientTick() throws Exception {
        var f = fixture();
        try {
            f.afterOperation = name -> {
                if (name.equals("setPresetFavorite")) f.identity.set(
                        new com.naocraftlab.skins.client.GameSessionTokenSource.SessionIdentity(new java.util.UUID(0,99), "Other"));
            };
            favorite(f, B); f.work();
            assertTrue(f.storage.loadOrCreateAccount(ACCOUNT).presets().get(1).favorite());
            assertFalse(f.runtime.snapshot().account().orElseThrow().presets().get(1).favorite());
            f.drain();
            assertFalse(f.runtime.snapshot().account().orElseThrow().presets().get(1).favorite());
        } finally { f.runtime.close(); }
    }

    @Test void preferenceAdapterChecksActualAccountAndScopeInsteadOfRequestSelfComparison() throws Exception {
        var f = fixture();
        try {
            var before = ScopedEditorSaveTest.inventory(directory);
            var adapter = new UiPreferencesStorageAdapter(f.storage, () -> new java.util.UUID(0,99));
            assertThrows(Exception.class, () -> adapter.updateGallerySections(new UiPreferencesPort.GallerySectionsUpdate(
                    scope(), java.util.Map.of("gallery.favorites", true))));
            var retired = new LibraryEditorPort.GalleryScope(ACCOUNT, 0, () -> false);
            assertThrows(Exception.class, () -> f.operations.updateGallerySections(new UiPreferencesPort.GallerySectionsUpdate(
                    retired, java.util.Map.of("gallery.favorites", true))));
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
        } finally { f.runtime.close(); }
    }

    @Test void openingCollapsedActiveTemporarilyRevealsWithoutWritesAndExplicitCollapseWins() throws Exception {
        var f = fixture();
        try {
            f.operations.setPresetFavorite(new LibraryEditorPort.PresetFavoriteUpdate(scope(), B, true));
            f.storage.updateGallerySections(ACCOUNT, java.util.Map.of("gallery.your_appearances", true), () -> true);
            var before = ScopedEditorSaveTest.inventory(directory);
            reopen(f);
            assertTrue(f.view().widget(f.card(A)).isPresent());
            assertEquals(before, ScopedEditorSaveTest.inventory(directory), "Opening/reveal is read-only");
            f.runtime.dispatchWidget("gallery.your_appearances"); f.drain();
            assertTrue(f.view().widget(f.card(A)).isEmpty());
            f.view(); f.view();
            assertTrue(f.view().widget(f.card(A)).isEmpty(), "Publication must not re-reveal active");
            assertTrue(f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections().contains("gallery.your_appearances"));
            reopen(f);
            assertTrue(f.view().widget(f.card(A)).isPresent(), "New opening gets its own temporary reveal");
        } finally { f.runtime.close(); }
    }

    @Test void favoriteMoveToExplicitCollapsedDestinationKeepsDraftAndFocusesVisibleHeader() throws Exception {
        var f = fixture();
        try {
            f.operations.setPresetFavorite(new LibraryEditorPort.PresetFavoriteUpdate(scope(), A, true));
            reopen(f);
            f.runtime.dispatchWidget("gallery.favorites"); f.drain();
            f.selectB();
            favorite(f, B); f.drain();
            assertEquals(B, f.runtime.snapshot().selectedPresetId().orElseThrow());
            assertTrue(f.view().widget(f.card(B)).isEmpty());
            assertEquals("gallery.favorites", f.view().focusRequest().orElseThrow().widgetId());
            assertTrue(f.view().widget("gallery.apply").isPresent());
            assertTrue(f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections().contains("gallery.favorites"));
        } finally { f.runtime.close(); }
    }

    @Test void queuedFavoritePreservesLatestEditorSaveAndDoesNotChangeItsIntent() throws Exception {
        var f = fixture();
        try {
            f.selectB(); favorite(f, B);
            var b = f.storage.loadOrCreateAccount(ACCOUNT).presets().get(1);
            f.operations.saveEditor(new LibraryEditorPort.EditorSaveRequest(java.util.Optional.of(B), "Latest name", b.skin(),
                    SkinVariant.CLASSIC, SkinVariant.CLASSIC, java.util.Optional.empty(), b.outerLayerVisibility(),
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), PersonalSkinSource.FILE));
            var appearance = f.storage.loadAppearance(ACCOUNT);
            f.drain();
            var saved = f.storage.loadOrCreateAccount(ACCOUNT).presets().stream().filter(p -> p.id().equals(B)).findFirst().orElseThrow();
            assertEquals("Latest name", saved.name()); assertTrue(saved.favorite());
            assertEquals(appearance, f.storage.loadAppearance(ACCOUNT));
            f.operations.saveEditor(new LibraryEditorPort.EditorSaveRequest(java.util.Optional.of(B), "After favorite", saved.skin(),
                    SkinVariant.CLASSIC, SkinVariant.CLASSIC, java.util.Optional.empty(), saved.outerLayerVisibility(),
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), PersonalSkinSource.FILE));
            assertTrue(f.storage.loadOrCreateAccount(ACCOUNT).presets().stream().filter(p -> p.id().equals(B)).findFirst().orElseThrow().favorite());
        } finally { f.runtime.close(); }
    }

    @Test void acceptedFavoriteSurvivesScreenCloseButCannotPublishOverReopenedWork() throws Exception {
        var f = fixture();
        try {
            favorite(f, B);
            f.runtime.closeScreen();
            f.work();
            assertTrue(f.storage.loadOrCreateAccount(ACCOUNT).presets().get(1).favorite(), "Close retires screen, not accepted write");
            f.runtime.reopen();
            var generation = f.runtime.snapshot().generation();
            while (!f.clients.isEmpty()) f.clients.remove().run();
            assertEquals(generation, f.runtime.snapshot().generation());
            assertEquals(ClientSnapshot.Lifecycle.INITIALIZING, f.runtime.snapshot().lifecycle());
            assertTrue(f.runtime.snapshot().busy());
            f.drain();
            assertTrue(f.runtime.snapshot().account().orElseThrow().presets().get(1).favorite());
        } finally { f.runtime.close(); }
    }

    @Test void acceptedCollapseSurvivesCloseAndOldCompletionCannotReplaceNewDraft() throws Exception {
        var f = fixture();
        try {
            f.operations.setPresetFavorite(new LibraryEditorPort.PresetFavoriteUpdate(scope(), B, true)); reopen(f);
            f.runtime.dispatchWidget("gallery.favorites");
            f.runtime.closeScreen(); f.work();
            assertTrue(f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections().contains("gallery.favorites"));
            f.runtime.reopen();
            long generation = f.runtime.snapshot().generation();
            while (!f.clients.isEmpty()) f.clients.remove().run();
            assertEquals(generation, f.runtime.snapshot().generation());
            assertTrue(f.runtime.snapshot().busy());
            f.drain();
            assertTrue(f.view().widget(f.card(B)).isEmpty());
            assertEquals(A, f.runtime.snapshot().selectedPresetId().orElseThrow());
        } finally { f.runtime.close(); }
    }

    @Test void collapsedTopologyDrivesActualScrollbarCoordinates() throws Exception {
        var f = fixture();
        try {
            f.storage.updateAccount(ACCOUNT, old -> {
                var presets = new java.util.ArrayList<>(old.presets());
                for (int i=10; i<150; i++) presets.add(new AppearancePreset(new java.util.UUID(0,i), "Extra " + i,
                        SkinReference.accountDefault(), null, com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(),
                        GalleryApplyTest.NOW, GalleryApplyTest.NOW, null, i<50));
                return new AccountState(old.schemaVersion(), ACCOUNT, old.skinAssets(), presets, old.updatedAt());
            });
            reopen(f);
            f.runtime.nativeScrollPositionChanged("gallery.cards", 0);
            f.runtime.dispatchWidget("gallery.favorites"); f.drain();
            assertTrue(f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections().contains("gallery.favorites"));
            var bar = f.view().scrollbar().orElseThrow();
            assertTrue(bar.maximum() > 0);
            f.runtime.pointerPressed(bar.thumb().x()+1, bar.thumb().y()+1, 0);
            double y = bar.track().y() + (bar.track().height()-bar.thumb().height())*0.3 + 1;
            f.runtime.pointerDragged(bar.thumb().x()+1, y, 0, 0, y-bar.thumb().y());
            f.runtime.pointerReleased(0);
            assertEquals((int)Math.round(bar.maximum()*0.3), f.runtime.snapshot().galleryOffset(), 1);
        } finally { f.runtime.close(); }
    }

    @Test void latestApplyBeforeFavoriteDoesNotLoseMaskSelectionOrRevision() throws Exception {
        var f = fixture();
        try {
            favorite(f, B);
            var latest = f.operations.applyGalleryDraft(new LibraryEditorPort.GalleryApplyRequest(scope(), B,
                    java.util.Optional.of(new LibraryEditorPort.GalleryMaskPatch(
                            com.naocraftlab.skins.client.OuterLayerVisibility.noneVisible(),
                            com.naocraftlab.skins.client.OuterLayerVisibility.allVisible())), 0));
            var appearance = f.storage.loadAppearance(ACCOUNT);
            f.drain();
            var saved = f.storage.loadOrCreateAccount(ACCOUNT).presets().stream().filter(p -> p.id().equals(B)).findFirst().orElseThrow();
            assertTrue(saved.favorite());
            assertEquals(com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(), saved.outerLayerVisibility());
            assertEquals(appearance, f.storage.loadAppearance(ACCOUNT));
            assertEquals(latest.intentRevision(), appearance.intentRevision());
            assertEquals(B, appearance.activePresetId());
        } finally { f.runtime.close(); }
    }

    @Test void accountChangeAndShutdownRevokeQueuedDurableAuthorityWithoutWorkerPublication() throws Exception {
        var f = fixture();
        try {
            favorite(f, B);
            var before = ScopedEditorSaveTest.inventory(directory);
            f.identity.set(new com.naocraftlab.skins.client.GameSessionTokenSource.SessionIdentity(new java.util.UUID(0,99), "Other"));
            f.work();
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            assertFalse(f.runtime.snapshot().busy(), "Metadata does not own global busy");
            f.drain();
            assertFalse(f.runtime.snapshot().account().orElseThrow().presets().get(1).favorite());
            f.identity.set(new com.naocraftlab.skins.client.GameSessionTokenSource.SessionIdentity(ACCOUNT, "Fixture"));
            reopen(f);
            favorite(f, B); f.runtime.close(); f.drain();
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
        } finally { f.runtime.close(); }
    }

    @Test void deletedFavoriteFailureNeverReportsConfirmedSuccess() throws Exception {
        var f = fixture();
        try {
            favorite(f, B); f.operations.deletePreset(new LibraryEditorPort.GalleryScope(f.operations.sessionIdentity().profileId(), 0, () -> true), B);
            var before = ScopedEditorSaveTest.inventory(directory);
            f.drain();
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            assertEquals(UiMessage.Severity.ERROR, f.runtime.snapshot().status().severity());
            assertFalse(f.runtime.snapshot().account().orElseThrow().presets().get(1).favorite());
        } finally { f.runtime.close(); }
    }

    @Test void collapsePersistsOnlyConfirmedSectionsAndBulkUsesOneScopedPatch() throws Exception {
        var f = fixture();
        try {
            f.operations.setPresetFavorite(new LibraryEditorPort.PresetFavoriteUpdate(
                    new LibraryEditorPort.GalleryScope(ACCOUNT, 0, () -> true), B, true));
            f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
            assertTrue(f.view().widget("gallery.your_appearances").isPresent());
            f.runtime.dispatchWidget("gallery.your_appearances", false, InteractionOrigin.KEYBOARD);
            assertFalse(f.runtime.snapshot().busy(), "Manual collapse does not own global busy");
            assertEquals(1, f.workers.size(), "Manual collapse enters real queued writer");
            assertTrue(f.view().widget(f.card(A)).isPresent(), "No optimistic collapse");
            f.storage.setSelectedAddSourceTab(ACCOUNT, AddSourceTab.FILE);
            f.storage.setCollectionCollapsed(ACCOUNT, "unrelated", true);
            f.storage.setSelectedEditorTab(ACCOUNT, EditorTab.CAPE);
            f.storage.setSelectedProvidersTab(ACCOUNT, com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE);
            f.storage.setCollapsedCapeCollections(ACCOUNT, java.util.Set.of("editor.collection"));
            f.storage.setPreferredSkinVariant(ACCOUNT, SkinVariant.SLIM);
            f.drain();
            var saved = f.storage.loadUiPreferences(ACCOUNT).preferences();
            assertTrue(saved.collapsedGallerySections().contains("gallery.your_appearances"));
            assertEquals(AddSourceTab.FILE, saved.selectedAddSourceTab());
            assertTrue(saved.collapsedCollectionIds().contains("unrelated"));
            assertEquals(EditorTab.CAPE, saved.selectedEditorTab());
            assertEquals(com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE, saved.selectedProvidersTab());
            assertEquals(java.util.Set.of("editor.collection"), saved.collapsedCapeCollections());
            assertEquals(java.util.Optional.of(SkinVariant.SLIM), saved.preferredSkinVariant());
            assertTrue(f.view().widget(f.card(A)).isEmpty(), "Explicit collapse must beat active reveal");
            assertEquals("gallery.your_appearances", f.view().focusRequest().orElseThrow().widgetId());
            var before = ScopedEditorSaveTest.inventory(directory);
            f.view(); f.runtime.view(854,480,1,1);
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            f.runtime.dispatchWidget("gallery.sections"); f.drain();
            assertEquals(java.util.Set.of("gallery.favorites", "gallery.your_appearances"),
                    f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections());
            assertEquals(2, f.sectionUpdates.size(), "Individual and bulk each call writer once");
            assertEquals(java.util.Map.of("gallery.favorites", true, "gallery.your_appearances", true),
                    f.sectionUpdates.get(1).collapsed());
            f.runtime.dispatchWidget("gallery.sections"); f.drain();
            assertTrue(f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections().isEmpty());
        } finally { f.runtime.close(); }
    }

    @Test void favoriteCommitsThroughRealOperationsAndOnlyPublishesConfirmedMetadata() throws Exception {
        var f = fixture();
        try {
            f.selectB();
            var before = f.storage.loadOrCreateAccount(ACCOUNT);
            var appearance = f.storage.loadAppearance(ACCOUNT);
            f.runtime.dispatchWidget("gallery.preset." + B + ".more");
            f.runtime.dispatchWidget("gallery.preset." + B + ".favorite", false, InteractionOrigin.KEYBOARD);
            assertFalse(f.runtime.snapshot().busy(), "Favorite does not own global busy");
            assertEquals(1, f.workers.size(), "Favorite enters real queued producer");
            assertFalse(f.storage.loadOrCreateAccount(ACCOUNT).presets().stream().filter(p -> p.id().equals(B)).findFirst().orElseThrow().favorite());
            f.work();
            assertFalse(f.runtime.snapshot().account().orElseThrow().presets().stream().filter(p -> p.id().equals(B)).findFirst().orElseThrow().favorite());
            f.drain();
            var saved = f.storage.loadOrCreateAccount(ACCOUNT);
            var b = saved.presets().stream().filter(p -> p.id().equals(B)).findFirst().orElseThrow();
            assertTrue(b.favorite());
            assertEquals(before.updatedAt(), saved.updatedAt());
            assertEquals(before.presets().get(1).updatedAt(), b.updatedAt());
            assertEquals(appearance, f.storage.loadAppearance(ACCOUNT));
            assertEquals(B, f.runtime.snapshot().selectedPresetId().orElseThrow());
            assertTrue(f.view().widget("gallery.apply").isPresent(), "Existing dirty selection survives");
            assertTrue(f.runtime.snapshot().account().orElseThrow().presets().stream().filter(p -> p.id().equals(B)).findFirst().orElseThrow().favorite());
            assertEquals(f.card(B), f.view().focusRequest().orElseThrow().widgetId());
            assertEquals(0, f.reconciliations.get());
        } finally { f.runtime.close(); }
    }
}

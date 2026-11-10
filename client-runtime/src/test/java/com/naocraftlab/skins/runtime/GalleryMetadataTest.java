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
            f.runtime.dispatchWidget("gallery.favorites"); f.drain();
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            assertTrue(f.sectionUpdates.isEmpty());
            f.operations.setPresetFavorite(new LibraryEditorPort.PresetFavoriteUpdate(scope(), B, true)); reopen(f);
            f.runtime.dispatchText("gallery.search", "a");
            assertTrue(f.view().widget("gallery.sections").orElseThrow().enabled());
            assertTrue(f.view().widget("gallery.favorites").isEmpty(), "Filtered empty section is hidden");
            int writes = f.sectionUpdates.size();
            f.runtime.dispatchWidget("gallery.favorites"); f.drain();
            assertEquals(writes, f.sectionUpdates.size());
            var captured = f.view().widget("gallery.sections").orElseThrow();
            f.runtime.dispatchText("gallery.search", "missing");
            var empty = f.view();
            assertFalse(empty.widget(captured.id()).orElseThrow().enabled());
            assertTrue(ViewNavigationPolicy.activationAction(empty,captured.id()).isEmpty());
            assertFalse(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE,captured.id()));
            var noMatches = ScopedEditorSaveTest.inventory(directory);
            f.runtime.dispatchWidget(captured.id(),false,InteractionOrigin.KEYBOARD);
            f.runtime.dispatchWidget(captured.id(),false,InteractionOrigin.POINTER);
            f.runtime.dispatchWidget("gallery.your_appearances"); f.drain();
            assertEquals(writes, f.sectionUpdates.size());
            assertEquals(noMatches, ScopedEditorSaveTest.inventory(directory));
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false,true})
    void singleFilteredBulkPatchesOnlyParticipatingGroupAndPreservesHiddenFlag(boolean favoriteGroup) throws Exception {
        var f = fixture();
        try {
            f.operations.setPresetFavorite(new LibraryEditorPort.PresetFavoriteUpdate(scope(), B, true));
            String visible = favoriteGroup ? "gallery.favorites" : "gallery.your_appearances";
            String hidden = favoriteGroup ? "gallery.your_appearances" : "gallery.favorites";
            f.storage.updateGallerySections(ACCOUNT,java.util.Map.of(hidden,true),()->true);
            var other = new java.util.UUID(0,99);
            f.storage.updateGallerySections(other,java.util.Map.of(visible,true),()->true);
            var otherPreferences = f.storage.loadUiPreferences(other).preferences();
            reopen(f);
            f.runtime.dispatchText("gallery.search",favoriteGroup ? "b" : "a");
            var before = ScopedEditorSaveTest.inventory(directory);
            f.view();
            assertEquals(before,ScopedEditorSaveTest.inventory(directory));
            assertEquals(java.util.Optional.of(GuiIcon.ACTION_COLLAPSE_ALL),f.view().widget("gallery.sections").orElseThrow().icon());
            f.runtime.dispatchWidget("gallery.sections");
            assertFalse(f.workers.isEmpty());
            assertTrue(f.view().widget(f.card(favoriteGroup ? B : A)).isPresent(),"No optimistic collapse");
            assertFalse(f.runtime.snapshot().busy());
            f.storage.setSelectedAddSourceTab(ACCOUNT,AddSourceTab.FILE);
            f.storage.setSelectedEditorTab(ACCOUNT,EditorTab.CAPE);
            f.storage.setPreferredSkinVariant(ACCOUNT,SkinVariant.SLIM);
            f.storage.setCollectionCollapsed(ACCOUNT,"unrelated",true);
            f.storage.setCollapsedCapeCollections(ACCOUNT,java.util.Set.of("unrelated.capes"));
            f.storage.setSelectedProvidersTab(ACCOUNT,com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE);
            var latest = f.storage.loadUiPreferences(ACCOUNT).preferences();
            f.drain();
            assertEquals(java.util.Map.of(visible,true),f.sectionUpdates.get(0).collapsed());
            var fresh = com.naocraftlab.skins.core.storage.GalleryStorageFixture.create(directory,GalleryApplyTest.CLOCK,stage -> {});
            fresh.initialize();
            assertEquals(latest.withGallerySections(java.util.Map.of(visible,true)),fresh.loadUiPreferences(ACCOUNT).preferences());
            assertEquals(otherPreferences,fresh.loadUiPreferences(other).preferences());
            assertEquals(java.util.Set.of(visible,hidden),f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections());
            assertEquals(java.util.Optional.of(GuiIcon.ACTION_EXPAND_ALL),f.view().widget("gallery.sections").orElseThrow().icon());
            f.runtime.dispatchWidget("gallery.sections"); f.drain();
            assertEquals(java.util.Map.of(visible,false),f.sectionUpdates.get(1).collapsed());
            assertEquals(java.util.Set.of(hidden),f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections());
            assertEquals(2,f.sectionUpdates.size());
            assertEquals(latest.withGallerySections(java.util.Map.of(visible,false)),fresh.loadUiPreferences(ACCOUNT).preferences());
            var after = ScopedEditorSaveTest.inventory(directory);
            f.runtime.dispatchText("gallery.search","");
            assertEquals(after,ScopedEditorSaveTest.inventory(directory));
            assertTrue(f.view().navigationNode(hidden).isPresent());
            assertEquals(favoriteGroup,f.view().widget(f.card(favoriteGroup ? A : B)).isPresent(),
                    "Hidden flag survives; only the original active opening reveal remains expanded");
            reopen(f);
            assertTrue(f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections().contains(hidden));
            assertEquals(0,f.reconciliations.get());
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false,true})
    void openingCollapsedActiveTemporarilyRevealsWithoutWritesAndExplicitCollapseWins(boolean withFavorites) throws Exception {
        var f = fixture();
        try {
            if (withFavorites) f.operations.setPresetFavorite(new LibraryEditorPort.PresetFavoriteUpdate(scope(), B, true));
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false,true})
    void accountSwitchFencesSingleSectionCompletionAndPreservesOtherAccount(boolean committed) throws Exception {
        var f = fixture();
        try {
            var other = new java.util.UUID(0,99);
            f.storage.updateGallerySections(other,java.util.Map.of("gallery.favorites",true),()->true);
            var otherPreferences = f.storage.loadUiPreferences(other).preferences();
            f.runtime.dispatchWidget("gallery.sections");
            assertFalse(f.workers.isEmpty());
            if (committed) {
                f.work();
                assertEquals(java.util.Set.of("gallery.your_appearances"),f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections());
            }
            f.identity.set(new com.naocraftlab.skins.client.GameSessionTokenSource.SessionIdentity(other,"Other"));
            f.runtime.tick(); f.drain();
            assertEquals(otherPreferences,f.storage.loadUiPreferences(other).preferences());
            assertEquals(committed ? java.util.Set.of("gallery.your_appearances") : java.util.Set.of(),
                    f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections());
            f.identity.set(new com.naocraftlab.skins.client.GameSessionTokenSource.SessionIdentity(ACCOUNT,"Fixture"));
            f.runtime.tick(); reopen(f);
            assertEquals(java.util.Optional.of(ACCOUNT),f.runtime.snapshot().account().map(AccountState::accountId));
            assertEquals(committed ? java.util.Set.of("gallery.your_appearances") : java.util.Set.of(),
                    f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections());
        } finally { f.runtime.close(); }
    }

    @Test void failedSingleSectionWritePreservesOpeningRevealAndDraftUntilConfirmedRetry() throws Exception {
        var f = fixture();
        try {
            f.storage.updateGallerySections(ACCOUNT,java.util.Map.of("gallery.your_appearances",true),()->true);
            reopen(f); f.selectB();
            var candidate = f.runtime.galleryPreviewCandidate().orElseThrow();
            var preferences = f.storage.loadUiPreferences(ACCOUNT).preferences();
            var appearance = f.storage.loadAppearance(ACCOUNT);
            f.beforeOperation = name -> { if (name.equals("updateGallerySections")) throw new java.io.UncheckedIOException(new java.io.IOException("fixture failure")); };
            f.runtime.dispatchWidget("gallery.sections");
            assertTrue(f.view().widget(f.card(B)).isPresent());
            f.drain();
            assertEquals(preferences,f.storage.loadUiPreferences(ACCOUNT).preferences());
            assertTrue(f.view().widget(f.card(B)).isPresent(),"Failed explicit collapse must retain opening reveal");
            assertEquals(UiMessage.Severity.ERROR,f.runtime.snapshot().status().severity());
            assertEquals(candidate,f.runtime.galleryPreviewCandidate().orElseThrow());
            assertTrue(f.view().widget("gallery.apply").orElseThrow().enabled());
            assertEquals(appearance,f.storage.loadAppearance(ACCOUNT));
            f.beforeOperation = ignored -> {};
            f.runtime.dispatchWidget("gallery.sections"); f.drain();
            assertTrue(f.view().widget(f.card(B)).isEmpty());
            assertEquals(java.util.Map.of("gallery.your_appearances",true),f.sectionUpdates.get(0).collapsed());
            assertEquals(0,f.reconciliations.get());
        } finally { f.runtime.close(); }
    }

    @Test void initialActiveScrollIgnoresCollapsedPrecedingCardsAndKeepsRealHeaderHeight() throws Exception {
        var f = fixture();
        try {
            f.storage.updateAccount(ACCOUNT,old -> {
                var presets = new java.util.ArrayList<>(old.presets());
                for (int i=10;i<150;i++) presets.add(new AppearancePreset(new java.util.UUID(0,i),"Extra "+i,
                        SkinReference.accountDefault(),null,com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(),
                        GalleryApplyTest.NOW,GalleryApplyTest.NOW,null,true));
                return new AccountState(old.schemaVersion(),ACCOUNT,old.skinAssets(),presets,old.updatedAt());
            });
            f.storage.updateGallerySections(ACCOUNT,java.util.Map.of("gallery.favorites",true),()->true);
            reopen(f);
            assertEquals(0,f.runtime.snapshot().galleryOffset(),"Initial publication must use effective collapsed topology");
            var view = f.view();
            var surface = view.scrollSurface("gallery.cards").orElseThrow();
            assertEquals(0,surface.maximumPixels());
            assertEquals(63,view.widget("gallery.favorites").orElseThrow().bounds().y());
            assertEquals(81,view.widget("gallery.your_appearances").orElseThrow().bounds().y());
            assertEquals(99,view.widget(f.card(A)).orElseThrow().bounds().y());
            assertEquals(0,view.scrollbar().orElseThrow().maximum());
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"normal","favorite","filtered"})
    void singleGroupGeometryDrivesRealNavigationAndScrollbarWithoutPhantomGap(String scenario) throws Exception {
        var f = fixture();
        try {
            f.storage.updateAccount(ACCOUNT,old -> {
                var presets = new java.util.ArrayList<>(old.presets());
                for (int i=10;i<150;i++) presets.add(new AppearancePreset(new java.util.UUID(0,i),"Extra "+i,
                        SkinReference.accountDefault(),null,com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(),
                        GalleryApplyTest.NOW,GalleryApplyTest.NOW,null,!scenario.equals("normal")));
                if (scenario.equals("favorite")) presets.replaceAll(p -> p.withFavorite(true));
                return new AccountState(old.schemaVersion(),ACCOUNT,old.skinAssets(),presets,old.updatedAt());
            });
            reopen(f);
            if (scenario.equals("filtered")) f.runtime.dispatchText("gallery.search","eXtRa");
            f.runtime.nativeScrollPositionChanged("gallery.cards",0);
            var view = f.view();
            var surface = view.scrollSurface("gallery.cards").orElseThrow();
            String header = scenario.equals("normal") ? "gallery.your_appearances" : "gallery.favorites";
            String absent = scenario.equals("normal") ? "gallery.favorites" : "gallery.your_appearances";
            assertTrue(view.navigationNode(absent).isEmpty());
            assertEquals(63,view.widget(header).orElseThrow().bounds().y());
            var cards = view.navigationNodes().stream().filter(n -> n.id().startsWith("gallery.card.")).toList();
            assertEquals(81,cards.get(0).bounds().y());
            int columns = Math.max(1,(surface.viewport().width()-2)/50);
            int rows = (cards.size()+columns-1)/columns;
            int maximum = Math.max(0,20+(rows-1)*98+96-surface.viewport().height()+2);
            assertTrue(maximum>0);
            assertEquals(maximum,surface.maximumPixels());
            var bar = view.scrollbar().orElseThrow();
            assertEquals(maximum,bar.maximum());
            var before = ScopedEditorSaveTest.inventory(directory);
            f.runtime.pointerPressed(bar.thumb().x()+1,bar.thumb().y()+1,0);
            double y=bar.track().y()+(bar.track().height()-bar.thumb().height())*0.3+1;
            f.runtime.pointerDragged(bar.thumb().x()+1,y,0,0,y-bar.thumb().y()); f.runtime.pointerReleased(0);
            assertEquals(maximum*0.3,f.view().scrollSurface("gallery.cards").orElseThrow().offsetPixels(),1);
            var from = cards.get(cards.size()-columns-1);
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.DOWN,from.id()));
            var focused = f.view();
            var target = focused.navigationNode(focused.focusRequest().orElseThrow().widgetId()).orElseThrow();
            assertTrue(target.bounds().y()>=surface.viewport().y());
            assertTrue(target.bounds().bottom()<=surface.viewport().bottom());
            assertEquals(before,ScopedEditorSaveTest.inventory(directory));
            assertTrue(f.sectionUpdates.isEmpty());
            assertEquals(0,f.reconciliations.get());
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

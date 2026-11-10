package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import com.naocraftlab.skins.core.model.CapePreviewMode;
import com.naocraftlab.skins.core.model.AddSourceTab;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class CapePreviewPreferenceJoinTest {
    @TempDir Path directory;

    GalleryRuntimeSelectionTest fixture() throws Exception {
        var support = new GalleryPreviewInteractionJoinTest();
        support.directory = directory;
        var f = support.fixture(new GalleryInspectLayersTest.Layers());
        f.operations.usePreset(GalleryRuntimeSelectionTest.A);
        f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
        return f;
    }

    void open(GalleryRuntimeSelectionTest f, String screen) {
        if (screen.equals("editor")) {
            f.runtime.dispatchWidget("gallery.preset." + GalleryRuntimeSelectionTest.A + ".more");
            f.runtime.dispatchWidget("gallery.preset." + GalleryRuntimeSelectionTest.A + ".edit");
        } else if (screen.equals("providers")) {
            f.runtime.dispatchWidget("gallery.providers");
            f.drain();
        }
        f.view();
    }

    PreviewRenderer.CapeMode mode(GalleryRuntimeSelectionTest f, String screen) {
        if (screen.equals("editor")) return f.runtime.snapshot().editor().orElseThrow().preview().capeMode();
        if (screen.equals("gallery")) return GalleryInspectLayersTest.main(f).capeMode();
        return f.view().previews().stream().filter(p -> p.id().equals("editor.preview")).findFirst().orElseThrow().capeMode();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"gallery", "editor", "providers"})
    void writeFailureShowsExistingFeedbackWithoutRollbackOrBusyAndFreshRuntimeReadsLastSuccess(String screen) throws Exception {
        var f = fixture();
        try {
            f.storage.updatePreferredCapeMode(GalleryRuntimeSelectionTest.ACCOUNT, CapePreviewMode.CAPE, () -> true);
            open(f, screen);
            assertTrue(f.view().widget(screen + ".preview_mode").orElseThrow().enabled());
            var path = f.storage.layout().accountUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT);
            byte[] before = Files.readAllBytes(path);
            var permissions = Files.getPosixFilePermissions(path.getParent());
            Files.setPosixFilePermissions(path.getParent(), PosixFilePermissions.fromString("r-x------"));
            try {
                f.runtime.dispatchWidget(screen + ".preview_mode", false, InteractionOrigin.POINTER);
                assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, screen));
                assertFalse(f.runtime.snapshot().busy());
                f.drain();
            } finally { Files.setPosixFilePermissions(path.getParent(), permissions); }
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, screen));
            assertFalse(f.runtime.snapshot().busy());
            assertTrue(f.view().widget(screen + ".preview_mode").orElseThrow().enabled());
            assertArrayEquals(before, Files.readAllBytes(path));
            assertTrue(f.view().texts().stream().anyMatch(t -> t.message().key().equals("nclskins.error.save")),
                    "current screen must project the existing nonblocking save error");
            f.runtime.closeScreen();
            f.runtime.reopen(); f.drain();
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, "gallery"));
        } finally { f.runtime.close(); }
        var fresh = fixture();
        try { assertEquals(PreviewRenderer.CapeMode.CAPE, mode(fresh, "gallery")); }
        finally { fresh.runtime.close(); }
    }

    @Test void sectionCollapseAndBulkPreserveDurablePreviewModeAcrossReopen() throws Exception {
        var f = fixture();
        try {
            assertTrue(f.view().widget("gallery.preview_mode").orElseThrow().enabled());
            f.runtime.dispatchWidget("gallery.preview_mode");
            f.drain();
            assertEquals(CapePreviewMode.ELYTRA, f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT)
                    .preferences().preferredCapeMode());
            assertTrue(f.view().widget("gallery.your_appearances").orElseThrow().enabled());
            f.runtime.dispatchWidget("gallery.your_appearances");
            f.drain();
            assertTrue(f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT).preferences()
                    .collapsedGallerySections().contains("gallery.your_appearances"));
            assertTrue(f.view().widget("gallery.sections").orElseThrow().enabled());
            f.runtime.dispatchWidget("gallery.sections");
            f.drain();
            assertEquals(CapePreviewMode.ELYTRA, f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT)
                    .preferences().preferredCapeMode());
            f.runtime.dispatchWidget("gallery.sections");
            f.drain();
            var preferences = f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT).preferences();
            assertFalse(preferences.collapsedGallerySections().contains("gallery.your_appearances"));
            assertEquals(CapePreviewMode.ELYTRA, preferences.preferredCapeMode());
            f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, "gallery"));
        } finally { f.runtime.close(); }
    }

    @Test void failedModeAndSectionWritesKeepTheirDistinctSessionPolicies() throws Exception {
        var f = fixture();
        try {
            f.beforeOperation = name -> {
                if (name.equals("updatePreferredCapeMode") || name.equals("updateGallerySections"))
                    throw new java.io.UncheckedIOException(new java.io.IOException("fixture failure"));
            };
            assertTrue(f.view().widget("gallery.preview_mode").orElseThrow().enabled());
            f.runtime.dispatchWidget("gallery.preview_mode");
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, "gallery"));
            f.drain();
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, "gallery"));
            assertEquals(CapePreviewMode.CAPE, f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT)
                    .preferences().preferredCapeMode());
            assertTrue(f.view().texts().stream().anyMatch(t -> t.message().key().equals("nclskins.error.save")));
            var before = f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT).preferences();
            assertTrue(f.view().widget(f.card(GalleryRuntimeSelectionTest.A)).isPresent());
            assertTrue(f.view().widget("gallery.your_appearances").orElseThrow().enabled());
            f.runtime.dispatchWidget("gallery.your_appearances");
            f.drain();
            assertEquals(before, f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT).preferences());
            assertTrue(f.view().widget(f.card(GalleryRuntimeSelectionTest.A)).isPresent());
            assertEquals(UiMessage.Severity.ERROR, f.runtime.snapshot().status().severity());
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, "gallery"));
            f.beforeOperation = ignored -> {};
            f.runtime.dispatchWidget("gallery.your_appearances");
            f.drain();
            var committed = f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT).preferences();
            assertTrue(committed.collapsedGallerySections().contains("gallery.your_appearances"));
            assertEquals(CapePreviewMode.CAPE, committed.preferredCapeMode());
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, "gallery"));
        } finally { f.runtime.close(); }
    }

    @Test void hiddenProviderToggleDoesNotWriteOrChangeAccountMode() throws Exception {
        var f = fixture();
        try {
            open(f, "providers");
            f.runtime.dispatchWidget("providers.add");
            assertTrue(f.view().widget("providers.preview_mode").isEmpty());
            var writes = new AtomicInteger();
            f.beforeOperation = name -> { if (name.equals("updatePreferredCapeMode")) writes.incrementAndGet(); };
            f.runtime.dispatchWidget("providers.preview_mode", false, InteractionOrigin.POINTER);
            f.drain();
            assertEquals(0, writes.get());
            f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
            assertEquals(PreviewRenderer.CapeMode.CAPE, mode(f, "gallery"));
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"gallery,editor", "gallery,providers", "editor,gallery",
            "editor,providers", "providers,gallery", "providers,editor"})
    void everyDirectedTransitionSharesImmediateChoiceAndOneDurableMode(String from, String to) throws Exception {
        var f = fixture();
        try {
            open(f, from);
            var appearance = f.storage.loadAppearance(GalleryRuntimeSelectionTest.ACCOUNT);
            var account = f.storage.loadOrCreateAccount(GalleryRuntimeSelectionTest.ACCOUNT);
            var providers = f.runtime.snapshot().providers();
            var writes = new AtomicInteger();
            f.beforeOperation = name -> {
                if (name.equals("updatePreferredCapeMode")) {
                    assertTrue(f.workerLane, "mode persistence must stay off the client lane");
                    writes.incrementAndGet();
                }
            };
            assertTrue(f.view().widget(from + ".preview_mode").orElseThrow().enabled());
            f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, from + ".preview_mode");
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, from));
            assertFalse(f.runtime.snapshot().busy());
            assertEquals(0, writes.get());
            if (from.equals("editor")) f.runtime.escapePressed();
            if (from.equals("providers")) {
                if (to.equals("editor")) {
                    f.runtime.dispatchWidget("providers.tab.CAPE");
                    f.runtime.dispatchWidget("providers.edit.OFFLINE");
                } else f.runtime.dispatchWidget("providers.back");
            } else open(f, to);
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, to));
            f.drain();
            assertEquals(1, writes.get());
            assertEquals(CapePreviewMode.ELYTRA, f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT)
                    .preferences().preferredCapeMode());
            assertEquals(appearance, f.storage.loadAppearance(GalleryRuntimeSelectionTest.ACCOUNT));
            assertEquals(account, f.storage.loadOrCreateAccount(GalleryRuntimeSelectionTest.ACCOUNT));
            assertEquals(providers, f.runtime.snapshot().providers());
            assertEquals(0, f.reconciliations.get());
        } finally { f.runtime.close(); }
    }

    @Test void delayedFailureCannotAnnotateAReplacementEditorWithTheSameStatus() throws Exception {
        var f = fixture();
        try {
            f.storage.setSelectedAddSourceTab(GalleryRuntimeSelectionTest.ACCOUNT, AddSourceTab.FILE);
            open(f, "editor");
            f.runtime.dispatchWidget("editor.preview_mode");
            var path = f.storage.layout().accountUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT);
            var permissions = Files.getPosixFilePermissions(path.getParent());
            Files.setPosixFilePermissions(path.getParent(), PosixFilePermissions.fromString("r-x------"));
            try { f.work(); }
            finally { Files.setPosixFilePermissions(path.getParent(), permissions); }
            assertFalse(f.clients.isEmpty());
            f.runtime.dispatchWidget("editor.cancel");
            open(f, "editor");
            var replacement = f.runtime.snapshot().editor().orElseThrow();
            while (!f.clients.isEmpty()) f.clients.remove().run();
            assertEquals(replacement.status(), f.runtime.snapshot().editor().orElseThrow().status(),
                    "retired editor's failure must not replace the new editor feedback");
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, "editor"));
        } finally { f.runtime.close(); }
    }

    @Test void rapidChoicesAreSerializedAndLateFailureCannotReplaceLatestFeedback() throws Exception {
        var f = fixture();
        try {
            f.storage.setSelectedAddSourceTab(GalleryRuntimeSelectionTest.ACCOUNT, AddSourceTab.FILE);
            var path = f.storage.layout().accountUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT);
            var permissions = Files.getPosixFilePermissions(path.getParent());
            var writes = new AtomicInteger();
            f.beforeOperation = name -> { if (name.equals("updatePreferredCapeMode")) writes.incrementAndGet(); };
            f.runtime.dispatchWidget("gallery.preview_mode");
            Files.setPosixFilePermissions(path.getParent(), PosixFilePermissions.fromString("r-x------"));
            try { f.work(); }
            finally { Files.setPosixFilePermissions(path.getParent(), permissions); }
            assertFalse(f.clients.isEmpty(), "failed worker must leave a client completion pending");
            f.runtime.dispatchWidget("gallery.preview_mode");
            f.runtime.dispatchWidget("gallery.preview_mode");
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, "gallery"));
            assertEquals(1, f.workers.size(), "the existing serial chain admits one worker at a time");
            var latestFeedback = f.runtime.snapshot().status();
            f.work();
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, "gallery"));
            f.work();
            assertEquals(3, writes.get());
            assertEquals(CapePreviewMode.ELYTRA, f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT)
                    .preferences().preferredCapeMode());
            while (!f.clients.isEmpty()) f.clients.remove().run();
            assertEquals(latestFeedback, f.runtime.snapshot().status());
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, "gallery"));
            assertEquals(AddSourceTab.FILE, f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT)
                    .preferences().selectedAddSourceTab());
        } finally { f.runtime.close(); }
    }

    @Test void admittedWriteSurvivesScreenCloseButRuntimeRetirementRevokesQueuedWrite() throws Exception {
        var f = fixture();
        try {
            f.runtime.dispatchWidget("gallery.preview_mode");
            f.runtime.closeScreen();
            f.drain();
            assertEquals(ClientSnapshot.Lifecycle.CLOSED, f.runtime.snapshot().lifecycle());
            assertEquals(CapePreviewMode.ELYTRA, f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT)
                    .preferences().preferredCapeMode());
            f.runtime.reopen(); f.drain();
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, "gallery"));
            f.runtime.dispatchWidget("gallery.preview_mode");
            f.runtime.close(); f.drain();
            assertEquals(CapePreviewMode.ELYTRA, f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT)
                    .preferences().preferredCapeMode());
        } finally { if (!f.runtime.closed()) f.runtime.close(); }
    }

    @Test void lateProductionReloadCannotOverwriteAnExplicitChoiceWhileItsWriteIsHeld() throws Exception {
        var f = fixture();
        try {
            var loaded = new AtomicInteger();
            f.afterOperation = name -> { if (name.equals("initializeForRuntime")) loaded.incrementAndGet(); };
            f.runtime.dispatchWidget("gallery.preview_mode");
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, "gallery"));
            var heldWrite = f.workers.removeFirst();
            assertEquals(CapePreviewMode.CAPE, f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT)
                    .preferences().preferredCapeMode());
            f.runtime.closeScreen(); f.runtime.reopen();
            f.drain();
            assertEquals(1, loaded.get());
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, "gallery"));
            assertEquals(CapePreviewMode.CAPE, f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT)
                    .preferences().preferredCapeMode());
            f.workers.addFirst(heldWrite); f.drain();
            assertEquals(CapePreviewMode.ELYTRA, f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT)
                    .preferences().preferredCapeMode());
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"gallery", "editor", "providers"})
    void existingBusyActionsRejectModeDispatchWithoutConsumingTheNextReadyChoice(String screen) throws Exception {
        var f = fixture();
        try {
            open(f, screen);
            var writes = new AtomicInteger();
            f.beforeOperation = name -> { if (name.equals("updatePreferredCapeMode")) writes.incrementAndGet(); };
            f.runtime.dispatchWidget(screen.equals("editor") ? "editor.save"
                    : screen.equals("gallery") ? "gallery.reset" : "gallery.providers");
            assertTrue(f.runtime.snapshot().busy());
            f.runtime.dispatchWidget(screen + ".preview_mode", false, InteractionOrigin.POINTER);
            f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, screen + ".preview_mode");
            f.drain();
            assertEquals(0, writes.get());
            if (screen.equals("editor")) open(f, screen);
            assertEquals(PreviewRenderer.CapeMode.CAPE, mode(f, screen));
            assertTrue(f.view().widget(screen + ".preview_mode").orElseThrow().enabled());
            f.runtime.dispatchWidget(screen + ".preview_mode", false, InteractionOrigin.POINTER);
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, screen));
            f.drain(); assertEquals(1, writes.get());
        } finally { f.runtime.close(); }
    }

    @Test void renderingNavigationAndReopenDoNotWriteModeAndAnotherRuntimeHasNoStaticAuthority() throws Exception {
        var f = fixture();
        try {
            var writes = new AtomicInteger();
            f.beforeOperation = name -> { if (name.equals("updatePreferredCapeMode")) writes.incrementAndGet(); };
            for (int i = 0; i < 3; i++) f.view();
            open(f, "editor"); f.runtime.dispatchWidget("editor.cancel");
            open(f, "providers"); f.runtime.dispatchWidget("providers.back");
            f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
            assertEquals(0, writes.get());
            assertFalse(Files.exists(f.storage.layout().accountUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT)));
            f.runtime.dispatchWidget("gallery.preview_mode"); f.drain();
            var support = new GalleryPreviewInteractionJoinTest(); support.directory = directory.resolve("independent");
            var other = support.fixture(new GalleryInspectLayersTest.Layers());
            try {
                assertEquals(PreviewRenderer.CapeMode.CAPE, mode(other, "gallery"));
                assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, "gallery"));
            } finally { other.runtime.close(); }
        } finally { f.runtime.close(); }
    }

    @Test void rejectedWorkerIsANonblockingSaveFailureAndDoesNotBreakTheSerialChain() throws Exception {
        var f = fixture();
        try {
            f.rejectWorker = true;
            f.runtime.dispatchWidget("gallery.preview_mode");
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, "gallery"));
            assertFalse(f.runtime.snapshot().busy());
            assertTrue(f.view().texts().stream().anyMatch(t -> t.message().key().equals("nclskins.error.save")));
            f.rejectWorker = false;
            f.runtime.dispatchWidget("gallery.preview_mode"); f.drain();
            assertEquals(CapePreviewMode.CAPE, f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT)
                    .preferences().preferredCapeMode());
        } finally { f.runtime.close(); }
    }

    @Test void accountChangedBeforeDispatchCannotChangeAStillVisibleProviderTransform() throws Exception {
        var f = fixture();
        try {
            open(f, "providers");
            assertTrue(f.view().widget("providers.preview_mode").orElseThrow().enabled());
            var before = mode(f, "providers");
            var writes = new AtomicInteger();
            f.beforeOperation = name -> { if (name.equals("updatePreferredCapeMode")) writes.incrementAndGet(); };
            f.identity.set(new GameSessionTokenSource.SessionIdentity(new java.util.UUID(0, 808), "OtherFixture"));
            f.runtime.dispatchWidget("providers.preview_mode", false, InteractionOrigin.POINTER);
            assertEquals(before, mode(f, "providers"), "stale account action must not change even the local transform");
            f.drain();
            assertEquals(0, writes.get());
        } finally { f.runtime.close(); }
    }

    @Test void accountRoundTripUsesItsOwnDurableModeAndRejectsQueuedOldAccountWrites() throws Exception {
        var f = fixture();
        try {
            var other = new java.util.UUID(0, 707);
            var template = f.storage.loadOrCreateAccount(GalleryRuntimeSelectionTest.ACCOUNT);
            f.storage.updateAccount(other, old -> new com.naocraftlab.skins.core.model.AccountState(
                    old.schemaVersion(), other, template.skinAssets(), template.presets(), old.updatedAt()));
            f.storage.updatePreferredCapeMode(other, CapePreviewMode.CAPE, () -> true);
            byte[] otherPreferences = Files.readAllBytes(f.storage.layout().accountUiPreferences(other));
            f.runtime.dispatchWidget("gallery.preview_mode"); f.drain();
            assertEquals(CapePreviewMode.ELYTRA, f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT)
                    .preferences().preferredCapeMode());
            f.runtime.dispatchWidget("gallery.preview_mode");
            f.identity.set(new GameSessionTokenSource.SessionIdentity(other, "OtherFixture"));
            f.operations.usePreset(GalleryRuntimeSelectionTest.A);
            f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
            assertEquals(other, f.runtime.snapshot().account().orElseThrow().accountId());
            assertEquals(CapePreviewMode.CAPE, f.storage.loadUiPreferences(other).preferences().preferredCapeMode());
            assertEquals(PreviewRenderer.CapeMode.CAPE, mode(f, "gallery"));
            assertArrayEquals(otherPreferences, Files.readAllBytes(f.storage.layout().accountUiPreferences(other)));
            f.identity.set(new GameSessionTokenSource.SessionIdentity(GalleryRuntimeSelectionTest.ACCOUNT, "Fixture"));
            f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, "gallery"));
            assertEquals(CapePreviewMode.ELYTRA, f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT)
                    .preferences().preferredCapeMode());
        } finally { f.runtime.close(); }
    }

    @Test void absentAccessoryIsOnlyDerivedOffAndCannotOverwriteTheSavedPreference() throws Exception {
        var f = fixture();
        try {
            f.runtime.dispatchWidget("gallery.preview_mode"); f.drain();
            f.selectB(); GalleryInspectLayersTest.ordinary(f);
            assertEquals(PreviewRenderer.CapeMode.OFF, mode(f, "gallery"));
            assertTrue(f.view().widget("gallery.preview_mode").isEmpty());
            var before = ScopedEditorSaveTest.inventory(directory);
            f.runtime.dispatchWidget("gallery.preview_mode", false, InteractionOrigin.POINTER);
            f.drain();
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            assertEquals(CapePreviewMode.ELYTRA, f.storage.loadUiPreferences(GalleryRuntimeSelectionTest.ACCOUNT)
                    .preferences().preferredCapeMode());
            f.runtime.dispatchWidget(f.card(GalleryRuntimeSelectionTest.A));
            GalleryInspectLayersTest.ordinary(f);
            assertEquals(PreviewRenderer.CapeMode.ELYTRA, mode(f, "gallery"));
        } finally { f.runtime.close(); }
    }

    @Test void galleryChoiceReachesEditorBeforeAnyWorkerRuns() throws Exception {
        var f = fixture();
        try {
            var before = GalleryInspectLayersTest.main(f);
            assertTrue(f.view().widget("gallery.preview_mode").orElseThrow().enabled());
            f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, "gallery.preview_mode");
            var desired = GalleryInspectLayersTest.main(f).capeMode();
            assertNotEquals(before.capeMode(), desired);
            f.runtime.dispatchWidget("gallery.preset." + GalleryRuntimeSelectionTest.A + ".more");
            f.runtime.dispatchWidget("gallery.preset." + GalleryRuntimeSelectionTest.A + ".edit");
            assertEquals(desired, f.runtime.snapshot().editor().orElseThrow().preview().capeMode());
        } finally { f.runtime.close(); }
    }

    @Test void successfulGalleryChoiceSurvivesFreshRuntimeThroughProductionStorage() throws Exception {
        var f = fixture();
        var desired = GalleryInspectLayersTest.main(f).capeMode() == PreviewRenderer.CapeMode.CAPE
                ? PreviewRenderer.CapeMode.ELYTRA : PreviewRenderer.CapeMode.CAPE;
        try {
            assertTrue(f.view().widget("gallery.preview_mode").orElseThrow().enabled());
            f.runtime.dispatchWidget("gallery.preview_mode", false, InteractionOrigin.POINTER);
            assertEquals(desired, GalleryInspectLayersTest.main(f).capeMode());
            f.drain();
        } finally { f.runtime.close(); }
        var reopened = fixture();
        try {
            assertEquals(desired, GalleryInspectLayersTest.main(reopened).capeMode());
        } finally { reopened.runtime.close(); }
    }
}

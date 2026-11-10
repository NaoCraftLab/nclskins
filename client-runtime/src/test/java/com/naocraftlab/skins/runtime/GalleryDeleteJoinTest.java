package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.naocraftlab.skins.runtime.GalleryRuntimeSelectionTest.*;

final class GalleryDeleteJoinTest {
    @TempDir Path directory;

    GalleryRuntimeSelectionTest fixture() throws Exception {
        var f = new GalleryRuntimeSelectionTest();
        f.directory = directory;
        f.setup();
        return f;
    }

    void delete(GalleryRuntimeSelectionTest f, UUID id, InteractionOrigin origin) {
        String prefix = "gallery.preset." + id;
        f.runtime.dispatchWidget(prefix + ".more");
        f.runtime.dispatchWidget(prefix + ".delete", false, origin);
        var confirm = f.view().widget(prefix + ".delete_confirm").orElseThrow();
        assertTrue(confirm.enabled() && confirm.activationEnabled());
        f.runtime.dispatchWidget(confirm.id(), false, origin);
    }

    @Test void deletingLastFavoriteKeepsOtherDraftAndFocusesCollapsedRemainingHeader() throws Exception {
        var f = fixture();
        try {
            UUID removed = new UUID(0,90);
            f.storage.updateAccount(ACCOUNT,old -> {
                var presets = new ArrayList<>(old.presets());
                presets.add(new com.naocraftlab.skins.core.model.AppearancePreset(removed,"Removed",
                        com.naocraftlab.skins.core.model.SkinReference.accountDefault(),null,
                        com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(),GalleryApplyTest.NOW,GalleryApplyTest.NOW,null,true));
                return new com.naocraftlab.skins.core.model.AccountState(old.schemaVersion(),ACCOUNT,old.skinAssets(),presets,old.updatedAt());
            });
            f.runtime.closeScreen(); f.runtime.reopen(); f.drain();
            f.selectB();
            var candidate = f.runtime.galleryPreviewCandidate().orElseThrow();
            var appearance = f.storage.loadAppearance(ACCOUNT);
            f.runtime.dispatchWidget("gallery.your_appearances"); f.drain();
            assertTrue(f.view().widget(f.card(B)).isEmpty());
            delete(f,removed,InteractionOrigin.KEYBOARD); f.drain();
            var view = f.view();
            assertEquals(Optional.of(B),f.runtime.snapshot().selectedPresetId());
            assertEquals(Optional.of(A),f.runtime.snapshot().activePresetId());
            assertEquals(appearance,f.storage.loadAppearance(ACCOUNT));
            assertEquals(candidate,f.runtime.galleryPreviewCandidate().orElseThrow());
            assertTrue(view.widget("gallery.apply").orElseThrow().enabled());
            assertTrue(view.navigationNode("gallery.favorites").isEmpty());
            assertTrue(view.navigationNode(f.card(B)).isEmpty());
            assertEquals("gallery.your_appearances",view.focusRequest().orElseThrow().widgetId());
            assertTrue(view.widget("gallery.your_appearances").orElseThrow().enabled());
            assertEquals(Set.of("gallery.your_appearances"),f.storage.loadUiPreferences(ACCOUNT).preferences().collapsedGallerySections());
            assertEquals(List.of(A,B),f.storage.loadOrCreateAccount(ACCOUNT).presets().stream().map(p -> p.id()).toList());
            assertEquals(0,f.reconciliations.get());
        } finally { f.runtime.close(); }
    }

    @Test void deletingLastPresetRetainsDisabledBulkSlotWithoutWriteAuthority() throws Exception {
        var f = new GalleryRuntimeSelectionTest();
        f.directory=directory; f.initiallyActive=false; f.setup();
        try {
            var bounds=f.view().widget("gallery.sections").orElseThrow().bounds();
            delete(f,B,InteractionOrigin.KEYBOARD); f.drain();
            delete(f,A,InteractionOrigin.KEYBOARD); f.drain();
            var view=f.view();
            assertTrue(f.storage.loadOrCreateAccount(ACCOUNT).presets().isEmpty());
            assertTrue(view.widget("gallery.add").orElseThrow().enabled());
            assertEquals(bounds,view.widget("gallery.sections").orElseThrow().bounds());
            assertFalse(view.widget("gallery.sections").orElseThrow().enabled());
            assertTrue(view.navigationNode("gallery.favorites").isEmpty());
            assertTrue(view.navigationNode("gallery.your_appearances").isEmpty());
            assertEquals(1,f.reconciliations.get(),"Last-preset deletion retains its existing default-intent checkpoint");
            var before=ScopedEditorSaveTest.inventory(directory);
            assertFalse(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE,"gallery.sections"));
            f.runtime.dispatchWidget("gallery.sections",false,InteractionOrigin.POINTER); f.drain();
            assertEquals(before,ScopedEditorSaveTest.inventory(directory));
            assertTrue(f.sectionUpdates.isEmpty());
            assertEquals(1,f.reconciliations.get(),"Disabled section actions add no checkpoint");
        } finally { f.runtime.close(); }
    }

    @Test void deleteAndFavoriteShareLatestDurableMutationLane() throws Exception {
        var f = fixture();
        try {
            var calls = new ArrayList<String>();
            f.beforeOperation = name -> {
                if (name.equals("deletePreset") || name.equals("setPresetFavorite")) {
                    assertTrue(f.workerLane);
                    calls.add(name);
                }
            };
            delete(f, B, InteractionOrigin.POINTER);
            f.runtime.dispatchWidget("gallery.preset." + B + ".delete_confirm");
            new GalleryMetadataContinuityJoinTest().star(f, A);
            assertFalse(f.runtime.snapshot().busy());
            f.drain();
            var saved = f.storage.loadOrCreateAccount(ACCOUNT);
            assertEquals(List.of(A), saved.presets().stream().map(p -> p.id()).toList());
            assertTrue(saved.presets().get(0).favorite());
            assertEquals(List.of("deletePreset", "setPresetFavorite"), calls);
            assertTrue(f.runtime.snapshot().account().orElseThrow().presets().get(0).favorite());
        } finally { f.runtime.close(); }
    }

    @Test void closedScreenDoesNotRevokeAdmittedWriteOrRestoreOldRoute() throws Exception {
        var f = fixture();
        try {
            delete(f, B, InteractionOrigin.KEYBOARD);
            f.runtime.dispatchWidget("gallery.providers");
            assertEquals("providers", f.view().screenId());
            f.drain();
            assertEquals(List.of(A), f.storage.loadOrCreateAccount(ACCOUNT).presets().stream().map(p -> p.id()).toList());
            assertEquals("providers", f.view().screenId());
        } finally { f.runtime.close(); }
    }

    @Test void capturedAccountCannotRetargetBeforeWorker() throws Exception {
        var f = fixture();
        try {
            delete(f, B, InteractionOrigin.KEYBOARD);
            var before = ScopedEditorSaveTest.inventory(directory);
            f.identity.set(new com.naocraftlab.skins.client.GameSessionTokenSource.SessionIdentity(new UUID(0,99), "Other"));
            f.drain();
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            assertEquals(2, f.storage.loadOrCreateAccount(ACCOUNT).presets().size());
        } finally { f.runtime.close(); }
    }

    @Test void retirementDuringAccountLockWaitRefusesDurableDelete() throws Exception {
        var f = fixture();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var deletionThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        try {
            delete(f, B, InteractionOrigin.POINTER);
            var holder = executor.submit(() -> f.storage.updateAccount(ACCOUNT, account -> {
                locked.countDown();
                try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new RuntimeException(failure); }
                return account;
            }));
            assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var deletion = executor.submit(() -> { deletionThread.set(Thread.currentThread()); f.work(); });
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            boolean waitingInDeleteLock = false;
            while (System.nanoTime() < deadline && !waitingInDeleteLock) {
                Thread worker = deletionThread.get();
                if (worker != null && worker.getState() == Thread.State.WAITING) {
                    var stack = Arrays.asList(worker.getStackTrace());
                    waitingInDeleteLock = stack.stream().anyMatch(frame -> frame.getClassName().equals(
                            "com.naocraftlab.skins.core.storage.InterProcessLockManager") && frame.getMethodName().equals("acquire"))
                            && stack.stream().anyMatch(frame -> frame.getClassName().equals(
                            "com.naocraftlab.skins.core.storage.NclSkinsStorage") && frame.getMethodName().equals("mutateAccountAndAppearance"));
                }
                Thread.onSpinWait();
            }
            assertTrue(waitingInDeleteLock, "Actual delete must be parked inside the held production account lock");
            f.identity.set(new com.naocraftlab.skins.client.GameSessionTokenSource.SessionIdentity(new UUID(0,99), "Other"));
            release.countDown();
            holder.get(5, java.util.concurrent.TimeUnit.SECONDS);
            deletion.get(5, java.util.concurrent.TimeUnit.SECONDS);
            f.drain();
            assertEquals(2, f.storage.loadOrCreateAccount(ACCOUNT).presets().size());
            assertEquals(A, f.storage.loadAppearance(ACCOUNT).activePresetId());
        } finally {
            release.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            f.runtime.close();
        }
    }

    @Test void rejectedWorkerSettlesTicketAndAllowsRetry() throws Exception {
        var f = fixture();
        try {
            f.rejectWorker = true;
            delete(f, B, InteractionOrigin.POINTER);
            assertEquals(UiMessage.Severity.ERROR, f.runtime.snapshot().status().severity());
            assertFalse(f.runtime.snapshot().busy());
            f.rejectWorker = false;
            f.runtime.dispatchWidget("gallery.preset." + B + ".delete_confirm");
            f.drain();
            assertEquals(List.of(A), f.storage.loadOrCreateAccount(ACCOUNT).presets().stream().map(p -> p.id()).toList());
        } finally { f.runtime.close(); }
    }

    @Test void accountEpochRoundTripCannotReviveOldDelete() throws Exception {
        var f = fixture();
        try {
            delete(f, B, InteractionOrigin.POINTER);
            var before = ScopedEditorSaveTest.inventory(directory);
            f.identity.set(new com.naocraftlab.skins.client.GameSessionTokenSource.SessionIdentity(new UUID(0,99), "Other"));
            f.runtime.tick();
            f.identity.set(new com.naocraftlab.skins.client.GameSessionTokenSource.SessionIdentity(ACCOUNT, "Fixture"));
            f.runtime.tick();
            f.drain();
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            assertEquals(2, f.storage.loadOrCreateAccount(ACCOUNT).presets().size());
        } finally { f.runtime.close(); }
    }

    @Test void committedDeleteCannotStealReopenedScreenFocus() throws Exception {
        var f = fixture();
        try {
            delete(f, B, InteractionOrigin.KEYBOARD);
            f.work();
            assertEquals(1, f.storage.loadOrCreateAccount(ACCOUNT).presets().size());
            f.runtime.closeScreen();
            f.runtime.reopen();
            f.work();
            while (!f.clients.isEmpty()) f.clients.remove().run();
            f.drain();
            assertEquals("gallery", f.view().screenId());
            assertEquals(Optional.of(A), f.runtime.snapshot().selectedPresetId());
            assertFalse(f.view().focusRequest().map(r -> r.widgetId().contains(B.toString())).orElse(false));
        } finally { f.runtime.close(); }
    }

    @Test void failedDeleteClearsOnlyItsTicketAndCanRetry() throws Exception {
        var f = fixture();
        try {
            f.beforeOperation = name -> { if (name.equals("deletePreset")) throw new IllegalStateException("fixture failure"); };
            delete(f, B, InteractionOrigin.POINTER);
            f.drain();
            assertEquals(2, f.storage.loadOrCreateAccount(ACCOUNT).presets().size());
            assertEquals(UiMessage.Severity.ERROR, f.runtime.snapshot().status().severity());
            assertFalse(f.runtime.snapshot().busy());
            f.beforeOperation = name -> {};
            f.runtime.dispatchWidget("gallery.preset." + B + ".delete_confirm");
            f.drain();
            assertEquals(List.of(A), f.storage.loadOrCreateAccount(ACCOUNT).presets().stream().map(p -> p.id()).toList());
        } finally { f.runtime.close(); }
    }

    @Test void inlineDeleteCompletionCanReentrantlyCloseAndReopen() throws Exception {
        var f = fixture();
        try {
            f.inline = true;
            var reopened = new java.util.concurrent.atomic.AtomicBoolean();
            var subscription = f.runtime.subscribe(snapshot -> {
                if (snapshot.account().orElseThrow().presets().size() == 1 && reopened.compareAndSet(false, true)) {
                    f.runtime.closeScreen();
                    f.runtime.reopen();
                }
            });
            delete(f, B, InteractionOrigin.POINTER);
            f.drain();
            assertTrue(reopened.get());
            assertFalse(f.runtime.snapshot().busy());
            assertEquals(List.of(A), f.storage.loadOrCreateAccount(ACCOUNT).presets().stream().map(p -> p.id()).toList());
            assertEquals("gallery", f.view().screenId());
            subscription.close();
        } finally { f.runtime.close(); }
    }

    @Test void admittedDeletePublishesResponsiveViewsBeforeWorkerAndPreservesNewIntent() throws Exception {
        var f = fixture();
        try {
            f.storage.updateAccount(ACCOUNT, old -> {
                var presets = new ArrayList<>(old.presets());
                for (int i = 10; i < 130; i++) presets.add(new com.naocraftlab.skins.core.model.AppearancePreset(
                        new UUID(0, i), "A grid " + i, com.naocraftlab.skins.core.model.SkinReference.accountDefault(),
                        null, GalleryApplyTest.NOW, GalleryApplyTest.NOW));
                return new com.naocraftlab.skins.core.model.AccountState(old.schemaVersion(), ACCOUNT,
                        old.skinAssets(), presets, old.updatedAt());
            });
            f.selectB(); f.runtime.dispatchWidget("gallery.reset"); f.drain();
            var views = new ArrayList<ViewSpec>();
            var subscription = f.runtime.subscribe(ignored -> views.add(f.view()));
            delete(f, B, InteractionOrigin.KEYBOARD);
            assertFalse(f.workers.isEmpty(), "Delete must reach real held worker");
            assertEquals(122, f.storage.loadOrCreateAccount(ACCOUNT).presets().size());
            assertFalse(f.runtime.snapshot().busy(), "F2-10 delete must not own global Busy");
            for (var view : views) {
                assertTrue(view.widget("gallery.providers").orElseThrow().enabled());
                assertTrue(view.widget("gallery.search").orElseThrow().enabled());
            }
            f.runtime.dispatchWidget("gallery.preset." + B + ".delete_confirm");
            f.runtime.dispatchWidget(f.card(A), false, InteractionOrigin.POINTER);
            f.runtime.dispatchText("gallery.search", "A");
            var oldFocus = f.view().focusRequest();
            assertTrue(f.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, "gallery.search"));
            var movedFocus = f.view().focusRequest().orElseThrow();
            assertNotEquals(oldFocus, Optional.of(movedFocus));
            var surface = f.view().scrollSurface("gallery.cards").orElseThrow();
            assertTrue(surface.maximumPixels() > surface.viewport().height());
            f.runtime.pointerScrolled(surface.viewport().x() + 5, surface.viewport().y() + 5, 0, -0.137);
            var before = f.view();
            double offset = before.scrollSurface("gallery.cards").orElseThrow().offsetPixels();
            assertTrue(offset > surface.offsetPixels());
            assertNotEquals(Math.rint(offset), offset);
            assertEquals(movedFocus, before.focusRequest().orElseThrow());
            f.work();
            assertEquals(121, f.storage.loadOrCreateAccount(ACCOUNT).presets().size());
            assertTrue(f.storage.loadOrCreateAccount(ACCOUNT).presets().stream().noneMatch(p -> p.id().equals(B)));
            assertFalse(f.clients.isEmpty(), "Client publication must remain independently held");
            f.drain();
            assertEquals(Optional.of(A), f.runtime.snapshot().selectedPresetId());
            assertEquals(Optional.of("A"), f.view().widget("gallery.search").orElseThrow().value());
            assertEquals(before.focusRequest(), f.view().focusRequest());
            assertTrue(f.view().scrollSurface("gallery.cards").orElseThrow().maximumPixels()
                    > f.view().scrollSurface("gallery.cards").orElseThrow().viewport().height());
            assertEquals(before.scrollSurface("gallery.cards").orElseThrow().offsetPixels(),
                    f.view().scrollSurface("gallery.cards").orElseThrow().offsetPixels());
            assertEquals(0, f.reconciliations.get());
            subscription.close();
        } finally { f.runtime.close(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void capturedScopeIsCheckedAtFinalAccountReplacement(boolean retire) throws Exception {
        var f = fixture();
        try {
            var before = f.storage.loadOrCreateAccount(ACCOUNT);
            var appearance = f.storage.loadAppearance(ACCOUNT);
            var replacements = new java.util.concurrent.atomic.AtomicInteger();
            var retired = new java.util.concurrent.atomic.AtomicBoolean();
            var scope = new LibraryEditorPort.GalleryScope(ACCOUNT, 0, () -> {
                var stack = Arrays.asList(Thread.currentThread().getStackTrace());
                boolean accountReplace = stack.stream().anyMatch(frame -> frame.getClassName().equals(
                        "com.naocraftlab.skins.core.storage.AtomicFileWriter") && frame.getMethodName().equals("replace"))
                        && stack.stream().anyMatch(frame -> frame.getMethodName().equals("saveAccountLocked"))
                        && stack.stream().anyMatch(frame -> frame.getMethodName().equals("mutateAccountAndAppearance"))
                        && stack.stream().anyMatch(frame -> frame.getClassName().equals(
                        "com.naocraftlab.skins.core.service.LibraryService") && frame.getMethodName().equals("deletePreset"));
                if (accountReplace && replacements.incrementAndGet() == 2 && retire) retired.set(true);
                return !retired.get();
            });
            if (retire) assertThrows(java.io.IOException.class, () -> f.operations.deletePreset(scope, B));
            else f.operations.deletePreset(scope, B);
            assertEquals(2, replacements.get(), "Backup then final account replacement guards must execute");
            assertEquals(retire, retired.get());
            assertEquals(retire ? before.presets() : before.presets().stream().filter(p -> !p.id().equals(B)).toList(),
                    f.storage.loadOrCreateAccount(ACCOUNT).presets());
            assertEquals(appearance, f.storage.loadAppearance(ACCOUNT));
        } finally { f.runtime.close(); }
    }

    @Test void twoQueuedDeletesPublishCurrentDefaultMaterializationWithoutRewind() throws Exception {
        var f = fixture();
        try {
            var imported = f.operations.importSkin("Offline deletion control",
                    com.naocraftlab.skins.core.model.SkinVariant.CLASSIC,
                    java.nio.file.Files.readAllBytes(Path.of("../core/src/test/resources/cpm/classic-a.png")));
            var asset = imported.skinAssets().stream().filter(skin -> skin.source()
                    == com.naocraftlab.skins.core.model.SkinSource.IMPORTED).findFirst().orElseThrow();
            f.storage.updateAccount(ACCOUNT, old -> new com.naocraftlab.skins.core.model.AccountState(
                    old.schemaVersion(), ACCOUNT, old.skinAssets(), old.personalSkins(),
                    old.presets().stream().map(preset -> preset.id().equals(A)
                            ? new com.naocraftlab.skins.core.model.AppearancePreset(A, preset.name(),
                                    com.naocraftlab.skins.core.model.SkinReference.asset(asset.id()), preset.capeId(),
                                    preset.outerLayerVisibility(), preset.createdAt(), preset.updatedAt(),
                                    preset.offlineCape(), preset.favorite()) : preset).toList(),
                    old.updatedAt(), old.personalCapes()));
            f.operations.usePreset(A);
            f.runtime.closeScreen();
            f.runtime.reopen();
            f.drain();
            var offline = f.runtime.snapshot().providers().skin().offline().value();
            assertNotNull(offline, "Queued deletion must start from a real nonempty Offline payload");
            assertEquals(asset.sha256(), offline.sha256());
            assertEquals(offline, f.storage.loadAppearance(ACCOUNT).providers().skin().offline().value());
            var calls = new ArrayList<String>();
            f.beforeOperation = name -> { if (name.equals("deletePreset")) calls.add(name); };
            long revision = f.storage.loadAppearance(ACCOUNT).intentRevision();
            delete(f, A, InteractionOrigin.POINTER);
            f.runtime.dispatchWidget("gallery.preset." + A + ".delete_confirm");
            delete(f, B, InteractionOrigin.POINTER);
            assertFalse(f.runtime.snapshot().busy());
            assertEquals(2, f.storage.loadOrCreateAccount(ACCOUNT).presets().size());
            f.work();
            var first = f.storage.loadAppearance(ACCOUNT);
            assertTrue(first.intentRevision() > revision);
            assertNull(first.activePresetId());
            assertNull(first.providers().skin().offline().value());
            assertEquals(List.of(B), f.storage.loadOrCreateAccount(ACCOUNT).presets().stream().map(p -> p.id()).toList());
            while (!f.clients.isEmpty()) f.clients.remove().run();
            assertEquals(first.intentRevision(), f.runtime.snapshot().intentRevision());
            assertEquals(first.providers(), f.runtime.snapshot().providers());
            assertNull(f.runtime.snapshot().providers().skin().offline().value());
            assertFalse(f.workers.isEmpty());
            f.work();
            var last = f.storage.loadAppearance(ACCOUNT);
            assertTrue(last.intentRevision() > first.intentRevision());
            assertTrue(f.storage.loadOrCreateAccount(ACCOUNT).presets().isEmpty());
            while (!f.clients.isEmpty()) f.clients.remove().run();
            assertFalse(f.workers.isEmpty(), "Existing active-delete reconciliation remains separately queued");
            assertEquals(0, f.reconciliations.get(), "Durable publication precedes the existing remote continuation");
            assertEquals(2, calls.size(), "Repeated same-item confirmation must not admit a third delete");
            assertTrue(f.runtime.snapshot().account().orElseThrow().presets().isEmpty());
            assertTrue(f.runtime.snapshot().activePresetId().isEmpty());
            assertEquals(last.intentRevision(), f.runtime.snapshot().intentRevision());
            assertEquals(last.providers(), f.runtime.snapshot().providers());
            assertNull(last.providers().skin().offline().value());
            assertNull(f.runtime.snapshot().providers().skin().offline().value());
            assertEquals(0, f.reconciliations.get());
        } finally { f.runtime.close(); }
    }
}

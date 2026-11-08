package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.api.ProfileApi;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

final class GalleryApplyTest {
    @TempDir Path directory;
    static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW.plusSeconds(20), ZoneOffset.UTC);
    final AtomicBoolean current = new AtomicBoolean(true);
    final AtomicReference<GameSessionTokenSource.SessionIdentity> identity = new AtomicReference<>(
            new GameSessionTokenSource.SessionIdentity(ACCOUNT, "Fixture"));
    NclSkinsStorage storage;
    DefaultClientOperations operations;

    void setup(com.naocraftlab.skins.core.storage.GalleryStorageFixture.Fault observer) throws Exception {
        storage = com.naocraftlab.skins.core.storage.GalleryStorageFixture.create(directory, CLOCK, observer);
        storage.initialize();
        storage.updateAccount(ACCOUNT, account -> new AccountState(account.schemaVersion(), ACCOUNT,
                List.of(), List.of(new AppearancePreset(A, "A", SkinReference.accountDefault(), null, NOW, NOW),
                        new AppearancePreset(B, "B", SkinReference.accountDefault(), null, NOW, NOW)), NOW));
        GameSessionTokenSource tokens = new GameSessionTokenSource() {
            public SessionIdentity currentSession() { return identity.get(); }
            public <T,E extends Exception> T withAccessToken(TokenRequest<T,E> request) {
                throw new AssertionError("Gallery local commit must not acquire credentials");
            }
        };
        ProfileApi api = (ProfileApi) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ProfileApi.class}, (proxy, method, args) -> {
                    throw new AssertionError("Gallery local commit must not call profile API");
                });
        byte[] png = Files.readAllBytes(Path.of("../core/src/test/resources/cpm/classic-a.png"));
        operations = TestClientGraph.operations(tokens, api, storage, (BundledSkinSource) model -> png, CLOCK);
        operations.usePreset(A);
    }

    LibraryEditorPort.GalleryApplyRequest request(UUID preset, boolean patch) {
        return new LibraryEditorPort.GalleryApplyRequest(
                new LibraryEditorPort.GalleryScope(ACCOUNT, 7, current::get), preset,
                patch ? Optional.of(new LibraryEditorPort.GalleryMaskPatch(
                        OuterLayerVisibility.allVisible(), OuterLayerVisibility.noneVisible())) : Optional.empty(), 9);
    }

    @Test void activeMaskOnlyPreservesPartialDeliveryIdentity() throws Exception {
        setup(stage -> {});
        storage.updateAppearance(ACCOUNT, old -> new AccountAppearanceState(old.schemaVersion(), ACCOUNT,
                old.intentRevision(), old.activePresetId(), old.skinSha256(), old.skinVariant(), old.capeId(),
                old.outerLayerVisibility(), AppearanceSyncStatus.PARTIAL, old.settledRevision(),
                old.updatedAt(), old.providers()));
        var before = storage.loadAppearance(ACCOUNT);
        var result = operations.applyGalleryDraft(request(A, true));
        var after = storage.loadAppearance(ACCOUNT);
        assertFalse(result.selectionAssignment());
        assertEquals(before.providers().skin().minecraftDelivery(), after.providers().skin().minecraftDelivery());
        assertEquals(before.providers().cape().minecraftDelivery(), after.providers().cape().minecraftDelivery());
        assertEquals(before.providers().skin().minecraft(), after.providers().skin().minecraft());
        assertEquals(before.providers().cape().minecraft(), after.providers().cape().minecraft());
        assertEquals(before.providers().skin().configurationRevision(), after.providers().skin().configurationRevision());
        assertEquals(before.providers().cape().configurationRevision(), after.providers().cape().configurationRevision());
        assertEquals(AppearanceSyncStatus.PARTIAL, after.syncStatus());
        assertEquals(before.settledRevision(), after.settledRevision());
        assertEquals(before.intentRevision() + 1, after.intentRevision());
        assertEquals(OuterLayerVisibility.noneVisible(), result.outerLayerVisibility().orElseThrow());
    }

    @Test void activeUnknownAndPendingPreserveDeliveryWithoutCredentials() throws Exception {
        setup(stage -> {});
        for (var status : List.of(AppearanceSyncStatus.UNKNOWN, AppearanceSyncStatus.PENDING)) {
            storage.updateAccount(ACCOUNT, old -> new AccountState(old.schemaVersion(), ACCOUNT, old.skinAssets(),
                    old.personalSkins(), old.presets().stream().map(p -> new AppearancePreset(p.id(), p.name(),
                            p.skin(), p.capeId(), OuterLayerVisibility.allVisible(), p.createdAt(), p.updatedAt(),
                            p.offlineCape(), p.favorite())).toList(), old.updatedAt(), old.personalCapes()));
            storage.updateAppearance(ACCOUNT, old -> new AccountAppearanceState(old.schemaVersion(), ACCOUNT,
                    old.intentRevision(), A, old.skinSha256(), old.skinVariant(), old.capeId(),
                    OuterLayerVisibility.allVisible(), status, old.settledRevision(), old.updatedAt(),
                    com.naocraftlab.skins.core.provider.AppearanceProviders.fromIntent(old.intentRevision(),
                            old.skinSha256(), old.skinVariant(), old.capeId(), status)));
            var before = storage.loadAppearance(ACCOUNT);
            var result = operations.applyGalleryDraft(request(A, true));
            var after = storage.loadAppearance(ACCOUNT);
            assertEquals(status, after.syncStatus());
            assertFalse(result.selectionAssignment());
            assertEquals(before.providers().skin().minecraftDelivery(), after.providers().skin().minecraftDelivery());
            assertEquals(before.providers().cape().minecraftDelivery(), after.providers().cape().minecraftDelivery());
        }
    }

    @Test void activeUnchangedIsNoWriteAndUsesLatestLockedSelection() throws Exception {
        setup(stage -> {});
        var captured = request(B, false);
        operations.usePreset(B);
        var before = ScopedEditorSaveTest.inventory(directory);
        var result = operations.applyGalleryDraft(captured);
        assertFalse(result.selectionAssignment());
        assertEquals(before, ScopedEditorSaveTest.inventory(directory));
    }

    @Test void favoriteAndRenameAfterCaptureSurviveMaskCommit() throws Exception {
        setup(stage -> {});
        var captured = request(B, true);
        storage.updateAccount(ACCOUNT, old -> new AccountState(old.schemaVersion(), ACCOUNT, old.skinAssets(),
                old.personalSkins(), old.presets().stream().map(p -> p.id().equals(B)
                        ? new AppearancePreset(p.id(), "Renamed", p.skin(), "latest-cape", p.outerLayerVisibility(),
                                p.createdAt(), p.updatedAt(), p.offlineCape(), true) : p).toList(),
                old.updatedAt(), old.personalCapes()));
        var result = operations.applyGalleryDraft(captured);
        var latest = result.account().presets().get(1);
        assertEquals("Renamed", latest.name());
        assertEquals("latest-cape", latest.capeId());
        assertTrue(latest.favorite());
        assertEquals(NOW, latest.createdAt());
        assertEquals(CLOCK.instant(), latest.updatedAt());
        assertEquals(OuterLayerVisibility.noneVisible(), latest.outerLayerVisibility());
        assertEquals(latest.capeId(), storage.loadAppearance(ACCOUNT).capeId());
    }

    @Test void conflictRejectsWithoutAnyWrite() throws Exception {
        setup(stage -> {});
        var request = new LibraryEditorPort.GalleryApplyRequest(request(B, true).scope(), B,
                Optional.of(new LibraryEditorPort.GalleryMaskPatch(OuterLayerVisibility.noneVisible(),
                        OuterLayerVisibility.allVisible())), 9);
        var before = ScopedEditorSaveTest.inventory(directory);
        assertThrows(IllegalStateException.class, () -> operations.applyGalleryDraft(request));
        assertEquals(before, ScopedEditorSaveTest.inventory(directory));
    }

    @Test void deletedSelectionRejectsWithoutAnyWrite() throws Exception {
        setup(stage -> {});
        var captured = request(B, true);
        operations.deletePreset(new LibraryEditorPort.GalleryScope(operations.sessionIdentity().profileId(), 0, () -> true), B);
        var before = ScopedEditorSaveTest.inventory(directory);
        assertThrows(com.naocraftlab.skins.core.service.LibraryOperationException.class,
                () -> operations.applyGalleryDraft(captured));
        assertEquals(before, ScopedEditorSaveTest.inventory(directory));
    }

    @Test void staleAndForeignInitiatingScopeRejectWithoutWrites() throws Exception {
        setup(stage -> {});
        var before = ScopedEditorSaveTest.inventory(directory);
        current.set(false);
        assertThrows(java.io.IOException.class, () -> operations.applyGalleryDraft(request(B, true)));
        current.set(true);
        var foreign = new LibraryEditorPort.GalleryApplyRequest(
                new LibraryEditorPort.GalleryScope(UUID.fromString("00000000-0000-0000-0000-000000000004"),
                        7, current::get), B, Optional.empty(), 9);
        assertThrows(java.io.IOException.class, () -> operations.applyGalleryDraft(foreign));
        assertEquals(before, ScopedEditorSaveTest.inventory(directory));
    }

    @Test void beforeJournalRetirementHasNoWrites() throws Exception {
        var armed = new AtomicBoolean();
        setup(stage -> { if (armed.get() && stage.equals("BEFORE_JOURNAL")) current.set(false); });
        var before = ScopedEditorSaveTest.inventory(directory);
        armed.set(true);
        assertThrows(java.io.IOException.class, () -> operations.applyGalleryDraft(request(B, true)));
        assertEquals(before, ScopedEditorSaveTest.inventory(directory));
    }

    @Test void retirementAtLockedAdmissionHasNoWrites() throws Exception {
        setup(stage -> {});
        var before = ScopedEditorSaveTest.inventory(directory);
        var calls = new AtomicInteger();
        var captured = new LibraryEditorPort.GalleryApplyRequest(
                new LibraryEditorPort.GalleryScope(ACCOUNT, 7, () -> calls.incrementAndGet() < 2),
                B, request(B, true).maskPatch(), 9);
        assertThrows(java.io.IOException.class, () -> operations.applyGalleryDraft(captured));
        assertEquals(2, calls.get());
        assertEquals(before, ScopedEditorSaveTest.inventory(directory));
    }

    @Test void publishedJournalFinishesDespiteScopeRetirement() throws Exception {
        var armed = new AtomicBoolean();
        setup(stage -> { if (armed.get() && stage.equals("AFTER_JOURNAL")) current.set(false); });
        armed.set(true);
        var result = operations.applyGalleryDraft(request(B, true));
        assertFalse(current.get());
        assertEquals(B, result.activePresetId());
        assertEquals(result.account(), storage.loadOrCreateAccount(ACCOUNT));
        assertEquals(OuterLayerVisibility.noneVisible(), storage.loadAppearance(ACCOUNT).outerLayerVisibility());
    }

    void faultReopen(String boundary) throws Exception {
        var armed = new AtomicBoolean();
        setup(stage -> {
            if (armed.get() && stage.equals(boundary)) {
                armed.set(false);
                current.set(false);
                throw new java.io.IOException("injected local failure");
            }
        });
        var before = ScopedEditorSaveTest.inventory(directory);
        armed.set(true);
        assertThrows(java.io.IOException.class, () -> operations.applyGalleryDraft(request(B, true)));
        var reopened = new NclSkinsStorage(directory, new PngValidator(), CLOCK);
        var account = reopened.loadOrCreateAccount(ACCOUNT);
        var appearance = reopened.loadAppearance(ACCOUNT);
        if (boundary.equals("BEFORE_JOURNAL")) {
            assertEquals(before, ScopedEditorSaveTest.inventory(directory));
            assertEquals(A, appearance.activePresetId());
        } else {
            assertEquals(B, appearance.activePresetId());
            assertEquals(OuterLayerVisibility.noneVisible(), appearance.outerLayerVisibility());
            assertEquals(appearance.outerLayerVisibility(), account.presets().get(1).outerLayerVisibility());
        }
        assertFalse(Files.exists(reopened.layout().accountState(ACCOUNT).resolveSibling("cape-transaction.json")));
    }

    @Test void preJournalFailureIsNotSuccess() throws Exception { faultReopen("BEFORE_JOURNAL"); }
    @Test void postJournalFailureRecoversBothFilesAfterRetirement() throws Exception { faultReopen("AFTER_JOURNAL"); }
    @Test void betweenFilesFailureRecoversBothFilesAfterRetirement() throws Exception { faultReopen("AFTER_APPEARANCE"); }

    @Test void inactiveEquivalentPresetCommitsMaskAndNewAssignmentWithoutNetwork() throws Exception {
        setup(stage -> {});
        var before = storage.loadAppearance(ACCOUNT);
        var result = operations.applyGalleryDraft(request(B, true));
        var durable = storage.loadAppearance(ACCOUNT);
        assertEquals(B, result.activePresetId());
        assertTrue(result.selectionAssignment());
        assertEquals(B, durable.activePresetId());
        assertEquals(OuterLayerVisibility.noneVisible(), result.account().presets().get(1).outerLayerVisibility());
        assertEquals(durable.outerLayerVisibility(), result.account().presets().get(1).outerLayerVisibility());
        assertTrue(durable.providers().skin().minecraftDelivery().intentRevision()
                > before.providers().skin().minecraftDelivery().intentRevision());
        assertTrue(result.localAppearance().isPresent());
        assertTrue(result.remoteResult().isEmpty());
        assertEquals(result.account(), storage.loadOrCreateAccount(ACCOUNT));
    }
}

package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CatalogCollectionOrder;
import com.naocraftlab.skins.client.CatalogText;
import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.ExpectedAppearance;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.MinecraftSkinCatalog;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.PersonalSkinCatalog;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.ServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.client.SignedProfileResolver;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.api.PublicSkinImportException;
import com.naocraftlab.skins.core.importing.ExternalImportProbe;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.MutationResult;
import com.naocraftlab.skins.core.model.OwnedCapeEntry;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.model.PersonalSkinEntry;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.RemoteAssetState;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.service.ApplicationPhase;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.RecoveryAction;
import com.naocraftlab.skins.core.service.RemoteAppearanceImpact;
import com.naocraftlab.skins.core.service.SessionCheckPhase;
import com.naocraftlab.skins.core.service.SessionFailureContext;
import com.naocraftlab.skins.core.service.SessionStatus;
import com.naocraftlab.skins.core.service.SessionValidation;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientRuntimeTest {
    private static final DirectClientExecutor CLIENT = new DirectClientExecutor();
    private static final FilePicker CANCELLED_PICKER =
            () -> CompletableFuture.completedFuture(Optional.empty());
    private static final TextResolver TEXT = message -> switch (message.key()) {
        case "nclskins.editor.default_name" -> "Preset " + message.arguments().get(0);
        case "nclskins.gallery.copy_name" -> "Copy of " + message.arguments().get(0);
        default -> message.key();
    };

    @Test
    void storagePreflightIsSynchronousAndPropagatesFatalRuntimeFailures() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());

        runtime.verifyStorageAccess();

        assertEquals(1, operations.storagePreflightCalls);
        IllegalStateException expected = new IllegalStateException("fatal storage failure");
        operations.storagePreflightFailure = expected;
        assertSame(expected, assertThrows(IllegalStateException.class, runtime::verifyStorageAccess));
        assertEquals(2, operations.storagePreflightCalls);
    }

    @Test
    void unmatchedGalleryQuerySeedsTheCreatedPresetNameAfterCatalogSelection() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(854, 480, 0, 0);

        runtime.dispatchText("gallery.search", "Wanted preset");
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        runtime.dispatchWidget("add.catalog.skin:minecraft:steve");

        assertEquals(
                "Wanted preset",
                runtime.snapshot().editor().orElseThrow().name());
    }

    @Test
    void galleryNavigationPublishesOneShotFocusAndDoesNotActivatePresetAnchors() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(3);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(320, 240, 0, 0);

        assertTrue(runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, null));
        ViewSpec searchFocused = runtime.view(320, 240, 0, 0);
        assertEquals(Optional.of("gallery.search"),
                searchFocused.focusRequest().map(ViewSpec.FocusRequest::widgetId));
        runtime.acknowledgeViewRendered(searchFocused);
        assertEquals(Optional.of("gallery.search"),
                runtime.view(320, 240, 0, 0).focusRequest().map(ViewSpec.FocusRequest::widgetId),
                "rendering alone must not acknowledge an unapplied native focus request");
        runtime.acknowledgeFocusApplied(
                searchFocused.screenId(), searchFocused.focusRequest().orElseThrow());
        assertTrue(runtime.view(320, 240, 0, 0).focusRequest().isEmpty());

        assertTrue(runtime.dispatchNavigation(
                ViewSpec.NavigationCommand.TAB_FORWARD, "gallery.search"));
        assertEquals(Optional.of("gallery.add"),
                runtime.view(320, 240, 0, 0).focusRequest().map(ViewSpec.FocusRequest::widgetId));
        assertTrue(runtime.dispatchNavigation(
                ViewSpec.NavigationCommand.RIGHT, "gallery.add"));
        String presetAnchor = runtime.view(320, 240, 0, 0).focusRequest()
                .orElseThrow().widgetId();
        assertTrue(presetAnchor.startsWith("gallery.card."));
        assertFalse(runtime.dispatchNavigation(
                ViewSpec.NavigationCommand.ACTIVATE, presetAnchor));
        assertTrue(runtime.snapshot().addSource().isEmpty());
        assertTrue(runtime.snapshot().editor().isEmpty());
        assertEquals(0, operations.applyCalls);

        assertTrue(runtime.dispatchNavigation(
                ViewSpec.NavigationCommand.LEFT, presetAnchor));
        assertTrue(runtime.dispatchNavigation(
                ViewSpec.NavigationCommand.ACTIVATE, "gallery.add"));
        assertTrue(runtime.snapshot().addSource().isPresent());
    }

    @Test
    void galleryDeleteFocusLifecycleOnlyRunsForKeyboardOrigin() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(3);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(854, 480, 0, 0);
        UUID pointerPreset = operations.account.presets().get(0).id();

        runtime.dispatchWidget(
                "gallery.preset." + pointerPreset + ".delete",
                false,
                InteractionOrigin.POINTER);
        assertTrue(runtime.view(854, 480, 0, 0).focusRequest().isEmpty());
        runtime.dispatchWidget(
                "gallery.preset." + pointerPreset + ".delete_cancel",
                false,
                InteractionOrigin.POINTER);
        assertTrue(runtime.view(854, 480, 0, 0).focusRequest().isEmpty());

        UUID keyboardPreset = operations.account.presets().get(1).id();
        String prefix = "gallery.preset." + keyboardPreset;
        runtime.dispatchWidget(prefix + ".delete", false, InteractionOrigin.KEYBOARD);
        assertEquals(Optional.of(prefix + ".delete_cancel"),
                runtime.view(854, 480, 0, 0).focusRequest().map(ViewSpec.FocusRequest::widgetId));
        runtime.dispatchWidget(prefix + ".delete_cancel", false, InteractionOrigin.KEYBOARD);
        assertEquals(Optional.of(prefix + ".delete"),
                runtime.view(854, 480, 0, 0).focusRequest().map(ViewSpec.FocusRequest::widgetId));

        runtime.dispatchWidget(prefix + ".delete", false, InteractionOrigin.KEYBOARD);
        runtime.dispatchWidget(prefix + ".delete_confirm", false, InteractionOrigin.KEYBOARD);
        String remainingFocus = runtime.view(854, 480, 0, 0).focusRequest()
                .orElseThrow().widgetId();
        assertTrue(remainingFocus.equals("gallery.add")
                || remainingFocus.startsWith("gallery.card."));
        assertFalse(remainingFocus.equals("gallery.card." + keyboardPreset));
    }

    @Test
    void personalCatalogPointerDeleteNeverCreatesKeyboardFocusIntent() {
        FakeOperations operations = new FakeOperations();
        String hash = operations.seedPersonalSkin("Pointer skin");
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(854, 480, 0, 0);
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        String delete = "add.catalog.delete:"
                + PersonalSkinCatalog.COLLECTION_ID + ":" + hash;
        Optional<ViewSpec.FocusRequest> baselineFocus = runtime.view(854, 480, 0, 0).focusRequest();

        runtime.dispatchWidget(delete, false, InteractionOrigin.POINTER);
        assertEquals(baselineFocus, runtime.view(854, 480, 0, 0).focusRequest());
        runtime.dispatchWidget(
                "add.catalog.delete.cancel", false, InteractionOrigin.POINTER);
        assertEquals(baselineFocus, runtime.view(854, 480, 0, 0).focusRequest());

        runtime.dispatchWidget(delete, false, InteractionOrigin.POINTER);
        runtime.dispatchWidget(
                "add.catalog.delete.confirm", false, InteractionOrigin.POINTER);
        assertEquals(baselineFocus, runtime.view(854, 480, 0, 0).focusRequest());
        assertFalse(operations.account.personalSkins().get(0).visible());
    }

    @Test
    void fullCommandStateMachineUsesOneImmutableSnapshotSurface() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        List<ClientSnapshot> publications = new ArrayList<>();
        runtime.subscribe(publications::add);

        runtime.initialize();
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        assertEquals(2, runtime.snapshot().account().orElseThrow().skinAssets().size());
        assertFalse(runtime.snapshot().busy());

        runtime.importSkin("Imported", SkinVariant.CLASSIC, skinPng());
        UUID importedId = runtime.snapshot().selectedSkinId().orElseThrow();
        runtime.renameSkin(importedId, "Renamed");
        assertEquals("Renamed", findSkin(runtime.snapshot(), importedId).name());
        runtime.toggleSkinVariant(importedId);
        assertEquals(SkinVariant.SLIM, findSkin(runtime.snapshot(), importedId).variant());
        int skinsBeforeCopy = runtime.snapshot().account().orElseThrow().skinAssets().size();
        runtime.duplicateSkin(importedId, "Copy");
        assertEquals(skinsBeforeCopy + 1, runtime.snapshot().account().orElseThrow().skinAssets().size());
        runtime.deleteSkin(importedId);
        assertNull(findSkinOrNull(runtime.snapshot(), importedId));

        runtime.view(854, 480, 0, 0);
        runtime.dispatchWidget("gallery.add");
        assertTrue(runtime.snapshot().addSource().isPresent());
        runtime.dispatchWidget("add.tab.catalog");
        runtime.dispatchWidget("add.catalog.skin:minecraft:steve");
        assertTrue(runtime.snapshot().editor().isPresent());
        runtime.dispatchText("editor.name", "Created");
        runtime.dispatchWidget("editor.model");
        runtime.dispatchWidget("editor.save");
        UUID createdPreset = runtime.snapshot().selectedPresetId().orElseThrow();
        assertEquals("Created", findPreset(runtime.snapshot(), createdPreset).name());
        assertEquals(SkinVariant.SLIM, findSkin(
                        runtime.snapshot(), findPreset(runtime.snapshot(), createdPreset).skin().assetId())
                .variant());

        runtime.dispatchWidget("gallery.preset." + createdPreset + ".apply");
        assertEquals(Optional.of(createdPreset), runtime.snapshot().activePresetId());
        int appliesBeforeEdit = operations.applyCalls;
        runtime.dispatchWidget("gallery.preset." + createdPreset + ".edit");
        runtime.dispatchText("editor.name", "Active changed");
        runtime.dispatchWidget("editor.save");
        assertEquals(appliesBeforeEdit, operations.applyCalls);
        assertEquals(Optional.of(createdPreset), runtime.snapshot().activePresetId());
        assertEquals("Active changed", findPreset(runtime.snapshot(), createdPreset).name());

        runtime.dispatchWidget("gallery.preset." + createdPreset + ".edit");
        runtime.dispatchText("editor.name", "Cancelled change");
        runtime.dispatchWidget("editor.cancel");
        assertEquals("Active changed", findPreset(runtime.snapshot(), createdPreset).name());

        int presetsBeforeCopy = runtime.snapshot().account().orElseThrow().presets().size();
        AppearancePreset sourceBeforeCopy = findPreset(runtime.snapshot(), createdPreset);
        runtime.dispatchWidget("gallery.preset." + createdPreset + ".duplicate");
        PresetEditorModel duplicateDraft = runtime.snapshot().editor().orElseThrow();
        assertEquals(presetsBeforeCopy, runtime.snapshot().account().orElseThrow().presets().size());
        assertTrue(duplicateDraft.originalPresetId().isEmpty());
        assertEquals("Copy of " + sourceBeforeCopy.name(), duplicateDraft.name());
        assertEquals(sourceBeforeCopy.skin(), duplicateDraft.skin());
        assertEquals(sourceBeforeCopy.optionalCapeId(), duplicateDraft.capeId());
        assertEquals(sourceBeforeCopy.outerLayerVisibility(), duplicateDraft.preview().outerLayerVisibility());
        runtime.dispatchWidget("editor.cancel");
        assertEquals(presetsBeforeCopy, runtime.snapshot().account().orElseThrow().presets().size());

        runtime.dispatchWidget("gallery.preset." + createdPreset + ".duplicate");
        runtime.dispatchWidget("editor.save");
        UUID duplicated = runtime.snapshot().selectedPresetId().orElseThrow();
        assertNotEquals(createdPreset, duplicated);
        assertEquals(presetsBeforeCopy + 1, runtime.snapshot().account().orElseThrow().presets().size());
        assertEquals(sourceBeforeCopy, findPreset(runtime.snapshot(), createdPreset));
        assertEquals(Optional.of(createdPreset), runtime.snapshot().activePresetId());

        operations.result = MutationResult.PARTIAL;
        operations.recovery = Set.of(
                RecoveryAction.RETRY_CAPE,
                RecoveryAction.RESTORE_PREVIOUS_APPEARANCE);
        runtime.dispatchWidget("gallery.preset." + createdPreset + ".apply");
        assertTrue(runtime.snapshot().recoveryActions().contains(RecoveryAction.RETRY_CAPE));
        assertFalse(runtime.snapshot().recoveryActions()
                .contains(RecoveryAction.RESTORE_PREVIOUS_APPEARANCE));
        assertTrue(runtime.view(854, 480, 0, 0).widget("gallery.restore").isEmpty());
        assertTrue(runtime.snapshot().activePresetId().isEmpty());
        operations.result = MutationResult.APPLIED;
        operations.recovery = Set.of();
        runtime.dispatchWidget("gallery.restore");
        assertEquals(0, operations.restoreCalls);

        operations.session = session(SessionStatus.EXPIRED);
        int retriesBeforeStaleDispatch = operations.retrySessionCalls;
        runtime.dispatchWidget("gallery.retry_session");
        assertEquals(retriesBeforeStaleDispatch, operations.retrySessionCalls);
        assertTrue(runtime.snapshot().session().orElseThrow().valid());
        operations.rateLimited = true;
        runtime.tick();
        assertTrue(runtime.snapshot().rateLimited());

        runtime.closeScreen();
        assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
        runtime.reopen();
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        assertTrue(publications.size() > 15);
        assertTrue(publications.stream().allMatch(snapshot -> snapshot.generation() >= 0));
    }

    @Test
    void duplicateSaveFailureKeepsTheUnsavedDraftAndOriginalLibrary() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        AppearancePreset source = operations.account.presets().get(0);

        runtime.dispatchWidget("gallery.preset." + source.id() + ".duplicate");
        operations.failEditorSave = true;
        runtime.dispatchWidget("editor.save");

        PresetEditorModel draft = runtime.snapshot().editor().orElseThrow();
        assertTrue(draft.originalPresetId().isEmpty());
        assertEquals("Copy of " + source.name(), draft.name());
        assertFalse(draft.busy());
        assertEquals(List.of(source), runtime.snapshot().account().orElseThrow().presets());
    }

    @Test
    void explicitSessionRetryPublishesConnectingThenReturnsToOfflineOrClearsRecovery() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.session = session(SessionStatus.OFFLINE_OR_INVALID);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());

        runtime.initialize();

        ViewSpec offline = runtime.view(854, 480, 427, 180);
        assertEquals(
                UiMessage.info("nclskins.session.offline"),
                offline.texts().stream()
                        .filter(text -> text.id().equals("gallery.offline"))
                        .findFirst()
                        .orElseThrow()
                        .message());
        assertTrue(offline.widget("gallery.retry_session").orElseThrow().enabled());

        operations.retrySessionFailure = new IOException("session still unavailable");
        runtime.dispatchWidget("gallery.retry_session");

        assertTrue(runtime.snapshot().busy());
        assertEquals(UiMessage.info("nclskins.status.checking_session"), runtime.snapshot().status());
        ViewSpec connectingBeforeFailure = runtime.view(854, 480, 427, 180);
        assertEquals(
                UiMessage.info("nclskins.session.connecting"),
                connectingBeforeFailure.texts().stream()
                        .filter(text -> text.id().equals("gallery.offline"))
                        .findFirst()
                        .orElseThrow()
                        .message());
        assertFalse(connectingBeforeFailure.widget("gallery.retry_session").orElseThrow().enabled());
        assertTrue(runtime.snapshot().busy(), "fast failure must wait for a rendered feedback frame");

        runtime.acknowledgeViewRendered(connectingBeforeFailure);
        assertSessionRetryConnectingForFiveTicks(runtime);
        runtime.tick();

        assertFalse(runtime.snapshot().busy());
        ViewSpec failed = runtime.view(854, 480, 427, 180);
        assertEquals(
                UiMessage.info("nclskins.session.offline"),
                failed.texts().stream()
                        .filter(text -> text.id().equals("gallery.offline"))
                        .findFirst()
                        .orElseThrow()
                        .message());
        assertTrue(failed.widget("gallery.retry_session").orElseThrow().enabled());

        operations.retrySessionFailure = null;
        operations.session = TestFixtures.validSession();
        runtime.dispatchWidget("gallery.retry_session");

        ViewSpec connectingBeforeSuccess = runtime.view(854, 480, 427, 180);
        assertEquals(
                UiMessage.info("nclskins.session.connecting"),
                connectingBeforeSuccess.texts().stream()
                        .filter(text -> text.id().equals("gallery.offline"))
                        .findFirst()
                        .orElseThrow()
                        .message());
        assertFalse(connectingBeforeSuccess.widget("gallery.retry_session").orElseThrow().enabled());
        assertTrue(runtime.snapshot().busy(), "fast success must wait for a rendered feedback frame");

        runtime.acknowledgeViewRendered(connectingBeforeSuccess);
        assertSessionRetryConnectingForFiveTicks(runtime);
        runtime.tick();

        assertFalse(runtime.snapshot().busy());
        ViewSpec connected = runtime.view(854, 480, 427, 180);
        assertTrue(connected.texts().stream().noneMatch(text -> text.id().equals("gallery.offline")));
        assertTrue(connected.widget("gallery.retry_session").isEmpty());
        assertEquals(2, operations.retrySessionCalls);
    }

    @Test
    void unavailableOrExpiredTokenShowsOnlyOfflineAndCannotDispatchSessionRetry() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        SessionValidation valid = TestFixtures.validSession();
        operations.session = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                valid.sessionIdentity(),
                null,
                new SessionFailureContext(
                        SessionCheckPhase.TOKEN_SOURCE,
                        ApiFailureKind.TOKEN_UNAVAILABLE,
                        null),
                "no token");
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());

        runtime.initialize();
        ViewSpec view = runtime.view(854, 480, 427, 180);

        assertEquals(
                UiMessage.info("nclskins.session.offline"),
                view.texts().stream()
                        .filter(text -> text.id().equals("gallery.offline"))
                        .findFirst()
                        .orElseThrow()
                        .message());
        assertTrue(view.widget("gallery.retry_session").isEmpty());
        runtime.dispatchWidget("gallery.retry_session");
        assertEquals(0, operations.retrySessionCalls);

        operations.session = new SessionValidation(
                SessionStatus.EXPIRED,
                valid.sessionIdentity(),
                null,
                new SessionFailureContext(
                        SessionCheckPhase.PROFILE,
                        ApiFailureKind.SESSION_EXPIRED,
                        401),
                "restart required");
        ClientRuntime expiredRuntime = runtime(operations, Runnable::run, Optional.empty());
        expiredRuntime.initialize();
        ViewSpec expiredView = expiredRuntime.view(854, 480, 427, 180);
        assertEquals(
                UiMessage.info("nclskins.session.offline"),
                expiredView.texts().stream()
                        .filter(text -> text.id().equals("gallery.offline"))
                        .findFirst()
                        .orElseThrow()
                        .message());
        assertTrue(expiredView.widget("gallery.retry_session").isEmpty());
        expiredRuntime.dispatchWidget("gallery.retry_session");
        assertEquals(0, operations.retrySessionCalls);
    }

    @Test
    void invalidSessionRetryResultReturnsOfflineWithoutReconciliationAfterSixTicks() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.session = session(SessionStatus.OFFLINE_OR_INVALID);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();

        runtime.dispatchWidget("gallery.retry_session");
        ViewSpec connecting = runtime.view(854, 480, 427, 180);
        runtime.acknowledgeViewRendered(connecting);

        assertSessionRetryConnectingForFiveTicks(runtime);
        assertEquals(0, operations.reconciliationCalls);
        runtime.tick();

        assertFalse(runtime.snapshot().busy());
        assertFalse(runtime.snapshot().syncInProgress());
        assertFalse(runtime.snapshot().session().orElseThrow().valid());
        assertEquals(0, operations.reconciliationCalls);
        ViewSpec offline = runtime.view(854, 480, 427, 180);
        assertEquals(
                UiMessage.info("nclskins.session.offline"),
                offline.texts().stream()
                        .filter(text -> text.id().equals("gallery.offline"))
                        .findFirst()
                        .orElseThrow()
                        .message());
        assertTrue(offline.widget("gallery.retry_session").orElseThrow().enabled());
    }

    @Test
    void validUnknownRetryShowsConnectingAndDisablesRecoveryDuringReconciliation() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.appearanceSyncStatus = AppearanceSyncStatus.UNKNOWN;
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());

        runtime.initialize();
        worker.runFirst();
        assertTrue(runtime.view(854, 480, 427, 180)
                .widget("gallery.retry_session")
                .orElseThrow()
                .enabled());

        runtime.dispatchWidget("gallery.retry_session");

        ViewSpec connecting = runtime.view(854, 480, 427, 180);
        assertEquals(
                UiMessage.info("nclskins.session.connecting"),
                connecting.texts().stream()
                        .filter(text -> text.id().equals("gallery.offline"))
                        .findFirst()
                        .orElseThrow()
                        .message());
        runtime.acknowledgeViewRendered(connecting);
        advanceTicks(runtime, 6);
        assertTrue(runtime.snapshot().busy());
        worker.runFirst();

        assertTrue(runtime.snapshot().session().orElseThrow().valid());
        assertTrue(runtime.snapshot().syncInProgress());
        ViewSpec reconciling = runtime.view(854, 480, 427, 180);
        assertTrue(reconciling.texts().stream()
                .noneMatch(text -> text.id().equals("gallery.offline")));
        assertFalse(reconciling.widget("gallery.retry_session").orElseThrow().enabled());

        runtime.dispatchWidget("gallery.retry_session");
        assertEquals(1, operations.retrySessionCalls);

        worker.runFirst();

        assertFalse(runtime.snapshot().syncInProgress());
        assertEquals(
                List.of(ClientOperations.ReconciliationTrigger.SESSION_REFRESHED),
                operations.reconciliationTriggers);
        assertTrue(runtime.view(854, 480, 427, 180)
                .widget("gallery.retry_session")
                .orElseThrow()
                .enabled());
    }

    @Test
    void unknownRetryDoesNotLatchSyncWhileLocalRebindHasNotHandedOffToReconciliation() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.appearanceRevision = 3;
        operations.appearanceSyncStatus = AppearanceSyncStatus.UNKNOWN;
        CompletableFuture<Optional<SignedProfileResolver.ResolvedProfile<String>>> resolution =
                new CompletableFuture<>();
        AppearanceRefreshCoordinator<String> refresh = new AppearanceRefreshCoordinator<>(
                CLIENT,
                ignored -> resolution,
                ignored -> PlayerAppearanceSink.ApplyResult.UPDATED,
                DiagnosticSinks.discarding());
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.of(refresh));

        runtime.initialize();
        worker.runFirst();
        AppliedAppearance local = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID,
                "a".repeat(64),
                SkinVariant.CLASSIC,
                Optional.empty());
        operations.durable = Optional.of(new ClientOperations.DurableAppearance(
                TestFixtures.ACCOUNT_ID,
                operations.appearanceRevision,
                AppearanceSyncStatus.UNKNOWN,
                Optional.empty(),
                Optional.of(local),
                Optional.empty()));

        runtime.dispatchWidget("gallery.retry_session");
        ViewSpec connecting = runtime.view(854, 480, 427, 180);
        runtime.acknowledgeViewRendered(connecting);
        worker.runFirst();
        advanceTicks(runtime, 6);

        assertFalse(runtime.snapshot().syncInProgress());
        assertEquals(0, worker.size(), "reconciliation must wait for the local rebind");
        assertTrue(runtime.view(854, 480, 427, 180)
                .widget("gallery.retry_session")
                .orElseThrow()
                .enabled());
        assertEquals(1, operations.retrySessionCalls);
        assertEquals(0, operations.reconciliationCalls);

        resolution.complete(Optional.empty());
        assertEquals(1, worker.size());
        assertTrue(runtime.snapshot().syncInProgress());

        worker.runFirst();

        assertFalse(runtime.snapshot().syncInProgress());
        assertEquals(
                List.of(ClientOperations.ReconciliationTrigger.SESSION_REFRESHED),
                operations.reconciliationTriggers);
    }

    @Test
    void retryFailureHidesSessionRecoveryDuringCooldownAndCoalescesDirectRedispatch() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.session = session(SessionStatus.OFFLINE_OR_INVALID);
        operations.retrySessionFailure = new IOException("rate limited");
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());

        runtime.initialize();
        worker.runFirst();
        runtime.dispatchWidget("gallery.retry_session");
        ViewSpec connecting = runtime.view(854, 480, 427, 180);
        runtime.acknowledgeViewRendered(connecting);
        operations.rateLimited = true;

        worker.runFirst();
        advanceTicks(runtime, 6);

        assertTrue(runtime.snapshot().rateLimited());
        ViewSpec rateLimited = runtime.view(854, 480, 427, 180);
        assertTrue(rateLimited.widget("gallery.retry_session").isEmpty());
        assertTrue(rateLimited.texts().stream()
                .noneMatch(text -> text.id().equals("gallery.offline")));
        runtime.dispatchWidget("gallery.retry_session");
        assertEquals(1, operations.retrySessionCalls);
    }

    @Test
    void eachAddOpenReloadsCrossInstancePreferencesAndExplicitModelChoicesPersist() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        operations.uiPreferences = new AccountUiPreferences(
                AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                TestFixtures.ACCOUNT_ID,
                AddSourceTab.CATALOG,
                Optional.of(SkinVariant.SLIM),
                Set.of(MinecraftSkinCatalog.COLLECTION_ID));

        runtime.dispatchWidget("gallery.add");

        AddSourceModel reloaded = runtime.snapshot().addSource().orElseThrow();
        assertEquals(AddSourceTab.CATALOG, reloaded.selectedTab());
        assertEquals(SkinVariant.SLIM, reloaded.preferredVariant());
        assertTrue(reloaded.collectionCollapsed(MinecraftSkinCatalog.COLLECTION_ID));

        runtime.dispatchWidget("add.catalog.filter");
        assertEquals(
                Optional.of(SkinVariant.CLASSIC),
                operations.uiPreferences.preferredSkinVariant());
        runtime.dispatchWidget("add.catalog.filter", true);
        assertEquals(AddSourceModel.CatalogFilter.ALL,
                runtime.snapshot().addSource().orElseThrow().filter());
        runtime.dispatchWidget("add.catalog.filter", true);
        assertEquals(AddSourceModel.CatalogFilter.SLIM,
                runtime.snapshot().addSource().orElseThrow().filter());
        assertEquals(
                Optional.of(SkinVariant.SLIM),
                operations.uiPreferences.preferredSkinVariant());
    }

    @Test
    void perOpenReloadDoesNotReviveAStalePreferenceRemovedByAnotherInstance() {
        FakeOperations operations = new FakeOperations();
        operations.uiPreferences = new AccountUiPreferences(
                AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                TestFixtures.ACCOUNT_ID,
                AddSourceTab.FILE,
                Optional.of(SkinVariant.SLIM),
                Set.of());
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        operations.uiPreferences = AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID);

        runtime.dispatchWidget("gallery.add");

        assertEquals(
                SkinVariant.CLASSIC,
                runtime.snapshot().addSource().orElseThrow().preferredVariant());
    }

    @Test
    void cancellingCatalogEditorRestoresTheExactTransientCatalogState() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        runtime.dispatchText("add.catalog.search", "e");
        runtime.dispatchWidget("add.catalog.filter");
        runtime.dispatchWidget("add.catalog.filter");
        AddSourceModel beforeEditor = runtime.snapshot().addSource().orElseThrow();

        runtime.dispatchWidget("add.catalog.skin:minecraft:steve");
        assertTrue(runtime.snapshot().editor().isPresent());
        runtime.dispatchWidget("editor.cancel");

        assertSame(beforeEditor, runtime.snapshot().addSource().orElseThrow());
        assertEquals("e", runtime.snapshot().addSource().orElseThrow().query());
        assertEquals(
                AddSourceModel.CatalogFilter.SLIM,
                runtime.snapshot().addSource().orElseThrow().filter());
    }

    @Test
    void catalogSelectionKeepsAValidPairVariantAndTotalFailureLeavesCatalogUsable() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        operations.failedCatalogModels = Set.of(SkinModel.CLASSIC);

        runtime.dispatchWidget("add.catalog.skin:minecraft:steve");

        assertEquals(SkinVariant.SLIM, runtime.snapshot().editor().orElseThrow().variant());
        assertEquals(
                Set.of(SkinVariant.SLIM),
                runtime.snapshot().editor().orElseThrow().availableCatalogVariants());

        runtime.dispatchWidget("editor.cancel");
        operations.failedCatalogModels = Set.of(SkinModel.CLASSIC, SkinModel.SLIM);
        runtime.dispatchWidget("add.catalog.skin:minecraft:steve");

        assertTrue(runtime.snapshot().editor().isEmpty());
        assertTrue(runtime.snapshot().addSource().isPresent());
    }

    @Test
    void offlineCatalogEditorPublishesSelectedDraftPreviewBeforeSave() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");

        runtime.dispatchWidget("add.catalog.skin:minecraft:steve");

        ViewSpec.Preview preview = runtime.view(854, 480, 0, 0).previews().stream()
                .filter(candidate -> candidate.id().equals("editor.preview"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                Optional.of(new ViewSpec.CatalogImage("minecraft", "steve")),
                preview.catalogImage());
        assertArrayEquals(
                operations.catalogPng,
                runtime.loadSkinPreview(preview).join().orElseThrow());
        assertTrue(runtime.snapshot().selectedPresetId().isEmpty());
    }

    @Test
    void reopeningAddReloadsTheSameCatalogPreviewAfterTheResourceStackChanges() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(854, 480, 0, 0);
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");

        ViewSpec.Preview firstPreview = runtime.view(854, 480, 0, 0).previews().stream()
                .filter(preview -> preview.catalogImage().isPresent())
                .findFirst()
                .orElseThrow();
        byte[] first = runtime.loadSkinPreview(firstPreview).join().orElseThrow();
        assertEquals(1, operations.catalogPreviewCalls);

        runtime.dispatchWidget("add.cancel");
        operations.catalogPng = skinPng(0xFF2266AA);
        runtime.dispatchWidget("gallery.add");
        ViewSpec.Preview reloadedPreview = runtime.view(854, 480, 0, 0).previews().stream()
                .filter(preview -> preview.catalogImage().isPresent())
                .findFirst()
                .orElseThrow();
        byte[] reloaded = runtime.loadSkinPreview(reloadedPreview).join().orElseThrow();

        assertEquals(2, operations.catalogPreviewCalls);
        assertFalse(java.util.Arrays.equals(first, reloaded));
        assertArrayEquals(operations.catalogPng, reloaded);
    }

    @Test
    void personalCatalogReusesItsAssetAndManualRemovalKeepsPresetsAndData() {
        FakeOperations operations = new FakeOperations();
        String hash = operations.seedPersonalSkin("From file");
        UUID personalAssetId = operations.account.personalSkins().get(0)
                .optionalAssetId(SkinVariant.CLASSIC)
                .orElseThrow();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(854, 480, 0, 0);

        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");

        AddSourceModel opened = runtime.snapshot().addSource().orElseThrow();
        assertEquals(
                PersonalSkinCatalog.COLLECTION_ID,
                opened.collections().get(0).id());
        ViewSpec catalog = runtime.view(854, 480, 0, 0);
        assertTrue(catalog.widgets().stream().anyMatch(widget ->
                widget.id().equals("add.catalog.delete:"
                        + PersonalSkinCatalog.COLLECTION_ID + ":" + hash)
                        && widget.kind() == ViewSpec.WidgetKind.ICON_BUTTON));

        int assetsBeforeReuse = operations.account.skinAssets().size();
        runtime.dispatchWidget("add.catalog.skin:" + PersonalSkinCatalog.COLLECTION_ID + ":" + hash);

        PresetEditorModel editor = runtime.snapshot().editor().orElseThrow();
        assertEquals(SkinReference.asset(personalAssetId), editor.skin());
        assertTrue(editor.png().isPresent());
        assertTrue(editor.saveRequest().pngBytes().isEmpty());
        runtime.dispatchWidget("editor.save");

        assertEquals(assetsBeforeReuse, operations.account.skinAssets().size());
        assertEquals(1, operations.account.presets().size());
        assertEquals(
                SkinReference.asset(personalAssetId),
                operations.account.presets().get(0).skin());

        runtime.dispatchWidget("gallery.add");
        int assetsBeforeRemoval = operations.account.skinAssets().size();
        int presetsBeforeRemoval = operations.account.presets().size();
        runtime.dispatchWidget(
                "add.catalog.delete:" + PersonalSkinCatalog.COLLECTION_ID + ":" + hash,
                false,
                InteractionOrigin.KEYBOARD);

        assertEquals("add_source", runtime.view(854, 480, 0, 0).screenId());
        assertEquals(0, operations.removePersonalCalls);
        runtime.dispatchWidget(
                "add.catalog.delete.cancel", false, InteractionOrigin.KEYBOARD);
        ViewSpec restored = runtime.view(854, 480, 0, 0);
        assertEquals("add_source", restored.screenId());
        assertEquals(
                Optional.of("add.catalog.delete:"
                        + PersonalSkinCatalog.COLLECTION_ID + ":" + hash),
                restored.focusRequest().map(ViewSpec.FocusRequest::widgetId));

        runtime.dispatchWidget("add.catalog.delete:"
                + PersonalSkinCatalog.COLLECTION_ID + ":" + hash);
        operations.failPersonalRemoval = true;
        runtime.dispatchWidget("add.catalog.delete.confirm");

        ViewSpec failed = runtime.view(854, 480, 0, 0);
        assertEquals("add_source", failed.screenId());
        assertTrue(failed.texts().stream().anyMatch(text ->
                text.id().equals("add.catalog.status")
                        && text.message().severity() == UiMessage.Severity.ERROR));
        assertTrue(operations.account.personalSkins().get(0).visible());

        operations.failPersonalRemoval = false;
        runtime.dispatchWidget("add.catalog.delete.confirm");

        assertEquals(2, operations.removePersonalCalls);
        assertFalse(operations.account.personalSkins().get(0).visible());
        assertEquals(assetsBeforeRemoval, operations.account.skinAssets().size());
        assertEquals(presetsBeforeRemoval, operations.account.presets().size());
        assertFalse(runtime.snapshot().addSource().orElseThrow().collections().stream()
                .anyMatch(collection -> PersonalSkinCatalog.isCollection(collection.id())));
    }

    @Test
    void otherPlayersCollectionRenameAndDeleteTargetThatExactCollection() {
        FakeOperations operations = new FakeOperations();
        String hash = operations.seedPersonalSkin("jeb_", PersonalSkinSource.PLAYER_NAME);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");

        String renameId = "add.catalog.rename:"
                + PersonalSkinCatalog.OTHER_PLAYERS_COLLECTION_ID + ':' + hash;
        String deleteId = "add.catalog.delete:"
                + PersonalSkinCatalog.OTHER_PLAYERS_COLLECTION_ID + ':' + hash;
        ViewSpec catalog = runtime.view(854, 480, 0, 0);
        assertTrue(catalog.widget(renameId).isPresent());
        assertTrue(catalog.widget(deleteId).isPresent());

        runtime.dispatchWidget(renameId);
        ViewSpec rename = runtime.view(854, 480, 0, 0);
        assertEquals(Optional.of("add.catalog.rename.name"),
                rename.focusRequest().map(ViewSpec.FocusRequest::widgetId));
        assertTrue(rename.widget("add.catalog.rename.name").orElseThrow().selectAllOnFocusAcquire());
        runtime.dispatchText("add.catalog.rename.name", "Dinnerbone");
        runtime.dispatchWidget("add.catalog.rename.save");
        assertEquals("Dinnerbone", operations.account.personalSkins().get(0).displayName());

        runtime.dispatchWidget(deleteId);
        assertEquals(PersonalSkinCatalog.OTHER_PLAYERS_COLLECTION_ID,
                runtime.snapshot().addSource().orElseThrow().personalSkinDeletion()
                        .orElseThrow().collectionId());
        runtime.dispatchWidget("add.catalog.delete.confirm");
        assertFalse(operations.account.personalSkins().get(0).visible());
        assertEquals(1, operations.removePersonalCalls);
    }

    @Test
    void personalCatalogModesResetAtWorkspaceBoundariesAndRemainMutuallyExclusive() {
        FakeOperations operations = new FakeOperations();
        String hash = operations.seedPersonalSkin("Workspace skin");
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");

        String collectionId = PersonalSkinCatalog.COLLECTION_ID;
        String renameId = "add.catalog.rename:" + collectionId + ':' + hash;
        String deleteId = "add.catalog.delete:" + collectionId + ':' + hash;

        runtime.dispatchWidget(renameId);
        assertTrue(runtime.view(854, 480, 0, 0).widget("add.catalog.rename.name").isPresent());
        runtime.dispatchWidget("add.tab.file");
        assertEquals(AddSourceTab.FILE,
                runtime.snapshot().addSource().orElseThrow().selectedTab());
        runtime.dispatchWidget("add.tab.catalog");
        assertTrue(runtime.view(854, 480, 0, 0).widget(renameId).isPresent());
        assertTrue(runtime.view(854, 480, 0, 0).widget("add.catalog.rename.name").isEmpty());

        runtime.dispatchWidget(renameId);
        runtime.dispatchWidget(deleteId);
        assertTrue(runtime.view(854, 480, 0, 0).widget("add.catalog.rename.name").isEmpty());
        assertTrue(runtime.snapshot().addSource().orElseThrow().personalSkinDeletion().isPresent());
        runtime.dispatchWidget(renameId);
        assertTrue(runtime.snapshot().addSource().orElseThrow().personalSkinDeletion().isEmpty());
        assertTrue(runtime.view(854, 480, 0, 0).widget("add.catalog.rename.name").isPresent());

        runtime.dispatchWidget("add.catalog.collection:" + collectionId);
        assertTrue(runtime.snapshot().addSource().orElseThrow().collectionCollapsed(collectionId));
        assertTrue(runtime.view(854, 480, 0, 0).widget("add.catalog.rename.name").isEmpty());
        runtime.dispatchWidget("add.catalog.collection:" + collectionId);
        assertTrue(runtime.view(854, 480, 0, 0).widget(renameId).isPresent());

        runtime.dispatchWidget(deleteId);
        runtime.dispatchWidget("add.cancel");
        assertTrue(runtime.snapshot().addSource().isEmpty(),
                "footer Cancel must close the flow in the same dispatch");
        runtime.dispatchWidget("gallery.add");
        assertTrue(runtime.snapshot().addSource().orElseThrow().personalSkinDeletion().isEmpty());
        assertTrue(runtime.view(854, 480, 0, 0).widget("add.catalog.rename.name").isEmpty());
    }

    @Test
    void galleryDeleteConfirmationDoesNotSurviveLeavingGallery() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(2);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();
        String prefix = "gallery.preset." + presetId;

        runtime.dispatchWidget(prefix + ".delete");
        assertTrue(runtime.view(854, 480, 0, 0)
                .widget(prefix + ".delete_cancel").isPresent());
        runtime.dispatchWidget("gallery.add");
        assertTrue(runtime.snapshot().addSource().isPresent());
        runtime.dispatchWidget("add.cancel");

        ViewSpec gallery = runtime.view(854, 480, 0, 0);
        assertTrue(gallery.widgets().stream()
                .noneMatch(widget -> widget.id().endsWith(".delete_cancel")));
        assertTrue(gallery.widgets().stream()
                .noneMatch(widget -> widget.id().endsWith(".delete_confirm")));
        assertTrue(gallery.widgets().stream()
                .filter(widget -> widget.id().endsWith(".delete"))
                .allMatch(ViewSpec.Widget::enabled));
    }

    @Test
    void addCancelPreservesTheFractionalGalleryPositionAndQuery() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());

        runtime.initialize();
        ViewSpec before = runtime.view(320, 240, 0, 0);
        int initialX = panelX(before, "gallery.card.add");
        runtime.dispatchText("gallery.search", "Preset");
        runtime.pointerScrolled(160, 100, 0.0, -0.5);
        int preservedX = panelX(runtime.view(320, 240, 0, 0), "gallery.card.add");
        assertTrue(preservedX < initialX);

        runtime.dispatchWidget("gallery.add");
        assertTrue(runtime.snapshot().addSource().isPresent());
        runtime.dispatchWidget("add.cancel");

        assertTrue(runtime.snapshot().editor().isEmpty());
        assertTrue(runtime.snapshot().addSource().isEmpty());
        ViewSpec restored = runtime.view(320, 240, 0, 0);
        assertEquals(preservedX, panelX(restored, "gallery.card.add"));
        assertEquals("Preset", restored.widget("gallery.search").orElseThrow().value().orElseThrow());
        assertEquals(5, runtime.snapshot().account().orElseThrow().presets().size());
    }

    @Test
    void duplicateDraftEscapePreservesFractionalGalleryPositionAndQuery() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        UUID sourceId = operations.account.presets().get(0).id();

        runtime.dispatchText("gallery.search", "Preset");
        runtime.view(320, 240, 0, 0);
        runtime.pointerScrolled(160, 100, 0.0, -0.5);
        ViewSpec before = runtime.view(320, 240, 0, 0);
        int fractionalX = panelX(before, "gallery.card.add");
        assertEquals("Preset", before.widget("gallery.search").orElseThrow().value().orElseThrow());

        runtime.dispatchWidget("gallery.preset." + sourceId + ".duplicate");
        runtime.escapePressed();

        ViewSpec restored = runtime.view(320, 240, 0, 0);
        assertEquals(fractionalX, panelX(restored, "gallery.card.add"));
        assertEquals("Preset", restored.widget("gallery.search").orElseThrow().value().orElseThrow());
        assertEquals(5, runtime.snapshot().account().orElseThrow().presets().size());
    }

    @Test
    void galleryWheelPreservesMagnitudeAndAppliesFractionalBoundedPositionsImmediately() {
        FakeOperations halfOperations = new FakeOperations();
        halfOperations.account = TestFixtures.account(5);
        ClientRuntime half = runtime(halfOperations, Runnable::run, Optional.empty());
        half.initialize();
        int startX = panelX(half.view(320, 240, 0, 0), "gallery.card.add");

        half.pointerScrolled(160, 100, 0.0, -0.5);

        assertEquals(startX - 16, panelX(half.view(320, 240, 0, 0), "gallery.card.add"));
        assertEquals(0, half.snapshot().galleryOffset(),
                "a fractional wheel target must not snap to the next card position");

        FakeOperations fullOperations = new FakeOperations();
        fullOperations.account = TestFixtures.account(5);
        ClientRuntime full = runtime(fullOperations, Runnable::run, Optional.empty());
        full.initialize();
        full.view(320, 240, 0, 0);
        full.pointerScrolled(160, 100, 0.0, -1.0);

        assertEquals(startX - 32, panelX(full.view(320, 240, 0, 0), "gallery.card.add"));
    }

    @Test
    void nativeScrollFeedbackUsesAbsolutePixelsAndRejectsStaleSurfaceIds() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        int startX = panelX(runtime.view(320, 240, 0, 0), "gallery.card.add");

        runtime.nativeScrollPositionChanged("gallery.cards", 16.0);
        assertEquals(startX - 16, panelX(runtime.view(320, 240, 0, 0), "gallery.card.add"));
        runtime.nativeScrollPositionChanged("gallery.cards", 32.0);
        assertEquals(startX - 32, panelX(runtime.view(320, 240, 0, 0), "gallery.card.add"),
                "native feedback is an absolute pixel offset, not another wheel delta");

        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        runtime.view(320, 240, 160, 100);
        runtime.nativeScrollPositionChanged("gallery.cards", 96.0);
        assertEquals(0, runtime.snapshot().addSource().orElseThrow().scrollOffset(),
                "feedback from the previous screen must be ignored");
        runtime.nativeScrollPositionChanged("add.catalog", 16.0);
        assertEquals(16, runtime.snapshot().addSource().orElseThrow().scrollOffset());
        runtime.nativeScrollPositionChanged("add.catalog", 48.0);
        assertEquals(48, runtime.snapshot().addSource().orElseThrow().scrollOffset());

        runtime.nativeScrollPositionChanged("unknown.surface", 100.0);
        assertEquals(48, runtime.snapshot().addSource().orElseThrow().scrollOffset());
        assertThrows(IllegalArgumentException.class,
                () -> runtime.nativeScrollPositionChanged("add.catalog", Double.NaN));
    }

    @Test
    void nativeGalleryScrollKeepsPartialNeighborsWhenSmoothScrollingSettlesOnACardBoundary() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();

        ViewSpec initial = runtime.view(854, 480, 0, 0);
        ViewSpec.ScrollSurface surface = initial.scrollSurface("gallery.cards").orElseThrow();
        double cardStride = surface.maximumPixels()
                / initial.scrollbar().orElseThrow().maximum();
        runtime.nativeScrollPositionChanged("gallery.cards", cardStride * 2.0);

        ViewSpec settled = runtime.view(854, 480, 0, 0);
        Bounds viewport = settled.scrollSurface("gallery.cards").orElseThrow().viewport();
        assertTrue(settled.panels().stream()
                .filter(panel -> panel.style() == ViewSpec.Panel.Style.VANILLA_LIST)
                .map(ViewSpec.Panel::bounds)
                .anyMatch(bounds -> bounds.x() < viewport.x()
                        && bounds.right() > viewport.x()));
        assertTrue(settled.panels().stream()
                .filter(panel -> panel.style() == ViewSpec.Panel.Style.VANILLA_LIST)
                .map(ViewSpec.Panel::bounds)
                .anyMatch(bounds -> bounds.x() < viewport.right()
                        && bounds.right() > viewport.right()));
    }

    @Test
    void nativeCapeScrollFeedbackIsBoundedAndOnlyAppliesInsideTheEditor() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.ownedCapes = capeInventory(5);
        operations.session = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                TestFixtures.validSession().sessionIdentity(),
                null,
                (SessionFailureContext) null,
                "offline");
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(320, 240, 0, 0);
        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        int maximum = runtime.snapshot().editor().orElseThrow().maximumCapeScroll(320, 240);
        assertTrue(maximum > 16);

        runtime.nativeScrollPositionChanged("editor.capes", 16.0);
        assertEquals(16, runtime.view(320, 240, 0, 0).scrollbar().orElseThrow().offset());
        runtime.nativeScrollPositionChanged("editor.capes", maximum + 100.0);
        assertEquals(maximum, runtime.view(320, 240, 0, 0).scrollbar().orElseThrow().offset());

        runtime.dispatchWidget("editor.cancel");
        runtime.nativeScrollPositionChanged("editor.capes", 0.0);
        assertTrue(runtime.snapshot().editor().isEmpty());
    }

    @Test
    void keyboardCapeNavigationScrollsToAnOffscreenLogicalCard() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.ownedCapes = capeInventory(5);
        operations.session = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                TestFixtures.validSession().sessionIdentity(),
                null,
                (SessionFailureContext) null,
                "offline");
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(320, 240, 0, 0);
        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        ViewSpec start = runtime.view(320, 240, 0, 0);
        assertEquals(0.0, start.scrollSurface("editor.capes").orElseThrow().offsetPixels());

        assertTrue(runtime.dispatchNavigation(
                ViewSpec.NavigationCommand.DOWN, "editor.cape_choice.3"));

        ViewSpec scrolled = runtime.view(320, 240, 0, 0);
        assertTrue(scrolled.scrollSurface("editor.capes").orElseThrow().offsetPixels() > 0.0);
        assertEquals(Optional.of("editor.cape_choice.5"),
                scrolled.focusRequest().map(ViewSpec.FocusRequest::widgetId));
        assertTrue(scrolled.widget("editor.cape_choice.5").isPresent());
    }

    @Test
    void pressingTheFractionalGalleryThumbDoesNotJumpToTheRoundedSnapshotOffset() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(320, 240, 0, 0);
        runtime.pointerScrolled(160, 100, 0.0, -0.5);

        ViewSpec before = runtime.view(320, 240, 0, 0);
        Bounds thumb = before.scrollbar().orElseThrow().thumb();
        int cardX = panelX(before, "gallery.card.add");
        double pressX = thumb.x() + thumb.width() * 0.37;
        double pressY = thumb.y() + thumb.height() / 2.0;

        runtime.pointerPressed(pressX, pressY, 0);
        runtime.pointerReleased(0);

        ViewSpec after = runtime.view(320, 240, 0, 0);
        assertEquals(cardX, panelX(after, "gallery.card.add"));
        assertEquals(
                before.scrollbar().orElseThrow().offset(),
                after.scrollbar().orElseThrow().offset());
    }

    @Test
    void applyingAnotherPresetCentersItAndDiscardsTheFractionalGalleryTarget() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        int initialX = panelX(runtime.view(320, 240, 0, 0), "gallery.card.add");
        runtime.pointerScrolled(160, 100, 0.0, -0.5);
        assertEquals(initialX - 16, panelX(runtime.view(320, 240, 0, 0), "gallery.card.add"));

        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        ViewSpec centered = runtime.view(320, 240, 0, 0);
        assertHorizontallyCentered(
                centered.panels().stream()
                        .filter(panel -> panel.id().equals("gallery.card." + presetId))
                        .findFirst()
                        .orElseThrow()
                        .bounds(),
                320);
        int centeredAddX = panelX(centered, "gallery.card.add");
        settleScroll(runtime);
        assertEquals(centeredAddX, panelX(runtime.view(320, 240, 0, 0), "gallery.card.add"),
                "the stale fractional target must not restore the previous scroll position");
    }

    @Test
    void externalOpenAndReopenCenterTheActivePresetAtEverySupportedViewport() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        UUID active = operations.account.presets().get(4).id();
        operations.activePresetId = Optional.of(active);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());

        runtime.initialize();
        for (int[] viewport : List.of(
                new int[]{240, 240},
                new int[]{320, 240},
                new int[]{427, 240},
                new int[]{854, 480})) {
            ViewSpec view = runtime.view(viewport[0], viewport[1], 0, 0);
            assertHorizontallyCentered(
                    view.panels().stream()
                            .filter(panel -> panel.id().equals("gallery.card." + active))
                            .findFirst()
                            .orElseThrow()
                            .bounds(),
                    viewport[0]);
        }

        runtime.view(320, 240, 0, 0);
        runtime.pointerScrolled(160, 100, 0.0, -0.5);
        runtime.closeScreen();
        runtime.reopen();
        ViewSpec reopened = runtime.view(320, 240, 0, 0);
        assertHorizontallyCentered(
                reopened.panels().stream()
                        .filter(panel -> panel.id().equals("gallery.card." + active))
                        .findFirst()
                        .orElseThrow()
                        .bounds(),
                320);
    }

    @Test
    void coldOpenPublishesOnlyANeutralLoadingShellUntilInitializationCompletes() {
        FakeOperations operations = new FakeOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());

        runtime.initialize();

        ClientSnapshot loading = runtime.snapshot();
        assertEquals(ClientSnapshot.Lifecycle.INITIALIZING, loading.lifecycle());
        assertTrue(loading.account().isEmpty());
        ViewSpec view = runtime.view(320, 240, 0, 0);
        assertTrue(view.texts().stream().anyMatch(text -> text.id().equals("gallery.loading")));
        assertTrue(view.widget("gallery.search").isEmpty());
        assertTrue(view.widget("gallery.retry_session").isEmpty());
        assertTrue(view.texts().stream().noneMatch(text -> text.id().equals("gallery.offline")));
        assertTrue(view.panels().stream().noneMatch(panel ->
                panel.id().startsWith("gallery.card.")));

        worker.runFirst();

        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        assertTrue(runtime.view(320, 240, 0, 0).widget("gallery.search").isPresent());
    }

    @Test
    void warmedOpenPublishesCardsAndAnchorAtomicallyWithoutTransientOfflineChrome() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        UUID warmedActive = operations.account.presets().get(4).id();
        operations.activePresetId = Optional.of(warmedActive);
        operations.session = session(SessionStatus.OFFLINE_OR_INVALID);
        operations.warmedInitialData = Optional.of(operations.initial());
        operations.activePresetId = Optional.empty();
        operations.session = TestFixtures.validSession();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        List<ClientSnapshot> publications = new ArrayList<>();
        runtime.subscribe(publications::add);

        runtime.initialize();

        ClientSnapshot seeded = runtime.snapshot();
        assertEquals(ClientSnapshot.Lifecycle.INITIALIZING, seeded.lifecycle());
        assertEquals(Optional.of(warmedActive), seeded.activePresetId());
        assertEquals(1, seeded.galleryOffset());
        ViewSpec seededView = runtime.view(320, 240, 0, 0);
        assertHorizontallyCentered(
                seededView.panels().stream()
                        .filter(panel -> panel.id().equals("gallery.card." + warmedActive))
                        .findFirst()
                        .orElseThrow()
                        .bounds(),
                320);
        assertTrue(seededView.widget("gallery.retry_session").isEmpty());
        assertTrue(seededView.texts().stream().noneMatch(text ->
                text.id().equals("gallery.offline")));

        worker.runFirst();

        ClientSnapshot ready = runtime.snapshot();
        assertEquals(ClientSnapshot.Lifecycle.READY, ready.lifecycle());
        assertTrue(ready.activePresetId().isEmpty());
        assertEquals(0, ready.galleryOffset());
        assertHorizontallyCentered(
                runtime.view(320, 240, 0, 0).panels().stream()
                        .filter(panel -> panel.id().equals("gallery.card.add"))
                        .findFirst()
                        .orElseThrow()
                        .bounds(),
                320);
        publications.stream()
                .filter(snapshot -> snapshot.account().isPresent())
                .forEach(snapshot -> assertEquals(
                        snapshot.activePresetId().isPresent() ? 1 : 0,
                        snapshot.galleryOffset(),
                        "account data and its gallery anchor must share one publication"));
    }

    @Test
    void repeatedOpenUsesTheLastReadyCardsAndFreshUnchangedDataDoesNotMoveThem() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        UUID active = operations.account.presets().get(4).id();
        operations.activePresetId = Optional.of(active);
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        runtime.initialize();
        worker.runFirst();
        runtime.view(320, 240, 0, 0);
        runtime.pointerScrolled(160, 100, 0.0, -0.5);
        runtime.closeScreen();

        runtime.reopen();

        ClientSnapshot reopening = runtime.snapshot();
        assertEquals(ClientSnapshot.Lifecycle.INITIALIZING, reopening.lifecycle());
        assertEquals(Optional.of(active), reopening.activePresetId());
        ViewSpec seeded = runtime.view(320, 240, 0, 0);
        assertHorizontallyCentered(
                seeded.panels().stream()
                        .filter(panel -> panel.id().equals("gallery.card." + active))
                        .findFirst()
                        .orElseThrow()
                        .bounds(),
                320);
        int seededAddX = panelX(seeded, "gallery.card.add");

        worker.runFirst();

        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        assertEquals(seededAddX, panelX(
                runtime.view(320, 240, 0, 0), "gallery.card.add"));
    }

    @Test
    void identicalTextDispatchesDoNotPublishOrResetGalleryScroll() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        String personalHash = operations.seedPersonalSkin("Personal");
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        AtomicInteger publications = new AtomicInteger();
        runtime.initialize();
        runtime.subscribe(ignored -> publications.incrementAndGet());

        runtime.dispatchText("gallery.search", "Preset");
        runtime.view(320, 240, 0, 0);
        runtime.pointerScrolled(160, 100, 0.0, -0.5);
        int scrolledX = panelX(runtime.view(320, 240, 0, 0), "gallery.card.add");
        int afterScrollPublications = publications.get();
        long generation = runtime.snapshot().generation();

        runtime.dispatchText("gallery.search", "Preset");

        assertEquals(afterScrollPublications, publications.get());
        assertEquals(generation, runtime.snapshot().generation());
        assertEquals(scrolledX, panelX(runtime.view(320, 240, 0, 0), "gallery.card.add"));

        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        String editorName = runtime.snapshot().editor().orElseThrow().name();
        assertNoPublication(publications, () -> runtime.dispatchText("editor.name", editorName));
        runtime.dispatchWidget("editor.cancel");

        runtime.dispatchWidget("gallery.add");
        AddSourceModel addSource = runtime.snapshot().addSource().orElseThrow();
        assertNoPublication(publications, () ->
                runtime.dispatchText("add.catalog.search", addSource.query()));
        assertNoPublication(publications, () ->
                runtime.dispatchText("add.player.input", addSource.playerInput()));
        assertNoPublication(publications, () ->
                runtime.dispatchText("add.url.input", addSource.urlInput()));

        runtime.dispatchWidget("add.tab.catalog");
        runtime.dispatchWidget("add.catalog.rename:"
                + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash);
        ViewSpec renameView = runtime.view(320, 240, 0, 0);
        assertEquals(
                Optional.of("add.catalog.rename.name"),
                renameView.focusRequest().map(ViewSpec.FocusRequest::widgetId));
        assertTrue(renameView.widget("add.catalog.rename.name")
                .orElseThrow()
                .selectAllOnFocusAcquire());
        String renameValue = renameView.widget("add.catalog.rename.name")
                .orElseThrow()
                .value()
                .orElseThrow();
        assertNoPublication(publications, () ->
                runtime.dispatchText("add.catalog.rename.name", renameValue));
    }

    @Test
    void typedPublicImportFailuresExposeOnlySafeLocalizationKeys() {
        Map<PublicSkinImportException.Code, String> playerFailures = Map.of(
                PublicSkinImportException.Code.INVALID_IDENTIFIER,
                "nclskins.add_source.player_invalid_identifier",
                PublicSkinImportException.Code.PROFILE_NOT_FOUND,
                "nclskins.add_source.player_not_found",
                PublicSkinImportException.Code.RATE_LIMITED,
                "nclskins.add_source.player_rate_limited",
                PublicSkinImportException.Code.SERVICE_UNAVAILABLE,
                "nclskins.add_source.player_service_unavailable",
                PublicSkinImportException.Code.NETWORK_FAILURE,
                "nclskins.add_source.player_service_unavailable",
                PublicSkinImportException.Code.PROFILE_REJECTED,
                "nclskins.add_source.player_rejected",
                PublicSkinImportException.Code.OVERSIZED,
                "nclskins.add_source.player_oversized");
        playerFailures.forEach((code, key) -> assertImportFailure(true, code, key));

        Map<PublicSkinImportException.Code, String> urlFailures = Map.of(
                PublicSkinImportException.Code.UNSAFE_URL,
                "nclskins.add_source.url_unsafe",
                PublicSkinImportException.Code.REDIRECT_REJECTED,
                "nclskins.add_source.url_redirect_rejected",
                PublicSkinImportException.Code.SITE_BLOCKED,
                "nclskins.add_source.url_site_blocked",
                PublicSkinImportException.Code.NETWORK_FAILURE,
                "nclskins.add_source.url_network_failure",
                PublicSkinImportException.Code.SERVICE_UNAVAILABLE,
                "nclskins.add_source.url_network_failure",
                PublicSkinImportException.Code.RATE_LIMITED,
                "nclskins.add_source.url_rate_limited",
                PublicSkinImportException.Code.OVERSIZED,
                "nclskins.add_source.url_oversized",
                PublicSkinImportException.Code.INVALID_PNG,
                "nclskins.add_source.url_invalid_file");
        urlFailures.forEach((code, key) -> assertImportFailure(false, code, key));
    }

    @Test
    void publicPlayerLookupRemainsAvailableForAnOfflineCurrentSession() {
        FakeOperations operations = new FakeOperations();
        operations.playerImportVariant = SkinVariant.SLIM;
        operations.session = session(SessionStatus.OFFLINE_OR_INVALID);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();

        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");
        runtime.dispatchText("add.player.input", "Notch");

        ViewSpec importView = runtime.view(320, 240, 0, 0);
        assertTrue(importView.widget("add.player.input").orElseThrow().enabled());
        assertTrue(importView.widget("add.player.load").orElseThrow().enabled());

        runtime.dispatchWidget("add.player.load");

        assertEquals(1, operations.playerImportCalls);
        PresetEditorModel editor = runtime.snapshot().editor().orElseThrow();
        assertEquals(SkinVariant.SLIM, editor.variant());
        assertEquals(SkinVariant.SLIM, editor.saveRequest().initialVariant());
        assertEquals(Optional.of(SkinVariant.SLIM), operations.uiPreferences.preferredSkinVariant());
        assertFalse(runtime.snapshot().session().orElseThrow().valid());
    }

    @Test
    void fileImportDetectsVariantAndManualOverrideRemainsAvailable(@TempDir Path directory)
            throws Exception {
        FakeOperations operations = new FakeOperations();
        Path selected = Files.write(directory.resolve("slim.png"), opaqueSkinPng(true));
        FilePicker picker = () -> CompletableFuture.completedFuture(Optional.of(selected));
        ClientRuntime runtime = runtime(operations, picker);
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");

        runtime.dispatchWidget("add.file.choose");

        PresetEditorModel editor = runtime.snapshot().editor().orElseThrow();
        assertEquals(SkinVariant.SLIM, editor.variant());
        assertEquals(SkinVariant.SLIM, editor.saveRequest().initialVariant());
        assertEquals(Optional.of(SkinVariant.SLIM), operations.uiPreferences.preferredSkinVariant());
        assertTrue(runtime.view(854, 480, 0, 0).widget("editor.model").orElseThrow().enabled());

        runtime.dispatchWidget("editor.model");

        assertEquals(SkinVariant.CLASSIC, runtime.snapshot().editor().orElseThrow().variant());
        assertEquals(Optional.of(SkinVariant.CLASSIC), operations.uiPreferences.preferredSkinVariant());
        runtime.dispatchText("editor.name", "Detected file");
        runtime.dispatchWidget("editor.save");
        AppearancePreset saved = findPreset(
                runtime.snapshot(), runtime.snapshot().selectedPresetId().orElseThrow());
        assertEquals(
                SkinVariant.CLASSIC,
                findSkin(runtime.snapshot(), saved.skin().assetId()).variant());
    }

    @Test
    void cancelledFilePickerDoesNotChangePreferredVariant() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, CANCELLED_PICKER);
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");

        runtime.dispatchWidget("add.file.choose");

        assertTrue(runtime.snapshot().editor().isEmpty());
        assertTrue(operations.uiPreferences.preferredSkinVariant().isEmpty());
    }

    @Test
    void unavailableModSourceOffersFolderAndBackReturnsToImportTab() {
        FakeOperations operations = new FakeOperations();
        operations.externalSourceAvailable = false;
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");

        assertTrue(runtime.view(320, 240, 0, 0).widget("add.external.mod").isPresent());
        runtime.dispatchWidget("add.external.mod");
        ViewSpec chooser = runtime.view(320, 240, 0, 0);
        assertEquals(UiMessage.info("nclskins.external_import.mod_title"), chooser.title());
        assertFalse(chooser.widget("external.source.skin_shuffle").orElseThrow().enabled());
        assertTrue(chooser.widget("external.folder.skin_shuffle").orElseThrow().enabled());
        assertEquals(ExternalImportSource.QUICK_SKIN, operations.lastExternalSource);
        assertEquals(Optional.empty(), operations.lastExternalRoot);

        runtime.dispatchWidget("external.back");
        ViewSpec restored = runtime.view(320, 240, 0, 0);
        assertEquals(UiMessage.info("nclskins.add_source.title"), restored.title());
        assertTrue(restored.widget("add.external.mod").isPresent());
    }

    @Test
    void externalImportRetriesSelectedFolderAndReturnsGallery(@TempDir Path directory) {
        FakeOperations operations = new FakeOperations();
        operations.externalSourceAvailable = false;
        FilePicker picker = new FilePicker() {
            @Override
            public CompletableFuture<Optional<Path>> chooseSkinPng() {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<Optional<Path>> chooseDirectory() {
                return CompletableFuture.completedFuture(Optional.of(directory));
            }
        };
        ClientRuntime runtime = runtime(operations, picker);
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");
        runtime.dispatchWidget("add.external.launcher");
        operations.externalSourceAvailable = true;
        runtime.dispatchWidget("external.folder.prism_launcher");
        runtime.dispatchWidget("external.source.prism_launcher");
        assertEquals("external_review", runtime.view(320, 240, 0, 0).screenId());
        assertEquals(0, operations.externalCommitCalls);
        runtime.dispatchWidget("external.review.cancel");
        assertEquals("external_chooser", runtime.view(320, 240, 0, 0).screenId());
        assertEquals(0, operations.externalCommitCalls);
        runtime.dispatchWidget("external.source.prism_launcher");
        operations.externalImportResult = new ClientOperations.ExternalImportResult(
                operations.account, 2, 3, 1, 1);
        runtime.dispatchWidget("external.review.commit");

        assertEquals("gallery", runtime.view(320, 240, 0, 0).screenId());
        assertEquals(
                UiMessage.success("nclskins.external_import.complete", 2, 1, 3, 1),
                runtime.snapshot().status());
        assertEquals(Optional.of(directory.toAbsolutePath().normalize()),
                operations.lastExternalRoot.map(path -> path.toAbsolutePath().normalize()));
        assertEquals(1, operations.externalCommitCalls);
    }

    @Test
    void missingSqliteDependencyBlocksImportAndDatabasePicker() {
        FakeOperations operations = new FakeOperations();
        operations.externalSourceProbes.put(
                ExternalImportSource.MINECRAFT_LAUNCHER, ExternalImportProbe.UNAVAILABLE);
        operations.externalSourceProbes.put(
                ExternalImportSource.CURSEFORGE_APP, ExternalImportProbe.DEPENDENCY_MISSING);
        operations.externalSourceProbes.put(
                ExternalImportSource.MODRINTH_APP, ExternalImportProbe.DEPENDENCY_MISSING);
        operations.externalSourceProbes.put(
                ExternalImportSource.PRISM_LAUNCHER, ExternalImportProbe.UNAVAILABLE);
        java.util.concurrent.atomic.AtomicInteger pickerCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        FilePicker picker = new FilePicker() {
            @Override
            public CompletableFuture<Optional<Path>> chooseSkinPng() {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<Optional<Path>> chooseSqliteDatabase() {
                pickerCalls.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.empty());
            }
        };
        ClientRuntime runtime = runtime(operations, picker);
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");
        runtime.dispatchWidget("add.external.launcher");

        ViewSpec chooser = runtime.view(240, 240, 0, 0);
        assertFalse(chooser.widget("external.source.curseforge_app").orElseThrow().enabled());
        assertFalse(chooser.widget("external.folder.modrinth_app").orElseThrow().enabled());
        runtime.dispatchWidget("external.folder.modrinth_app");
        runtime.dispatchWidget("external.source.curseforge_app");

        assertEquals(0, pickerCalls.get());
        assertEquals("external_chooser", runtime.view(240, 240, 0, 0).screenId());
    }

    @Test
    void leavingExternalImportFencesLateWorkerResult() {
        FakeOperations operations = new FakeOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        runtime.initialize();
        worker.runFirst();
        runtime.dispatchWidget("gallery.add");
        worker.runFirst();
        runtime.dispatchWidget("add.tab.file");
        runtime.dispatchWidget("add.external.launcher");
        assertTrue(runtime.snapshot().busy());
        runtime.dispatchWidget("external.back");
        assertFalse(runtime.snapshot().busy());
        assertEquals(UiMessage.info("nclskins.status.cancelled"), runtime.snapshot().status());

        worker.runFirst();

        ViewSpec restored = runtime.view(320, 240, 0, 0);
        assertEquals(UiMessage.info("nclskins.add_source.title"), restored.title());
        assertTrue(restored.widget("add.external.launcher").isPresent());
        assertEquals(UiMessage.info("nclskins.status.cancelled"), runtime.snapshot().status());
    }

    @Test
    void urlImportUsesDetectedVariantAndPreferenceSurvivesEditorCancel() {
        FakeOperations operations = new FakeOperations();
        operations.urlImportVariant = SkinVariant.SLIM;
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");
        runtime.dispatchText("add.url.input", "https://example.test/slim.png");

        runtime.dispatchWidget("add.url.load");

        assertEquals(SkinVariant.SLIM, runtime.snapshot().editor().orElseThrow().variant());
        assertEquals(Optional.of(SkinVariant.SLIM), operations.uiPreferences.preferredSkinVariant());
        runtime.dispatchWidget("editor.cancel");
        assertEquals(
                SkinVariant.SLIM,
                runtime.snapshot().addSource().orElseThrow().preferredVariant());
    }

    @Test
    void centerAnchoredGalleryRangeSurvivesViewportExpansionWhileCapeRangeReclamps() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        operations.ownedCapes = capeInventory(5);
        operations.session = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                TestFixtures.validSession().sessionIdentity(),
                null,
                (SessionFailureContext) null,
                "offline");
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(320, 240, 0, 0);

        runtime.pointerScrolled(160, 100, 0.0, -100.0);
        settleScroll(runtime);
        assertEquals(5, runtime.snapshot().galleryOffset());

        runtime.view(854, 480, 0, 0);
        runtime.tick();
        assertEquals(5, runtime.snapshot().galleryOffset());
        runtime.view(320, 240, 0, 0);
        settleScroll(runtime);
        assertEquals(5, runtime.snapshot().galleryOffset(),
                "the last centered card is independent of viewport geometry");

        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        PresetEditorModel editorModel = runtime.snapshot().editor().orElseThrow();
        int narrowCapeMaximum = editorModel.maximumCapeScroll(320, 240);
        int expandedCapeMaximum = editorModel.maximumCapeScroll(854, 480);
        assertTrue(expandedCapeMaximum < narrowCapeMaximum);
        ViewSpec editor = runtime.view(320, 240, 0, 0);
        Bounds capeViewport = editor.clipRegions().stream()
                .filter(region -> region.id().equals("editor.capes"))
                .findFirst()
                .orElseThrow()
                .bounds();
        runtime.pointerScrolled(
                capeViewport.x() + capeViewport.width() / 2.0,
                capeViewport.y() + capeViewport.height() / 2.0,
                0.0,
                -0.5);
        assertEquals(16, runtime.view(320, 240, 0, 0).scrollbar().orElseThrow().offset(),
                "cape wheel movement must apply the direct half-step magnitude");

        runtime.pointerScrolled(
                capeViewport.x() + capeViewport.width() / 2.0,
                capeViewport.y() + capeViewport.height() / 2.0,
                0.0,
                -100.0);
        assertEquals(narrowCapeMaximum,
                runtime.view(320, 240, 0, 0).scrollbar().orElseThrow().offset());

        runtime.view(854, 480, 0, 0);
        runtime.tick();
        ViewSpec expandedEditor = runtime.view(854, 480, 0, 0);
        if (expandedCapeMaximum == 0) {
            assertTrue(expandedEditor.scrollbar().isEmpty());
        } else {
            assertEquals(expandedCapeMaximum, expandedEditor.scrollbar().orElseThrow().offset());
        }
        runtime.view(320, 240, 0, 0);
        settleScroll(runtime);
        assertEquals(expandedCapeMaximum,
                runtime.view(320, 240, 0, 0).scrollbar().orElseThrow().offset(),
                "restoring the narrow viewport must not resurrect the stale cape target");
    }

    @Test
    void centerAnchoredGalleryRangeIsIndependentOfHeightAtTheSameWidth() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(320, 360, 0, 0);

        runtime.pointerScrolled(160, 160, 0.0, -100.0);
        settleScroll(runtime);
        assertEquals(5, runtime.snapshot().galleryOffset());

        runtime.view(320, 240, 0, 0);
        runtime.tick();
        assertEquals(5, runtime.snapshot().galleryOffset());

        runtime.view(320, 360, 0, 0);
        settleScroll(runtime);
        assertEquals(
                5,
                runtime.snapshot().galleryOffset(),
                "the last centered card must not depend on card height");
    }

    @Test
    void catalogWheelAppliesDirectBoundedPixelStepsWithoutPageJumps() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());

        runtime.initialize();
        runtime.view(320, 240, 0, 0);
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        runtime.view(320, 240, 160, 100);

        runtime.pointerScrolled(160, 40, 0.0, -1.0);
        assertEquals(0, runtime.snapshot().addSource().orElseThrow().scrollOffset(),
                "the search/filter row is outside the vanilla catalog scroll viewport");

        runtime.pointerScrolled(160, 100, 0.0, -0.5);
        assertEquals(16, runtime.snapshot().addSource().orElseThrow().scrollOffset(),
                "a half wheel step must move the catalog immediately by half the normal delta");
        runtime.tick();
        int first = runtime.snapshot().addSource().orElseThrow().scrollOffset();
        assertEquals(16, first, "ticks must not ease or otherwise change direct wheel movement");

        runtime.pointerScrolled(160, 100, 0.0, -1.0);
        assertEquals(48, runtime.snapshot().addSource().orElseThrow().scrollOffset(),
                "successive wheel steps must accumulate at event time");
        runtime.tick();
        assertEquals(48, runtime.snapshot().addSource().orElseThrow().scrollOffset());
    }

    @Test
    void editorPreviewClickDoesNotChangeLayersAndCycleButtonsHonorShiftDirection() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());

        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");

        PresetEditorModel before = runtime.snapshot().editor().orElseThrow();
        runtime.pointerPressed(215, 150, 0);
        runtime.pointerReleased(215, 150, 0);

        PresetEditorModel afterClick = runtime.snapshot().editor().orElseThrow();
        assertEquals(
                before.preview().outerLayerVisibility(),
                afterClick.preview().outerLayerVisibility());

        runtime.dispatchWidget("editor.outer_layer.body", true);

        PresetEditorModel afterReverse = runtime.snapshot().editor().orElseThrow();
        assertTrue(afterReverse.preview().outerLayerVisibility().visible(OuterLayerPart.BODY));
        assertFalse(afterReverse.preview().outerLayerVisibility().visible(OuterLayerPart.LEFT_ARM));
        assertTrue(afterReverse.preview().outerLayerVisibility().visible(OuterLayerPart.RIGHT_ARM));

        runtime.dispatchWidget("editor.outer_layer.body");
        PresetEditorModel wrapped = runtime.snapshot().editor().orElseThrow();
        assertTrue(wrapped.preview().outerLayerVisibility().visible(OuterLayerPart.BODY));
        assertTrue(wrapped.preview().outerLayerVisibility().visible(OuterLayerPart.LEFT_ARM));
        assertTrue(wrapped.preview().outerLayerVisibility().visible(OuterLayerPart.RIGHT_ARM));

        runtime.dispatchWidget("editor.outer_layer.body");
        PresetEditorModel afterForward = runtime.snapshot().editor().orElseThrow();
        assertFalse(afterForward.preview().outerLayerVisibility().visible(OuterLayerPart.BODY));
        assertFalse(afterForward.preview().outerLayerVisibility().visible(OuterLayerPart.LEFT_ARM));
        assertFalse(afterForward.preview().outerLayerVisibility().visible(OuterLayerPart.RIGHT_ARM));
    }

    @Test
    void closeSupersedesAnUnpublishedWorkerResultAndReopenStartsFreshGeneration() {
        FakeOperations operations = new FakeOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());

        runtime.initialize();
        assertEquals(ClientSnapshot.Lifecycle.INITIALIZING, runtime.snapshot().lifecycle());
        runtime.closeScreen();
        worker.runFirst();
        assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
        assertTrue(runtime.snapshot().account().isEmpty());

        long closedGeneration = runtime.snapshot().generation();
        runtime.reopen();
        worker.runFirst();
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        assertTrue(runtime.snapshot().generation() > closedGeneration);
    }

    @Test
    void terminalCloseMaterializesClosedWithoutReenteringScreenSubscribers() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        AtomicInteger terminalCallbacks = new AtomicInteger();
        runtime.subscribe(snapshot -> {
            if (snapshot.lifecycle() == ClientSnapshot.Lifecycle.CLOSED) {
                terminalCallbacks.incrementAndGet();
            }
        });
        runtime.initialize();

        runtime.close();

        assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
        assertTrue(runtime.closed());
        assertEquals(0, terminalCallbacks.get());
        assertThrows(IllegalStateException.class, () -> runtime.view(320, 240, 0, 0));
    }

    @Test
    void ordinaryScreenCloseStillPublishesClosedAndCanReopen() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        AtomicInteger closedCallbacks = new AtomicInteger();
        runtime.subscribe(snapshot -> {
            if (snapshot.lifecycle() == ClientSnapshot.Lifecycle.CLOSED) {
                closedCallbacks.incrementAndGet();
            }
        });
        runtime.initialize();

        runtime.closeScreen();

        assertEquals(1, closedCallbacks.get());
        assertFalse(runtime.closed());
        runtime.reopen();
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
    }

    @Test
    void offlineRateLimitedApplyStaysEnabledAndPublishesLocalPendingBeforeCheckpoint() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.session = session(SessionStatus.OFFLINE_OR_INVALID);
        operations.rateLimited = true;
        operations.localFirst = true;
        QueuedExecutor worker = new QueuedExecutor();
        AtomicInteger notifications = new AtomicInteger();
        AtomicReference<ExpectedAppearance> installed = new AtomicReference<>();
        AppearanceRefreshCoordinator<String> refresh = new AppearanceRefreshCoordinator<>(
                CLIENT,
                expected -> CompletableFuture.completedFuture(Optional.of(
                        new SignedProfileResolver.ResolvedProfile<>(
                                expected.profileId(), expected, "local"))),
                resolved -> {
                    installed.set(resolved.expectedAppearance());
                    return PlayerAppearanceSink.ApplyResult.UPDATED;
                },
                DiagnosticSinks.discarding());
        ClientRuntime runtime = runtime(
                operations,
                worker,
                Optional.of(refresh),
                Optional.of(notifications::incrementAndGet));

        runtime.initialize();
        worker.runFirst();
        UUID presetId = operations.account.presets().get(0).id();
        ViewSpec gallery = runtime.view(854, 480, 0, 0);

        assertTrue(gallery.widget("gallery.preset." + presetId + ".apply")
                .orElseThrow()
                .enabled());

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");
        worker.runFirst();

        ClientSnapshot pending = runtime.snapshot();
        assertEquals(Optional.of(presetId), pending.activePresetId());
        assertEquals(1, pending.intentRevision());
        assertEquals(AppearanceSyncStatus.PENDING, pending.syncStatus());
        assertFalse(pending.syncInProgress());
        assertTrue(installed.get().skinTexture().isEmpty());
        assertEquals(Optional.of("a".repeat(64)), installed.get().localSkinSha256());
        assertEquals(0, operations.applyCalls);
        assertEquals(0, notifications.get());

        assertEquals(0, worker.size());
        assertEquals(0, operations.reconciliationCalls);
        assertEquals(AppearanceSyncStatus.PENDING, runtime.snapshot().syncStatus());
        assertFalse(runtime.snapshot().syncInProgress());
        assertEquals(0, operations.applyCalls);
        assertEquals(0, notifications.get());
    }

    @Test
    void rateLimitCountdownCoalescesLatestIntentAndRecoversAfterClosedGallery() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(3);
        operations.localFirst = true;
        operations.reconcileWithOutcome = true;
        operations.rateLimited = true;
        operations.rateLimitRemaining = Duration.ofSeconds(60);
        QueuedExecutor reconciliation = new QueuedExecutor();
        AtomicInteger notifications = new AtomicInteger();
        ClientRuntime runtime = new ClientRuntime(
                operations,
                CLIENT,
                CANCELLED_PICKER,
                Runnable::run,
                reconciliation,
                TEXT,
                Optional.empty(),
                Optional.of(notifications::incrementAndGet),
                IMMEDIATE_READINESS_SCHEDULER,
                DiagnosticSinks.discarding());
        runtime.initialize();
        runtime.tick();

        UUID first = operations.account.presets().get(0).id();
        UUID second = operations.account.presets().get(1).id();
        UUID third = operations.account.presets().get(2).id();
        runtime.dispatchWidget("gallery.preset." + first + ".apply");
        runtime.dispatchWidget("gallery.preset." + second + ".apply");
        runtime.dispatchWidget("gallery.preset." + third + ".apply");

        assertEquals(0, reconciliation.size());
        assertEquals(Optional.of(third), runtime.snapshot().activePresetId());
        assertEquals(3, runtime.snapshot().intentRevision());
        assertEquals(1.0, runtime.snapshot().rateLimitProgress().orElseThrow().fraction());

        operations.rateLimitRemaining = Duration.ofSeconds(30);
        runtime.tick();
        assertEquals(0.5, runtime.snapshot().rateLimitProgress().orElseThrow().fraction());

        operations.rateLimitRemaining = Duration.ofSeconds(90);
        runtime.tick();
        ClientSnapshot.RateLimitProgress extended =
                runtime.snapshot().rateLimitProgress().orElseThrow();
        assertEquals(Duration.ofSeconds(90), extended.remaining());
        assertEquals(Duration.ofSeconds(90), extended.total());
        assertEquals(1.0, extended.fraction());

        operations.rateLimitRemaining = Duration.ofSeconds(45);
        runtime.tick();
        assertEquals(0.5, runtime.snapshot().rateLimitProgress().orElseThrow().fraction());

        runtime.dispatchWidget("gallery.preset." + third + ".apply");
        assertEquals(3, runtime.snapshot().intentRevision());
        assertEquals(0, reconciliation.size());

        runtime.closeScreen();
        operations.rateLimited = false;
        runtime.tick();
        assertEquals(1, reconciliation.size());

        reconciliation.runFirst();
        assertEquals(
                List.of(ClientOperations.ReconciliationTrigger.RATE_LIMIT_EXPIRED),
                operations.reconciliationTriggers);
        assertEquals(3, operations.reconciliationKeys.get(0).intentRevision());
        assertEquals(1, notifications.get());
    }

    @Test
    void retryCapeUsesExplicitDurableReconciliationAndNeverLegacyCapeMutation() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.localFirst = true;
        operations.reconcileWithOutcome = true;
        operations.result = MutationResult.PARTIAL;
        operations.recovery = Set.of(RecoveryAction.RETRY_CAPE);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(1, operations.reconciliationCalls);
        assertTrue(runtime.snapshot().recoveryActions().contains(RecoveryAction.RETRY_CAPE));

        operations.result = MutationResult.APPLIED;
        operations.recovery = Set.of();
        runtime.dispatchWidget("gallery.retry_cape");

        assertEquals(2, operations.reconciliationCalls);
        assertEquals(0, operations.retryCapeCalls);
        assertEquals(
                List.of(
                        ClientOperations.ReconciliationTrigger.LOCAL_INTENT,
                        ClientOperations.ReconciliationTrigger.EXPLICIT_RETRY),
                operations.reconciliationTriggers);
        assertEquals(AppearanceSyncStatus.OFFICIAL, runtime.snapshot().syncStatus());
    }

    @Test
    void queuedExplicitTriggerForOldRevisionCannotRecoverNewerRevision() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(2);
        operations.localFirst = true;
        QueuedExecutor reconciliation = new QueuedExecutor();
        ClientRuntime runtime = new ClientRuntime(
                operations,
                CLIENT,
                CANCELLED_PICKER,
                Runnable::run,
                reconciliation,
                TEXT,
                Optional.empty(),
                Optional.empty(),
                IMMEDIATE_READINESS_SCHEDULER,
                DiagnosticSinks.discarding());
        runtime.initialize();
        UUID first = operations.account.presets().get(0).id();
        UUID second = operations.account.presets().get(1).id();

        runtime.dispatchWidget("gallery.preset." + first + ".apply");
        runtime.dispatchWidget("gallery.retry_cape");
        runtime.dispatchWidget("gallery.preset." + second + ".apply");

        assertEquals(1, reconciliation.size());
        assertEquals(2, runtime.snapshot().intentRevision());
        assertTrue(runtime.snapshot().syncInProgress());

        reconciliation.runFirst();

        assertEquals(1, operations.reconciliationCalls);
        assertEquals(
                List.of(ClientOperations.ReconciliationTrigger.LOCAL_INTENT),
                operations.reconciliationTriggers);
        assertEquals(
                List.of(new ClientOperations.ReconciliationKey(
                        TestFixtures.ACCOUNT_ID, 2)),
                operations.reconciliationKeys);
        assertEquals(0, operations.retryCapeCalls);
        assertEquals(Optional.of(second), runtime.snapshot().activePresetId());
        assertEquals(2, runtime.snapshot().intentRevision());
        assertFalse(runtime.snapshot().syncInProgress());
    }

    @Test
    void confirmedReconciliationNotifiesServerExactlyOnceAndUnknownOrNoOpDoNotNotify() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.localFirst = true;
        operations.reconcileWithOutcome = true;
        AtomicInteger notifications = new AtomicInteger();
        ClientRuntime runtime = runtime(
                operations,
                Runnable::run,
                Optional.empty(),
                Optional.of(notifications::incrementAndGet));
        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();

        operations.remoteAppearanceImpact = RemoteAppearanceImpact.CONFIRMED_CHANGED;
        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(1, notifications.get());
        assertEquals(0, operations.applyCalls);

        operations.result = MutationResult.UNKNOWN;
        operations.remoteAppearanceImpact = RemoteAppearanceImpact.UNCERTAIN;
        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(1, notifications.get());

        operations.result = MutationResult.APPLIED;
        operations.remoteAppearanceImpact = RemoteAppearanceImpact.NONE;
        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(1, notifications.get());
    }

    @Test
    void confirmedReconciliationStillNotifiesAfterGalleryCloses() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.localFirst = true;
        operations.reconcileWithOutcome = true;
        operations.remoteAppearanceImpact = RemoteAppearanceImpact.CONFIRMED_CHANGED;
        QueuedExecutor worker = new QueuedExecutor();
        AtomicInteger notifications = new AtomicInteger();
        ClientRuntime runtime = runtime(
                operations,
                worker,
                Optional.empty(),
                Optional.of(notifications::incrementAndGet));
        runtime.initialize();
        worker.runFirst();
        UUID presetId = operations.account.presets().get(0).id();

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");
        worker.runFirst();
        runtime.closeScreen();
        worker.runFirst();

        assertEquals(1, notifications.get());
        assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
        assertEquals(0, operations.applyCalls);
    }

    @Test
    void postMutationLocalFailureStillNotifiesServerWithoutPublishingOutcomeData() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.localFirst = true;
        operations.settlementFailureImpact = RemoteAppearanceImpact.CONFIRMED_CHANGED;
        AtomicInteger notifications = new AtomicInteger();
        ClientRuntime runtime = runtime(
                operations,
                Runnable::run,
                Optional.empty(),
                Optional.of(notifications::incrementAndGet));
        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(1, notifications.get());
        assertEquals(AppearanceSyncStatus.OFFICIAL, runtime.snapshot().syncStatus());
        assertTrue(runtime.snapshot().lastMutation().isEmpty());
        assertFalse(runtime.snapshot().busy());
    }

    @Test
    void postNoOpLocalFailureDoesNotNotifyServer() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.localFirst = true;
        operations.settlementFailureImpact = RemoteAppearanceImpact.NONE;
        AtomicInteger notifications = new AtomicInteger();
        ClientRuntime runtime = runtime(
                operations,
                Runnable::run,
                Optional.empty(),
                Optional.of(notifications::incrementAndGet));
        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(0, notifications.get());
        assertEquals(AppearanceSyncStatus.OFFICIAL, runtime.snapshot().syncStatus());
        assertTrue(runtime.snapshot().lastMutation().isEmpty());
    }

    @Test
    void postUncertainSettlementFailurePublishesUnknownWithoutNotifyingServer() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.localFirst = true;
        operations.settlementFailureImpact = RemoteAppearanceImpact.UNCERTAIN;
        AtomicInteger notifications = new AtomicInteger();
        ClientRuntime runtime = runtime(
                operations,
                Runnable::run,
                Optional.empty(),
                Optional.of(notifications::incrementAndGet));
        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(0, notifications.get());
        assertEquals(AppearanceSyncStatus.UNKNOWN, runtime.snapshot().syncStatus());
        assertTrue(runtime.snapshot().lastMutation().isEmpty());
    }

    @Test
    void settlementFailureCarrierContainsOnlyRemoteImpact() {
        RemoteMutationSettlementException failure =
                new RemoteMutationSettlementException(RemoteAppearanceImpact.CONFIRMED_CHANGED);

        assertEquals(RemoteAppearanceImpact.CONFIRMED_CHANGED, failure.remoteAppearanceImpact());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("session-token"));
        assertFalse(failure.toString().contains(TestFixtures.ACCOUNT_ID.toString()));
        assertFalse(failure.toString().contains("textures.minecraft.net"));
    }

    @Test
    void notifierFailureDoesNotInvalidateAcceptedMutation() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.remoteAppearanceImpact = RemoteAppearanceImpact.CONFIRMED_CHANGED;
        ServerAppearanceRefreshNotifier failing = () -> {
            throw new IllegalStateException("connection changed");
        };
        ClientRuntime runtime = runtime(
                operations,
                Runnable::run,
                Optional.empty(),
                Optional.of(failing));
        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(MutationResult.APPLIED, runtime.snapshot().lastMutation().orElseThrow().result());
        assertFalse(runtime.snapshot().busy());
    }

    @Test
    void notifierIsDeliveredOnlyThroughClientExecutor() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        QueuedExecutor worker = new QueuedExecutor();
        TrackingClientExecutor client = new TrackingClientExecutor();
        AtomicInteger notifications = new AtomicInteger();
        ClientRuntime runtime = new ClientRuntime(
                operations,
                client,
                CANCELLED_PICKER,
                worker,
                TEXT,
                Optional.empty(),
                Optional.of(() -> {
                    assertTrue(client.isClientThread());
                    notifications.incrementAndGet();
                }),
                IMMEDIATE_READINESS_SCHEDULER,
                DiagnosticSinks.discarding());

        runtime.initialize();
        client.runFirst();
        worker.runFirst();
        client.runFirst();
        UUID presetId = operations.account.presets().get(0).id();

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");
        client.runFirst();
        worker.runFirst();

        assertEquals(0, notifications.get());
        client.runFirst();
        assertEquals(1, notifications.get());
    }

    @Test
    void reconnectUsesLatestDurableAppearanceWithoutSessionRetryAndPreviewBytesAreCached() {
        FakeOperations operations = new FakeOperations();
        AppliedAppearance durable = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID,
                "a".repeat(64),
                SkinVariant.CLASSIC,
                Optional.empty());
        operations.durable = Optional.of(new ClientOperations.DurableAppearance(
                TestFixtures.ACCOUNT_ID,
                4,
                AppearanceSyncStatus.OFFICIAL,
                Optional.empty(),
                Optional.of(durable),
                Optional.empty()));
        AtomicInteger resolves = new AtomicInteger();
        SignedProfileResolver<String> resolver = expected -> {
            resolves.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of(
                    new SignedProfileResolver.ResolvedProfile<>(
                            expected.profileId(), expected, "profile")));
        };
        PlayerAppearanceSink<String> sink = ignored -> PlayerAppearanceSink.ApplyResult.UPDATED;
        AppearanceRefreshCoordinator<String> coordinator =
                new AppearanceRefreshCoordinator<>(
                        CLIENT, resolver, sink, DiagnosticSinks.discarding());
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.of(coordinator));

        assertEquals(
                AppearanceRefreshCoordinator.Result.UPDATED,
                runtime.afterReconnect().join());
        assertEquals(1, resolves.get());
        assertEquals(0, operations.retrySessionCalls);
        assertEquals(0, operations.reconciliationCalls);

        byte[] first = runtime.loadSkinPreview(SkinReference.asset(TestFixtures.CLASSIC_ID))
                .join()
                .orElseThrow();
        first[0] = 99;
        byte[] second = runtime.loadSkinPreview(SkinReference.asset(TestFixtures.CLASSIC_ID))
                .join()
                .orElseThrow();
        assertArrayEquals(new byte[] {7, 8, 9}, second);
        assertEquals(1, operations.skinPreviewCalls);
    }

    @Test
    void reconnectCompletesLocalRebindBeforeStartingItsCheckpoint() {
        FakeOperations operations = new FakeOperations();
        operations.appearanceRevision = 5;
        operations.appearanceSyncStatus = AppearanceSyncStatus.PENDING;
        AppliedAppearance pending = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID,
                "a".repeat(64),
                SkinVariant.CLASSIC,
                Optional.empty());
        operations.durable = Optional.of(new ClientOperations.DurableAppearance(
                TestFixtures.ACCOUNT_ID,
                operations.appearanceRevision,
                operations.appearanceSyncStatus,
                Optional.empty(),
                Optional.of(pending),
                Optional.empty()));
        AtomicBoolean rebound = new AtomicBoolean();
        operations.reconciliationPrecondition = rebound::get;
        AppearanceRefreshCoordinator<String> refresh = new AppearanceRefreshCoordinator<>(
                CLIENT,
                expected -> CompletableFuture.completedFuture(Optional.of(
                        new SignedProfileResolver.ResolvedProfile<>(
                                expected.profileId(), expected, "rebound"))),
                resolved -> {
                    rebound.set(true);
                    return PlayerAppearanceSink.ApplyResult.UPDATED;
                },
                DiagnosticSinks.discarding());
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.of(refresh));

        assertEquals(AppearanceRefreshCoordinator.Result.UPDATED, runtime.afterReconnect().join());

        assertTrue(rebound.get());
        assertEquals(1, operations.reconciliationCalls);
        assertFalse(operations.reconciliationBeforePrecondition);
    }

    @Test
    void reconnectRejectsOldDurableOwnerAfterSessionSwitchBeforeScheduling() {
        FakeOperations operations = new FakeOperations();
        AppliedAppearance oldLocal = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID,
                "a".repeat(64),
                SkinVariant.CLASSIC,
                Optional.empty());
        operations.durable = Optional.of(new ClientOperations.DurableAppearance(
                TestFixtures.ACCOUNT_ID,
                7,
                AppearanceSyncStatus.PENDING,
                Optional.empty(),
                Optional.of(oldLocal),
                Optional.empty()));
        QueuedExecutor worker = new QueuedExecutor();
        TrackingClientExecutor client = new TrackingClientExecutor();
        AtomicInteger localResolves = new AtomicInteger();
        AppearanceRefreshCoordinator<String> refresh = new AppearanceRefreshCoordinator<>(
                client,
                expected -> {
                    localResolves.incrementAndGet();
                    return CompletableFuture.completedFuture(Optional.empty());
                },
                ignored -> PlayerAppearanceSink.ApplyResult.UPDATED,
                DiagnosticSinks.discarding());
        ClientRuntime runtime = new ClientRuntime(
                operations,
                client,
                CANCELLED_PICKER,
                worker,
                TEXT,
                Optional.of(refresh),
                DiagnosticSinks.discarding());

        CompletableFuture<AppearanceRefreshCoordinator.Result> reconnect =
                runtime.afterReconnect();
        client.runFirst();
        worker.runFirst();

        UUID switchedAccountId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        operations.session = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                new GameSessionTokenSource.SessionIdentity(switchedAccountId, "switched"),
                null,
                (SessionFailureContext) null,
                "session switched");
        operations.durable = Optional.of(new ClientOperations.DurableAppearance(
                switchedAccountId,
                0,
                AppearanceSyncStatus.LOCAL_ONLY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
        client.runFirst();

        assertEquals(AppearanceRefreshCoordinator.Result.DEFERRED, reconnect.join());
        assertEquals(0, worker.size());
        assertEquals(0, localResolves.get());
        assertEquals(0, operations.reconciliationCalls);
        assertEquals(0, operations.applyCalls);
        assertEquals(0, operations.retrySessionCalls);
    }

    @Test
    void unresolvedLocalReconnectRunsOneReconciliationPerConnectionAndTicksNeverRetryIt() {
        FakeOperations operations = new FakeOperations();
        operations.appearanceRevision = 7;
        operations.appearanceSyncStatus = AppearanceSyncStatus.PENDING;
        AppliedAppearance pending = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID,
                "a".repeat(64),
                SkinVariant.CLASSIC,
                Optional.empty());
        operations.durable = Optional.of(new ClientOperations.DurableAppearance(
                TestFixtures.ACCOUNT_ID,
                operations.appearanceRevision,
                operations.appearanceSyncStatus,
                Optional.empty(),
                Optional.of(pending),
                Optional.empty()));
        AppearanceRefreshCoordinator<String> refresh = new AppearanceRefreshCoordinator<>(
                CLIENT,
                expected -> CompletableFuture.completedFuture(Optional.empty()),
                ignored -> PlayerAppearanceSink.ApplyResult.DEFERRED,
                DiagnosticSinks.discarding());
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.of(refresh));
        ClientProcessHost<Object> host = new ClientProcessHost<>(runtime, () -> {});
        Object connection = new Object();

        for (int tick = 0; tick < 200; tick++) {
            host.tick(connection, true);
        }

        assertEquals(1, operations.reconciliationCalls);

        host.tick(new Object(), true);

        assertEquals(2, operations.reconciliationCalls);
    }

    @Test
    void deletingActiveFinalPresetPublishesPendingAccountDefaultWithoutRemoteSignal() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.localFirst = true;
        AtomicInteger notifications = new AtomicInteger();
        AtomicReference<ExpectedAppearance> installed = new AtomicReference<>();
        SignedProfileResolver<String> resolver = expected -> CompletableFuture.completedFuture(Optional.of(
                new SignedProfileResolver.ResolvedProfile<>(
                        expected.profileId(), expected, "account-default")));
        PlayerAppearanceSink<String> sink = new PlayerAppearanceSink<>() {
            @Override
            public ApplyResult apply(SignedProfileResolver.ResolvedProfile<String> resolved) {
                installed.set(resolved.expectedAppearance());
                return ApplyResult.UPDATED;
            }

            @Override
            public ApplyResult reset(ExpectedAppearance expected) {
                installed.set(expected);
                return ApplyResult.UPDATED;
            }
        };
        ClientRuntime runtime = runtime(
                operations,
                Runnable::run,
                Optional.of(new AppearanceRefreshCoordinator<>(
                        CLIENT, resolver, sink, DiagnosticSinks.discarding())),
                Optional.of(notifications::incrementAndGet));
        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        runtime.dispatchWidget("gallery.preset." + presetId + ".delete");
        runtime.dispatchWidget("gallery.preset." + presetId + ".delete_confirm");

        assertTrue(runtime.snapshot().account().orElseThrow().presets().isEmpty());
        assertTrue(runtime.snapshot().activePresetId().isEmpty());
        assertEquals(2, runtime.snapshot().intentRevision());
        assertEquals(AppearanceSyncStatus.PENDING, runtime.snapshot().syncStatus());
        assertTrue(runtime.snapshot().lastMutation().isEmpty());
        ExpectedAppearance accountDefault = installed.get();
        assertTrue(accountDefault.skinTexture().isEmpty());
        assertTrue(accountDefault.localSkinSha256().isEmpty());
        assertTrue(accountDefault.skinModel().isEmpty());
        assertEquals("nclskins.status.deleted", runtime.snapshot().status().key());
        assertEquals(0, operations.applyCalls);
        assertEquals(2, operations.reconciliationCalls);
        assertEquals(0, notifications.get());
        assertHorizontallyCentered(
                runtime.view(320, 240, 0, 0).panels().stream()
                        .filter(panel -> panel.id().equals("gallery.card.add"))
                        .findFirst()
                        .orElseThrow()
                        .bounds(),
                320);
    }

    private static void assertSessionRetryConnectingForFiveTicks(ClientRuntime runtime) {
        for (int tick = 1; tick <= 5; tick++) {
            runtime.tick();
            assertTrue(runtime.snapshot().busy(), "session retry settled at tick " + tick);
            assertEquals(
                    UiMessage.info("nclskins.status.checking_session"),
                    runtime.snapshot().status());
            ViewSpec view = runtime.view(854, 480, 427, 180);
            assertEquals(
                    UiMessage.info("nclskins.session.connecting"),
                    view.texts().stream()
                            .filter(text -> text.id().equals("gallery.offline"))
                            .findFirst()
                            .orElseThrow()
                            .message());
            assertFalse(view.widget("gallery.retry_session").orElseThrow().enabled());
        }
    }

    private static void advanceTicks(ClientRuntime runtime, int count) {
        for (int tick = 0; tick < count; tick++) {
            runtime.tick();
        }
    }

    private static void settleScroll(ClientRuntime runtime) {
        for (int index = 0; index < 80; index++) {
            runtime.tick();
        }
    }

    private static int panelX(ViewSpec view, String panelId) {
        return view.panels().stream()
                .filter(panel -> panel.id().equals(panelId))
                .findFirst()
                .orElseThrow()
                .bounds()
                .x();
    }

    private static void assertNoPublication(AtomicInteger publications, Runnable action) {
        int before = publications.get();
        action.run();
        assertEquals(before, publications.get());
    }

    private static void assertHorizontallyCentered(Bounds bounds, int viewportWidth) {
        assertTrue(
                Math.abs(bounds.x() + bounds.width() / 2.0 - viewportWidth / 2.0) <= 0.5,
                () -> bounds + " is not horizontally centered in " + viewportWidth);
    }

    private static void assertImportFailure(
            boolean player,
            PublicSkinImportException.Code code,
            String expectedKey) {
        FakeOperations operations = new FakeOperations();
        PublicSkinImportException failure = new PublicSkinImportException(
                code, "sensitive source detail must not reach the view");
        if (player) {
            operations.playerImportFailure = failure;
        } else {
            operations.urlImportFailure = failure;
        }
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");
        runtime.dispatchText(player ? "add.player.input" : "add.url.input", player
                ? "Player"
                : "https://example.test/skin.png");
        runtime.dispatchWidget(player ? "add.player.load" : "add.url.load");

        assertEquals(UiMessage.error(expectedKey), runtime.snapshot().status());
        assertTrue(operations.uiPreferences.preferredSkinVariant().isEmpty());
        assertFalse(runtime.snapshot().status().literal());
        assertFalse(runtime.snapshot().status().key().contains("sensitive"));
    }

    private static OwnedCapeInventory capeInventory(int count) {
        List<OwnedCapeEntry> capes = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new OwnedCapeEntry(
                        "cape-" + index,
                        "Cape " + index,
                        RemoteAssetState.ACTIVE,
                        null))
                .toList();
        return new OwnedCapeInventory(
                OwnedCapeInventory.CURRENT_SCHEMA_VERSION,
                TestFixtures.ACCOUNT_ID,
                capes,
                Instant.EPOCH);
    }

    private static ClientRuntime runtime(
            FakeOperations operations,
            Executor worker,
            Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh) {
        return runtime(operations, worker, appearanceRefresh, Optional.empty());
    }

    private static ClientRuntime runtime(FakeOperations operations, FilePicker picker) {
        return new ClientRuntime(
                operations,
                CLIENT,
                picker,
                Runnable::run,
                TEXT,
                Optional.empty(),
                Optional.empty(),
                IMMEDIATE_READINESS_SCHEDULER,
                DiagnosticSinks.discarding());
    }

    private static ClientRuntime runtime(
            FakeOperations operations,
            Executor worker,
            Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh,
            Optional<ServerAppearanceRefreshNotifier> serverAppearanceRefreshNotifier) {
        return new ClientRuntime(
                operations,
                CLIENT,
                CANCELLED_PICKER,
                worker,
                TEXT,
                appearanceRefresh,
                serverAppearanceRefreshNotifier,
                IMMEDIATE_READINESS_SCHEDULER,
                DiagnosticSinks.discarding());
    }

    private static final ServerAppearanceReadinessCoordinator.DelayScheduler
            IMMEDIATE_READINESS_SCHEDULER = (delay, action) -> {
                action.run();
                return () -> {};
            };

    private static byte[] skinPng() {
        return skinPng(0xFFFF00FF);
    }

    private static byte[] skinPng(int color) {
        try {
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(8, 8, color);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static byte[] opaqueSkinPng(boolean slim) {
        try {
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    image.setRGB(x, y, 0xff3186d8);
                }
            }
            if (slim) {
                image.setRGB(54, 20, 0x003186d8);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static SkinAsset findSkin(ClientSnapshot snapshot, UUID id) {
        SkinAsset skin = findSkinOrNull(snapshot, id);
        if (skin == null) {
            throw new AssertionError("skin not found: " + id);
        }
        return skin;
    }

    private static SkinAsset findSkinOrNull(ClientSnapshot snapshot, UUID id) {
        return snapshot.account().orElseThrow().skinAssets().stream()
                .filter(skin -> skin.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    private static AppearancePreset findPreset(ClientSnapshot snapshot, UUID id) {
        return snapshot.account().orElseThrow().presets().stream()
                .filter(preset -> preset.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static SessionValidation session(SessionStatus status) {
        SessionValidation valid = TestFixtures.validSession();
        return new SessionValidation(
                status,
                valid.sessionIdentity(),
                valid.profile(),
                (SessionFailureContext) null,
                status.name());
    }

    private static final class DirectClientExecutor implements ClientExecutor {
        @Override
        public boolean isClientThread() {
            return true;
        }

        @Override
        public void execute(Runnable action) {
            action.run();
        }
    }

    private static final class QueuedExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private int size() {
            return tasks.size();
        }

        private void runFirst() {
            tasks.remove(0).run();
        }
    }

    private static final class TrackingClientExecutor implements ClientExecutor {
        private final List<Runnable> tasks = new ArrayList<>();
        private boolean clientThread;

        @Override
        public boolean isClientThread() {
            return clientThread;
        }

        @Override
        public void execute(Runnable action) {
            tasks.add(action);
        }

        private void runFirst() {
            Runnable action = tasks.remove(0);
            clientThread = true;
            try {
                action.run();
            } finally {
                clientThread = false;
            }
        }
    }

    private static final class FakeOperations implements ClientOperations {
        private AccountState account = TestFixtures.account(0);
        private OwnedCapeInventory ownedCapes = OwnedCapeInventory.empty(
                TestFixtures.ACCOUNT_ID, Instant.EPOCH);
        private SessionValidation session = TestFixtures.validSession();
        private MutationResult result = MutationResult.APPLIED;
        private RemoteAppearanceImpact remoteAppearanceImpact =
                RemoteAppearanceImpact.CONFIRMED_CHANGED;
        private Set<RecoveryAction> recovery = Set.of();
        private Optional<DurableAppearance> durable = Optional.empty();
        private Optional<UUID> activePresetId = Optional.empty();
        private long appearanceRevision;
        private AppearanceSyncStatus appearanceSyncStatus = AppearanceSyncStatus.LOCAL_ONLY;
        private boolean localFirst;
        private boolean reconcileWithOutcome;
        private boolean rateLimited;
        private Duration rateLimitRemaining = Duration.ofSeconds(1);
        private int sequence = 100;
        private int applyCalls;
        private int restoreCalls;
        private int retrySessionCalls;
        private int reconciliationCalls;
        private int retryCapeCalls;
        private int skinPreviewCalls;
        private String lastCapeId;
        private final List<ReconciliationTrigger> reconciliationTriggers = new ArrayList<>();
        private final List<ReconciliationKey> reconciliationKeys = new ArrayList<>();
        private String deleteWarning;
        private boolean removeFinalDespiteFailedReset;
        private RemoteAppearanceImpact settlementFailureImpact;
        private AccountUiPreferences uiPreferences =
                AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID);
        private Set<SkinModel> failedCatalogModels = Set.of();
        private byte[] catalogPng = skinPng();
        private int catalogPreviewCalls;
        private UUID personalCatalogAssetId;
        private int removePersonalCalls;
        private boolean failPersonalRemoval;
        private java.util.function.BooleanSupplier reconciliationPrecondition = () -> true;
        private boolean reconciliationBeforePrecondition;
        private int storagePreflightCalls;
        private RuntimeException storagePreflightFailure;
        private Exception retrySessionFailure;
        private boolean failEditorSave;
        private Exception playerImportFailure;
        private Exception urlImportFailure;
        private SkinVariant playerImportVariant = SkinVariant.CLASSIC;
        private SkinVariant urlImportVariant = SkinVariant.CLASSIC;
        private int playerImportCalls;
        private Optional<InitialData> warmedInitialData = Optional.empty();
        private Exception externalImportFailure;
        private ExternalImportResult externalImportResult;
        private boolean externalSourceAvailable = true;
        private final EnumMap<ExternalImportSource, ExternalImportProbe> externalSourceProbes =
                new EnumMap<>(ExternalImportSource.class);
        private int externalCommitCalls;
        private ExternalImportSource lastExternalSource;
        private Optional<Path> lastExternalRoot = Optional.empty();

        @Override
        public void verifyStorageAccess() {
            storagePreflightCalls++;
            if (storagePreflightFailure != null) {
                throw storagePreflightFailure;
            }
        }

        @Override
        public InitialData initialize() {
            return initial();
        }

        @Override
        public Optional<InitialData> warmedInitialData() {
            return warmedInitialData;
        }

        @Override
        public List<SkinCatalogSource.CollectionDescriptor> catalogCollections() {
            List<SkinCatalogSource.CollectionDescriptor> collections = new ArrayList<>();
            account.personalSkins().stream()
                    .filter(PersonalSkinEntry::visible)
                    .findFirst()
                    .ifPresent(entry -> {
                        personalCatalogAssetId = entry.optionalAssetId(SkinVariant.CLASSIC)
                                .orElseThrow();
                        boolean otherPlayer = entry.source() == PersonalSkinSource.PLAYER_NAME;
                        collections.add(new SkinCatalogSource.CollectionDescriptor(
                                otherPlayer
                                        ? PersonalSkinCatalog.OTHER_PLAYERS_COLLECTION_ID
                                        : PersonalSkinCatalog.COLLECTION_ID,
                                CatalogText.literal(otherPlayer ? "Other players' skins" : "Your skins"),
                                Optional.empty(),
                                Optional.empty(),
                                List.of(new SkinCatalogSource.SkinDescriptor(
                                        entry.sha256(),
                                        CatalogText.literal(entry.displayName()),
                                        Optional.empty(),
                                        Optional.empty(),
                                        List.of(SkinModel.CLASSIC))),
                                CatalogCollectionOrder.personal(otherPlayer
                                        ? PersonalSkinCatalog.OTHER_PLAYERS_SOURCE_ID
                                        : PersonalSkinCatalog.SOURCE_ID)));
                    });
            collections.addAll(MinecraftSkinCatalog.collections());
            return List.copyOf(collections);
        }

        @Override
        public byte[] loadCatalogSkin(String collectionId, String skinId, SkinModel model)
                throws IOException {
            catalogPreviewCalls++;
            if (failedCatalogModels.contains(model)) {
                throw new IOException("catalog variant unavailable");
            }
            return catalogPng.clone();
        }

        @Override
        public Optional<UUID> reusableCatalogSkinAsset(
                String collectionId, String skinId, SkinModel model) throws IOException {
            if (!PersonalSkinCatalog.isCollection(collectionId)) {
                return Optional.empty();
            }
            if (personalCatalogAssetId == null || model != SkinModel.CLASSIC) {
                throw new IOException("personal catalog snapshot is unavailable");
            }
            return Optional.of(personalCatalogAssetId);
        }

        @Override
        public Optional<AccountUiPreferences> loadUiPreferences() {
            return Optional.of(uiPreferences);
        }

        @Override
        public void setSelectedAddSourceTab(AddSourceTab tab) {
            uiPreferences = uiPreferences.withSelectedAddSourceTab(tab);
        }

        @Override
        public void setCollectionCollapsed(String collectionId, boolean collapsed) {
            uiPreferences = uiPreferences.withCollectionCollapsed(collectionId, collapsed);
        }

        @Override
        public void setPreferredSkinVariant(SkinVariant variant) {
            uiPreferences = uiPreferences.withPreferredSkinVariant(variant);
        }

        @Override
        public AccountState importSkin(String name, SkinVariant variant, byte[] normalizedPng) {
            Instant now = nextTime();
            SkinAsset asset = new SkinAsset(
                    nextId(),
                    name,
                    String.format("%064x", sequence),
                    variant,
                    SkinSource.IMPORTED,
                    now,
                    now);
            account = copy(append(account.skinAssets(), asset), account.presets());
            return account;
        }

        @Override
        public ImportDraft loadPlayerSkin(String playerNameOrUuid) throws Exception {
            playerImportCalls++;
            if (playerImportFailure != null) {
                throw playerImportFailure;
            }
            return new ImportDraft(
                    "Player", playerImportVariant, skinPng(), PersonalSkinSource.PLAYER_NAME);
        }

        @Override
        public ImportDraft loadUrlSkin(String url) throws Exception {
            if (urlImportFailure != null) {
                throw urlImportFailure;
            }
            return new ImportDraft(
                    "Remote skin", urlImportVariant, skinPng(), PersonalSkinSource.URL);
        }

        @Override
        public ExternalImportProbe probeExternalSource(
                ExternalImportSource source, Optional<Path> selectedRoot) throws Exception {
            lastExternalSource = source;
            lastExternalRoot = selectedRoot;
            if (externalImportFailure != null) {
                throw externalImportFailure;
            }
            return externalSourceProbes.getOrDefault(source, externalSourceAvailable
                    ? ExternalImportProbe.AVAILABLE
                    : ExternalImportProbe.UNAVAILABLE);
        }

        @Override
        public ExternalImportReview prepareExternalAppearances(
                ExternalImportSource source, Optional<Path> selectedRoot) throws Exception {
            lastExternalSource = source;
            lastExternalRoot = selectedRoot;
            if (externalImportFailure != null) {
                throw externalImportFailure;
            }
            return new ExternalImportReview(source, List.of(new ExternalImportCandidate(
                    "candidate-0",
                    "Imported",
                    SkinVariant.CLASSIC,
                    PersonalSkinSource.FILE,
                    skinPng(),
                    "0".repeat(64),
                    null,
                    0,
                    false)), 0, 0);
        }

        @Override
        public ExternalImportResult commitExternalAppearances(
                List<ExternalImportCandidate> selected, int skipped, int warnings) throws Exception {
            externalCommitCalls++;
            if (externalImportFailure != null) {
                throw externalImportFailure;
            }
            return externalImportResult == null
                    ? new ExternalImportResult(account, 1, 0, 0, 0)
                    : externalImportResult;
        }

        @Override
        public AccountState renameSkin(UUID skinId, String newName) {
            Instant now = nextTime();
            List<SkinAsset> skins = account.skinAssets().stream()
                    .map(skin -> skin.id().equals(skinId) ? skin.renamed(newName, now) : skin)
                    .toList();
            account = copy(skins, account.presets());
            return account;
        }

        @Override
        public AccountState changeSkinVariant(UUID skinId, SkinVariant variant) {
            Instant now = nextTime();
            List<SkinAsset> skins = account.skinAssets().stream()
                    .map(skin -> skin.id().equals(skinId) ? skin.withVariant(variant, now) : skin)
                    .toList();
            account = copy(skins, account.presets());
            return account;
        }

        @Override
        public AccountState duplicateSkin(UUID skinId, String newName) {
            SkinAsset source = account.skinAssets().stream()
                    .filter(skin -> skin.id().equals(skinId))
                    .findFirst()
                    .orElseThrow();
            account = copy(
                    append(account.skinAssets(), source.duplicate(nextId(), newName, nextTime())),
                    account.presets());
            return account;
        }

        @Override
        public AccountState deleteSkin(UUID skinId) {
            account = copy(
                    account.skinAssets().stream().filter(skin -> !skin.id().equals(skinId)).toList(),
                    account.presets());
            return account;
        }

        @Override
        public AccountState removePersonalSkin(String sha256) throws IOException {
            removePersonalCalls++;
            if (failPersonalRemoval) {
                throw new IOException("personal catalog write failed");
            }
            Instant now = nextTime();
            List<PersonalSkinEntry> personalSkins = account.personalSkins().stream()
                    .map(entry -> entry.sha256().equals(sha256) ? entry.hidden(now) : entry)
                    .toList();
            account = new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION,
                    TestFixtures.ACCOUNT_ID,
                    account.skinAssets(),
                    personalSkins,
                    account.presets(),
                    now);
            return account;
        }

        @Override
        public AccountState renamePersonalSkin(String sha256, String newName) {
            Instant now = nextTime();
            List<PersonalSkinEntry> personalSkins = account.personalSkins().stream()
                    .map(entry -> entry.sha256().equals(sha256)
                            ? entry.renamed(newName, now)
                            : entry)
                    .toList();
            account = new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION,
                    TestFixtures.ACCOUNT_ID,
                    account.skinAssets(),
                    personalSkins,
                    account.presets(),
                    now);
            return account;
        }

        @Override
        public InitialData resetLibrary() {
            account = TestFixtures.account(0);
            return initial();
        }

        @Override
        public EditorSave saveEditor(EditorSaveRequest request) throws IOException {
            if (failEditorSave) {
                throw new IOException("editor save failed");
            }
            UUID presetId = request.originalPresetId().orElseGet(this::nextId);
            SkinReference skin = request.skin();
            if (request.pngBytes().isPresent()) {
                Instant skinTime = nextTime();
                SkinAsset imported = new SkinAsset(
                        nextId(),
                        request.name() + " skin",
                        String.format("%064x", sequence),
                        request.variant(),
                        SkinSource.IMPORTED,
                        skinTime,
                        skinTime,
                        request.catalogOrigin());
                account = copy(append(account.skinAssets(), imported), account.presets());
                skin = SkinReference.asset(imported.id());
            } else if (request.variant() != request.initialVariant()) {
                UUID selectedSkinId = skin.assetId();
                SkinAsset selected = account.skinAssets().stream()
                        .filter(asset -> asset.id().equals(selectedSkinId))
                        .findFirst()
                        .orElseThrow();
                Instant skinTime = nextTime();
                SkinAsset changed = selected.withVariant(request.variant(), skinTime);
                List<SkinAsset> skins = account.skinAssets().stream()
                        .map(asset -> asset.id().equals(changed.id()) ? changed : asset)
                        .toList();
                account = copy(skins, account.presets());
            }
            Instant now = nextTime();
            AppearancePreset existing = account.presets().stream()
                    .filter(preset -> preset.id().equals(presetId))
                    .findFirst()
                    .orElse(null);
            AppearancePreset saved = new AppearancePreset(
                    presetId,
                    request.name(),
                    skin,
                    request.capeId().orElse(null),
                    request.outerLayerVisibility(),
                    existing == null ? now : existing.createdAt(),
                    now);
            List<AppearancePreset> presets = new ArrayList<>(account.presets());
            presets.removeIf(preset -> preset.id().equals(presetId));
            presets.add(saved);
            account = copy(account.skinAssets(), presets);
            if (activePresetId.filter(presetId::equals).isEmpty()) {
                return new EditorSave(account, presetId);
            }
            appearanceRevision++;
            appearanceSyncStatus = AppearanceSyncStatus.PENDING;
            AppliedAppearance local = AppliedAppearance.localSkin(
                    TestFixtures.ACCOUNT_ID,
                    "a".repeat(64),
                    request.variant(),
                    Optional.empty());
            durable = Optional.of(new DurableAppearance(
                    account.accountId(),
                    appearanceRevision,
                    appearanceSyncStatus,
                    activePresetId,
                    Optional.of(local),
                    Optional.of(request.outerLayerVisibility())));
            return new EditorSave(account, presetId, durable);
        }

        @Override
        public PresetDelete deletePreset(UUID presetId) {
            if (localFirst) {
                account = copy(
                        account.skinAssets(),
                        account.presets().stream()
                                .filter(preset -> !preset.id().equals(presetId))
                                .toList());
                activePresetId = Optional.empty();
                appearanceRevision++;
                appearanceSyncStatus = AppearanceSyncStatus.PENDING;
                AppliedAppearance accountDefault = AppliedAppearance.accountDefault(
                        TestFixtures.ACCOUNT_ID, Optional.empty());
                durable = Optional.of(new DurableAppearance(
                        account.accountId(),
                        appearanceRevision,
                        appearanceSyncStatus,
                        activePresetId,
                        Optional.of(accountDefault),
                        Optional.of(com.naocraftlab.skins.client.OuterLayerVisibility.allVisible())));
                return PresetDelete.local(account, durable.orElseThrow());
            }
            boolean finalPreset = account.presets().size() == 1;
            if (finalPreset) {
                if (result == MutationResult.APPLIED || removeFinalDespiteFailedReset) {
                    account = copy(account.skinAssets(), List.of());
                }
                return PresetDelete.withRemoteReset(
                        remote(),
                        deleteWarning == null ? List.of() : List.of(deleteWarning));
            }
            account = copy(
                    account.skinAssets(),
                    account.presets().stream().filter(preset -> !preset.id().equals(presetId)).toList());
            return PresetDelete.local(account);
        }

        @Override
        public RemoteResult applyPreset(UUID presetId) {
            applyCalls++;
            if (settlementFailureImpact != null) {
                throw new RemoteMutationSettlementException(settlementFailureImpact);
            }
            return remote();
        }

        @Override
        public PresetUse usePreset(UUID presetId) throws Exception {
            if (!localFirst) {
                RemoteResult remote = applyPreset(presetId);
                activePresetId = Optional.of(presetId);
                return new PresetUse(
                        remote.account(),
                        remote.session(),
                        presetId,
                        remote.outcome().optionalAppliedAppearance(),
                        Optional.of(remote),
                        false,
                        false);
            }
            AppearancePreset preset = account.presets().stream()
                    .filter(candidate -> candidate.id().equals(presetId))
                    .findFirst()
                    .orElseThrow();
            activePresetId = Optional.of(presetId);
            appearanceRevision++;
            appearanceSyncStatus = AppearanceSyncStatus.PENDING;
            AppliedAppearance local = AppliedAppearance.localSkin(
                    TestFixtures.ACCOUNT_ID,
                    "a".repeat(64),
                    SkinVariant.CLASSIC,
                    Optional.empty());
            durable = Optional.of(new DurableAppearance(
                    account.accountId(),
                    appearanceRevision,
                    appearanceSyncStatus,
                    activePresetId,
                    Optional.of(local),
                    Optional.of(preset.outerLayerVisibility())));
            return new PresetUse(
                    account,
                    session,
                    presetId,
                    Optional.of(local),
                    Optional.empty(),
                    true,
                    true,
                    Optional.of(preset.outerLayerVisibility()),
                    appearanceRevision,
                    appearanceSyncStatus);
        }

        @Override
        public Optional<ReconciliationResult> reconcileAppearance(ReconciliationTrigger trigger) {
            if (!reconciliationPrecondition.getAsBoolean()) {
                reconciliationBeforePrecondition = true;
            }
            reconciliationCalls++;
            reconciliationTriggers.add(trigger);
            reconciliationKeys.add(new ReconciliationKey(
                    account.accountId(), appearanceRevision));
            if (settlementFailureImpact != null) {
                appearanceSyncStatus = settlementFailureImpact == RemoteAppearanceImpact.UNCERTAIN
                        ? AppearanceSyncStatus.UNKNOWN
                        : AppearanceSyncStatus.OFFICIAL;
                durable = Optional.of(new DurableAppearance(
                        account.accountId(),
                        appearanceRevision,
                        appearanceSyncStatus,
                        activePresetId,
                        durable.flatMap(DurableAppearance::localAppearance),
                        durable.flatMap(DurableAppearance::outerLayerVisibility)));
                throw new RemoteMutationSettlementException(settlementFailureImpact);
            }
            if (reconcileWithOutcome) {
                PresetApplicationOutcome outcome = remote().outcome();
                appearanceSyncStatus = switch (outcome.result()) {
                    case APPLIED -> AppearanceSyncStatus.OFFICIAL;
                    case PARTIAL -> AppearanceSyncStatus.PARTIAL;
                    case UNKNOWN -> AppearanceSyncStatus.UNKNOWN;
                    case FAILED, SESSION_EXPIRED -> AppearanceSyncStatus.PENDING;
                };
                durable = Optional.of(new DurableAppearance(
                        account.accountId(),
                        appearanceRevision,
                        appearanceSyncStatus,
                        activePresetId,
                        outcome.optionalAppliedAppearance(),
                        durable.flatMap(DurableAppearance::outerLayerVisibility)));
                return Optional.of(new ReconciliationResult(
                        account,
                        session,
                        Optional.empty(),
                        durable.orElseThrow(),
                        Optional.of(outcome)));
            }
            return Optional.empty();
        }

        @Override
        public RemoteResult retryCape(String capeId) {
            retryCapeCalls++;
            lastCapeId = capeId;
            return remote();
        }

        @Override
        public RemoteResult restorePreviousAppearance(PresetApplicationOutcome outcome) {
            restoreCalls++;
            return remote();
        }

        @Override
        public byte[] loadSkinPreview(UUID skinId) {
            skinPreviewCalls++;
            return new byte[] {7, 8, 9};
        }

        @Override
        public Optional<byte[]> loadCapePreview(String capeId) {
            return Optional.of(new byte[] {4, 5, 6});
        }

        @Override
        public InitialData retrySession() throws Exception {
            retrySessionCalls++;
            if (retrySessionFailure != null) {
                throw retrySessionFailure;
            }
            return initial();
        }

        @Override
        public boolean rateLimited() {
            return rateLimited;
        }

        @Override
        public Optional<Duration> rateLimitRemaining() {
            return rateLimited ? Optional.of(rateLimitRemaining) : Optional.empty();
        }

        @Override
        public GameSessionTokenSource.SessionIdentity sessionIdentity() {
            return session.sessionIdentity();
        }

        @Override
        public Optional<DurableAppearance> durableAppearance() {
            return durable;
        }

        private InitialData initial() {
            return new InitialData(
                    account,
                    session,
                    Optional.empty(),
                    activePresetId,
                    durable.flatMap(DurableAppearance::localAppearance),
                    appearanceSyncStatus == AppearanceSyncStatus.PENDING,
                    List.of(),
                    uiPreferences,
                    durable.flatMap(DurableAppearance::outerLayerVisibility),
                    ownedCapes,
                    appearanceRevision,
                    appearanceSyncStatus);
        }

        private RemoteResult remote() {
            AppliedAppearance appearance = result == MutationResult.APPLIED || result == MutationResult.PARTIAL
                    ? AppliedAppearance.accountDefault(TestFixtures.ACCOUNT_ID, Optional.empty())
                    : null;
            PresetApplicationOutcome outcome = new PresetApplicationOutcome(
                    result,
                    ApplicationPhase.COMPLETE,
                    session.profile(),
                    session.profile(),
                    appearance,
                    null,
                    recovery,
                    remoteAppearanceImpact,
                    result.name());
            return new RemoteResult(outcome, account, session, Optional.empty());
        }

        private AccountState copy(List<SkinAsset> skins, List<AppearancePreset> presets) {
            return new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION,
                    TestFixtures.ACCOUNT_ID,
                    skins,
                    account.personalSkins(),
                    presets,
                    nextTime());
        }

        private String seedPersonalSkin(String name) {
            return seedPersonalSkin(name, PersonalSkinSource.FILE);
        }

        private String seedPersonalSkin(String name, PersonalSkinSource source) {
            String hash = "a".repeat(64);
            Instant now = nextTime();
            SkinAsset asset = new SkinAsset(
                    nextId(),
                    name,
                    hash,
                    SkinVariant.CLASSIC,
                    SkinSource.IMPORTED,
                    now,
                    now);
            PersonalSkinEntry entry = new PersonalSkinEntry(
                    hash,
                    name,
                    source,
                    now,
                    now,
                    Map.of(SkinVariant.CLASSIC, asset.id()),
                    true);
            account = new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION,
                    TestFixtures.ACCOUNT_ID,
                    append(account.skinAssets(), asset),
                    List.of(entry),
                    account.presets(),
                    nextTime());
            return hash;
        }

        private Instant nextTime() {
            return Instant.parse("2026-01-01T00:00:00Z").plusSeconds(sequence++);
        }

        private UUID nextId() {
            return new UUID(9L, sequence++);
        }

        private static <T> List<T> append(List<T> values, T value) {
            List<T> result = new ArrayList<>(values);
            result.add(value);
            return List.copyOf(result);
        }
    }
}

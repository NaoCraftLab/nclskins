package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.MutationResult;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.ApplicationPhase;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.RecoveryAction;
import com.naocraftlab.skins.core.service.SessionValidation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ClientRuntimeRegressionTest {
    private static final ClientExecutor CLIENT = new ClientExecutor() {
        @Override
        public boolean isClientThread() {
            return true;
        }

        @Override
        public void execute(Runnable action) {
            action.run();
        }
    };
    private static final FilePicker CANCELLED_PICKER =
            () -> CompletableFuture.completedFuture(Optional.empty());
    private static final TextResolver TEXT = message -> switch (message.key()) {
        case "nclskins.editor.default_name" -> "Preset " + message.arguments().get(0);
        case "nclskins.mutation.partial" -> "The skin was applied, but the cape needs attention.";
        case "nclskins.status.reconnect_refresh" ->
                "The local player preview will update after reconnecting.";
        default -> message.key();
    };

    @Test
    void failedEditorSaveReleasesBusyPreservesDraftAndCanStillBeCancelled() {
        StubOperations operations = new StubOperations();
        operations.failEditorSave = true;
        ClientRuntime runtime = runtime(operations);
        runtime.initialize();
        runtime.view(854, 480, 0, 0);

        UUID presetId = runtime.snapshot().account().orElseThrow().presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        runtime.dispatchText("editor.name", "Draft survives");
        runtime.dispatchWidget("editor.model");
        PresetEditorModel beforeSave = runtime.snapshot().editor().orElseThrow();

        runtime.dispatchWidget("editor.save");

        ClientSnapshot failed = runtime.snapshot();
        PresetEditorModel retained = failed.editor().orElseThrow();
        assertFalse(failed.busy());
        assertFalse(retained.busy());
        assertEquals("Draft survives", retained.name());
        assertEquals(beforeSave.skin(), retained.skin());
        assertEquals(beforeSave.variant(), retained.variant());
        assertEquals(beforeSave.capeId(), retained.capeId());
        assertEquals("nclskins.error.save", retained.status().orElseThrow().key());
        assertEquals("nclskins.error.save", failed.status().key());

        runtime.dispatchWidget("editor.cancel");

        assertTrue(runtime.snapshot().editor().isEmpty());
        assertEquals("gallery", runtime.view(854, 480, 0, 0).screenId());
    }

    @Test
    @SuppressWarnings("deprecation")
    void unsafePreviousRemoteRestoreIsNotPublishedOrDispatched() {
        StubOperations operations = new StubOperations();
        ClientRuntime runtime = runtime(operations);
        runtime.initialize();
        UUID presetId = operations.account.presets().get(1).id();

        operations.mutationResult = MutationResult.PARTIAL;
        operations.recoveryActions = Set.of(
                RecoveryAction.RETRY_CAPE,
                RecoveryAction.RESTORE_PREVIOUS_APPEARANCE);
        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(Optional.of(presetId), runtime.snapshot().selectedPresetId());
        assertTrue(runtime.snapshot().activePresetId().isEmpty());
        assertTrue(runtime.snapshot().recoveryActions().contains(RecoveryAction.RETRY_CAPE));
        assertFalse(runtime.snapshot().recoveryActions()
                .contains(RecoveryAction.RESTORE_PREVIOUS_APPEARANCE));
        assertTrue(runtime.view(854, 480, 0, 0).widget("gallery.restore").isEmpty());

        runtime.dispatchWidget("gallery.restore");
        runtime.activateCape("legacy-cape");
        runtime.hideCape();

        assertEquals(0, operations.restoreCalls);
        assertEquals(0, operations.retryCapeCalls);
    }

    @Test
    void deferredLocalRefreshKeepsPartialMutationErrorAsTheLeadingStatus() {
        StubOperations operations = new StubOperations();
        operations.mutationResult = MutationResult.PARTIAL;
        AppearanceRefreshCoordinator<Object> refresh = new AppearanceRefreshCoordinator<>(
                CLIENT,
                ignored -> CompletableFuture.completedFuture(Optional.empty()),
                ignored -> PlayerAppearanceSink.ApplyResult.DEFERRED);
        ClientRuntime runtime = new ClientRuntime(
                operations,
                CLIENT,
                CANCELLED_PICKER,
                Runnable::run,
                TEXT,
                Optional.of(refresh));
        runtime.initialize();
        UUID presetId = operations.account.presets().get(1).id();

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        UiMessage status = runtime.snapshot().status();
        assertEquals(
                "The skin was applied, but the cape needs attention. "
                        + "The local player preview will update after reconnecting.",
                status.key());
        assertEquals(UiMessage.Severity.ERROR, status.severity());
        assertTrue(status.literal());
        assertTrue(status.arguments().isEmpty());
    }

    @Test
    void editorPublishesCanonicalSkinAndCapePreviewErrors() {
        StubOperations operations = new StubOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker);
        runtime.initialize();
        worker.runFirst();
        UUID presetId = operations.account.presets().get(1).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        ViewSpec.Preview preview = runtime.view(854, 480, 0, 0).previews().get(0);

        operations.failNextSkinPreview = true;
        CompletableFuture<Optional<byte[]>> skin = runtime.loadSkinPreview(preview);
        worker.runFirst();

        assertTrue(skin.join().isEmpty());
        assertEquals(
                "nclskins.error.preview",
                runtime.snapshot().editor().orElseThrow().status().orElseThrow().key());

        operations.failNextCapePreview = true;
        CompletableFuture<Optional<byte[]>> cape = runtime.loadCapePreview(preview);
        worker.runFirst();

        assertTrue(cape.join().isEmpty());
        assertEquals(
                "nclskins.error.cape_preview",
                runtime.snapshot().editor().orElseThrow().status().orElseThrow().key());
    }

    @Test
    void identicalConcurrentSkinAndCapePreviewsCoalesceAndPublishIndependentArrays() {
        StubOperations operations = new StubOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker);
        SkinReference skin = SkinReference.asset(TestFixtures.CLASSIC_ID);

        CompletableFuture<Optional<byte[]>> firstSkin = runtime.loadSkinPreview(skin);
        CompletableFuture<Optional<byte[]>> secondSkin = runtime.loadSkinPreview(skin);
        assertEquals(1, worker.size());
        worker.runFirst();

        byte[] firstSkinBytes = firstSkin.join().orElseThrow();
        byte[] secondSkinBytes = secondSkin.join().orElseThrow();
        assertEquals(1, operations.skinPreviewCalls.get());
        assertArrayEquals(new byte[] {1, 2, 3}, firstSkinBytes);
        assertArrayEquals(firstSkinBytes, secondSkinBytes);
        assertNotSame(firstSkinBytes, secondSkinBytes);
        firstSkinBytes[0] = 99;
        assertArrayEquals(new byte[] {1, 2, 3}, secondSkinBytes);

        CompletableFuture<Optional<byte[]>> firstCape = runtime.loadCapePreview("cape-shared");
        CompletableFuture<Optional<byte[]>> secondCape = runtime.loadCapePreview("cape-shared");
        assertEquals(1, worker.size());
        worker.runFirst();

        byte[] firstCapeBytes = firstCape.join().orElseThrow();
        byte[] secondCapeBytes = secondCape.join().orElseThrow();
        assertEquals(1, operations.capePreviewCalls.get());
        assertArrayEquals(new byte[] {4, 5, 6}, firstCapeBytes);
        assertArrayEquals(firstCapeBytes, secondCapeBytes);
        assertNotSame(firstCapeBytes, secondCapeBytes);
        firstCapeBytes[0] = 99;
        assertArrayEquals(new byte[] {4, 5, 6}, secondCapeBytes);
    }

    @Test
    void failedPreviewClearsInflightSoTheSameKeyCanBeRetried() {
        StubOperations operations = new StubOperations();
        operations.failNextSkinPreview = true;
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker);
        SkinReference skin = SkinReference.asset(TestFixtures.CLASSIC_ID);

        CompletableFuture<Optional<byte[]>> first = runtime.loadSkinPreview(skin);
        CompletableFuture<Optional<byte[]>> joined = runtime.loadSkinPreview(skin);
        worker.runFirst();

        assertTrue(first.join().isEmpty());
        assertTrue(joined.join().isEmpty());
        assertEquals(1, operations.skinPreviewCalls.get());

        CompletableFuture<Optional<byte[]>> retry = runtime.loadSkinPreview(skin);
        assertEquals(1, worker.size());
        worker.runFirst();

        assertArrayEquals(new byte[] {1, 2, 3}, retry.join().orElseThrow());
        assertEquals(2, operations.skinPreviewCalls.get());
    }

    @Test
    void startupWarmupIsIdempotentAndDoesNotEnterTheGalleryLifecycle() {
        StubOperations operations = new StubOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker);
        ClientSnapshot beforeWarmup = runtime.snapshot();

        runtime.warmSession();
        runtime.warmSession();

        assertEquals(ClientSnapshot.Lifecycle.NEW, runtime.snapshot().lifecycle());
        assertEquals(beforeWarmup, runtime.snapshot());
        assertEquals(0, operations.initializeCalls.get());
        assertEquals(0, operations.warmSessionCalls.get());
        assertEquals(1, worker.size());

        worker.runFirst();
        runtime.warmSession();

        assertEquals(1, operations.warmSessionCalls.get());
        assertEquals(0, operations.initializeCalls.get());
        assertEquals(ClientSnapshot.Lifecycle.NEW, runtime.snapshot().lifecycle());
        assertEquals(0, worker.size());

        runtime.initialize();
        assertEquals(ClientSnapshot.Lifecycle.INITIALIZING, runtime.snapshot().lifecycle());
        assertEquals(0, operations.initializeCalls.get());
        assertEquals(1, worker.size());
        worker.runFirst();

        assertEquals(1, operations.initializeCalls.get());
        assertEquals(1, operations.warmSessionCalls.get());
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
    }

    @Test
    void startupWarmupSchedulesRevisionZeroProfileCheckpoint() {
        StubOperations operations = new StubOperations();
        operations.warmCheckpoint = true;
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker);

        runtime.warmSession();
        worker.runFirst();

        assertEquals(ClientSnapshot.Lifecycle.NEW, runtime.snapshot().lifecycle());
        assertEquals(1, worker.size());
        worker.runFirst();

        assertEquals(1, operations.reconciliationCalls.get());
        assertEquals(
                List.of(ClientOperations.ReconciliationTrigger.PROCESS_START),
                operations.reconciliationTriggers);
        assertFalse(runtime.snapshot().syncInProgress());
    }

    @Test
    void editorDraftIsSideEffectFreeAndOnlyActiveSaveAppliesItsMask() {
        StubOperations operations = new StubOperations();
        operations.visibilityInResults = true;
        List<OuterLayerVisibility> applied = new ArrayList<>();
        ClientRuntime runtime = new ClientRuntime(
                operations,
                CLIENT,
                CANCELLED_PICKER,
                Runnable::run,
                TEXT,
                Optional.empty(),
                Optional.of(applied::add),
                Optional.empty(),
                ServerAppearanceReadinessCoordinator.DelayScheduler.system());

        runtime.initialize();
        assertEquals(List.of(OuterLayerVisibility.allVisible()), applied);
        UUID active = operations.account.presets().get(0).id();
        UUID inactive = operations.account.presets().get(1).id();

        runtime.dispatchWidget("gallery.preset." + inactive + ".edit");
        runtime.dispatchWidget("editor.outer_layer.body");
        assertEquals(1, applied.size());
        runtime.dispatchWidget("editor.save");
        assertEquals(1, applied.size());

        runtime.dispatchWidget("gallery.preset." + active + ".edit");
        runtime.dispatchWidget("editor.outer_layer.head");
        assertEquals(1, applied.size());
        runtime.dispatchWidget("editor.save");

        assertEquals(2, applied.size());
        assertFalse(applied.get(1).visible(OuterLayerPart.HEAD));
        assertTrue(applied.get(1).visible(OuterLayerPart.BODY));
    }

    private static ClientRuntime runtime(StubOperations operations) {
        return runtime(operations, Runnable::run);
    }

    private static ClientRuntime runtime(StubOperations operations, Executor worker) {
        return new ClientRuntime(
                operations,
                CLIENT,
                CANCELLED_PICKER,
                worker,
                TEXT,
                Optional.empty());
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

    private static final class StubOperations implements ClientOperations {
        private final SessionValidation session = TestFixtures.validSession();
        private AccountState account = TestFixtures.account(2);
        private boolean failEditorSave;
        private MutationResult mutationResult = MutationResult.APPLIED;
        private Set<RecoveryAction> recoveryActions = Set.of();
        private String lastCapeId;
        private Optional<UUID> rememberedActivePreset = Optional.empty();
        private int restoreCalls;
        private int retryCapeCalls;
        private final AtomicInteger warmSessionCalls = new AtomicInteger();
        private final AtomicInteger initializeCalls = new AtomicInteger();
        private final AtomicInteger skinPreviewCalls = new AtomicInteger();
        private final AtomicInteger capePreviewCalls = new AtomicInteger();
        private final AtomicInteger reconciliationCalls = new AtomicInteger();
        private final List<ReconciliationTrigger> reconciliationTriggers = new ArrayList<>();
        private boolean failNextSkinPreview;
        private boolean failNextCapePreview;
        private boolean visibilityInResults;
        private boolean warmCheckpoint;
        private long appearanceRevision;

        @Override
        public void warmSession() {
            warmSessionCalls.incrementAndGet();
        }

        @Override
        public boolean warmedReconciliationRecommended() {
            return warmCheckpoint;
        }

        @Override
        public Optional<DurableAppearance> warmedDurableAppearance() {
            return warmCheckpoint
                    ? Optional.of(new DurableAppearance(
                            account.accountId(),
                            0,
                            AppearanceSyncStatus.LOCAL_ONLY,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()))
                    : Optional.empty();
        }

        @Override
        public Optional<ReconciliationResult> reconcileAppearance(
                ReconciliationTrigger trigger) {
            reconciliationCalls.incrementAndGet();
            reconciliationTriggers.add(trigger);
            return Optional.empty();
        }

        @Override
        public InitialData initialize() {
            initializeCalls.incrementAndGet();
            return new InitialData(
                    account,
                    session,
                    Optional.empty(),
                    visibilityInResults ? Optional.of(account.presets().get(0).id()) : Optional.empty(),
                    Optional.empty(),
                    false,
                    List.of(),
                    com.naocraftlab.skins.core.model.AccountUiPreferences.defaults(account.accountId()),
                    visibilityInResults
                            ? Optional.of(account.presets().get(0).outerLayerVisibility())
                            : Optional.empty());
        }

        @Override
        public EditorSave saveEditor(EditorSaveRequest request) throws IOException {
            if (failEditorSave) {
                throw new IOException("characterized save failure");
            }
            UUID presetId = request.originalPresetId().orElseThrow();
            List<AppearancePreset> presets = new ArrayList<>(account.presets());
            for (int index = 0; index < presets.size(); index++) {
                AppearancePreset existing = presets.get(index);
                if (existing.id().equals(presetId)) {
                    presets.set(index, new AppearancePreset(
                            existing.id(),
                            request.name(),
                            request.skin(),
                            request.capeId().orElse(null),
                            request.outerLayerVisibility(),
                            existing.createdAt(),
                            existing.updatedAt().plusNanos(1)));
                    break;
                }
            }
            account = new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION,
                    account.accountId(),
                    account.skinAssets(),
                    account.personalSkins(),
                    presets,
                    account.updatedAt().plusNanos(1));
            if (!visibilityInResults || !account.presets().get(0).id().equals(presetId)) {
                return new EditorSave(account, presetId);
            }
            appearanceRevision++;
            AppearancePreset saved = account.presets().stream()
                    .filter(preset -> preset.id().equals(presetId))
                    .findFirst()
                    .orElseThrow();
            DurableAppearance appearance = new DurableAppearance(
                    account.accountId(),
                    appearanceRevision,
                    AppearanceSyncStatus.PENDING,
                    Optional.of(presetId),
                    Optional.of(AppliedAppearance.accountDefault(
                            account.accountId(), Optional.empty())),
                    Optional.of(saved.outerLayerVisibility()));
            return new EditorSave(account, presetId, Optional.of(appearance));
        }

        @Override
        public RemoteResult applyPreset(UUID presetId) {
            return remoteResult();
        }

        @Override
        public PresetUse usePreset(UUID presetId) {
            RemoteResult remote = remoteResult();
            AppearancePreset preset = account.presets().stream()
                    .filter(candidate -> candidate.id().equals(presetId))
                    .findFirst()
                    .orElseThrow();
            return new PresetUse(
                    account,
                    session,
                    presetId,
                    remote.outcome().optionalAppliedAppearance(),
                    Optional.of(remote),
                    false,
                    false,
                    visibilityInResults ? Optional.of(preset.outerLayerVisibility()) : Optional.empty());
        }

        @Override
        public RemoteResult retryCape(String capeId) {
            retryCapeCalls++;
            lastCapeId = capeId;
            return remoteResult();
        }

        @Override
        public void rememberActivePreset(UUID accountId, Optional<UUID> presetId) {
            rememberedActivePreset = presetId;
        }

        private RemoteResult remoteResult() {
            AppliedAppearance appearance = mutationResult == MutationResult.APPLIED
                            || mutationResult == MutationResult.PARTIAL
                    ? AppliedAppearance.accountDefault(TestFixtures.ACCOUNT_ID, Optional.empty())
                    : null;
            PresetApplicationOutcome outcome = new PresetApplicationOutcome(
                    mutationResult,
                    ApplicationPhase.COMPLETE,
                    session.profile(),
                    session.profile(),
                    appearance,
                    null,
                    recoveryActions,
                    mutationResult.name());
            return new RemoteResult(outcome, account, session, Optional.empty());
        }

        @Override
        public AccountState importSkin(String name, SkinVariant variant, byte[] normalizedPng) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountState renameSkin(UUID skinId, String newName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountState changeSkinVariant(UUID skinId, SkinVariant variant) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountState duplicateSkin(UUID skinId, String newName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountState deleteSkin(UUID skinId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InitialData resetLibrary() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountState duplicatePreset(UUID presetId, String newName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PresetDelete deletePreset(UUID presetId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RemoteResult restorePreviousAppearance(PresetApplicationOutcome outcome) {
            restoreCalls++;
            return remoteResult();
        }

        @Override
        public byte[] loadSkinPreview(UUID skinId) {
            skinPreviewCalls.incrementAndGet();
            if (failNextSkinPreview) {
                failNextSkinPreview = false;
                throw new IllegalStateException("characterized preview failure");
            }
            return new byte[] {1, 2, 3};
        }

        @Override
        public Optional<byte[]> loadCapePreview(String capeId) {
            capePreviewCalls.incrementAndGet();
            if (failNextCapePreview) {
                failNextCapePreview = false;
                throw new IllegalStateException("characterized cape preview failure");
            }
            return Optional.of(new byte[] {4, 5, 6});
        }

        @Override
        public InitialData retrySession() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean rateLimited() {
            return false;
        }

        @Override
        public GameSessionTokenSource.SessionIdentity sessionIdentity() {
            return session.sessionIdentity();
        }
    }
}

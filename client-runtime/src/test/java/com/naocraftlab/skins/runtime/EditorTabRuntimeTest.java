package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.EditorTab;
import com.naocraftlab.skins.core.model.OwnedCapeEntry;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.model.RemoteAssetState;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.SessionFailureContext;
import com.naocraftlab.skins.core.service.SessionStatus;
import com.naocraftlab.skins.core.service.SessionValidation;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EditorTabRuntimeTest {
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
            () -> java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
    private static final TextResolver TEXT = message -> message.key();

    @Test
    void modelKeyboardSelectionAndScrollRemainIndependentFromCapes() {
        Operations operations = new Operations(EditorTab.APPEARANCE, 20);
        ClientRuntime runtime = runtime(operations);
        runtime.initialize();
        runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
        runtime.view(1280, 240, 0, 0);
        assertTrue(runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, "editor.name"));
        assertEquals("editor.model_choice.classic", runtime.view(1280, 240, 0, 0).focusRequest().orElseThrow().widgetId());
        assertTrue(runtime.dispatchNavigation(ViewSpec.NavigationCommand.RIGHT, "editor.model_choice.classic"));
        assertEquals(SkinVariant.CLASSIC, runtime.snapshot().editor().orElseThrow().variant());
        assertTrue(runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, "editor.model_choice.slim"));
        assertEquals(SkinVariant.SLIM, runtime.snapshot().editor().orElseThrow().variant());
        runtime.dispatchWidget("editor.model_choice.slim");
        assertEquals(SkinVariant.SLIM, runtime.snapshot().editor().orElseThrow().variant());
        runtime.nativeScrollPositionChanged("editor.models", 40.0);
        runtime.dispatchWidget("editor.tab.cape");
        assertTrue(runtime.view(1280, 240, 0, 0).widget("editor.model_choice.slim").isEmpty());
        runtime.dispatchWidget("editor.model_choice.classic");
        assertEquals(SkinVariant.SLIM, runtime.snapshot().editor().orElseThrow().variant());
        runtime.nativeScrollPositionChanged("editor.capes", 12.0);
        runtime.dispatchWidget("editor.tab.appearance");
        ViewSpec view = runtime.view(1280, 240, 0, 0);
        assertEquals(40.0, view.scrollSurface("editor.models").orElseThrow().offsetPixels());
        assertEquals(SkinVariant.SLIM, runtime.snapshot().editor().orElseThrow().variant());
        runtime.pointerScrolled(view.scrollSurface("editor.models").orElseThrow().viewport().x() + 2,
                100, 0, -1);
        assertTrue(runtime.view(1280, 240, 0, 0).scrollSurface("editor.models").orElseThrow().offsetPixels() > 40);
        runtime.dispatchWidget("editor.cancel");
        assertEquals(0, operations.saveCalls);
    }

    @Test
    void appearanceScrollMovesNameAndKeyboardFocusRestoresItWithoutLosingDraft() {
        Operations operations = new Operations(EditorTab.APPEARANCE, 0);
        ClientRuntime runtime = runtime(operations);
        runtime.initialize();
        runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
        runtime.dispatchText("editor.name", "Preserved draft");
        ViewSpec initial = runtime.view(1280, 240, 0, 0);
        runtime.pointerScrolled(initial.widget("editor.name").orElseThrow().bounds().x() + 3, 60, 0, -1);
        ViewSpec scrolled = runtime.view(1280, 240, 0, 0);
        assertTrue(scrolled.scrollSurface("editor.models").orElseThrow().offsetPixels() > 0);
        assertTrue(scrolled.widget("editor.name").orElseThrow().bounds().y() < 52);
        runtime.nativeScrollPositionChanged("editor.models", 150.0);
        runtime.view(1280, 240, 0, 0);
        assertTrue(runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_BACKWARD, "editor.model_choice.classic"));
        ViewSpec focused = runtime.view(1280, 240, 0, 0);
        assertEquals("editor.name", focused.focusRequest().orElseThrow().widgetId());
        assertTrue(focused.widget("editor.name").orElseThrow().bounds().y()
                >= focused.scrollSurface("editor.models").orElseThrow().viewport().y());
        assertEquals("Preserved draft", focused.widget("editor.name").orElseThrow().value().orElseThrow());
        ViewSpec tall = runtime.view(854, 1000, 0, 0);
        assertTrue(tall.scrollbar().isEmpty());
        assertEquals(52, tall.widget("editor.name").orElseThrow().bounds().y());
    }

    @Test
    void reopeningAfterCancelRestoresTheSavedTabButNotTheDraft() {
        Operations operations = new Operations(EditorTab.APPEARANCE, 0);
        ClientRuntime runtime = runtime(operations);
        runtime.initialize();

        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        runtime.dispatchText("editor.name", "Cancelled draft");
        runtime.dispatchWidget("editor.tab.cape");
        assertEquals(EditorTab.CAPE, runtime.snapshot().editor().orElseThrow().selectedEditorTab());

        runtime.dispatchWidget("editor.cancel");
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");

        PresetEditorModel reopened = runtime.snapshot().editor().orElseThrow();
        assertEquals(EditorTab.CAPE, reopened.selectedEditorTab());
        assertEquals("Preset 1", reopened.name());
        assertEquals(0, operations.saveCalls);
        assertEquals(EditorTab.CAPE, operations.preferences.selectedEditorTab());
    }

    @Test
    void newDraftStartsOnAppearanceAndFocusesNameOnlyOnce() {
        Operations operations = new Operations(EditorTab.CAPE, 0);
        ClientRuntime runtime = runtime(operations);
        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".duplicate");

        assertTrue(runtime.snapshot().editor().orElseThrow().originalPresetId().isEmpty());
        assertEquals(EditorTab.APPEARANCE,
                runtime.snapshot().editor().orElseThrow().selectedEditorTab());
        ViewSpec view = runtime.view(854, 480, 0, 0);
        ViewSpec.FocusRequest request = view.focusRequest().orElseThrow();
        assertEquals("editor.name", request.widgetId());
        String name = view.widget("editor.name").orElseThrow().value().orElseThrow();
        assertTrue(ViewHostPolicy.shouldSelectAllOnFocusAcquire(view, "editor.name",
                ViewHostPolicy.FocusCause.PROGRAMMATIC, false, true, name));
        assertFalse(ViewHostPolicy.shouldSelectAllOnFocusAcquire(view, "editor.name",
                ViewHostPolicy.FocusCause.POINTER, true, true, name));
        runtime.acknowledgeFocusApplied(view.screenId(), request);
        assertTrue(runtime.view(854, 480, 0, 0).focusRequest().isEmpty());
        runtime.dispatchText("editor.name", "New draft");
        assertTrue(runtime.view(854, 480, 0, 0).focusRequest().isEmpty());
        assertEquals(EditorTab.CAPE, operations.preferences.selectedEditorTab());

        runtime.dispatchWidget("editor.cancel");
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        assertEquals(EditorTab.CAPE,
                runtime.snapshot().editor().orElseThrow().selectedEditorTab());
        assertTrue(runtime.view(854, 480, 0, 0).focusRequest().isEmpty());
    }

    @Test
    void switchingTabsKeepsDirtyDraftAndDoesNotSaveApplyOrLoadPreviews() {
        Operations operations = new Operations(EditorTab.APPEARANCE, 0);
        ClientRuntime runtime = runtime(operations);
        runtime.initialize();

        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        int skinPreviewCallsBeforeSwitch = operations.skinPreviewCalls;
        int capePreviewCallsBeforeSwitch = operations.capePreviewCalls;
        runtime.dispatchText("editor.name", "Unsaved name");
        runtime.dispatchWidget("editor.tab.cape");

        PresetEditorModel switched = runtime.snapshot().editor().orElseThrow();
        assertEquals(EditorTab.CAPE, switched.selectedEditorTab());
        assertEquals("Unsaved name", switched.name());
        assertEquals(0, operations.saveCalls);
        assertEquals(0, operations.applyCalls);
        assertEquals(skinPreviewCallsBeforeSwitch, operations.skinPreviewCalls);
        assertEquals(capePreviewCallsBeforeSwitch, operations.capePreviewCalls);
    }

    @Test
    void capeScrollSurvivesAnAppearanceTabRoundTrip() {
        Operations operations = new Operations(EditorTab.CAPE, 6);
        ClientRuntime runtime = runtime(operations);
        runtime.initialize();
        runtime.view(320, 240, 0, 0);

        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        runtime.nativeScrollPositionChanged("editor.capes", 16.0);
        double before = runtime.view(320, 240, 0, 0)
                .scrollSurface("editor.capes").orElseThrow().offsetPixels();
        assertTrue(before > 0.0);

        runtime.dispatchWidget("editor.tab.appearance");
        runtime.nativeScrollPositionChanged("editor.capes", 0.0);
        runtime.dispatchWidget("editor.tab.cape");

        assertEquals(before, runtime.view(320, 240, 0, 0)
                .scrollSurface("editor.capes").orElseThrow().offsetPixels());
    }

    @Test
    void preferenceWriteFailureLeavesTheSelectedTabAndDraftUsable() {
        Operations operations = new Operations(EditorTab.APPEARANCE, 0);
        operations.failEditorTabWrite = true;
        ClientRuntime runtime = runtime(operations);
        runtime.initialize();

        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        runtime.dispatchText("editor.name", "Still editable");
        runtime.dispatchWidget("editor.tab.cape");

        PresetEditorModel retained = runtime.snapshot().editor().orElseThrow();
        assertEquals(EditorTab.CAPE, retained.selectedEditorTab());
        assertEquals("Still editable", retained.name());
        assertFalse(retained.busy());
        assertEquals("nclskins.error.save", retained.status().orElseThrow().key());
        assertEquals(0, operations.saveCalls);
    }

    @Test
    void delayedPreferenceWritesPersistTheLatestTabAfterRapidSwitches() {
        Operations operations = new Operations(EditorTab.APPEARANCE, 0);
        DelayedExecutor worker = new DelayedExecutor();
        ClientRuntime runtime = runtime(operations, worker);
        runtime.initialize();
        worker.runAll();

        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        worker.runAll();
        runtime.dispatchWidget("editor.tab.cape");
        runtime.dispatchWidget("editor.tab.appearance");
        worker.runAll();

        assertEquals(EditorTab.APPEARANCE, operations.preferences.selectedEditorTab());
        assertEquals(EditorTab.APPEARANCE,
                runtime.snapshot().editor().orElseThrow().selectedEditorTab());
    }

    @Test
    void delayedFailureFromEarlierTabCannotMarkTheNewerTabDraft() {
        Operations operations = new Operations(EditorTab.APPEARANCE, 0);
        operations.failedEditorTabWrites = 1;
        DelayedExecutor worker = new DelayedExecutor();
        ClientRuntime runtime = runtime(operations, worker);
        runtime.initialize();
        worker.runAll();

        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        worker.runAll();
        runtime.dispatchWidget("editor.tab.cape");
        runtime.dispatchWidget("editor.tab.appearance");
        worker.runAll();

        PresetEditorModel current = runtime.snapshot().editor().orElseThrow();
        assertEquals(EditorTab.APPEARANCE, current.selectedEditorTab());
        assertTrue(current.status().isEmpty());
    }

    @Test
    void delayedFailureFromClosedEditorCannotMarkTheReopenedDraft() {
        Operations operations = new Operations(EditorTab.APPEARANCE, 0);
        operations.failedEditorTabWrites = 1;
        DelayedExecutor worker = new DelayedExecutor();
        ClientRuntime runtime = runtime(operations, worker);
        runtime.initialize();
        worker.runAll();

        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        worker.runAll();
        runtime.dispatchWidget("editor.tab.cape");
        runtime.dispatchWidget("editor.cancel");
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        worker.runAll();

        assertTrue(runtime.snapshot().editor().orElseThrow().status().isEmpty());
    }

    private static ClientRuntime runtime(Operations operations) {
        return runtime(operations, Runnable::run);
    }

    private static ClientRuntime runtime(Operations operations, Executor worker) {
        return new ClientRuntime(
                operations,
                CLIENT,
                CANCELLED_PICKER,
                worker,
                worker,
                Runnable::run,
                TEXT,
                Optional.empty(),
                Optional.empty(),
                (delay, action) -> {
                    action.run();
                    return () -> {};
                },
                DiagnosticSinks.discarding());
    }

    private static final class Operations implements ClientOperations {
        private final AccountState account = TestFixtures.account(1);
        private final SessionValidation session;
        private AccountUiPreferences preferences;
        private final OwnedCapeInventory ownedCapes;
        private boolean failEditorTabWrite;
        private int saveCalls;
        private int applyCalls;
        private int skinPreviewCalls;
        private int capePreviewCalls;
        private int failedEditorTabWrites;

        private Operations(EditorTab tab, int capeCount) {
            session = capeCount == 0
                    ? TestFixtures.validSession()
                    : new SessionValidation(
                            SessionStatus.OFFLINE_OR_INVALID,
                            TestFixtures.validSession().sessionIdentity(),
                            null,
                            (SessionFailureContext) null,
                            "offline");
            preferences = AccountUiPreferences.defaults(account.accountId()).withSelectedEditorTab(tab);
            ownedCapes = new OwnedCapeInventory(
                    OwnedCapeInventory.CURRENT_SCHEMA_VERSION,
                    account.accountId(),
                    java.util.stream.IntStream.range(0, capeCount)
                            .mapToObj(index -> new OwnedCapeEntry(
                                    "cape-" + index,
                                    "Cape " + index,
                                    RemoteAssetState.ACTIVE,
                                    null))
                            .toList(),
                    Instant.EPOCH);
        }

        @Override
        public InitialData initialize() {
            return initial();
        }

        @Override
        public Optional<AccountUiPreferences> loadUiPreferences() {
            return Optional.of(preferences);
        }

        @Override
        public Optional<OwnedCapeInventory> ownedCapeInventory() {
            return Optional.of(ownedCapes);
        }

        @Override
        public void setSelectedEditorTab(UUID accountId, EditorTab tab) {
            if (failEditorTabWrite) {
                throw new IllegalStateException("storage unavailable");
            }
            if (failedEditorTabWrites > 0) {
                failedEditorTabWrites--;
                throw new IllegalStateException("storage unavailable");
            }
            assertEquals(account.accountId(), accountId);
            preferences = preferences.withSelectedEditorTab(tab);
        }

        @Override
        public AccountState importSkin(String name, SkinVariant variant, byte[] normalizedPng) {
            throw unavailable();
        }

        @Override
        public AccountState renameSkin(UUID skinId, String newName) {
            throw unavailable();
        }

        @Override
        public AccountState changeSkinVariant(UUID skinId, SkinVariant variant) {
            throw unavailable();
        }

        @Override
        public AccountState duplicateSkin(UUID skinId, String newName) {
            throw unavailable();
        }

        @Override
        public AccountState deleteSkin(UUID skinId) {
            throw unavailable();
        }

        @Override
        public InitialData resetLibrary() {
            throw unavailable();
        }

        @Override
        public EditorSave saveEditor(EditorSaveRequest request) {
            saveCalls++;
            throw unavailable();
        }

        @Override
        public PresetDelete deletePreset(UUID presetId) {
            throw unavailable();
        }

        @Override
        public RemoteResult applyPreset(UUID presetId) {
            applyCalls++;
            throw unavailable();
        }

        @Override
        public PresetUse usePreset(UUID presetId) {
            throw unavailable();
        }

        @Override
        public RemoteResult retryCape(String capeId) {
            throw unavailable();
        }

        @Override
        public RemoteResult restorePreviousAppearance(PresetApplicationOutcome outcome) {
            throw unavailable();
        }

        @Override
        public byte[] loadSkinPreview(UUID skinId) {
            skinPreviewCalls++;
            throw unavailable();
        }

        @Override
        public Optional<byte[]> loadCapePreview(String capeId) {
            capePreviewCalls++;
            throw unavailable();
        }

        @Override
        public InitialData retrySession() {
            return initial();
        }

        @Override
        public boolean rateLimited() {
            return false;
        }

        @Override
        public GameSessionTokenSource.SessionIdentity sessionIdentity() {
            return session.sessionIdentity();
        }

        private InitialData initial() {
            return new InitialData(
                    account,
                    session,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    false,
                    List.of(),
                    preferences,
                    Optional.empty(),
                    ownedCapes,
                    0,
                    com.naocraftlab.skins.core.model.AppearanceSyncStatus.LOCAL_ONLY);
        }

        private static UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException();
        }
    }

    private static final class DelayedExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable action) {
            tasks.add(action);
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove(0).run();
            }
        }
    }
}

package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger;
import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.OuterLayerVisibilityController;
import com.naocraftlab.skins.client.PersonalSkinCatalog;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.PreviewPreferences;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.ScreenDestination;
import com.naocraftlab.skins.client.ServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.client.SignedTextureVerifier;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinExtensionEnvironmentSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.api.PublicSkinImportException;
import com.naocraftlab.skins.core.compatibility.SkinConsumer;
import com.naocraftlab.skins.core.compatibility.SkinConsumerState;
import com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.config.ClientConfiguration;
import com.naocraftlab.skins.core.importing.ExternalImportProbe;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.EditorTab;
import com.naocraftlab.skins.core.model.LocalCapeReference;
import com.naocraftlab.skins.core.model.MutationResult;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.NormalizedSkin;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderCape;
import com.naocraftlab.skins.core.provider.ProviderObservation;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.LibraryOperationException;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.RecoveryAction;
import com.naocraftlab.skins.core.service.SessionValidation;
import com.naocraftlab.skins.diagnostics.DiagnosticDetails;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class ClientRuntime implements AutoCloseable {
    private ScreenOperationTicket beginScreenOperation() {
        state.busy = true;
        return new ScreenOperationTicket(++state.generation);
    }

    private void editorSaved(EditorFlow.SaveCompletion completion) {
        var saved = completion.saved();
        UUID previousActivePresetId = state.activePresetId;
        state.account = saved.account();
        AppearancePreset preset = findPreset(saved.presetId());
        galleryFlow.acceptSavedPreset(preset, saved.presetId());
        state.selectedCapeId = preset == null ? null : preset.capeId();
        catalogImportFlow.leaveForSavedPreset();
        state.status = completion.hadOfflineCape()
                && preset != null && preset.offlineCape() == null
                ? UiMessage.info("nclskins.capes.deleted_reference") : UiMessage.success("nclskins.status.saved");
        saved.reappliedAppearance().ifPresent(appearance -> {
            state.activePresetId = appearance.activePresetId().orElse(null);
            state.providers = appearance.providers();
            state.intentRevision = appearance.intentRevision();
            state.syncStatus = appearance.syncStatus();
            CompletableFuture<AppearanceRefreshCoordinator.Result> localRebind =
                    refreshLocalAppearance(appearance.localAppearance());
            appearance.outerLayerVisibility()
                    .ifPresent(this::applyDurableOuterLayerVisibility);
            if (automaticCheckpointEligible(appearance.syncStatus())) {
                reconcileAfterLocalRebind(
                        localRebind,
                        appearance.reconciliationKey(),
                        Trigger.LOCAL_INTENT);
            }
        });
        centerGalleryIfActiveChanged(previousActivePresetId);
        if (addSourceRoot()) closeScreenOnClient();
        else returnFromEditor();
    }

    private void capeImported(CapeImportResult result) {
        result.account().ifPresent(account -> state.account = account);
        var entries = new java.util.ArrayList<>(state.account.personalCapes());
        var entry = result.entry();
        if (result.account().isEmpty() && entries.stream().noneMatch(value ->
                value.texture().entryId().equals(entry.texture().entryId()))) {
            entries.add(entry);
            state.account = state.account.withPersonalCapes(entries);
        }
    }

    private void presetUsed(LibraryEditorPort.PresetUse use) {
        state.account = use.account();
        state.session = use.session();
        state.activePresetId = use.activePresetId();
        state.providers = use.providers();
        state.intentRevision = use.intentRevision();
        state.syncStatus = use.syncStatus();
        CompletableFuture<AppearanceRefreshCoordinator.Result> localRebind =
                refreshLocalAppearance(use.localAppearance());
        use.outerLayerVisibility().ifPresent(this::applyDurableOuterLayerVisibility);
        if (use.remoteResult().isPresent()) {
            acceptRemoteResult(
                    use.remoteResult().orElseThrow(),
                    use.activePresetId());
        } else {
            state.remoteProfile = use.session().profile();
            state.rateLimited = state.providers.minecraftEnabled() && operations.rateLimited();
            state.status = UiMessage.info("nclskins.status.local_only");
            if (automaticCheckpointEligible(use.syncStatus()) || use.syncStatus() == AppearanceSyncStatus.UNKNOWN || use.syncStatus() == AppearanceSyncStatus.PARTIAL) {
                reconcileAfterLocalRebind(
                        localRebind,
                        use.syncStatus() == AppearanceSyncStatus.UNKNOWN || use.syncStatus() == AppearanceSyncStatus.PARTIAL
                                ? Trigger.EXPLICIT_RETRY : Trigger.LOCAL_INTENT);
            }
        }
    }

    private void presetDeletionCompleted(UUID presetId, LibraryEditorPort.PresetDelete deletion) {
        state.account = deletion.account();
        if (state.account.presets().stream().anyMatch(preset -> preset.id().equals(presetId))) return;
        deletion.appearance().ifPresent(appearance -> {
            state.activePresetId = appearance.activePresetId().orElse(null);
            state.providers = appearance.providers();
            state.intentRevision = appearance.intentRevision();
            state.syncStatus = appearance.syncStatus();
            CompletableFuture<AppearanceRefreshCoordinator.Result> localRebind =
                    refreshLocalAppearance(appearance.localAppearance());
            appearance.outerLayerVisibility()
                    .ifPresent(this::applyDurableOuterLayerVisibility);
            if (automaticCheckpointEligible(appearance.syncStatus())) {
                reconcileAfterLocalRebind(
                        localRebind,
                        Trigger.LOCAL_INTENT);
            }
        });
    }

    private void editProvider(ProviderFlow.EditRequest request) {
        state.providersOpen = false;
        clearRuntimeFocus("providers");
        if (request.component() == AppearanceProviders.Component.CAPE) {
            openEditor(state.activePresetId);
            state.editorReturnsToProviders = editorFlow.editor() != null;
            editorFlow.inspectProviderCape(request.provider());
        } else {
            state.galleryReturnsToProviders = true;
        }
    }

    private static final double WHEEL_SCROLL_PIXELS = 32.0;
    private static final int SESSION_RETRY_FEEDBACK_TICKS = 6;

    private final GalleryFlow galleryFlow = new GalleryFlow(new GalleryFlow.Context() {
        public void acceptDraft(EditorDraftTransfer transfer) { catalogImportFlow.leaveForDuplicate(); editorFlow.acceptDraft(transfer); galleryFlow.acceptDraftSelection(transfer.selectedPresetId()); }
        public ClientSnapshot snapshot() { return snapshot; }
        public boolean busy() { return state.busy; }
        public AccountState account() { return state.account; }
        public TextResolver textResolver() { return textResolver; }
        public UUID activePresetId() { return state.activePresetId; }
        public int viewportHeight() { return viewportHeight; }
        public PreviewRenderer.CapeMode preferredCapeMode() { return preferredCapeMode; }
        public LibraryEditorPort libraryEditorPort() { return operations; }
        public ClientSessionView clientSessionView() { return operations; }
        public AppearanceSyncStatus syncStatus() { return state.syncStatus; }
        public int viewportWidth() { return viewportWidth; }
        public boolean galleryReturnsToProviders() { return state.galleryReturnsToProviders; }
        public void closeScreen() { ClientRuntime.this.closeScreen(); }
        public void armRateLimitRecovery() { ClientRuntime.this.armRateLimitRecovery(); }
        public void requestRuntimeFocus(String screenId, String widgetId) { ClientRuntime.this.requestRuntimeFocus(screenId, widgetId); }
        public void diagnose(DiagnosticEvent event, Throwable failure) { ClientRuntime.this.diagnose(event, failure); }
        public void openAddSource() { ClientRuntime.this.openAddSource(); }
        public void openAddSource(AddSourceTab override) { ClientRuntime.this.openAddSource(override); }
        public Optional<com.naocraftlab.skins.core.model.RemoteProfile> editorProfile() { return ClientRuntime.this.editorProfile(); }
        public List<com.naocraftlab.skins.core.model.OwnedCapeEntry> editorOwnedCapes() { return ClientRuntime.this.editorOwnedCapes(); }
        public void openEditor(UUID presetId) { ClientRuntime.this.openEditor(presetId); }
        public void closeToProviders() { ClientRuntime.this.closeToProviders(); }
        public void retrySelectedCape() { ClientRuntime.this.retrySelectedCape(); }
        public void retrySession() { ClientRuntime.this.retrySession(); }
        public <T> void submit(
            UiMessage progress, ThrowingSupplier<T> operation, Consumer<T> completion) { ClientRuntime.this.submit(progress, operation, completion); }
        public <T> void submitRemote(
            UiMessage progress,
            ThrowingSupplier<T> operation,
            Consumer<T> completion,
            Function<T, Optional<PresetApplicationOutcome>> completedOutcome) { ClientRuntime.this.submitRemote(progress, operation, completion, completedOutcome); }
        public <T> void submit(
            UiMessage progress,
            ThrowingSupplier<T> operation,
            Consumer<T> completion,
            Consumer<Throwable> failureCompletion) { ClientRuntime.this.submit(progress, operation, completion, failureCompletion); }
        public <T> void submit(
            UiMessage progress,
            ThrowingSupplier<T> operation,
            Consumer<T> completion,
            Consumer<Throwable> failureCompletion,
            Function<T, Optional<PresetApplicationOutcome>> completedOutcome) { ClientRuntime.this.submit(progress, operation, completion, failureCompletion, completedOutcome); }
        public void publish() { ClientRuntime.this.publish(); }
        public SkinVariant currentPlayerVariant() { return ClientRuntime.this.currentPlayerVariant(); }
        public SkinVariant preferredSkinVariant() { return ClientRuntime.this.preferredSkinVariant(); }
        public AppearancePreset findPreset(UUID id) { return ClientRuntime.this.findPreset(id); }
        public void showFeedback(UiMessage message) { state.status = message; }
        public void presetUsed(LibraryEditorPort.PresetUse use) { ClientRuntime.this.presetUsed(use); }
        public void presetDeletionCompleted(UUID presetId, LibraryEditorPort.PresetDelete deletion) { ClientRuntime.this.presetDeletionCompleted(presetId, deletion); }
    });
    private final EditorFlow editorFlow = new EditorFlow(new EditorFlow.Context() {
        public AccountState account() { return state.account; }
        public AccountUiPreferences uiPreferences() { return state.uiPreferences; }
        public UiPreferencesPort uiPreferencesPort() { return operations; }
        public LibraryEditorPort libraryEditorPort() { return operations; }
        public CatalogRead catalogRead() { return operations; }
        public CatalogMaterialization catalogMaterialization() { return operations; }
        public Executor worker() { return worker; }
        public ClientExecutor clientExecutor() { return clientExecutor; }
        public AppearanceProviders providers() { return state.providers; }
        public RemoteProfile remoteProfile() { return state.remoteProfile; }
        public OwnedCapeInventory ownedCapes() { return state.ownedCapes; }
        public UUID activePresetId() { return state.activePresetId; }
        public TextResolver textResolver() { return textResolver; }
        public int viewportHeight() { return viewportHeight; }
        public PreviewRenderer.CapeMode preferredCapeMode() { return preferredCapeMode; }
        public int viewportWidth() { return viewportWidth; }
        public ViewChromeMetrics viewChromeMetrics() { return viewChromeMetrics; }
        public FilePicker filePicker() { return filePicker; }
        public boolean disposed() { return disposed; }
        public ClientSnapshot snapshot() { return snapshot; }
        public ViewSpec view(int width, int height, int mouseX, int mouseY) { return ClientRuntime.this.view(width, height, mouseX, mouseY); }
        public ViewSpec view(
            int width,
            int height,
            int mouseX,
            int mouseY,
            ViewChromeMetrics chromeMetrics) { return ClientRuntime.this.view(width, height, mouseX, mouseY, chromeMetrics); }
        public ViewSpec withRuntimeFocus(ViewSpec view) { return ClientRuntime.this.withRuntimeFocus(view); }
        public void requestRuntimeFocus(String screenId, String widgetId) { ClientRuntime.this.requestRuntimeFocus(screenId, widgetId); }
        public void clearRuntimeFocus(String screenId) { ClientRuntime.this.clearRuntimeFocus(screenId); }
        public void applyNavigationScroll(ViewSpec.NavigationNode node, double offsetPixels) { ClientRuntime.this.applyNavigationScroll(node, offsetPixels); }
        public CompletableFuture<Optional<byte[]>> loadSkinPreview(SkinReference reference) { return ClientRuntime.this.loadSkinPreview(reference); }
        public CompletableFuture<Optional<byte[]>> loadSkinPreview(ViewSpec.Preview preview) { return ClientRuntime.this.loadSkinPreview(preview); }
        public SkinFeatureEvidence analyzeImportedSkinFeatureEvidence(byte[] pngBytes)
            throws PngValidationException { return ClientRuntime.analyzeImportedSkinFeatureEvidence(pngBytes); }
        public SkinFeatureEvidence analyzeStoredSkinFeatureEvidence(byte[] pngBytes)
            throws PngValidationException { return ClientRuntime.analyzeStoredSkinFeatureEvidence(pngBytes); }
        public void diagnose(DiagnosticEvent event, Throwable failure) { ClientRuntime.this.diagnose(event, failure); }
        public void retainKeyboardFocus(
            InteractionOrigin origin, String screenId, String widgetId) { ClientRuntime.this.retainKeyboardFocus(origin, screenId, widgetId); }
        public void returnFromEditor() { ClientRuntime.this.returnFromEditor(); }
        public void installWarmedCapePreviews(UUID accountId, boolean replaceResources) { ClientRuntime.this.installWarmedCapePreviews(accountId, replaceResources); }
        public void rememberPreferredSkinVariant(SkinVariant variant) { ClientRuntime.this.rememberPreferredSkinVariant(variant); }
        public <T> void submit(
            UiMessage progress, ThrowingSupplier<T> operation, Consumer<T> completion) { ClientRuntime.this.submit(progress, operation, completion); }
        public <T> void submit(
            UiMessage progress,
            ThrowingSupplier<T> operation,
            Consumer<T> completion,
            Consumer<Throwable> failureCompletion) { ClientRuntime.this.submit(progress, operation, completion, failureCompletion); }
        public <T> void submit(
            UiMessage progress,
            ThrowingSupplier<T> operation,
            Consumer<T> completion,
            Consumer<Throwable> failureCompletion,
            Function<T, Optional<PresetApplicationOutcome>> completedOutcome) { ClientRuntime.this.submit(progress, operation, completion, failureCompletion, completedOutcome); }
        public boolean current(ScreenOperationTicket ticket) { return ClientRuntime.this.current(ticket.generation()); }
        public void publish() { ClientRuntime.this.publish(); }
        public void onClient(Runnable action) { ClientRuntime.this.onClient(action); }
        public SkinVariant preferredSkinVariant() { return ClientRuntime.this.preferredSkinVariant(); }
        public UiMessage fileImportFailure(Throwable failure) { return ClientRuntime.fileImportFailure(failure); }
        public NormalizedSkin readPng(Path path) { return ClientRuntime.readPng(path); }
        public Throwable unwrap(Throwable failure) { return ClientRuntime.unwrap(failure); }
        public ScreenOperationTicket beginScreenOperation() { return ClientRuntime.this.beginScreenOperation(); }
        public ScreenOperationTicket captureScreenOperation() { return new ScreenOperationTicket(state.generation); }
        public void finishScreenOperation(ScreenOperationTicket ticket) { if (ClientRuntime.this.current(ticket.generation())) state.busy = false; }
        public void showFeedback(UiMessage message) { state.status = message; }
        public void editorTabSelected(EditorTab tab) { state.uiPreferences = (state.uiPreferences == null ? AccountUiPreferences.defaults(state.account.accountId()) : state.uiPreferences).withSelectedEditorTab(tab); }
        public void capeDisclosureChanged(Set<String> collapsed) { state.uiPreferences = state.uiPreferences.withCollapsedCapeCollections(collapsed); }
        public void editorPreviewModeChanged(PreviewRenderer.CapeMode mode) { preferredCapeMode = mode; if (mode != PreviewRenderer.CapeMode.OFF) { PreviewPreferences.setCapeMode(mode); providerFlow.acceptPreviewMode(mode); } }
        public void editorSaved(EditorFlow.SaveCompletion completion) { ClientRuntime.this.editorSaved(completion); }
        public void capeRenamed(AccountState account) { state.account = account; }
        public void capeDeleted(LibraryEditorPort.CapeDeletion result) { state.account = result.account(); acceptProviderChange(result.appearance()); }
        public void capeCatalogLoaded(CatalogRead.CapeEditorData data) { state.account = data.account(); installWarmedCapePreviews(data.account().accountId(), true); }
        public void capeImported(CapeImportResult result) { ClientRuntime.this.capeImported(result); }
        public SkinFeatureEvidence cachedAssetEvidence(UUID assetId) { return state.assetEvidence.get(assetId); }
        public boolean acceptAssetEvidence(UUID assetId, SkinFeatureEvidence evidence) { return !evidence.equals(state.assetEvidence.put(assetId, evidence)); }
    });
    private final CatalogImportFlow catalogImportFlow = new CatalogImportFlow(new CatalogImportFlow.Context() {
        public void acceptDraft(EditorDraftTransfer transfer) { editorFlow.acceptDraft(transfer); galleryFlow.acceptDraftSelection(transfer.selectedPresetId()); }
        public boolean busy() { return state.busy; }
        public UiMessage status() { return state.status; }
        public AccountUiPreferences uiPreferences() { return state.uiPreferences; }
        public AccountState account() { return state.account; }
        public UiPreferencesPort uiPreferencesPort() { return operations; }
        public CatalogMaterialization catalogMaterialization() { return operations; }
        public ImportOperations importOperations() { return operations; }
        public LibraryEditorPort libraryEditorPort() { return operations; }
        public Executor worker() { return worker; }
        public boolean disposed() { return disposed; }
        public String pendingPresetName() { return editorFlow.pendingPresetName(); }
        public TextResolver textResolver() { return textResolver; }
        public PresetEditorModel editor() { return editorFlow.editor(); }
        public int viewportHeight() { return viewportHeight; }
        public PreviewRenderer.CapeMode preferredCapeMode() { return preferredCapeMode; }
        public FilePicker filePicker() { return filePicker; }
        public int viewportWidth() { return viewportWidth; }
        public PreviewAssetLoader previewAssets() { return previewAssets; }
        public ViewChromeMetrics viewChromeMetrics() { return viewChromeMetrics; }
        public ClientSnapshot snapshot() { return snapshot; }
        public boolean addSourceRoot() { return ClientRuntime.this.addSourceRoot(); }
        public void closeScreenOnClient() { ClientRuntime.this.closeScreenOnClient(); }
        public ViewSpec withRuntimeFocus(ViewSpec view) { return ClientRuntime.this.withRuntimeFocus(view); }
        public void requestRuntimeFocus(String screenId, String widgetId) { ClientRuntime.this.requestRuntimeFocus(screenId, widgetId); }
        public void clearRuntimeFocus(String screenId) { ClientRuntime.this.clearRuntimeFocus(screenId); }
        public void diagnose(DiagnosticEvent event, Throwable failure) { ClientRuntime.this.diagnose(event, failure); }
        public void retainKeyboardFocus(
            InteractionOrigin origin, String screenId, String widgetId) { ClientRuntime.this.retainKeyboardFocus(origin, screenId, widgetId); }
        public void openExternalImport(ExternalImportModel.Category category) { ClientRuntime.this.openExternalImport(category); }
        public void persistUiPreference(ThrowingSupplier<Void> operation) { ClientRuntime.this.persistUiPreference(operation); }
        public Optional<com.naocraftlab.skins.core.model.RemoteProfile> editorProfile() { return ClientRuntime.this.editorProfile(); }
        public List<com.naocraftlab.skins.core.model.OwnedCapeEntry> editorOwnedCapes() { return ClientRuntime.this.editorOwnedCapes(); }
        public PresetEditorModel createEditor(UUID presetId) { return ClientRuntime.this.createEditor(presetId); }
        public PresetEditorModel applyPendingPresetName(PresetEditorModel editor) { return ClientRuntime.this.applyPendingPresetName(editor); }
        public void rememberPreferredSkinVariant(SkinVariant variant) { ClientRuntime.this.rememberPreferredSkinVariant(variant); }
        public <T> void submit(
            UiMessage progress, ThrowingSupplier<T> operation, Consumer<T> completion) { ClientRuntime.this.submit(progress, operation, completion); }
        public <T> void submit(
            UiMessage progress,
            ThrowingSupplier<T> operation,
            Consumer<T> completion,
            Consumer<Throwable> failureCompletion) { ClientRuntime.this.submit(progress, operation, completion, failureCompletion); }
        public <T> void submit(
            UiMessage progress,
            ThrowingSupplier<T> operation,
            Consumer<T> completion,
            Consumer<Throwable> failureCompletion,
            Function<T, Optional<PresetApplicationOutcome>> completedOutcome) { ClientRuntime.this.submit(progress, operation, completion, failureCompletion, completedOutcome); }
        public boolean current(ScreenOperationTicket ticket) { return ClientRuntime.this.current(ticket.generation()); }
        public void publish() { ClientRuntime.this.publish(); }
        public void onClient(Runnable action) { ClientRuntime.this.onClient(action); }
        public UiMessage fileImportFailure(Throwable failure) { return ClientRuntime.fileImportFailure(failure); }
        public NormalizedSkin readPng(Path path) { return ClientRuntime.readPng(path); }
        public String publicImportFailureKey(Throwable failure, boolean player) { return ClientRuntime.publicImportFailureKey(failure, player); }
        public Throwable unwrap(Throwable failure) { return ClientRuntime.unwrap(failure); }
        public Optional<PersonalCatalogAction> personalCatalogAction(
            String widgetId, String prefix) { return ClientRuntime.personalCatalogAction(widgetId, prefix); }
        public ScreenOperationTicket beginScreenOperation() { return ClientRuntime.this.beginScreenOperation(); }
        public ScreenOperationTicket captureScreenOperation() { return new ScreenOperationTicket(state.generation); }
        public void finishScreenOperation(ScreenOperationTicket ticket) { if (ClientRuntime.this.current(ticket.generation())) state.busy = false; }
        public void cancelScreenOperation() { state.generation++; state.busy = false; }
        public void showFeedback(UiMessage message) { state.status = message; }
        public void addSourceTabSelected(AddSourceTab tab) { state.uiPreferences = state.uiPreferences.withSelectedAddSourceTab(tab); }
        public void catalogCollectionDisclosureChanged(String id, boolean collapsed) { state.uiPreferences = state.uiPreferences.withCollectionCollapsed(id, collapsed); }
        public void catalogDisclosureChanged(Set<String> collapsed) { if (state.uiPreferences != null && collapsed != null) state.uiPreferences = state.uiPreferences.withCollapsedCollectionIds(collapsed); }
        public void externalImportCompleted(ImportOperations.ExternalImportResult result) { state.account = result.account(); galleryFlow.acceptExternalImport(); previewAssets.invalidateCatalogPreviews(); }
        public void personalSkinRenamed(AccountState account) { state.account = account; }
        public void personalSkinDeleted(AccountState account) { state.account = account; }
    });
    private final ProviderFlow providerFlow = new ProviderFlow(new ProviderFlow.Context() {
        public AccountState account() { return state.account; }
        public ClientSnapshot.Lifecycle lifecycle() { return state.lifecycle; }
        public boolean providersOpen() { return state.providersOpen; }
        public AppearanceProviders providers() { return state.providers; }
        public PresetEditorModel editor() { return editorFlow.editor(); }
        public AccountUiPreferences uiPreferences() { return state.uiPreferences; }
        public UiPreferencesPort uiPreferencesPort() { return operations; }
        public ProviderOperations providerOperations() { return operations; }
        public ClientSessionView clientSessionView() { return operations; }
        public int viewportWidth() { return viewportWidth; }
        public int viewportHeight() { return viewportHeight; }
        public PreviewRenderer.CapeMode preferredCapeMode() { return preferredCapeMode; }
        public ScreenDestination rootDestination() { return state.rootDestination; }
        public boolean busy() { return state.busy; }
        public CapeObservationPort capeObservations() { return capeObservations; }
        public boolean disposed() { return disposed; }
        public long intentRevision() { return state.intentRevision; }
        public Executor worker() { return worker; }
        public Optional<ServerAppearanceReadinessCoordinator> serverAppearanceReadiness() { return serverAppearanceReadiness; }
        public Optional<OuterLayerVisibilityController> outerLayerVisibilityController() { return outerLayerVisibilityController; }
        public ClientSnapshot snapshot() { return snapshot; }
        public TextResolver textResolver() { return textResolver; }
        public void closeScreenOnClient() { ClientRuntime.this.closeScreenOnClient(); }
        public ViewSpec withRuntimeFocus(ViewSpec view) { return ClientRuntime.this.withRuntimeFocus(view); }
        public void requestRuntimeFocus(String screenId, String widgetId) { ClientRuntime.this.requestRuntimeFocus(screenId, widgetId); }
        public void diagnose(DiagnosticEvent event, Throwable failure) { ClientRuntime.this.diagnose(event, failure); }
        public boolean currentSessionOwns(ClientOperations.InitialData data) { return ClientRuntime.this.currentSessionOwns(data); }
        public void persistUiPreference(ThrowingSupplier<Void> operation) { ClientRuntime.this.persistUiPreference(operation); }
        public void reconcileAfterLocalRebind(
            CompletableFuture<AppearanceRefreshCoordinator.Result> localRebind,
            Trigger trigger) { ClientRuntime.this.reconcileAfterLocalRebind(localRebind, trigger); }
        public boolean currentSessionOwns(ClientOperations.DurableAppearance appearance) { return ClientRuntime.this.currentSessionOwns(appearance); }
        public void reconcileAfterLocalRebind(
            CompletableFuture<AppearanceRefreshCoordinator.Result> localRebind,
            ClientOperations.ReconciliationKey key,
            Trigger trigger) { ClientRuntime.this.reconcileAfterLocalRebind(localRebind, key, trigger); }
        public <T> void submit(
            UiMessage progress, ThrowingSupplier<T> operation, Consumer<T> completion) { ClientRuntime.this.submit(progress, operation, completion); }
        public <T> void submit(
            UiMessage progress,
            ThrowingSupplier<T> operation,
            Consumer<T> completion,
            Consumer<Throwable> failureCompletion) { ClientRuntime.this.submit(progress, operation, completion, failureCompletion); }
        public <T> void submit(
            UiMessage progress,
            ThrowingSupplier<T> operation,
            Consumer<T> completion,
            Consumer<Throwable> failureCompletion,
            Function<T, Optional<PresetApplicationOutcome>> completedOutcome) { ClientRuntime.this.submit(progress, operation, completion, failureCompletion, completedOutcome); }
        public void publish() { ClientRuntime.this.publish(); }
        public void onClient(Runnable action) { ClientRuntime.this.onClient(action); }
        public SkinVariant currentPlayerVariant() { return ClientRuntime.this.currentPlayerVariant(); }
        public UiMessage operationFailure(Throwable failure) { return ClientRuntime.operationFailure(failure); }
        public void showFeedback(UiMessage message) { state.status = message; }
        public void providersTabSelected(AppearanceProviders.Component tab) { state.uiPreferences = state.uiPreferences.withSelectedProvidersTab(tab); sessionActivityBaselineGeneration = -1; }
        public void providerPreviewModeChanged(PreviewRenderer.CapeMode mode) { preferredCapeMode = mode; PreviewPreferences.setCapeMode(mode); editorFlow.acceptPreviewMode(mode); }
        public void showProviders() { state.providersOpen = true; }
        public void returnToGallery() { state.providersOpen = false; }
        public void editProvider(ProviderFlow.EditRequest request) { ClientRuntime.this.editProvider(request); }
        public void acceptProviderSnapshot(ClientOperations.DurableAppearance appearance) { ClientRuntime.this.acceptProviderSnapshot(appearance); }
        public CompletableFuture<AppearanceRefreshCoordinator.Result> acceptProviderChange(ClientOperations.DurableAppearance appearance) { return ClientRuntime.this.acceptProviderChange(appearance); }
    });

    private final ClientOperations operations;
    private final CapeObservationPort capeObservations;
    private final DiagnosticSink diagnostics;

    private final ClientExecutor clientExecutor;
    private final FilePicker filePicker;
    private final Executor worker;
    private final ExecutorService ownedWorker;
    private final ExecutorService ownedReconciliationWorker;
    private final Executor sessionWorker;
    private final ExecutorService ownedSessionWorker;

    private SelfCapeInputs publishedSelfCapeInputs;
    private final TextResolver textResolver;
    private final Optional<CurrentPlayerAppearanceSource> currentAppearanceSource;
    private final Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh;
    private final Optional<OuterLayerVisibilityController> outerLayerVisibilityController;
    private final Optional<ServerAppearanceReadinessCoordinator> serverAppearanceReadiness;

    private final CopyOnWriteArrayList<Consumer<ClientSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private final PreviewAssetLoader previewAssets;
    private final AccountReconciliationCoordinator reconciliation;
    private final State state = new State();
    private Supplier<ClientConfiguration> configurationSource = ClientConfiguration::defaults;
    private SkinExtensionEnvironmentSource skinExtensionEnvironmentSource =
            SkinExtensionEnvironmentSource.unknown();
    private SkinExtensionEnvironment skinExtensionEnvironment =
            SkinExtensionEnvironment.unknown(0);
    private boolean skinExtensionEnvironmentInitialized;
    private long sessionRetryTicket = -1L;
    private long sessionActivitySequence;
    private long sessionActivityTicket = -1L;
    private long sessionActivityBaselineGeneration = -1L;
    private UUID sessionActivityAccountId;
    private boolean sessionRetryFeedbackRendered;
    private int sessionRetryFeedbackTicksRemaining;
    private SessionRetrySettlement pendingSessionRetrySettlement;
    private boolean rateLimitRecoveryArmed;
    private UUID rateLimitRecoveryAccountId;
    private Duration rateLimitTotal;

    private volatile ClientSnapshot snapshot = ClientSnapshot.initial();
    private volatile boolean disposed;

    private boolean startupWarmupStarted;
    private int viewportWidth = 320;
    private int viewportHeight = 240;
    private ViewChromeMetrics viewChromeMetrics = ViewChromeMetrics.STANDARD;

    private long runtimeFocusToken;
    private String runtimeFocusScreenId;
    private String runtimeFocusWidgetId;

    private PreviewRenderer.CapeMode preferredCapeMode = PreviewPreferences.capeMode();
    private boolean capeCatalogWarmupRunning;
    private boolean capeCatalogReloadPending;

    public ClientRuntime(
            ClientOperations operations,
            ClientExecutor clientExecutor,
            FilePicker filePicker,
            Executor worker,
            TextResolver textResolver,
            Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh,
            DiagnosticSink diagnostics) {
        this(
                operations,
                clientExecutor,
                filePicker,
                worker,
                null,
                worker,
                null,
                worker,
                null,
                textResolver,
                Optional.empty(),
                appearanceRefresh,
                Optional.empty(),
                Optional.empty(),
                ServerAppearanceReadinessCoordinator.DelayScheduler.system(),
                diagnostics);
    }

    public ClientRuntime(
            ClientOperations operations,
            ClientExecutor clientExecutor,
            FilePicker filePicker,
            Executor worker,
            TextResolver textResolver,
            Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh,
            Optional<ServerAppearanceRefreshNotifier> serverAppearanceRefreshNotifier,
            DiagnosticSink diagnostics) {
        this(
                operations,
                clientExecutor,
                filePicker,
                worker,
                null,
                worker,
                null,
                worker,
                null,
                textResolver,
                Optional.empty(),
                appearanceRefresh,
                Optional.empty(),
                serverAppearanceRefreshNotifier,
                ServerAppearanceReadinessCoordinator.DelayScheduler.system(),
                diagnostics);
    }

    ClientRuntime(
            ClientOperations operations,
            ClientExecutor clientExecutor,
            FilePicker filePicker,
            Executor worker,
            TextResolver textResolver,
            Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh,
            Optional<ServerAppearanceRefreshNotifier> serverAppearanceRefreshNotifier,
            ServerAppearanceReadinessCoordinator.DelayScheduler readinessScheduler,
            DiagnosticSink diagnostics) {
        this(
                operations,
                clientExecutor,
                filePicker,
                worker,
                null,
                worker,
                null,
                worker,
                null,
                textResolver,
                Optional.empty(),
                appearanceRefresh,
                Optional.empty(),
                serverAppearanceRefreshNotifier,
                readinessScheduler,
                diagnostics);
    }

    ClientRuntime(
            ClientOperations operations,
            ClientExecutor clientExecutor,
            FilePicker filePicker,
            Executor worker,
            Executor reconciliationWorker,
            TextResolver textResolver,
            Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh,
            Optional<ServerAppearanceRefreshNotifier> serverAppearanceRefreshNotifier,
            ServerAppearanceReadinessCoordinator.DelayScheduler readinessScheduler,
            DiagnosticSink diagnostics) {
        this(
                operations,
                clientExecutor,
                filePicker,
                worker,
                null,
                reconciliationWorker,
                null,
                reconciliationWorker,
                null,
                textResolver,
                Optional.empty(),
                appearanceRefresh,
                Optional.empty(),
                serverAppearanceRefreshNotifier,
                readinessScheduler,
                diagnostics);
    }

    ClientRuntime(
            ClientOperations operations,
            ClientExecutor clientExecutor,
            FilePicker filePicker,
            Executor worker,
            Executor reconciliationWorker,
            Executor sessionWorker,
            TextResolver textResolver,
            Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh,
            Optional<ServerAppearanceRefreshNotifier> serverAppearanceRefreshNotifier,
            ServerAppearanceReadinessCoordinator.DelayScheduler readinessScheduler,
            DiagnosticSink diagnostics) {
        this(
                operations,
                clientExecutor,
                filePicker,
                worker,
                null,
                reconciliationWorker,
                null,
                sessionWorker,
                null,
                textResolver,
                Optional.empty(),
                appearanceRefresh,
                Optional.empty(),
                serverAppearanceRefreshNotifier,
                readinessScheduler,
                diagnostics);
    }

    ClientRuntime(
            ClientOperations operations,
            ClientExecutor clientExecutor,
            FilePicker filePicker,
            Executor worker,
            TextResolver textResolver,
            Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh,
            Optional<OuterLayerVisibilityController> outerLayerVisibilityController,
            Optional<ServerAppearanceRefreshNotifier> serverAppearanceRefreshNotifier,
            ServerAppearanceReadinessCoordinator.DelayScheduler readinessScheduler,
            DiagnosticSink diagnostics) {
        this(operations, clientExecutor, filePicker, worker, textResolver, Optional.empty(),
                appearanceRefresh, outerLayerVisibilityController, serverAppearanceRefreshNotifier,
                readinessScheduler, diagnostics);
    }

    ClientRuntime(
            ClientOperations operations,
            ClientExecutor clientExecutor,
            FilePicker filePicker,
            Executor worker,
            TextResolver textResolver,
            Optional<CurrentPlayerAppearanceSource> currentAppearanceSource,
            Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh,
            Optional<OuterLayerVisibilityController> outerLayerVisibilityController,
            Optional<ServerAppearanceRefreshNotifier> serverAppearanceRefreshNotifier,
            ServerAppearanceReadinessCoordinator.DelayScheduler readinessScheduler,
            DiagnosticSink diagnostics) {
        this(
                operations,
                clientExecutor,
                filePicker,
                worker,
                null,
                worker,
                null,
                worker,
                null,
                textResolver,
                currentAppearanceSource,
                appearanceRefresh,
                outerLayerVisibilityController,
                serverAppearanceRefreshNotifier,
                readinessScheduler,
                diagnostics);
    }

    private ClientRuntime(
            ClientOperations operations,
            ClientExecutor clientExecutor,
            FilePicker filePicker,
            Executor worker,
            ExecutorService ownedWorker,
            Executor reconciliationWorker,
            ExecutorService ownedReconciliationWorker,
            Executor sessionWorker,
            ExecutorService ownedSessionWorker,
            TextResolver textResolver,
            Optional<CurrentPlayerAppearanceSource> currentAppearanceSource,
            Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh,
            Optional<OuterLayerVisibilityController> outerLayerVisibilityController,
            Optional<ServerAppearanceRefreshNotifier> serverAppearanceRefreshNotifier,
            ServerAppearanceReadinessCoordinator.DelayScheduler readinessScheduler,
            DiagnosticSink diagnostics) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.capeObservations = operations;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.clientExecutor = Objects.requireNonNull(clientExecutor, "clientExecutor");
        this.filePicker = Objects.requireNonNull(filePicker, "filePicker");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.previewAssets = new PreviewAssetLoader(clientExecutor, worker, diagnostics);
        this.ownedWorker = ownedWorker;
        this.ownedReconciliationWorker = ownedReconciliationWorker;
        this.reconciliation = new AccountReconciliationCoordinator(operations, reconciliationWorker,
                this::onClient, this::acceptAppearanceReconciliation,
                this::finishAppearanceReconciliation, this::diagnose);
        this.sessionWorker = Objects.requireNonNull(sessionWorker, "sessionWorker");
        this.ownedSessionWorker = ownedSessionWorker;
        this.textResolver = Objects.requireNonNull(textResolver, "textResolver");
        this.currentAppearanceSource = Objects.requireNonNull(
                currentAppearanceSource, "currentAppearanceSource");
        this.appearanceRefresh = Objects.requireNonNull(appearanceRefresh, "appearanceRefresh");
        this.outerLayerVisibilityController = Objects.requireNonNull(
                outerLayerVisibilityController, "outerLayerVisibilityController");
        this.serverAppearanceReadiness = Objects.requireNonNull(
                        serverAppearanceRefreshNotifier, "serverAppearanceRefreshNotifier")
                .map(ServerAppearanceReadinessCoordinator::new);
        Objects.requireNonNull(readinessScheduler, "readinessScheduler");
        capeObservations.onCapeObservation(observation -> onClient(() -> {
            switch (observation.provider()) {
                case OPTIFINE -> acceptOptiFineObservation(observation);
                case SKINMC -> acceptSkinMcObservation(observation);
                case SNEAKY -> acceptSneakyObservation(observation);
                default -> throw new IllegalArgumentException("Not a read-only cape provider");
            }
        }));
    }

    private void acceptOptiFineObservation(CapeObservationPort.Observation observation) {
        if (disposed || state.lifecycle == ClientSnapshot.Lifecycle.CLOSED || state.account == null
                || !state.account.accountId().equals(observation.accountId())
                || !state.providers.cape().enabled(BuiltinProvider.OPTIFINE)
                || state.providers.cape().configurationRevision() != observation.capeConfigurationRevision()) return;
        try {
            var current = operations.sessionIdentity();
            if (!current.profileId().equals(observation.accountId())
                    || !current.profileName().equals(observation.canonicalName())) return;
        } catch (RuntimeException unavailable) {
            return;
        }
        state.providers = new AppearanceProviders(state.providers.skin(),
                state.providers.cape().observeOptifine(observation.cape()));
        publish();
    }

    private void acceptSkinMcObservation(CapeObservationPort.Observation observation) {
        if (disposed || state.lifecycle == ClientSnapshot.Lifecycle.CLOSED || state.account == null
                || !state.account.accountId().equals(observation.accountId())
                || !state.providers.cape().enabled(BuiltinProvider.SKINMC)
                || state.providers.cape().configurationRevision() != observation.capeConfigurationRevision()) return;
        try {
            var current = operations.sessionIdentity();
            if (!current.profileId().equals(observation.accountId())
                    || !current.profileName().equals(observation.canonicalName())) return;
        } catch (RuntimeException unavailable) {
            return;
        }
        state.providers = new AppearanceProviders(state.providers.skin(),
                state.providers.cape().observeSkinmc(observation.cape()));
        publish();
    }

    private void acceptSneakyObservation(CapeObservationPort.Observation observation) {
        if (disposed || state.lifecycle == ClientSnapshot.Lifecycle.CLOSED || state.account == null
                || !state.account.accountId().equals(observation.accountId())
                || !state.providers.cape().enabled(BuiltinProvider.SNEAKY)
                || state.providers.cape().configurationRevision() != observation.capeConfigurationRevision()
                || !Objects.equals(state.providers.skin().resolve()
                        .map(resolved -> resolved.value().sha256()).orElse(null), observation.skinSha256())) return;
        try {
            var current = operations.sessionIdentity();
            if (!current.profileId().equals(observation.accountId())
                    || !current.profileName().equals(observation.canonicalName())) return;
        } catch (RuntimeException unavailable) {
            return;
        }
        state.providers = new AppearanceProviders(state.providers.skin(),
                state.providers.cape().withSneakyObservation(observation.observation()));
        publish();
    }

    public static ClientRuntime createDefaultWithDeterministicAppearance(
            GameSessionTokenSource tokenSource,
            SkinCatalogSource bundledSkins,
            Path dataRoot,
            CurrentPlayerAppearanceSource currentAppearanceSource,
            ClientExecutor clientExecutor,
            FilePicker filePicker,
            TextResolver textResolver,
            SignedTextureVerifier signedTextureVerifier,
            PlayerAppearanceSink<AcknowledgedAppearanceAssets> sink,
            OuterLayerVisibilityController outerLayerVisibilityController,
            ServerAppearanceRefreshNotifier serverAppearanceRefreshNotifier,
            DiagnosticSink diagnostics) {
        ExecutorService worker = newWorker("nclskins-client-runtime");
        ExecutorService reconciliationWorker = newWorker("nclskins-appearance-reconciliation");
        ExecutorService sessionWorker = newWorker("nclskins-session-activity");
        DefaultClientOperations operations = DefaultClientOperations
                .createDefault(tokenSource, bundledSkins, dataRoot)
                .enablePublicImports(signedTextureVerifier)
                .attachOptifineCapes(sink, clientExecutor);
        AppearanceRefreshCoordinator<AcknowledgedAppearanceAssets> refresh =
                new AppearanceRefreshCoordinator<>(
                        clientExecutor,
                        operations.deterministicAppearanceResolver(worker),
                        sink,
                        diagnostics);
        ClientRuntime runtime = new ClientRuntime(
                operations,
                clientExecutor,
                filePicker,
                worker,
                worker,
                reconciliationWorker,
                reconciliationWorker,
                sessionWorker,
                sessionWorker,
                textResolver,
                Optional.of(Objects.requireNonNull(currentAppearanceSource, "currentAppearanceSource")),
                Optional.of(refresh),
                Optional.of(Objects.requireNonNull(
                        outerLayerVisibilityController, "outerLayerVisibilityController")),
                Optional.of(Objects.requireNonNull(
                        serverAppearanceRefreshNotifier, "serverAppearanceRefreshNotifier")),
                ServerAppearanceReadinessCoordinator.DelayScheduler.system(),
                Objects.requireNonNull(diagnostics, "diagnostics"));
        runtime.providerFlow.useOptiFineAccountLink(new OptiFineAccountLink(tokenSource, sessionWorker));
        return runtime;
    }

    ClientRuntime useOptiFineAccountLink(OptiFineAccountLink link) {
        providerFlow.useOptiFineAccountLink(link);
        return this;
    }

    public Optional<URI> consumeReadyOptiFineAccountLink() {
        return providerFlow.consumeReadyOptiFineAccountLink();
    }

    public Optional<URI> currentOptiFineAccountLink() {
        return providerFlow.currentOptiFineAccountLink();
    }

    public void finishOptiFineAccountLink() {
        providerFlow.finishOptiFineAccountLink();
    }

    public Optional<URI> consumeReadySkinMcAccountLink() {
        return providerFlow.consumeReadySkinMcAccountLink();
    }

    public Optional<URI> currentSkinMcAccountLink() {
        return providerFlow.currentSkinMcAccountLink();
    }

    public void finishSkinMcAccountLink() {
        providerFlow.finishSkinMcAccountLink();
    }

    public Optional<URI> consumeReadySneakyEditorLink() {
        return providerFlow.consumeReadySneakyEditorLink();
    }

    public Optional<URI> currentSneakyEditorLink() {
        return providerFlow.currentSneakyEditorLink();
    }

    public void finishSneakyEditorLink() {
        providerFlow.finishSneakyEditorLink();
    }

    public void expireOptiFineAccountLink() {
        providerFlow.expireOptiFineAccountLink();
    }

    private void cancelOptiFineAccountLink() {
        providerFlow.cancelOptiFineAccountLink();
    }

    private void cancelSkinMcAccountLink() {
        providerFlow.cancelSkinMcAccountLink();
    }

    private void cancelSneakyEditorLink() {
        providerFlow.cancelSneakyEditorLink();
    }

    private boolean liveSneakyEditorLinkView() {
        return providerFlow.liveSneakyEditorLinkView();
    }

    private boolean liveSkinMcLinkView() {
        return providerFlow.liveSkinMcLinkView();
    }

    private boolean liveOptiFineLinkView() {
        return providerFlow.liveOptiFineLinkView();
    }

    public ClientSnapshot snapshot() {
        return snapshot;
    }

    public ClientRuntime useConfigurationSource(Supplier<ClientConfiguration> source) {
        if (state.lifecycle != ClientSnapshot.Lifecycle.NEW) {
            throw new IllegalStateException("configuration source must be installed before initialization");
        }
        configurationSource = Objects.requireNonNull(source, "source");
        return this;
    }

    public ClientRuntime useSkinExtensionEnvironmentSource(
            SkinExtensionEnvironmentSource source) {
        if (state.lifecycle != ClientSnapshot.Lifecycle.NEW) {
            throw new IllegalStateException("environment source must be installed before initialization");
        }
        skinExtensionEnvironmentSource = Objects.requireNonNull(source, "source");
        return this;
    }

    public DiagnosticSink diagnostics() {
        return diagnostics;
    }

    public boolean closed() {
        return disposed;
    }

    public Subscription subscribe(Consumer<ClientSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        onClient(() -> listener.accept(snapshot));
        return () -> listeners.remove(listener);
    }

    public void initialize() {
        onClient(this::initializeOnClient);
    }

    public void verifyStorageAccess() {
        ensureNotDisposed();
        try {
            operations.verifyStorageAccess();
            state.providers = operations.loadProviders();
            publish();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "NCL Skins (nclskins) could not initialize its per-user state directory.",
                    failure);
        }
    }

    public void warmSession() {
        onClient(() -> {
            ensureNotDisposed();
            if (startupWarmupStarted) {
                return;
            }
            startupWarmupStarted = true;
            CompletableFuture.supplyAsync(() -> {
                        try {
                            operations.warmSession();
                            return operations.warmedOuterLayerVisibility();
                        } catch (Exception failure) {
                            diagnostics.report(
                                    DiagnosticEvent.CLIENT_SESSION_WARMUP_FAILED,
                                    () -> DiagnosticDetails.failure(failure));
                            throw new CompletionException(failure);
                        }
                    }, worker)
                    .whenComplete((visibility, failure) -> onClient(() -> {
                        if (!disposed && failure != null) {
                            startupWarmupStarted = false;
                        } else if (!disposed && visibility != null) {
                            Optional<ClientOperations.DurableAppearance> warmed =
                                    operations.warmedDurableAppearance();
                            if (warmed.filter(this::currentSessionOwns).isEmpty()
                                    && warmed.isPresent()) {
                                return;
                            }
                            warmed.ifPresent(this::acceptProviderSnapshot);
                            if (warmed.isPresent()) capeObservations.startOptiFineCapes();
                            visibility.ifPresent(this::applyDurableOuterLayerVisibility);
                            CompletableFuture<AppearanceRefreshCoordinator.Result> localRebind =
                                    refreshLocalAppearance(warmed
                                            .flatMap(ClientOperations.DurableAppearance::localAppearance));
                            if (operations.warmedReconciliationRecommended() && warmed.isPresent()) {
                                reconcileAfterLocalRebind(
                                        localRebind,
                                        reconciliationKey(warmed.orElseThrow()),
                                        Trigger.PROCESS_START);
                            }
                        }
                    }));
        });
    }

    public void reopen() {
        reopen(ScreenDestination.GALLERY);
    }

    public void reopen(ScreenDestination destination) {
        Objects.requireNonNull(destination, "destination");
        onClient(() -> {
            ensureNotDisposed();
            if (state.lifecycle == ClientSnapshot.Lifecycle.CLOSED) {
                state.resetForReopen();
                if (state.readyData) centerGalleryOnActive();
            }
            if (state.lifecycle == ClientSnapshot.Lifecycle.NEW) {
                state.requestedDestination = destination;
            }
            initializeOnClient();
        });
    }

    private void resolveRequestedDestination() {
        ScreenDestination destination = state.requestedDestination;
        state.requestedDestination = null;
        if (destination == null || state.account == null) return;
        state.rootDestination = !state.providers.galleryAvailable()
                ? ScreenDestination.PROVIDERS : destination;
        switch (state.rootDestination) {
            case PROVIDERS -> state.providersOpen = true;
            case GALLERY -> { }
            case ACTIVE_EDITOR -> {
                if (state.activePresetId != null && state.account.presets().stream()
                        .anyMatch(preset -> preset.id().equals(state.activePresetId))) {
                    openEditor(state.activePresetId);
                } else {
                    state.rootDestination = ScreenDestination.SKIN_IMPORT;
                    openAddSource(AddSourceTab.FILE);
                }
            }
            case SKIN_CATALOG -> openAddSource(AddSourceTab.CATALOG);
            case SKIN_IMPORT -> openAddSource(AddSourceTab.FILE);
        }
    }

    private boolean destinationLoading() {
        return (state.requestedDestination != null && state.requestedDestination != ScreenDestination.GALLERY)
                || (state.busy && !state.providersOpen && editorFlow.editor() == null && catalogImportFlow.addSource() == null
                        && (state.rootDestination == ScreenDestination.ACTIVE_EDITOR || addSourceRoot()));
    }

    private boolean addSourceRoot() {
        return state.rootDestination == ScreenDestination.SKIN_CATALOG
                || state.rootDestination == ScreenDestination.SKIN_IMPORT;
    }

    private void selectProvidersTab(AppearanceProviders.Component tab) {
        providerFlow.selectProvidersTab(tab);
    }

    public void closeScreen() {
        onClient(this::closeScreenOnClient);
    }

    public void trackedCapePlayer(UUID profileId, String canonicalName) {
        capeObservations.trackedCapePlayer(profileId, canonicalName);
    }

    public void untrackedCapePlayer(UUID profileId) {
        capeObservations.untrackedCapePlayer(profileId);
    }

    public void capeWorldChanged() {
        capeObservations.capeWorldChanged();
    }

    public void escapePressed() {
        onClient(() -> {
            if (destinationLoading()) {
                closeScreenOnClient();
                return;
            }
            if (disposed
                    || state.lifecycle == ClientSnapshot.Lifecycle.CLOSED
                    || state.busy && catalogImportFlow.externalImport() == null) {
                return;
            }
            if (state.providersOpen || !state.providers.galleryAvailable()) {
                dispatchProviderWidget("providers.back");
                return;
            }
            if (state.galleryReturnsToProviders) {
                closeToProviders();
                return;
            }
            if (galleryFlow.pendingPresetDeleteId() != null) {
                cancelPresetDeletion(galleryFlow.pendingPresetDeleteId(), InteractionOrigin.PROGRAMMATIC);
                return;
            }
            if (catalogImportFlow.personalRenameHash() != null) {
                cancelPersonalSkinRename();
                return;
            }
            if (catalogImportFlow.addSource() != null
                    && catalogImportFlow.addSource().personalSkinDeletion().isPresent()) {
                cancelPersonalSkinDeletion(InteractionOrigin.PROGRAMMATIC);
                return;
            }
            if (editorFlow.editor() != null && editorFlow.editor().capeCatalog() != null && editorFlow.editor().capeCatalog().editing() != null) {
                UUID entry = editorFlow.editor().capeCatalog().editing();
                updateEditor(editor -> editor.withCapeCatalog(editor.capeCatalog().cancelEdit()));
                requestRuntimeFocus("preset_editor", "editor.cape_item.OFFLINE." + entry);
                publish();
                return;
            }
            if (editorFlow.editor() != null) {
                cancelEditor();
                return;
            }
            if (catalogImportFlow.externalImport() != null) {
                cancelExternalImport();
                return;
            }
            if (catalogImportFlow.addSource() != null) {
                cancelAddSource();
                return;
            }
            closeScreenOnClient();
        });
    }

    private void closeScreenOnClient() {
        if (disposed || state.lifecycle == ClientSnapshot.Lifecycle.CLOSED) {
            return;
        }
        cancelOptiFineAccountLink();
        cancelSkinMcAccountLink();
        cancelSneakyEditorLink();
        providerFlow.clearAccountLinkFeedback();
        state.generation++;
        state.lifecycle = ClientSnapshot.Lifecycle.CLOSED;
        state.editorReturnsToProviders = false;
        state.galleryReturnsToProviders = false;
        providerFlow.clearPreviewSources();
        state.providersOpen = false;
        providerFlow.leaveChooser();
        state.busy = false;
        state.sessionActivity = ClientSnapshot.SessionActivity.NONE;
        galleryFlow.dismissDeletion();
        clearRuntimeFocus("gallery");
        clearSessionRetryFeedback();
        editorFlow.closeDraft();
        catalogImportFlow.closeScreens();
        galleryFlow.stopScrolling();
        providerFlow.releaseScrollbar();
        editorFlow.releaseScrollbar();
        catalogImportFlow.releaseScrollbar();
        catalogImportFlow.resetClosedScroll();
        publish();
    }

    public void tick() {
        onClient(() -> {
            if (disposed) {
                return;
            }
            if (providerFlow.expireAccountLink()) {
                publish();
            }
            boolean rateLimitChanged = observeRateLimit();
            boolean capeCooldownChanged = observeCapeProviderCooldowns();
            if (state.lifecycle == ClientSnapshot.Lifecycle.CLOSED) {
                if (rateLimitChanged || capeCooldownChanged) {
                    publish();
                }
                return;
            }
            advanceSessionRetryFeedback();
            boolean scrollChanged = clampGalleryScroll();
            if (editorFlow.editor() != null) {
                scrollChanged |= clampEditorScroll();
            }
            if (catalogImportFlow.addSource() != null
                    && catalogImportFlow.addSource().selectedTab() == AddSourceTab.CATALOG
                    && catalogImportFlow.addSource().personalSkinDeletion().isEmpty()) {
                double beforePosition = catalogImportFlow.addSourceScrollPosition();
                double beforeTarget = catalogImportFlow.addSourceScrollTarget();
                int beforeOffset = catalogImportFlow.addSource().scrollOffset();
                clampAddSourceScroll();
                scrollChanged |= Math.abs(beforePosition - catalogImportFlow.addSourceScrollPosition()) > 0.001
                        || Math.abs(beforeTarget - catalogImportFlow.addSourceScrollTarget()) > 0.001
                        || beforeOffset != catalogImportFlow.addSource().scrollOffset();
            }
            if (rateLimitChanged || capeCooldownChanged || scrollChanged) {
                publish();
            }
        });
    }

    public void resourcesReloaded() {
        onClient(() -> {
            if (disposed) {
                return;
            }
            boolean environmentChanged = refreshSkinExtensionEnvironment();
            capeCatalogReloadPending = true;
            warmReloadedCapeCatalog();
            if (environmentChanged) {
                publish();
            }
        });
    }

    private void warmReloadedCapeCatalog() {
        if (capeCatalogWarmupRunning || !capeCatalogReloadPending) {
            return;
        }
        capeCatalogReloadPending = false;
        UUID accountId = state.account == null ? null : state.account.accountId();
        final long generation;
        try {
            generation = operations.capeCatalogGeneration();
        } catch (RuntimeException failure) {
            diagnose(DiagnosticEvent.CLIENT_CAPE_CACHE_FAILED, failure);
            return;
        }
        if (generation == Long.MIN_VALUE) {
            return;
        }
        capeCatalogWarmupRunning = true;
        CompletableFuture.runAsync(() -> {
            try {
                operations.warmResourceCapeCatalog(generation);
                if (accountId != null) {
                    operations.warmCapeCatalog(accountId, generation);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CompletionException(interrupted);
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        }, worker).whenComplete((ignored, failure) -> onClient(() -> {
            capeCatalogWarmupRunning = false;
            if (disposed) {
                return;
            }
            if (failure != null) {
                diagnose(DiagnosticEvent.CLIENT_CAPE_CACHE_FAILED, failure);
            } else if (accountId != null && state.account != null
                    && accountId.equals(state.account.accountId())) {
                installWarmedCapePreviews(accountId, true);
                if (editorFlow.editor() != null) {
                    reloadEditorCatalog();
                }
            }
            warmReloadedCapeCatalog();
        }));
    }

    private boolean observeRateLimit() {
        Optional<Duration> remaining = state.providers.minecraftEnabled() ? operations.rateLimitRemaining() : Optional.empty();
        boolean limited = remaining.isPresent();
        boolean changed = state.rateLimited != limited;
        state.rateLimited = limited;
        if (limited) {
            Duration current = remaining.orElseThrow();
            if (rateLimitTotal == null || current.compareTo(rateLimitTotal) > 0) {
                rateLimitTotal = current;
            }
            double fraction = Math.min(1.0, Math.max(0.0,
                    (double) current.toNanos() / (double) rateLimitTotal.toNanos()));
            ClientSnapshot.RateLimitProgress progress =
                    new ClientSnapshot.RateLimitProgress(current, rateLimitTotal, fraction);
            changed |= !Optional.of(progress).equals(state.rateLimitProgress);
            state.rateLimitProgress = Optional.of(progress);
            armRateLimitRecovery();
            return changed;
        }

        changed |= state.rateLimitProgress.isPresent();
        state.rateLimitProgress = Optional.empty();
        rateLimitTotal = null;
        if (!rateLimitRecoveryArmed) {
            return changed;
        }
        UUID accountId = state.providers.minecraftEnabled() ? rateLimitRecoveryAccountId : null;
        rateLimitRecoveryArmed = false;
        rateLimitRecoveryAccountId = null;
        if (accountId != null && accountId.equals(currentSessionAccountId())) {
            currentReconciliationKey()
                    .filter(key -> key.accountId().equals(accountId))
                    .ifPresent(key -> requestAppearanceReconciliation(
                            key, Trigger.RATE_LIMIT_EXPIRED));
        }
        return true;
    }

    private boolean observeCapeProviderCooldowns() {
        Map<BuiltinProvider, Duration> next = new EnumMap<>(BuiltinProvider.class);
        if (state.lifecycle == ClientSnapshot.Lifecycle.READY && state.account != null
                && state.account.accountId().equals(currentSessionAccountId())) {
            for (BuiltinProvider provider : List.of(BuiltinProvider.OPTIFINE, BuiltinProvider.SKINMC)) {
                if (!state.providers.cape().enabled(provider)) continue;
                capeObservations.capeProviderCooldown(provider).ifPresent(remaining -> {
                    if (remaining.isNegative() || remaining.isZero()) return;
                    Duration bounded = remaining.compareTo(Duration.ofHours(24)) > 0
                            ? Duration.ofHours(24) : remaining;
                    next.put(provider, Duration.ofSeconds(bounded.getSeconds()
                            + (bounded.getNano() > 0 ? 1 : 0)));
                });
            }
        }
        if (state.capeProviderCooldowns.equals(next)) return false;
        state.capeProviderCooldowns = Map.copyOf(next);
        return true;
    }

    private void armRateLimitRecovery() {
        UUID currentAccountId = currentSessionAccountId();
        if (currentAccountId == null
                || state.account == null
                || !state.account.accountId().equals(currentAccountId)) {
            rateLimitRecoveryArmed = false;
            rateLimitRecoveryAccountId = null;
            return;
        }
        rateLimitRecoveryArmed = true;
        rateLimitRecoveryAccountId = currentAccountId;
    }

    private UUID currentSessionAccountId() {
        try {
            return operations.sessionIdentity().profileId();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    public CompletableFuture<AppearanceRefreshCoordinator.Result> afterReconnect() {
        CompletableFuture<AppearanceRefreshCoordinator.Result> publication = new CompletableFuture<>();
        onClient(() -> {
            if (disposed) {
                publication.complete(AppearanceRefreshCoordinator.Result.NOT_APPLICABLE);
                return;
            }
            CompletableFuture.supplyAsync(() -> {
                        try {
                            return operations.durableAppearance();
                        } catch (Exception failure) {
                            throw new CompletionException(failure);
                        }
                    }, worker)
                    .whenComplete((durable, failure) -> onClient(() -> {
                        if (disposed) {
                            publication.complete(AppearanceRefreshCoordinator.Result.NOT_APPLICABLE);
                            return;
                        }
                        if (failure != null || durable == null) {
                            diagnose(DiagnosticEvent.CLIENT_RECONNECT_FAILED, failure);
                            publication.complete(AppearanceRefreshCoordinator.Result.DEFERRED);
                            return;
                        }
                        if (durable.isPresent()
                                && durable.filter(this::currentSessionOwns).isEmpty()) {
                            publication.complete(AppearanceRefreshCoordinator.Result.DEFERRED);
                            return;
                        }
                        durable.ifPresent(this::acceptProviderSnapshot);
                        boolean checkpoint = durable
                                .filter(appearance -> automaticCheckpointEligible(
                                        appearance.syncStatus()))
                                .isPresent();
                        durable.flatMap(ClientOperations.DurableAppearance::outerLayerVisibility)
                                .ifPresent(this::applyDurableOuterLayerVisibility);
                        Optional<AppliedAppearance> local = durable
                                .flatMap(ClientOperations.DurableAppearance::localAppearance);
                        CompletableFuture<AppearanceRefreshCoordinator.Result> localRebind =
                                appearanceRefresh.isPresent() && local.isPresent()
                                        ? appearanceRefresh.orElseThrow()
                                                .afterReconnect(local.orElseThrow(), ignored -> {})
                                        : CompletableFuture.completedFuture(
                                                AppearanceRefreshCoordinator.Result.NOT_APPLICABLE);
                        localRebind.whenComplete((result, refreshFailure) -> onClient(() -> {
                                    if (disposed) {
                                        publication.complete(
                                                AppearanceRefreshCoordinator.Result.NOT_APPLICABLE);
                                        return;
                                    }
                                    if (checkpoint) {
                                        requestAppearanceReconciliation(
                                                reconciliationKey(durable.orElseThrow()),
                                                Trigger.RECONNECT);
                                    }
                                    if (refreshFailure == null && result != null) {
                                        publication.complete(result);
                                    } else {
                                        diagnose(
                                                DiagnosticEvent.CLIENT_RECONNECT_FAILED,
                                                refreshFailure);
                                        publication.complete(
                                                AppearanceRefreshCoordinator.Result.DEFERRED);
                                    }
                                }));
                    }));
        });
        return publication;
    }

    public ViewSpec view(int width, int height, int mouseX, int mouseY) {
        return viewInternal(width, height, mouseX, mouseY);
    }

    public ViewSpec view(
            int width,
            int height,
            int mouseX,
            int mouseY,
            ViewChromeMetrics chromeMetrics) {
        viewChromeMetrics = Objects.requireNonNull(chromeMetrics, "chromeMetrics");
        return viewInternal(width, height, mouseX, mouseY);
    }

    private ViewSpec viewInternal(int width, int height, int mouseX, int mouseY) {
        ensureNotDisposed();
        viewportWidth = width;
        viewportHeight = height;
        if (destinationLoading()) {
            return new ViewSpec("loading", UiMessage.info("nclskins.title"), width, height,
                    List.of(), List.of(new ViewSpec.Text("destination.loading",
                            new Bounds(8, Math.max(0, height / 2 - 5), Math.max(1, width - 16), 10),
                            UiMessage.info("nclskins.status.loading"), ViewSpec.Text.Alignment.CENTER)),
                    List.of(), List.of(), Optional.empty());
        }
        if (state.providersOpen || !state.providers.galleryAvailable()) return presentProvider(width, height);
        if (editorFlow.editor() != null) return presentEditor(width, height);
        if (catalogImportFlow.externalImport() != null || catalogImportFlow.addSource() != null) return presentCatalogImport(width, height);
        return withRuntimeFocus(galleryView(width, height, mouseX, mouseY));
    }

    private ViewSpec withRuntimeFocus(ViewSpec view) {
        if (!view.screenId().equals(runtimeFocusScreenId) || runtimeFocusWidgetId == null) {
            return view;
        }
        return view.withFocusRequest(Optional.of(
                new ViewSpec.FocusRequest(runtimeFocusWidgetId, runtimeFocusToken)));
    }

    public void acknowledgeViewRendered(ViewSpec renderedView) {
        Objects.requireNonNull(renderedView, "renderedView");
        if (disposed
                || !"gallery".equals(renderedView.screenId())
                || renderedView.texts().stream().noneMatch(text ->
                text.id().equals("gallery.offline")
                        && text.message().equals(
                        UiMessage.info("nclskins.session.connecting")))) {
            return;
        }
        onClient(this::acknowledgeSessionRetryFeedbackRendered);
    }

    public void acknowledgeFocusApplied(
            String screenId, ViewSpec.FocusRequest appliedRequest) {
        Objects.requireNonNull(screenId, "screenId");
        Objects.requireNonNull(appliedRequest, "appliedRequest");
        if (!disposed
                && screenId.equals(runtimeFocusScreenId)
                && appliedRequest.token() == runtimeFocusToken
                && appliedRequest.widgetId().equals(runtimeFocusWidgetId)) {
            runtimeFocusScreenId = null;
            runtimeFocusWidgetId = null;
        }
    }

    public Optional<CurrentPlayerAppearanceSource.PlayerAppearance> previewPlayerAppearance(ViewSpec.Preview preview) {
        if (preview.imageRevision().equals("provider:default")) {
            return currentAppearanceSource.map(CurrentPlayerAppearanceSource::defaultPlayerAppearance);
        }
        return currentPlayerAppearance();
    }

    public Optional<CurrentPlayerAppearanceSource.PlayerAppearance> currentPlayerAppearance() {
        ensureNotDisposed();
        if (!clientExecutor.isClientThread()) {
            throw new IllegalStateException("Current player appearance is client-thread-only");
        }
        try {
            return currentAppearanceSource.map(CurrentPlayerAppearanceSource::currentPlayerAppearance);
        } catch (RuntimeException unavailableAppearance) {
            diagnose(DiagnosticEvent.CLIENT_CURRENT_APPEARANCE_FAILED, unavailableAppearance);
            return Optional.empty();
        }
    }

    public Optional<PreviewRenderer.PreviewAppearance> menuPreviewAppearance() {
        Optional<CurrentPlayerAppearanceSource.PlayerAppearance> current = currentPlayerAppearance();
        if (current.isEmpty() || outerLayerVisibilityController.isEmpty()) {
            return Optional.empty();
        }
        try {
            var appearance = current.orElseThrow();
            Optional<TextureRegistry.TextureHandle> cape = appearance.cape();
            boolean hasElytra = true;
            try {
                var identity = operations.sessionIdentity();
                var resolved = CapeProjection.resolveSelf(identity.profileId(), identity.profileName());
                if (resolved.isPresent()) {
                    var result = resolved.orElseThrow();
                    cape = Optional.ofNullable(result.capeLocation())
                            .map(location -> new TextureRegistry.TextureHandle(location, 64, 32));
                    hasElytra = result.hasElytra();
                }
            } catch (RuntimeException unavailableIdentity) {
                diagnose(DiagnosticEvent.CLIENT_CURRENT_APPEARANCE_FAILED, unavailableIdentity);
            }
            return Optional.of(new PreviewRenderer.PreviewAppearance(
                    appearance.skin(),
                    appearance.model(),
                    cape,
                    cape.isPresent() ? PreviewRenderer.CapeMode.CAPE : PreviewRenderer.CapeMode.OFF,
                    outerLayerVisibilityController.orElseThrow().current(), hasElytra));
        } catch (RuntimeException unavailableAppearance) {
            diagnose(DiagnosticEvent.CLIENT_CURRENT_APPEARANCE_FAILED, unavailableAppearance);
            return Optional.empty();
        }
    }

    public void dispatchWidget(String widgetId) {
        dispatchWidget(widgetId, false, InteractionOrigin.PROGRAMMATIC);
    }

    public void dispatchWidget(String widgetId, boolean reverse) {
        dispatchWidget(widgetId, reverse, InteractionOrigin.PROGRAMMATIC);
    }

    public void dispatchWidget(
            String widgetId, boolean reverse, InteractionOrigin origin) {
        Objects.requireNonNull(widgetId, "widgetId");
        Objects.requireNonNull(origin, "origin");
        onClient(() -> dispatchWidgetOnClient(widgetId, reverse, origin));
    }

    public boolean dispatchNavigation(
            ViewSpec.NavigationCommand command, String focusedWidgetId) {
        Objects.requireNonNull(command, "command");
        ensureNotDisposed();
        if (!clientExecutor.isClientThread()) {
            throw new IllegalStateException("UI navigation is client-thread-only");
        }
        clearFileImportError();
        ViewSpec current = view(viewportWidth, viewportHeight, 0, 0);
        if (command == ViewSpec.NavigationCommand.ACTIVATE) {
            Optional<String> action = ViewNavigationPolicy.activationAction(
                    current, focusedWidgetId);
            if (action.isEmpty()) {
                return false;
            }
            dispatchWidgetOnClient(
                    action.orElseThrow(), false, InteractionOrigin.KEYBOARD);
            return true;
        }
        if ("gallery".equals(current.screenId())
                && command == ViewSpec.NavigationCommand.TAB_FORWARD
                && "gallery.search".equals(focusedWidgetId)) {
            Optional<ViewSpec.NavigationNode> visible = galleryVisibleEntryTarget(current);
            if (visible.isPresent()) {
                ViewSpec.NavigationNode node = visible.orElseThrow();
                galleryFlow.focusCard(node.id());
                requestRuntimeFocus(current.screenId(), node.id());
                publish();
                return true;
            }
        }
        Optional<ViewSpec.NavigationNode> target = command == ViewSpec.NavigationCommand.TAB_FORWARD
                && "editor.cape_disclosure".equals(focusedWidgetId)
                ? visibleCapeEntryTarget(current).or(() -> ViewNavigationPolicy.target(current, focusedWidgetId, command))
                : ViewNavigationPolicy.target(current, focusedWidgetId, command);
        if (target.isEmpty()) {
            return false;
        }
        ViewSpec.NavigationNode node = target.orElseThrow();
        if ("gallery".equals(current.screenId())
                && node.pattern() == ViewSpec.NavigationPattern.HORIZONTAL_LIST) {
            galleryFlow.focusCard(node.id());
        }
        ViewNavigationPolicy.ensureVisibleOffset(current, node)
                .ifPresent(offset -> applyNavigationScroll(node, offset));
        requestRuntimeFocus(current.screenId(), node.id());
        publish();
        return true;
    }

    private static Optional<ViewSpec.NavigationNode> galleryVisibleEntryTarget(ViewSpec view) {
        ViewSpec.ScrollSurface surface = view.scrollSurface("gallery.cards").orElse(null);
        if (surface == null) {
            return Optional.empty();
        }
        Bounds viewport = surface.viewport();
        List<ViewSpec.NavigationNode> entries = view.navigationNodes().stream()
                .filter(ViewSpec.NavigationNode::enabled)
                .filter(node -> node.pattern() == ViewSpec.NavigationPattern.HORIZONTAL_LIST)
                .filter(node -> node.surfaceId().filter("gallery.cards"::equals).isPresent())
                .filter(node -> node.bounds().right() > viewport.x()
                        && node.bounds().x() < viewport.right())
                .toList();
        int viewportCenter = viewport.x() + viewport.width() / 2;
        Optional<ViewSpec.NavigationNode> centered = entries.stream()
                .filter(node -> node.bounds().x() >= viewport.x()
                        && node.bounds().right() <= viewport.right())
                .min(java.util.Comparator
                        .comparingInt((ViewSpec.NavigationNode node) -> Math.abs(
                                node.bounds().x() + node.bounds().width() / 2 - viewportCenter))
                        .thenComparingInt(node -> node.bounds().x()));
        return centered.isPresent()
                ? centered
                : entries.stream().min(java.util.Comparator.comparingInt(node -> node.bounds().x()));
    }

    private static Optional<ViewSpec.NavigationNode> visibleCapeEntryTarget(ViewSpec view) {
        return view.scrollSurface("editor.capes").flatMap(surface -> view.navigationNodes().stream()
                .filter(ViewSpec.NavigationNode::enabled)
                .filter(node -> node.surfaceId().filter(surface.id()::equals).isPresent())
                .filter(node -> node.pattern() == ViewSpec.NavigationPattern.GRID)
                .filter(node -> node.bounds().bottom() > surface.viewport().y()
                        && node.bounds().y() < surface.viewport().bottom())
                .min(java.util.Comparator.comparingInt(ViewSpec.NavigationNode::tabOrder)));
    }

    private void requestRuntimeFocus(String screenId, String widgetId) {
        runtimeFocusToken = Math.max(1, runtimeFocusToken + 1);
        runtimeFocusScreenId = Objects.requireNonNull(screenId, "screenId");
        runtimeFocusWidgetId = Objects.requireNonNull(widgetId, "widgetId");
    }

    private void clearRuntimeFocus(String screenId) {
        if (Objects.equals(runtimeFocusScreenId, screenId)) {
            runtimeFocusScreenId = null;
            runtimeFocusWidgetId = null;
        }
    }

    private void applyNavigationScroll(ViewSpec.NavigationNode node, double offsetPixels) {
        String surfaceId = node.surfaceId().orElse(null);
        if ("gallery.cards".equals(surfaceId)) {
            double bounded = Math.max(0.0, Math.min(galleryMaximum(), offsetPixels));
            galleryFlow.navigateToOffset(bounded);
        } else if ("add.catalog".equals(surfaceId) && catalogImportFlow.addSource() != null) {
            int bounded = catalogImportFlow.addSourcePresenter().normalizedScrollOffset(
                    catalogImportFlow.addSource(),
                    viewportWidth,
                    viewportHeight,
                    (int) Math.round(offsetPixels),
                    viewChromeMetrics);
            catalogImportFlow.navigateToOffset(bounded);
        } else if ("providers.chooser".equals(surfaceId) && providerFlow.providerAdding()) {
            providerFlow.scrollChooserTo(Math.max(0, offsetPixels));
        } else if ("providers.rows".equals(surfaceId) && !providerFlow.providerAdding()) {
            providerFlow.scrollRowsTo(Math.max(0, offsetPixels));
        } else if ("external.review".equals(surfaceId)
                && catalogImportFlow.externalImport() != null
                && catalogImportFlow.externalImport().review().isPresent()) {
            catalogImportFlow.scrollReviewTo(Math.max(0, (int) Math.round(offsetPixels)));
        } else if ("editor.models".equals(surfaceId) && editorFlow.editor() != null) {
            editorFlow.navigateModelsTo(offsetPixels);
        } else if ("editor.capes".equals(surfaceId) && editorFlow.editor() != null) {
            editorFlow.navigateCapesTo(offsetPixels);
        }
    }

    public void dispatchText(String widgetId, String value) {
        Objects.requireNonNull(widgetId, "widgetId");
        Objects.requireNonNull(value, "value");
        onClient(() -> {
            clearFileImportError();
            if ("gallery.search".equals(widgetId)
                    && editorFlow.editor() == null
                    && catalogImportFlow.addSource() == null) {
                if (galleryFlow.galleryQuery().equals(value)) {
                    return;
                }
                galleryFlow.search(value);
                publish();
            } else if ("editor.cape_search".equals(widgetId)
                    && editorFlow.editor() != null && editorFlow.editor().capeCatalog() != null) {
                updateEditor(editor -> editor.withCapeCatalog(editor.capeCatalog().withQuery(value)));
                editorFlow.resetCapeSearchScroll();
            } else if ("editor.cape_action.name".equals(widgetId)
                    && editorFlow.editor() != null && editorFlow.editor().capeCatalog() != null) {
                updateEditor(editor -> editor.withCapeCatalog(editor.capeCatalog().rename(value)));
            } else if ("editor.name".equals(widgetId) && editorFlow.editor() != null) {
                if (editorFlow.editor().name().equals(value)) {
                    return;
                }
                editorFlow.renameDraft(value);
                publish();
            } else if ("add.catalog.search".equals(widgetId)
                    && catalogImportFlow.addSource() != null
                    && catalogImportFlow.addSource().personalSkinDeletion().isEmpty()) {
                if (catalogImportFlow.addSource().query().equals(value)) {
                    return;
                }
                catalogImportFlow.searchCatalog(value);
                resetAddSourceScroll();
                publish();
            } else if ("add.catalog.rename.name".equals(widgetId)
                    && catalogImportFlow.personalRenameHash() != null) {
                if (catalogImportFlow.personalRenameValue().equals(value)) {
                    return;
                }
                catalogImportFlow.editPersonalSkinName(value);
                publish();
            } else if ("add.player.input".equals(widgetId) && catalogImportFlow.addSource() != null) {
                if (catalogImportFlow.addSource().playerInput().equals(value)) {
                    return;
                }
                catalogImportFlow.editPlayerInput(value);
                publish();
            } else if ("add.url.input".equals(widgetId) && catalogImportFlow.addSource() != null) {
                if (catalogImportFlow.addSource().urlInput().equals(value)) {
                    return;
                }
                catalogImportFlow.editUrlInput(value);
                publish();
            }
        });
    }

    public void pointerPressed(double mouseX, double mouseY, int button) {
        onClient(() -> {
            clearFileImportError();
            if (editorFlow.editor() != null && editorFlow.editor().capeCatalog() != null && editorFlow.editor().capeCatalog().importError() != null) {
                editorFlow.clearCapeImportError();
                publish();
            }
            if (button != 0 || state.busy) {
                return;
            }
            if (state.providersOpen || !state.providers.galleryAvailable()) {
                if (providerFlow.providerAdding()) return;
                ViewSpec providerView = viewInternal(viewportWidth, viewportHeight, (int) mouseX, (int) mouseY);
                Optional<ViewSpec.Scrollbar> rowsScrollbar = providerView.scrollbar()
                        .filter(scrollbar -> scrollbar.track().contains(mouseX, mouseY));
                if (rowsScrollbar.isPresent()) {
                    ViewSpec.Scrollbar scrollbar = rowsScrollbar.orElseThrow();
                    providerFlow.grabScrollbar(scrollbar, mouseX, mouseY);
                    setProviderRowsFromScrollbar(scrollbar, mouseY - providerFlow.providerRowsScrollbarGrabOffset());
                    return;
                }
                providerFlow.beginPreviewRotation(mouseX, mouseY);
                publish();
                return;
            }
            if (editorFlow.editor() != null) {
                ViewSpec editorView = editorFlow.editor().present(
                        viewportWidth, viewportHeight, editorFlow.editorCapeScrollPosition(), editorFlow.editorModelScrollPosition(),
                        viewChromeMetrics);
                Optional<ViewSpec.Scrollbar> capeScrollbar = editorView.scrollbar()
                        .filter(scrollbar -> scrollbar.orientation()
                                == ViewSpec.Scrollbar.Orientation.VERTICAL)
                        .filter(scrollbar -> scrollbar.track().contains(mouseX, mouseY));
                if (capeScrollbar.isPresent()) {
                    ViewSpec.Scrollbar scrollbar = capeScrollbar.orElseThrow();
                    editorFlow.grabScrollbar(scrollbar, mouseX, mouseY);
                    setEditorContentPosition(editorPositionFromScrollbar(
                            viewportWidth,
                            viewportHeight,
                            mouseY - editorFlow.editorScrollbarGrabOffset()));
                    return;
                }
                Bounds previewBounds = editorView
                        .previews()
                        .get(0)
                        .anchorBounds();
                editorFlow.beginPreviewRotation(previewBounds, mouseX, mouseY);
                publish();
                return;
            }
            if (catalogImportFlow.addSource() != null) {
                if (catalogImportFlow.addSource().personalSkinDeletion().isPresent()) {
                    return;
                }
                ViewSpec addSource = catalogImportFlow.addSourcePresenter().present(
                        catalogImportFlow.addSource(),
                        state.busy,
                        Optional.empty(),
                        viewportWidth,
                        viewportHeight,
                        Optional.empty(),
                        viewChromeMetrics);
                addSource.scrollbar().ifPresent(scrollbar -> {
                    if (scrollbar.orientation() != ViewSpec.Scrollbar.Orientation.VERTICAL
                            || !scrollbar.track().contains(mouseX, mouseY)) {
                        return;
                    }
                    catalogImportFlow.grabScrollbar(scrollbar, mouseX, mouseY);
                    setAddSourceOffset(catalogImportFlow.addSourcePresenter().offsetFromScrollbar(
                            catalogImportFlow.addSource(),
                            viewportWidth,
                            viewportHeight,
                            mouseY - catalogImportFlow.addSourceScrollbarGrabOffset(),
                            viewChromeMetrics));
                });
                return;
            }
            ViewSpec gallery = galleryView(
                    viewportWidth, viewportHeight, (int) mouseX, (int) mouseY);
            gallery.scrollbar().ifPresent(scrollbar -> {
                if (!scrollbar.track().contains(mouseX, mouseY)) {
                    return;
                }
                boolean grabbedThumb = galleryFlow.grabScrollbar(scrollbar, mouseX, mouseY);
                if (!grabbedThumb) {
                    setGalleryPosition(galleryFlow.galleryPresenter().positionFromScrollbar(
                            snapshot, viewportWidth, viewportHeight, galleryFlow.galleryQuery(),
                            mouseX - galleryFlow.galleryScrollbarGrabOffset()));
                }
            });
        });
    }

    public void pointerDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        onClient(() -> {
            if (button != 0) {
                return;
            }
            if (providerFlow.draggingProviderRowsScrollbar()) {
                viewInternal(viewportWidth, viewportHeight, (int) mouseX, (int) mouseY).scrollbar()
                        .ifPresent(scrollbar -> setProviderRowsFromScrollbar(scrollbar,
                                mouseY - providerFlow.providerRowsScrollbarGrabOffset()));
            } else if (providerFlow.providerPreview().rotating()) {
                providerFlow.dragPreview(deltaX, deltaY);
                publish();
            } else if (editorFlow.draggingEditorScrollbar() && editorFlow.editor() != null) {
                setEditorContentPosition(editorPositionFromScrollbar(
                        viewportWidth,
                        viewportHeight,
                        mouseY - editorFlow.editorScrollbarGrabOffset()));
            } else if (editorFlow.editor() != null && editorFlow.editor().preview().rotating()) {
                editorFlow.dragPreview(deltaX, deltaY);
                publish();
            } else if (catalogImportFlow.draggingAddSourceScrollbar()
                    && catalogImportFlow.addSource() != null
                    && catalogImportFlow.addSource().personalSkinDeletion().isEmpty()) {
                setAddSourceOffset(catalogImportFlow.addSourcePresenter().offsetFromScrollbar(
                        catalogImportFlow.addSource(),
                        viewportWidth,
                        viewportHeight,
                        mouseY - catalogImportFlow.addSourceScrollbarGrabOffset(),
                        viewChromeMetrics));
            } else if (galleryFlow.draggingGalleryScrollbar()) {
                setGalleryPosition(galleryFlow.galleryPresenter().positionFromScrollbar(
                        snapshot, viewportWidth, viewportHeight, galleryFlow.galleryQuery(),
                        mouseX - galleryFlow.galleryScrollbarGrabOffset()));
            }
        });
    }

    public void pointerReleased(int button) {
        pointerReleased(Double.NaN, Double.NaN, button);
    }

    public void pointerReleased(double mouseX, double mouseY, int button) {
        onClient(() -> {
            if (button != 0) {
                return;
            }
            providerFlow.endPreviewRotation();
            providerFlow.releaseScrollbar();
            galleryFlow.releaseScrollbar();
            editorFlow.releaseScrollbar();
            catalogImportFlow.releaseScrollbar();
            if (editorFlow.editor() != null && editorFlow.editor().preview().rotating()) {
                editorFlow.endPreviewRotation();
                publish();
            }
        });
    }

    public void pointerScrolled(
            double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        onClient(() -> {
            if (state.providersOpen || !state.providers.galleryAvailable()) {
                if (providerFlow.providerAdding()) return;
                ViewSpec providerView = viewInternal(viewportWidth, viewportHeight, (int) mouseX, (int) mouseY);
                ViewSpec.ScrollSurface rows = providerView.scrollSurface("providers.rows").orElse(null);
                double amount = dominantScrollAmount(horizontalAmount, verticalAmount);
                if (rows != null && rows.viewport().contains(mouseX, mouseY) && amount != 0.0) {
                    providerFlow.scrollRowsTo(Math.max(0, Math.min(rows.maximumPixels(),
                            rows.offsetPixels() - amount * WHEEL_SCROLL_PIXELS)));
                    publish();
                    return;
                }
                providerFlow.scrollPreview(mouseX, mouseY, verticalAmount);
                publish();
                return;
            }
            if (editorFlow.editor() != null) {
                ViewSpec editorView = editorFlow.editor().present(
                        viewportWidth, viewportHeight, editorFlow.editorCapeScrollPosition(), editorFlow.editorModelScrollPosition(),
                        viewChromeMetrics);
                boolean editorGallery = editorView.clipRegions().stream()
                        .filter(region -> region.id().equals("editor.capes") || region.id().equals("editor.models"))
                        .map(ViewSpec.ClipRegion::bounds)
                        .anyMatch(bounds -> bounds.contains(mouseX, mouseY));
                double amount = dominantScrollAmount(horizontalAmount, verticalAmount);
                if (editorGallery && amount != 0.0) {
                    queueEditorContentScroll(-amount * WHEEL_SCROLL_PIXELS);
                    return;
                }
                Bounds previewBounds = editorView
                        .previews()
                        .get(0)
                        .bounds();
                PreviewInteractionModel changed = editorFlow.editor().preview()
                        .scroll(previewBounds, mouseX, mouseY, verticalAmount);
                if (changed != editorFlow.editor().preview()) {
                    editorFlow.acceptScrolledPreview(changed);
                    publish();
                }
                return;
            }
            if (catalogImportFlow.externalImport() != null && catalogImportFlow.externalImport().review().isPresent()) {
                ViewSpec reviewView = view(
                        viewportWidth, viewportHeight, (int) mouseX, (int) mouseY);
                double amount = dominantScrollAmount(horizontalAmount, verticalAmount);
                if (amount != 0.0
                        && PointerRouting.clipRegion(
                        reviewView,
                        "external.review.viewport",
                        mouseX,
                        mouseY)) {
                    ExternalImportModel.ReviewState review =
                            catalogImportFlow.externalImport().review().orElseThrow();
                    catalogImportFlow.scrollReviewTo(Math.max(0, review.scrollOffset() - (int) Math.round(amount * 32.0)));
                    publish();
                }
                return;
            }
            if (catalogImportFlow.addSource() != null) {
                if (catalogImportFlow.addSource().personalSkinDeletion().isPresent()) {
                    return;
                }
                ViewSpec addSourceView = view(
                        viewportWidth, viewportHeight, (int) mouseX, (int) mouseY);
                if (catalogImportFlow.addSource().selectedTab() == AddSourceTab.CATALOG
                        && PointerRouting.clipRegion(
                                addSourceView,
                                "add.catalog.viewport",
                                mouseX,
                                mouseY)
                        && (verticalAmount != 0.0 || horizontalAmount != 0.0)) {
                    double amount = verticalAmount != 0.0 ? verticalAmount : horizontalAmount;
                    queueAddSourceScroll(-amount * 32.0);
                }
                return;
            }
            int bottom = Math.max(150, viewportHeight - 64);
            double amount = dominantScrollAmount(horizontalAmount, verticalAmount);
            if (mouseY >= 38 && mouseY <= bottom && amount != 0.0) {
                queueGalleryScroll(-amount * WHEEL_SCROLL_PIXELS);
            }
        });
    }

    public void nativeScrollPositionChanged(String surfaceId, double offsetPixels) {
        Objects.requireNonNull(surfaceId, "surfaceId");
        if (!Double.isFinite(offsetPixels)) {
            throw new IllegalArgumentException("native scroll position must be finite");
        }
        onClient(() -> {
            switch (surfaceId) {
                case "gallery.cards" -> {
                    if (editorFlow.editor() != null || catalogImportFlow.addSource() != null) {
                        return;
                    }
                    setGalleryPosition(offsetPixels);
                }
                case "add.catalog" -> {
                    if (catalogImportFlow.addSource() == null
                            || catalogImportFlow.addSource().selectedTab() != AddSourceTab.CATALOG
                            || catalogImportFlow.addSource().personalSkinDeletion().isPresent()) {
                        return;
                    }
                    setAddSourceOffset((int) Math.round(offsetPixels));
                }
                case "providers.chooser" -> {
                    if (!providerFlow.providerAdding()) return;
                    providerFlow.scrollChooserTo(offsetPixels);
                    publish();
                }
                case "providers.rows" -> {
                    if (providerFlow.providerAdding() || !(state.providersOpen || !state.providers.galleryAvailable())) return;
                    providerFlow.scrollRowsTo(Math.max(0, offsetPixels));
                    publish();
                }
                case "external.review" -> {
                    if (catalogImportFlow.externalImport() == null || catalogImportFlow.externalImport().review().isEmpty()) {
                        return;
                    }
                    catalogImportFlow.scrollReviewTo(Math.max(0, (int) Math.round(offsetPixels)));
                    publish();
                }
                case "editor.models" -> {
                    if (editorFlow.editor() == null || editorFlow.editor().selectedEditorTab() != EditorTab.APPEARANCE) {
                        return;
                    }
                    setEditorContentPosition(offsetPixels);
                }
                case "editor.capes" -> {
                    if (editorFlow.editor() == null || editorFlow.editor().selectedEditorTab() != EditorTab.CAPE) {
                        return;
                    }
                    setEditorContentPosition(offsetPixels);
                }
                default -> {

                }
            }
        });
    }

    private void setProviderRowsFromScrollbar(ViewSpec.Scrollbar scrollbar, double thumbY) {
        providerFlow.setProviderRowsFromScrollbar(scrollbar, thumbY);
    }

    public CompletableFuture<Optional<byte[]>> loadSkinPreview(SkinReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (reference.optionalAssetId().isEmpty()) {
            return previewAssets.publishPreview(Optional.empty());
        }
        UUID skinId = reference.assetId();
        return previewAssets.requestPreview(
                "skin:" + skinId,
                () -> Optional.of(operations.loadSkinPreview(skinId)));
    }

    public CompletableFuture<Optional<byte[]>> loadSkinPreview(ViewSpec.Preview preview) {
        Objects.requireNonNull(preview, "preview");
        if (preview.imageRevision().startsWith("provider:skin:")) {
            return loadProviderTexture(new ViewSpec.ProviderTexture(preview.imageRevision().substring(14), true, false));
        }
        PresetEditorModel editor = editorFlow.editor();
        if ("editor.preview".equals(preview.id()) && editor != null && editor.png().isPresent()) {
            CompletableFuture<Optional<byte[]>> loaded =
                    previewAssets.publishPreview(Optional.of(editor.png().orElseThrow().bytes()));
            observeSkinPreview(preview, loaded, false);
            return loaded;
        }
        if (preview.catalogImage().isPresent()) {
            ViewSpec.CatalogImage image = preview.catalogImage().orElseThrow();
            SkinModel model = preview.variant() == SkinVariant.SLIM
                    ? SkinModel.SLIM
                    : SkinModel.CLASSIC;
            CompletableFuture<Optional<byte[]>> loaded = previewAssets.requestPreview(
                    "catalog:"
                            + previewAssets.catalogEpoch()
                            + ":"
                            + image.collectionId()
                            + ":"
                            + image.skinId()
                            + ":"
                            + model.name(),
                    () -> Optional.of(operations.loadCatalogSkin(
                            image.collectionId(), image.skinId(), model)));
            observeSkinPreview(preview, loaded, false);
            return loaded;
        }
        if (preview.externalImage().isPresent()) {
            String candidateId = preview.externalImage().orElseThrow().candidateId();
            Optional<ImportOperations.ExternalImportCandidate> candidate = catalogImportFlow.externalImport() == null
                    ? Optional.empty()
                    : catalogImportFlow.externalImport().candidate(candidateId);
            CompletableFuture<Optional<byte[]>> loaded = previewAssets.publishPreview(
                    candidate.map(ImportOperations.ExternalImportCandidate::normalizedPng));
            observeSkinPreview(preview, loaded, false);
            return loaded;
        }
        CompletableFuture<Optional<byte[]>> loaded = loadSkinPreview(preview.skin());
        observeSkinPreview(preview, loaded, preview.skin().optionalAssetId().isPresent());
        return loaded;
    }

    public CompletableFuture<Optional<byte[]>> loadProviderTexture(ViewSpec.ProviderTexture texture) {
        return previewAssets.requestPreview(texture.requestKey(), () -> operations.loadProviderTexture(texture.cacheKey(), texture.skin()));
    }

    public CompletableFuture<Optional<byte[]>> loadCapePreview(String capeId) {
        Objects.requireNonNull(capeId, "capeId");
        if (capeId.startsWith("provider:cape:")) {
            return loadProviderTexture(new ViewSpec.ProviderTexture(capeId.substring(14), false, false));
        }
        if (capeId.startsWith("resource:cape:") && editorFlow.editor() != null
                && editorFlow.editor().capeCatalog() != null && state.account != null) {
            Optional<CatalogRead.ResourceCapeSelection> selection =
                    editorFlow.editor().capeCatalog().cards().stream()
                            .filter(card -> card.texture().filter(capeId::equals).isPresent())
                            .map(CapeCatalogModel.Card::resource)
                            .filter(Objects::nonNull)
                            .findFirst();
            if (selection.isPresent()) {
                UUID accountId = state.account.accountId();
                return previewAssets.requestPreview(capeId, () -> operations.loadResourceCapePreview(
                        accountId, selection.orElseThrow()));
            }
            return previewAssets.publishPreview(Optional.empty());
        }
        return previewAssets.requestPreview("cape:" + capeId, () -> operations.loadCapePreview(capeId));
    }

    public CompletableFuture<Optional<byte[]>> loadCapePreview(ViewSpec.Preview preview) {
        Objects.requireNonNull(preview, "preview");
        String capeId = preview.capeId().orElseThrow(
                () -> new IllegalArgumentException("preview does not request a cape"));
        CompletableFuture<Optional<byte[]>> loaded = loadCapePreview(capeId);
        loaded.whenComplete((bytes, failure) -> {
            if (failure != null || bytes == null || bytes.isEmpty()) {
                reportCapePreviewFailure(preview);
            } else {
                clearEditorPreviewFailure(preview, "nclskins.error.cape_preview", true);
            }
        });
        return loaded;
    }

    public void reportSkinPreviewFailure(ViewSpec.Preview preview) {
        reportEditorPreviewFailure(preview, "nclskins.error.preview", false);
    }

    public void reportCapePreviewFailure(ViewSpec.Preview preview) {
        reportEditorPreviewFailure(preview, "nclskins.error.cape_preview", true);
    }

    private void observeSkinPreview(
            ViewSpec.Preview preview,
            CompletableFuture<Optional<byte[]>> loaded,
            boolean reportLoadFailure) {
        loaded.whenComplete((bytes, failure) -> {
            if (failure != null || bytes == null || bytes.isEmpty()) {
                if (reportLoadFailure) {
                    reportSkinPreviewFailure(preview);
                }
            } else {
                try {
                    SkinFeatureEvidence evidence = previewUsesStoredSkin(preview)
                            ? analyzeStoredSkinFeatureEvidence(bytes.orElseThrow())
                            : analyzeImportedSkinFeatureEvidence(bytes.orElseThrow());
                    onClient(() -> {
                        boolean changed = false;
                        if ("editor.preview".equals(preview.id())
                                && editorPreviewStillCurrent(preview)) {
                            changed = editorFlow.acceptPreviewEvidence(evidence);
                        }
                        Optional<UUID> assetId = preview.skin().optionalAssetId();
                        if (assetId.isPresent()
                                && preview.imageRevision().equals(
                                        "asset:" + assetId.orElseThrow())) {
                            changed |= !evidence.equals(state.assetEvidence.put(
                                    assetId.orElseThrow(), evidence));
                        }
                        Optional<ViewSpec.CatalogImage> catalogImage = preview.catalogImage();
                        if (catalogImage.isPresent()) {
                            ViewSpec.CatalogImage image = catalogImage.orElseThrow();
                            changed |= catalogImportFlow.acceptPreviewEvidence(image, preview.variant(), evidence);
                        }
                        if (changed && !disposed) {
                            publish();
                        }
                    });
                } catch (PngValidationException ignored) {
                }
                clearEditorPreviewFailure(preview, "nclskins.error.preview", false);
            }
        });
    }

    private boolean editorPreviewStillCurrent(ViewSpec.Preview preview) {
        return editorFlow.editorPreviewStillCurrent(preview);
    }

    private static boolean previewUsesStoredSkin(ViewSpec.Preview preview) {
        return preview.skin().optionalAssetId().isPresent()
                || preview.catalogImage()
                        .map(image -> PersonalSkinCatalog.isCollection(image.collectionId()))
                        .orElse(false);
    }

    private static SkinFeatureEvidence analyzeImportedSkinFeatureEvidence(byte[] pngBytes)
            throws PngValidationException {
        return new PngValidator().projectImport(pngBytes).featureEvidence();
    }

    private static SkinFeatureEvidence analyzeStoredSkinFeatureEvidence(byte[] pngBytes)
            throws PngValidationException {
        return new PngValidator().projectStoredRender(pngBytes).featureEvidence();
    }

    public void importSkin(String name, SkinVariant variant, byte[] pngBytes) {
        Objects.requireNonNull(variant, "variant");
        byte[] supplied = Objects.requireNonNull(pngBytes, "pngBytes");
        byte[] owned = supplied.length > PngValidator.DEFAULT_MAX_BYTES ? null : supplied.clone();
        submit(
                UiMessage.info("nclskins.status.saving"),
                () -> {
                    if (owned == null) {
                        throw new PngValidationException(PngValidationException.Reason.OVERSIZED,
                                "Skin exceeds the encoded texture limit");
                    }
                    return operations.importSkin(name, variant, new PngValidator().normalizeSkin(owned));
                },
                account -> {
                    Set<UUID> previous = state.account == null
                            ? Set.of()
                            : ids(state.account.skinAssets());
                    state.account = account;
                    galleryFlow.skinImported(account, previous);
                    state.status = UiMessage.success("nclskins.status.saved");
                });
    }

    public void renameSkin(UUID skinId, String name) {
        submitLocal(
                () -> operations.renameSkin(skinId, name),
                UiMessage.success("nclskins.status.saved"));
    }

    public void toggleSkinVariant(UUID skinId) {
        onClient(() -> {
            SkinAsset skin = findSkin(skinId);
            if (skin == null) {
                return;
            }
            SkinVariant next = skin.variant() == SkinVariant.CLASSIC ? SkinVariant.SLIM : SkinVariant.CLASSIC;
            submitLocal(
                    () -> operations.changeSkinVariant(skinId, next),
                    UiMessage.success("nclskins.status.saved"));
        });
    }

    public void duplicateSkin(UUID skinId, String name) {
        submitLocal(
                () -> operations.duplicateSkin(skinId, name),
                UiMessage.success("nclskins.status.saved"));
    }

    public void deleteSkin(UUID skinId) {
        submit(
                UiMessage.info("nclskins.status.deleting"),
                () -> operations.deleteSkin(skinId),
                account -> {
                    state.account = account;
                    galleryFlow.skinDeleted(skinId);
                    state.status = UiMessage.success("nclskins.status.deleted");
                });
    }

    public void resetLibrary() {
        submit(
                UiMessage.info("nclskins.status.loading"),
                operations::resetLibrary,
                data -> {
                    CompletableFuture<AppearanceRefreshCoordinator.Result> localRebind =
                            acceptInitialData(data, false);
                    if (operations.reconciliationRecommended(data)) {
                        reconcileAfterLocalRebind(
                                localRebind,
                                Trigger.LOCAL_INTENT);
                    }
                });
    }

    @Deprecated
    public void activateCape(String capeId) {
        Objects.requireNonNull(capeId, "capeId");
    }

    @Deprecated
    public void hideCape() {}

    @Override
    public void close() {
        onClient(() -> {
            if (disposed) {
                return;
            }
            cancelOptiFineAccountLink();
            cancelSkinMcAccountLink();
            cancelSneakyEditorLink();
            disposed = true;
            state.generation++;
            state.lifecycle = ClientSnapshot.Lifecycle.CLOSED;
            state.editorReturnsToProviders = false;
            state.busy = false;
            clearSessionRetryFeedback();
            editorFlow.closeDraft();
            catalogImportFlow.leaveForSavedPreset();
            appearanceRefresh.ifPresent(AppearanceRefreshCoordinator::close);
            serverAppearanceReadiness.ifPresent(ServerAppearanceReadinessCoordinator::close);
            capeObservations.closeOptiFineCapes();
            operations.close();
            previewAssets.close();

            listeners.clear();
            publish();
            if (ownedWorker != null) {
                ownedWorker.shutdownNow();
            }
            if (ownedReconciliationWorker != null) {
                ownedReconciliationWorker.shutdownNow();
            }
            if (ownedSessionWorker != null) {
                ownedSessionWorker.shutdownNow();
            }
            reconciliation.close();
            diagnostics.close();
        });
    }

    private void diagnose(DiagnosticEvent event, Throwable failure) {
        if (failure == null) {
            diagnostics.report(event, DiagnosticDetails::none);
        } else {
            diagnostics.report(event, () -> DiagnosticDetails.failure(failure));
        }
    }

    private void initializeOnClient() {
        ensureNotDisposed();
        if (state.lifecycle == ClientSnapshot.Lifecycle.INITIALIZING
                || state.lifecycle == ClientSnapshot.Lifecycle.READY
                || state.busy) {
            return;
        }
        if (!skinExtensionEnvironmentInitialized) {
            refreshSkinExtensionEnvironment();
        }
        if (!state.readyData && state.account == null) {
            operations.warmedInitialData()
                    .filter(this::currentSessionOwns)
                    .ifPresent(this::installInitialSeed);
        }
        if (state.account != null) {
            centerGalleryOnActive();
        }
        state.lifecycle = ClientSnapshot.Lifecycle.INITIALIZING;
        submit(
                UiMessage.info("nclskins.status.loading"),
                operations::initialize,
                data -> {
                    if (!currentSessionOwns(data)) {
                        closeScreenOnClient();
                        return;
                    }
                    acceptInitialData(data, true);
                    resolveRequestedDestination();
                });
    }

    private CompletableFuture<AppearanceRefreshCoordinator.Result> acceptSessionActivityData(
            ClientOperations.InitialData data, long baselineGeneration) {
        if (state.generation == baselineGeneration && !sameLocalInitialData(data)) {
            return acceptInitialData(data, false);
        }
        state.session = data.session();
        state.remoteProfile = data.session().profile();
        state.rateLimited = state.providers.minecraftEnabled() && operations.rateLimited();
        state.selectedCapeId = data.session().optionalProfile()
                .flatMap(RemoteProfile::activeCape)
                .map(cape -> cape.id())
                .orElse(null);
        if (!data.session().valid()) {
            state.status = sessionMessage(data.session());
        }
        return CompletableFuture.completedFuture(
                AppearanceRefreshCoordinator.Result.NOT_APPLICABLE);
    }

    private boolean sameLocalInitialData(ClientOperations.InitialData data) {
        return Objects.equals(state.account, data.account())
                && Objects.equals(
                        state.currentOfficialSkinId,
                        data.currentOfficialSkinId().orElse(null))
                && Objects.equals(state.activePresetId, data.activePresetId().orElse(null))
                && state.intentRevision == data.intentRevision()
                && state.syncStatus == data.syncStatus()
                && Objects.equals(state.providers, data.providers())
                && Objects.equals(
                        state.localAppearance,
                        data.localAppearance().orElse(null))
                && Objects.equals(state.uiPreferences, data.uiPreferences())
                && Objects.equals(state.ownedCapes, data.ownedCapes());
    }

    private CompletableFuture<AppearanceRefreshCoordinator.Result> acceptInitialData(
            ClientOperations.InitialData data, boolean initialized) {
        boolean gameChanged = !state.readyData || state.account == null
                || !state.account.accountId().equals(data.account().accountId())
                || !Objects.equals(state.localAppearance, data.localAppearance().orElse(null))
                || providerChainChanged(state.providers.skin(), data.providers().skin())
                || providerChainChanged(state.providers.cape(), data.providers().cape());
        if (state.account != null && !state.account.accountId().equals(data.account().accountId())) {
            cancelOptiFineAccountLink();
            cancelSkinMcAccountLink();
            cancelSneakyEditorLink();
            providerFlow.clearAccountLinkFeedback();
        }
        UUID previousActivePresetId = state.activePresetId;
        boolean sameGalleryAnchor = sameGalleryAnchor(data);
        state.lifecycle = ClientSnapshot.Lifecycle.READY;
        state.account = data.account();
        state.session = data.session();
        state.remoteProfile = data.session().profile();
        state.currentOfficialSkinId = data.currentOfficialSkinId().orElse(null);
        state.activePresetId = data.activePresetId().orElse(null);
        if ((initialized && !sameGalleryAnchor)
                || (!initialized && !Objects.equals(previousActivePresetId, state.activePresetId))) {
            centerGalleryOnActive();
        }
        state.providers = data.providers();
        capeObservations.startOptiFineCapes();
        adoptSharedCapeObservation(data.providers());
        state.intentRevision = data.intentRevision();
        state.syncStatus = data.syncStatus();
        state.uiPreferences = data.uiPreferences();
        providerFlow.restoreTab(data.uiPreferences().selectedProvidersTab());
        state.ownedCapes = data.ownedCapes();
        installWarmedCapePreviews(state.account.accountId(), false);
        state.readyData = true;
        if (operations.supportsAssetFeatureEvidence()) {
            refreshAssetFeatureEvidence(state.generation, state.account.accountId());
        }
        state.rateLimited = state.providers.minecraftEnabled() && operations.rateLimited();
        state.selectedCapeId = data.session().optionalProfile()
                .flatMap(RemoteProfile::activeCape)
                .map(cape -> cape.id())
                .orElse(null);
        if (!data.storageWarnings().isEmpty()) {
            state.status = UiMessage.literal(data.storageWarnings().get(0), UiMessage.Severity.ERROR);
        } else if (data.session().valid()) {
            state.status = initialized
                    ? UiMessage.success("nclskins.status.profile_loaded")
                    : UiMessage.success("nclskins.session.message.valid");
        } else {
            state.status = sessionMessage(data.session());
        }
        CompletableFuture<AppearanceRefreshCoordinator.Result> localRebind =
                gameChanged ? refreshLocalAppearance(data.localAppearance())
                        : CompletableFuture.completedFuture(
                                AppearanceRefreshCoordinator.Result.NOT_APPLICABLE);
        data.outerLayerVisibility().ifPresent(this::applyDurableOuterLayerVisibility);
        if (initialized || data.ownedCapes().capes().isEmpty()) {
            return localRebind;
        }
        long capeWarmupGeneration = state.generation;
                CompletableFuture.runAsync(() -> {
                    try {
                        operations.warmOwnedCapeCache();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        diagnose(DiagnosticEvent.CLIENT_CAPE_CACHE_FAILED, interrupted);
                    } catch (Exception failure) {
                        diagnose(DiagnosticEvent.CLIENT_CAPE_CACHE_FAILED, failure);
                    }
                }, worker)
                .thenRunAsync(() -> {
                    try {
                        Optional<OwnedCapeInventory> warmed = operations.ownedCapeInventory();
                        onClient(() -> {
                            if (!disposed && state.generation == capeWarmupGeneration) {
                                warmed.ifPresent(value -> {
                                    state.ownedCapes = value;
                                    editorFlow.ownedCapesLoaded(value);
                                });
                                installWarmedCapePreviews(state.account.accountId(), false);
                                publish();
                            }
                        });
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        diagnose(DiagnosticEvent.CLIENT_CAPE_CACHE_FAILED, interrupted);
                    } catch (Exception failure) {
                        diagnose(DiagnosticEvent.CLIENT_CAPE_CACHE_FAILED, failure);
                    }
                }, worker);
        return localRebind;
    }

    private void refreshAssetFeatureEvidence(long generation, UUID accountId) {
        CompletableFuture.supplyAsync(() -> {
                    try {
                        return operations.assetFeatureEvidence();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return Map.<UUID, SkinFeatureEvidence>of();
                    } catch (Exception unavailable) {
                        return Map.<UUID, SkinFeatureEvidence>of();
                    }
                }, worker)
                .thenAccept(evidence -> onClient(() -> {
                    if (disposed
                            || state.generation != generation
                            || state.account == null
                            || !state.account.accountId().equals(accountId)) {
                        return;
                    }
                    if (!state.assetEvidence.equals(evidence)) {
                        state.assetEvidence.clear();
                        state.assetEvidence.putAll(evidence);
                        publish();
                    }
                }));
    }

    private boolean currentSessionOwns(ClientOperations.InitialData data) {
        return data.account().accountId().equals(operations.sessionIdentity().profileId());
    }

    private void installInitialSeed(ClientOperations.InitialData data) {
        state.account = data.account();
        state.session = data.session();
        state.remoteProfile = data.session().profile();
        state.currentOfficialSkinId = data.currentOfficialSkinId().orElse(null);
        state.activePresetId = data.activePresetId().orElse(null);
        state.providers = data.providers();
        state.intentRevision = data.intentRevision();
        state.syncStatus = data.syncStatus();
        state.uiPreferences = data.uiPreferences();
        providerFlow.restoreTab(data.uiPreferences().selectedProvidersTab());
        state.ownedCapes = data.ownedCapes();
        state.selectedCapeId = data.session().optionalProfile()
                .flatMap(RemoteProfile::activeCape)
                .map(cape -> cape.id())
                .orElse(null);
    }

    private boolean sameGalleryAnchor(ClientOperations.InitialData data) {
        return state.account != null
                && state.account.presets().equals(data.account().presets())
                && Objects.equals(state.activePresetId, data.activePresetId().orElse(null));
    }

    private void dispatchProviderWidget(String id) {
        providerFlow.dispatchProviderWidget(id);
    }

    private void dispatchProviderWidget(String id, InteractionOrigin origin) {
        providerFlow.dispatchProviderWidget(id, origin);
    }

    private void prepareOptiFineAccountLink() {
        providerFlow.prepareOptiFineAccountLink();
    }

    private void submitProviderConfiguration(ThrowingSupplier<ClientOperations.DurableAppearance> operation,
            Consumer<ClientOperations.DurableAppearance> completion) {
        providerFlow.submitProviderConfiguration(operation, completion);
    }

    private void acceptProviderSnapshot(ClientOperations.DurableAppearance appearance) {
        if (!currentSessionOwns(appearance)
                || appearance.providers().skin().configurationRevision() < state.providers.skin().configurationRevision()
                || appearance.providers().cape().configurationRevision() < state.providers.cape().configurationRevision()
                || appearance.intentRevision() < state.intentRevision) return;
        state.providers = appearance.providers();
        publish();
    }

    private CompletableFuture<AppearanceRefreshCoordinator.Result> acceptProviderChange(
            ClientOperations.DurableAppearance appearance) {
        if (!currentSessionOwns(appearance)) return CompletableFuture.completedFuture(
                AppearanceRefreshCoordinator.Result.NOT_APPLICABLE);
        boolean gameChanged = providerFlow.providerChainChanged(state.providers.skin(), appearance.providers().skin())
                || providerFlow.providerChainChanged(state.providers.cape(), appearance.providers().cape());
        boolean localChanged = !Objects.equals(state.localAppearance,
                appearance.localAppearance().orElse(null));
        if (!appearance.providers().cape().enabled(BuiltinProvider.OPTIFINE)) providerFlow.cancelOptiFineAccountLink();
        if (!appearance.providers().cape().enabled(BuiltinProvider.SKINMC)) providerFlow.cancelSkinMcAccountLink();
        if (!appearance.providers().cape().enabled(BuiltinProvider.SNEAKY)) providerFlow.cancelSneakyEditorLink();
        state.providers = appearance.providers();
        state.intentRevision = appearance.intentRevision();
        state.syncStatus = appearance.syncStatus();
        CompletableFuture<AppearanceRefreshCoordinator.Result> rebind = gameChanged || localChanged
                ? refreshLocalAppearance(appearance.localAppearance())
                : CompletableFuture.completedFuture(AppearanceRefreshCoordinator.Result.NOT_APPLICABLE);
        observeRateLimit();
        publish();
        return rebind;
    }

    private boolean providerChainChanged(
            com.naocraftlab.skins.core.provider.ProviderChannel<?> before,
            com.naocraftlab.skins.core.provider.ProviderChannel<?> after) {
        return providerFlow.providerChainChanged(before, after);
    }

    private void adoptSharedCapeObservation(AppearanceProviders providers) {
        providerFlow.adoptSharedCapeObservation(providers);
    }

    private RefreshComparison captureRefreshComparison(AppearanceProviders.Component component) {
        return providerFlow.captureRefreshComparison(component);
    }

    private void finishRefreshComparison(RefreshComparison comparison,
            AppearanceProviders confirmedState, ProviderObservation<?> confirmedMinecraft,
            ProviderObservation<ProviderCape> optifine,
            ProviderObservation<ProviderCape> skinmc) {
        providerFlow.finishRefreshComparison(comparison, confirmedState, confirmedMinecraft, optifine, skinmc);
    }

    private boolean sameCapeContent(ProviderCape left, ProviderCape right) {
        return providerFlow.sameCapeContent(left, right);
    }

    private void dispatchWidgetOnClient(
            String widgetId, boolean reverse, InteractionOrigin origin) {
        ensureNotDisposed();
        clearFileImportError();
        if (state.lifecycle == ClientSnapshot.Lifecycle.CLOSED) {
            return;
        }
        if (dispatchProviderAction(widgetId, reverse, origin)) return;
        if (dispatchGalleryAction(widgetId, reverse, origin)) return;
        if (dispatchCatalogImportAction(widgetId, reverse, origin)) return;
        dispatchEditorAction(widgetId, reverse, origin);
    }

    private void cycleEditorOuterLayer(String action, boolean reverse) {
        editorFlow.cycleEditorOuterLayer(action, reverse);
    }

    private void retainKeyboardFocus(
            InteractionOrigin origin, String screenId, String widgetId) {
        if (!origin.keyboard()) {
            return;
        }
        ViewSpec updated = viewInternal(viewportWidth, viewportHeight, 0, 0);
        if (updated.screenId().equals(screenId) && updated.widget(widgetId).isPresent()) {
            requestRuntimeFocus(screenId, widgetId);
            publish();
        }
    }

    private void dispatchPresetWidget(String widgetId, InteractionOrigin origin) {
        galleryFlow.dispatchPresetWidget(widgetId, origin);
    }

    private void selectGalleryCard(String widgetId, InteractionOrigin origin) {
        galleryFlow.selectGalleryCard(widgetId, origin);
    }

    private void openAddSource() {
        openAddSource(null);
    }

    private void openAddSource(AddSourceTab override) {
        if (state.busy || state.account == null) {
            return;
        }
        galleryFlow.dismissDeletion();
        clearRuntimeFocus("gallery");
        editorFlow.prepareNewPresetName(galleryFlow.suggestedPresetName());
        previewAssets.invalidateCatalogPreviews();
        AccountUiPreferences cachedPreferences = state.uiPreferences == null
                ? AccountUiPreferences.defaults(state.account.accountId())
                : state.uiPreferences;
        UUID accountId = state.account.accountId();

        SkinVariant fallbackVariant = currentPlayerVariant();
        SkinExtensionEnvironment catalogEnvironment = skinExtensionEnvironment;
        boolean hideIncompatibleCatalogSkins = snapshot.hideIncompatibleCatalogSkins();
        submit(
                UiMessage.info("nclskins.status.loading"),
                () -> {
                    AccountUiPreferences latest = cachedPreferences;
                    try {
                        latest = operations.loadUiPreferences()
                                .filter(preferences -> preferences.accountId().equals(accountId))
                                .orElse(cachedPreferences);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        diagnose(DiagnosticEvent.CLIENT_PREFERENCES_LOAD_FAILED, interrupted);
                    } catch (Exception failure) {
                        diagnose(DiagnosticEvent.CLIENT_PREFERENCES_LOAD_FAILED, failure);
                    }
                    List<SkinCatalogSource.CollectionDescriptor> collections =
                            operations.catalogCollections();
                    return new AddSourceData(
                            latest, collections, operations.catalogFeatureEvidence());
                },
                data -> {
                    if (!accountId.equals(operations.sessionIdentity().profileId())) {
                        closeScreenOnClient();
                        return;
                    }
                    state.uiPreferences = data.preferences();
                    catalogImportFlow.openCatalog(data, fallbackVariant, catalogEnvironment, hideIncompatibleCatalogSkins);
                    galleryFlow.acceptDraftSelection(null);
                    state.status = UiMessage.info("nclskins.external_import.choose_source");
                    if (override != null) selectAddSourceTab(override);
                },
                failure -> {
                    if (!accountId.equals(operations.sessionIdentity().profileId())) {
                        closeScreenOnClient();
                        return;
                    }
                    catalogImportFlow.openEmptyCatalog(cachedPreferences, fallbackVariant, catalogEnvironment, hideIncompatibleCatalogSkins);
                    galleryFlow.acceptDraftSelection(null);
                    state.status = UiMessage.info("nclskins.external_import.choose_source");
                    if (override != null) selectAddSourceTab(override);
                });
    }

    private void selectAddSourceTab(AddSourceTab tab) {
        catalogImportFlow.selectAddSourceTab(tab);
    }

    private void cycleCatalogFilter(boolean reverse) {
        catalogImportFlow.cycleCatalogFilter(reverse);
    }

    private void toggleCatalogCollection(String collectionId) {
        catalogImportFlow.toggleCatalogCollection(collectionId);
    }

    private void toggleAllCatalogCollections(
            InteractionOrigin origin, String widgetId) {
        catalogImportFlow.toggleAllCatalogCollections(origin, widgetId);
    }

    private void selectCatalogSkin(String encodedId) {
        catalogImportFlow.selectCatalogSkin(encodedId);
    }

    private CatalogSelection loadCatalogSelection(
            SkinCatalogSource.CollectionDescriptor collection,
            SkinCatalogSource.SkinDescriptor skin,
            SkinVariant initialVariant) throws Exception{
        return catalogImportFlow.loadCatalogSelection(collection, skin, initialVariant);
    }

    private Optional<String> resolvedCatalogText(
            Optional<com.naocraftlab.skins.client.CatalogText> value) {
        return catalogImportFlow.resolvedCatalogText(value);
    }

    private void chooseAddSourcePng() {
        catalogImportFlow.chooseAddSourcePng();
    }

    private void openExternalImport(ExternalImportModel.Category category) {
        if (state.busy
                || catalogImportFlow.addSource() == null
                || catalogImportFlow.addSource().selectedTab() != AddSourceTab.FILE) {
            return;
        }
        catalogImportFlow.openExternalCategory(category);
        submit(
                UiMessage.info("nclskins.external_import.searching"),
                () -> {
                    EnumMap<ExternalImportSource, ExternalImportProbe> probes =
                            new EnumMap<>(ExternalImportSource.class);
                    for (ExternalImportSource source : category.sources()) {
                        ExternalImportProbe available;
                        try {
                            available = operations.probeExternalSource(source, Optional.empty());
                        } catch (Exception ignored) {
                            available = ExternalImportProbe.UNAVAILABLE;
                        }
                        probes.put(source, available);
                    }
                    return Map.copyOf(probes);
                },
                probes -> {
                    if (catalogImportFlow.externalImport() != null
                            && catalogImportFlow.externalImport().category() == category) {
                        catalogImportFlow.acceptAutomaticProbes(probes);
                        state.status = UiMessage.info("nclskins.external_import.choose_source");
                    }
                },
                failure -> state.status = UiMessage.error("nclskins.external_import.probe_failed"));
    }

    private void prepareExternalImport(ExternalImportSource source) {
        catalogImportFlow.prepareExternalImport(source);
    }

    private void chooseExternalImportFolder(ExternalImportSource source) {
        catalogImportFlow.chooseExternalImportFolder(source);
    }

    private void finishExternalDirectoryPicker(
            long ticket,
            ExternalImportSource source,
            Path ignored,
            Throwable failure) {
        catalogImportFlow.finishExternalDirectoryPicker(new ScreenOperationTicket(ticket), source, ignored, failure);
    }

    private void toggleExternalCandidate(String candidateId) {
        catalogImportFlow.toggleExternalCandidate(candidateId);
    }

    private void toggleExternalCollection(boolean duplicates) {
        catalogImportFlow.toggleExternalCollection(duplicates);
    }

    private void toggleAllExternalCollections() {
        catalogImportFlow.toggleAllExternalCollections();
    }

    private void toggleAllExternalCandidates() {
        catalogImportFlow.toggleAllExternalCandidates();
    }

    private void commitExternalImport() {
        catalogImportFlow.commitExternalImport();
    }

    private void finishExternalImport(ImportOperations.ExternalImportResult result) {
        catalogImportFlow.finishExternalImport(result);
    }

    private void failExternalPreparation(ExternalImportSource source, Throwable failure) {
        catalogImportFlow.failExternalPreparation(source, failure);
    }

    private String invalidFolderKey(ExternalImportSource source) {
        return catalogImportFlow.invalidFolderKey(source);
    }

    private void cancelExternalReview() {
        catalogImportFlow.cancelExternalReview();
    }

    private void cancelExternalImport() {
        catalogImportFlow.cancelExternalImport();
    }

    private void loadRemoteImport(boolean player) {
        catalogImportFlow.loadRemoteImport(player);
    }

    private boolean openImportedDraft(
            ImportOperations.ImportDraft draft,
            String sourceName,
            boolean useSuggestedPresetName) {
        return catalogImportFlow.openImportedDraft(draft, sourceName, useSuggestedPresetName);
    }

    private void finishAddSourcePicker(long ticket, byte[] ignored, Throwable failure) {
        catalogImportFlow.finishAddSourcePicker(new ScreenOperationTicket(ticket), ignored, failure);
    }

    private void requestPersonalSkinDeletion(
            String collectionId, String sha256, InteractionOrigin origin) {
        catalogImportFlow.requestPersonalSkinDeletion(collectionId, sha256, origin);
    }

    private void requestPersonalSkinRename(String collectionId, String sha256) {
        catalogImportFlow.requestPersonalSkinRename(collectionId, sha256);
    }

    private void cancelPersonalSkinRename() {
        catalogImportFlow.cancelPersonalSkinRename();
    }

    private void savePersonalSkinRename() {
        catalogImportFlow.savePersonalSkinRename();
    }

    private void cancelPersonalSkinDeletion(InteractionOrigin origin) {
        catalogImportFlow.cancelPersonalSkinDeletion(origin);
    }

    private void confirmPersonalSkinDeletion(InteractionOrigin origin) {
        catalogImportFlow.confirmPersonalSkinDeletion(origin);
    }

    private void cancelAddSource() {
        catalogImportFlow.cancelAddSource();
    }

    private boolean personalCatalogInteractionBelongsTo(String collectionId) {
        return catalogImportFlow.personalCatalogInteractionBelongsTo(collectionId);
    }

    private void resetPersonalCatalogInteraction() {
        catalogImportFlow.resetPersonalCatalogInteraction();
    }

    private CompletableFuture<Void> uiPreferenceWrite = CompletableFuture.completedFuture(null);

    private void persistUiPreference(ThrowingSupplier<Void> operation) {
        uiPreferenceWrite = uiPreferenceWrite.handle((ignored, failure) -> null).thenRunAsync(() -> {
            try {
                operation.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                diagnose(DiagnosticEvent.CLIENT_PREFERENCES_SAVE_FAILED, interrupted);
            } catch (Exception failure) {
                diagnose(DiagnosticEvent.CLIENT_PREFERENCES_SAVE_FAILED, failure);
            }
        }, worker);
    }

    private void selectEditorTab(EditorTab tab) {
        editorFlow.selectEditorTab(tab);
    }

    private Optional<com.naocraftlab.skins.core.model.RemoteProfile> editorProfile() {
        return editorFlow.editorProfile();
    }

    private List<com.naocraftlab.skins.core.model.OwnedCapeEntry> editorOwnedCapes() {
        return editorFlow.editorOwnedCapes();
    }

    private void openEditor(UUID presetId) {
        if (state.busy || state.account == null) {
            return;
        }
        galleryFlow.dismissDeletion();
        clearRuntimeFocus("gallery");
        PresetEditorModel editor = createEditor(presetId);
        if (editor == null) {
            state.status = UiMessage.error("nclskins.gallery.prepare_failed");
            publish();
            return;
        }
        catalogImportFlow.leaveForSavedPreset();
        state.editorReturnsToProviders = false;
        LocalCapeReference offlineSeed = state.account.presets().stream()
                .filter(preset -> preset.id().equals(presetId))
                .findFirst()
                .map(AppearancePreset::offlineCape)
                .orElse(null);
        editorFlow.acceptDraft(new EditorDraftTransfer(editor, offlineSeed, Optional.empty(), presetId));
        galleryFlow.acceptDraftSelection(presetId);
        publish();
    }

    private PresetEditorModel createEditor(UUID presetId) {
        return editorFlow.createEditor(presetId);
    }

    private boolean dispatchCapeCatalog(String id, boolean reverse, InteractionOrigin origin) {
        return editorFlow.dispatchCapeCatalog(id, reverse, origin);
    }

    private void persistCapeDisclosure(Set<String> before, Set<String> after) {
        editorFlow.persistCapeDisclosure(before, after);
    }

    private void reloadEditorCatalog() {
        editorFlow.reloadEditorCatalog();
    }

    private void refreshCapeCatalog(com.naocraftlab.skins.core.model.LocalCapeReference local, Optional<String> owned) {
        editorFlow.refreshCapeCatalog(local, owned);
    }

    private void chooseCapePng() {
        editorFlow.chooseCapePng();
    }

    private void chooseEditorPng() {
        editorFlow.chooseEditorPng();
    }

    private void finishEditorPicker(long ticket, byte[] ignored, Throwable failure) {
        editorFlow.finishEditorPicker(new ScreenOperationTicket(ticket), ignored, failure);
    }

    private void saveEditor() {
        editorFlow.saveEditor();
    }

    private void cancelEditor() {
        editorFlow.cancelEditor();
    }

    private void returnFromEditor() {
        if (state.rootDestination == ScreenDestination.ACTIVE_EDITOR) {
            closeScreenOnClient();
            return;
        }
        if (state.editorReturnsToProviders) {
            state.providersOpen = true;
            providerFlow.leaveChooser();
            selectProvidersTab(AppearanceProviders.Component.CAPE);
            providerFlow.invalidateCapePreview();
        }
        state.editorReturnsToProviders = false;
    }

    private void closeToProviders() {
        state.galleryReturnsToProviders = false;
        galleryFlow.dismissDeletion();
        galleryFlow.clearFocusedCard();
        clearRuntimeFocus("gallery");
        state.providersOpen = true;
        providerFlow.leaveChooser();
        providerFlow.restoreTab(state.uiPreferences == null ? AppearanceProviders.Component.SKIN
                : state.uiPreferences.selectedProvidersTab());
        providerFlow.invalidateSkinPreview();
        publish();
    }

    private PresetEditorModel applyPendingPresetName(PresetEditorModel editor) {
        return editorFlow.applyPendingPresetName(editor);
    }

    private void requestPresetDeletion(UUID presetId, InteractionOrigin origin) {
        galleryFlow.requestPresetDeletion(presetId, origin);
    }

    private void cancelPresetDeletion(UUID presetId, InteractionOrigin origin) {
        galleryFlow.cancelPresetDeletion(presetId, origin);
    }

    private void duplicatePreset(UUID presetId) {
        galleryFlow.duplicatePreset(presetId);
    }

    private void deletePreset(UUID presetId, InteractionOrigin origin) {
        galleryFlow.deletePreset(presetId, origin);
    }

    private void applyPreset(UUID presetId, boolean preserveGalleryOffset) {
        galleryFlow.applyPreset(presetId, preserveGalleryOffset);
    }

    private void acceptPresetUse(LibraryEditorPort.PresetUse use, boolean preserveGalleryOffset) {
        galleryFlow.acceptPresetUse(use, preserveGalleryOffset);
    }

    private static boolean automaticCheckpointEligible(AppearanceSyncStatus status) {
        return status == AppearanceSyncStatus.PENDING
                || status == AppearanceSyncStatus.ATTEMPTING;
    }

    private CompletableFuture<AppearanceRefreshCoordinator.Result> refreshLocalAppearance(
            Optional<AppliedAppearance> appearance) {
        state.localAppearance = appearance.orElse(null);
        if (appearanceRefresh.isEmpty() || appearance.isEmpty()) {
            return CompletableFuture.completedFuture(
                    AppearanceRefreshCoordinator.Result.NOT_APPLICABLE);
        }
        return appearanceRefresh.orElseThrow()
                .afterReconnect(appearance.orElseThrow(), ignored -> {});
    }

    private void refreshLocalAppearance(AppliedAppearance appearance) {
        refreshLocalAppearance(Optional.of(Objects.requireNonNull(appearance, "appearance")));
    }

    private void reconcileAfterLocalRebind(
            CompletableFuture<AppearanceRefreshCoordinator.Result> localRebind,
            Trigger trigger) {
        currentReconciliationKey().ifPresent(key ->
                reconcileAfterLocalRebind(localRebind, key, trigger));
    }

    private Optional<ClientOperations.ReconciliationKey> currentReconciliationKey() {
        if (state.account == null) {
            return Optional.empty();
        }
        return Optional.of(new ClientOperations.ReconciliationKey(
                state.account.accountId(), state.intentRevision,
                state.providers.skin().minecraftDelivery().activation(),
                state.providers.cape().minecraftDelivery().activation()));
    }

    private ClientOperations.ReconciliationKey reconciliationKey(
            ClientOperations.DurableAppearance appearance) {
        return appearance.reconciliationKey();
    }

    private boolean currentSessionOwns(ClientOperations.DurableAppearance appearance) {
        return appearance.accountId().equals(operations.sessionIdentity().profileId());
    }

    private void reconcileAfterLocalRebind(
            CompletableFuture<AppearanceRefreshCoordinator.Result> localRebind,
            ClientOperations.ReconciliationKey key,
            Trigger trigger) {
        Objects.requireNonNull(localRebind, "localRebind").whenComplete(
                (ignored, failure) -> onClient(() -> {
                    if (state.providers.minecraftEnabled() && operations.rateLimitRemaining().isPresent()) {
                        armRateLimitRecovery();
                        publish();
                        return;
                    }
                    requestAppearanceReconciliation(key, trigger);
                }));
    }

    private void requestAppearanceReconciliation(
            Trigger trigger) {
        currentReconciliationKey().ifPresent(key ->
                requestAppearanceReconciliation(key, trigger));
    }

    private void requestAppearanceReconciliation(
            ClientOperations.ReconciliationKey key,
            Trigger trigger) {
        if (disposed) return;
        state.syncInProgress = true;
        publish();
        reconciliation.request(key, trigger);
    }

    private void acceptAppearanceReconciliation(
            AccountReconciliationCoordinator.Request request,
            Optional<ClientOperations.ReconciliationResult> result,
            Optional<ClientOperations.DurableAppearance> durableAfterFailure,
            Throwable failure) {
        UUID currentUserId;
        try {
            currentUserId = operations.sessionIdentity().profileId();
        } catch (RuntimeException unavailableUser) {
            currentUserId = null;
        }
        boolean currentExactAccount = request.key().accountId().equals(currentUserId)
                && (state.account == null || state.account.accountId().equals(currentUserId));
        boolean exactAccountResult = result
                .map(ClientOperations.ReconciliationResult::account)
                .map(AccountState::accountId)
                .filter(request.key().accountId()::equals)
                .isPresent();
        boolean confirmedRemoteChange = failure == null
                ? result.flatMap(ClientOperations.ReconciliationResult::outcome)
                        .map(outcome -> outcome.remoteAppearanceImpact()
                                == com.naocraftlab.skins.core.service.RemoteAppearanceImpact.CONFIRMED_CHANGED)
                        .orElse(false)
                : remoteAppearanceMayHaveChanged(failure);
        if (currentExactAccount
                && state.providers.minecraftEnabled()
                && confirmedRemoteChange
                && (failure != null || exactAccountResult)) {
            serverAppearanceReadiness.ifPresent(coordinator -> {
                try {
                    coordinator.start();
                } catch (RuntimeException readinessFailure) {
                    diagnose(DiagnosticEvent.CLIENT_READINESS_FAILED, readinessFailure);
                }
            });
        }
        if (disposed) {
            return;
        }
        if (result.isEmpty()) {
            durableAfterFailure.ifPresent(appearance ->
                    acceptDurableAfterReconciliationFailure(
                            request, appearance, currentExactAccount));
            return;
        }
        ClientOperations.ReconciliationResult reconciled = result.orElseThrow();
        ClientOperations.DurableAppearance appearance = reconciled.appearance();
        if (!currentExactAccount
                || !exactAccountResult
                || appearance.intentRevision() < request.key().intentRevision()
                || state.account != null
                        && (!state.account.accountId().equals(reconciled.account().accountId())
                                || state.intentRevision > appearance.intentRevision())) {
            return;
        }
        UUID previousActivePresetId = state.activePresetId;
        state.account = reconciled.account();
        state.session = reconciled.session();
        state.remoteProfile = reconciled.session().profile();
        state.currentOfficialSkinId = reconciled.currentOfficialSkinId().orElse(null);
        state.activePresetId = appearance.activePresetId().orElse(null);
        state.providers = appearance.providers();
        state.intentRevision = appearance.intentRevision();
        state.syncStatus = appearance.syncStatus();
        appearance.localAppearance().ifPresent(this::refreshLocalAppearance);
        appearance.outerLayerVisibility().ifPresent(this::applyDurableOuterLayerVisibility);
        reconciled.outcome().ifPresent(outcome -> {
            PresetApplicationOutcome visible = withoutLegacyRestore(outcome);
            state.lastMutation = visible;
            state.status = mutationMessage(visible, operations.rateLimited());
        });
        if (reconciled.outcome().isEmpty()
                && appearance.syncStatus() == AppearanceSyncStatus.PENDING
                && state.lifecycle != ClientSnapshot.Lifecycle.INITIALIZING) {
            state.status = UiMessage.info("nclskins.status.local_only");
        }
        centerGalleryIfActiveChanged(previousActivePresetId);
        publish();
    }

    private void acceptDurableAfterReconciliationFailure(
            AccountReconciliationCoordinator.Request request,
            ClientOperations.DurableAppearance appearance,
            boolean currentExactAccount) {
        if (!currentExactAccount
                || !appearance.accountId().equals(request.key().accountId())
                || appearance.intentRevision() < request.key().intentRevision()
                || state.account != null
                        && (state.account.accountId().equals(appearance.accountId())
                                && state.intentRevision > appearance.intentRevision())) {
            return;
        }
        UUID previousActivePresetId = state.activePresetId;
        state.activePresetId = appearance.activePresetId().orElse(null);
        state.providers = appearance.providers();
        state.intentRevision = appearance.intentRevision();
        state.syncStatus = appearance.syncStatus();
        appearance.localAppearance().ifPresent(this::refreshLocalAppearance);
        appearance.outerLayerVisibility().ifPresent(this::applyDurableOuterLayerVisibility);
        centerGalleryIfActiveChanged(previousActivePresetId);
        publish();
    }

    private void finishAppearanceReconciliation() {
        if (disposed) {
            return;
        }
        if (reconciliation.busy()) return;
        state.syncInProgress = false;
        publish();
    }

    private void applyDurableOuterLayerVisibility(
            com.naocraftlab.skins.client.OuterLayerVisibility visibility) {
        outerLayerVisibilityController.ifPresent(controller -> controller.applyDurable(visibility));
    }

    private void retrySelectedCape() {
        if (!state.busy) {
            if (operations.rateLimitRemaining().isPresent()) {
                armRateLimitRecovery();
                publish();
                return;
            }
            requestAppearanceReconciliation(
                    Trigger.EXPLICIT_RETRY);
        }
    }

    private void retrySession() {
        if (!canRetrySession()) {
            return;
        }
        if (operations.rateLimitRemaining().isPresent()) {
            armRateLimitRecovery();
            publish();
            return;
        }
        long ticket = ++sessionActivitySequence;
        sessionActivityTicket = ticket;
        sessionActivityBaselineGeneration = state.generation;
        sessionActivityAccountId = state.account.accountId();
        state.sessionActivity = ClientSnapshot.SessionActivity.RECONNECTING;
        state.status = UiMessage.info("nclskins.status.checking_session");
        sessionRetryTicket = ticket;
        sessionRetryFeedbackRendered = false;
        sessionRetryFeedbackTicksRemaining = SESSION_RETRY_FEEDBACK_TICKS;
        pendingSessionRetrySettlement = null;
        publish();
        CompletableFuture.supplyAsync(() -> {
                    try {
                        return operations.retrySession();
                    } catch (Exception failure) {
                        throw new CompletionException(failure);
                    }
                }, sessionWorker)
                .whenComplete((result, failure) -> onClient(() ->
                        stageSessionRetrySettlement(ticket, result, failure)));
    }

    private boolean canRetrySession() {
        if (disposed
                || state.busy
                || state.sessionActivity != ClientSnapshot.SessionActivity.NONE
                || state.syncInProgress
                || state.lifecycle == ClientSnapshot.Lifecycle.CLOSED
                || state.account == null
                || (state.session != null && state.session.restartRequired())) {
            return false;
        }
        return snapshot.gallerySessionPresentation()
                == ClientSnapshot.GallerySessionPresentation.OFFLINE_RETRY;
    }

    private void stageSessionRetrySettlement(
            long ticket, ClientOperations.InitialData result, Throwable failure) {
        if (!currentSessionActivity(ticket)) {
            clearSessionRetryFeedback(ticket);
            return;
        }
        SessionRetrySettlement settlement = failure == null
                ? SessionRetrySettlement.success(ticket, Objects.requireNonNull(
                result, "session retry result"))
                : SessionRetrySettlement.failure(ticket, failure);
        if (!sessionRetryFeedbackRendered || sessionRetryFeedbackTicksRemaining > 0) {
            pendingSessionRetrySettlement = settlement;
            return;
        }
        applySessionRetrySettlement(settlement);
    }

    private void acknowledgeSessionRetryFeedbackRendered() {
        if (disposed
                || sessionRetryTicket < 0
                || state.sessionActivity != ClientSnapshot.SessionActivity.RECONNECTING) {
            return;
        }
        sessionRetryFeedbackRendered = true;
    }

    private void advanceSessionRetryFeedback() {
        if (sessionRetryTicket < 0
                || !sessionRetryFeedbackRendered
                || sessionRetryFeedbackTicksRemaining <= 0) {
            return;
        }
        sessionRetryFeedbackTicksRemaining--;
        if (sessionRetryFeedbackTicksRemaining == 0
                && pendingSessionRetrySettlement != null) {
            SessionRetrySettlement settlement = pendingSessionRetrySettlement;
            pendingSessionRetrySettlement = null;
            applySessionRetrySettlement(settlement);
        }
    }

    private void applySessionRetrySettlement(SessionRetrySettlement settlement) {
        if (!currentSessionActivity(settlement.ticket())) {
            clearSessionRetryFeedback(settlement.ticket());
            return;
        }
        long baselineGeneration = sessionActivityBaselineGeneration;
        state.sessionActivity = ClientSnapshot.SessionActivity.NONE;
        if (settlement.failure() != null) {
            state.lifecycle = state.lifecycle == ClientSnapshot.Lifecycle.INITIALIZING
                    ? ClientSnapshot.Lifecycle.READY
                    : state.lifecycle;
            state.rateLimited = state.providers.minecraftEnabled() && operations.rateLimited();
            state.status = operationFailure(settlement.failure());
        } else {
            CompletableFuture<AppearanceRefreshCoordinator.Result> localRebind =
                    acceptSessionActivityData(settlement.result(), baselineGeneration);
            if (settlement.result().session().valid()) {
                reconcileAfterLocalRebind(
                        localRebind,
                        Trigger.SESSION_REFRESHED);
            }
        }
        clearSessionRetryFeedback(settlement.ticket());
        publish();
    }

    private void clearSessionRetryFeedback(long ticket) {
        if (sessionRetryTicket != ticket) {
            return;
        }
        clearSessionActivity(ticket);
        sessionRetryTicket = -1L;
        sessionRetryFeedbackRendered = false;
        sessionRetryFeedbackTicksRemaining = 0;
        pendingSessionRetrySettlement = null;
    }

    private void clearSessionRetryFeedback() {
        clearSessionActivity(sessionActivityTicket);
        sessionRetryTicket = -1L;
        sessionRetryFeedbackRendered = false;
        sessionRetryFeedbackTicksRemaining = 0;
        pendingSessionRetrySettlement = null;
    }

    private boolean currentSessionActivity(long ticket) {
        if (disposed
                || ticket < 0
                || ticket != sessionActivityTicket
                || state.lifecycle == ClientSnapshot.Lifecycle.CLOSED
                || sessionActivityAccountId == null) {
            return false;
        }
        try {
            return sessionActivityAccountId.equals(operations.sessionIdentity().profileId());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void clearSessionActivity(long ticket) {
        if (ticket != sessionActivityTicket) {
            return;
        }
        sessionActivityTicket = -1L;
        sessionActivityBaselineGeneration = -1L;
        sessionActivityAccountId = null;
        state.sessionActivity = ClientSnapshot.SessionActivity.NONE;
    }

    private void acceptRemoteResult(
            ClientOperations.RemoteResult result, UUID presetToActivate) {
        PresetApplicationOutcome outcome = withoutLegacyRestore(result.outcome());
        state.lastMutation = outcome;
        state.account = result.account();
        state.session = result.session();
        state.remoteProfile = outcome.afterProfile() == null
                ? outcome.beforeProfile()
                : outcome.afterProfile();
        state.currentOfficialSkinId = result.currentOfficialSkinId().orElse(null);
        state.rateLimited = state.providers.minecraftEnabled() && operations.rateLimited();
        UiMessage outcomeStatus = mutationMessage(outcome, state.rateLimited);
        state.status = outcomeStatus;
        if (outcome.result() == MutationResult.APPLIED) {
            if (presetToActivate != null) {
                state.activePresetId = presetToActivate;
            } else {
                clearActivePreset();
            }
        } else if (outcome.result() == MutationResult.PARTIAL
                || outcome.result() == MutationResult.UNKNOWN) {
            clearActivePreset();
        }
        appearanceRefresh.ifPresent(refresh -> refresh.afterMutation(outcome, refreshResult -> {
            if (refreshResult == AppearanceRefreshCoordinator.Result.DEFERRED
                    && state.lastMutation == outcome
                    && state.lifecycle != ClientSnapshot.Lifecycle.CLOSED
                    && !state.status.literal()) {
                state.status = UiMessage.literal(
                        textResolver.resolve(outcomeStatus)
                                + " "
                                + textResolver.resolve(UiMessage.info(
                                        "nclskins.status.reconnect_refresh")),
                        outcomeStatus.severity());
                publish();
            }
        }));
    }

    private void reportEditorPreviewFailure(
            ViewSpec.Preview failed, String translationKey, boolean capeFailure) {
        editorFlow.reportEditorPreviewFailure(failed, translationKey, capeFailure);
    }

    private void clearEditorPreviewFailure(
            ViewSpec.Preview loaded, String translationKey, boolean capeFailure) {
        editorFlow.clearEditorPreviewFailure(loaded, translationKey, capeFailure);
    }

    private void clearActivePreset() {
        state.activePresetId = null;
    }

    private static PresetApplicationOutcome withoutLegacyRestore(
            PresetApplicationOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (!outcome.recoveryActions().contains(RecoveryAction.RESTORE_PREVIOUS_APPEARANCE)) {
            return outcome;
        }
        Set<RecoveryAction> recoveryActions = new HashSet<>(outcome.recoveryActions());
        recoveryActions.remove(RecoveryAction.RESTORE_PREVIOUS_APPEARANCE);
        return new PresetApplicationOutcome(
                outcome.result(),
                outcome.phase(),
                outcome.beforeProfile(),
                outcome.afterProfile(),
                outcome.appliedAppearance(),
                outcome.failureKind(),
                recoveryActions,
                outcome.remoteAppearanceImpact(),
                outcome.userMessage());
    }

    private void queueGalleryScroll(double pixelDelta) {
        galleryFlow.queueGalleryScroll(pixelDelta);
    }

    private void setGalleryOffset(int offset) {
        galleryFlow.setGalleryOffset(offset);
    }

    private void setGalleryPosition(double position) {
        galleryFlow.setGalleryPosition(position);
    }

    private void resetGalleryScroll() {
        galleryFlow.resetGalleryScroll();
    }

    private void centerGalleryIfActiveChanged(UUID previousActivePresetId) {
        galleryFlow.centerGalleryIfActiveChanged(previousActivePresetId);
    }

    private void centerGalleryOnActive() {
        galleryFlow.centerGalleryOnActive();
    }

    private ViewSpec galleryView(
            int width, int height, int mouseX, int mouseY) {
        return galleryFlow.galleryView(width, height, mouseX, mouseY);
    }

    private int galleryMaximum() {
        return galleryFlow.galleryMaximum();
    }

    private void queueEditorContentScroll(double pixelDelta) {
        editorFlow.queueEditorContentScroll(pixelDelta);
    }

    private double editorPositionFromScrollbar(int width, int height, double top) {
        return editorFlow.editorPositionFromScrollbar(width, height, top);
    }

    private void setEditorContentPosition(double position) {
        editorFlow.setEditorContentPosition(position);
    }

    private void resetEditorScroll() {
        editorFlow.resetEditorScroll();
    }

    private void initializeEditorCapeCatalog(LocalCapeReference offlineSeed) {
        editorFlow.initializeEditorCapeCatalog(offlineSeed);
    }

    private void installWarmedCapePreviews(UUID accountId, boolean replaceResources) {
        Map<String, byte[]> warmed = operations.warmedCapePreviews(accountId);
        previewAssets.installWarmed(warmed, replaceResources);
    }

    private boolean clampGalleryScroll() {
        return galleryFlow.clampGalleryScroll();
    }

    private boolean clampEditorScroll() {
        return editorFlow.clampEditorScroll();
    }

    private static double dominantScrollAmount(double horizontalAmount, double verticalAmount) {
        if (!Double.isFinite(horizontalAmount) || !Double.isFinite(verticalAmount)) {
            return 0.0;
        }
        return Math.abs(horizontalAmount) > Math.abs(verticalAmount)
                ? horizontalAmount
                : verticalAmount;
    }

    private void setAddSourceOffset(int offset) {
        catalogImportFlow.setAddSourceOffset(offset);
    }

    private void queueAddSourceScroll(double delta) {
        catalogImportFlow.queueAddSourceScroll(delta);
    }

    private void resetAddSourceScroll() {
        catalogImportFlow.resetAddSourceScroll();
    }

    private void clampAddSourceScroll() {
        catalogImportFlow.clampAddSourceScroll();
    }

    private void updateEditor(java.util.function.UnaryOperator<PresetEditorModel> update) {
        editorFlow.updateEditor(update);
    }

    private void selectEditorVariant(SkinVariant variant) {
        editorFlow.selectEditorVariant(variant);
    }

    private void prepareEditorEvidence(PresetEditorModel editor) {
        editorFlow.prepareEditorEvidence(editor);
    }

    private boolean editorEvidenceStillCurrent(String revision, SkinVariant variant) {
        return editorFlow.editorEvidenceStillCurrent(revision, variant);
    }

    private String editorEvidenceRevision(PresetEditorModel editor) {
        return editorFlow.editorEvidenceRevision(editor);
    }

    private void rememberPreferredSkinVariant(SkinVariant variant) {
        if (state.account == null) {
            return;
        }
        AccountUiPreferences preferences = state.uiPreferences == null
                ? AccountUiPreferences.defaults(state.account.accountId())
                : state.uiPreferences;
        state.uiPreferences = preferences.withPreferredSkinVariant(variant);
        if (catalogImportFlow.addSource() != null) {
            catalogImportFlow.preferredVariantChanged(variant);
        }
        persistUiPreference(() -> {
            operations.setPreferredSkinVariant(variant);
            return null;
        });
    }

    private void submitLocal(ThrowingSupplier<AccountState> operation, UiMessage success) {
        submit(
                UiMessage.info("nclskins.status.saving"),
                operation,
                account -> {
                    state.account = account;
                    state.status = success;
                });
    }

    private <T> void submit(
            UiMessage progress, ThrowingSupplier<T> operation, Consumer<T> completion) {
        submit(progress, operation, completion, ignored -> {});
    }

    private <T> void submitRemote(
            UiMessage progress,
            ThrowingSupplier<T> operation,
            Consumer<T> completion,
            Function<T, Optional<PresetApplicationOutcome>> completedOutcome) {
        submit(
                progress,
                operation,
                completion,
                ignored -> {},
                Objects.requireNonNull(completedOutcome, "completedOutcome"));
    }

    private <T> void submit(
            UiMessage progress,
            ThrowingSupplier<T> operation,
            Consumer<T> completion,
            Consumer<Throwable> failureCompletion) {
        submit(
                progress,
                operation,
                completion,
                failureCompletion,
                ignored -> Optional.empty());
    }

    private <T> void submit(
            UiMessage progress,
            ThrowingSupplier<T> operation,
            Consumer<T> completion,
            Consumer<Throwable> failureCompletion,
            Function<T, Optional<PresetApplicationOutcome>> completedOutcome) {
        if (disposed || state.busy || state.lifecycle == ClientSnapshot.Lifecycle.CLOSED) {
            return;
        }
        long ticket = ++state.generation;
        state.busy = true;
        state.status = Objects.requireNonNull(progress, "progress");
        publish();
        CompletableFuture.supplyAsync(() -> {
                    try {
                        return operation.get();
                    } catch (Exception failure) {
                        throw new CompletionException(failure);
                    }
                }, worker)
                .whenComplete((result, failure) -> onClient(() -> {
                    boolean refreshServer = failure == null
                            ? remoteAppearanceMayHaveChanged(Objects.requireNonNull(
                                    completedOutcome.apply(result), "completed remote outcome"))
                            : remoteAppearanceMayHaveChanged(failure);
                    if (refreshServer && state.providers.minecraftEnabled()) {
                        serverAppearanceReadiness.ifPresent(coordinator -> {
                            try {
                                coordinator.start();
                            } catch (RuntimeException readinessFailure) {
                                diagnose(
                                        DiagnosticEvent.CLIENT_READINESS_FAILED,
                                        readinessFailure);
                            }
                        });
                    }
                    if (!current(ticket)) {
                        return;
                    }
                    state.busy = false;
                    if (failure != null) {
                        diagnose(DiagnosticEvent.CLIENT_ASYNC_OPERATION_FAILED, failure);
                        state.lifecycle = state.lifecycle == ClientSnapshot.Lifecycle.INITIALIZING
                                ? ClientSnapshot.Lifecycle.READY
                                : state.lifecycle;
                        state.status = operationFailure(failure);
                        failureCompletion.accept(failure);
                    } else {
                        completion.accept(result);
                    }
                    publish();
                }));
    }

    private static boolean remoteAppearanceMayHaveChanged(
            Optional<PresetApplicationOutcome> outcome) {
        return outcome.isPresent()
                && outcome.orElseThrow().remoteAppearanceImpact()
                        == com.naocraftlab.skins.core.service.RemoteAppearanceImpact.CONFIRMED_CHANGED;
    }

    private static boolean remoteAppearanceMayHaveChanged(Throwable failure) {
        Throwable cause = unwrap(failure);
        return cause instanceof RemoteMutationSettlementException settlement
                && settlement.remoteAppearanceImpact()
                        == com.naocraftlab.skins.core.service.RemoteAppearanceImpact.CONFIRMED_CHANGED;
    }

    private boolean current(long ticket) {
        return !disposed
                && ticket == state.generation
                && state.lifecycle != ClientSnapshot.Lifecycle.CLOSED;
    }

    private void publish() {
        observeCapeProviderCooldowns();
        notifySelfCapeInputs();
        appearanceRefresh.ifPresent(coordinator -> coordinator.providerVisibility(
                new com.naocraftlab.skins.client.ProviderVisibility(
                        state.providers.skin().order().contains(BuiltinProvider.MINECRAFT),
                        state.providers.cape().order().contains(BuiltinProvider.MINECRAFT))));
        ClientConfiguration configuration;
        try {
            configuration = Objects.requireNonNull(
                    configurationSource.get(), "configuration source result");
        } catch (RuntimeException unavailable) {
            configuration = ClientConfiguration.defaults();
        }
        snapshot = new ClientSnapshot(
                state.lifecycle,
                Optional.ofNullable(state.account),
                Optional.ofNullable(state.session),
                Optional.ofNullable(state.remoteProfile),
                Optional.ofNullable(state.lastMutation),
                Optional.ofNullable(galleryFlow.selectedSkinId()),
                Optional.ofNullable(galleryFlow.selectedPresetId()),
                Optional.ofNullable(state.selectedCapeId),
                Optional.ofNullable(state.currentOfficialSkinId),
                Optional.ofNullable(state.activePresetId),
                Optional.ofNullable(editorFlow.editor()),
                Optional.ofNullable(catalogImportFlow.addSource()),
                state.status,
                state.busy,
                state.rateLimited,
                state.rateLimitProgress,
                state.capeProviderCooldowns,
                galleryFlow.galleryOffset(),
                state.generation,
                state.intentRevision,
                state.syncStatus,
                state.syncInProgress,
                state.sessionActivity,
                skinExtensionEnvironment,
                state.assetEvidence,
                catalogImportFlow.catalogEvidence(),
                configuration.compatibility().hideIncompatibleCatalogSkins(),
                configuration.compatibility().hideIncompatibleGalleryLooks(), state.providers);
        ClientSnapshot published = snapshot;
        listeners.forEach(listener -> listener.accept(published));
    }

    private void notifySelfCapeInputs() {
        if (state.account == null) return;
        GameSessionTokenSource.SessionIdentity identity;
        try {
            identity = operations.sessionIdentity();
        } catch (RuntimeException unavailable) {
            return;
        }
        if (!state.account.accountId().equals(identity.profileId())) return;
        SelfCapeInputs next = new SelfCapeInputs(identity.profileId(), identity.profileName(),
                state.providers.cape().offline(), state.providers.cape().minecraft());
        if (next.equals(publishedSelfCapeInputs)) return;
        publishedSelfCapeInputs = next;
        capeObservations.selfCapeCandidatesChanged(identity.profileId(), identity.profileName(),
                state.providers);
    }

    private record SelfCapeInputs(UUID accountId, String canonicalName,
            ProviderObservation<ProviderCape> offline,
            ProviderObservation<ProviderCape> minecraft) {}

    private boolean refreshSkinExtensionEnvironment() {
        skinExtensionEnvironmentInitialized = true;
        SkinExtensionEnvironment refreshed;
        try {
            SkinExtensionEnvironmentSource.Snapshot source = Objects.requireNonNull(
                    skinExtensionEnvironmentSource.snapshot(), "environment snapshot");
            EnumMap<SkinConsumer, SkinConsumerState> states = new EnumMap<>(SkinConsumer.class);
            for (SkinExtensionEnvironmentSource.Consumer consumer
                    : SkinExtensionEnvironmentSource.Consumer.values()) {
                states.put(
                        SkinConsumer.valueOf(consumer.name()),
                        SkinConsumerState.valueOf(source.consumers().get(consumer).name()));
            }
            refreshed = new SkinExtensionEnvironment(source.generation(), states);
        } catch (RuntimeException unavailable) {
            refreshed = SkinExtensionEnvironment.unknown(0);
        }
        if (refreshed.equals(skinExtensionEnvironment)) {
            return false;
        }
        skinExtensionEnvironment = refreshed;
        return true;
    }

    private void onClient(Runnable action) {
        if (clientExecutor.isClientThread()) {
            action.run();
        } else {
            clientExecutor.execute(action);
        }
    }

    private void ensureNotDisposed() {
        if (disposed) {
            throw new IllegalStateException("Client runtime is closed");
        }
    }

    private SkinVariant currentPlayerVariant() {
        return currentPlayerAppearance()
                .map(CurrentPlayerAppearanceSource.PlayerAppearance::model)
                .map(model -> model == com.naocraftlab.skins.client.SkinModel.SLIM
                        ? SkinVariant.SLIM
                        : SkinVariant.CLASSIC)
                .orElse(SkinVariant.CLASSIC);
    }

    private SkinVariant preferredSkinVariant() {
        return Optional.ofNullable(state.uiPreferences)
                .flatMap(AccountUiPreferences::preferredSkinVariant)
                .orElseGet(this::currentPlayerVariant);
    }

    private AppearancePreset findPreset(UUID id) {
        if (id == null || state.account == null) {
            return null;
        }
        return state.account.presets().stream().filter(preset -> preset.id().equals(id)).findFirst().orElse(null);
    }

    private SkinAsset findSkin(UUID id) {
        if (id == null || state.account == null) {
            return null;
        }
        return state.account.skinAssets().stream().filter(skin -> skin.id().equals(id)).findFirst().orElse(null);
    }

    private static UiMessage fileImportFailure(Throwable failure) {
        return UiMessage.error(unwrap(failure) instanceof PngValidationException
                ? "nclskins.error.png" : "nclskins.add_source.file_io_error");
    }

    private void clearFileImportError() {
        if (catalogImportFlow.addSource() != null && (state.status.key().equals("nclskins.error.png")
                || state.status.key().equals("nclskins.add_source.file_io_error"))) {
            state.status = UiMessage.info("nclskins.add_source.title");
            publish();
        }
    }

    private static NormalizedSkin readPng(Path path) {
        try {
            return new PngValidator().projectStandardImport(path);
        } catch (IOException | PngValidationException failure) {
            throw new CompletionException(failure);
        }
    }

    private static UiMessage sessionMessage(SessionValidation validation) {
        String key = switch (validation.status()) {
            case UNCHECKED -> null;
            case VALID -> "nclskins.session.message.valid";
            case EXPIRED -> "nclskins.session.message.expired";
            case OFFLINE_OR_INVALID -> offlineMessageKey(validation.failureKind());
            case UUID_MISMATCH -> "nclskins.session.message.uuid_mismatch";
            case NOT_ENTITLED -> "nclskins.session.message.not_entitled";
            case PROFILE_RESTRICTED -> "nclskins.session.message.restricted";
        };
        return key == null ? null : validation.valid() ? UiMessage.success(key) : UiMessage.error(key);
    }

    private static String offlineMessageKey(ApiFailureKind failureKind) {
        if (failureKind == null) {
            return "nclskins.session.message.check_failed";
        }
        return switch (failureKind) {
            case TOKEN_UNAVAILABLE -> "nclskins.session.message.offline";
            case INVALID_SESSION -> "nclskins.session.message.offline";
            case NETWORK, SERVER_ERROR -> "nclskins.session.message.service_unavailable";
            case RATE_LIMITED -> "nclskins.session.message.rate_limited";
            case INVALID_RESPONSE, REDIRECT_REJECTED -> "nclskins.session.message.invalid_response";
            case SESSION_EXPIRED -> "nclskins.session.message.expired";
            case FORBIDDEN, NOT_FOUND -> "nclskins.session.message.check_failed";
        };
    }

    private static UiMessage mutationMessage(PresetApplicationOutcome outcome, boolean rateLimited) {
        if (rateLimited || outcome.optionalFailureKind().filter(ApiFailureKind.RATE_LIMITED::equals).isPresent()) {
            return UiMessage.error("nclskins.session.message.rate_limited");
        }
        String key = switch (outcome.result()) {
            case APPLIED -> "nclskins.mutation.applied";
            case PARTIAL -> "nclskins.mutation.partial";
            case UNKNOWN -> "nclskins.mutation.unknown";
            case FAILED -> "nclskins.mutation.failed";
            case SESSION_EXPIRED -> "nclskins.session.message.expired";
        };
        return outcome.result() == MutationResult.APPLIED ? UiMessage.success(key) : UiMessage.error(key);
    }

    private static UiMessage operationFailure(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof LibraryOperationException libraryFailure
                && libraryFailure.code() == LibraryOperationException.Code.SKIN_IN_USE) {
            return UiMessage.literal(
                    "Delete dependent presets first, then delete this skin.",
                    UiMessage.Severity.ERROR);
        }
        return UiMessage.error("nclskins.error.save");
    }

    private static String publicImportFailureKey(Throwable failure, boolean player) {
        Throwable cause = unwrap(failure);
        if (!(cause instanceof PublicSkinImportException importFailure)) {
            return player
                    ? "nclskins.add_source.player_failed"
                    : "nclskins.add_source.url_failed";
        }
        if (player) {
            return switch (importFailure.code()) {
                case INVALID_IDENTIFIER -> "nclskins.add_source.player_invalid_identifier";
                case PROFILE_NOT_FOUND -> "nclskins.add_source.player_not_found";
                case RATE_LIMITED -> "nclskins.add_source.player_rate_limited";
                case SERVICE_UNAVAILABLE, NETWORK_FAILURE -> "nclskins.add_source.player_service_unavailable";
                case PROFILE_REJECTED -> "nclskins.add_source.player_rejected";
                case OVERSIZED -> "nclskins.add_source.player_oversized";
                default -> "nclskins.add_source.player_failed";
            };
        }
        return switch (importFailure.code()) {
            case UNSAFE_URL -> "nclskins.add_source.url_unsafe";
            case REDIRECT_REJECTED -> "nclskins.add_source.url_redirect_rejected";
            case SITE_BLOCKED -> "nclskins.add_source.url_site_blocked";
            case RATE_LIMITED -> "nclskins.add_source.url_rate_limited";
            case NETWORK_FAILURE, SERVICE_UNAVAILABLE -> "nclskins.add_source.url_network_failure";
            case OVERSIZED -> "nclskins.add_source.url_oversized";
            case INVALID_PNG -> "nclskins.add_source.url_invalid_file";
            default -> "nclskins.add_source.url_failed";
        };
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Set<UUID> ids(List<? extends Object> values) {
        Set<UUID> ids = new HashSet<>();
        for (Object value : values) {
            if (value instanceof SkinAsset skin) {
                ids.add(skin.id());
            } else if (value instanceof AppearancePreset preset) {
                ids.add(preset.id());
            }
        }
        return Set.copyOf(ids);
    }

    private static ExecutorService newWorker(String threadName) {
        Objects.requireNonNull(threadName, "threadName");
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private static Optional<PersonalCatalogAction> personalCatalogAction(
            String widgetId, String prefix) {
        String value = widgetId.substring(prefix.length());
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            return Optional.empty();
        }
        String collectionId = value.substring(0, separator);
        String sha256 = value.substring(separator + 1);
        return sha256.matches("[0-9a-f]{64}")
                ? Optional.of(new PersonalCatalogAction(collectionId, sha256))
                : Optional.empty();
    }

    private record SessionRetrySettlement(
            long ticket, ClientOperations.InitialData result, Throwable failure) {
        private SessionRetrySettlement {
            if (ticket < 0 || (result == null) == (failure == null)) {
                throw new IllegalArgumentException("session retry settlement must have one outcome");
            }
        }

        private static SessionRetrySettlement success(
                long ticket, ClientOperations.InitialData result) {
            return new SessionRetrySettlement(
                    ticket, Objects.requireNonNull(result, "result"), null);
        }

        private static SessionRetrySettlement failure(long ticket, Throwable failure) {
            return new SessionRetrySettlement(
                    ticket, null, Objects.requireNonNull(failure, "failure"));
        }
    }

    private final class State {
        private AppearanceProviders providers =
                AppearanceProviders.initial();
        private ClientSnapshot.Lifecycle lifecycle = ClientSnapshot.Lifecycle.NEW;
        private AccountState account;
        private SessionValidation session;
        private RemoteProfile remoteProfile;
        private PresetApplicationOutcome lastMutation;

        private String selectedCapeId;
        private UUID currentOfficialSkinId;
        private UUID activePresetId;

        private AccountUiPreferences uiPreferences;
        private OwnedCapeInventory ownedCapes;
        private UiMessage status = UiMessage.info("nclskins.status.loading");
        private boolean busy;
        private boolean rateLimited;
        private Optional<ClientSnapshot.RateLimitProgress> rateLimitProgress = Optional.empty();
        private Map<BuiltinProvider, Duration> capeProviderCooldowns = Map.of();
        private long intentRevision;
        private AppearanceSyncStatus syncStatus = AppearanceSyncStatus.LOCAL_ONLY;
        private AppliedAppearance localAppearance;
        private boolean syncInProgress;
        private ClientSnapshot.SessionActivity sessionActivity =
                ClientSnapshot.SessionActivity.NONE;

        private boolean providersOpen;
        private boolean editorReturnsToProviders;
        private boolean galleryReturnsToProviders;

        private long generation;
        private boolean readyData;
        private final Map<UUID, SkinFeatureEvidence> assetEvidence = new LinkedHashMap<>();

        private ScreenDestination requestedDestination;
        private ScreenDestination rootDestination = ScreenDestination.GALLERY;

        private void resetForReopen() {
            requestedDestination = null;
            rootDestination = ScreenDestination.GALLERY;
            boolean retainReadyData = readyData && account != null;
            readyData = retainReadyData;
            generation++;
            lifecycle = ClientSnapshot.Lifecycle.NEW;
            if (!retainReadyData) {
                account = null;
                session = null;
                remoteProfile = null;
                selectedCapeId = null;
                currentOfficialSkinId = null;
                activePresetId = null;
                uiPreferences = null;
                ownedCapes = null;
                providers = AppearanceProviders.initial();
                intentRevision = 0;
                syncStatus = AppearanceSyncStatus.LOCAL_ONLY;
                localAppearance = null;
            }
            lastMutation = null;
            status = UiMessage.info("nclskins.status.loading");
            busy = false;
            rateLimited = false;
            rateLimitProgress = Optional.empty();
            capeProviderCooldowns = Map.of();
            syncInProgress = false;
            sessionActivity = ClientSnapshot.SessionActivity.NONE;
            providersOpen = false;
            editorReturnsToProviders = false;
            galleryReturnsToProviders = false;
            if (!retainReadyData) {
                assetEvidence.clear();
            }
            galleryFlow.resetSession();
            editorFlow.resetSession();
            catalogImportFlow.resetSession();
            providerFlow.resetSession();
        }
    }
    private boolean dispatchGalleryAction(String widgetId, boolean reverse, InteractionOrigin origin) {
        return galleryFlow.dispatchGalleryAction(widgetId, reverse, origin);
    }

    private boolean dispatchEditorAction(String widgetId, boolean reverse, InteractionOrigin origin) {
        return editorFlow.dispatchEditorAction(widgetId, reverse, origin);
    }

    private boolean dispatchCatalogImportAction(String widgetId, boolean reverse, InteractionOrigin origin) {
        return catalogImportFlow.dispatchCatalogImportAction(widgetId, reverse, origin);
    }

    private boolean dispatchProviderAction(String widgetId, boolean reverse, InteractionOrigin origin) {
        return providerFlow.dispatchProviderAction(widgetId, reverse, origin);
    }

    private ViewSpec presentProvider(int width, int height) {
        return providerFlow.presentProvider(width, height);
    }

    private ViewSpec presentEditor(int width, int height) {
        return editorFlow.presentEditor(width, height);
    }

    private ViewSpec presentCatalogImport(int width, int height) {
        return catalogImportFlow.presentCatalogImport(width, height);
    }
}

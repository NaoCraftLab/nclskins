package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CatalogCollectionOrder;
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
import com.naocraftlab.skins.core.compatibility.SkinCompatibility;
import com.naocraftlab.skins.core.compatibility.SkinCompatibilityEvaluator;
import com.naocraftlab.skins.core.compatibility.SkinCompatibilityStatus;
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
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.EditorTab;
import com.naocraftlab.skins.core.model.LocalCapeReference;
import com.naocraftlab.skins.core.model.MutationResult;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;


public final class ClientRuntime implements AutoCloseable {
    private static final double WHEEL_SCROLL_PIXELS = 32.0;
    private static final int SESSION_RETRY_FEEDBACK_TICKS = 6;

    private final ClientOperations operations;
    private final CapeObservationPort capeObservations;
    private final DiagnosticSink diagnostics;
    private CompletableFuture<Void> providerConfigurationWrite = CompletableFuture.completedFuture(null);
    private long editorCatalogGeneration;
    private BuiltinProvider pendingCapeProviderInspection;
    private CompletableFuture<Void> capeDisclosureWrite = CompletableFuture.completedFuture(null);
    private long capeDisclosureSequence;
    private CompletableFuture<Void> editorTabPreferenceWrite = CompletableFuture.completedFuture(null);
    private long editorTabPreferenceSequence;
    private final ClientExecutor clientExecutor;
    private final FilePicker filePicker;
    private final Executor worker;
    private final ExecutorService ownedWorker;
    private final Executor reconciliationWorker;
    private final ExecutorService ownedReconciliationWorker;
    private final Executor sessionWorker;
    private final ExecutorService ownedSessionWorker;
    private OptiFineAccountLink optiFineAccountLink;
    private boolean optiFineLinkClaimed;
    private UiMessage optiFineLinkFeedback;
    private long optiFineLinkAttempt;
    private static final URI SKINMC_ACCOUNT_URI = URI.create("https://skinmc.net/account/capes");
    private static final URI SNEAKY_EDITOR_URI = URI.create("https://penguinspy.neocities.org/projects/loom/");
    private UUID skinMcLinkAccountId;
    private boolean skinMcLinkPending;
    private boolean skinMcLinkClaimed;
    private boolean sneakyEditorLinkPending;
    private boolean sneakyEditorLinkClaimed;
    private SelfCapeInputs publishedSelfCapeInputs;
    private final TextResolver textResolver;
    private final Optional<CurrentPlayerAppearanceSource> currentAppearanceSource;
    private final Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh;
    private final Optional<OuterLayerVisibilityController> outerLayerVisibilityController;
    private final Optional<ServerAppearanceReadinessCoordinator> serverAppearanceReadiness;
    private final GalleryPresenter galleryPresenter = new GalleryPresenter();
    private final AddSourcePresenter addSourcePresenter = new AddSourcePresenter();
    private final ExternalImportPresenter externalImportPresenter = new ExternalImportPresenter();
    private final CopyOnWriteArrayList<Consumer<ClientSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, byte[]> previewBytes = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Optional<byte[]>>> previewInFlight =
            new ConcurrentHashMap<>();
    private final Object reconciliationMonitor = new Object();
    private final Map<ClientOperations.ReconciliationKey, ClientOperations.ReconciliationTrigger>
            pendingReconciliations = new LinkedHashMap<>();
    private ReconciliationRequest activeReconciliation;
    private boolean reconciliationRunning;
    private long catalogPreviewEpoch;
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
    private boolean draggingGalleryScrollbar;
    private boolean draggingProviderRowsScrollbar;
    private double providerRowsScrollbarGrabOffset;
    private double galleryScrollbarGrabOffset;
    private boolean draggingEditorScrollbar;
    private double editorScrollbarGrabOffset;
    private double editorModelScrollPosition;
    private double editorCapeScrollPosition;
    private double editorCapeScrollTarget;
    private boolean draggingAddSourceScrollbar;
    private double addSourceScrollbarGrabOffset;
    private double addSourceScrollPosition;
    private double addSourceScrollTarget;
    private long runtimeFocusToken;
    private String runtimeFocusScreenId;
    private String runtimeFocusWidgetId;
    private long catalogDisclosureRevision;
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
        this.ownedWorker = ownedWorker;
        this.reconciliationWorker = Objects.requireNonNull(
                reconciliationWorker, "reconciliationWorker");
        this.ownedReconciliationWorker = ownedReconciliationWorker;
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
        runtime.optiFineAccountLink = new OptiFineAccountLink(tokenSource, sessionWorker);
        return runtime;
    }

    ClientRuntime useOptiFineAccountLink(OptiFineAccountLink link) {
        optiFineAccountLink = Objects.requireNonNull(link, "link");
        return this;
    }

    public Optional<URI> consumeReadyOptiFineAccountLink() {
        if (!liveOptiFineLinkView() || optiFineLinkClaimed) return Optional.empty();
        URI uri = optiFineAccountLink.readyUri(state.account.accountId());
        if (uri == null) return Optional.empty();
        optiFineLinkClaimed = true;
        return Optional.of(uri);
    }

    public Optional<URI> currentOptiFineAccountLink() {
        if (!liveOptiFineLinkView() || !optiFineLinkClaimed) return Optional.empty();
        return Optional.ofNullable(optiFineAccountLink.readyUri(state.account.accountId()));
    }

    public void finishOptiFineAccountLink() {
        onClient(this::cancelOptiFineAccountLink);
    }

    public Optional<URI> consumeReadySkinMcAccountLink() {
        if (!liveSkinMcLinkView() || !skinMcLinkPending || skinMcLinkClaimed) return Optional.empty();
        skinMcLinkClaimed = true;
        return Optional.of(SKINMC_ACCOUNT_URI);
    }

    public Optional<URI> currentSkinMcAccountLink() {
        return liveSkinMcLinkView() && skinMcLinkClaimed
                ? Optional.of(SKINMC_ACCOUNT_URI) : Optional.empty();
    }

    public void finishSkinMcAccountLink() {
        onClient(this::cancelSkinMcAccountLink);
    }

    public Optional<URI> consumeReadySneakyEditorLink() {
        if (!liveSneakyEditorLinkView() || !sneakyEditorLinkPending || sneakyEditorLinkClaimed) return Optional.empty();
        sneakyEditorLinkClaimed = true;
        return Optional.of(SNEAKY_EDITOR_URI);
    }

    public Optional<URI> currentSneakyEditorLink() {
        return liveSneakyEditorLinkView() && sneakyEditorLinkClaimed
                ? Optional.of(SNEAKY_EDITOR_URI) : Optional.empty();
    }

    public void finishSneakyEditorLink() {
        onClient(this::cancelSneakyEditorLink);
    }

    public void expireOptiFineAccountLink() {
        onClient(() -> {
            boolean expired = optiFineAccountLink != null && optiFineAccountLink.expired();
            cancelOptiFineAccountLink();
            optiFineLinkFeedback = expired
                    ? UiMessage.error("nclskins.providers.link_expired") : null;
            publish();
        });
    }

    private void cancelOptiFineAccountLink() {
        optiFineLinkAttempt++;
        if (optiFineAccountLink != null) optiFineAccountLink.cancel();
        optiFineLinkClaimed = false;
        optiFineLinkFeedback = null;
    }

    private void cancelSkinMcAccountLink() {
        skinMcLinkPending = false;
        skinMcLinkClaimed = false;
        skinMcLinkAccountId = null;
    }

    private void cancelSneakyEditorLink() {
        sneakyEditorLinkPending = false;
        sneakyEditorLinkClaimed = false;
    }

    private boolean liveSneakyEditorLinkView() {
        return state.lifecycle == ClientSnapshot.Lifecycle.READY
                && (state.providersOpen || !state.providers.galleryAvailable())
                && !state.providerAdding && state.editor == null
                && state.providerComponent == AppearanceProviders.Component.CAPE
                && state.providers.cape().enabled(BuiltinProvider.SNEAKY);
    }

    private boolean liveSkinMcLinkView() {
        return state.account != null && state.account.accountId().equals(skinMcLinkAccountId)
                && state.lifecycle == ClientSnapshot.Lifecycle.READY
                && (state.providersOpen || !state.providers.galleryAvailable())
                && !state.providerAdding && state.editor == null
                && state.providerComponent == AppearanceProviders.Component.CAPE
                && state.providers.cape().enabled(BuiltinProvider.SKINMC);
    }

    private boolean liveOptiFineLinkView() {
        return optiFineAccountLink != null && state.account != null
                && state.lifecycle == ClientSnapshot.Lifecycle.READY
                && (state.providersOpen || !state.providers.galleryAvailable())
                && !state.providerAdding && state.editor == null
                && state.providerComponent == AppearanceProviders.Component.CAPE
                && state.providers.cape().enabled(BuiltinProvider.OPTIFINE);
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
                                        ClientOperations.ReconciliationTrigger.PROCESS_START);
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
                || (state.busy && !state.providersOpen && state.editor == null && state.addSource == null
                        && (state.rootDestination == ScreenDestination.ACTIVE_EDITOR || addSourceRoot()));
    }

    private boolean addSourceRoot() {
        return state.rootDestination == ScreenDestination.SKIN_CATALOG
                || state.rootDestination == ScreenDestination.SKIN_IMPORT;
    }

    private void selectProvidersTab(AppearanceProviders.Component tab) {
        state.providerComponent = tab;
        if (state.account == null || state.uiPreferences == null
                || state.uiPreferences.selectedProvidersTab() == tab) return;
        UUID accountId = state.account.accountId();
        state.uiPreferences = state.uiPreferences.withSelectedProvidersTab(tab);
        sessionActivityBaselineGeneration = -1;
        persistUiPreference(() -> {
            operations.setSelectedProvidersTab(accountId, tab);
            return null;
        });
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
                    || state.busy && state.externalImport == null) {
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
            if (state.pendingPresetDeleteId != null) {
                cancelPresetDeletion(state.pendingPresetDeleteId, InteractionOrigin.PROGRAMMATIC);
                return;
            }
            if (state.personalRenameHash != null) {
                cancelPersonalSkinRename();
                return;
            }
            if (state.addSource != null
                    && state.addSource.personalSkinDeletion().isPresent()) {
                cancelPersonalSkinDeletion(InteractionOrigin.PROGRAMMATIC);
                return;
            }
            if (state.editor != null && state.editor.capeCatalog() != null && state.editor.capeCatalog().editing() != null) {
                UUID entry = state.editor.capeCatalog().editing();
                updateEditor(editor -> editor.withCapeCatalog(editor.capeCatalog().cancelEdit()));
                requestRuntimeFocus("preset_editor", "editor.cape_item.OFFLINE." + entry);
                publish();
                return;
            }
            if (state.editor != null) {
                cancelEditor();
                return;
            }
            if (state.externalImport != null) {
                cancelExternalImport();
                return;
            }
            if (state.addSource != null) {
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
        optiFineLinkFeedback = null;
        state.generation++;
        state.lifecycle = ClientSnapshot.Lifecycle.CLOSED;
        state.editorReturnsToProviders = false;
        state.galleryReturnsToProviders = false;
        state.providerPreviewSources.clear();
        state.providersOpen = false;
        state.providerAdding = false;
        state.busy = false;
        state.sessionActivity = ClientSnapshot.SessionActivity.NONE;
        state.pendingPresetDeleteId = null;
        clearRuntimeFocus("gallery");
        clearSessionRetryFeedback();
        state.editor = null;
        state.editorEvidence = null;
        state.addSource = null;
        state.externalImport = null;
        if (draggingGalleryScrollbar) {
            state.galleryScrollTarget = state.galleryScrollPosition;
        }
        draggingGalleryScrollbar = false;
        draggingProviderRowsScrollbar = false;
        draggingEditorScrollbar = false;
        draggingAddSourceScrollbar = false;
        addSourceScrollPosition = 0.0;
        addSourceScrollTarget = 0.0;
        publish();
    }

    public void tick() {
        onClient(() -> {
            if (disposed) {
                return;
            }
            if (optiFineAccountLink != null && optiFineAccountLink.expired()) {
                cancelOptiFineAccountLink();
                optiFineLinkFeedback = UiMessage.error("nclskins.providers.link_expired");
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
            if (state.editor != null) {
                scrollChanged |= clampEditorScroll();
            }
            if (state.addSource != null
                    && state.addSource.selectedTab() == AddSourceTab.CATALOG
                    && state.addSource.personalSkinDeletion().isEmpty()) {
                double beforePosition = addSourceScrollPosition;
                double beforeTarget = addSourceScrollTarget;
                int beforeOffset = state.addSource.scrollOffset();
                clampAddSourceScroll();
                scrollChanged |= Math.abs(beforePosition - addSourceScrollPosition) > 0.001
                        || Math.abs(beforeTarget - addSourceScrollTarget) > 0.001
                        || beforeOffset != state.addSource.scrollOffset();
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
                if (state.editor != null) {
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
                            key, ClientOperations.ReconciliationTrigger.RATE_LIMIT_EXPIRED));
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
                                                ClientOperations.ReconciliationTrigger.RECONNECT);
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
        if (state.providersOpen || !state.providers.galleryAvailable()) {
            if (state.providerAdding) return withRuntimeFocus(new ProvidersPresenter().presentChooser(
                    state.providers, state.providerComponent, state.busy, width, height, state.providerChooserOffset));
            ViewSpec providerView = new ProvidersPresenter().present(state.providers, state.providerComponent,
                    state.providerAdding, state.busy, state.providerPreview.withOuterLayerVisibility(
                    outerLayerVisibilityController.map(OuterLayerVisibilityController::current).orElse(OuterLayerVisibility.allVisible())),
                    currentPlayerVariant(), width, height, state.providerPreviewSources.get(AppearanceProviders.Component.SKIN),
                    state.providerPreviewSources.get(AppearanceProviders.Component.CAPE), snapshot.rateLimitProgress(),
                    optiFineAccountLink != null && optiFineAccountLink.preparing(), optiFineLinkFeedback,
                    state.providerRowsOffset, textResolver, snapshot.capeProviderCooldowns());
            SkinFeatureEvidence evidence = Optional.of(providerView.previews().get(0).imageRevision()).filter(revision -> revision.startsWith("provider:skin:")).flatMap(revision -> snapshot.account().flatMap(account ->
                    account.skinAssets().stream().filter(asset -> asset.sha256().equals(revision.substring(14))).findFirst()))
                    .map(asset -> snapshot.assetEvidence().getOrDefault(asset.id(), SkinFeatureEvidence.ORDINARY)).orElse(SkinFeatureEvidence.ORDINARY);
            SkinCompatibility compatibility = new SkinCompatibilityEvaluator().evaluate(evidence, snapshot.skinExtensionEnvironment());
            if (compatibility.status() != SkinCompatibilityStatus.ORDINARY) {
                providerView = providerView.withCompatibilityIndicator("providers.compatibility", new Bounds(2, Math.max(35, height - 55), 20, 20),
                        CompatibilityMessages.accessibleLabel(compatibility), CompatibilityMessages.icon(compatibility), Optional.empty());
            }
            return withRuntimeFocus(providerView);
        }
        PresetEditorModel editor = state.editor;
        if (editor != null) {
            ViewSpec editorView = editor.present(
                    width, height, editorCapeScrollPosition, editorModelScrollPosition, viewChromeMetrics);
            SkinFeatureEvidence evidence = state.editorEvidence;
            if (evidence != null) {
                SkinCompatibility compatibility = new SkinCompatibilityEvaluator().evaluate(
                        evidence, snapshot.skinExtensionEnvironment());
                if (compatibility.status() != SkinCompatibilityStatus.ORDINARY) {
                    Bounds indicatorBounds = new Bounds(
                            2,
                            Math.max(35, height - 55),
                            20,
                            20);
                    editorView = editorView.withCompatibilityIndicator(
                            "editor.compatibility",
                            indicatorBounds,
                            CompatibilityMessages.accessibleLabel(compatibility),
                            CompatibilityMessages.icon(compatibility),
                            Optional.of("editor.outer_layer.legs"));
                }
            }
            return withRuntimeFocus(editorView);
        }
        if (state.externalImport != null) {
            return withRuntimeFocus(externalImportPresenter.present(
                    state.externalImport,
                    state.busy,
                    Optional.of(state.status),
                    width,
                    height,
                    snapshot.skinExtensionEnvironment()));
        }
        if (state.addSource != null) {
            return withRuntimeFocus(addSourcePresenter.present(
                    state.addSource,
                    state.busy,
                    Optional.of(state.status),
                    width,
                    height,
                    state.personalRenameHash == null
                            ? Optional.empty()
                            : Optional.of(new AddSourcePresenter.PersonalSkinRename(
                            state.personalRenameCollectionId,
                            state.personalRenameHash,
                            state.personalRenameValue)),
                    viewChromeMetrics));
        }
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
                state.gallerySelectedCardId = node.id();
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
            state.gallerySelectedCardId = node.id();
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
            state.galleryScrollPosition = bounded;
            state.galleryScrollTarget = bounded;
            state.galleryOffset = (int) Math.round(bounded);
        } else if ("add.catalog".equals(surfaceId) && state.addSource != null) {
            int bounded = addSourcePresenter.normalizedScrollOffset(
                    state.addSource,
                    viewportWidth,
                    viewportHeight,
                    (int) Math.round(offsetPixels),
                    viewChromeMetrics);
            state.addSource = state.addSource.withScrollOffset(bounded);
            addSourceScrollPosition = bounded;
            addSourceScrollTarget = bounded;
        } else if ("providers.chooser".equals(surfaceId) && state.providerAdding) {
            state.providerChooserOffset = Math.max(0, offsetPixels);
        } else if ("providers.rows".equals(surfaceId) && !state.providerAdding) {
            state.providerRowsOffset = Math.max(0, offsetPixels);
        } else if ("external.review".equals(surfaceId)
                && state.externalImport != null
                && state.externalImport.review().isPresent()) {
            state.externalImport = state.externalImport.withReviewScroll(
                    Math.max(0, (int) Math.round(offsetPixels)));
        } else if ("editor.models".equals(surfaceId) && state.editor != null) {
            editorModelScrollPosition = state.editor.normalizedModelScrollPosition(
                    viewportWidth, viewportHeight, offsetPixels);
        } else if ("editor.capes".equals(surfaceId) && state.editor != null) {
            double bounded = state.editor.normalizedCapeScrollPosition(
                    viewportWidth, viewportHeight, offsetPixels, viewChromeMetrics);
            editorCapeScrollPosition = bounded;
            editorCapeScrollTarget = bounded;
        }
    }

    public void dispatchText(String widgetId, String value) {
        Objects.requireNonNull(widgetId, "widgetId");
        Objects.requireNonNull(value, "value");
        onClient(() -> {
            clearFileImportError();
            if ("gallery.search".equals(widgetId)
                    && state.editor == null
                    && state.addSource == null) {
                if (state.galleryQuery.equals(value)) {
                    return;
                }
                state.galleryQuery = value;
                state.gallerySelectedCardId = galleryPresenter.normalizeSelectedCardId(
                        snapshot, value, state.gallerySelectedCardId);
                resetGalleryScroll();
                state.pendingPresetDeleteId = null;
                publish();
            } else if ("editor.cape_search".equals(widgetId)
                    && state.editor != null && state.editor.capeCatalog() != null) {
                updateEditor(editor -> editor.withCapeCatalog(editor.capeCatalog().withQuery(value)));
                editorCapeScrollPosition = 0;
                editorCapeScrollTarget = 0;
            } else if ("editor.cape_action.name".equals(widgetId)
                    && state.editor != null && state.editor.capeCatalog() != null) {
                updateEditor(editor -> editor.withCapeCatalog(editor.capeCatalog().rename(value)));
            } else if ("editor.name".equals(widgetId) && state.editor != null) {
                if (state.editor.name().equals(value)) {
                    return;
                }
                state.editor = state.editor.withName(value);
                publish();
            } else if ("add.catalog.search".equals(widgetId)
                    && state.addSource != null
                    && state.addSource.personalSkinDeletion().isEmpty()) {
                if (state.addSource.query().equals(value)) {
                    return;
                }
                state.addSource = state.addSource.withQuery(value);
                resetAddSourceScroll();
                publish();
            } else if ("add.catalog.rename.name".equals(widgetId)
                    && state.personalRenameHash != null) {
                if (state.personalRenameValue.equals(value)) {
                    return;
                }
                state.personalRenameValue = value;
                publish();
            } else if ("add.player.input".equals(widgetId) && state.addSource != null) {
                if (state.addSource.playerInput().equals(value)) {
                    return;
                }
                state.addSource = state.addSource.withPlayerInput(value);
                publish();
            } else if ("add.url.input".equals(widgetId) && state.addSource != null) {
                if (state.addSource.urlInput().equals(value)) {
                    return;
                }
                state.addSource = state.addSource.withUrlInput(value);
                publish();
            }
        });
    }

    public void pointerPressed(double mouseX, double mouseY, int button) {
        onClient(() -> {
            clearFileImportError();
            if (state.editor != null && state.editor.capeCatalog() != null && state.editor.capeCatalog().importError() != null) {
                state.editor = state.editor.withCapeCatalog(state.editor.capeCatalog().error(false));
                publish();
            }
            if (button != 0 || state.busy) {
                return;
            }
            if (state.providersOpen || !state.providers.galleryAvailable()) {
                if (state.providerAdding) return;
                ViewSpec providerView = viewInternal(viewportWidth, viewportHeight, (int) mouseX, (int) mouseY);
                Optional<ViewSpec.Scrollbar> rowsScrollbar = providerView.scrollbar()
                        .filter(scrollbar -> scrollbar.track().contains(mouseX, mouseY));
                if (rowsScrollbar.isPresent()) {
                    ViewSpec.Scrollbar scrollbar = rowsScrollbar.orElseThrow();
                    draggingProviderRowsScrollbar = true;
                    providerRowsScrollbarGrabOffset = scrollbar.thumb().contains(mouseX, mouseY)
                            ? mouseY - scrollbar.thumb().y() : scrollbar.thumb().height() / 2.0;
                    setProviderRowsFromScrollbar(scrollbar, mouseY - providerRowsScrollbarGrabOffset);
                    return;
                }
                state.providerPreview = state.providerPreview.beginRotate(new Bounds(0, 0, viewportWidth / 2, viewportHeight), mouseX, mouseY);
                publish();
                return;
            }
            if (state.editor != null) {
                ViewSpec editorView = state.editor.present(
                        viewportWidth, viewportHeight, editorCapeScrollPosition, editorModelScrollPosition,
                        viewChromeMetrics);
                Optional<ViewSpec.Scrollbar> capeScrollbar = editorView.scrollbar()
                        .filter(scrollbar -> scrollbar.orientation()
                                == ViewSpec.Scrollbar.Orientation.VERTICAL)
                        .filter(scrollbar -> scrollbar.track().contains(mouseX, mouseY));
                if (capeScrollbar.isPresent()) {
                    ViewSpec.Scrollbar scrollbar = capeScrollbar.orElseThrow();
                    draggingEditorScrollbar = true;
                    editorScrollbarGrabOffset = scrollbar.thumb().contains(mouseX, mouseY)
                            ? mouseY - scrollbar.thumb().y()
                            : scrollbar.thumb().height() / 2.0;
                    setEditorContentPosition(editorPositionFromScrollbar(
                            viewportWidth,
                            viewportHeight,
                            mouseY - editorScrollbarGrabOffset));
                    return;
                }
                Bounds previewBounds = editorView
                        .previews()
                        .get(0)
                        .anchorBounds();
                state.editor = state.editor.withPreview(
                        state.editor.preview().beginRotate(previewBounds, mouseX, mouseY));
                publish();
                return;
            }
            if (state.addSource != null) {
                if (state.addSource.personalSkinDeletion().isPresent()) {
                    return;
                }
                ViewSpec addSource = addSourcePresenter.present(
                        state.addSource,
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
                    draggingAddSourceScrollbar = true;
                    addSourceScrollbarGrabOffset = scrollbar.thumb().contains(mouseX, mouseY)
                            ? mouseY - scrollbar.thumb().y()
                            : scrollbar.thumb().height() / 2.0;
                    setAddSourceOffset(addSourcePresenter.offsetFromScrollbar(
                            state.addSource,
                            viewportWidth,
                            viewportHeight,
                            mouseY - addSourceScrollbarGrabOffset,
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
                draggingGalleryScrollbar = true;
                boolean grabbedThumb = scrollbar.thumb().contains(mouseX, mouseY);
                galleryScrollbarGrabOffset = grabbedThumb
                        ? mouseX - scrollbar.thumb().x()
                        : scrollbar.thumb().width() / 2.0;
                if (!grabbedThumb) {
                    setGalleryPosition(galleryPresenter.positionFromScrollbar(
                            snapshot, viewportWidth, viewportHeight, state.galleryQuery,
                            mouseX - galleryScrollbarGrabOffset));
                }
            });
        });
    }

    public void pointerDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        onClient(() -> {
            if (button != 0) {
                return;
            }
            if (draggingProviderRowsScrollbar) {
                viewInternal(viewportWidth, viewportHeight, (int) mouseX, (int) mouseY).scrollbar()
                        .ifPresent(scrollbar -> setProviderRowsFromScrollbar(scrollbar,
                                mouseY - providerRowsScrollbarGrabOffset));
            } else if (state.providerPreview.rotating()) {
                state.providerPreview = state.providerPreview.drag(deltaX, deltaY);
                publish();
            } else if (draggingEditorScrollbar && state.editor != null) {
                setEditorContentPosition(editorPositionFromScrollbar(
                        viewportWidth,
                        viewportHeight,
                        mouseY - editorScrollbarGrabOffset));
            } else if (state.editor != null && state.editor.preview().rotating()) {
                state.editor = state.editor.withPreview(state.editor.preview().drag(deltaX, deltaY));
                publish();
            } else if (draggingAddSourceScrollbar
                    && state.addSource != null
                    && state.addSource.personalSkinDeletion().isEmpty()) {
                setAddSourceOffset(addSourcePresenter.offsetFromScrollbar(
                        state.addSource,
                        viewportWidth,
                        viewportHeight,
                        mouseY - addSourceScrollbarGrabOffset,
                        viewChromeMetrics));
            } else if (draggingGalleryScrollbar) {
                setGalleryPosition(galleryPresenter.positionFromScrollbar(
                        snapshot, viewportWidth, viewportHeight, state.galleryQuery,
                        mouseX - galleryScrollbarGrabOffset));
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
            state.providerPreview = state.providerPreview.endRotate();
            draggingProviderRowsScrollbar = false;
            draggingGalleryScrollbar = false;
            draggingEditorScrollbar = false;
            draggingAddSourceScrollbar = false;
            if (state.editor != null && state.editor.preview().rotating()) {
                state.editor = state.editor.withPreview(state.editor.preview().endRotate());
                publish();
            }
        });
    }

    public void pointerScrolled(
            double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        onClient(() -> {
            if (state.providersOpen || !state.providers.galleryAvailable()) {
                if (state.providerAdding) return;
                ViewSpec providerView = viewInternal(viewportWidth, viewportHeight, (int) mouseX, (int) mouseY);
                ViewSpec.ScrollSurface rows = providerView.scrollSurface("providers.rows").orElse(null);
                double amount = dominantScrollAmount(horizontalAmount, verticalAmount);
                if (rows != null && rows.viewport().contains(mouseX, mouseY) && amount != 0.0) {
                    state.providerRowsOffset = Math.max(0, Math.min(rows.maximumPixels(),
                            rows.offsetPixels() - amount * WHEEL_SCROLL_PIXELS));
                    publish();
                    return;
                }
                state.providerPreview = state.providerPreview.scroll(new Bounds(0, 0, viewportWidth / 2, viewportHeight), mouseX, mouseY, verticalAmount);
                publish();
                return;
            }
            if (state.editor != null) {
                ViewSpec editorView = state.editor.present(
                        viewportWidth, viewportHeight, editorCapeScrollPosition, editorModelScrollPosition,
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
                PreviewInteractionModel changed = state.editor.preview()
                        .scroll(previewBounds, mouseX, mouseY, verticalAmount);
                if (changed != state.editor.preview()) {
                    state.editor = state.editor.withPreview(changed);
                    publish();
                }
                return;
            }
            if (state.externalImport != null && state.externalImport.review().isPresent()) {
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
                            state.externalImport.review().orElseThrow();
                    state.externalImport = state.externalImport.withReviewScroll(
                            Math.max(0, review.scrollOffset() - (int) Math.round(amount * 32.0)));
                    publish();
                }
                return;
            }
            if (state.addSource != null) {
                if (state.addSource.personalSkinDeletion().isPresent()) {
                    return;
                }
                ViewSpec addSourceView = view(
                        viewportWidth, viewportHeight, (int) mouseX, (int) mouseY);
                if (state.addSource.selectedTab() == AddSourceTab.CATALOG
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
                    if (state.editor != null || state.addSource != null) {
                        return;
                    }
                    setGalleryPosition(offsetPixels);
                }
                case "add.catalog" -> {
                    if (state.addSource == null
                            || state.addSource.selectedTab() != AddSourceTab.CATALOG
                            || state.addSource.personalSkinDeletion().isPresent()) {
                        return;
                    }
                    setAddSourceOffset((int) Math.round(offsetPixels));
                }
                case "providers.chooser" -> {
                    if (!state.providerAdding) return;
                    state.providerChooserOffset = offsetPixels;
                    publish();
                }
                case "providers.rows" -> {
                    if (state.providerAdding || !(state.providersOpen || !state.providers.galleryAvailable())) return;
                    state.providerRowsOffset = Math.max(0, offsetPixels);
                    publish();
                }
                case "external.review" -> {
                    if (state.externalImport == null || state.externalImport.review().isEmpty()) {
                        return;
                    }
                    state.externalImport = state.externalImport.withReviewScroll(
                            Math.max(0, (int) Math.round(offsetPixels)));
                    publish();
                }
                case "editor.models" -> {
                    if (state.editor == null || state.editor.selectedEditorTab() != EditorTab.APPEARANCE) {
                        return;
                    }
                    setEditorContentPosition(offsetPixels);
                }
                case "editor.capes" -> {
                    if (state.editor == null || state.editor.selectedEditorTab() != EditorTab.CAPE) {
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
        int travel = scrollbar.track().height() - scrollbar.thumb().height();
        double fraction = travel <= 0 ? 0.0
                : (thumbY - scrollbar.track().y()) / travel;
        state.providerRowsOffset = Math.max(0, Math.min(scrollbar.maximum(),
                fraction * scrollbar.maximum()));
        publish();
    }


    public CompletableFuture<Optional<byte[]>> loadSkinPreview(SkinReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (reference.optionalAssetId().isEmpty()) {
            return publishPreview(Optional.empty());
        }
        UUID skinId = reference.assetId();
        return requestPreview(
                "skin:" + skinId,
                () -> Optional.of(operations.loadSkinPreview(skinId)));
    }


    public CompletableFuture<Optional<byte[]>> loadSkinPreview(ViewSpec.Preview preview) {
        Objects.requireNonNull(preview, "preview");
        if (preview.imageRevision().startsWith("provider:skin:")) {
            return loadProviderTexture(new ViewSpec.ProviderTexture(preview.imageRevision().substring(14), true, false));
        }
        PresetEditorModel editor = state.editor;
        if ("editor.preview".equals(preview.id()) && editor != null && editor.png().isPresent()) {
            CompletableFuture<Optional<byte[]>> loaded =
                    publishPreview(Optional.of(editor.png().orElseThrow().bytes()));
            observeSkinPreview(preview, loaded, false);
            return loaded;
        }
        if (preview.catalogImage().isPresent()) {
            ViewSpec.CatalogImage image = preview.catalogImage().orElseThrow();
            SkinModel model = preview.variant() == SkinVariant.SLIM
                    ? SkinModel.SLIM
                    : SkinModel.CLASSIC;
            CompletableFuture<Optional<byte[]>> loaded = requestPreview(
                    "catalog:"
                            + catalogPreviewEpoch
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
            Optional<ClientOperations.ExternalImportCandidate> candidate = state.externalImport == null
                    ? Optional.empty()
                    : state.externalImport.candidate(candidateId);
            CompletableFuture<Optional<byte[]>> loaded = publishPreview(
                    candidate.map(ClientOperations.ExternalImportCandidate::normalizedPng));
            observeSkinPreview(preview, loaded, false);
            return loaded;
        }
        CompletableFuture<Optional<byte[]>> loaded = loadSkinPreview(preview.skin());
        observeSkinPreview(preview, loaded, preview.skin().optionalAssetId().isPresent());
        return loaded;
    }


    public CompletableFuture<Optional<byte[]>> loadProviderTexture(ViewSpec.ProviderTexture texture) {
        return requestPreview(texture.requestKey(), () -> operations.loadProviderTexture(texture));
    }

    public CompletableFuture<Optional<byte[]>> loadCapePreview(String capeId) {
        Objects.requireNonNull(capeId, "capeId");
        if (capeId.startsWith("provider:cape:")) {
            return loadProviderTexture(new ViewSpec.ProviderTexture(capeId.substring(14), false, false));
        }
        if (capeId.startsWith("resource:cape:") && state.editor != null
                && state.editor.capeCatalog() != null && state.account != null) {
            Optional<ClientOperations.ResourceCapeSelection> selection =
                    state.editor.capeCatalog().cards().stream()
                            .filter(card -> card.texture().filter(capeId::equals).isPresent())
                            .map(CapeCatalogModel.Card::resource)
                            .filter(Objects::nonNull)
                            .findFirst();
            if (selection.isPresent()) {
                UUID accountId = state.account.accountId();
                return requestPreview(capeId, () -> operations.loadResourceCapePreview(
                        accountId, selection.orElseThrow()));
            }
            return publishPreview(Optional.empty());
        }
        return requestPreview("cape:" + capeId, () -> operations.loadCapePreview(capeId));
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
                            changed = !evidence.equals(state.editorEvidence);
                            state.editorEvidence = evidence;
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
                            changed |= !evidence.equals(state.catalogEvidence.put(
                                    catalogEvidenceKey(
                                            image.collectionId(),
                                            image.skinId(),
                                            preview.variant()),
                                    evidence));
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
        return editorEvidenceStillCurrent(preview.imageRevision(), preview.variant());
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
                    state.selectedSkinId = account.skinAssets().stream()
                            .map(SkinAsset::id)
                            .filter(id -> !previous.contains(id))
                            .findFirst()
                            .orElse(null);
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
                    if (skinId.equals(state.selectedSkinId)) {
                        state.selectedSkinId = null;
                    }
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
                                ClientOperations.ReconciliationTrigger.LOCAL_INTENT);
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
            state.editor = null;
            state.editorEvidence = null;
            state.addSource = null;
            appearanceRefresh.ifPresent(AppearanceRefreshCoordinator::close);
            serverAppearanceReadiness.ifPresent(ServerAppearanceReadinessCoordinator::close);
            capeObservations.closeOptiFineCapes();
            operations.close();
            previewInFlight.clear();
            previewBytes.clear();


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
            synchronized (reconciliationMonitor) {
                pendingReconciliations.clear();
                activeReconciliation = null;
                reconciliationRunning = false;
            }
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
            optiFineLinkFeedback = null;
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
        state.providerComponent = data.uiPreferences().selectedProvidersTab();
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
                                    if (state.editor != null && state.editor.capeCatalog() != null) state.editor = state.editor.withCapeCatalog(
                                            state.editor.capeCatalog().withOwnedClassification(value.capes()));
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
        state.providerComponent = data.uiPreferences().selectedProvidersTab();
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
        dispatchProviderWidget(id, InteractionOrigin.PROGRAMMATIC);
    }

    private void dispatchProviderWidget(String id, InteractionOrigin origin) {
        if (id.startsWith("providers.") && !id.equals("providers.back")) state.providersOpen = true;
        if (id.equals("gallery.providers")) {
            state.providersOpen = true;
            state.providerPreviewSources.clear();
            submit(UiMessage.info("nclskins.providers.title"), operations::reloadProviders, appearance -> {
                acceptProviderChange(appearance);
                adoptSharedCapeObservation(appearance.providers());
            });
            state.providerPreview = PreviewInteractionModel.editor(viewportHeight, preferredCapeMode);
        } else if (id.equals("providers.back")) {
            cancelOptiFineAccountLink();
            cancelSkinMcAccountLink();
            cancelSneakyEditorLink();
            if (state.providerAdding) state.providerAdding = false;
            else if (state.rootDestination == ScreenDestination.PROVIDERS) closeScreenOnClient();
            else if (state.providers.galleryAvailable()) state.providersOpen = false;
            else closeScreenOnClient();
        } else if (id.startsWith("providers.tab.")) {
            cancelOptiFineAccountLink();
            cancelSkinMcAccountLink();
            cancelSneakyEditorLink();
            selectProvidersTab(AppearanceProviders.Component.valueOf(id.substring(14)));
            state.providerPreviewSources.clear();
            state.providerAdding = false;
            state.providerRowsOffset = 0;
        } else if (id.equals("providers.add")) {
            cancelOptiFineAccountLink();
            cancelSkinMcAccountLink();
            cancelSneakyEditorLink();
            state.providerAdding = true;
            state.providerChooserOffset = 0;
            state.providerRowsOffset = 0;
        } else if (id.equals("providers.preview_mode")) {
            state.providerPreview = state.providerPreview.cycleCapeMode(true);
            preferredCapeMode = state.providerPreview.capeMode();
            PreviewPreferences.setCapeMode(preferredCapeMode);
            if (state.editor != null) {
                state.editor = state.editor.withPreview(
                        state.editor.preview().withCapeMode(preferredCapeMode));
            }
        } else {
            var component = state.providerComponent;
            String[] action = id.split("\\.");
            if (action.length < 2 || state.busy) return;
            if (id.equals("providers.refresh")) {
                RefreshComparison comparison = captureRefreshComparison(component);
                submit(UiMessage.info("nclskins.providers.refresh"),
                        () -> operations.refreshProvidersWithObservation(component), result -> {
                    ClientOperations.DurableAppearance appearance = result.appearance();
                    acceptProviderChange(appearance);
                    if (component == AppearanceProviders.Component.CAPE) {
                        AppearanceProviders confirmedMinecraft = appearance.providers();
                        CompletableFuture<ProviderObservation<ProviderCape>> optifine = new CompletableFuture<>();
                        CompletableFuture<ProviderObservation<ProviderCape>> skinmc = new CompletableFuture<>();
                        CompletableFuture.allOf(optifine, skinmc).thenRun(() -> onClient(() ->
                                finishRefreshComparison(comparison, confirmedMinecraft,
                                        result.confirmedMinecraft(), optifine.join(), skinmc.join())));
                        try {
                            capeObservations.refreshOptiFineCapes(optifine::complete);
                        } catch (RuntimeException unavailable) {
                            optifine.complete(null);
                        }
                        try {
                            capeObservations.refreshSkinMcCapes(skinmc::complete);
                        } catch (RuntimeException unavailable) {
                            skinmc.complete(null);
                        }
                    } else {
                        finishRefreshComparison(comparison, appearance.providers(),
                                result.confirmedMinecraft(), null, null);
                    }
                });
            } else if (action.length == 3) {
                BuiltinProvider provider = BuiltinProvider.valueOf(action[2]);
                String verb = action[1];
                if (verb.equals("edit")) {
                    if (state.providerAdding || !state.providers.galleryAvailable()
                            || !provider.writable()
                            || !(component == AppearanceProviders.Component.SKIN ? state.providers.skin().order()
                            : state.providers.cape().order()).contains(provider)) return;
                    cancelOptiFineAccountLink();
                    cancelSkinMcAccountLink();
                    cancelSneakyEditorLink();
                    state.providersOpen = false;
                    clearRuntimeFocus("providers");
                    if (component == AppearanceProviders.Component.CAPE) {
                        openEditor(state.activePresetId);
                        state.editorReturnsToProviders = state.editor != null;
                        selectEditorTab(EditorTab.CAPE);
                        if (state.editor != null && state.editor.capeCatalog() != null) {
                            state.editor = state.editor.withCapeCatalog(state.editor.capeCatalog().inspect(provider, state.account));
                            var inspected = state.editor.capeCatalog().inspected();
                            pendingCapeProviderInspection = inspected != null && inspected.resource() == null
                                    ? provider : null;
                            editorCapeScrollPosition = state.editor.initialCapeScrollPosition(
                                    viewportWidth, viewportHeight, viewChromeMetrics);
                            editorCapeScrollTarget = editorCapeScrollPosition;
                        }
                    } else {
                        state.galleryReturnsToProviders = true;
                    }
                    publish();
                    return;
                }
                if (verb.equals("account")) {
                    if ((provider != BuiltinProvider.OPTIFINE && provider != BuiltinProvider.SKINMC
                            && provider != BuiltinProvider.SNEAKY)
                            || component != AppearanceProviders.Component.CAPE
                            || state.providerAdding || !state.providers.cape().order().contains(provider)) return;
                    if (provider == BuiltinProvider.OPTIFINE) prepareOptiFineAccountLink();
                    else if (provider == BuiltinProvider.SNEAKY) {
                        sneakyEditorLinkPending = true;
                        sneakyEditorLinkClaimed = false;
                        publish();
                    } else {
                        skinMcLinkAccountId = state.account.accountId();
                        skinMcLinkPending = true;
                        skinMcLinkClaimed = false;
                        publish();
                    }
                    return;
                }
                if (verb.equals("row") && state.providerAdding && (component == AppearanceProviders.Component.SKIN
                        ? state.providers.skin().order() : state.providers.cape().order()).contains(provider)) return;
                if (verb.equals("row") && !state.providerAdding) { state.providerPreviewSources.put(component, provider); publish(); return; }
                if (verb.equals("remove") && provider == BuiltinProvider.OPTIFINE
                        && component == AppearanceProviders.Component.CAPE) cancelOptiFineAccountLink();
                if (verb.equals("remove") && provider == BuiltinProvider.SKINMC
                        && component == AppearanceProviders.Component.CAPE) cancelSkinMcAccountLink();
                if (verb.equals("remove") && provider == BuiltinProvider.SNEAKY
                        && component == AppearanceProviders.Component.CAPE) cancelSneakyEditorLink();
                int previousIndex = (component == AppearanceProviders.Component.SKIN ? state.providers.skin().order() : state.providers.cape().order()).indexOf(provider);
                UUID accountId = state.account.accountId();
                submitProviderConfiguration(() -> switch (verb) {
                    case "row" -> operations.enableProvider(accountId, component, provider);
                    case "remove" -> operations.disableProvider(accountId, component, provider);
                    case "up" -> operations.moveProvider(accountId, component, provider, -1);
                    case "down" -> operations.moveProvider(accountId, component, provider, 1);
                    default -> throw new IllegalArgumentException("Unknown provider action");
                }, appearance -> {
                    CompletableFuture<AppearanceRefreshCoordinator.Result> providerRebind =
                            acceptProviderChange(appearance);
                    capeObservations.optiFineConfigurationChanged();
                    state.providerAdding = false;
                    if (origin == InteractionOrigin.KEYBOARD) {
                        var remaining = component == AppearanceProviders.Component.SKIN ? state.providers.skin().order() : state.providers.cape().order();
                        String target = "providers.row." + provider.name();
                        if (verb.equals("remove")) target = remaining.isEmpty() ? "providers.add"
                                : "providers.row." + remaining.get(Math.max(0, Math.min(previousIndex, remaining.size() - 1))).name();
                        requestRuntimeFocus("providers", target);
                    }
                    if (verb.equals("remove") && state.providerPreviewSources.get(component) == provider) state.providerPreviewSources.remove(component);
                    if (verb.equals("row")) reconcileAfterLocalRebind(
                            providerRebind, ClientOperations.ReconciliationTrigger.LOCAL_INTENT);
                });
            }
        }
        publish();
    }

    private void prepareOptiFineAccountLink() {
        if (!liveOptiFineLinkView() || optiFineAccountLink.preparing()) return;
        UUID accountId = state.account.accountId();
        long attempt = ++optiFineLinkAttempt;
        optiFineLinkClaimed = false;
        optiFineLinkFeedback = UiMessage.info("nclskins.providers.link_preparing");
        publish();
        optiFineAccountLink.begin(accountId).whenComplete((result, failure) -> onClient(() -> {
            if (disposed || attempt != optiFineLinkAttempt || !liveOptiFineLinkView()
                    || !accountId.equals(state.account.accountId())) return;
            OptiFineAccountLink.Outcome outcome = failure == null ? result.outcome() : OptiFineAccountLink.Outcome.FAILED;
            optiFineLinkFeedback = switch (outcome) {
                case READY, CANCELLED -> null;
                case AUTH_REQUIRED -> UiMessage.error("nclskins.providers.link_auth_required");
                case FAILED -> UiMessage.error("nclskins.providers.link_failed");
                case EXPIRED -> UiMessage.error("nclskins.providers.link_expired");
            };
            publish();
        }));
    }

    private void submitProviderConfiguration(ThrowingSupplier<ClientOperations.DurableAppearance> operation,
            Consumer<ClientOperations.DurableAppearance> completion) {
        UUID accountId = state.account.accountId();
        providerConfigurationWrite = providerConfigurationWrite.handle((ignored, failure) -> null).thenRunAsync(() -> {
            if (disposed || state.account == null || !accountId.equals(state.account.accountId())) return;
            try {
                var appearance = operation.get();
                onClient(() -> {
                    if (disposed || state.lifecycle == ClientSnapshot.Lifecycle.CLOSED || state.account == null
                            || !accountId.equals(state.account.accountId()) || !currentSessionOwns(appearance)
                            || appearance.providers().skin().configurationRevision() < state.providers.skin().configurationRevision()
                            || appearance.providers().cape().configurationRevision() < state.providers.cape().configurationRevision()
                            || appearance.intentRevision() < state.intentRevision) return;
                    completion.accept(appearance);
                    publish();
                });
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                diagnose(DiagnosticEvent.CLIENT_ASYNC_OPERATION_FAILED, failure);
                onClient(() -> {
                    if (state.account != null && accountId.equals(state.account.accountId())) { state.status = operationFailure(failure); publish(); }
                });
            }
        }, worker);
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
        boolean gameChanged = providerChainChanged(state.providers.skin(), appearance.providers().skin())
                || providerChainChanged(state.providers.cape(), appearance.providers().cape());
        boolean localChanged = !Objects.equals(state.localAppearance,
                appearance.localAppearance().orElse(null));
        if (!appearance.providers().cape().enabled(BuiltinProvider.OPTIFINE)) cancelOptiFineAccountLink();
        if (!appearance.providers().cape().enabled(BuiltinProvider.SKINMC)) cancelSkinMcAccountLink();
        if (!appearance.providers().cape().enabled(BuiltinProvider.SNEAKY)) cancelSneakyEditorLink();
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

    private static boolean providerChainChanged(
            com.naocraftlab.skins.core.provider.ProviderChannel<?> before,
            com.naocraftlab.skins.core.provider.ProviderChannel<?> after) {
        if (!before.order().equals(after.order())) return true;
        for (BuiltinProvider provider : before.order()) {
            if (!before.observation(provider).equals(after.observation(provider))) return true;
        }
        return false;
    }

    private void adoptSharedCapeObservation(AppearanceProviders providers) {
        if (state.account == null) return;
        try {
            GameSessionTokenSource.SessionIdentity identity = operations.sessionIdentity();
            if (identity.profileId().equals(state.account.accountId())) {
                capeObservations.adoptSharedCapeObservation(identity.profileId(), identity.profileName(), providers);
            }
        } catch (RuntimeException unavailable) {
            return;
        }
    }

    private RefreshComparison captureRefreshComparison(AppearanceProviders.Component component) {
        if (state.account == null) return null;
        try {
            GameSessionTokenSource.SessionIdentity identity = operations.sessionIdentity();
            if (!identity.profileId().equals(state.account.accountId())) return null;
            return new RefreshComparison(identity.profileId(), identity.profileName(),
                    component, state.providers);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private void finishRefreshComparison(RefreshComparison comparison,
            AppearanceProviders confirmedState, ProviderObservation<?> confirmedMinecraft,
            ProviderObservation<ProviderCape> optifine,
            ProviderObservation<ProviderCape> skinmc) {
        if (comparison == null || disposed || state.account == null
                || !comparison.accountId().equals(state.account.accountId())) return;
        GameSessionTokenSource.SessionIdentity identity;
        try {
            identity = operations.sessionIdentity();
        } catch (RuntimeException unavailable) {
            return;
        }
        if (!comparison.accountId().equals(identity.profileId())
                || !comparison.canonicalName().equals(identity.profileName())) return;
        var before = comparison.component() == AppearanceProviders.Component.SKIN
                ? comparison.providers().skin() : comparison.providers().cape();
        var after = comparison.component() == AppearanceProviders.Component.SKIN
                ? confirmedState.skin() : confirmedState.cape();
        if (before.configurationRevision() != after.configurationRevision()
                || !before.order().equals(after.order())
                || (comparison.component() == AppearanceProviders.Component.CAPE
                    && state.providers.cape().configurationRevision() != after.configurationRevision())) return;
        ProviderObservation<?> oldMinecraft = before.observation(BuiltinProvider.MINECRAFT);
        boolean changed = false;
        if (before.enabled(BuiltinProvider.MINECRAFT) && oldMinecraft.known()
                && confirmedMinecraft != null && confirmedMinecraft.known()) {
            changed = comparison.component() == AppearanceProviders.Component.CAPE
                    ? !sameCapeContent((ProviderCape) oldMinecraft.value(),
                            (ProviderCape) confirmedMinecraft.value())
                    : !oldMinecraft.equals(confirmedMinecraft);
        }
        if (comparison.component() == AppearanceProviders.Component.CAPE
                && before.enabled(BuiltinProvider.OPTIFINE) && optifine != null) {
            ProviderObservation<ProviderCape> oldOptifine = comparison.providers().cape().optifine();
            changed |= oldOptifine.known() && optifine.known()
                    && !sameCapeContent(oldOptifine.value(), optifine.value());
        }
        if (comparison.component() == AppearanceProviders.Component.CAPE
                && before.enabled(BuiltinProvider.SKINMC) && skinmc != null) {
            ProviderObservation<ProviderCape> oldSkinMc = comparison.providers().cape().skinmc();
            changed |= oldSkinMc.known() && skinmc.known()
                    && !sameCapeContent(oldSkinMc.value(), skinmc.value());
        }
        if (changed) {
            serverAppearanceReadiness.ifPresent(ServerAppearanceReadinessCoordinator::start);
        }
    }

    private static boolean sameCapeContent(ProviderCape left, ProviderCape right) {
        if (left == null || right == null) return left == right;
        if (left.textureCacheKey() != null && right.textureCacheKey() != null) {
            return left.textureCacheKey().equals(right.textureCacheKey())
                    && Objects.equals(left.hasElytra(), right.hasElytra());
        }
        return left.equals(right);
    }

    private record RefreshComparison(UUID accountId, String canonicalName,
            AppearanceProviders.Component component, AppearanceProviders providers) {}

    private void dispatchWidgetOnClient(
            String widgetId, boolean reverse, InteractionOrigin origin) {
        ensureNotDisposed();
        clearFileImportError();
        if (state.lifecycle == ClientSnapshot.Lifecycle.CLOSED) {
            return;
        }
        if (widgetId.equals("gallery.providers") || widgetId.startsWith("providers.")) {
            dispatchProviderWidget(widgetId, origin);
            return;
        }
        if (widgetId.startsWith("gallery.preset.")) {
            dispatchPresetWidget(widgetId, origin);
            return;
        }
        if (widgetId.startsWith("gallery.card.")) {
            selectGalleryCard(widgetId, origin);
            return;
        }
        if (widgetId.startsWith("add.catalog.collection:")) {
            toggleCatalogCollection(widgetId.substring("add.catalog.collection:".length()));
            return;
        }
        if (widgetId.startsWith("add.catalog.delete:")) {
            personalCatalogAction(widgetId, "add.catalog.delete:")
                    .ifPresent(action -> requestPersonalSkinDeletion(
                            action.collectionId(), action.sha256(), origin));
            return;
        }
        if (widgetId.startsWith("add.catalog.rename:")) {
            personalCatalogAction(widgetId, "add.catalog.rename:")
                    .ifPresent(action -> requestPersonalSkinRename(
                            action.collectionId(), action.sha256()));
            return;
        }
        if (widgetId.startsWith("add.catalog.skin:")) {
            selectCatalogSkin(widgetId.substring("add.catalog.skin:".length()));
            return;
        }
        if (widgetId.startsWith("external.source.")) {
            prepareExternalImport(ExternalImportPresenter.source(widgetId));
            return;
        }
        if (widgetId.startsWith("external.folder.")) {
            chooseExternalImportFolder(ExternalImportPresenter.source(widgetId));
            return;
        }
        if (widgetId.startsWith("external.review.card:")) {
            toggleExternalCandidate(widgetId.substring("external.review.card:".length()));
            return;
        }
        if (widgetId.startsWith("external.review.collection.")) {
            toggleExternalCollection(widgetId.endsWith("duplicates"));
            return;
        }
        if (widgetId.startsWith("editor.outer_layer.")) {
            cycleEditorOuterLayer(
                    widgetId.substring("editor.outer_layer.".length()), reverse);
            return;
        }
        if (state.editor != null && state.editor.capeCatalog() != null) {
            state.editor = state.editor.withCapeCatalog(state.editor.capeCatalog().error(false));
            if (dispatchCapeCatalog(widgetId, reverse, origin)) return;
        }
        if (widgetId.startsWith("editor.cape_choice.")) {
            try {
                int index = Integer.parseInt(widgetId.substring("editor.cape_choice.".length()));
                updateEditor(editor -> editor.selectCape(index));
            } catch (NumberFormatException ignored) {

            }
            return;
        }
        switch (widgetId) {
            case "gallery.add" -> openAddSource();
            case "gallery.retry_session" -> retrySession();
            case "gallery.retry_cape" -> retrySelectedCape();
            case "gallery.done" -> {
                if (state.galleryReturnsToProviders) {
                    closeToProviders();
                } else {
                    closeScreen();
                }
            }
            case "add.tab.file" -> selectAddSourceTab(AddSourceTab.FILE);
            case "add.tab.catalog" -> selectAddSourceTab(AddSourceTab.CATALOG);
            case "add.file.choose" -> chooseAddSourcePng();
            case "add.external.launcher" -> openExternalImport(ExternalImportModel.Category.LAUNCHER);
            case "add.external.mod" -> openExternalImport(ExternalImportModel.Category.MOD);
            case "add.player.load" -> loadRemoteImport(true);
            case "add.url.load" -> loadRemoteImport(false);
            case "add.catalog.filter" -> cycleCatalogFilter(reverse);
            case "add.catalog.disclosure" ->
                    toggleAllCatalogCollections(origin, widgetId);
            case "add.catalog.delete.confirm" -> confirmPersonalSkinDeletion(origin);
            case "add.catalog.delete.cancel" -> cancelPersonalSkinDeletion(origin);
            case "add.catalog.rename.save" -> savePersonalSkinRename();
            case "add.catalog.rename.cancel" -> cancelPersonalSkinRename();
            case "add.cancel" -> cancelAddSource();
            case "external.back" -> cancelExternalImport();
            case "external.review.toggle_all" -> toggleAllExternalCandidates();
            case "external.review.disclosure" -> {
                toggleAllExternalCollections();
                retainKeyboardFocus(origin, "external_review", widgetId);
            }
            case "external.review.commit" -> commitExternalImport();
            case "external.review.cancel" -> cancelExternalReview();
            case "editor.tab.appearance", "editor.tab.cape" -> {
                selectEditorTab(widgetId.endsWith("appearance") ? EditorTab.APPEARANCE : EditorTab.CAPE);
                retainKeyboardFocus(origin, "preset_editor", widgetId);
            }
            case "editor.model_choice.classic" -> selectEditorVariant(SkinVariant.CLASSIC);
            case "editor.model_choice.slim" -> selectEditorVariant(SkinVariant.SLIM);
            case "editor.cape" -> updateEditor(editor -> editor.cycleCape(reverse ? -1 : 1));
            case "editor.preview_mode" -> {
                updateEditor(editor -> editor.cyclePreviewMode(reverse ? -1 : 1));
                if (state.editor != null) {
                    preferredCapeMode = state.editor.preview().capeMode();
                    if (preferredCapeMode != PreviewRenderer.CapeMode.OFF) {
                        PreviewPreferences.setCapeMode(preferredCapeMode);
                        state.providerPreview = state.providerPreview.withCapeMode(preferredCapeMode);
                    }
                }
            }
            case "editor.save" -> saveEditor();
            case "editor.cancel" -> cancelEditor();
            default -> {

            }
        }
    }

    private void cycleEditorOuterLayer(String action, boolean reverse) {
        switch (action) {
            case "head", "body", "legs" -> updateEditor(
                    editor -> editor.cycleOuterLayer(action, reverse ? -1 : 1));
            default -> {

            }
        }
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
        int actionSeparator = widgetId.lastIndexOf('.');
        if (actionSeparator <= "gallery.preset.".length()) {
            return;
        }
        UUID presetId;
        try {
            presetId = UUID.fromString(widgetId.substring("gallery.preset.".length(), actionSeparator));
        } catch (IllegalArgumentException malformedId) {
            return;
        }
        String action = widgetId.substring(actionSeparator + 1);
        switch (action) {
            case "apply" -> applyPreset(presetId, false);
            case "edit" -> openEditor(presetId);
            case "duplicate" -> duplicatePreset(presetId);
            case "delete" -> requestPresetDeletion(presetId, origin);
            case "delete_confirm" -> deletePreset(presetId, origin);
            case "delete_cancel" -> cancelPresetDeletion(presetId, origin);
            default -> {

            }
        }
    }

    private void selectGalleryCard(String widgetId, InteractionOrigin origin) {
        if (!galleryPresenter.cardIds(snapshot, state.galleryQuery).contains(widgetId)) {
            return;
        }
        boolean changed = !widgetId.equals(state.gallerySelectedCardId);
        if (changed) {
            state.gallerySelectedCardId = widgetId;
        }
        if (origin.keyboard()) {
            requestRuntimeFocus("gallery", widgetId);
        }
        if (changed || origin.keyboard()) {
            publish();
        }
    }

    private void openAddSource() {
        openAddSource(null);
    }

    private void openAddSource(AddSourceTab override) {
        if (state.busy || state.account == null) {
            return;
        }
        state.pendingPresetDeleteId = null;
        clearRuntimeFocus("gallery");
        state.pendingPresetName = galleryPresenter.matchingPresetCount(
                                snapshot, state.galleryQuery) == 0
                        && !state.galleryQuery.isBlank()
                ? UntrustedDisplayName.sanitize(state.galleryQuery, "")
                : null;
        if (state.pendingPresetName != null && state.pendingPresetName.isBlank()) {
            state.pendingPresetName = null;
        }
        invalidateCatalogPreviews();
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
                    state.catalogEvidence.clear();
                    data.featureEvidence().forEach((variant, evidence) ->
                            state.catalogEvidence.put(
                                    catalogEvidenceKey(
                                            variant.collectionId(),
                                            variant.skinId(),
                                            variant.variant()),
                                    evidence));
                    state.addSource = AddSourceModel.open(
                                    data.preferences(), data.collections(), fallbackVariant, textResolver)
                            .withCompatibilityContext(
                                    catalogEnvironment,
                                    state.catalogEvidence,
                                    hideIncompatibleCatalogSkins);
                    resetAddSourceScroll();
                    state.selectedPresetId = null;
                    state.status = UiMessage.info("nclskins.external_import.choose_source");
                    if (override != null) selectAddSourceTab(override);
                },
                failure -> {
                    if (!accountId.equals(operations.sessionIdentity().profileId())) {
                        closeScreenOnClient();
                        return;
                    }
                    state.addSource = AddSourceModel.open(
                                    cachedPreferences, List.of(), fallbackVariant, textResolver)
                            .withCompatibilityContext(
                                    catalogEnvironment, Map.of(), hideIncompatibleCatalogSkins);
                    resetAddSourceScroll();
                    state.selectedPresetId = null;
                    state.status = UiMessage.info("nclskins.external_import.choose_source");
                    if (override != null) selectAddSourceTab(override);
                });
    }

    private void selectAddSourceTab(AddSourceTab tab) {
        if (state.busy
                || state.addSource == null
                || state.addSource.selectedTab() == tab) {
            return;
        }
        resetPersonalCatalogInteraction();
        state.addSource = state.addSource.withSelectedTab(tab);
        if (tab == AddSourceTab.FILE) {
            state.status = UiMessage.info("nclskins.external_import.choose_source");
        }
        if (state.uiPreferences != null) {
            state.uiPreferences = state.uiPreferences.withSelectedAddSourceTab(tab);
        }
        publish();
        UUID accountId = state.account.accountId();
        persistUiPreference(() -> {
            operations.setSelectedAddSourceTab(accountId, tab);
            return null;
        });
    }

    private void cycleCatalogFilter(boolean reverse) {
        if (state.busy
                || state.addSource == null
                || state.addSource.personalSkinDeletion().isPresent()
                || state.addSource.selectedTab() != AddSourceTab.CATALOG) {
            return;
        }
        state.addSource = state.addSource.cycleFilter(reverse);
        resetAddSourceScroll();
        if (state.addSource.filter() != AddSourceModel.CatalogFilter.ALL) {
            rememberPreferredSkinVariant(state.addSource.preferredVariant());
        }
        publish();
    }

    private void toggleCatalogCollection(String collectionId) {
        if (state.busy
                || state.addSource == null
                || state.addSource.selectedTab() != AddSourceTab.CATALOG
                || state.addSource.collections().stream()
                        .noneMatch(collection -> collection.id().equals(collectionId))) {
            return;
        }
        boolean collapsed = !state.addSource.collectionCollapsed(collectionId);
        if (collapsed && personalCatalogInteractionBelongsTo(collectionId)) {
            resetPersonalCatalogInteraction();
        }
        state.addSource = state.addSource.withCollectionCollapsed(collectionId, collapsed);
        clampAddSourceScroll();
        if (state.uiPreferences != null) {
            state.uiPreferences = state.uiPreferences.withCollectionCollapsed(collectionId, collapsed);
        }
        setAddSourceOffset(state.addSource.scrollOffset());
        publish();
        persistUiPreference(() -> {
            operations.setCollectionCollapsed(collectionId, collapsed);
            return null;
        });
    }

    private void toggleAllCatalogCollections(
            InteractionOrigin origin, String widgetId) {
        if (state.busy
                || state.addSource == null
                || state.addSource.selectedTab() != AddSourceTab.CATALOG
                || state.addSource.availableCollectionIds().isEmpty()) {
            return;
        }
        boolean collapsed = !state.addSource.anyAvailableCollectionCollapsed();
        AddSourceModel previousModel = state.addSource;
        AccountUiPreferences previousPreferences = state.uiPreferences;
        double previousPosition = addSourceScrollPosition;
        double previousTarget = addSourceScrollTarget;
        String previousRenameCollectionId = state.personalRenameCollectionId;
        String previousRenameHash = state.personalRenameHash;
        String previousRenameValue = state.personalRenameValue;
        if (collapsed) {
            resetPersonalCatalogInteraction();
        }
        state.addSource = state.addSource.withAvailableCollectionsCollapsed(collapsed);
        clampAddSourceScroll();
        Set<String> replacement = state.addSource.collapsedCollectionIds();
        if (state.uiPreferences != null) {
            state.uiPreferences = state.uiPreferences.withCollapsedCollectionIds(replacement);
        }
        addSourceScrollPosition = state.addSource.scrollOffset();
        addSourceScrollTarget = state.addSource.scrollOffset();
        if (origin.keyboard()) {
            requestRuntimeFocus("add_source", widgetId);
        }
        publish();
        long revision = ++catalogDisclosureRevision;
        long generation = state.generation;
        CompletableFuture.runAsync(() -> {
                    try {
                        operations.replaceCollapsedCollectionIds(replacement);
                    } catch (Exception failure) {
                        throw new CompletionException(failure);
                    }
                }, worker)
                .whenComplete((ignored, failure) -> onClient(() -> {
                    if (failure == null
                            || disposed
                            || revision != catalogDisclosureRevision
                            || generation != state.generation) {
                        return;
                    }
                    diagnose(DiagnosticEvent.CLIENT_ASYNC_OPERATION_FAILED, failure);
                    if (state.addSource == null
                            || !state.addSource.collapsedCollectionIds().equals(replacement)) {
                        return;
                    }
                    state.addSource = previousModel;
                    state.uiPreferences = previousPreferences;
                    addSourceScrollPosition = previousPosition;
                    addSourceScrollTarget = previousTarget;
                    state.personalRenameCollectionId = previousRenameCollectionId;
                    state.personalRenameHash = previousRenameHash;
                    state.personalRenameValue = previousRenameValue;
                    state.status = UiMessage.error("nclskins.add_source.disclosure_failed");
                    publish();
                }));
    }

    private void selectCatalogSkin(String encodedId) {
        if (state.busy
                || state.addSource == null
                || state.addSource.personalSkinDeletion().isPresent()
                || state.addSource.selectedTab() != AddSourceTab.CATALOG) {
            return;
        }
        int separator = encodedId.indexOf(':');
        if (separator <= 0 || separator == encodedId.length() - 1) {
            return;
        }
        String collectionId = encodedId.substring(0, separator);
        String skinId = encodedId.substring(separator + 1);
        SkinCatalogSource.CollectionDescriptor collection = state.addSource.collections().stream()
                .filter(value -> value.id().equals(collectionId))
                .findFirst()
                .orElse(null);
        if (collection == null) {
            return;
        }
        SkinCatalogSource.SkinDescriptor skin = collection.skins().stream()
                .filter(value -> value.id().equals(skinId))
                .findFirst()
                .orElse(null);
        if (skin == null || !state.addSource.visibleSkins(collection).contains(skin)) {
            return;
        }
        SkinVariant initialVariant = state.addSource.selectedVariant(skin);
        submit(
                UiMessage.info("nclskins.add_source.loading"),
                () -> loadCatalogSelection(collection, skin, initialVariant),
                selection -> {
                    String name = state.pendingPresetName != null
                            ? state.pendingPresetName
                            : textResolver.resolve(selection.skin().nameText());
                    state.editor = selection.reusableVariants().isEmpty()
                            ? PresetEditorModel.openCatalog(
                                    name,
                                    selection.origin().orElseThrow(),
                                    selection.variants(),
                                    selection.initialVariant(),
                                    editorProfile(),
                                    editorOwnedCapes(),
                                    viewportHeight,
                                    preferredCapeMode)
                            : PresetEditorModel.openPersonalCatalog(
                                    name,
                                    selection.reusableVariants(),
                                    selection.initialVariant(),
                                    editorProfile(),
                                    editorOwnedCapes(),
                                    viewportHeight,
                                    preferredCapeMode);
                    prepareEditorEvidence(state.editor);
                    initializeEditorCapeCatalog(null);
                    resetEditorScroll();
                    state.selectedPresetId = null;
                },
                failure -> state.status = UiMessage.error("nclskins.add_source.load_failed"));
    }

    private CatalogSelection loadCatalogSelection(
            SkinCatalogSource.CollectionDescriptor collection,
            SkinCatalogSource.SkinDescriptor skin,
            SkinVariant initialVariant) throws Exception {
        EnumMap<SkinVariant, byte[]> variants = new EnumMap<>(SkinVariant.class);
        EnumMap<SkinVariant, PresetEditorModel.ReusableCatalogVariant> reusableVariants =
                new EnumMap<>(SkinVariant.class);
        Exception firstFailure = null;
        for (SkinModel model : skin.models()) {
            SkinVariant variant = model == SkinModel.SLIM ? SkinVariant.SLIM : SkinVariant.CLASSIC;
            try {
                byte[] png = operations.loadCatalogSkin(collection.id(), skin.id(), model);
                variants.put(variant, png);
                Optional<UUID> reusable = operations.reusableCatalogSkinAsset(
                        collection.id(), skin.id(), model);
                reusable.ifPresent(assetId -> reusableVariants.put(
                        variant,
                        new PresetEditorModel.ReusableCatalogVariant(
                                SkinReference.asset(assetId), png)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception unavailableVariant) {
                if (firstFailure == null) {
                    firstFailure = unavailableVariant;
                }
            }
        }
        if (variants.isEmpty()) {
            if (firstFailure != null) {
                throw firstFailure;
            }
            throw new IOException("Catalog skin has no available model variants");
        }
        boolean personal = collection.order().kind() == CatalogCollectionOrder.Kind.PERSONAL;
        if (personal && !reusableVariants.keySet().equals(variants.keySet())) {
            throw new IOException("Personal catalog asset is unavailable; reopen Add");
        }
        if (!personal && !reusableVariants.isEmpty()) {
            throw new IOException("External catalog returned a reusable local asset");
        }
        SkinVariant resolvedInitial = variants.containsKey(initialVariant)
                ? initialVariant
                : variants.containsKey(SkinVariant.CLASSIC)
                        ? SkinVariant.CLASSIC
                        : SkinVariant.SLIM;
        return new CatalogSelection(
                skin,
                personal
                        ? Optional.empty()
                        : Optional.of(new CatalogOrigin(
                                collection.sourceId(),
                                collection.id(),
                                skin.id(),
                                resolvedCatalogText(skin.descriptionText()),
                        resolvedCatalogText(skin.authorsText()))),
                Map.copyOf(variants),
                Map.copyOf(reusableVariants),
                resolvedInitial);
    }

    private Optional<String> resolvedCatalogText(
            Optional<com.naocraftlab.skins.client.CatalogText> value) {
        return value.map(textResolver::resolve)
                .map(String::trim)
                .filter(text -> !text.isBlank());
    }

    private void chooseAddSourcePng() {
        if (state.addSource == null
                || state.addSource.selectedTab() != AddSourceTab.FILE
                || state.busy) {
            return;
        }
        long ticket = ++state.generation;
        state.busy = true;
        state.status = UiMessage.info("nclskins.status.choose_png");
        publish();
        CompletableFuture<Optional<Path>> picked;
        try {
            picked = Objects.requireNonNull(filePicker.chooseSkinPng(), "picker future");
        } catch (RuntimeException unavailablePicker) {
            finishAddSourcePicker(ticket, null, unavailablePicker);
            return;
        }
        picked.whenComplete((selection, failure) -> onClient(() -> {
            if (!current(ticket) || state.addSource == null) {
                return;
            }
            if (failure != null || selection == null) {
                finishAddSourcePicker(ticket, null, failure);
            } else if (selection.isEmpty()) {
                state.busy = false;
                state.status = UiMessage.info("nclskins.status.cancelled");
                publish();
            } else {
                Path path = selection.orElseThrow();
                CompletableFuture.supplyAsync(() -> readPng(path), worker)
                        .whenComplete((skin, pngFailure) -> onClient(() -> {
                            if (!current(ticket) || state.addSource == null) {
                                return;
                            }
                            state.busy = false;
                            if (pngFailure != null) {
                                diagnose(DiagnosticEvent.CLIENT_IMPORT_FAILED, pngFailure);
                                state.status = fileImportFailure(pngFailure);
                            } else {
                                String sourceName = UntrustedDisplayName.fromFileName(path.getFileName().toString(),
                                        textResolver.resolve(UiMessage.info("nclskins.editor.add_title")));
                                openImportedDraft(
                                        new ClientOperations.ImportDraft(
                                                sourceName,
                                                skin.detectedVariant(),
                                                skin.pngBytes(),
                                                PersonalSkinSource.FILE),
                                        sourceName + ".png",
                                        true);
                            }
                            publish();
                        }));
            }
        }));
    }

    private void openExternalImport(ExternalImportModel.Category category) {
        if (state.busy
                || state.addSource == null
                || state.addSource.selectedTab() != AddSourceTab.FILE) {
            return;
        }
        state.externalImport = ExternalImportModel.open(category);
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
                    if (state.externalImport != null
                            && state.externalImport.category() == category) {
                        state.externalImport = state.externalImport.withAutomaticProbes(probes);
                        state.status = UiMessage.info("nclskins.external_import.choose_source");
                    }
                },
                failure -> state.status = UiMessage.error("nclskins.external_import.probe_failed"));
    }

    private void prepareExternalImport(ExternalImportSource source) {
        if (state.externalImport == null
                || state.busy
                || !state.externalImport.available(source)) {
            return;
        }
        Optional<Path> root = state.externalImport.selectedRoot(source);
        submit(
                UiMessage.info("nclskins.external_import.preparing"),
                () -> operations.prepareExternalAppearances(source, root),
                review -> {
                    if (state.externalImport != null) {
                        state.externalImport = state.externalImport.withReview(review);
                        state.status = UiMessage.info("nclskins.external_import.review_ready");
                    }
                },
                failure -> failExternalPreparation(source, failure));
    }

    private void chooseExternalImportFolder(ExternalImportSource source) {
        if (state.externalImport == null
                || !state.externalImport.category().sources().contains(source)
                || state.busy
                || state.externalImport.sources().get(source).availability()
                == ExternalImportModel.Availability.DEPENDENCY_MISSING) {
            return;
        }
        long ticket = ++state.generation;
        state.busy = true;
        state.status = UiMessage.info("nclskins.external_import.choose_folder_status");
        publish();
        CompletableFuture<Optional<Path>> picked;
        try {
            picked = Objects.requireNonNull(
                    source.requiresSqlite()
                            ? filePicker.chooseSqliteDatabase()
                            : filePicker.chooseDirectory(),
                    "external import picker future");
        } catch (RuntimeException unavailablePicker) {
            finishExternalDirectoryPicker(ticket, source, null, unavailablePicker);
            return;
        }
        picked.whenComplete((selection, failure) -> onClient(() -> {
            if (!current(ticket) || state.externalImport == null) {
                return;
            }
            if (failure != null || selection == null) {
                finishExternalDirectoryPicker(ticket, source, null, failure);
                return;
            }
            if (selection.isEmpty()) {
                state.busy = false;
                state.status = UiMessage.info("nclskins.external_import.choose_source");
                publish();
                return;
            }
            Path root = selection.orElseThrow();
            CompletableFuture.supplyAsync(() -> {
                try {
                    return operations.probeExternalSource(source, Optional.of(root));
                } catch (Exception probeFailure) {
                    throw new CompletionException(probeFailure);
                }
            }, worker).whenComplete((probe, probeFailure) -> onClient(() -> {
                if (!current(ticket) || state.externalImport == null) {
                    return;
                }
                state.busy = false;
                if (probeFailure == null) {
                    state.externalImport = state.externalImport.withManualProbe(
                            source, root, probe == ExternalImportProbe.AVAILABLE);
                    state.status = probe == ExternalImportProbe.AVAILABLE
                            ? UiMessage.success("nclskins.external_import.folder_ready")
                            : UiMessage.error(invalidFolderKey(source));
                } else {
                    diagnose(DiagnosticEvent.CLIENT_IMPORT_FAILED, probeFailure);
                    state.externalImport = state.externalImport.withManualProbe(source, root, false);
                    state.status = UiMessage.error(invalidFolderKey(source));
                }
                publish();
            }));
        }));
    }

    private void finishExternalDirectoryPicker(
            long ticket,
            ExternalImportSource source,
            Path ignored,
            Throwable failure) {
        if (!current(ticket) || state.externalImport == null) {
            return;
        }
        diagnose(DiagnosticEvent.CLIENT_PICKER_FAILED, failure);
        state.busy = false;
        state.status = UiMessage.error("nclskins.external_import.picker_failed");
        publish();
    }

    private void toggleExternalCandidate(String candidateId) {
        if (state.externalImport == null || state.externalImport.review().isEmpty() || state.busy) {
            return;
        }
        state.externalImport = state.externalImport.toggleCandidate(candidateId);
        publish();
    }

    private void toggleExternalCollection(boolean duplicates) {
        if (state.externalImport == null || state.externalImport.review().isEmpty() || state.busy) {
            return;
        }
        state.externalImport = state.externalImport.toggleCollection(duplicates);
        publish();
    }

    private void toggleAllExternalCollections() {
        if (state.externalImport == null || state.externalImport.review().isEmpty() || state.busy) {
            return;
        }
        ExternalImportModel.ReviewState review = state.externalImport.review().orElseThrow();
        if (review.availableCollections().isEmpty()) {
            return;
        }
        ExternalImportModel changed = state.externalImport.withAllCollectionsCollapsed(
                !review.anyCollectionCollapsed());
        int normalized = externalImportPresenter.normalizedReviewScrollOffset(
                changed, viewportWidth, viewportHeight, review.scrollOffset());
        state.externalImport = changed.withReviewScroll(normalized);
        publish();
    }

    private void toggleAllExternalCandidates() {
        if (state.externalImport == null || state.externalImport.review().isEmpty() || state.busy) {
            return;
        }
        state.externalImport = state.externalImport.toggleAll();
        publish();
    }

    private void commitExternalImport() {
        if (state.externalImport == null || state.externalImport.review().isEmpty() || state.busy) {
            return;
        }
        ExternalImportModel.ReviewState review = state.externalImport.review().orElseThrow();
        List<ClientOperations.ExternalImportCandidate> selected = review.selectedCandidates();
        if (selected.isEmpty()) {
            return;
        }
        submit(
                UiMessage.info("nclskins.status.saving"),
                () -> operations.commitExternalAppearances(
                        selected, review.review().skipped(), review.review().warnings()),
                this::finishExternalImport,
                failure -> state.status = UiMessage.error("nclskins.external_import.commit_failed"));
    }

    private void finishExternalImport(ClientOperations.ExternalImportResult result) {
        state.account = result.account();
        state.externalImport = null;
        state.addSource = null;
        state.selectedPresetId = null;
        invalidateCatalogPreviews();
        state.status = UiMessage.success(
                "nclskins.external_import.complete",
                result.imported(),
                result.skipped(),
                result.alreadyPresent(),
                result.warnings());
        if (addSourceRoot()) closeScreenOnClient();
    }

    private void failExternalPreparation(ExternalImportSource source, Throwable failure) {
        if (state.externalImport == null) {
            return;
        }
        Throwable cause = unwrap(failure);
        if (cause instanceof ExternalImportException external
                && external.code() == ExternalImportException.Code.NO_VALID_APPEARANCES) {
            state.status = UiMessage.error(switch (source) {
                case CURSEFORGE_APP, MODRINTH_APP ->
                        "nclskins.external_import.no_valid_current_account";
                case MINECRAFT_LAUNCHER, SKIN_SHUFFLE, SKIN_SWAPPER_FAMILY,
                        QUICK_SKIN, PRISM_LAUNCHER -> "nclskins.external_import.no_valid";
            });
            return;
        }
        if (cause instanceof ExternalImportException external
                && external.code() == ExternalImportException.Code.DEPENDENCY_MISSING) {
            state.status = UiMessage.error("nclskins.external_import.sqlite_dependency_required");
            return;
        }
        state.status = UiMessage.error(invalidFolderKey(source));
    }

    private static String invalidFolderKey(ExternalImportSource source) {
        return "nclskins.external_import.invalid_folder." + switch (source) {
            case MINECRAFT_LAUNCHER -> "minecraft_launcher";
            case CURSEFORGE_APP -> "curseforge_app";
            case MODRINTH_APP -> "modrinth_app";
            case SKIN_SHUFFLE -> "skin_shuffle";
            case SKIN_SWAPPER_FAMILY -> "skin_swapper_family";
            case QUICK_SKIN -> "quick_skin";
            case PRISM_LAUNCHER -> "prism_launcher";
        };
    }

    private void cancelExternalReview() {
        if (state.externalImport == null || state.externalImport.review().isEmpty()) {
            return;
        }
        state.generation++;
        state.busy = false;
        state.externalImport = state.externalImport.clearReview();
        state.status = UiMessage.info("nclskins.external_import.choose_source");
        publish();
    }

    private void cancelExternalImport() {
        if (state.externalImport == null) {
            return;
        }
        boolean cancelledBusyOperation = state.busy;
        if (state.busy) {
            state.generation++;
            state.busy = false;
            state.status = UiMessage.info("nclskins.status.cancelled");
        }
        if (state.externalImport.review().isPresent()) {
            state.externalImport = state.externalImport.clearReview();
        } else {
            state.externalImport = null;
        }
        if (!cancelledBusyOperation) {
            state.status = UiMessage.info("nclskins.external_import.choose_source");
        }
        publish();
    }

    private void loadRemoteImport(boolean player) {
        if (state.addSource == null || state.addSource.selectedTab() != AddSourceTab.FILE || state.busy) {
            return;
        }
        String input = player ? state.addSource.playerInput() : state.addSource.urlInput();
        if (input.isBlank()) {
            return;
        }
        submit(
                UiMessage.info(player
                        ? "nclskins.add_source.player_loading"
                        : "nclskins.add_source.url_loading"),
                () -> player ? operations.loadPlayerSkin(input) : operations.loadUrlSkin(input),
                draft -> {
                    openImportedDraft(draft, draft.name() + ".png", true);
                },
                failure -> state.status = UiMessage.error(player
                        ? publicImportFailureKey(failure, true)
                        : publicImportFailureKey(failure, false)));
    }

    private boolean openImportedDraft(
            ClientOperations.ImportDraft draft,
            String sourceName,
            boolean useSuggestedPresetName) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(sourceName, "sourceName");
        PresetEditorModel editor = createEditor(null);
        if (editor == null) {
            state.status = UiMessage.error("nclskins.gallery.prepare_failed");
            return false;
        }
        editor = editor.withImportedPng(sourceName, draft.pngBytes(), draft.variant());
        if (useSuggestedPresetName) {
            editor = editor.withName(draft.name());
        }
        state.editor = applyPendingPresetName(editor);
        prepareEditorEvidence(state.editor);
        initializeEditorCapeCatalog(null);
        resetEditorScroll();
        state.editorPersonalSource = draft.source();
        state.selectedPresetId = null;
        rememberPreferredSkinVariant(draft.variant());
        return true;
    }

    private void finishAddSourcePicker(long ticket, byte[] ignored, Throwable failure) {
        onClient(() -> {
            if (!current(ticket) || state.addSource == null) {
                return;
            }
            diagnose(DiagnosticEvent.CLIENT_PICKER_FAILED, failure);
            state.busy = false;
            state.status = UiMessage.error("nclskins.error.picker");
            publish();
        });
    }

    private void requestPersonalSkinDeletion(
            String collectionId, String sha256, InteractionOrigin origin) {
        if (state.busy
                || state.addSource == null
                || state.addSource.selectedTab() != AddSourceTab.CATALOG) {
            return;
        }
        SkinCatalogSource.CollectionDescriptor collection = state.addSource.collections().stream()
                .filter(value -> value.order().kind() == CatalogCollectionOrder.Kind.PERSONAL)
                .filter(value -> value.id().equals(collectionId))
                .findFirst()
                .orElse(null);
        if (collection == null) {
            return;
        }
        SkinCatalogSource.SkinDescriptor skin = collection.skins().stream()
                .filter(value -> value.id().equals(sha256))
                .findFirst()
                .orElse(null);
        if (skin == null || !state.addSource.visibleSkins(collection).contains(skin)) {
            return;
        }
        resetPersonalCatalogInteraction();
        state.addSource = state.addSource.requestPersonalSkinDeletion(
                collection, skin, origin.keyboard());
        draggingAddSourceScrollbar = false;
        state.status = UiMessage.info("nclskins.your_skins.delete_note");
        publish();
    }

    private void requestPersonalSkinRename(String collectionId, String sha256) {
        if (state.busy
                || state.addSource == null
                || state.addSource.selectedTab() != AddSourceTab.CATALOG) {
            return;
        }
        SkinCatalogSource.SkinDescriptor skin = state.addSource.collections().stream()
                .filter(collection -> collection.order().kind() == CatalogCollectionOrder.Kind.PERSONAL)
                .filter(collection -> collection.id().equals(collectionId))
                .flatMap(collection -> collection.skins().stream())
                .filter(candidate -> candidate.id().equals(sha256))
                .findFirst()
                .orElse(null);
        if (skin == null) {
            return;
        }
        resetPersonalCatalogInteraction();
        state.personalRenameCollectionId = collectionId;
        state.personalRenameHash = sha256;
        state.personalRenameValue = state.addSource.skinName(skin);
        state.addSource = state.addSource.withRequestedFocus("add.catalog.rename.name");
        publish();
    }

    private void cancelPersonalSkinRename() {
        if (state.busy || state.personalRenameHash == null) {
            return;
        }
        String collectionForFocus = state.personalRenameCollectionId;
        String hashForFocus = state.personalRenameHash;
        state.personalRenameCollectionId = null;
        state.personalRenameHash = null;
        state.personalRenameValue = "";
        state.addSource = state.addSource.withRequestedFocus(
                AddSourceModel.personalActionId(
                        "add.catalog.rename:", collectionForFocus, hashForFocus));
        publish();
    }

    private void savePersonalSkinRename() {
        if (state.busy || state.addSource == null || state.personalRenameHash == null) {
            return;
        }
        String collectionId = state.personalRenameCollectionId;
        String hash = state.personalRenameHash;
        String name = UntrustedDisplayName.sanitize(state.personalRenameValue, "");
        if (name.isBlank()) {
            return;
        }
        submit(
                UiMessage.info("nclskins.status.saving"),
                () -> operations.renamePersonalSkin(hash, name),
                account -> {
                    state.account = account;
                    if (state.addSource != null) {
                        state.addSource = state.addSource
                                .renamedPersonalSkin(collectionId, hash, name)
                                .withRequestedFocus(AddSourceModel.personalActionId(
                                        "add.catalog.rename:", collectionId, hash));
                    }
                    state.personalRenameCollectionId = null;
                    state.personalRenameHash = null;
                    state.personalRenameValue = "";
                    state.status = UiMessage.success("nclskins.your_skins.renamed");
                });
    }

    private void cancelPersonalSkinDeletion(InteractionOrigin origin) {
        if (state.busy
                || state.addSource == null
                || state.addSource.personalSkinDeletion().isEmpty()) {
            return;
        }
        state.addSource = state.addSource.cancelPersonalSkinDeletion(origin.keyboard());
        state.status = UiMessage.info("nclskins.status.cancelled");
        publish();
    }

    private void confirmPersonalSkinDeletion(InteractionOrigin origin) {
        if (state.busy
                || state.addSource == null
                || state.addSource.personalSkinDeletion().isEmpty()) {
            return;
        }
        AddSourceModel.PersonalSkinDeletion deletion =
                state.addSource.personalSkinDeletion().orElseThrow();
        submit(
                UiMessage.info("nclskins.your_skins.deleting"),
                () -> operations.removePersonalSkin(deletion.sha256()),
                account -> {
                    state.account = account;
                    if (state.addSource != null
                            && state.addSource.personalSkinDeletion()
                                    .map(AddSourceModel.PersonalSkinDeletion::sha256)
                                    .filter(deletion.sha256()::equals)
                                    .isPresent()) {
                        state.addSource = state.addSource.removeConfirmedPersonalSkin(origin.keyboard());
                        int normalized = addSourcePresenter.normalizedScrollOffset(
                                state.addSource,
                                viewportWidth,
                                viewportHeight,
                                state.addSource.scrollOffset(),
                                viewChromeMetrics);
                        state.addSource = state.addSource.withScrollOffset(normalized);
                        clampAddSourceScroll();
                    }
                    invalidateCatalogPreviews();
                    state.status = UiMessage.success("nclskins.your_skins.deleted");
                },
                failure -> state.status = UiMessage.error("nclskins.your_skins.delete_failed"));
    }

    private void cancelAddSource() {
        if (state.addSource != null && state.editor == null) {
            if (state.busy && state.addSource.selectedTab() != AddSourceTab.FILE) {
                return;
            }
            if (state.busy) {
                state.generation++;
                state.busy = false;
                state.status = UiMessage.info("nclskins.status.cancelled");
            }
            if (addSourceRoot()) {
                closeScreenOnClient();
                return;
            }
            resetPersonalCatalogInteraction();
            state.addSource = null;
            clearRuntimeFocus("add_source");
            draggingAddSourceScrollbar = false;
            resetAddSourceScroll();
            publish();
        }
    }

    private boolean personalCatalogInteractionBelongsTo(String collectionId) {
        if (state.addSource == null) {
            return false;
        }
        boolean deletionBelongs = state.addSource.personalSkinDeletion()
                .map(AddSourceModel.PersonalSkinDeletion::collectionId)
                .filter(collectionId::equals)
                .isPresent();
        return deletionBelongs || Objects.equals(state.personalRenameCollectionId, collectionId);
    }

    private void resetPersonalCatalogInteraction() {
        boolean hasRename = state.personalRenameHash != null;
        boolean hasDeletion = state.addSource != null
                && state.addSource.personalSkinDeletion().isPresent();
        if (!hasRename && !hasDeletion) {
            return;
        }
        if (state.addSource != null) {
            state.addSource = state.addSource.withoutPersonalSkinInteraction();
        }
        state.personalRenameCollectionId = null;
        state.personalRenameHash = null;
        state.personalRenameValue = "";
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
        if (state.editor == null || state.editor.busy()) {
            return;
        }
        pendingCapeProviderInspection = null;
        state.editor = state.editor.withSelectedEditorTab(tab);
        draggingEditorScrollbar = false;
        clearRuntimeFocus("preset_editor");
        if (state.account != null) {
            UUID accountId = state.account.accountId();
            AccountUiPreferences preferences = state.uiPreferences == null
                    ? AccountUiPreferences.defaults(accountId) : state.uiPreferences;
            state.uiPreferences = preferences.withSelectedEditorTab(tab);
            long ticket = state.generation;
            long preferenceSequence = ++editorTabPreferenceSequence;
            editorTabPreferenceWrite = editorTabPreferenceWrite.handle((ignored, failure) -> null).thenRunAsync(() -> {
                try {
                    operations.setSelectedEditorTab(accountId, tab);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(failure);
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            }, worker).whenComplete((ignored, failure) -> clientExecutor.execute(() -> {
                if (failure != null && preferenceSequence == editorTabPreferenceSequence
                        && current(ticket) && state.account != null
                        && state.account.accountId().equals(accountId) && state.editor != null) {
                    diagnose(DiagnosticEvent.CLIENT_PREFERENCES_SAVE_FAILED, failure);
                    state.editor = state.editor.withPreviewFailure(UiMessage.error("nclskins.error.save"));
                    publish();
                }
            }));
        }
        publish();
    }

    private Optional<com.naocraftlab.skins.core.model.RemoteProfile> editorProfile() {
        return state.providers.cape().enabled(BuiltinProvider.MINECRAFT)
                ? Optional.ofNullable(state.remoteProfile) : Optional.empty();
    }

    private List<com.naocraftlab.skins.core.model.OwnedCapeEntry> editorOwnedCapes() {
        return state.providers.cape().enabled(BuiltinProvider.MINECRAFT) && state.ownedCapes != null
                ? state.ownedCapes.capes() : List.of();
    }

    private void openEditor(UUID presetId) {
        if (state.busy || state.account == null) {
            return;
        }
        state.pendingPresetDeleteId = null;
        clearRuntimeFocus("gallery");
        PresetEditorModel editor = createEditor(presetId);
        if (editor == null) {
            state.status = UiMessage.error("nclskins.gallery.prepare_failed");
            publish();
            return;
        }
        state.addSource = null;
        state.editorReturnsToProviders = false;
        state.editor = editor;
        LocalCapeReference offlineSeed = state.account.presets().stream()
                .filter(preset -> preset.id().equals(presetId))
                .findFirst()
                .map(AppearancePreset::offlineCape)
                .orElse(null);
        prepareEditorEvidence(editor);
        initializeEditorCapeCatalog(offlineSeed);
        resetEditorScroll();
        state.selectedPresetId = presetId;
        publish();
    }

    private PresetEditorModel createEditor(UUID presetId) {
        Optional<AppearancePreset> preset = presetId == null
                ? Optional.empty()
                : state.account.presets().stream().filter(value -> value.id().equals(presetId)).findFirst();
        if (presetId != null && preset.isEmpty()) {
            return null;
        }
        try {
            return PresetEditorModel.open(
                    state.account,
                    preset,
                    editorProfile(),
                    Optional.ofNullable(state.activePresetId),
                    textResolver,
                    viewportHeight,
                    preferredCapeMode,
                    preferredSkinVariant(),
                    editorOwnedCapes());
        } catch (IllegalStateException missingBundledSkin) {
            diagnose(DiagnosticEvent.CLIENT_BUNDLED_SKIN_MISSING, missingBundledSkin);
            return null;
        }
    }

    private boolean dispatchCapeCatalog(String id, boolean reverse, InteractionOrigin origin) {
        CapeCatalogModel catalog = state.editor.capeCatalog();
        UUID capeAccountId = state.account.accountId();
        if (id.startsWith("editor.cape_item.")) {
            var card = catalog.cards().stream().filter(value -> value.widgetId().equals(id)).findFirst();
            if (card.isPresent()) {
                pendingCapeProviderInspection = null;
                if (card.orElseThrow().importCard()) chooseCapePng();
                else updateEditor(editor -> editor.withCapeCatalog(catalog.choose(card.orElseThrow())));
            }
        } else if (id.equals("editor.cape_filter")) {
            updateEditor(editor -> editor.withCapeCatalog(catalog.cycleFilter(reverse)));
        } else if (id.equals("editor.cape_disclosure")) {
            updateEditor(editor -> editor.withCapeCatalog(catalog.toggleAll()));
        } else if (id.startsWith("editor.cape_header.")) {
            String collectionId = id.substring("editor.cape_header.".length());
            updateEditor(editor -> editor.withCapeCatalog(catalog.toggle(collectionId)));
        } else if (id.startsWith("editor.cape_action.")) {
            String[] parts = id.split("\\.");
            if (parts.length != 4) return true;
            UUID entry = UUID.fromString(parts[3]);
            switch (parts[2]) {
                case "rename", "delete" -> {
                    if (catalog.editing() != null) return true;
                    updateEditor(editor -> editor.withCapeCatalog(catalog.edit(entry, parts[2].equals("delete"))));
                    if (parts[2].equals("rename") || origin == InteractionOrigin.KEYBOARD) {
                        String focusId = parts[2].equals("delete") ? "editor.cape_action.cancel." + entry : "editor.cape_action.name";
                        ViewSpec view = view(viewportWidth, viewportHeight, 0, 0);
                        view.navigationNode(focusId).ifPresent(node -> ViewNavigationPolicy.ensureVisibleOffset(view, node)
                                .ifPresent(offset -> applyNavigationScroll(node, offset)));
                        requestRuntimeFocus("preset_editor", focusId);
                    }
                }
                case "cancel" -> {
                    updateEditor(editor -> editor.withCapeCatalog(catalog.cancelEdit()));
                    if (origin == InteractionOrigin.KEYBOARD) requestRuntimeFocus("preset_editor", "editor.cape_item.OFFLINE." + entry);
                }
                case "save" -> {
                    if (!catalog.renameValue().trim().isEmpty()) {
                        submit(UiMessage.info("nclskins.status.saving"),
                                () -> operations.renameCape(capeAccountId, entry,
                                        UntrustedDisplayName.sanitize(catalog.renameValue(), "")), account -> {
                                    state.account = account;
                                    refreshCapeCatalog(catalog.offline(), catalog.minecraft());
                                    state.editor = state.editor.withCapeCatalog(
                                            state.editor.capeCatalog().cancelEdit());
                                    if (origin == InteractionOrigin.KEYBOARD) {
                                        requestRuntimeFocus("preset_editor", "editor.cape_item.OFFLINE." + entry);
                                    }
                                });
                    }
                }
                case "confirm" -> submit(UiMessage.info("nclskins.status.saving"), () -> operations.deleteCape(capeAccountId, entry), result -> {
                    state.account = result.account();
                    acceptProviderChange(result.appearance());
                    refreshCapeCatalog(catalog.offline() != null && entry.equals(catalog.offline().entryId()) ? null : catalog.offline(), catalog.minecraft());
                    editorCapeScrollPosition = state.editor.normalizedCapeScrollPosition(
                            viewportWidth, viewportHeight, editorCapeScrollPosition, viewChromeMetrics);
                    editorCapeScrollTarget = editorCapeScrollPosition;
                    if (origin == InteractionOrigin.KEYBOARD) requestRuntimeFocus("preset_editor", "editor.cape_item.OFFLINE.none");
                });
                default -> { }
            }
        } else return false;
        if (id.equals("editor.cape_disclosure") || id.startsWith("editor.cape_header.")) {
            persistCapeDisclosure(catalog.collapsed(), state.editor.capeCatalog().collapsed());
        }
        editorCapeScrollPosition = state.editor.normalizedCapeScrollPosition(
                viewportWidth, viewportHeight, editorCapeScrollPosition, viewChromeMetrics);
        editorCapeScrollTarget = editorCapeScrollPosition;
        publish();
        return true;
    }

    private void persistCapeDisclosure(Set<String> before, Set<String> after) {
        if (before.equals(after)) return;
        UUID accountId = state.account.accountId();
        Set<String> values = Set.copyOf(after);
        state.uiPreferences = state.uiPreferences.withCollapsedCapeCollections(values);
        long ticket = state.generation;
        long sequence = ++capeDisclosureSequence;
        capeDisclosureWrite = capeDisclosureWrite.handle((ignored, failure) -> null).thenRunAsync(() -> {
            try { operations.setCollapsedCapeCollections(accountId, values); }
            catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                diagnose(DiagnosticEvent.CLIENT_PREFERENCES_SAVE_FAILED, failure);
                onClient(() -> {
                    if (!current(ticket) || capeDisclosureSequence != sequence || state.editor == null || state.editor.capeCatalog() == null) return;
                    state.uiPreferences = state.uiPreferences.withCollapsedCapeCollections(before);
                    state.editor = state.editor.withCapeCatalog(state.editor.capeCatalog().withCollapsed(before));
                    publish();
                });
            }
        }, worker);
    }

    private void reloadEditorCatalog() {
        if (state.editor == null || state.account == null) return;
        UUID accountId = state.account.accountId();
        long generation = ++editorCatalogGeneration;
        long ticket = state.generation;
        CompletableFuture.supplyAsync(() -> {
            try { return operations.loadCapeEditorData(accountId); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }, worker).whenComplete((data, failure) -> onClient(() -> {
            if (!current(ticket) || generation != editorCatalogGeneration || state.editor == null
                    || !accountId.equals(state.account.accountId())) return;
            if (failure != null) { diagnose(DiagnosticEvent.CLIENT_ASYNC_OPERATION_FAILED, failure); return; }
            var previous = state.editor.capeCatalog();
            state.account = data.account();
            installWarmedCapePreviews(accountId, true);
            var local = previous.offline();
            if (local != null && local.entryId() != null) {
                var reference = local;
                if (state.account.personalCapes().stream().noneMatch(entry -> entry.texture().equals(reference))) local = null;
            }
            var fresh = CapeCatalogModel.open(state.account, state.providers, local,
                    previous.minecraft(), state.editor.capeChoices(), data.resourceCollections(),
                    data.sourceHashes(), data.resourceGeneration(), textResolver);
            var inspection = previous.inspected() == null ? fresh.inspected() : fresh.cards().stream()
                    .filter(card -> card.widgetId().equals(previous.inspected().widgetId())).findFirst().orElse(null);
            state.editor = state.editor.withCapeCatalog(new CapeCatalogModel(fresh.cards(), fresh.providers(), fresh.offline(), previous.minecraft(),
                    previous.query(), previous.filter(), previous.collapsed(), inspection, previous.editing(), previous.deleting(), previous.renameValue(), previous.importError(), textResolver));
            if (pendingCapeProviderInspection != null) {
                state.editor = state.editor.withCapeCatalog(
                        state.editor.capeCatalog().inspect(pendingCapeProviderInspection, state.account));
                pendingCapeProviderInspection = null;
                editorCapeScrollPosition = state.editor.initialCapeScrollPosition(
                        viewportWidth, viewportHeight, viewChromeMetrics);
            } else {
                editorCapeScrollPosition = state.editor.normalizedCapeScrollPosition(
                        viewportWidth, viewportHeight, editorCapeScrollPosition, viewChromeMetrics);
            }
            editorCapeScrollTarget = editorCapeScrollPosition;
            publish();
        }));
    }

    private void refreshCapeCatalog(com.naocraftlab.skins.core.model.LocalCapeReference local, Optional<String> owned) {
        if (state.editor == null) return;
        var previous = state.editor.capeCatalog();
        var fresh = previous.refreshed(
                state.account, state.providers, local, owned, state.editor.capeChoices());
        state.editor = state.editor.withCapeCatalog(fresh);
    }

    private void chooseCapePng() {
        if (state.editor == null || state.editor.busy()) return;
        UUID accountId = state.account.accountId();
        String fallbackName = textResolver.resolve(UiMessage.info("options.modelPart.cape"));
        long ticket = ++state.generation;
        state.busy = true;
        state.editor = state.editor.withBusyWithoutStatus();
        publish();
        CompletableFuture<Optional<Path>> picked;
        try { picked = java.util.Objects.requireNonNull(filePicker.chooseCapePng()); }
        catch (RuntimeException failure) {
            state.busy = false;
            state.editor = state.editor.withoutStatus().withCapeCatalog(state.editor.capeCatalog().withImportError(UiMessage.error("nclskins.error.picker")));
            publish();
            return;
        }
        picked.whenComplete((selection, failure) -> onClient(() -> {
            if (!current(ticket) || state.editor == null) return;
            state.busy = false;
            state.editor = state.editor.withoutStatus();
            if (failure != null) {
                state.editor = state.editor.withoutStatus().withCapeCatalog(state.editor.capeCatalog().withImportError(UiMessage.error("nclskins.error.picker")));
                publish();
            } else if (selection != null && selection.isPresent()) {
                state.editor = state.editor.withBusyWithoutStatus();
                Set<String> resourceIdentities = state.editor.capeCatalog().cards().stream()
                        .map(CapeCatalogModel.Card::resource)
                        .filter(Objects::nonNull)
                        .map(ClientOperations.ResourceCapeSelection::contentIdentity)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                submit(UiMessage.info("nclskins.status.saving"), () -> {
                    var entry = operations.importCape(
                            accountId, selection.orElseThrow(), fallbackName);
                    Optional<AccountState> account = resourceIdentities.contains(entry.renderSha256())
                            ? operations.discardCapeIfUnreferenced(
                                    accountId, entry.texture().entryId())
                            : Optional.empty();
                    return new CapeImportResult(entry, account);
                }, result -> {
                    if (state.editor == null) return;
                    state.editor = state.editor.withoutStatus();
                    var entry = result.entry();
                    result.account().ifPresent(value -> state.account = value);
                    var entries = new java.util.ArrayList<>(state.account.personalCapes());
                    if (result.account().isEmpty()
                            && entries.stream().noneMatch(value -> value.texture().entryId()
                                    .equals(entry.texture().entryId()))) {
                        entries.add(entry);
                        state.account = state.account.withPersonalCapes(entries);
                    }
                    refreshCapeCatalog(entry.texture(), state.editor.capeId());
                    var catalog = state.editor.capeCatalog().withQuery("");
                    var selected = catalog.resourceOwner(entry.renderSha256()).orElseGet(() ->
                            catalog.cards().stream().filter(card -> entry.texture().equals(card.local()))
                                    .findFirst().orElseThrow());
                    state.editor = state.editor.withCapeCatalog(catalog.reveal(selected).choose(selected));
                    persistCapeDisclosure(catalog.collapsed(), state.editor.capeCatalog().collapsed());
                    editorCapeScrollPosition = state.editor.initialCapeScrollPosition(
                            viewportWidth, viewportHeight, viewChromeMetrics);
                    editorCapeScrollTarget = editorCapeScrollPosition;
                }, invalid -> {
                    if (state.editor != null) {
                        state.editor = state.editor.withoutStatus();
                        if (unwrap(invalid) instanceof com.naocraftlab.skins.core.png.PngValidationException) {
                            state.editor = state.editor.withCapeCatalog(state.editor.capeCatalog().error(true));
                        } else state.editor = state.editor.withCapeCatalog(state.editor.capeCatalog().withImportError(UiMessage.error("nclskins.capes.io_error")));
                    }
                });
            }
            publish();
        }));
    }

    private void chooseEditorPng() {
        if (state.editor == null || state.editor.busy()) {
            return;
        }
        long ticket = ++state.generation;
        state.busy = true;
        state.editor = state.editor.withBusy(UiMessage.info("nclskins.status.choose_png"));
        publish();
        CompletableFuture<Optional<Path>> picked;
        try {
            picked = Objects.requireNonNull(filePicker.chooseSkinPng(), "picker future");
        } catch (RuntimeException unavailablePicker) {
            finishEditorPicker(ticket, null, unavailablePicker);
            return;
        }
        picked.whenComplete((selection, failure) -> onClient(() -> {
            if (!current(ticket) || state.editor == null) {
                return;
            }
            if (failure != null || selection == null) {
                finishEditorPicker(ticket, null, failure);
            } else if (selection.isEmpty()) {
                state.busy = false;
                state.editor = state.editor.withStatus(UiMessage.info("nclskins.status.cancelled"));
                publish();
            } else {
                Path path = selection.orElseThrow();
                CompletableFuture.supplyAsync(() -> readPng(path), worker)
                        .whenComplete((skin, pngFailure) -> onClient(() -> {
                            if (!current(ticket) || state.editor == null) {
                                return;
                            }
                            state.busy = false;
                            if (pngFailure != null) {
                                diagnose(DiagnosticEvent.CLIENT_IMPORT_FAILED, pngFailure);
                                state.editor = state.editor.withStatus(fileImportFailure(pngFailure));
                            } else {
                                state.editor = state.editor.withImportedPng(
                                        path.getFileName().toString(),
                                        skin.pngBytes(),
                                        skin.detectedVariant());
                                prepareEditorEvidence(state.editor);
                                rememberPreferredSkinVariant(skin.detectedVariant());
                            }
                            publish();
                        }));
            }
        }));
    }

    private void finishEditorPicker(long ticket, byte[] ignored, Throwable failure) {
        onClient(() -> {
            if (!current(ticket) || state.editor == null) {
                return;
            }
            diagnose(DiagnosticEvent.CLIENT_PICKER_FAILED, failure);
            state.busy = false;
            state.editor = state.editor.withStatus(UiMessage.error("nclskins.error.picker"));
            publish();
        });
    }

    private void saveEditor() {
        if (state.editor == null || state.editor.busy() || state.editor.name().trim().isEmpty()) {
            return;
        }
        PresetEditorModel draft = state.editor;
        UUID editorAccountId = state.account.accountId();
        PersonalSkinSource personalSource = state.editorPersonalSource;
        state.editor = draft.withBusy(UiMessage.info("nclskins.status.saving"));
        submit(
                UiMessage.info("nclskins.status.saving"),
                () -> {
                    ClientOperations.EditorSaveRequest request = draft.saveRequest();
                    com.naocraftlab.skins.core.model.LocalCapeReference offlineCape =
                            request.offlineCape();
                    Optional<ClientOperations.ResourceCapeSelection> resourceCape =
                            draft.capeCatalog() == null
                                    ? Optional.empty()
                                    : draft.capeCatalog().selectedResource();
                    if (resourceCape.isPresent()) {
                        offlineCape = operations.materializeResourceCape(
                                editorAccountId, resourceCape.orElseThrow()).texture();
                    }
                    return operations.saveEditor(new ClientOperations.EditorSaveRequest(
                            request.originalPresetId(), request.name(), request.skin(),
                            request.initialVariant(), request.variant(), request.capeId(),
                            request.outerLayerVisibility(), request.pngBytes(), request.catalogOrigin(), request.personalSkinName(),
                            personalSource).withOfflineCape(offlineCape));
                },
                saved -> {
                    UUID previousActivePresetId = state.activePresetId;
                    state.account = saved.account();
                    state.selectedPresetId = saved.presetId();
                    AppearancePreset preset = findPreset(saved.presetId());
                    state.selectedSkinId = preset == null ? null : preset.skin().assetId();
                    state.selectedCapeId = preset == null ? null : preset.capeId();
                    state.editor = null;
                    state.editorEvidence = null;
                    state.editorPersonalSource = PersonalSkinSource.FILE;
                    state.addSource = null;
                    state.pendingPresetName = null;
                    state.status = draft.capeCatalog() != null && draft.capeCatalog().offline() != null
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
                                    ClientOperations.ReconciliationTrigger.LOCAL_INTENT);
                        }
                    });
                    centerGalleryIfActiveChanged(previousActivePresetId);
                    if (addSourceRoot()) closeScreenOnClient();
                    else returnFromEditor();
                },
                failure -> {
                    if (state.editor != null) {
                        UiMessage saveFailure = UiMessage.error("nclskins.error.save");
                        state.editor = state.editor.withStatus(saveFailure);
                        state.status = saveFailure;
                    }
                });
    }

    private void cancelEditor() {
        if (state.editor != null && !state.editor.busy()) {
            state.editor = null;
            state.editorEvidence = null;
            returnFromEditor();
            resetEditorScroll();
            publish();
        }
    }

    private void returnFromEditor() {
        if (state.rootDestination == ScreenDestination.ACTIVE_EDITOR) {
            closeScreenOnClient();
            return;
        }
        if (state.editorReturnsToProviders) {
            state.providersOpen = true;
            state.providerAdding = false;
            selectProvidersTab(AppearanceProviders.Component.CAPE);
            state.providerPreviewSources.remove(AppearanceProviders.Component.CAPE);
        }
        state.editorReturnsToProviders = false;
    }

    private void closeToProviders() {
        state.galleryReturnsToProviders = false;
        state.pendingPresetDeleteId = null;
        state.gallerySelectedCardId = null;
        clearRuntimeFocus("gallery");
        state.providersOpen = true;
        state.providerAdding = false;
        state.providerComponent = state.uiPreferences == null ? AppearanceProviders.Component.SKIN
                : state.uiPreferences.selectedProvidersTab();
        state.providerPreviewSources.remove(AppearanceProviders.Component.SKIN);
        publish();
    }

    private PresetEditorModel applyPendingPresetName(PresetEditorModel editor) {
        return state.pendingPresetName == null ? editor : editor.withName(state.pendingPresetName);
    }

    private void requestPresetDeletion(UUID presetId, InteractionOrigin origin) {
        if (state.busy || findPreset(presetId) == null) {
            return;
        }
        state.pendingPresetDeleteId = presetId;
        state.gallerySelectedCardId = "gallery.card." + presetId;
        if (origin.keyboard()) {
            requestRuntimeFocus(
                    "gallery", "gallery.preset." + presetId + ".delete_cancel");
        }
        publish();
    }

    private void cancelPresetDeletion(UUID presetId, InteractionOrigin origin) {
        if (!presetId.equals(state.pendingPresetDeleteId) || state.busy) {
            return;
        }
        state.pendingPresetDeleteId = null;
        if (origin.keyboard()) {
            requestRuntimeFocus("gallery", "gallery.preset." + presetId + ".delete");
        }
        publish();
    }

    private void duplicatePreset(UUID presetId) {
        AppearancePreset source = findPreset(presetId);
        if (source == null || state.busy || state.account == null) {
            return;
        }
        String name = textResolver.resolve(UiMessage.info("nclskins.gallery.copy_name", source.name())).trim();
        if (name.length() > 128) {
            name = name.substring(0, 128).trim();
        }
        String copyName = name.isEmpty() ? source.name() : name;
        try {
            state.addSource = null;
            state.editor = PresetEditorModel.openDuplicate(
                    state.account,
                    source,
                    copyName,
                    editorProfile(),
                    Optional.ofNullable(state.activePresetId),
                    textResolver,
                    viewportHeight,
                    preferredCapeMode,
                    preferredSkinVariant(),
                    editorOwnedCapes());
            initializeEditorCapeCatalog(source.offlineCape());
            prepareEditorEvidence(state.editor);
            resetEditorScroll();
            state.selectedPresetId = presetId;
            publish();
        } catch (IllegalStateException missingBundledSkin) {
            diagnose(DiagnosticEvent.CLIENT_BUNDLED_SKIN_MISSING, missingBundledSkin);
            state.status = UiMessage.error("nclskins.gallery.prepare_failed");
            publish();
        }
    }

    private void deletePreset(UUID presetId, InteractionOrigin origin) {
        if (!presetId.equals(state.pendingPresetDeleteId)) {
            return;
        }
        List<String> previousCards = galleryPresenter.cardIds(snapshot, state.galleryQuery);
        int removedOrdinal = previousCards.indexOf("gallery.card." + presetId);
        submit(
                UiMessage.info("nclskins.status.deleting"),
                () -> operations.deletePreset(presetId),
                deletion -> {
                    UUID previousActivePresetId = state.activePresetId;
                    state.account = deletion.account();
                    state.pendingPresetDeleteId = null;
                    boolean deleted = state.account.presets().stream()
                            .noneMatch(preset -> preset.id().equals(presetId));
                    if (!deleted) {
                        if (!deletion.cleanupWarnings().isEmpty()) {
                            state.status = UiMessage.literal(
                                    deletion.cleanupWarnings().get(0), UiMessage.Severity.ERROR);
                        }
                        return;
                    }
                    if (presetId.equals(state.selectedPresetId)) {
                        state.selectedPresetId = null;
                    }
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
                                    ClientOperations.ReconciliationTrigger.LOCAL_INTENT);
                        }
                    });
                    List<String> remainingCards = galleryPresenter.cardIds(
                            Optional.of(state.account),
                            Optional.ofNullable(state.activePresetId),
                            state.galleryQuery);
                    int nextOrdinal = removedOrdinal < 0
                            ? 0
                            : Math.min(removedOrdinal, remainingCards.size() - 1);
                    state.gallerySelectedCardId = remainingCards.isEmpty()
                            ? "gallery.add"
                            : remainingCards.get(Math.max(0, nextOrdinal));
                    if (origin.keyboard()) {
                        requestRuntimeFocus("gallery", state.gallerySelectedCardId);
                    }
                    state.status = deletion.cleanupWarnings().isEmpty()
                            ? UiMessage.success("nclskins.status.deleted")
                            : UiMessage.literal(
                                    deletion.cleanupWarnings().get(0), UiMessage.Severity.ERROR);
                    centerGalleryIfActiveChanged(previousActivePresetId);
                });
    }

    private void applyPreset(UUID presetId, boolean preserveGalleryOffset) {
        if (findPreset(presetId) == null) {
            return;
        }
        if (presetId.equals(state.activePresetId)
                && state.syncStatus != AppearanceSyncStatus.OFFICIAL
                && operations.rateLimitRemaining().isPresent()) {
            state.selectedPresetId = presetId;
            armRateLimitRecovery();
            publish();
            return;
        }
        state.selectedPresetId = presetId;
        submitRemote(
                UiMessage.info("nclskins.status.applying"),
                () -> operations.usePreset(presetId),
                result -> acceptPresetUse(result, preserveGalleryOffset),
                result -> result.remoteResult().map(ClientOperations.RemoteResult::outcome));
    }

    private void acceptPresetUse(ClientOperations.PresetUse use, boolean preserveGalleryOffset) {
        UUID previous = state.activePresetId;
        state.account = use.account();
        state.session = use.session();
        state.activePresetId = use.activePresetId();
        state.selectedPresetId = use.activePresetId();
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
                                ? ClientOperations.ReconciliationTrigger.EXPLICIT_RETRY : ClientOperations.ReconciliationTrigger.LOCAL_INTENT);
            }
        }
        if (!preserveGalleryOffset) {
            centerGalleryIfActiveChanged(previous);
        }
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
            ClientOperations.ReconciliationTrigger trigger) {
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
            ClientOperations.ReconciliationTrigger trigger) {
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
            ClientOperations.ReconciliationTrigger trigger) {
        currentReconciliationKey().ifPresent(key ->
                requestAppearanceReconciliation(key, trigger));
    }

    private void requestAppearanceReconciliation(
            ClientOperations.ReconciliationKey key,
            ClientOperations.ReconciliationTrigger trigger) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(trigger, "trigger");
        if (disposed) {
            return;
        }
        boolean start;
        synchronized (reconciliationMonitor) {
            ClientOperations.ReconciliationTrigger pending = pendingReconciliations.get(key);
            if (pending != null) {
                if (trigger.ordinal() > pending.ordinal()) {
                    pendingReconciliations.put(key, trigger);
                }
            } else if (activeReconciliation == null
                    || !activeReconciliation.key().equals(key)
                    || trigger.ordinal() > activeReconciliation.trigger().ordinal()) {
                pendingReconciliations.put(key, trigger);
            }
            start = !reconciliationRunning;
            if (start) {
                reconciliationRunning = true;
            }
        }
        state.syncInProgress = true;
        publish();
        if (start) {
            CompletableFuture.runAsync(this::drainAppearanceReconciliation, reconciliationWorker);
        }
    }

    private void drainAppearanceReconciliation() {
        while (!disposed) {
            ReconciliationRequest request;
            synchronized (reconciliationMonitor) {
                if (pendingReconciliations.isEmpty()) {
                    reconciliationRunning = false;
                    activeReconciliation = null;
                    onClient(this::finishAppearanceReconciliation);
                    return;
                }
                Map.Entry<ClientOperations.ReconciliationKey,
                                ClientOperations.ReconciliationTrigger>
                        pending = pendingReconciliations.entrySet().iterator().next();
                request = new ReconciliationRequest(pending.getKey(), pending.getValue());
                pendingReconciliations.remove(pending.getKey());
                activeReconciliation = request;
            }
            Optional<ClientOperations.ReconciliationResult> result = Optional.empty();
            Optional<ClientOperations.DurableAppearance> durableAfterFailure = Optional.empty();
            Throwable failure = null;
            try {
                if (operations.reconciliationKey().filter(request.key()::equals).isPresent()) {
                    result = Objects.requireNonNull(
                            operations.reconcileAppearance(request.key(), request.trigger()),
                            "reconciliation result");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = interrupted;
            } catch (Exception unavailable) {
                failure = unavailable;
                if (durableSettlementMayHaveAdvanced(unavailable)) {
                    try {
                        durableAfterFailure = operations.durableAppearance()
                                .filter(appearance -> appearance.accountId()
                                        .equals(request.key().accountId()));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        diagnose(
                                DiagnosticEvent.CLIENT_RECONCILIATION_CLEANUP_FAILED,
                                interrupted);
                    } catch (Exception unavailableDurableState) {
                        diagnose(
                                DiagnosticEvent.CLIENT_RECONCILIATION_CLEANUP_FAILED,
                                unavailableDurableState);
                    }
                }
            }
            if (failure != null) {
                diagnose(DiagnosticEvent.CLIENT_RECONCILIATION_FAILED, failure);
            }
            Optional<ClientOperations.ReconciliationResult> completed = result;
            Optional<ClientOperations.DurableAppearance> completedDurableAfterFailure =
                    durableAfterFailure;
            Throwable completedFailure = failure;
            onClient(() -> acceptAppearanceReconciliation(
                    request, completed, completedDurableAfterFailure, completedFailure));
            synchronized (reconciliationMonitor) {
                if (request.equals(activeReconciliation)) {
                    activeReconciliation = null;
                }
            }
        }
        synchronized (reconciliationMonitor) {
            pendingReconciliations.clear();
            activeReconciliation = null;
            reconciliationRunning = false;
        }
    }

    private void acceptAppearanceReconciliation(
            ReconciliationRequest request,
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
            ReconciliationRequest request,
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
        synchronized (reconciliationMonitor) {
            if (reconciliationRunning
                    || activeReconciliation != null
                    || !pendingReconciliations.isEmpty()) {
                return;
            }
        }
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
                    ClientOperations.ReconciliationTrigger.EXPLICIT_RETRY);
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
                        ClientOperations.ReconciliationTrigger.SESSION_REFRESHED);
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
        Objects.requireNonNull(failed, "failed");
        Objects.requireNonNull(translationKey, "translationKey");
        onClient(() -> {
            if (disposed || state.editor == null || !"editor.preview".equals(failed.id())) {
                return;
            }
            ViewSpec.Preview current = state.editor.present(
                            viewportWidth,
                            viewportHeight,
                            editorCapeScrollPosition,
                            editorModelScrollPosition,
                            viewChromeMetrics)
                    .previews()
                    .get(0);
            boolean stillRequested = capeFailure
                    ? current.capeId().equals(failed.capeId())
                    : current.skin().equals(failed.skin())
                            && current.imageRevision().equals(failed.imageRevision());
            if (!stillRequested) {
                return;
            }
            state.editor = state.editor.withPreviewFailure(UiMessage.error(translationKey));
            publish();
        });
    }

    private void clearEditorPreviewFailure(
            ViewSpec.Preview loaded, String translationKey, boolean capeFailure) {
        Objects.requireNonNull(loaded, "loaded");
        Objects.requireNonNull(translationKey, "translationKey");
        onClient(() -> {
            if (disposed || state.editor == null || !"editor.preview".equals(loaded.id())) {
                return;
            }
            ViewSpec.Preview current = state.editor.present(
                            viewportWidth,
                            viewportHeight,
                            editorCapeScrollPosition,
                            editorModelScrollPosition,
                            viewChromeMetrics)
                    .previews()
                    .get(0);
            boolean stillRequested = capeFailure
                    ? current.capeId().equals(loaded.capeId())
                    : current.skin().equals(loaded.skin())
                    && current.imageRevision().equals(loaded.imageRevision());
            if (!stillRequested) {
                return;
            }
            PresetEditorModel cleared = state.editor.withoutPreviewFailure(
                    UiMessage.error(translationKey));
            if (cleared == state.editor) {
                return;
            }
            state.editor = cleared;
            publish();
        });
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
        if (!Double.isFinite(pixelDelta) || pixelDelta == 0.0) {
            return;
        }
        setGalleryPosition(state.galleryScrollPosition + pixelDelta);
    }

    private void setGalleryOffset(int offset) {
        int bounded = Math.max(0, Math.min(galleryMaximum(), offset));
        if (bounded != state.galleryOffset) {
            state.galleryOffset = bounded;
            state.galleryScrollPosition = bounded;
            state.galleryScrollTarget = bounded;
            publish();
        }
    }

    private void setGalleryPosition(double position) {
        double bounded = Math.max(0.0, Math.min(galleryMaximum(), position));
        if (Math.abs(bounded - state.galleryScrollPosition) > 0.001
                || Math.abs(bounded - state.galleryScrollTarget) > 0.001) {
            state.galleryScrollPosition = bounded;
            state.galleryScrollTarget = bounded;
            state.galleryOffset = (int) Math.round(bounded);
            publish();
        }
    }

    private void resetGalleryScroll() {
        draggingGalleryScrollbar = false;
        state.galleryOffset = 0;
        state.galleryScrollPosition = 0.0;
        state.galleryScrollTarget = 0.0;
    }

    private void centerGalleryIfActiveChanged(UUID previousActivePresetId) {
        if (!Objects.equals(previousActivePresetId, state.activePresetId)) {
            centerGalleryOnActive();
        }
    }

    private void centerGalleryOnActive() {
        draggingGalleryScrollbar = false;
        double centered = galleryPresenter.initialScrollPosition(
                Optional.ofNullable(state.account),
                Optional.ofNullable(state.activePresetId),
                state.galleryQuery,
                viewportWidth,
                viewportHeight);
        state.galleryOffset = (int) Math.round(centered);
        state.galleryScrollPosition = centered;
        state.galleryScrollTarget = centered;
    }

    private ViewSpec galleryView(
            int width, int height, int mouseX, int mouseY) {
        state.gallerySelectedCardId = galleryPresenter.normalizeSelectedCardId(
                snapshot, state.galleryQuery, state.gallerySelectedCardId);
        return galleryPresenter.present(
                snapshot,
                width,
                height,
                mouseX,
                mouseY,
                preferredCapeMode,
                currentPlayerVariant(),
                state.galleryQuery,
                Optional.ofNullable(state.pendingPresetDeleteId),
                state.galleryScrollPosition,
                state.gallerySelectedCardId);
    }

    private int galleryMaximum() {
        return galleryPresenter.maximumScroll(
                snapshot, viewportWidth, viewportHeight, state.galleryQuery);
    }

    private void queueEditorContentScroll(double pixelDelta) {
        if (state.editor == null || !Double.isFinite(pixelDelta) || pixelDelta == 0.0) {
            return;
        }
        setEditorContentPosition((state.editor.selectedEditorTab() == EditorTab.APPEARANCE
                ? editorModelScrollPosition : editorCapeScrollPosition) + pixelDelta);
    }

    private double editorPositionFromScrollbar(int width, int height, double top) {
        return state.editor.selectedEditorTab() == EditorTab.APPEARANCE
                ? state.editor.modelPositionFromScrollbar(width, height, top)
                : state.editor.capePositionFromScrollbar(width, height, top, viewChromeMetrics);
    }

    private void setEditorContentPosition(double position) {
        if (state.editor == null) {
            return;
        }
        if (state.editor.selectedEditorTab() == EditorTab.APPEARANCE) {
            double bounded = state.editor.normalizedModelScrollPosition(viewportWidth, viewportHeight, position);
            if (Math.abs(bounded - editorModelScrollPosition) > 0.001) {
                editorModelScrollPosition = bounded;
                publish();
            }
            return;
        }
        double bounded = state.editor.normalizedCapeScrollPosition(
                viewportWidth, viewportHeight, position, viewChromeMetrics);
        if (Math.abs(bounded - editorCapeScrollPosition) > 0.001
                || Math.abs(bounded - editorCapeScrollTarget) > 0.001) {
            editorCapeScrollPosition = bounded;
            editorCapeScrollTarget = bounded;
            publish();
        }
    }

    private void resetEditorScroll() {
        pendingCapeProviderInspection = null;
        editorModelScrollPosition = 0.0;
        editorTabPreferenceSequence++;
        clearRuntimeFocus("preset_editor");
        if (state.editor != null) {
            if (state.editor.capeCatalog() != null && state.uiPreferences != null) {
                state.editor = state.editor.withCapeCatalog(state.editor.capeCatalog()
                        .withCollapsed(state.uiPreferences.collapsedCapeCollections()));
            }
            boolean newPreset = state.editor.originalPresetId().isEmpty();
            state.editor = state.editor.withSelectedEditorTab(newPreset || state.uiPreferences == null
                    ? EditorTab.APPEARANCE : state.uiPreferences.selectedEditorTab());
            if (newPreset) {
                requestRuntimeFocus("preset_editor", "editor.name");
            }
        }
        draggingEditorScrollbar = false;
        editorCapeScrollPosition = state.editor == null
                ? 0.0
                : state.editor.initialCapeScrollPosition(
                        viewportWidth, viewportHeight, viewChromeMetrics);
        editorCapeScrollTarget = editorCapeScrollPosition;
        reloadEditorCatalog();
    }

    private void initializeEditorCapeCatalog(LocalCapeReference offlineSeed) {
        if (state.editor == null || state.account == null) {
            return;
        }
        UUID accountId = state.account.accountId();
        Optional<ClientOperations.CapeEditorData> warmed =
                operations.warmedCapeEditorData(accountId);
        state.editor = state.editor.withCapeCatalog(warmed
                .map(data -> CapeCatalogModel.open(
                        state.account, state.providers, offlineSeed, state.editor.capeId(),
                        state.editor.capeChoices(), data.resourceCollections(), data.sourceHashes(),
                        data.resourceGeneration(), textResolver))
                .orElseGet(() -> CapeCatalogModel.open(
                        state.account, state.providers, offlineSeed, state.editor.capeId(),
                        state.editor.capeChoices(), textResolver)));
        installWarmedCapePreviews(accountId, true);
    }

    private void installWarmedCapePreviews(UUID accountId, boolean replaceResources) {
        Map<String, byte[]> warmed = operations.warmedCapePreviews(accountId);
        if (replaceResources) {
            previewBytes.keySet().removeIf(key -> key.startsWith("resource:cape:"));
        }
        warmed.forEach((key, bytes) -> previewBytes.put(key, bytes.clone()));
    }

    private boolean clampGalleryScroll() {
        double maximum = galleryMaximum();
        double position = Math.max(0.0, Math.min(maximum, state.galleryScrollPosition));
        double target = Math.max(0.0, Math.min(maximum, state.galleryScrollTarget));
        boolean changed = Math.abs(position - state.galleryScrollPosition) > 0.001
                || Math.abs(target - state.galleryScrollTarget) > 0.001;
        state.galleryScrollPosition = position;
        state.galleryScrollTarget = target;
        if (changed) {
            state.galleryOffset = (int) Math.round(position);
        }
        return changed;
    }

    private boolean clampEditorScroll() {
        double modelPosition = state.editor.normalizedModelScrollPosition(
                viewportWidth, viewportHeight, editorModelScrollPosition);
        boolean modelChanged = Math.abs(modelPosition - editorModelScrollPosition) > 0.001;
        editorModelScrollPosition = modelPosition;
        double position = state.editor.normalizedCapeScrollPosition(
                viewportWidth, viewportHeight, editorCapeScrollPosition, viewChromeMetrics);
        double target = state.editor.normalizedCapeScrollPosition(
                viewportWidth, viewportHeight, editorCapeScrollTarget, viewChromeMetrics);
        boolean changed = Math.abs(position - editorCapeScrollPosition) > 0.001
                || Math.abs(target - editorCapeScrollTarget) > 0.001;
        editorCapeScrollPosition = position;
        editorCapeScrollTarget = target;
        return changed || modelChanged;
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
        if (state.addSource == null) {
            return;
        }
        int bounded = addSourcePresenter.normalizedScrollOffset(
                state.addSource,
                viewportWidth,
                viewportHeight,
                offset,
                viewChromeMetrics);
        if (bounded != state.addSource.scrollOffset()
                || Math.abs(addSourceScrollPosition - bounded) > 0.001
                || Math.abs(addSourceScrollTarget - bounded) > 0.001) {
            state.addSource = state.addSource.withScrollOffset(bounded);
            addSourceScrollPosition = bounded;
            addSourceScrollTarget = bounded;
            publish();
        }
    }

    private void queueAddSourceScroll(double delta) {
        if (state.addSource == null || !Double.isFinite(delta) || delta == 0.0) {
            return;
        }
        int maximum = addSourcePresenter.maximumScroll(
                state.addSource, viewportWidth, viewportHeight, viewChromeMetrics);
        double bounded = Math.max(
                0.0, Math.min(maximum, addSourceScrollPosition + delta));
        if (Math.abs(bounded - addSourceScrollPosition) > 0.001
                || Math.abs(bounded - addSourceScrollTarget) > 0.001) {
            addSourceScrollPosition = bounded;
            addSourceScrollTarget = bounded;
            state.addSource = state.addSource.withScrollOffset((int) Math.round(bounded));
            publish();
        }
    }

    private void resetAddSourceScroll() {
        draggingAddSourceScrollbar = false;
        int offset = state.addSource == null ? 0 : state.addSource.scrollOffset();
        addSourceScrollPosition = offset;
        addSourceScrollTarget = offset;
    }

    private void clampAddSourceScroll() {
        if (state.addSource == null) {
            resetAddSourceScroll();
            return;
        }
        int maximum = addSourcePresenter.maximumScroll(
                state.addSource, viewportWidth, viewportHeight, viewChromeMetrics);
        addSourceScrollPosition = Math.max(0.0, Math.min(maximum, addSourceScrollPosition));
        addSourceScrollTarget = Math.max(0.0, Math.min(maximum, addSourceScrollTarget));
        state.addSource = state.addSource.withScrollOffset((int) Math.round(addSourceScrollPosition));
    }

    private void updateEditor(java.util.function.UnaryOperator<PresetEditorModel> update) {
        if (state.editor == null) {
            return;
        }
        state.editor = Objects.requireNonNull(update.apply(state.editor), "editor update");
        publish();
    }

    private void selectEditorVariant(SkinVariant variant) {
        if (state.editor == null || state.editor.selectedEditorTab() != EditorTab.APPEARANCE) {
            return;
        }
        SkinVariant before = state.editor.variant();
        state.editor = state.editor.selectVariant(variant);
        if (state.editor.variant() != before) {
            prepareEditorEvidence(state.editor);
            rememberPreferredSkinVariant(state.editor.variant());
        }
        publish();
    }

    private void prepareEditorEvidence(PresetEditorModel editor) {
        Objects.requireNonNull(editor, "editor");
        state.editorEvidence = null;
        Optional<UUID> assetId = editor.skin().optionalAssetId();
        if (editor.png().isPresent()) {
            try {
                SkinFeatureEvidence evidence = analyzeImportedSkinFeatureEvidence(
                        editor.png().orElseThrow().bytes());
                state.editorEvidence = evidence;
            } catch (PngValidationException ignored) {
            }
            return;
        }
        if (assetId.isEmpty()) {
            return;
        }
        SkinFeatureEvidence cached = state.assetEvidence.get(assetId.orElseThrow());
        if (cached != null) {
            state.editorEvidence = cached;
            return;
        }
        String revision = editorEvidenceRevision(editor);
        SkinVariant variant = editor.variant();
        CompletableFuture<Optional<byte[]>> loaded = loadSkinPreview(editor.skin());
        loaded.whenComplete((bytes, failure) -> {
            if (failure != null || bytes == null || bytes.isEmpty()) {
                return;
            }
            try {
                SkinFeatureEvidence evidence = analyzeStoredSkinFeatureEvidence(
                        bytes.orElseThrow());
                onClient(() -> {
                    boolean changed = !evidence.equals(state.assetEvidence.put(
                            assetId.orElseThrow(), evidence));
                    if (editorEvidenceStillCurrent(revision, variant)) {
                        changed |= !evidence.equals(state.editorEvidence);
                        state.editorEvidence = evidence;
                    }
                    if (changed && !disposed) {
                        publish();
                    }
                });
            } catch (PngValidationException ignored) {
            }
        });
    }

    private boolean editorEvidenceStillCurrent(String revision, SkinVariant variant) {
        PresetEditorModel editor = state.editor;
        return editor != null
                && editorEvidenceRevision(editor).equals(revision)
                && editor.variant() == variant;
    }

    private static String editorEvidenceRevision(PresetEditorModel editor) {
        return editor.png()
                .map(PresetEditorModel.DraftPng::revision)
                .orElseGet(() -> editor.skin().optionalAssetId()
                        .map(id -> "asset:" + id)
                        .orElse("current-player"));
    }

    private void rememberPreferredSkinVariant(SkinVariant variant) {
        if (state.account == null) {
            return;
        }
        AccountUiPreferences preferences = state.uiPreferences == null
                ? AccountUiPreferences.defaults(state.account.accountId())
                : state.uiPreferences;
        state.uiPreferences = preferences.withPreferredSkinVariant(variant);
        if (state.addSource != null) {
            state.addSource = state.addSource.withPreferredVariant(variant);
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

    private static boolean durableSettlementMayHaveAdvanced(Throwable failure) {
        return unwrap(failure) instanceof RemoteMutationSettlementException;
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
                Optional.ofNullable(state.selectedSkinId),
                Optional.ofNullable(state.selectedPresetId),
                Optional.ofNullable(state.selectedCapeId),
                Optional.ofNullable(state.currentOfficialSkinId),
                Optional.ofNullable(state.activePresetId),
                Optional.ofNullable(state.editor),
                Optional.ofNullable(state.addSource),
                state.status,
                state.busy,
                state.rateLimited,
                state.rateLimitProgress,
                state.capeProviderCooldowns,
                state.galleryOffset,
                state.generation,
                state.intentRevision,
                state.syncStatus,
                state.syncInProgress,
                state.sessionActivity,
                skinExtensionEnvironment,
                state.assetEvidence,
                state.catalogEvidence,
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

    private static String catalogEvidenceKey(
            String collectionId, String skinId, SkinVariant variant) {
        return collectionId + ':' + skinId + ':' + variant.name();
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
        if (state.addSource != null && (state.status.key().equals("nclskins.error.png")
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

    private CompletableFuture<Optional<byte[]>> requestPreview(
            String key, ThrowingSupplier<Optional<byte[]>> source) {
        ensureNotDisposed();
        byte[] cached = previewBytes.get(key);
        if (cached != null) {
            return publishPreview(Optional.of(cached.clone()));
        }

        CompletableFuture<Optional<byte[]>> publication;
        boolean start;
        synchronized (previewInFlight) {
            publication = previewInFlight.get(key);
            start = publication == null;
            if (start) {
                publication = new CompletableFuture<>();
                previewInFlight.put(key, publication);
            }
        }
        CompletableFuture<Optional<byte[]>> shared = publication;
        if (!start) {
            return shared.thenApply(bytes -> bytes.map(byte[]::clone));
        }

                CompletableFuture.supplyAsync(() -> {
                    try {
                        return Objects.requireNonNull(source.get(), "preview source result")
                                .map(byte[]::clone);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        diagnose(DiagnosticEvent.CLIENT_PREVIEW_SOURCE_FAILED, interrupted);
                        return Optional.<byte[]>empty();
                    } catch (Exception failure) {
                        diagnose(DiagnosticEvent.CLIENT_PREVIEW_SOURCE_FAILED, failure);
                        return Optional.<byte[]>empty();
                    }
                }, worker)
                .whenComplete((bytes, failure) -> onClient(() -> {
                    previewInFlight.remove(key, shared);
                    if (disposed) {
                        shared.complete(Optional.empty());
                        return;
                    }
                    Optional<byte[]> result = failure == null && bytes != null
                            ? bytes.map(byte[]::clone)
                            : Optional.empty();
                    if (!staleCatalogPreview(key)) {
                        result.ifPresent(value -> previewBytes.put(key, value.clone()));
                    }
                    shared.complete(result.map(byte[]::clone));
                }));
        return shared.thenApply(bytes -> bytes.map(byte[]::clone));
    }

    private void invalidateCatalogPreviews() {
        catalogPreviewEpoch++;
        previewBytes.keySet().removeIf(key -> key.startsWith("catalog:"));
        previewInFlight.keySet().removeIf(key -> key.startsWith("catalog:"));
    }

    private boolean staleCatalogPreview(String key) {
        return key.startsWith("catalog:")
                && !key.startsWith("catalog:" + catalogPreviewEpoch + ":");
    }

    private CompletableFuture<Optional<byte[]>> publishPreview(Optional<byte[]> bytes) {
        CompletableFuture<Optional<byte[]>> publication = new CompletableFuture<>();
        onClient(() -> publication.complete(bytes.map(byte[]::clone)));
        return publication;
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
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private record CapeImportResult(
            com.naocraftlab.skins.core.model.PersonalCapeEntry entry,
            Optional<AccountState> account) {
        private CapeImportResult {
            Objects.requireNonNull(entry, "entry");
            account = Objects.requireNonNull(account, "account");
        }
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private record CatalogSelection(
            SkinCatalogSource.SkinDescriptor skin,
            Optional<CatalogOrigin> origin,
            Map<SkinVariant, byte[]> variants,
            Map<SkinVariant, PresetEditorModel.ReusableCatalogVariant> reusableVariants,
            SkinVariant initialVariant) {
        private CatalogSelection {
            Objects.requireNonNull(skin, "skin");
            origin = Objects.requireNonNull(origin, "origin");
            variants = Map.copyOf(Objects.requireNonNull(variants, "variants"));
            reusableVariants = Map.copyOf(
                    Objects.requireNonNull(reusableVariants, "reusableVariants"));
            if (origin.isPresent() == !reusableVariants.isEmpty()) {
                throw new IllegalArgumentException(
                        "catalog selection must be external or reusable");
            }
            Objects.requireNonNull(initialVariant, "initialVariant");
        }
    }

    private record AddSourceData(
            AccountUiPreferences preferences,
            List<SkinCatalogSource.CollectionDescriptor> collections,
            Map<ClientOperations.CatalogVariant, SkinFeatureEvidence> featureEvidence) {
        private AddSourceData {
            Objects.requireNonNull(preferences, "preferences");
            collections = List.copyOf(Objects.requireNonNull(collections, "collections"));
            featureEvidence = Map.copyOf(Objects.requireNonNull(
                    featureEvidence, "featureEvidence"));
        }
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

    private record PersonalCatalogAction(String collectionId, String sha256) {
        private PersonalCatalogAction {
            Objects.requireNonNull(collectionId, "collectionId");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    private record ReconciliationRequest(
            ClientOperations.ReconciliationKey key,
            ClientOperations.ReconciliationTrigger trigger) {
        private ReconciliationRequest {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(trigger, "trigger");
        }
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

    private static final class State {
        private AppearanceProviders providers =
                AppearanceProviders.initial();
        private ClientSnapshot.Lifecycle lifecycle = ClientSnapshot.Lifecycle.NEW;
        private AccountState account;
        private SessionValidation session;
        private RemoteProfile remoteProfile;
        private PresetApplicationOutcome lastMutation;
        private UUID selectedSkinId;
        private UUID selectedPresetId;
        private String selectedCapeId;
        private UUID currentOfficialSkinId;
        private UUID activePresetId;
        private PresetEditorModel editor;
        private PersonalSkinSource editorPersonalSource = PersonalSkinSource.FILE;
        private AddSourceModel addSource;
        private ExternalImportModel externalImport;
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
        private int galleryOffset;
        private double galleryScrollPosition;
        private double galleryScrollTarget;
        private final java.util.EnumMap<AppearanceProviders.Component, BuiltinProvider> providerPreviewSources = new java.util.EnumMap<>(AppearanceProviders.Component.class);
        private boolean providersOpen;
        private boolean editorReturnsToProviders;
        private boolean galleryReturnsToProviders;
        private boolean providerAdding;
        private double providerChooserOffset;
        private double providerRowsOffset;
        private AppearanceProviders.Component providerComponent = AppearanceProviders.Component.SKIN;
        private PreviewInteractionModel providerPreview = PreviewInteractionModel.editor(240, PreviewRenderer.CapeMode.CAPE);
        private String galleryQuery = "";
        private String gallerySelectedCardId;
        private String pendingPresetName;
        private UUID pendingPresetDeleteId;
        private String personalRenameCollectionId;
        private String personalRenameHash;
        private String personalRenameValue = "";
        private long generation;
        private boolean readyData;
        private final Map<UUID, SkinFeatureEvidence> assetEvidence = new LinkedHashMap<>();
        private final Map<String, SkinFeatureEvidence> catalogEvidence = new LinkedHashMap<>();
        private SkinFeatureEvidence editorEvidence;

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
            selectedSkinId = null;
            selectedPresetId = null;
            editor = null;
            editorPersonalSource = PersonalSkinSource.FILE;
            addSource = null;
            externalImport = null;
            status = UiMessage.info("nclskins.status.loading");
            busy = false;
            rateLimited = false;
            rateLimitProgress = Optional.empty();
            capeProviderCooldowns = Map.of();
            syncInProgress = false;
            sessionActivity = ClientSnapshot.SessionActivity.NONE;
            galleryOffset = 0;
            galleryScrollPosition = 0.0;
            galleryScrollTarget = 0.0;
            providersOpen = false;
            editorReturnsToProviders = false;
            galleryReturnsToProviders = false;
            providerAdding = false;
            providerPreviewSources.clear();
            galleryQuery = "";
            gallerySelectedCardId = null;
            pendingPresetName = null;
            pendingPresetDeleteId = null;
            personalRenameCollectionId = null;
            personalRenameHash = null;
            personalRenameValue = "";
            if (!retainReadyData) {
                assetEvidence.clear();
            }
            catalogEvidence.clear();
            editorEvidence = null;
        }
    }
}

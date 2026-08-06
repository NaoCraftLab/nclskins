package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CatalogCollectionOrder;
import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.OuterLayerVisibilityController;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.PreviewPreferences;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.ServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.client.SignedTextureVerifier;
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
import com.naocraftlab.skins.core.model.CatalogOrigin;
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
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.LibraryOperationException;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.RecoveryAction;
import com.naocraftlab.skins.core.service.SessionValidation;

import java.io.IOException;
import java.nio.file.Path;
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


public final class ClientRuntime implements AutoCloseable {
    private static final double WHEEL_SCROLL_PIXELS = 32.0;
    private static final int SESSION_RETRY_FEEDBACK_TICKS = 6;

    private final ClientOperations operations;
    private final ClientExecutor clientExecutor;
    private final FilePicker filePicker;
    private final Executor worker;
    private final ExecutorService ownedWorker;
    private final Executor reconciliationWorker;
    private final ExecutorService ownedReconciliationWorker;
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
    private long sessionRetryTicket = -1L;
    private boolean sessionRetryFeedbackRendered;
    private int sessionRetryFeedbackTicksRemaining;
    private SessionRetrySettlement pendingSessionRetrySettlement;

    private volatile ClientSnapshot snapshot = ClientSnapshot.initial();
    private volatile boolean disposed;

    private boolean startupWarmupStarted;
    private int viewportWidth = 320;
    private int viewportHeight = 240;
    private boolean draggingGalleryScrollbar;
    private double galleryScrollbarGrabOffset;
    private boolean draggingEditorCapeScrollbar;
    private double editorCapeScrollbarGrabOffset;
    private double editorCapeScrollPosition;
    private double editorCapeScrollTarget;
    private boolean draggingAddSourceScrollbar;
    private double addSourceScrollbarGrabOffset;
    private double addSourceScrollPosition;
    private double addSourceScrollTarget;
    private PreviewRenderer.CapeMode preferredCapeMode = PreviewPreferences.capeMode();

    public ClientRuntime(
            ClientOperations operations,
            ClientExecutor clientExecutor,
            FilePicker filePicker,
            Executor worker,
            TextResolver textResolver,
            Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh) {
        this(
                operations,
                clientExecutor,
                filePicker,
                worker,
                null,
                worker,
                null,
                textResolver,
                Optional.empty(),
                appearanceRefresh,
                Optional.empty(),
                Optional.empty(),
                ServerAppearanceReadinessCoordinator.DelayScheduler.system());
    }

    public ClientRuntime(
            ClientOperations operations,
            ClientExecutor clientExecutor,
            FilePicker filePicker,
            Executor worker,
            TextResolver textResolver,
            Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh,
            Optional<ServerAppearanceRefreshNotifier> serverAppearanceRefreshNotifier) {
        this(
                operations,
                clientExecutor,
                filePicker,
                worker,
                null,
                worker,
                null,
                textResolver,
                Optional.empty(),
                appearanceRefresh,
                Optional.empty(),
                serverAppearanceRefreshNotifier,
                ServerAppearanceReadinessCoordinator.DelayScheduler.system());
    }

    ClientRuntime(
            ClientOperations operations,
            ClientExecutor clientExecutor,
            FilePicker filePicker,
            Executor worker,
            TextResolver textResolver,
            Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh,
            Optional<ServerAppearanceRefreshNotifier> serverAppearanceRefreshNotifier,
            ServerAppearanceReadinessCoordinator.DelayScheduler readinessScheduler) {
        this(
                operations,
                clientExecutor,
                filePicker,
                worker,
                null,
                worker,
                null,
                textResolver,
                Optional.empty(),
                appearanceRefresh,
                Optional.empty(),
                serverAppearanceRefreshNotifier,
                readinessScheduler);
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
            ServerAppearanceReadinessCoordinator.DelayScheduler readinessScheduler) {
        this(
                operations,
                clientExecutor,
                filePicker,
                worker,
                null,
                reconciliationWorker,
                null,
                textResolver,
                Optional.empty(),
                appearanceRefresh,
                Optional.empty(),
                serverAppearanceRefreshNotifier,
                readinessScheduler);
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
            ServerAppearanceReadinessCoordinator.DelayScheduler readinessScheduler) {
        this(
                operations,
                clientExecutor,
                filePicker,
                worker,
                null,
                worker,
                null,
                textResolver,
                Optional.empty(),
                appearanceRefresh,
                outerLayerVisibilityController,
                serverAppearanceRefreshNotifier,
                readinessScheduler);
    }

    private ClientRuntime(
            ClientOperations operations,
            ClientExecutor clientExecutor,
            FilePicker filePicker,
            Executor worker,
            ExecutorService ownedWorker,
            Executor reconciliationWorker,
            ExecutorService ownedReconciliationWorker,
            TextResolver textResolver,
            Optional<CurrentPlayerAppearanceSource> currentAppearanceSource,
            Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh,
            Optional<OuterLayerVisibilityController> outerLayerVisibilityController,
            Optional<ServerAppearanceRefreshNotifier> serverAppearanceRefreshNotifier,
            ServerAppearanceReadinessCoordinator.DelayScheduler readinessScheduler) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.clientExecutor = Objects.requireNonNull(clientExecutor, "clientExecutor");
        this.filePicker = Objects.requireNonNull(filePicker, "filePicker");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.ownedWorker = ownedWorker;
        this.reconciliationWorker = Objects.requireNonNull(
                reconciliationWorker, "reconciliationWorker");
        this.ownedReconciliationWorker = ownedReconciliationWorker;
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
    }


    public static ClientRuntime createDefaultWithDeterministicAppearance(
            GameSessionTokenSource tokenSource,
            SkinCatalogSource bundledSkins,
            CurrentPlayerAppearanceSource currentAppearanceSource,
            ClientExecutor clientExecutor,
            FilePicker filePicker,
            TextResolver textResolver,
            SignedTextureVerifier signedTextureVerifier,
            PlayerAppearanceSink<AcknowledgedAppearanceAssets> sink,
            OuterLayerVisibilityController outerLayerVisibilityController,
            ServerAppearanceRefreshNotifier serverAppearanceRefreshNotifier) {
        ExecutorService worker = newWorker("nclskins-client-runtime");
        ExecutorService reconciliationWorker = newWorker("nclskins-appearance-reconciliation");
        DefaultClientOperations operations = DefaultClientOperations
                .createDefault(tokenSource, bundledSkins)
                .enablePublicImports(signedTextureVerifier);
        AppearanceRefreshCoordinator<AcknowledgedAppearanceAssets> refresh =
                new AppearanceRefreshCoordinator<>(
                        clientExecutor,
                        operations.deterministicAppearanceResolver(worker),
                        sink);
        return new ClientRuntime(
                operations,
                clientExecutor,
                filePicker,
                worker,
                worker,
                reconciliationWorker,
                reconciliationWorker,
                textResolver,
                Optional.of(Objects.requireNonNull(currentAppearanceSource, "currentAppearanceSource")),
                Optional.of(refresh),
                Optional.of(Objects.requireNonNull(
                        outerLayerVisibilityController, "outerLayerVisibilityController")),
                Optional.of(Objects.requireNonNull(
                        serverAppearanceRefreshNotifier, "serverAppearanceRefreshNotifier")),
                ServerAppearanceReadinessCoordinator.DelayScheduler.system());
    }

    public ClientSnapshot snapshot() {
        return snapshot;
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
        onClient(() -> {
            ensureNotDisposed();
            if (state.lifecycle == ClientSnapshot.Lifecycle.CLOSED) {
                state.resetForReopen();
                if (state.readyData) {
                    centerGalleryOnActive();
                }
            }
            initializeOnClient();
        });
    }


    public void closeScreen() {
        onClient(this::closeScreenOnClient);
    }

    public void escapePressed() {
        onClient(() -> {
            if (disposed
                    || state.lifecycle == ClientSnapshot.Lifecycle.CLOSED
                    || state.busy && state.externalImport == null) {
                return;
            }
            if (state.pendingPresetDeleteId != null) {
                cancelPresetDeletion(state.pendingPresetDeleteId);
                return;
            }
            if (state.personalRenameHash != null) {
                cancelPersonalSkinRename();
                return;
            }
            if (state.addSource != null
                    && state.addSource.personalSkinDeletion().isPresent()) {
                cancelPersonalSkinDeletion();
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
        state.generation++;
        state.lifecycle = ClientSnapshot.Lifecycle.CLOSED;
        state.busy = false;
        clearSessionRetryFeedback();
        state.editor = null;
        state.addSource = null;
        state.externalImport = null;
        if (draggingGalleryScrollbar) {
            state.galleryScrollTarget = state.galleryScrollPosition;
        }
        draggingGalleryScrollbar = false;
        draggingEditorCapeScrollbar = false;
        draggingAddSourceScrollbar = false;
        addSourceScrollPosition = 0.0;
        addSourceScrollTarget = 0.0;
        publish();
    }

    public void tick() {
        onClient(() -> {
            if (disposed || state.lifecycle == ClientSnapshot.Lifecycle.CLOSED) {
                return;
            }
            advanceSessionRetryFeedback();
            boolean rateLimited = operations.rateLimited();
            if (rateLimited != state.rateLimited) {
                state.rateLimited = rateLimited;
                publish();
            }
            boolean scrollChanged = clampGalleryScroll();
            if (state.editor != null) {
                scrollChanged |= clampEditorCapeScroll();
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
            if (scrollChanged) {
                publish();
            }
        });
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
                            publication.complete(AppearanceRefreshCoordinator.Result.DEFERRED);
                            return;
                        }
                        if (durable.isPresent()
                                && durable.filter(this::currentSessionOwns).isEmpty()) {
                            publication.complete(AppearanceRefreshCoordinator.Result.DEFERRED);
                            return;
                        }
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
                                        publication.complete(
                                                AppearanceRefreshCoordinator.Result.DEFERRED);
                                    }
                                }));
                    }));
        });
        return publication;
    }

    public ViewSpec view(int width, int height, int mouseX, int mouseY) {
        ensureNotDisposed();
        viewportWidth = width;
        viewportHeight = height;
        PresetEditorModel editor = state.editor;
        if (editor != null) {
            return editor.present(width, height, editorCapeScrollPosition);
        }
        if (state.externalImport != null) {
            return externalImportPresenter.present(
                    state.externalImport,
                    state.busy,
                    Optional.of(state.status),
                    width,
                    height);
        }
        if (state.addSource != null) {
            return addSourcePresenter.present(
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
                            state.personalRenameValue)));
        }
        return galleryView(width, height, mouseX, mouseY);
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


    public Optional<CurrentPlayerAppearanceSource.PlayerAppearance> currentPlayerAppearance() {
        ensureNotDisposed();
        if (!clientExecutor.isClientThread()) {
            throw new IllegalStateException("Current player appearance is client-thread-only");
        }
        try {
            return currentAppearanceSource.map(CurrentPlayerAppearanceSource::currentPlayerAppearance);
        } catch (RuntimeException unavailableAppearance) {
            return Optional.empty();
        }
    }


    public void dispatchWidget(String widgetId) {
        dispatchWidget(widgetId, false);
    }


    public void dispatchWidget(String widgetId, boolean reverse) {
        Objects.requireNonNull(widgetId, "widgetId");
        onClient(() -> dispatchWidgetOnClient(widgetId, reverse));
    }

    public void dispatchText(String widgetId, String value) {
        Objects.requireNonNull(widgetId, "widgetId");
        Objects.requireNonNull(value, "value");
        onClient(() -> {
            if ("gallery.search".equals(widgetId)
                    && state.editor == null
                    && state.addSource == null) {
                if (state.galleryQuery.equals(value)) {
                    return;
                }
                state.galleryQuery = value;
                resetGalleryScroll();
                state.pendingPresetDeleteId = null;
                publish();
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
            if (button != 0 || state.busy) {
                return;
            }
            if (state.editor != null) {
                ViewSpec editorView = state.editor.present(
                        viewportWidth, viewportHeight, editorCapeScrollPosition);
                Optional<ViewSpec.Scrollbar> capeScrollbar = editorView.scrollbar()
                        .filter(scrollbar -> scrollbar.orientation()
                                == ViewSpec.Scrollbar.Orientation.VERTICAL)
                        .filter(scrollbar -> scrollbar.track().contains(mouseX, mouseY));
                if (capeScrollbar.isPresent()) {
                    ViewSpec.Scrollbar scrollbar = capeScrollbar.orElseThrow();
                    draggingEditorCapeScrollbar = true;
                    editorCapeScrollbarGrabOffset = scrollbar.thumb().contains(mouseX, mouseY)
                            ? mouseY - scrollbar.thumb().y()
                            : scrollbar.thumb().height() / 2.0;
                    setEditorCapePosition(state.editor.capePositionFromScrollbar(
                            viewportWidth,
                            viewportHeight,
                            mouseY - editorCapeScrollbarGrabOffset));
                    return;
                }
                Bounds previewBounds = editorView
                        .previews()
                        .get(0)
                        .bounds();
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
                        state.addSource, state.busy, viewportWidth, viewportHeight);
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
                            mouseY - addSourceScrollbarGrabOffset));
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
            if (draggingEditorCapeScrollbar && state.editor != null) {
                setEditorCapePosition(state.editor.capePositionFromScrollbar(
                        viewportWidth,
                        viewportHeight,
                        mouseY - editorCapeScrollbarGrabOffset));
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
                        mouseY - addSourceScrollbarGrabOffset));
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
            draggingGalleryScrollbar = false;
            draggingEditorCapeScrollbar = false;
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
            if (state.editor != null) {
                ViewSpec editorView = state.editor.present(
                        viewportWidth, viewportHeight, editorCapeScrollPosition);
                boolean capeGallery = editorView.clipRegions().stream()
                        .filter(region -> region.id().equals("editor.capes"))
                        .map(ViewSpec.ClipRegion::bounds)
                        .anyMatch(bounds -> bounds.contains(mouseX, mouseY));
                double amount = dominantScrollAmount(horizontalAmount, verticalAmount);
                if (capeGallery && amount != 0.0) {
                    queueEditorCapeScroll(-amount * WHEEL_SCROLL_PIXELS);
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
                    setGalleryPosition(galleryPresenter.scrollPositionDelta(
                            viewportWidth, viewportHeight, offsetPixels));
                }
                case "add.catalog" -> {
                    if (state.addSource == null
                            || state.addSource.selectedTab() != AddSourceTab.CATALOG
                            || state.addSource.personalSkinDeletion().isPresent()) {
                        return;
                    }
                    setAddSourceOffset((int) Math.round(offsetPixels));
                }
                case "external.review" -> {
                    if (state.externalImport == null || state.externalImport.review().isEmpty()) {
                        return;
                    }
                    state.externalImport = state.externalImport.withReviewScroll(
                            Math.max(0, (int) Math.round(offsetPixels)));
                    publish();
                }
                case "editor.capes" -> {
                    if (state.editor == null) {
                        return;
                    }
                    setEditorCapePosition(offsetPixels);
                }
                default -> {

                }
            }
        });
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


    public CompletableFuture<Optional<byte[]>> loadCapePreview(String capeId) {
        Objects.requireNonNull(capeId, "capeId");
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
                clearEditorPreviewFailure(preview, "nclskins.error.preview", false);
            }
        });
    }

    public void importSkin(String name, SkinVariant variant, byte[] pngBytes) {
        Objects.requireNonNull(variant, "variant");
        byte[] owned = Objects.requireNonNull(pngBytes, "pngBytes").clone();
        submit(
                UiMessage.info("nclskins.status.saving"),
                () -> operations.importSkin(name, variant, new PngValidator().normalizeSkin(owned)),
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
            disposed = true;
            state.generation++;
            state.lifecycle = ClientSnapshot.Lifecycle.CLOSED;
            state.busy = false;
            clearSessionRetryFeedback();
            state.editor = null;
            state.addSource = null;
            appearanceRefresh.ifPresent(AppearanceRefreshCoordinator::close);
            serverAppearanceReadiness.ifPresent(ServerAppearanceReadinessCoordinator::close);
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
            synchronized (reconciliationMonitor) {
                pendingReconciliations.clear();
                activeReconciliation = null;
                reconciliationRunning = false;
            }
        });
    }

    private void initializeOnClient() {
        ensureNotDisposed();
        if (state.lifecycle == ClientSnapshot.Lifecycle.INITIALIZING
                || state.lifecycle == ClientSnapshot.Lifecycle.READY
                || state.busy) {
            return;
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
                    CompletableFuture<AppearanceRefreshCoordinator.Result> localRebind =
                            acceptInitialData(data, true);
                    if (operations.reconciliationRecommended(data)) {
                        reconcileAfterLocalRebind(
                                localRebind,
                                ClientOperations.ReconciliationTrigger.GALLERY_OPEN);
                    }
                });
    }

    private CompletableFuture<AppearanceRefreshCoordinator.Result> acceptInitialData(
            ClientOperations.InitialData data, boolean initialized) {
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
        state.intentRevision = data.intentRevision();
        state.syncStatus = data.syncStatus();
        state.uiPreferences = data.uiPreferences();
        state.ownedCapes = data.ownedCapes();
        state.readyData = true;
        state.rateLimited = operations.rateLimited();
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
                refreshLocalAppearance(data.localAppearance());
        data.outerLayerVisibility().ifPresent(this::applyDurableOuterLayerVisibility);
        if (data.ownedCapes().capes().isEmpty()) {
            return localRebind;
        }
        long capeWarmupGeneration = state.generation;
        CompletableFuture.runAsync(() -> {
                    try {
                        operations.warmOwnedCapeCache();
                    } catch (Exception ignored) {

                    }
                }, worker)
                .thenRunAsync(() -> {
                    try {
                        Optional<OwnedCapeInventory> warmed = operations.ownedCapeInventory();
                        onClient(() -> {
                            if (!disposed && state.generation == capeWarmupGeneration) {
                                warmed.ifPresent(value -> state.ownedCapes = value);
                                publish();
                            }
                        });
                    } catch (Exception ignored) {

                    }
                }, worker);
        return localRebind;
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
        state.intentRevision = data.intentRevision();
        state.syncStatus = data.syncStatus();
        state.uiPreferences = data.uiPreferences();
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

    private void dispatchWidgetOnClient(String widgetId, boolean reverse) {
        ensureNotDisposed();
        if (state.lifecycle == ClientSnapshot.Lifecycle.CLOSED) {
            return;
        }
        if (widgetId.startsWith("gallery.preset.")) {
            dispatchPresetWidget(widgetId);
            return;
        }
        if (widgetId.startsWith("add.catalog.collection:")) {
            toggleCatalogCollection(widgetId.substring("add.catalog.collection:".length()));
            return;
        }
        if (widgetId.startsWith("add.catalog.delete:")) {
            personalCatalogAction(widgetId, "add.catalog.delete:")
                    .ifPresent(action -> requestPersonalSkinDeletion(
                            action.collectionId(), action.sha256()));
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
            case "gallery.done" -> closeScreen();
            case "add.tab.file" -> selectAddSourceTab(AddSourceTab.FILE);
            case "add.tab.catalog" -> selectAddSourceTab(AddSourceTab.CATALOG);
            case "add.file.choose" -> chooseAddSourcePng();
            case "add.external.launcher" -> openExternalImport(ExternalImportModel.Category.LAUNCHER);
            case "add.external.mod" -> openExternalImport(ExternalImportModel.Category.MOD);
            case "add.player.load" -> loadRemoteImport(true);
            case "add.url.load" -> loadRemoteImport(false);
            case "add.catalog.filter" -> cycleCatalogFilter(reverse);
            case "add.catalog.delete.confirm" -> confirmPersonalSkinDeletion();
            case "add.catalog.delete.cancel" -> cancelPersonalSkinDeletion();
            case "add.catalog.rename.save" -> savePersonalSkinRename();
            case "add.catalog.rename.cancel" -> cancelPersonalSkinRename();
            case "add.cancel" -> cancelAddSource();
            case "external.back" -> cancelExternalImport();
            case "external.review.toggle_all" -> toggleAllExternalCandidates();
            case "external.review.commit" -> commitExternalImport();
            case "external.review.cancel" -> cancelExternalReview();
            case "editor.model" -> toggleEditorVariant();
            case "editor.cape" -> updateEditor(editor -> editor.cycleCape(reverse ? -1 : 1));
            case "editor.preview_mode" -> {
                updateEditor(editor -> editor.cyclePreviewMode(reverse ? -1 : 1));
                if (state.editor != null) {
                    preferredCapeMode = state.editor.preview().capeMode();
                    if (preferredCapeMode != PreviewRenderer.CapeMode.OFF) {
                        PreviewPreferences.setCapeMode(preferredCapeMode);
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

    private void dispatchPresetWidget(String widgetId) {
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
            case "delete" -> requestPresetDeletion(presetId);
            case "delete_confirm" -> deletePreset(presetId);
            case "delete_cancel" -> cancelPresetDeletion(presetId);
            default -> {

            }
        }
    }

    private void openAddSource() {
        if (state.busy || state.account == null) {
            return;
        }
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
        UiMessage previousStatus = state.status;
        submit(
                UiMessage.info("nclskins.status.loading"),
                () -> {
                    AccountUiPreferences latest = cachedPreferences;
                    try {
                        latest = operations.loadUiPreferences()
                                .filter(preferences -> preferences.accountId().equals(accountId))
                                .orElse(cachedPreferences);
                    } catch (Exception ignored) {

                    }
                    return new AddSourceData(latest, operations.catalogCollections());
                },
                data -> {
                    state.uiPreferences = data.preferences();
                    state.addSource = AddSourceModel.open(
                            data.preferences(), data.collections(), fallbackVariant, textResolver);
                    resetAddSourceScroll();
                    state.selectedPresetId = null;
                    state.status = previousStatus;
                },
                failure -> {
                    state.addSource = AddSourceModel.open(
                            cachedPreferences, List.of(), fallbackVariant, textResolver);
                    resetAddSourceScroll();
                    state.selectedPresetId = null;
                });
    }

    private void selectAddSourceTab(AddSourceTab tab) {
        if (state.busy
                || state.addSource == null
                || state.addSource.personalSkinDeletion().isPresent()
                || state.addSource.selectedTab() == tab) {
            return;
        }
        state.addSource = state.addSource.withSelectedTab(tab);
        if (state.uiPreferences != null) {
            state.uiPreferences = state.uiPreferences.withSelectedAddSourceTab(tab);
        }
        publish();
        persistUiPreference(() -> {
            operations.setSelectedAddSourceTab(tab);
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
                || state.addSource.personalSkinDeletion().isPresent()
                || state.addSource.selectedTab() != AddSourceTab.CATALOG
                || state.addSource.collections().stream()
                        .noneMatch(collection -> collection.id().equals(collectionId))) {
            return;
        }
        boolean collapsed = !state.addSource.collectionCollapsed(collectionId);
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
                                    Optional.ofNullable(state.remoteProfile),
                                    state.ownedCapes.capes(),
                                    viewportHeight,
                                    preferredCapeMode)
                            : PresetEditorModel.openPersonalCatalog(
                                    name,
                                    selection.reusableVariants(),
                                    selection.initialVariant(),
                                    Optional.ofNullable(state.remoteProfile),
                                    state.ownedCapes.capes(),
                                    viewportHeight,
                                    preferredCapeMode);
                    resetEditorCapeScroll();
                    state.selectedPresetId = null;
                    state.status = UiMessage.info("nclskins.status.png_ready");
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
                                state.status = UiMessage.error("nclskins.error.png");
                            } else {
                                String sourceName = path.getFileName().toString();
                                openImportedDraft(
                                        new ClientOperations.ImportDraft(
                                                sourceName,
                                                skin.detectedVariant(),
                                                skin.pngBytes(),
                                                PersonalSkinSource.FILE),
                                        sourceName,
                                        false);
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
    }

    private void failExternalPreparation(ExternalImportSource source, Throwable failure) {
        if (state.externalImport == null) {
            return;
        }
        Throwable cause = unwrap(failure);
        if (cause instanceof ExternalImportException external
                && external.code() == ExternalImportException.Code.NO_VALID_APPEARANCES) {
            state.status = UiMessage.error("nclskins.external_import.no_valid");
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
                    if (openImportedDraft(draft, draft.name() + ".png", true)) {
                        state.status = UiMessage.success("nclskins.status.png_ready");
                    }
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
        resetEditorCapeScroll();
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
            state.busy = false;
            state.status = UiMessage.error("nclskins.error.picker");
            publish();
        });
    }

    private void requestPersonalSkinDeletion(String collectionId, String sha256) {
        if (state.busy
                || state.addSource == null
                || state.addSource.selectedTab() != AddSourceTab.CATALOG
                || state.addSource.personalSkinDeletion().isPresent()) {
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
        state.addSource = state.addSource.requestPersonalSkinDeletion(collection, skin);
        draggingAddSourceScrollbar = false;
        state.status = UiMessage.info("nclskins.your_skins.delete_note");
        publish();
    }

    private void requestPersonalSkinRename(String collectionId, String sha256) {
        if (state.busy || state.addSource == null || state.personalRenameHash != null) {
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

    private void cancelPersonalSkinDeletion() {
        if (state.busy
                || state.addSource == null
                || state.addSource.personalSkinDeletion().isEmpty()) {
            return;
        }
        state.addSource = state.addSource.cancelPersonalSkinDeletion();
        state.status = UiMessage.info("nclskins.status.cancelled");
        publish();
    }

    private void confirmPersonalSkinDeletion() {
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
                        state.addSource = state.addSource.removeConfirmedPersonalSkin();
                        int normalized = addSourcePresenter.normalizedScrollOffset(
                                state.addSource,
                                viewportWidth,
                                viewportHeight,
                                state.addSource.scrollOffset());
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
            if (state.addSource.personalSkinDeletion().isPresent()) {
                if (state.busy) {
                    return;
                }
                state.addSource = state.addSource.cancelPersonalSkinDeletion();
                publish();
                return;
            }
            if (state.busy && state.addSource.selectedTab() != AddSourceTab.FILE) {
                return;
            }
            if (state.busy) {
                state.generation++;
                state.busy = false;
                state.status = UiMessage.info("nclskins.status.cancelled");
            }
            state.addSource = null;
            draggingAddSourceScrollbar = false;
            resetAddSourceScroll();
            publish();
        }
    }

    private void persistUiPreference(ThrowingSupplier<Void> operation) {
        CompletableFuture.runAsync(() -> {
            try {
                operation.get();
            } catch (Exception ignored) {

            }
        }, worker);
    }

    private void openEditor(UUID presetId) {
        if (state.busy || state.account == null) {
            return;
        }
        PresetEditorModel editor = createEditor(presetId);
        if (editor == null) {
            state.status = UiMessage.error("nclskins.gallery.prepare_failed");
            publish();
            return;
        }
        state.addSource = null;
        state.editor = editor;
        resetEditorCapeScroll();
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
                    Optional.ofNullable(state.remoteProfile),
                    Optional.ofNullable(state.activePresetId),
                    textResolver,
                    viewportHeight,
                    preferredCapeMode,
                    preferredSkinVariant(),
                    state.ownedCapes == null ? List.of() : state.ownedCapes.capes());
        } catch (IllegalStateException missingBundledSkin) {
            return null;
        }
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
                                state.editor = state.editor.withStatus(UiMessage.error("nclskins.error.png"));
                            } else {
                                state.editor = state.editor.withImportedPng(
                                        path.getFileName().toString(),
                                        skin.pngBytes(),
                                        skin.detectedVariant());
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
        PersonalSkinSource personalSource = state.editorPersonalSource;
        state.editor = draft.withBusy(UiMessage.info("nclskins.status.saving"));
        submit(
                UiMessage.info("nclskins.status.saving"),
                () -> {
                    ClientOperations.EditorSaveRequest request = draft.saveRequest();
                    return operations.saveEditor(new ClientOperations.EditorSaveRequest(
                            request.originalPresetId(), request.name(), request.skin(),
                            request.initialVariant(), request.variant(), request.capeId(),
                            request.outerLayerVisibility(), request.pngBytes(), request.catalogOrigin(), request.personalSkinName(),
                            personalSource));
                },
                saved -> {
                    UUID previousActivePresetId = state.activePresetId;
                    state.account = saved.account();
                    state.selectedPresetId = saved.presetId();
                    AppearancePreset preset = findPreset(saved.presetId());
                    state.selectedSkinId = preset == null ? null : preset.skin().assetId();
                    state.selectedCapeId = preset == null ? null : preset.capeId();
                    state.editor = null;
                    state.editorPersonalSource = PersonalSkinSource.FILE;
                    state.addSource = null;
                    state.pendingPresetName = null;
                    state.status = UiMessage.success("nclskins.status.saved");
                    saved.reappliedAppearance().ifPresent(appearance -> {
                        state.activePresetId = appearance.activePresetId().orElse(null);
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
            resetEditorCapeScroll();
            publish();
        }
    }

    private PresetEditorModel applyPendingPresetName(PresetEditorModel editor) {
        return state.pendingPresetName == null ? editor : editor.withName(state.pendingPresetName);
    }

    private void requestPresetDeletion(UUID presetId) {
        if (state.busy || findPreset(presetId) == null) {
            return;
        }
        state.pendingPresetDeleteId = presetId;
        publish();
    }

    private void cancelPresetDeletion(UUID presetId) {
        if (!presetId.equals(state.pendingPresetDeleteId) || state.busy) {
            return;
        }
        state.pendingPresetDeleteId = null;
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
                    Optional.ofNullable(state.remoteProfile),
                    Optional.ofNullable(state.activePresetId),
                    textResolver,
                    viewportHeight,
                    preferredCapeMode,
                    preferredSkinVariant(),
                    state.ownedCapes == null ? List.of() : state.ownedCapes.capes());
            resetEditorCapeScroll();
            state.selectedPresetId = presetId;
            publish();
        } catch (IllegalStateException missingBundledSkin) {
            state.status = UiMessage.error("nclskins.gallery.prepare_failed");
            publish();
        }
    }

    private void deletePreset(UUID presetId) {
        if (!presetId.equals(state.pendingPresetDeleteId)) {
            return;
        }
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
            state.rateLimited = operations.rateLimited();
            state.status = UiMessage.info("nclskins.status.local_only");
            if (automaticCheckpointEligible(use.syncStatus())) {
                reconcileAfterLocalRebind(
                        localRebind,
                        ClientOperations.ReconciliationTrigger.LOCAL_INTENT);
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
                state.account.accountId(), state.intentRevision));
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
                (ignored, failure) -> onClient(() ->
                        requestAppearanceReconciliation(key, trigger)));
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
            } catch (Throwable unavailable) {
                failure = unavailable;
                if (durableSettlementMayHaveAdvanced(unavailable)) {
                    try {
                        durableAfterFailure = operations.durableAppearance()
                                .filter(appearance -> appearance.accountId()
                                        .equals(request.key().accountId()));
                    } catch (Throwable unavailableDurableState) {


                    }
                }
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
                && confirmedRemoteChange
                && (failure != null || exactAccountResult)) {
            serverAppearanceReadiness.ifPresent(coordinator -> {
                try {
                    coordinator.start();
                } catch (RuntimeException ignored) {

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
            requestAppearanceReconciliation(
                    ClientOperations.ReconciliationTrigger.EXPLICIT_RETRY);
        }
    }

    private void retrySession() {
        if (!canRetrySession()) {
            return;
        }
        long ticket = ++state.generation;
        state.busy = true;
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
                }, worker)
                .whenComplete((result, failure) -> onClient(() ->
                        stageSessionRetrySettlement(ticket, result, failure)));
    }

    private boolean canRetrySession() {
        if (disposed
                || state.busy
                || state.syncInProgress
                || state.lifecycle == ClientSnapshot.Lifecycle.CLOSED
                || state.account == null) {
            return false;
        }
        if (state.rateLimited || operations.rateLimited()) {
            if (!state.rateLimited) {
                state.rateLimited = true;
                publish();
            }
            return false;
        }
        boolean offline = state.session == null || !state.session.valid();
        return offline
                || snapshot.recoveryActions().contains(RecoveryAction.REFRESH_REMOTE_PROFILE);
    }

    private void stageSessionRetrySettlement(
            long ticket, ClientOperations.InitialData result, Throwable failure) {
        if (!current(ticket)) {
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
                || !state.busy
                || !state.status.equals(UiMessage.info("nclskins.status.checking_session"))) {
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
        if (!current(settlement.ticket())) {
            clearSessionRetryFeedback(settlement.ticket());
            return;
        }
        state.busy = false;
        if (settlement.failure() != null) {
            state.lifecycle = state.lifecycle == ClientSnapshot.Lifecycle.INITIALIZING
                    ? ClientSnapshot.Lifecycle.READY
                    : state.lifecycle;
            state.rateLimited = operations.rateLimited();
            state.status = operationFailure(settlement.failure());
        } else {
            CompletableFuture<AppearanceRefreshCoordinator.Result> localRebind =
                    acceptInitialData(settlement.result(), false);
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
        sessionRetryTicket = -1L;
        sessionRetryFeedbackRendered = false;
        sessionRetryFeedbackTicksRemaining = 0;
        pendingSessionRetrySettlement = null;
    }

    private void clearSessionRetryFeedback() {
        sessionRetryTicket = -1L;
        sessionRetryFeedbackRendered = false;
        sessionRetryFeedbackTicksRemaining = 0;
        pendingSessionRetrySettlement = null;
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
        state.rateLimited = operations.rateLimited();
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
            ViewSpec.Preview current = state.editor.present(viewportWidth, viewportHeight)
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
            ViewSpec.Preview current = state.editor.present(viewportWidth, viewportHeight)
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
        setGalleryPosition(state.galleryScrollPosition
                + galleryPresenter.scrollPositionDelta(
                viewportWidth, viewportHeight, pixelDelta));
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
        double centered = galleryPresenter.centeredScrollPosition(
                Optional.ofNullable(state.account),
                Optional.ofNullable(state.activePresetId),
                state.galleryQuery);
        state.galleryOffset = (int) Math.round(centered);
        state.galleryScrollPosition = centered;
        state.galleryScrollTarget = centered;
    }

    private ViewSpec galleryView(
            int width, int height, int mouseX, int mouseY) {
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
                state.galleryScrollPosition);
    }

    private int galleryMaximum() {
        return galleryPresenter.maximumScroll(
                snapshot, viewportWidth, viewportHeight, state.galleryQuery);
    }

    private void queueEditorCapeScroll(double pixelDelta) {
        if (state.editor == null || !Double.isFinite(pixelDelta) || pixelDelta == 0.0) {
            return;
        }
        setEditorCapePosition(editorCapeScrollPosition + pixelDelta);
    }

    private void setEditorCapePosition(double position) {
        if (state.editor == null) {
            return;
        }
        double bounded = state.editor.normalizedCapeScrollPosition(
                viewportWidth, viewportHeight, position);
        if (Math.abs(bounded - editorCapeScrollPosition) > 0.001
                || Math.abs(bounded - editorCapeScrollTarget) > 0.001) {
            editorCapeScrollPosition = bounded;
            editorCapeScrollTarget = bounded;
            publish();
        }
    }

    private void resetEditorCapeScroll() {
        draggingEditorCapeScrollbar = false;
        editorCapeScrollPosition = state.editor == null
                ? 0.0
                : state.editor.initialCapeScrollPosition(viewportWidth, viewportHeight);
        editorCapeScrollTarget = editorCapeScrollPosition;
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

    private boolean clampEditorCapeScroll() {
        double position = state.editor.normalizedCapeScrollPosition(
                viewportWidth, viewportHeight, editorCapeScrollPosition);
        double target = state.editor.normalizedCapeScrollPosition(
                viewportWidth, viewportHeight, editorCapeScrollTarget);
        boolean changed = Math.abs(position - editorCapeScrollPosition) > 0.001
                || Math.abs(target - editorCapeScrollTarget) > 0.001;
        editorCapeScrollPosition = position;
        editorCapeScrollTarget = target;
        return changed;
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
                state.addSource, viewportWidth, viewportHeight, offset);
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
                state.addSource, viewportWidth, viewportHeight);
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
                state.addSource, viewportWidth, viewportHeight);
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

    private void toggleEditorVariant() {
        if (state.editor == null) {
            return;
        }
        SkinVariant before = state.editor.variant();
        state.editor = state.editor.toggleVariant();
        if (state.editor.variant() != before) {
            rememberPreferredSkinVariant(state.editor.variant());
        }
        publish();
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
                    if (refreshServer) {
                        serverAppearanceReadiness.ifPresent(coordinator -> {
                        try {
                            coordinator.start();
                        } catch (RuntimeException ignored) {

                        }
                        });
                    }
                    if (!current(ticket)) {
                        return;
                    }
                    state.busy = false;
                    if (failure != null) {
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
                state.galleryOffset,
                state.generation,
                state.intentRevision,
                state.syncStatus,
                state.syncInProgress);
        ClientSnapshot published = snapshot;
        listeners.forEach(listener -> listener.accept(published));
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

    private static NormalizedSkin readPng(Path path) {
        try {
            return new PngValidator().normalizeSkinWithVariant(path);
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
                    } catch (Exception failure) {
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
            case VALID -> "nclskins.session.message.valid";
            case EXPIRED -> "nclskins.session.message.expired";
            case OFFLINE_OR_INVALID -> offlineMessageKey(validation.failureKind());
            case UUID_MISMATCH -> "nclskins.session.message.uuid_mismatch";
            case NOT_ENTITLED -> "nclskins.session.message.not_entitled";
            case PROFILE_RESTRICTED -> "nclskins.session.message.restricted";
        };
        return validation.valid() ? UiMessage.success(key) : UiMessage.error(key);
    }

    private static String offlineMessageKey(ApiFailureKind failureKind) {
        if (failureKind == null) {
            return "nclskins.session.message.check_failed";
        }
        return switch (failureKind) {
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
            List<SkinCatalogSource.CollectionDescriptor> collections) {
        private AddSourceData {
            Objects.requireNonNull(preferences, "preferences");
            collections = List.copyOf(Objects.requireNonNull(collections, "collections"));
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
        private long intentRevision;
        private AppearanceSyncStatus syncStatus = AppearanceSyncStatus.LOCAL_ONLY;
        private boolean syncInProgress;
        private int galleryOffset;
        private double galleryScrollPosition;
        private double galleryScrollTarget;
        private String galleryQuery = "";
        private String pendingPresetName;
        private UUID pendingPresetDeleteId;
        private String personalRenameCollectionId;
        private String personalRenameHash;
        private String personalRenameValue = "";
        private long generation;
        private boolean readyData;

        private void resetForReopen() {
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
                intentRevision = 0;
                syncStatus = AppearanceSyncStatus.LOCAL_ONLY;
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
            syncInProgress = false;
            galleryOffset = 0;
            galleryScrollPosition = 0.0;
            galleryScrollTarget = 0.0;
            galleryQuery = "";
            pendingPresetName = null;
            pendingPresetDeleteId = null;
            personalRenameCollectionId = null;
            personalRenameHash = null;
            personalRenameValue = "";
        }
    }
}

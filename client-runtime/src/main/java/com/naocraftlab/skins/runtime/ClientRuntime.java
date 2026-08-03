package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SignedTextureVerifier;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.CatalogCollectionOrder;
import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.PreviewPreferences;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibilityController;
import com.naocraftlab.skins.client.ServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.MutationResult;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.LibraryOperationException;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.RecoveryAction;
import com.naocraftlab.skins.core.service.SessionStatus;
import com.naocraftlab.skins.core.service.SessionValidation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;


public final class ClientRuntime implements AutoCloseable {
    private static final double WHEEL_SCROLL_PIXELS = 32.0;

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
            PlayerAppearanceSink<AcknowledgedAppearanceAssets> sink) {
        return createDefaultWithDeterministicAppearance(
                tokenSource,
                bundledSkins,
                currentAppearanceSource,
                clientExecutor,
                filePicker,
                textResolver,
                signedTextureVerifier,
                sink,
                visibility -> {},
                ServerAppearanceRefreshNotifier.NO_OP);
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
            ServerAppearanceRefreshNotifier serverAppearanceRefreshNotifier) {
        return createDefaultWithDeterministicAppearance(
                tokenSource,
                bundledSkins,
                currentAppearanceSource,
                clientExecutor,
                filePicker,
                textResolver,
                signedTextureVerifier,
                sink,
                visibility -> {},
                serverAppearanceRefreshNotifier);
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
                publish();
            }
            initializeOnClient();
        });
    }


    public void closeScreen() {
        onClient(() -> {
            if (disposed || state.lifecycle == ClientSnapshot.Lifecycle.CLOSED) {
                return;
            }
            state.generation++;
            state.lifecycle = ClientSnapshot.Lifecycle.CLOSED;
            state.busy = false;
            state.editor = null;
            state.addSource = null;
            if (draggingGalleryScrollbar) {
                state.galleryScrollTarget = state.galleryScrollPosition;
            }
            draggingGalleryScrollbar = false;
            draggingEditorCapeScrollbar = false;
            draggingAddSourceScrollbar = false;
            addSourceScrollPosition = 0.0;
            addSourceScrollTarget = 0.0;
            publish();
        });
    }

    public void tick() {
        onClient(() -> {
            if (disposed || state.lifecycle == ClientSnapshot.Lifecycle.CLOSED) {
                return;
            }
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
                                    state.personalRenameHash, state.personalRenameValue)));
        }
        return galleryView(width, height, mouseX, mouseY);
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
                state.galleryQuery = value;
                resetGalleryScroll();
                state.pendingPresetDeleteId = null;
                publish();
            } else if ("editor.name".equals(widgetId) && state.editor != null) {
                state.editor = state.editor.withName(value);
                publish();
            } else if ("add.catalog.search".equals(widgetId)
                    && state.addSource != null
                    && state.addSource.personalSkinDeletion().isEmpty()) {
                state.addSource = state.addSource.withQuery(value);
                resetAddSourceScroll();
                publish();
            } else if ("add.catalog.rename.name".equals(widgetId)
                    && state.personalRenameHash != null) {
                state.personalRenameValue = value;
                publish();
            } else if ("add.player.input".equals(widgetId) && state.addSource != null) {
                state.addSource = state.addSource.withPlayerInput(value);
                publish();
            } else if ("add.url.input".equals(widgetId) && state.addSource != null) {
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
                            snapshot, viewportWidth, state.galleryQuery,
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
                        snapshot, viewportWidth, state.galleryQuery,
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
            return publishPreview(Optional.of(editor.png().orElseThrow().bytes()));
        }
        if (preview.catalogImage().isPresent()) {
            ViewSpec.CatalogImage image = preview.catalogImage().orElseThrow();
            SkinModel model = preview.variant() == SkinVariant.SLIM
                    ? SkinModel.SLIM
                    : SkinModel.CLASSIC;
            return requestPreview(
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
        }
        CompletableFuture<Optional<byte[]>> loaded = loadSkinPreview(preview.skin());
        if (preview.skin().optionalAssetId().isPresent()) {
            loaded.whenComplete((bytes, failure) -> {
                if (failure != null || bytes == null || bytes.isEmpty()) {
                    reportSkinPreviewFailure(preview);
                }
            });
        }
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
        state.lifecycle = ClientSnapshot.Lifecycle.READY;
        state.account = data.account();
        state.session = data.session();
        state.remoteProfile = data.session().profile();
        state.currentOfficialSkinId = data.currentOfficialSkinId().orElse(null);
        state.activePresetId = data.activePresetId().orElse(null);
        state.intentRevision = data.intentRevision();
        state.syncStatus = data.syncStatus();
        state.uiPreferences = data.uiPreferences();
        state.ownedCapes = data.ownedCapes();
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
            requestPersonalSkinDeletion(widgetId.substring("add.catalog.delete:".length()));
            return;
        }
        if (widgetId.startsWith("add.catalog.rename:")) {
            String hash = widgetId.substring("add.catalog.rename:".length());
            if (hash.matches("[0-9a-f]{64}")) {
                requestPersonalSkinRename(hash);
                return;
            }
        }
        if (widgetId.startsWith("add.catalog.skin:")) {
            selectCatalogSkin(widgetId.substring("add.catalog.skin:".length()));
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
            case "add.player.load" -> loadRemoteImport(true);
            case "add.url.load" -> loadRemoteImport(false);
            case "add.catalog.filter" -> cycleCatalogFilter();
            case "add.catalog.delete.confirm" -> confirmPersonalSkinDeletion();
            case "add.catalog.delete.cancel" -> cancelPersonalSkinDeletion();
            case "add.catalog.rename.save" -> savePersonalSkinRename();
            case "add.catalog.rename.cancel" -> cancelPersonalSkinRename();
            case "add.cancel" -> cancelAddSource();
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

    private void cycleCatalogFilter() {
        if (state.busy
                || state.addSource == null
                || state.addSource.personalSkinDeletion().isPresent()
                || state.addSource.selectedTab() != AddSourceTab.CATALOG) {
            return;
        }
        state.addSource = state.addSource.cycleFilter();
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
                                resolvedCatalogText(skin.authorsText().isPresent()
                                        ? skin.authorsText()
                                        : collection.authorsText()))),
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
                        .whenComplete((png, pngFailure) -> onClient(() -> {
                            if (!current(ticket) || state.addSource == null) {
                                return;
                            }
                            state.busy = false;
                            if (pngFailure != null) {
                                state.status = UiMessage.error("nclskins.error.png");
                            } else {
                                PresetEditorModel editor = createEditor(null);
                                if (editor == null) {
                                    state.status = UiMessage.error("nclskins.gallery.prepare_failed");
                                } else {
                                    state.editor = applyPendingPresetName(
                                            editor.withPng(path.getFileName().toString(), png));
                                    resetEditorCapeScroll();
                                    state.editorPersonalSource = PersonalSkinSource.FILE;
                                    state.selectedPresetId = null;
                                }
                            }
                            publish();
                        }));
            }
        }));
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
                    PresetEditorModel editor = createEditor(null);
                    if (editor == null) {
                        state.status = UiMessage.error("nclskins.gallery.prepare_failed");
                        return;
                    }
                    if (editor.variant() != draft.variant()) {
                        editor = editor.toggleVariant();
                    }
                    state.editor = applyPendingPresetName(
                            editor.withName(draft.name()).withPng(draft.name() + ".png", draft.pngBytes()));
                    resetEditorCapeScroll();
                    state.editorPersonalSource = draft.source();
                    state.selectedPresetId = null;
                    state.status = UiMessage.success("nclskins.status.png_ready");
                },
                failure -> state.status = UiMessage.error(player
                        ? "nclskins.add_source.player_failed"
                        : "nclskins.add_source.url_failed"));
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

    private void requestPersonalSkinDeletion(String sha256) {
        if (state.busy
                || state.addSource == null
                || state.addSource.selectedTab() != AddSourceTab.CATALOG
                || state.addSource.personalSkinDeletion().isPresent()) {
            return;
        }
        SkinCatalogSource.CollectionDescriptor collection = state.addSource.collections().stream()
                .filter(value -> value.order().kind() == CatalogCollectionOrder.Kind.PERSONAL)
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

    private void requestPersonalSkinRename(String sha256) {
        if (state.busy || state.addSource == null || state.personalRenameHash != null) {
            return;
        }
        SkinCatalogSource.SkinDescriptor skin = state.addSource.collections().stream()
                .filter(collection -> collection.order().kind() == CatalogCollectionOrder.Kind.PERSONAL)
                .flatMap(collection -> collection.skins().stream())
                .filter(candidate -> candidate.id().equals(sha256))
                .findFirst()
                .orElse(null);
        if (skin == null) {
            return;
        }
        state.personalRenameHash = sha256;
        state.personalRenameValue = state.addSource.skinName(skin);
        publish();
    }

    private void cancelPersonalSkinRename() {
        if (state.busy || state.personalRenameHash == null) {
            return;
        }
        state.personalRenameHash = null;
        state.personalRenameValue = "";
        publish();
    }

    private void savePersonalSkinRename() {
        if (state.busy || state.addSource == null || state.personalRenameHash == null) {
            return;
        }
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
                        state.addSource = state.addSource.renamedPersonalSkin(hash, name);
                    }
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
                        .whenComplete((png, pngFailure) -> onClient(() -> {
                            if (!current(ticket) || state.editor == null) {
                                return;
                            }
                            state.busy = false;
                            if (pngFailure != null) {
                                state.editor = state.editor.withStatus(UiMessage.error("nclskins.error.png"));
                            } else {
                                state.editor = state.editor.withPng(
                                        path.getFileName().toString(), png);
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
        if (source == null) {
            return;
        }
        int preservedOffset = state.galleryOffset;
        Set<UUID> before = ids(state.account == null ? List.of() : state.account.presets());
        String name = textResolver.resolve(UiMessage.info("nclskins.gallery.copy_name", source.name())).trim();
        if (name.length() > 128) {
            name = name.substring(0, 128).trim();
        }
        String copyName = name.isEmpty() ? source.name() : name;
        submit(
                UiMessage.info("nclskins.status.duplicating"),
                () -> operations.duplicatePreset(presetId, copyName),
                account -> {
                    state.account = account;
                    state.galleryOffset = preservedOffset;
                    UUID duplicateId = account.presets().stream()
                            .map(AppearancePreset::id)
                            .filter(id -> !before.contains(id))
                            .findFirst()
                            .orElse(null);
                    state.selectedPresetId = duplicateId;
                    state.status = UiMessage.success("nclskins.status.duplicated");
                    if (duplicateId != null) {
                        openEditor(duplicateId);
                    }
                });
    }

    private void deletePreset(UUID presetId) {
        if (!presetId.equals(state.pendingPresetDeleteId)) {
            return;
        }
        submit(
                UiMessage.info("nclskins.status.deleting"),
                () -> operations.deletePreset(presetId),
                deletion -> {
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
        if (!preserveGalleryOffset && !use.activePresetId().equals(previous)) {
            resetGalleryScroll();
        }
        if (use.remoteResult().isPresent()) {
            acceptRemoteResult(
                    use.remoteResult().orElseThrow(),
                    use.activePresetId(),
                    true);
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
        state.activePresetId = appearance.activePresetId().orElse(null);
        state.intentRevision = appearance.intentRevision();
        state.syncStatus = appearance.syncStatus();
        appearance.localAppearance().ifPresent(this::refreshLocalAppearance);
        appearance.outerLayerVisibility().ifPresent(this::applyDurableOuterLayerVisibility);
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
        submit(
                UiMessage.info("nclskins.status.checking_session"),
                operations::retrySession,
                data -> {
                    reconcileAfterLocalRebind(
                            acceptInitialData(data, false),
                            ClientOperations.ReconciliationTrigger.EXPLICIT_RETRY);
                });
    }

    private void acceptRemoteResult(
            ClientOperations.RemoteResult result, UUID presetToActivate, boolean preserveGalleryOffset) {
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
                UUID previous = state.activePresetId;
                state.activePresetId = presetToActivate;
                if (!preserveGalleryOffset && !presetToActivate.equals(previous)) {
                    resetGalleryScroll();
                }
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
                + galleryPresenter.scrollPositionDelta(viewportWidth, pixelDelta));
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
        return galleryPresenter.maximumScroll(snapshot, viewportWidth, state.galleryQuery);
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

    private static byte[] readPng(Path path) {
        try {
            return new PngValidator().normalizeSkin(path);
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

    private record ReconciliationRequest(
            ClientOperations.ReconciliationKey key,
            ClientOperations.ReconciliationTrigger trigger) {
        private ReconciliationRequest {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(trigger, "trigger");
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
        private String personalRenameHash;
        private String personalRenameValue = "";
        private long generation;

        private void resetForReopen() {
            generation++;
            lifecycle = ClientSnapshot.Lifecycle.NEW;
            account = null;
            session = null;
            remoteProfile = null;
            lastMutation = null;
            selectedSkinId = null;
            selectedPresetId = null;
            selectedCapeId = null;
            currentOfficialSkinId = null;
            activePresetId = null;
            editor = null;
            editorPersonalSource = PersonalSkinSource.FILE;
            addSource = null;
            uiPreferences = null;
            ownedCapes = null;
            status = UiMessage.info("nclskins.status.loading");
            busy = false;
            rateLimited = false;
            intentRevision = 0;
            syncStatus = AppearanceSyncStatus.LOCAL_ONLY;
            syncInProgress = false;
            galleryOffset = 0;
            galleryScrollPosition = 0.0;
            galleryScrollTarget = 0.0;
            galleryQuery = "";
            pendingPresetName = null;
            pendingPresetDeleteId = null;
            personalRenameHash = null;
            personalRenameValue = "";
        }
    }
}

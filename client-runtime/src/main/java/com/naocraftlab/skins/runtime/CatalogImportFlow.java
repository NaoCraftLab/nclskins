package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CatalogCollectionOrder;
import com.naocraftlab.skins.client.CpmPublicationName;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.CpmModelFileConversion;
import com.naocraftlab.skins.client.CpmModelPublication;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment;
import com.naocraftlab.skins.core.importing.ExternalImportProbe;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.NormalizedSkin;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

final class CatalogImportFlow {
    private final State state = new State();
    private final Context context;
    private final AddSourcePresenter addSourcePresenter = new AddSourcePresenter();
    private final ExternalImportPresenter externalImportPresenter = new ExternalImportPresenter();
    private boolean draggingAddSourceScrollbar;
    private double addSourceScrollbarGrabOffset;
    private double addSourceScrollPosition;
    private double addSourceScrollTarget;
    private long catalogDisclosureRevision;

    CatalogImportFlow(Context context) { this.context = Objects.requireNonNull(context, "context"); }

    void selectAddSourceTab(AddSourceTab tab) {
        if (state.dependency != null) return;
        if (context.busy()
                || state.addSource == null
                || state.addSource.selectedTab() == tab) {
            return;
        }
        resetPersonalCatalogInteraction();
        state.addSource = state.addSource.withSelectedTab(tab);
        if (state.model != null) {
            context.publish();
            return;
        }
        if (tab == AddSourceTab.FILE) {
            context.showFeedback(UiMessage.info("nclskins.external_import.choose_source"));
        }
        if (context.uiPreferences() != null) {
            context.addSourceTabSelected(tab);
        }
        context.publish();
        UUID accountId = context.account().accountId();
        context.persistUiPreference(() -> {
            context.uiPreferencesPort().setSelectedAddSourceTab(accountId, tab);
            return null;
        });
    }

    void cycleCatalogFilter(boolean reverse) {
        if (state.model != null || context.busy()
                || state.addSource == null
                || state.addSource.personalSkinDeletion().isPresent()
                || state.addSource.selectedTab() != AddSourceTab.CATALOG) {
            return;
        }
        state.addSource = state.addSource.cycleFilter(reverse);
        resetAddSourceScroll();
        if (state.addSource.filter() != AddSourceModel.CatalogFilter.ALL) {
            context.rememberPreferredSkinVariant(state.addSource.preferredVariant());
        }
        context.publish();
    }

    void toggleCatalogCollection(String collectionId) {
        if (context.busy()
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
        if (context.uiPreferences() != null) {
            context.catalogCollectionDisclosureChanged(collectionId, collapsed);
        }
        setAddSourceOffset(state.addSource.scrollOffset());
        context.publish();
        context.persistUiPreference(() -> {
            context.uiPreferencesPort().setCollectionCollapsed(collectionId, collapsed);
            return null;
        });
    }

    void toggleAllCatalogCollections(
            InteractionOrigin origin, String widgetId) {
        if (context.busy()
                || state.addSource == null
                || state.addSource.selectedTab() != AddSourceTab.CATALOG
                || state.addSource.availableCollectionIds().isEmpty()) {
            return;
        }
        boolean collapsed = !state.addSource.anyAvailableCollectionCollapsed();
        AddSourceModel previousModel = state.addSource;
        AccountUiPreferences previousPreferences = context.uiPreferences();
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
        if (context.uiPreferences() != null) {
            context.catalogDisclosureChanged(replacement);
        }
        addSourceScrollPosition = state.addSource.scrollOffset();
        addSourceScrollTarget = state.addSource.scrollOffset();
        if (origin.keyboard()) {
            context.requestRuntimeFocus("add_source", widgetId);
        }
        context.publish();
        long revision = ++catalogDisclosureRevision;
        ScreenOperationTicket ticket = context.captureScreenOperation();
        CompletableFuture.runAsync(() -> {
                    try {
                        context.uiPreferencesPort().replaceCollapsedCollectionIds(replacement);
                    } catch (Exception failure) {
                        throw new CompletionException(failure);
                    }
                }, context.worker())
                .whenComplete((ignored, failure) -> context.onClient(() -> {
                    if (failure == null
                            || context.disposed()
                            || revision != catalogDisclosureRevision
                            || !context.current(ticket)) {
                        return;
                    }
                    context.diagnose(DiagnosticEvent.CLIENT_ASYNC_OPERATION_FAILED, failure);
                    if (state.addSource == null
                            || !state.addSource.collapsedCollectionIds().equals(replacement)) {
                        return;
                    }
                    state.addSource = previousModel;
                    context.catalogDisclosureChanged(previousPreferences == null ? null : previousPreferences.collapsedCollectionIds());
                    addSourceScrollPosition = previousPosition;
                    addSourceScrollTarget = previousTarget;
                    state.personalRenameCollectionId = previousRenameCollectionId;
                    state.personalRenameHash = previousRenameHash;
                    state.personalRenameValue = previousRenameValue;
                    context.showFeedback(UiMessage.error("nclskins.add_source.disclosure_failed"));
                    context.publish();
                }));
    }

    void selectCatalogSkin(String encodedId) {
        if (state.dependency != null) return;
        if (context.busy()
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
        SkinCatalogSource.SkinDescriptor skin = (state.model == null
                ? collection.skins() : state.addSource.visibleSkins(collection)).stream()
                .filter(value -> value.id().equals(skinId))
                .findFirst()
                .orElse(null);
        if (skin == null || !state.addSource.visibleSkins(collection).contains(skin)) {
            return;
        }
        SkinVariant initialVariant = state.addSource.selectedVariant(skin);
        if (state.model != null) {
            SkinModel required = state.model.requiredVariant().orElse(SkinModel.valueOf(initialVariant.name()));
            SkinCatalogSource.SkinDescriptor selectable = state.addSource.visibleSkins(collection).stream()
                    .filter(value -> value.id().equals(skinId)).findFirst().orElse(null);
            if (selectable == null || !selectable.models().contains(required)
                    || !initialVariant.name().equals(required.name())) return;
            ScreenOperationTicket ticket = context.beginScreenOperation();
            context.publish();
            convertBase(ticket, () -> {
                Optional<CatalogMaterialization.FrozenCatalogSelection> before = context.catalogMaterialization()
                        .freezeCatalogSelection(collection.id(), skin.id());
                byte[] png = context.catalogMaterialization().loadCatalogSkin(collection.id(), skin.id(), required);
                Optional<CatalogMaterialization.FrozenCatalogSelection> after = context.catalogMaterialization()
                        .freezeCatalogSelection(collection.id(), skin.id());
                if (!before.equals(after)) throw new IOException("Catalog selection changed");
                return new ImportOperations.ImportDraft("", initialVariant, png, PersonalSkinSource.FILE);
            });
            return;
        }
        context.submit(
                UiMessage.info("nclskins.add_source.loading"),
                () -> loadCatalogSelection(collection, skin, initialVariant),
                selection -> {
                    String name = context.pendingPresetName() != null
                            ? context.pendingPresetName()
                            : context.textResolver().resolve(selection.skin().nameText());
                    PresetEditorModel draft = selection.reusableVariants().isEmpty()
                            ? PresetEditorModel.openCatalog(
                                    name,
                                    selection.origin().orElseThrow(),
                                    selection.variants(),
                                    selection.initialVariant(),
                                    context.editorProfile(),
                                    context.editorOwnedCapes(),
                                    context.viewportHeight(),
                                    context.preferredCapeMode())
                            : PresetEditorModel.openPersonalCatalog(
                                    name,
                                    selection.reusableVariants(),
                                    selection.initialVariant(),
                                    context.editorProfile(),
                                    context.editorOwnedCapes(),
                                    context.viewportHeight(),
                                    context.preferredCapeMode());
                    context.acceptDraft(new EditorDraftTransfer(draft.withFrozenCatalogSelection(selection.frozen()),
                            null, Optional.empty(), null));
                },
                failure -> context.showFeedback(UiMessage.error("nclskins.add_source.load_failed")));
    }

    CatalogSelection loadCatalogSelection(
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
                byte[] png = context.catalogMaterialization().loadCatalogSkin(collection.id(), skin.id(), model);
                variants.put(variant, png);
                Optional<UUID> reusable = context.catalogMaterialization().reusableCatalogSkinAsset(
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
                resolvedInitial, context.catalogMaterialization().freezeCatalogSelection(collection.id(), skin.id()));
    }

    Optional<String> resolvedCatalogText(
            Optional<com.naocraftlab.skins.client.CatalogText> value) {
        return value.map(context.textResolver()::resolve)
                .map(String::trim)
                .filter(text -> !text.isBlank());
    }

    private volatile com.naocraftlab.skins.client.CpmModelPublication.Request publicationRequest;
    private volatile CompletableFuture<com.naocraftlab.skins.client.CpmModelPublication.Result> publicationResult;
    private volatile ImportScope publicationScope;
    private PublicationAttempt activeAttempt;
    private java.util.function.LongSupplier nanoTime = System::nanoTime;

    void clock(java.util.function.LongSupplier clock) { nanoTime = Objects.requireNonNull(clock); }

    boolean publicationBusy() { return activeAttempt != null; }

    void servicePublicationDeadline() {
        PublicationAttempt attempt = activeAttempt;
        if (attempt == null || attempt.remainingNanos() > 0) return;
        CpmModelPublication.Result result;
        synchronized (attempt) {
            result = attempt.send == null ? CpmModelPublication.Result.stopped(CpmModelPublication.Reason.EXPIRED)
                    : attempt.send.stop(CpmModelPublication.Reason.EXPIRED);
            attempt.retired = true;
        }
        failPublicationAttempt(attempt, result.status() == CpmModelPublication.Status.UNCERTAIN
                ? publicationFailure(result) : UiMessage.error("nclskins.add.cpm_publication_timeout"));
    }

    private void failPublicationAttempt(PublicationAttempt attempt, UiMessage failure) {
        failPublicationAttempt(attempt, failure, Optional.empty());
    }

    private void failPublicationAttempt(PublicationAttempt attempt, UiMessage failure,
            Optional<CpmModelFileConversion.Reason> reason) {
        if (activeAttempt != attempt) return;
        activeAttempt = null;
        CpmModelPublication.Result terminal;
        synchronized (attempt) {
            attempt.retired = true;
            terminal = attempt.send == null ? CpmModelPublication.Result.stopped(CpmModelPublication.Reason.STALE)
                    : attempt.send.stop(attempt.remainingNanos() == 0
                            ? CpmModelPublication.Reason.EXPIRED : CpmModelPublication.Reason.STALE);
        }
        context.cancelScreenOperation();
        if (state.modelParent != null) {
            ParentFrame parent = state.modelParent;
            AddSourceModel restored = parent.model().refreshed(state.addSource);
            retireModel();
            state.addSource = restored;
            addSourceScrollPosition = parent.scrollPosition();
            addSourceScrollTarget = parent.scrollTarget();
        }
        if (reason.isPresent() && state.addSource != null) {
            state.addSource = state.addSource.withImportContext(new AddSourceModel.ImportContext(
                    Optional.empty(), reason, state.addSource.importContext().purpose()));
        }
        context.showFeedback(failure);
        context.publish();
        if (attempt.send != null) attempt.send.uiResult.complete(terminal);
    }

    private void completeAttempt() {
        PublicationAttempt attempt = activeAttempt;
        activeAttempt = null;
        if (attempt != null) synchronized (attempt) { attempt.retired = true; }
    }

    private final class PublicationAttempt {
        private final long started = nanoTime.getAsLong();
        private final ImportScope scope;
        private volatile boolean retired;
        private volatile PublicationSend send;
        private PublicationAttempt(ImportScope scope) { this.scope = scope; }
        public long remainingNanos() { return Math.max(0, java.time.Duration.ofSeconds(60).toNanos() - (nanoTime.getAsLong() - started)); }
        public boolean getAsBoolean() { return !retired && !context.disposed() && remainingNanos() > 0
                && context.currentPublicationScope(scope); }
    }

    private final class PublicationSend implements CpmModelPublication.Control {
        private final PublicationAttempt attempt;
        private final PublicationSettlement settlement;
        private final CompletableFuture<CpmModelPublication.Result> uiResult;
        private boolean possibleSend;
        private PublicationSend(PublicationAttempt attempt, PublicationSettlement settlement,
                CompletableFuture<CpmModelPublication.Result> uiResult) {
            this.attempt = attempt;
            this.settlement = settlement;
            this.uiResult = uiResult;
        }
        public long remainingNanos() { return attempt.remainingNanos(); }
        public boolean getAsBoolean() { return attempt.send == this && attempt.getAsBoolean(); }
        public boolean beginSend() {
            synchronized (attempt) {
                if (settlement.value != null || !getAsBoolean()) return false;
                possibleSend = true;
                return true;
            }
        }
        private CpmModelPublication.Result stop(CpmModelPublication.Reason reason) {
            if (settlement.value == null) settlement.value = possibleSend
                    ? new CpmModelPublication.Result(CpmModelPublication.Status.UNCERTAIN, reason, Optional.empty())
                    : CpmModelPublication.Result.stopped(reason);
            return settlement.value;
        }
    }

    CompletableFuture<com.naocraftlab.skins.client.CpmModelPublication.Result> publishCpmModel(
            com.naocraftlab.skins.client.CpmModelPublication adapter,
            com.naocraftlab.skins.client.CpmModelPublication.Request request, boolean consent) {
        var reason = com.naocraftlab.skins.client.CpmModelPublication.Reason.NO_CONSENT;
        if (!consent) {
            cancelCpmPublication();
            return CompletableFuture.completedFuture(com.naocraftlab.skins.client.CpmModelPublication.Result.stopped(reason));
        }
        var prior = publicationResult;
        if (publicationRequest == request && prior != null && context.currentPublicationScope(publicationScope)) return prior;
        for (PublicationSettlement settlement : state.settlements) {
            if (!java.util.Arrays.equals(settlement.request.wire(), request.wire())) continue;
            if (!context.currentPublicationScope(settlement.scope))
                return CompletableFuture.completedFuture(CpmModelPublication.Result.stopped(CpmModelPublication.Reason.STALE));
            var settled = settlement.value;
            if (settled != null && settled.status() != CpmModelPublication.Status.NOT_SENT)
                return CompletableFuture.completedFuture(settled);
            if (settled == null) return settlement.result;
        }
        if (publicationRequest != null && publicationRequest != request
                && publicationResult != null && !publicationResult.isDone()) cancelCpmPublication();
        if (context.disposed() || state.addSource == null || context.busy())
            return CompletableFuture.completedFuture(com.naocraftlab.skins.client.CpmModelPublication.Result.stopped(
                    com.naocraftlab.skins.client.CpmModelPublication.Reason.STALE));
        ImportScope scope = context.captureImportScope();
        if (context.disposed() || state.addSource == null)
            return CompletableFuture.completedFuture(CpmModelPublication.Result.stopped(CpmModelPublication.Reason.STALE));
        publicationScope = scope;
        if (scope.accountId() == null) return CompletableFuture.completedFuture(
                CpmModelPublication.Result.stopped(CpmModelPublication.Reason.MISSING_SESSION));
        if (activeAttempt == null) activeAttempt = new PublicationAttempt(scope);
        PublicationAttempt attempt = activeAttempt;
        ScreenOperationTicket ticket = context.beginScreenOperation();
        publicationRequest = Objects.requireNonNull(request);
        var result = new CompletableFuture<com.naocraftlab.skins.client.CpmModelPublication.Result>();
        publicationResult = result;
        state.settlements.removeIf(value -> value.value != null
                && value.value.status() == CpmModelPublication.Status.NOT_SENT);
        PublicationSettlement settlement = new PublicationSettlement(scope, request, new CompletableFuture<>());
        PublicationSend send = new PublicationSend(attempt, settlement, result);
        attempt.send = send;
        state.settlements.add(settlement);
        context.publish();
        CompletableFuture.supplyAsync(() -> {
                    if (!send.getAsBoolean()) return CpmModelPublication.Result.stopped(attempt.remainingNanos() == 0
                            ? CpmModelPublication.Reason.EXPIRED : CpmModelPublication.Reason.STALE);
                    return adapter.publish(request, send);
                }, context.worker())
                .whenComplete((value, failure) -> context.onClient(() -> {
                    CpmModelPublication.Result settled;
                    synchronized (attempt) {
                        settled = failure == null && value != null ? value : send.stop(CpmModelPublication.Reason.PROTOCOL);
                        settlement.value = settled;
                    }
                    settlement.result.complete(settled);
                    servicePublicationDeadline();
                    if (activeAttempt != attempt || !attempt.getAsBoolean()) {
                        result.complete(settled);
                        if (activeAttempt == attempt) {
                            context.currentImportScope(scope);
                            if (activeAttempt == attempt) completeAttempt();
                        }
                        return;
                    }
                    if (context.current(ticket)) context.finishScreenOperation(ticket);
                    result.complete(settled);
                    if (state.preparation == null && activeAttempt == attempt) completeAttempt();
                }));
        return result;
    }

    void cancelCpmPublication() {
        PublicationAttempt attempt = activeAttempt;
        completeAttempt();
        publicationRequest = null;
        if (attempt != null && attempt.send != null) {
            CpmModelPublication.Result stopped;
            synchronized (attempt) { stopped = attempt.send.stop(CpmModelPublication.Reason.STALE); }
            attempt.send.uiResult.complete(stopped);
        }
        publicationResult = null;
    }

    void chooseAddSourcePng() {
        if (state.dependency != null) return;
        if (state.addSource == null
                || state.addSource.selectedTab() != AddSourceTab.FILE
                || context.busy()) {
            return;
        }
        ParentFrame parent = new ParentFrame(state.addSource, context.status(), addSourceScrollPosition, addSourceScrollTarget);
        state.operationScope = context.captureImportScope();
        ScreenOperationTicket ticket = context.beginScreenOperation();
        context.showFeedback(UiMessage.info("nclskins.status.choose_png"));
        context.publish();
        CompletableFuture<Optional<Path>> picked;
        try {
            picked = Objects.requireNonNull(state.model == null
                    ? context.filePicker().chooseSkinImportFile(context.modelFileConversion().isPresent())
                    : context.filePicker().chooseSkinPng(), "picker future");
        } catch (RuntimeException unavailablePicker) {
            finishAddSourcePicker(ticket, null, unavailablePicker);
            return;
        }
        picked.whenComplete((selection, failure) -> context.onClient(() -> {
            if (!modelCurrent(ticket)) {
                return;
            }
            if (failure != null || selection == null) {
                finishAddSourcePicker(ticket, null, failure);
            } else if (selection.isEmpty()) {
                state.operationScope = null;
                context.finishScreenOperation(ticket);
                context.showFeedback(UiMessage.info("nclskins.status.cancelled"));
                context.publish();
            } else {
                Path path = selection.orElseThrow();
                if (state.model != null) {
                    convertBase(ticket, path);
                    return;
                }
                acquireFile(ticket, path, parent);
            }
        }));
    }


    private void acquireFile(ScreenOperationTicket ticket, Path path, ParentFrame parent) {
        Optional<CpmModelFileConversion> available = context.modelFileConversion();
        String name = UntrustedDisplayName.fromFileName(path.getFileName().toString(),
                context.textResolver().resolve(UiMessage.info("nclskins.editor.add_title")));
        acquireInput(ticket, name, path.getFileName().toString(), parent, available,
                () -> context.importOperations().acquireFile(path, available, unavailableModelReason()), context::fileImportFailure);
    }

    private void acquireInput(ScreenOperationTicket ticket, String name, String publicationName, ParentFrame parent,
            Optional<CpmModelFileConversion> available, ThrowingSupplier<ImportOperations.AcquiredInput> acquisition,
            Function<Throwable, UiMessage> failureMessage) {
        acquireInput(ticket, name, publicationName, parent, available, acquisition, failureMessage, beginImportWork(true));
    }

    private void acquireInput(ScreenOperationTicket ticket, String name, String publicationName, ParentFrame parent,
            Optional<CpmModelFileConversion> available, ThrowingSupplier<ImportOperations.AcquiredInput> acquisition,
            Function<Throwable, UiMessage> failureMessage, ImportWork work) {
        SkinModel preferred = SkinModel.valueOf(state.addSource.preferredVariant().name());
        CompletableFuture.supplyAsync(() -> {
            try {
                requireImportWork(work);
                ImportOperations.AcquiredInput acquired = acquisition.get();
                if (acquired instanceof ImportOperations.AcquiredModel model) {
                    try {
                        requireImportWork(work);
                        var automatic = model.model().automaticBase(preferred);
                        var bases = new java.util.ArrayList<CpmModelFileConversion.Raster>();
                        automatic.ifPresent(bases::add);
                        if (automatic.isPresent() && model.model().requiredVariant().isEmpty()) {
                            requireImportWork(work);
                            SkinModel alternate = automatic.orElseThrow().variant() == SkinModel.SLIM ? SkinModel.CLASSIC : SkinModel.SLIM;
                            model.model().automaticBase(alternate).filter(base -> base.variant() == alternate).ifPresent(bases::add);
                        }
                        return new PreparedInput(acquired, List.copyOf(bases));
                    } catch (Exception | Error failure) {
                        model.model().close();
                        throw failure;
                    }
                }
                return new PreparedInput(acquired, List.of());
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        }, context.worker()).whenComplete((prepared, failure) -> context.onClient(() -> {
            ImportOperations.AcquiredInput acquired = prepared == null ? null : prepared.input();
            if (!modelCurrent(ticket)) {
                closeAcquired(acquired);
                return;
            }
            if (failure != null) {
                modelFailure(ticket, failure, failureMessage.apply(failure));
                return;
            }
            if (acquired instanceof ImportOperations.AcquiredSkin skin) {
                state.operationScope = null;
                context.finishScreenOperation(ticket);
                openImportedDraft(skin.draft(), name + ".png", true);
                context.publish();
                return;
            }
            if (acquired instanceof ImportOperations.AcquiredDependency dependency) {
                state.dependency = new PendingDependency(dependency, work, name, publicationName, parent, available, failureMessage,
                        ++state.preparationRevision);
                state.addSource = state.addSource.withImportContext(new AddSourceModel.ImportContext(
                        Optional.empty(), Optional.empty(), AddSourceModel.Purpose.CREATE_PRESET,
                        Optional.of(new AddSourceModel.DependencyPrompt(state.dependency.revision)))).withScrollOffset(0);
                resetAddSourceScroll();
                context.finishScreenOperation(ticket);
                context.showFeedback(UiMessage.info("nclskins.add.cpm_dependency_title"));
                context.publish();
                return;
            }
            state.dependency = null;
            state.model = ((ImportOperations.AcquiredModel) acquired).model();
            state.modelScope = state.operationScope;
            state.operationScope = null;
            state.converter = available.orElseThrow();
            state.modelParent = parent;
            state.modelName = name;
            state.publicationName = CpmPublicationName.fromBasename(publicationName);
            state.automaticBase = !prepared.automaticBases().isEmpty();
            if (state.automaticBase) {
                convertBases(ticket, prepared::automaticBases, context::fileImportFailure);
                return;
            }
            state.addSource = state.addSource.withImportContext(new AddSourceModel.ImportContext(
                    state.model.requiredVariant(), Optional.empty(), AddSourceModel.Purpose.CPM_BASE)).withSelectedTab(AddSourceTab.CATALOG);
            context.finishScreenOperation(ticket);
            context.publish();
        }));
    }

    private record PendingDependency(ImportOperations.AcquiredDependency input, ImportWork work, String name, String publicationName,
            ParentFrame parent, Optional<CpmModelFileConversion> converter,
            Function<Throwable, UiMessage> failureMessage, long revision) { }

    private void confirmDependency(long revision) {
        PendingDependency pending = state.dependency;
        if (pending == null || pending.revision != revision || context.busy() || pending.work.retired
                || context.disposed() || !context.currentImportScope(pending.work.scope)) return;
        ScreenOperationTicket ticket = context.beginScreenOperation();
        acquireInput(ticket, pending.name, pending.publicationName, pending.parent, pending.converter,
                () -> pending.input.acquire(() -> !pending.work.retired && !context.disposed()
                        && context.currentPublicationScope(pending.work.scope) && context.current(ticket)),
                failure -> {
                    Throwable cause = context.unwrap(failure);
                    if (cause instanceof com.naocraftlab.skins.core.api.PublicSkinImportException publicFailure)
                        return UiMessage.error(publicFailure.code() == com.naocraftlab.skins.core.api.PublicSkinImportException.Code.OVERSIZED
                                ? "nclskins.add.cpm_dependency_limit" : context.publicImportFailureKey(failure, false));
                    return pending.failureMessage.apply(failure);
                }, pending.work);
        context.publish();
    }

    private void closeAcquired(ImportOperations.AcquiredInput input) {
        if (input instanceof ImportOperations.AcquiredModel model) model.model().close();
        if (input instanceof ImportOperations.AcquiredDependency dependency) context.worker().execute(() -> {
            try { dependency.close(); }
            catch (IOException failure) { throw new CompletionException(failure); }
        });
    }

    private CpmModelFileConversion.Reason unavailableModelReason() {
        return context.cpmPresent() ? CpmModelFileConversion.Reason.CPM_UNAVAILABLE : CpmModelFileConversion.Reason.CPM_MISSING;
    }

    private boolean modelCurrent(ScreenOperationTicket ticket) {
        return !context.disposed() && state.addSource != null
                && context.currentImportScope(state.operationScope) && context.current(ticket);
    }

    boolean hasImportScope() { return state.operationScope != null || state.modelScope != null || publicationRequest != null; }


    void returnFromEditor() {
        if (state.model == null) return;
        ParentFrame parent = state.automaticBase ? state.modelParent : state.modelChooser;
        if (parent == null) return;
        AddSourceModel restored = parent.model().refreshed(state.addSource);
        if (state.automaticBase) retireModel();
        state.addSource = restored;
        addSourceScrollPosition = parent.scrollPosition();
        addSourceScrollTarget = parent.scrollTarget();
        context.clearRuntimeFocus("add_source");
        context.showFeedback(parent.feedback());
    }

    void editorPngReplaced() {
        if (state.model == null) return;
        AddSourceModel parent = state.modelParent.model().refreshed(state.addSource);
        retireModel();
        state.addSource = parent;
    }

    void invalidatePendingScope(CpmModelFileConversion.Reason reason) {
        if (activeAttempt != null && state.preparation != null && context.editor() == null) {
            failPublicationAttempt(activeAttempt, context.fileImportFailure(new CpmModelFileConversion.Failure(reason)),
                    Optional.of(reason));
            return;
        }
        if (state.operationScope == null) {
            if (state.modelScope != null && context.editor() == null) retireImportWork();
            return;
        }
        if (state.addSource == null || context.editor() != null) return;
        retireImportWork();
        context.cancelScreenOperation();
        state.operationScope = null;
        if (state.addSource != null) {
            state.addSource = state.addSource.withImportContext(new AddSourceModel.ImportContext(
                    state.model == null ? Optional.empty() : state.model.requiredVariant(), Optional.of(reason),
                    state.model == null ? AddSourceModel.Purpose.CREATE_PRESET : state.addSource.importContext().purpose()));
        }
        context.publish();
    }

    private void modelFailure(ScreenOperationTicket ticket, Throwable failure) {
        modelFailure(ticket, failure, context.fileImportFailure(failure));
    }

    private void modelFailure(ScreenOperationTicket ticket, Throwable failure, UiMessage message) {
        if (!modelCurrent(ticket)) return;
        PendingDependency dependency = state.dependency;
        state.dependency = null;
        if (dependency != null) {
            closeAcquired(dependency.input);
            state.addSource = dependency.parent.model().refreshed(state.addSource);
            addSourceScrollPosition = dependency.parent.scrollPosition();
            addSourceScrollTarget = dependency.parent.scrollTarget();
        }
        if (state.model != null && state.automaticBase && context.editor() == null) {
            ParentFrame parent = state.modelParent;
            AddSourceModel restored = parent.model().refreshed(state.addSource);
            retireModel();
            state.addSource = restored;
            addSourceScrollPosition = parent.scrollPosition();
            addSourceScrollTarget = parent.scrollTarget();
        }
        Throwable cause = context.unwrap(failure);
        Optional<CpmModelFileConversion.Reason> reason = cause instanceof CpmModelFileConversion.Failure typed
                ? Optional.of(typed.reason()) : Optional.empty();
        state.addSource = state.addSource.withImportContext(new AddSourceModel.ImportContext(
                state.model == null ? Optional.empty() : state.model.requiredVariant(), reason,
                state.model == null ? AddSourceModel.Purpose.CREATE_PRESET : state.addSource.importContext().purpose()));
        state.operationScope = null;
        context.finishScreenOperation(ticket);
        context.showFeedback(message);
        context.publish();
    }

    boolean canCancelPendingBase() {
        if (publicationBusy()) return false;
        if (state.dependency != null) return true;
        return state.model != null && context.editor() == null
                && (!context.busy() || state.personalRenameHash == null
                && state.addSource.personalSkinDeletion().isEmpty());
    }

    boolean clearImportFailure() {
        if (state.addSource == null || state.addSource.importContext().failure().isEmpty()) return false;
        state.addSource = state.addSource.withImportContext(new AddSourceModel.ImportContext(
                state.addSource.importContext().requiredVariant(), Optional.empty(), state.addSource.importContext().purpose()));
        return true;
    }

    private void convertBase(ScreenOperationTicket ticket, Path path) {
        convertBase(ticket, () -> {
            var acquired = context.importOperations().acquireFile(path, Optional.empty(),
                    CpmModelFileConversion.Reason.NESTED_MODEL);
            return ((ImportOperations.AcquiredSkin) acquired).draft();
        });
    }

    private void convertBase(ScreenOperationTicket ticket, ThrowingSupplier<ImportOperations.ImportDraft> preparation) {
        convertBase(ticket, preparation, context::fileImportFailure);
    }

    private void convertBase(ScreenOperationTicket ticket, ThrowingSupplier<ImportOperations.ImportDraft> preparation,
            Function<Throwable, UiMessage> preparationFailure) {
        convertBases(ticket, () -> {
            ImportOperations.ImportDraft skin = preparation.get();
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(skin.pngBytes()));
            return List.of(new CpmModelFileConversion.Raster(SkinModel.valueOf(skin.variant().name()),
                    image.getRGB(0, 0, 64, 64, null, 0, 64)));
        }, preparationFailure);
    }

    private void convertBases(ScreenOperationTicket ticket, ThrowingSupplier<List<CpmModelFileConversion.Raster>> preparation,
            Function<Throwable, UiMessage> preparationFailure) {
        state.operationScope = context.captureImportScope();
        CpmModelFileConversion.AdmittedModel model = state.model;
        CpmModelFileConversion adapter = state.converter;
        String publicationName = state.publicationName;
        boolean publicationAuthorized = state.work != null && !state.work.retired && state.work.publicationAuthorized
                && context.currentPublicationScope(state.modelScope);

        state.addSource = state.addSource.withImportContext(new AddSourceModel.ImportContext(
                model.requiredVariant(), Optional.empty(), state.addSource.importContext().purpose()));
        if (!state.automaticBase) state.modelChooser = new ParentFrame(state.addSource, context.status(),
                addSourceScrollPosition, addSourceScrollTarget);
        context.publish();
        ImportWork work = beginImportWork(publicationAuthorized);
        CompletableFuture.supplyAsync(() -> {
            var pending = new java.util.ArrayList<PendingPublication>();
            try {
                requireImportWork(work);
                List<CpmModelFileConversion.Raster> bases = preparation.get();
                var pairs = new EnumMap<SkinVariant, byte[]>(SkinVariant.class);
                for (var base : bases) {
                    requireImportWork(work);
                    if (model.requiredVariant().isPresent() && base.variant() != model.requiredVariant().orElseThrow())
                        throw new CpmModelFileConversion.Failure(CpmModelFileConversion.Reason.VARIANT_UNSUPPORTED);
                    try {
                        var prepared = adapter.prepare(model, base, work.revision);
                        if (prepared instanceof CpmModelFileConversion.Inline inline) {
                            var converted = inline.raster();
                            pairs.put(SkinVariant.valueOf(converted.variant().name()), encodeCarrier(converted));
                        } else if (prepared instanceof CpmModelFileConversion.Publication publication) {
                            try {
                                if (publication.revision() != work.revision || publication.variant() != base.variant())
                                    throw new CpmModelFileConversion.Failure(CpmModelFileConversion.Reason.CONVERSION_FAILED);
                                pending.add(new PendingPublication(publication, publication.wire()));
                            } catch (Exception | Error failure) { publication.close(); throw failure; }
                        } else throw new CpmModelFileConversion.Failure(CpmModelFileConversion.Reason.CONVERSION_FAILED);
                    } catch (CpmModelFileConversion.Failure unsupported) {
                        if (pairs.isEmpty() && pending.isEmpty() || unsupported.reason() != CpmModelFileConversion.Reason.VARIANT_UNSUPPORTED
                                && unsupported.reason() != CpmModelFileConversion.Reason.NONPORTABLE_OVERFLOW) throw unsupported;
                    }
                }
                return new PreparationBatch(work, model, publicationName,
                        SkinVariant.valueOf(bases.get(0).variant().name()), pairs, pending);
            } catch (Exception failure) {
                pending.forEach(value -> value.continuation.close());
                throw new CompletionException(failure);
            }
        }, context.worker()).whenComplete((converted, failure) -> context.onClient(() -> {
            if (!modelCurrent(ticket) || state.model != model) {
                if (converted != null) closeBatch(converted);
                return;
            }
            if (failure != null) {
                modelFailure(ticket, failure, context.unwrap(failure) instanceof CpmModelFileConversion.Failure
                        ? context.fileImportFailure(failure) : preparationFailure.apply(failure));
                return;
            }
            context.finishScreenOperation(ticket);
            state.preparation = converted;
            continuePreparation(converted);
        }));
    }

    private void finishConverted(ConvertedPairs converted) {
            state.operationScope = null;
            PresetEditorModel editor = context.createEditor(null);
            if (editor == null) {
                context.showFeedback(UiMessage.error("nclskins.gallery.prepare_failed"));
            } else {
                editor = editor.withConvertedPngs(state.modelName + ".png", converted.pngs(), converted.variant()).withName(state.modelName);
                context.acceptDraft(new EditorDraftTransfer(context.applyPendingPresetName(editor),
                        null, Optional.of(PersonalSkinSource.FILE), null,
                        Optional.of(context.captureEditorSaveScope(state.modelScope))));
            }
            context.publish();
    }

    private void continuePreparation(PreparationBatch batch) {
        servicePublicationDeadline();
        if (!currentPreparation(batch)) return;
        PendingPublication pending = batch.pending.stream().filter(value -> !value.linked).findFirst().orElse(null);
        if (pending != null) {
            if (!batch.work.publicationAuthorized) {
                var reason = state.modelScope.worldEpoch() != batch.work.scope.worldEpoch()
                        ? CpmModelFileConversion.Reason.STALE_WORLD : CpmModelFileConversion.Reason.STALE_RESOURCES;
                retirePreparation();
                modelFailure(context.captureScreenOperation(), new CpmModelFileConversion.Failure(reason));
                return;
            }
            if (activeAttempt == null) {
                activeAttempt = new PublicationAttempt(batch.work.scope);
                ParentFrame parent = state.modelParent;
                state.addSource = parent.model().refreshed(state.addSource);
                addSourceScrollPosition = parent.scrollPosition();
                addSourceScrollTarget = parent.scrollTarget();
                context.clearRuntimeFocus("add_source");
                context.showFeedback(UiMessage.info("nclskins.add.cpm_publication_loading"));
            }
            var confirmed = batch.pending.stream().filter(value -> value.result != null
                    && value.result.status() == CpmModelPublication.Status.CONFIRMED
                    && java.util.Arrays.equals(value.wire, pending.wire)).findFirst();
            if (confirmed.isPresent()) {
                pending.result = confirmed.orElseThrow().result;
                linkPublication(batch, pending);
                return;
            }
            for (PublicationSettlement settlement : state.settlements) {
                if (context.currentPublicationScope(settlement.scope)
                        && java.util.Arrays.equals(settlement.request.wire(), pending.wire)
                        && settlement.value != null && settlement.value.status() != CpmModelPublication.Status.NOT_SENT) {
                    pending.result = settlement.value;
                    if (pending.result.status() == CpmModelPublication.Status.CONFIRMED) linkPublication(batch, pending);
                    else failPublicationAttempt(activeAttempt, publicationFailure(pending.result));
                    return;
                }
            }
            publishPreparation(batch, pending);
            return;
        }
        state.preparation = null;
        completeAttempt();
        state.addSource = state.addSource.withImportContext(new AddSourceModel.ImportContext(
                batch.model.requiredVariant(), Optional.empty(), state.addSource.importContext().purpose()));
        closeBatch(batch);
        finishConverted(new ConvertedPairs(batch.variant, Map.copyOf(batch.pairs)));
    }

    private boolean currentPreparation(PreparationBatch batch) {
        return batch != null && state.preparation == batch && state.model == batch.model && state.work == batch.work
                && !batch.work.retired && !context.disposed() && state.addSource != null
                && context.currentPublicationScope(batch.work.scope);
    }


    private void publishPreparation(PreparationBatch batch, PendingPublication pending) {
        PublicationAttempt attempt = activeAttempt;
        if (!currentPreparation(batch) || context.busy() || attempt == null) return;
        var request = new CpmModelPublication.Request(pending.wire, ++state.preparationRevision, batch.publicationName);
        context.publishCpmModel(request, true).whenComplete((result, failure) -> context.onClient(() -> {
            servicePublicationDeadline();
            if (!currentPreparation(batch) || activeAttempt != attempt) return;
            pending.result = failure == null && result != null ? result
                    : CpmModelPublication.Result.stopped(CpmModelPublication.Reason.PROTOCOL);
            if (pending.result.status() == CpmModelPublication.Status.CONFIRMED) linkPublication(batch, pending);
            else {
                failPublicationAttempt(attempt, publicationFailure(pending.result));
            }
        }));
    }

    private void linkPublication(PreparationBatch batch, PendingPublication pending) {
        servicePublicationDeadline();
        if (!currentPreparation(batch)) return;
        PublicationAttempt attempt = activeAttempt;
        context.showFeedback(UiMessage.info("nclskins.add.cpm_publication_loading"));
        ScreenOperationTicket ticket = context.beginScreenOperation();
        var candidates = batch.pending.stream().filter(value -> !value.linked
                && java.util.Arrays.equals(value.wire, pending.wire)).toList();
        context.publish();
        CompletableFuture.supplyAsync(() -> {
            var linked = new java.util.ArrayList<LinkedPublication>();
            try {
                for (PendingPublication candidate : candidates) {
                    if (attempt == null || !attempt.getAsBoolean()) throw new CpmModelFileConversion.Failure(CpmModelFileConversion.Reason.CLOSED);
                    requireImportWork(batch.work);
                    var prepared = candidate.continuation.link(pending.result.pasteId().orElseThrow());
                    if (!attempt.getAsBoolean()) {
                        if (prepared instanceof CpmModelFileConversion.Publication next) next.close();
                        throw new CpmModelFileConversion.Failure(CpmModelFileConversion.Reason.CLOSED);
                    }
                    if (prepared instanceof CpmModelFileConversion.Publication next) {
                        try {
                            if (next.variant() != candidate.continuation.variant() || next.revision() != batch.work.revision)
                                throw new CpmModelFileConversion.Failure(CpmModelFileConversion.Reason.CONVERSION_FAILED);
                            linked.add(new LinkedPublication(candidate, new PendingPublication(next, next.wire()), null));
                        } catch (Exception | Error failure) { next.close(); throw failure; }
                    } else if (prepared instanceof CpmModelFileConversion.Inline inline) {
                        var raster = inline.raster();
                        if (raster.variant() != candidate.continuation.variant())
                            throw new CpmModelFileConversion.Failure(CpmModelFileConversion.Reason.CONVERSION_FAILED);
                        byte[] png = encodeCarrier(raster);
                        if (!attempt.getAsBoolean()) throw new CpmModelFileConversion.Failure(CpmModelFileConversion.Reason.CLOSED);
                        linked.add(new LinkedPublication(candidate, null, png));
                    } else throw new CpmModelFileConversion.Failure(CpmModelFileConversion.Reason.CONVERSION_FAILED);
                }
                requireImportWork(batch.work);
                return linked;
            } catch (Exception | Error failure) {
                linked.forEach(value -> { if (value.next != null) value.next.continuation.close(); });
                throw new CompletionException(failure);
            }
        }, context.worker()).whenComplete((linked, failure) -> context.onClient(() -> {
            servicePublicationDeadline();
            if (!currentPreparation(batch) || !context.current(ticket)) {
                if (linked != null) context.worker().execute(() -> linked.forEach(value -> {
                    if (value.next != null) value.next.continuation.close();
                }));
                return;
            }
            context.finishScreenOperation(ticket);
            if (failure != null) {
                Throwable cause = context.unwrap(failure);
                Optional<CpmModelFileConversion.Reason> reason = cause instanceof CpmModelFileConversion.Failure typed
                        ? Optional.of(typed.reason()) : Optional.empty();
                failPublicationAttempt(attempt, context.fileImportFailure(failure), reason);
                return;
            }
            for (var value : linked) {
                value.previous.linked = true;
                value.previous.result = pending.result;
                if (value.next != null) batch.pending.add(value.next);
                else batch.pairs.put(SkinVariant.valueOf(value.previous.continuation.variant().name()), value.png);
            }
            continuePreparation(batch);
        }));
    }

    private static UiMessage publicationFailure(CpmModelPublication.Result result) {
        if (result.status() == CpmModelPublication.Status.UNCERTAIN
                && result.reason() != CpmModelPublication.Reason.SIZE_QUOTA)
            return UiMessage.error("nclskins.add.cpm_publication_uncertain");
        return UiMessage.error(switch (result.reason()) {
            case MISSING_SESSION -> "nclskins.add.cpm_publication_missing_session";
            case PROOF_REJECTED, EXPIRED -> "nclskins.add.cpm_publication_proof_rejected";
            case COUNT_QUOTA -> "nclskins.add.cpm_publication_count_quota";
            case SIZE_QUOTA -> "nclskins.add.cpm_publication_size_quota";
            case NETWORK -> "nclskins.add.cpm_publication_network";
            default -> "nclskins.add.cpm_publication_protocol";
        });
    }

    private void closeBatch(PreparationBatch batch) {
        var pending = List.copyOf(batch.pending);
        context.worker().execute(() -> pending.forEach(value -> value.continuation.close()));
    }

    private void retirePreparation() {
        cancelCpmPublication();
        PreparationBatch batch = state.preparation;
        state.preparation = null;
        if (batch != null) closeBatch(batch);
    }

    private record LinkedPublication(PendingPublication previous, PendingPublication next, byte[] png) {}

    private static final class PendingPublication {
        private final CpmModelFileConversion.Publication continuation;
        private final byte[] wire;
        private CpmModelPublication.Result result;
        private boolean linked;
        private PendingPublication(CpmModelFileConversion.Publication continuation, byte[] wire) {
            this.continuation = continuation;
            this.wire = wire.clone();
        }
    }

    private static final class PreparationBatch {
        private final String publicationName;
        private final ImportWork work;
        private final CpmModelFileConversion.AdmittedModel model;
        private final SkinVariant variant;
        private final Map<SkinVariant, byte[]> pairs;
        private final List<PendingPublication> pending;
        private PreparationBatch(ImportWork work, CpmModelFileConversion.AdmittedModel model, String publicationName,
                SkinVariant variant,
                Map<SkinVariant, byte[]> pairs, List<PendingPublication> pending) {
            this.work = work; this.model = model; this.variant = variant;
            this.publicationName = publicationName;
            this.pairs = pairs; this.pending = new java.util.ArrayList<>(pending);
        }
    }

    private static byte[] encodeCarrier(CpmModelFileConversion.Raster raster) throws CpmModelFileConversion.Failure {
        try {
            var image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, 64, 64, raster.argb(), 0, 64);
            var output = new java.io.ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
            return new com.naocraftlab.skins.core.png.PngValidator().projectImport(output.toByteArray()).pngBytes();
        } catch (IOException | com.naocraftlab.skins.core.png.PngValidationException failure) {
            throw new CpmModelFileConversion.Failure(CpmModelFileConversion.Reason.INVALID_OUTPUT_PNG);
        }
    }

    private record PreparedInput(ImportOperations.AcquiredInput input, List<CpmModelFileConversion.Raster> automaticBases) {}
    private record ConvertedPairs(SkinVariant variant, Map<SkinVariant, byte[]> pngs) {}

    private ImportWork beginImportWork(boolean publicationAuthorized) {
        retireImportWork();
        ImportWork work = new ImportWork(state.operationScope, ++state.preparationRevision, publicationAuthorized);
        state.work = work;
        return work;
    }

    private void retireImportWork() {
        retirePreparation();
        PendingDependency dependency = state.dependency;
        state.dependency = null;
        if (dependency != null) closeAcquired(dependency.input);
        if (state.work != null) state.work.retired = true;
        state.work = null;
    }

    private void requireImportWork(ImportWork work) throws CpmModelFileConversion.Failure {
        if (work.retired || !context.currentPublicationScope(work.scope))
            throw new CpmModelFileConversion.Failure(CpmModelFileConversion.Reason.CLOSED);
    }

    private static final class ImportWork {
        private final ImportScope scope;
        private final long revision;
        private final boolean publicationAuthorized;
        private volatile boolean retired;
        private ImportWork(ImportScope scope, long revision, boolean publicationAuthorized) {
            this.scope = scope; this.revision = revision; this.publicationAuthorized = publicationAuthorized;
        }
    }

    private void retireModel() {
        retireImportWork();
        if (state.model != null) {
            CpmModelFileConversion.AdmittedModel model = state.model;
            context.worker().execute(model::close);
        }
        state.model = null;
        state.converter = null;
        state.modelName = null;
        state.publicationName = null;
        state.modelParent = null;
        state.modelChooser = null;
        state.automaticBase = false;
        state.operationScope = null;
        state.modelScope = null;
    }

    void prepareExternalImport(ExternalImportSource source) {
        if (!externalImportsAllowed() || state.externalImport == null
                || context.busy()
                || !state.externalImport.available(source)) {
            return;
        }
        Optional<Path> root = state.externalImport.selectedRoot(source);
        ExternalImportModel owner = state.externalImport;
        context.submit(
                UiMessage.info("nclskins.external_import.preparing"),
                () -> {
                    requireExternalOperation(owner);
                    return context.importOperations().prepareExternalAppearances(source, root);
                },
                review -> {
                    if (externalOperationCurrent(owner)) {
                        state.externalImport = state.externalImport.withReview(review);
                        context.showFeedback(UiMessage.info("nclskins.external_import.review_ready"));
                    }
                },
                failure -> failExternalPreparation(source, failure));
    }

    void chooseExternalImportFolder(ExternalImportSource source) {
        if (!externalImportsAllowed() || state.externalImport == null
                || !state.externalImport.category().sources().contains(source)
                || context.busy()
                || state.externalImport.sources().get(source).availability()
                == ExternalImportModel.Availability.DEPENDENCY_MISSING) {
            return;
        }
        ExternalImportModel owner = state.externalImport;
        ScreenOperationTicket ticket = context.beginScreenOperation();
        context.showFeedback(UiMessage.info("nclskins.external_import.choose_folder_status"));
        context.publish();
        CompletableFuture<Optional<Path>> picked;
        try {
            picked = Objects.requireNonNull(
                    source.requiresSqlite()
                            ? context.filePicker().chooseSqliteDatabase()
                            : context.filePicker().chooseDirectory(),
                    "external import picker future");
        } catch (RuntimeException unavailablePicker) {
            finishExternalDirectoryPicker(ticket, source, null, unavailablePicker);
            return;
        }
        picked.whenComplete((selection, failure) -> context.onClient(() -> {
            if (!context.current(ticket) || state.externalImport == null) {
                return;
            }
            if (failure != null || selection == null) {
                finishExternalDirectoryPicker(ticket, source, null, failure);
                return;
            }
            if (selection.isEmpty()) {
                context.finishScreenOperation(ticket);
                context.showFeedback(UiMessage.info("nclskins.external_import.choose_source"));
                context.publish();
                return;
            }
            Path root = selection.orElseThrow();
            CompletableFuture.supplyAsync(() -> {
                try {
                    requireExternalOperation(owner);
                    return context.importOperations().probeExternalSource(source, Optional.of(root));
                } catch (Exception probeFailure) {
                    throw new CompletionException(probeFailure);
                }
            }, context.worker()).whenComplete((probe, probeFailure) -> context.onClient(() -> {
                if (!context.current(ticket) || state.externalImport == null) {
                    return;
                }
                context.finishScreenOperation(ticket);
                if (probeFailure == null) {
                    state.externalImport = state.externalImport.withManualProbe(
                            source, root, probe == ExternalImportProbe.AVAILABLE);
                    context.showFeedback(probe == ExternalImportProbe.AVAILABLE
                            ? UiMessage.success("nclskins.external_import.folder_ready")
                            : UiMessage.error(invalidFolderKey(source)));
                } else {
                    context.diagnose(DiagnosticEvent.CLIENT_IMPORT_FAILED, probeFailure);
                    state.externalImport = state.externalImport.withManualProbe(source, root, false);
                    context.showFeedback(UiMessage.error(invalidFolderKey(source)));
                }
                context.publish();
            }));
        }));
    }

    void finishExternalDirectoryPicker(
            ScreenOperationTicket ticket,
            ExternalImportSource source,
            Path ignored,
            Throwable failure) {
        if (!context.current(ticket) || state.externalImport == null) {
            return;
        }
        context.diagnose(DiagnosticEvent.CLIENT_PICKER_FAILED, failure);
        context.finishScreenOperation(ticket);
        context.showFeedback(UiMessage.error("nclskins.external_import.picker_failed"));
        context.publish();
    }

    void toggleExternalCandidate(String candidateId) {
        if (state.externalImport == null || state.externalImport.review().isEmpty() || context.busy()) {
            return;
        }
        state.externalImport = state.externalImport.toggleCandidate(candidateId);
        context.publish();
    }

    void toggleExternalCollection(boolean duplicates) {
        if (state.externalImport == null || state.externalImport.review().isEmpty() || context.busy()) {
            return;
        }
        state.externalImport = state.externalImport.toggleCollection(duplicates);
        context.publish();
    }

    void toggleAllExternalCollections() {
        if (state.externalImport == null || state.externalImport.review().isEmpty() || context.busy()) {
            return;
        }
        ExternalImportModel.ReviewState review = state.externalImport.review().orElseThrow();
        if (review.availableCollections().isEmpty()) {
            return;
        }
        ExternalImportModel changed = state.externalImport.withAllCollectionsCollapsed(
                !review.anyCollectionCollapsed());
        int normalized = externalImportPresenter.normalizedReviewScrollOffset(
                changed, context.viewportWidth(), context.viewportHeight(), review.scrollOffset());
        state.externalImport = changed.withReviewScroll(normalized);
        context.publish();
    }

    void toggleAllExternalCandidates() {
        if (state.externalImport == null || state.externalImport.review().isEmpty() || context.busy()) {
            return;
        }
        state.externalImport = state.externalImport.toggleAll();
        context.publish();
    }

    void commitExternalImport() {
        if (!externalImportsAllowed() || state.externalImport == null || state.externalImport.review().isEmpty() || context.busy()) {
            return;
        }
        ExternalImportModel.ReviewState review = state.externalImport.review().orElseThrow();
        List<ImportOperations.ExternalImportCandidate> selected = review.selectedCandidates();
        if (selected.isEmpty()) {
            return;
        }
        ExternalImportModel owner = state.externalImport;
        context.submit(
                UiMessage.info("nclskins.status.saving"),
                () -> {
                    requireExternalOperation(owner);
                    return context.importOperations().commitExternalAppearances(
                            selected, review.review().skipped(), review.review().warnings());
                },
                result -> { if (externalOperationCurrent(owner)) finishExternalImport(result); },
                failure -> context.showFeedback(UiMessage.error("nclskins.external_import.commit_failed")));
    }

    void finishExternalImport(ImportOperations.ExternalImportResult result) {
        state.externalImport = null;
        state.addSource = null;
        context.externalImportCompleted(result);
        context.showFeedback(UiMessage.success(
                "nclskins.external_import.complete",
                result.imported(),
                result.skipped(),
                result.alreadyPresent(),
                result.warnings()));
        if (context.addSourceRoot()) context.closeScreenOnClient();
    }

    void failExternalPreparation(ExternalImportSource source, Throwable failure) {
        if (state.externalImport == null) {
            return;
        }
        Throwable cause = context.unwrap(failure);
        if (cause instanceof ExternalImportException external
                && external.code() == ExternalImportException.Code.NO_VALID_APPEARANCES) {
            context.showFeedback(UiMessage.error(switch (source) {
                case CURSEFORGE_APP, MODRINTH_APP ->
                        "nclskins.external_import.no_valid_current_account";
                case MINECRAFT_LAUNCHER, SKIN_SHUFFLE, SKIN_SWAPPER_FAMILY,
                        QUICK_SKIN, PRISM_LAUNCHER -> "nclskins.external_import.no_valid";
            }));
            return;
        }
        if (cause instanceof ExternalImportException external
                && external.code() == ExternalImportException.Code.DEPENDENCY_MISSING) {
            context.showFeedback(UiMessage.error("nclskins.external_import.sqlite_dependency_required"));
            return;
        }
        context.showFeedback(UiMessage.error(invalidFolderKey(source)));
    }

    String invalidFolderKey(ExternalImportSource source) {
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

    void cancelExternalReview() {
        if (state.externalImport == null || state.externalImport.review().isEmpty()) {
            return;
        }
        context.cancelScreenOperation();
        state.externalImport = state.externalImport.clearReview();
        context.showFeedback(UiMessage.info("nclskins.external_import.choose_source"));
        context.publish();
    }

    void cancelExternalImport() {
        if (state.externalImport == null) {
            return;
        }
        boolean cancelledBusyOperation = context.busy();
        if (context.busy()) {
            context.cancelScreenOperation();
            context.showFeedback(UiMessage.info("nclskins.status.cancelled"));
        }
        if (state.externalImport.review().isPresent()) {
            state.externalImport = state.externalImport.clearReview();
        } else {
            state.externalImport = null;
        }
        if (!cancelledBusyOperation) {
            context.showFeedback(UiMessage.info("nclskins.external_import.choose_source"));
        }
        context.publish();
    }

    void loadRemoteImport(boolean player) {
        if (state.dependency != null) return;
        if (state.addSource == null || state.addSource.selectedTab() != AddSourceTab.FILE || context.busy()) {
            return;
        }
        String input = player ? state.addSource.playerInput() : state.addSource.urlInput();
        if (input.isBlank()) {
            return;
        }
        if (state.model != null) {
            ScreenOperationTicket ticket = context.beginScreenOperation();
            context.publish();
            convertBase(ticket, () -> player
                    ? context.importOperations().loadPlayerSkin(input)
                    : context.importOperations().loadUrlSkin(input),
                    problem -> UiMessage.error(context.publicImportFailureKey(problem, player)));
            return;
        }
        if (!player) {
            ParentFrame parent = new ParentFrame(state.addSource, context.status(), addSourceScrollPosition, addSourceScrollTarget);
            state.operationScope = context.captureImportScope();
            ScreenOperationTicket ticket = context.beginScreenOperation();
            Optional<CpmModelFileConversion> available = context.modelFileConversion();
            context.showFeedback(UiMessage.info("nclskins.add_source.url_loading"));
            context.publish();
            acquireInput(ticket, "Imported URL skin", CpmPublicationName.fromUrl(input), parent, available,
                    () -> context.importOperations().acquireUrl(input, available, unavailableModelReason()),
                    failure -> context.unwrap(failure) instanceof CpmModelFileConversion.Failure
                            ? context.fileImportFailure(failure) : UiMessage.error(context.publicImportFailureKey(failure, false)));
            return;
        }
        context.submit(
                UiMessage.info(player
                        ? "nclskins.add_source.player_loading"
                        : "nclskins.add_source.url_loading"),
                () -> player ? context.importOperations().loadPlayerSkin(input) : context.importOperations().loadUrlSkin(input),
                draft -> {
                    openImportedDraft(draft, draft.name() + ".png", true);
                },
                failure -> context.showFeedback(UiMessage.error(player
                        ? context.publicImportFailureKey(failure, true)
                        : context.publicImportFailureKey(failure, false))));
    }

    boolean openImportedDraft(
            ImportOperations.ImportDraft draft,
            String sourceName,
            boolean useSuggestedPresetName) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(sourceName, "sourceName");
        PresetEditorModel editor = context.createEditor(null);
        if (editor == null) {
            context.showFeedback(UiMessage.error("nclskins.gallery.prepare_failed"));
            return false;
        }
        editor = editor.withImportedPng(sourceName, draft.pngBytes(), draft.variant());
        if (useSuggestedPresetName) {
            editor = editor.withName(draft.name());
        }
        context.acceptDraft(new EditorDraftTransfer(context.applyPendingPresetName(editor),
                null, Optional.of(draft.source()), null));
        context.rememberPreferredSkinVariant(draft.variant());
        return true;
    }

    void finishAddSourcePicker(ScreenOperationTicket ticket, byte[] ignored, Throwable failure) {
        context.onClient(() -> {
            if (!context.current(ticket) || state.addSource == null) {
                return;
            }
            if (context.unwrap(failure) instanceof FilePicker.InvalidSelection invalidSelection
                    && invalidSelection.reason() == FilePicker.InvalidSelection.Reason.MODEL_NOT_ALLOWED) {
                modelFailure(ticket, new CpmModelFileConversion.Failure(state.model != null
                        ? CpmModelFileConversion.Reason.NESTED_MODEL : unavailableModelReason()));
                return;
            }
            context.diagnose(DiagnosticEvent.CLIENT_PICKER_FAILED, failure);
            state.operationScope = null;
            context.finishScreenOperation(ticket);
            context.showFeedback(UiMessage.error("nclskins.error.picker"));
            context.publish();
        });
    }

    void requestPersonalSkinDeletion(
            String collectionId, String sha256, InteractionOrigin origin) {
        if (context.busy()
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
        context.showFeedback(UiMessage.info("nclskins.your_skins.delete_note"));
        context.publish();
    }

    void requestPersonalSkinRename(String collectionId, String sha256) {
        if (context.busy()
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
        context.publish();
    }

    void cancelPersonalSkinRename() {
        if (context.busy() || state.personalRenameHash == null) {
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
        context.publish();
    }

    void savePersonalSkinRename() {
        if (context.busy() || state.addSource == null || state.personalRenameHash == null) {
            return;
        }
        String collectionId = state.personalRenameCollectionId;
        String hash = state.personalRenameHash;
        String name = UntrustedDisplayName.sanitize(state.personalRenameValue, "");
        if (name.isBlank()) {
            return;
        }
        context.submit(
                UiMessage.info("nclskins.status.saving"),
                () -> context.libraryEditorPort().renamePersonalSkin(hash, name),
                account -> {
                    context.personalSkinRenamed(account);
                    if (state.addSource != null) {
                        state.addSource = state.addSource
                                .renamedPersonalSkin(collectionId, hash, name)
                                .withRequestedFocus(AddSourceModel.personalActionId(
                                        "add.catalog.rename:", collectionId, hash));
                    }
                    state.personalRenameCollectionId = null;
                    state.personalRenameHash = null;
                    state.personalRenameValue = "";
                    context.showFeedback(UiMessage.success("nclskins.your_skins.renamed"));
                });
    }

    void cancelPersonalSkinDeletion(InteractionOrigin origin) {
        if (context.busy()
                || state.addSource == null
                || state.addSource.personalSkinDeletion().isEmpty()) {
            return;
        }
        state.addSource = state.addSource.cancelPersonalSkinDeletion(origin.keyboard());
        context.showFeedback(UiMessage.info("nclskins.status.cancelled"));
        context.publish();
    }

    void confirmPersonalSkinDeletion(InteractionOrigin origin) {
        if (context.busy()
                || state.addSource == null
                || state.addSource.personalSkinDeletion().isEmpty()) {
            return;
        }
        AddSourceModel.PersonalSkinDeletion deletion =
                state.addSource.personalSkinDeletion().orElseThrow();
        context.submit(
                UiMessage.info("nclskins.your_skins.deleting"),
                () -> context.libraryEditorPort().removePersonalSkin(deletion.sha256()),
                account -> {
                    context.personalSkinDeleted(account);
                    if (state.addSource != null
                            && state.addSource.personalSkinDeletion()
                                    .map(AddSourceModel.PersonalSkinDeletion::sha256)
                                    .filter(deletion.sha256()::equals)
                                    .isPresent()) {
                        state.addSource = state.addSource.removeConfirmedPersonalSkin(origin.keyboard());
                        clampAddSourceScroll();
                    }
                    context.previewAssets().invalidateCatalogPreviews();
                    context.showFeedback(UiMessage.success("nclskins.your_skins.deleted"));
                },
                failure -> context.showFeedback(UiMessage.error("nclskins.your_skins.delete_failed")));
    }

    void cancelAddSource() {
        if (publicationBusy()) return;
        if (state.dependency != null) {
            ParentFrame parent = state.dependency.parent;
            retireImportWork();
            context.cancelScreenOperation();
            state.operationScope = null;
            state.addSource = parent.model().refreshed(state.addSource);
            addSourceScrollPosition = parent.scrollPosition();
            addSourceScrollTarget = parent.scrollTarget();
            context.showFeedback(UiMessage.info("nclskins.status.cancelled"));
            context.publish();
            return;
        }

        if (state.model != null && context.editor() == null) {
            if (!canCancelPendingBase()) return;
            ParentFrame parent = state.modelParent;
            AddSourceModel restored = parent.model().refreshed(state.addSource);
            context.cancelScreenOperation();
            retireModel();
            state.addSource = restored;
            draggingAddSourceScrollbar = false;
            addSourceScrollPosition = parent.scrollPosition();
            addSourceScrollTarget = parent.scrollTarget();
            context.showFeedback(parent.feedback());
            context.publish();
            return;
        }
        if (state.addSource != null && context.editor() == null) {
            if (context.busy() && state.addSource.selectedTab() != AddSourceTab.FILE) {
                return;
            }
            if (context.busy()) {
                retireImportWork();
                context.cancelScreenOperation();
                context.showFeedback(UiMessage.info("nclskins.status.cancelled"));
            }
            if (context.addSourceRoot()) {
                context.closeScreenOnClient();
                return;
            }
            resetPersonalCatalogInteraction();
            state.operationScope = null;
            state.addSource = null;
            context.clearRuntimeFocus("add_source");
            draggingAddSourceScrollbar = false;
            resetAddSourceScroll();
            context.publish();
        }
    }

    boolean personalCatalogInteractionBelongsTo(String collectionId) {
        if (state.addSource == null) {
            return false;
        }
        boolean deletionBelongs = state.addSource.personalSkinDeletion()
                .map(AddSourceModel.PersonalSkinDeletion::collectionId)
                .filter(collectionId::equals)
                .isPresent();
        return deletionBelongs || Objects.equals(state.personalRenameCollectionId, collectionId);
    }

    void resetPersonalCatalogInteraction() {
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

    Optional<ViewSpec.ScrollSurface> addSourceSurface() {
        if (state.addSource == null || state.externalImport != null) return Optional.empty();
        return presentCatalogImport(context.viewportWidth(), context.viewportHeight()).scrollSurfaces().stream()
                .filter(surface -> surface.id().equals("add.catalog") || surface.id().equals("add.import"))
                .findFirst();
    }

    void setAddSourceFromScrollbar(ViewSpec.Scrollbar scrollbar, double desiredThumbTop) {
        int travel = scrollbar.track().height() - scrollbar.thumb().height();
        double fraction = travel <= 0 ? 0 : Math.max(0, Math.min(1,
                (desiredThumbTop - scrollbar.track().y()) / travel));
        setAddSourceOffset((int) Math.round(fraction * scrollbar.maximum()));
    }

    void setAddSourceOffset(int offset) {
        if (publicationBusy() || state.addSource == null) {
            return;
        }
        int maximum = addSourceSurface().map(surface -> (int) surface.maximumPixels()).orElse(0);
        int bounded = Math.max(0, Math.min(maximum, offset));
        if (bounded != state.addSource.scrollOffset()
                || Math.abs(addSourceScrollPosition - bounded) > 0.001
                || Math.abs(addSourceScrollTarget - bounded) > 0.001) {
            state.addSource = state.addSource.withScrollOffset(bounded);
            addSourceScrollPosition = bounded;
            addSourceScrollTarget = bounded;
            context.publish();
        }
    }

    void queueAddSourceScroll(double delta) {
        if (publicationBusy() || state.addSource == null || !Double.isFinite(delta) || delta == 0.0) {
            return;
        }
        int maximum = addSourceSurface().map(surface -> (int) surface.maximumPixels()).orElse(0);
        double current = Math.max(0.0, Math.min(maximum, addSourceScrollPosition));
        double bounded = Math.max(
                0.0, Math.min(maximum, current + delta));
        if (Math.abs(bounded - addSourceScrollPosition) > 0.001
                || Math.abs(bounded - addSourceScrollTarget) > 0.001) {
            addSourceScrollPosition = bounded;
            addSourceScrollTarget = bounded;
            state.addSource = state.addSource.withScrollOffset((int) Math.round(bounded));
            context.publish();
        }
    }

    void resetAddSourceScroll() {
        draggingAddSourceScrollbar = false;
        int offset = state.addSource == null ? 0 : state.addSource.scrollOffset();
        addSourceScrollPosition = offset;
        addSourceScrollTarget = offset;
    }

    void clampAddSourceScroll() {
        if (state.addSource == null) {
            resetAddSourceScroll();
            return;
        }
        int maximum = addSourceSurface().map(surface -> (int) surface.maximumPixels()).orElse(0);
        addSourceScrollPosition = Math.max(0.0, Math.min(maximum, addSourceScrollPosition));
        addSourceScrollTarget = Math.max(0.0, Math.min(maximum, addSourceScrollTarget));
        state.addSource = state.addSource.withScrollOffset((int) Math.round(addSourceScrollPosition));
    }

    boolean dispatchCatalogImportAction(String widgetId, boolean reverse, InteractionOrigin origin) {
        if (widgetId.startsWith("add.cpm.dependency.")) {
            PendingDependency pending = state.dependency;
            if (pending == null) return true;
            if (widgetId.equals("add.cpm.dependency.confirm." + pending.revision)) confirmDependency(pending.revision);
            if (widgetId.equals("add.cpm.dependency.cancel." + pending.revision)) cancelAddSource();
            return true;
        }
        if (state.dependency != null && !widgetId.equals("add.cancel"))
            return widgetId.startsWith("add.") || widgetId.startsWith("external.");
        if (widgetId.startsWith("add.cpm.publication.")) {
            return true;
        }
        if (state.preparation != null && !widgetId.equals("add.cancel")) return widgetId.startsWith("add.") || widgetId.startsWith("external.");
        if (!externalImportsAllowed() && (widgetId.startsWith("external.") || widgetId.startsWith("add.external."))) return true;
        if (widgetId.startsWith("add.catalog.collection:")) {
            toggleCatalogCollection(widgetId.substring("add.catalog.collection:".length()));
            return true;
        }
        if (widgetId.startsWith("add.catalog.delete:")) {
            context.personalCatalogAction(widgetId, "add.catalog.delete:")
                    .ifPresent(action -> requestPersonalSkinDeletion(
                            action.collectionId(), action.sha256(), origin));
            return true;
        }
        if (widgetId.startsWith("add.catalog.rename:")) {
            context.personalCatalogAction(widgetId, "add.catalog.rename:")
                    .ifPresent(action -> requestPersonalSkinRename(
                            action.collectionId(), action.sha256()));
            return true;
        }
        if (widgetId.startsWith("add.catalog.skin:")) {
            selectCatalogSkin(widgetId.substring("add.catalog.skin:".length()));
            return true;
        }
        if (widgetId.startsWith("external.source.")) {
            prepareExternalImport(ExternalImportPresenter.source(widgetId));
            return true;
        }
        if (widgetId.startsWith("external.folder.")) {
            chooseExternalImportFolder(ExternalImportPresenter.source(widgetId));
            return true;
        }
        if (widgetId.startsWith("external.review.card:")) {
            toggleExternalCandidate(widgetId.substring("external.review.card:".length()));
            return true;
        }
        if (widgetId.startsWith("external.review.collection.")) {
            toggleExternalCollection(widgetId.endsWith("duplicates"));
            return true;
        }
        switch (widgetId) {
            case "add.tab.file" -> {
                selectAddSourceTab(AddSourceTab.FILE);
                return true;
            }
            case "add.tab.catalog" -> {
                selectAddSourceTab(AddSourceTab.CATALOG);
                return true;
            }
            case "add.file.choose" -> {
                chooseAddSourcePng();
                return true;
            }
            case "add.external.launcher" -> {
                context.openExternalImport(ExternalImportModel.Category.LAUNCHER);
                return true;
            }
            case "add.external.mod" -> {
                context.openExternalImport(ExternalImportModel.Category.MOD);
                return true;
            }
            case "add.player.load" -> {
                loadRemoteImport(true);
                return true;
            }
            case "add.url.load" -> {
                loadRemoteImport(false);
                return true;
            }
            case "add.catalog.filter" -> {
                cycleCatalogFilter(reverse);
                return true;
            }
            case "add.catalog.disclosure" -> {
                toggleAllCatalogCollections(origin, widgetId);
                return true;
            }
            case "add.catalog.delete.confirm" -> {
                confirmPersonalSkinDeletion(origin);
                return true;
            }
            case "add.catalog.delete.cancel" -> {
                cancelPersonalSkinDeletion(origin);
                return true;
            }
            case "add.catalog.rename.save" -> {
                savePersonalSkinRename();
                return true;
            }
            case "add.catalog.rename.cancel" -> {
                cancelPersonalSkinRename();
                return true;
            }
            case "add.cancel" -> {
                cancelAddSource();
                return true;
            }
            case "external.back" -> {
                cancelExternalImport();
                return true;
            }
            case "external.review.toggle_all" -> {
                toggleAllExternalCandidates();
                return true;
            }
            case "external.review.disclosure" -> {
                toggleAllExternalCollections();
                context.retainKeyboardFocus(origin, "external_review", widgetId);
                return true;
            }
            case "external.review.commit" -> {
                commitExternalImport();
                return true;
            }
            case "external.review.cancel" -> {
                cancelExternalReview();
                return true;
            }
            default -> { return false; }
        }
    }

    ViewSpec presentCatalogImport(int width, int height) {
        if (state.externalImport != null) {
            return context.withRuntimeFocus(externalImportPresenter.present(
                    state.externalImport,
                    context.busy(),
                    Optional.of(context.status()),
                    width,
                    height,
                    context.snapshot().skinExtensionEnvironment()));
        }
        if (state.addSource != null) {
            return context.withRuntimeFocus(addSourcePresenter.present(
                    state.addSource,
                    context.busy(),
                    Optional.of(context.status()),
                    width,
                    height,
                    state.personalRenameHash == null
                            ? Optional.empty()
                            : Optional.of(new AddSourcePresenter.PersonalSkinRename(
                            state.personalRenameCollectionId,
                            state.personalRenameHash,
                            state.personalRenameValue)),
                    context.viewChromeMetrics()));
        }

        throw new IllegalStateException("Catalog flow is not open");
    }

    void leaveForSavedPreset() {
        retireModel();
        state.addSource = null;
    }

    void leaveForDuplicate() {
        state.addSource = null;
    }

    void resetSession() {
        cancelCpmPublication();
        state.settlements.clear();
        retireModel();
        state.addSource = null;
        state.externalImport = null;
        state.personalRenameCollectionId = null;
        state.personalRenameHash = null;
        state.personalRenameValue = "";
        state.catalogEvidence.clear();
    }

    void closeScreens() {
        cancelCpmPublication();
        state.settlements.clear();
        retireModel();
        state.addSource = null;
        state.externalImport = null;
    }

    void navigateToOffset(int bounded) {
        if (publicationBusy()) return;
        state.addSource = state.addSource.withScrollOffset(bounded);
        addSourceScrollPosition = bounded;
        addSourceScrollTarget = bounded;
    }

    boolean rememberChooserFocus(String widgetId) {
        if (state.model == null || state.addSource == null) return false;
        state.addSource = state.addSource.withRequestedFocus(widgetId);
        return true;
    }

    void searchCatalog(String value) {
        if (state.preparation != null || state.dependency != null) return;
        state.addSource = state.addSource.withQuery(value);
    }

    void editPlayerInput(String value) {
        if (state.preparation != null || state.dependency != null) return;
        state.addSource = state.addSource.withPlayerInput(value);
    }

    void editUrlInput(String value) {
        if (state.preparation != null || state.dependency != null) return;
        state.addSource = state.addSource.withUrlInput(value);
    }

    void editPersonalSkinName(String value) {
        state.personalRenameValue = value;
    }

    void scrollReviewTo(int offset) {
        state.externalImport = state.externalImport.withReviewScroll(offset);
    }

    boolean externalImportsAllowed() { return state.model == null && state.dependency == null; }

    boolean externalOperationCurrent(ExternalImportModel owner) {
        return externalImportsAllowed() && owner != null && state.externalImport == owner;
    }

    private void requireExternalOperation(ExternalImportModel owner) throws IOException {
        if (!externalOperationCurrent(owner)) throw new IOException("External import scope expired");
    }

    void openExternalCategory(ExternalImportModel.Category category) {
        if (!externalImportsAllowed()) return;
        state.externalImport = ExternalImportModel.open(category);
    }

    void acceptAutomaticProbes(Map<ExternalImportSource, ExternalImportProbe> probes) {
        if (!externalImportsAllowed() || state.externalImport == null) return;
        state.externalImport = state.externalImport.withAutomaticProbes(probes);
    }

    boolean acceptPreviewEvidence(ViewSpec.CatalogImage image, SkinVariant variant, SkinFeatureEvidence evidence) {
        return !evidence.equals(state.catalogEvidence.put(
                catalogEvidenceKey(image.collectionId(), image.skinId(), variant), evidence));
    }

    void openCatalog(
            AddSourceData data,
            SkinVariant fallbackVariant,
            SkinExtensionEnvironment catalogEnvironment,
            boolean hideIncompatibleCatalogSkins) {
        state.catalogEvidence.clear();
        data.featureEvidence().forEach((variant, evidence) -> state.catalogEvidence.put(
                catalogEvidenceKey(variant.collectionId(), variant.skinId(), variant.variant()), evidence));
        AddSourceModel refreshed = AddSourceModel.open(
                        data.preferences(), data.collections(), fallbackVariant, context.textResolver())
                .withCompatibilityContext(catalogEnvironment, state.catalogEvidence, hideIncompatibleCatalogSkins);
        if (state.model != null) {
            state.addSource = state.addSource.refreshed(refreshed);
            state.modelParent = new ParentFrame(state.modelParent.model().refreshed(refreshed),
                    state.modelParent.feedback(), state.modelParent.scrollPosition(), state.modelParent.scrollTarget());
        } else state.addSource = refreshed;
        resetAddSourceScroll();
    }

    void openEmptyCatalog(
            AccountUiPreferences cachedPreferences,
            SkinVariant fallbackVariant,
            SkinExtensionEnvironment catalogEnvironment,
            boolean hideIncompatibleCatalogSkins) {
        AddSourceModel refreshed = AddSourceModel.open(
                        cachedPreferences, List.of(), fallbackVariant, context.textResolver())
                .withCompatibilityContext(catalogEnvironment, Map.of(), hideIncompatibleCatalogSkins);
        state.addSource = state.model == null ? refreshed : state.addSource.refreshed(refreshed);
        resetAddSourceScroll();
    }

    void preferredVariantChanged(SkinVariant variant) {
        state.addSource = state.addSource.withPreferredVariant(variant);
    }

    AddSourceModel addSource() { return state.addSource; }

    ExternalImportModel externalImport() { return state.externalImport; }

    String personalRenameHash() { return state.personalRenameHash; }

    String personalRenameValue() { return state.personalRenameValue; }

    Map<String, SkinFeatureEvidence> catalogEvidence() { return java.util.Map.copyOf(state.catalogEvidence); }

    private static String catalogEvidenceKey(String collectionId, String skinId, SkinVariant variant) {
        return collectionId + ':' + skinId + ':' + variant.name();
    }

    void releaseScrollbar() { draggingAddSourceScrollbar = false; }

    void grabScrollbar(ViewSpec.Scrollbar scrollbar, double mouseX, double mouseY) {
        if (publicationBusy()) return;
        draggingAddSourceScrollbar = true;
        addSourceScrollbarGrabOffset = scrollbar.thumb().contains(mouseX, mouseY)
                ? mouseY - scrollbar.thumb().y() : scrollbar.thumb().height() / 2.0;
    }

    void resetClosedScroll() {
        addSourceScrollPosition = 0.0;
        addSourceScrollTarget = 0.0;
    }

    double addSourceScrollTarget() { return addSourceScrollTarget; }

    double addSourceScrollPosition() { return addSourceScrollPosition; }

    double addSourceScrollbarGrabOffset() { return addSourceScrollbarGrabOffset; }

    boolean draggingAddSourceScrollbar() { return draggingAddSourceScrollbar; }

    interface Context {
        CompletableFuture<CpmModelPublication.Result> publishCpmModel(CpmModelPublication.Request request, boolean consent);

        LibraryEditorPort.EditorSaveScope captureEditorSaveScope(ImportScope scope);
        ImportScope captureImportScope();
        boolean currentImportScope(ImportScope scope);
        boolean currentPublicationScope(ImportScope scope);
        boolean cpmPresent();
        Optional<CpmModelFileConversion> modelFileConversion();
        void acceptDraft(EditorDraftTransfer transfer);
        boolean busy();
        UiMessage status();
        AccountUiPreferences uiPreferences();
        AccountState account();
        UiPreferencesPort uiPreferencesPort();
        CatalogMaterialization catalogMaterialization();
        ImportOperations importOperations();
        LibraryEditorPort libraryEditorPort();
        Executor worker();
        boolean disposed();
        String pendingPresetName();
        TextResolver textResolver();
        PresetEditorModel editor();
        int viewportHeight();
        PreviewRenderer.CapeMode preferredCapeMode();
        FilePicker filePicker();
        int viewportWidth();
        PreviewAssets previewAssets();
        ViewChromeMetrics viewChromeMetrics();
        ClientSnapshot snapshot();
        boolean addSourceRoot();
        void closeScreenOnClient();
        ViewSpec withRuntimeFocus(ViewSpec view);
        void requestRuntimeFocus(String screenId, String widgetId);
        void clearRuntimeFocus(String screenId);
        void diagnose(DiagnosticEvent event, Throwable failure);
        void retainKeyboardFocus(
            InteractionOrigin origin, String screenId, String widgetId);
        void openExternalImport(ExternalImportModel.Category category);
        void persistUiPreference(ThrowingSupplier<Void> operation);
        Optional<com.naocraftlab.skins.core.model.RemoteProfile> editorProfile();
        List<com.naocraftlab.skins.core.model.OwnedCapeEntry> editorOwnedCapes();
        PresetEditorModel createEditor(UUID presetId);
        PresetEditorModel applyPendingPresetName(PresetEditorModel editor);
        void rememberPreferredSkinVariant(SkinVariant variant);
        <T> void submit(
            UiMessage progress, ThrowingSupplier<T> operation, Consumer<T> completion);
        <T> void submit(
            UiMessage progress,
            ThrowingSupplier<T> operation,
            Consumer<T> completion,
            Consumer<Throwable> failureCompletion);
        <T> void submit(
            UiMessage progress,
            ThrowingSupplier<T> operation,
            Consumer<T> completion,
            Consumer<Throwable> failureCompletion,
            Function<T, Optional<PresetApplicationOutcome>> completedOutcome);
        boolean current(ScreenOperationTicket ticket);
        void publish();
        void onClient(Runnable action);
        UiMessage fileImportFailure(Throwable failure);
        NormalizedSkin readPng(Path path);
        String publicImportFailureKey(Throwable failure, boolean player);
        Throwable unwrap(Throwable failure);
        Optional<PersonalCatalogAction> personalCatalogAction(
            String widgetId, String prefix);
        ScreenOperationTicket beginScreenOperation();
        ScreenOperationTicket captureScreenOperation();
        void finishScreenOperation(ScreenOperationTicket ticket);
        void cancelScreenOperation();
        void showFeedback(UiMessage message);
        void addSourceTabSelected(AddSourceTab tab);
        void catalogCollectionDisclosureChanged(String id, boolean collapsed);
        void catalogDisclosureChanged(Set<String> collapsed);
        void externalImportCompleted(ImportOperations.ExternalImportResult result);
        void personalSkinRenamed(AccountState account);
        void personalSkinDeleted(AccountState account);
    }

    private record ParentFrame(AddSourceModel model, UiMessage feedback, double scrollPosition, double scrollTarget) {}

    private static final class PublicationSettlement {
        private final ImportScope scope;
        private final CpmModelPublication.Request request;
        private final CompletableFuture<CpmModelPublication.Result> result;
        private volatile CpmModelPublication.Result value;
        private PublicationSettlement(ImportScope scope, CpmModelPublication.Request request,
                CompletableFuture<CpmModelPublication.Result> result) {
            this.scope = scope; this.request = request; this.result = result;
        }
    }

    record ImportScope(UUID accountId, long accountEpoch, long resourceEpoch, long worldEpoch) {}

    private static final class State {
        PreparationBatch preparation;
        PendingDependency dependency;
        final List<PublicationSettlement> settlements = new java.util.ArrayList<>();
        long preparationRevision;
        ImportScope operationScope;
        ImportWork work;
        ImportScope modelScope;
        volatile CpmModelFileConversion.AdmittedModel model;
        CpmModelFileConversion converter;
        String modelName;
        String publicationName;
        ParentFrame modelParent;
        ParentFrame modelChooser;
        boolean automaticBase;
        AddSourceModel addSource;
        volatile ExternalImportModel externalImport;
        String personalRenameCollectionId;
        String personalRenameHash;
        String personalRenameValue = "";
        final Map<String, SkinFeatureEvidence> catalogEvidence = new LinkedHashMap<>();
    }
}

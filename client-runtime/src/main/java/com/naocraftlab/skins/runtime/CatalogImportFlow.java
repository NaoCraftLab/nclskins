package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CatalogCollectionOrder;
import com.naocraftlab.skins.client.FilePicker;
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
        if (context.busy()
                || state.addSource == null
                || state.addSource.selectedTab() == tab) {
            return;
        }
        resetPersonalCatalogInteraction();
        state.addSource = state.addSource.withSelectedTab(tab);
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
        if (context.busy()
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
        SkinCatalogSource.SkinDescriptor skin = collection.skins().stream()
                .filter(value -> value.id().equals(skinId))
                .findFirst()
                .orElse(null);
        if (skin == null || !state.addSource.visibleSkins(collection).contains(skin)) {
            return;
        }
        SkinVariant initialVariant = state.addSource.selectedVariant(skin);
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

    void chooseAddSourcePng() {
        if (state.addSource == null
                || state.addSource.selectedTab() != AddSourceTab.FILE
                || context.busy()) {
            return;
        }
        ScreenOperationTicket ticket = context.beginScreenOperation();
        context.showFeedback(UiMessage.info("nclskins.status.choose_png"));
        context.publish();
        CompletableFuture<Optional<Path>> picked;
        try {
            picked = Objects.requireNonNull(context.filePicker().chooseSkinPng(), "picker future");
        } catch (RuntimeException unavailablePicker) {
            finishAddSourcePicker(ticket, null, unavailablePicker);
            return;
        }
        picked.whenComplete((selection, failure) -> context.onClient(() -> {
            if (!context.current(ticket) || state.addSource == null) {
                return;
            }
            if (failure != null || selection == null) {
                finishAddSourcePicker(ticket, null, failure);
            } else if (selection.isEmpty()) {
                context.finishScreenOperation(ticket);
                context.showFeedback(UiMessage.info("nclskins.status.cancelled"));
                context.publish();
            } else {
                Path path = selection.orElseThrow();
                CompletableFuture.supplyAsync(() -> context.readPng(path), context.worker())
                        .whenComplete((skin, pngFailure) -> context.onClient(() -> {
                            if (!context.current(ticket) || state.addSource == null) {
                                return;
                            }
                            context.finishScreenOperation(ticket);
                            if (pngFailure != null) {
                                context.diagnose(DiagnosticEvent.CLIENT_IMPORT_FAILED, pngFailure);
                                context.showFeedback(context.fileImportFailure(pngFailure));
                            } else {
                                String sourceName = UntrustedDisplayName.fromFileName(path.getFileName().toString(),
                                        context.textResolver().resolve(UiMessage.info("nclskins.editor.add_title")));
                                openImportedDraft(
                                        new ImportOperations.ImportDraft(
                                                sourceName,
                                                skin.detectedVariant(),
                                                skin.pngBytes(),
                                                PersonalSkinSource.FILE),
                                        sourceName + ".png",
                                        true);
                            }
                            context.publish();
                        }));
            }
        }));
    }

    void prepareExternalImport(ExternalImportSource source) {
        if (state.externalImport == null
                || context.busy()
                || !state.externalImport.available(source)) {
            return;
        }
        Optional<Path> root = state.externalImport.selectedRoot(source);
        context.submit(
                UiMessage.info("nclskins.external_import.preparing"),
                () -> context.importOperations().prepareExternalAppearances(source, root),
                review -> {
                    if (state.externalImport != null) {
                        state.externalImport = state.externalImport.withReview(review);
                        context.showFeedback(UiMessage.info("nclskins.external_import.review_ready"));
                    }
                },
                failure -> failExternalPreparation(source, failure));
    }

    void chooseExternalImportFolder(ExternalImportSource source) {
        if (state.externalImport == null
                || !state.externalImport.category().sources().contains(source)
                || context.busy()
                || state.externalImport.sources().get(source).availability()
                == ExternalImportModel.Availability.DEPENDENCY_MISSING) {
            return;
        }
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
        if (state.externalImport == null || state.externalImport.review().isEmpty() || context.busy()) {
            return;
        }
        ExternalImportModel.ReviewState review = state.externalImport.review().orElseThrow();
        List<ImportOperations.ExternalImportCandidate> selected = review.selectedCandidates();
        if (selected.isEmpty()) {
            return;
        }
        context.submit(
                UiMessage.info("nclskins.status.saving"),
                () -> context.importOperations().commitExternalAppearances(
                        selected, review.review().skipped(), review.review().warnings()),
                this::finishExternalImport,
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
        if (state.addSource == null || state.addSource.selectedTab() != AddSourceTab.FILE || context.busy()) {
            return;
        }
        String input = player ? state.addSource.playerInput() : state.addSource.urlInput();
        if (input.isBlank()) {
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
            context.diagnose(DiagnosticEvent.CLIENT_PICKER_FAILED, failure);
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
                        int normalized = addSourcePresenter.normalizedScrollOffset(
                                state.addSource,
                                context.viewportWidth(),
                                context.viewportHeight(),
                                state.addSource.scrollOffset(),
                                context.viewChromeMetrics());
                        state.addSource = state.addSource.withScrollOffset(normalized);
                        clampAddSourceScroll();
                    }
                    context.previewAssets().invalidateCatalogPreviews();
                    context.showFeedback(UiMessage.success("nclskins.your_skins.deleted"));
                },
                failure -> context.showFeedback(UiMessage.error("nclskins.your_skins.delete_failed")));
    }

    void cancelAddSource() {
        if (state.addSource != null && context.editor() == null) {
            if (context.busy() && state.addSource.selectedTab() != AddSourceTab.FILE) {
                return;
            }
            if (context.busy()) {
                context.cancelScreenOperation();
                context.showFeedback(UiMessage.info("nclskins.status.cancelled"));
            }
            if (context.addSourceRoot()) {
                context.closeScreenOnClient();
                return;
            }
            resetPersonalCatalogInteraction();
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

    void setAddSourceOffset(int offset) {
        if (state.addSource == null) {
            return;
        }
        int bounded = addSourcePresenter.normalizedScrollOffset(
                state.addSource,
                context.viewportWidth(),
                context.viewportHeight(),
                offset,
                context.viewChromeMetrics());
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
        if (state.addSource == null || !Double.isFinite(delta) || delta == 0.0) {
            return;
        }
        int maximum = addSourcePresenter.maximumScroll(
                state.addSource, context.viewportWidth(), context.viewportHeight(), context.viewChromeMetrics());
        double bounded = Math.max(
                0.0, Math.min(maximum, addSourceScrollPosition + delta));
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
        int maximum = addSourcePresenter.maximumScroll(
                state.addSource, context.viewportWidth(), context.viewportHeight(), context.viewChromeMetrics());
        addSourceScrollPosition = Math.max(0.0, Math.min(maximum, addSourceScrollPosition));
        addSourceScrollTarget = Math.max(0.0, Math.min(maximum, addSourceScrollTarget));
        state.addSource = state.addSource.withScrollOffset((int) Math.round(addSourceScrollPosition));
    }

    boolean dispatchCatalogImportAction(String widgetId, boolean reverse, InteractionOrigin origin) {
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
        state.addSource = null;
    }

    void leaveForDuplicate() {
        state.addSource = null;
    }

    void resetSession() {
        state.addSource = null;
        state.externalImport = null;
        state.personalRenameCollectionId = null;
        state.personalRenameHash = null;
        state.personalRenameValue = "";
        state.catalogEvidence.clear();
    }

    void closeScreens() {
        state.addSource = null;
        state.externalImport = null;
    }

    void navigateToOffset(int bounded) {
        state.addSource = state.addSource.withScrollOffset(bounded);
        addSourceScrollPosition = bounded;
        addSourceScrollTarget = bounded;
    }

    void searchCatalog(String value) {
        state.addSource = state.addSource.withQuery(value);
    }

    void editPlayerInput(String value) {
        state.addSource = state.addSource.withPlayerInput(value);
    }

    void editUrlInput(String value) {
        state.addSource = state.addSource.withUrlInput(value);
    }

    void editPersonalSkinName(String value) {
        state.personalRenameValue = value;
    }

    void scrollReviewTo(int offset) {
        state.externalImport = state.externalImport.withReviewScroll(offset);
    }

    void openExternalCategory(ExternalImportModel.Category category) {
        state.externalImport = ExternalImportModel.open(category);
    }

    void acceptAutomaticProbes(Map<ExternalImportSource, ExternalImportProbe> probes) {
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
        state.addSource = AddSourceModel.open(
                        data.preferences(), data.collections(), fallbackVariant, context.textResolver())
                .withCompatibilityContext(catalogEnvironment, state.catalogEvidence, hideIncompatibleCatalogSkins);
        resetAddSourceScroll();
    }

    void openEmptyCatalog(
            AccountUiPreferences cachedPreferences,
            SkinVariant fallbackVariant,
            SkinExtensionEnvironment catalogEnvironment,
            boolean hideIncompatibleCatalogSkins) {
        state.addSource = AddSourceModel.open(
                        cachedPreferences, List.of(), fallbackVariant, context.textResolver())
                .withCompatibilityContext(catalogEnvironment, Map.of(), hideIncompatibleCatalogSkins);
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

    AddSourcePresenter addSourcePresenter() { return addSourcePresenter; }

    interface Context {
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
        PreviewAssetLoader previewAssets();
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

    private static final class State {
        AddSourceModel addSource;
        ExternalImportModel externalImport;
        String personalRenameCollectionId;
        String personalRenameHash;
        String personalRenameValue = "";
        final Map<String, SkinFeatureEvidence> catalogEvidence = new LinkedHashMap<>();
    }
}

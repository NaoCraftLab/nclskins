package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.compatibility.SkinCompatibility;
import com.naocraftlab.skins.core.compatibility.SkinCompatibilityEvaluator;
import com.naocraftlab.skins.core.compatibility.SkinCompatibilityStatus;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.EditorTab;
import com.naocraftlab.skins.core.model.LocalCapeReference;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.NormalizedSkin;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

final class EditorFlow {
    private final State state = new State();
    private final Context context;
    private long editorCatalogGeneration;
    private BuiltinProvider pendingCapeProviderInspection;
    private CompletableFuture<Void> capeDisclosureWrite = CompletableFuture.completedFuture(null);
    private long capeDisclosureSequence;
    private CompletableFuture<Void> editorTabPreferenceWrite = CompletableFuture.completedFuture(null);
    private long editorTabPreferenceSequence;
    private boolean draggingEditorScrollbar;
    private double editorScrollbarGrabOffset;
    private double editorModelScrollPosition;
    private double editorCapeScrollPosition;
    private double editorCapeScrollTarget;

    EditorFlow(Context context) { this.context = Objects.requireNonNull(context, "context"); }

    boolean editorPreviewStillCurrent(ViewSpec.Preview preview) {
        return editorEvidenceStillCurrent(preview.imageRevision(), preview.variant());
    }

    void cycleEditorOuterLayer(String action, boolean reverse) {
        switch (action) {
            case "head", "body", "legs" -> updateEditor(
                    editor -> editor.cycleOuterLayer(action, reverse ? -1 : 1));
            default -> {

            }
        }
    }

    void selectEditorTab(EditorTab tab) {
        if (state.editor == null || state.editor.busy()) {
            return;
        }
        pendingCapeProviderInspection = null;
        state.editor = state.editor.withSelectedEditorTab(tab);
        draggingEditorScrollbar = false;
        context.clearRuntimeFocus("preset_editor");
        if (context.account() != null) {
            UUID accountId = context.account().accountId();
            context.editorTabSelected(tab);
            ScreenOperationTicket ticket = context.captureScreenOperation();
            long preferenceSequence = ++editorTabPreferenceSequence;
            editorTabPreferenceWrite = editorTabPreferenceWrite.handle((ignored, failure) -> null).thenRunAsync(() -> {
                try {
                    context.uiPreferencesPort().setSelectedEditorTab(accountId, tab);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(failure);
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            }, context.worker()).whenComplete((ignored, failure) -> context.clientExecutor().execute(() -> {
                if (failure != null && preferenceSequence == editorTabPreferenceSequence
                        && context.current(ticket) && context.account() != null
                        && context.account().accountId().equals(accountId) && state.editor != null) {
                    context.diagnose(DiagnosticEvent.CLIENT_PREFERENCES_SAVE_FAILED, failure);
                    state.editor = state.editor.withPreviewFailure(UiMessage.error("nclskins.error.save"));
                    context.publish();
                }
            }));
        }
        context.publish();
    }

    Optional<com.naocraftlab.skins.core.model.RemoteProfile> editorProfile() {
        return context.providers().cape().enabled(BuiltinProvider.MINECRAFT)
                ? Optional.ofNullable(context.remoteProfile()) : Optional.empty();
    }

    List<com.naocraftlab.skins.core.model.OwnedCapeEntry> editorOwnedCapes() {
        return context.providers().cape().enabled(BuiltinProvider.MINECRAFT) && context.ownedCapes() != null
                ? context.ownedCapes().capes() : List.of();
    }

    PresetEditorModel createEditor(UUID presetId) {
        Optional<AppearancePreset> preset = presetId == null
                ? Optional.empty()
                : context.account().presets().stream().filter(value -> value.id().equals(presetId)).findFirst();
        if (presetId != null && preset.isEmpty()) {
            return null;
        }
        try {
            return PresetEditorModel.open(
                    context.account(),
                    preset,
                    editorProfile(),
                    Optional.ofNullable(context.activePresetId()),
                    context.textResolver(),
                    context.viewportHeight(),
                    context.preferredCapeMode(),
                    context.preferredSkinVariant(),
                    editorOwnedCapes());
        } catch (IllegalStateException missingBundledSkin) {
            context.diagnose(DiagnosticEvent.CLIENT_BUNDLED_SKIN_MISSING, missingBundledSkin);
            return null;
        }
    }

    boolean dispatchCapeCatalog(String id, boolean reverse, InteractionOrigin origin) {
        CapeCatalogModel catalog = state.editor.capeCatalog();
        UUID capeAccountId = context.account().accountId();
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
                        ViewSpec view = context.view(context.viewportWidth(), context.viewportHeight(), 0, 0);
                        view.navigationNode(focusId).ifPresent(node -> ViewNavigationPolicy.ensureVisibleOffset(view, node)
                                .ifPresent(offset -> context.applyNavigationScroll(node, offset)));
                        context.requestRuntimeFocus("preset_editor", focusId);
                    }
                }
                case "cancel" -> {
                    updateEditor(editor -> editor.withCapeCatalog(catalog.cancelEdit()));
                    if (origin == InteractionOrigin.KEYBOARD) context.requestRuntimeFocus("preset_editor", "editor.cape_item.OFFLINE." + entry);
                }
                case "save" -> {
                    if (!catalog.renameValue().trim().isEmpty()) {
                        context.submit(UiMessage.info("nclskins.status.saving"),
                                () -> context.libraryEditorPort().renameCape(capeAccountId, entry,
                                        UntrustedDisplayName.sanitize(catalog.renameValue(), "")), account -> {
                                    context.capeRenamed(account);
                                    refreshCapeCatalog(catalog.offline(), catalog.minecraft());
                                    state.editor = state.editor.withCapeCatalog(
                                            state.editor.capeCatalog().cancelEdit());
                                    if (origin == InteractionOrigin.KEYBOARD) {
                                        context.requestRuntimeFocus("preset_editor", "editor.cape_item.OFFLINE." + entry);
                                    }
                                });
                    }
                }
                case "confirm" -> context.submit(UiMessage.info("nclskins.status.saving"), () -> context.libraryEditorPort().deleteCape(capeAccountId, entry), result -> {
                    context.capeDeleted(result);
                    refreshCapeCatalog(catalog.offline() != null && entry.equals(catalog.offline().entryId()) ? null : catalog.offline(), catalog.minecraft());
                    editorCapeScrollPosition = state.editor.normalizedCapeScrollPosition(
                            context.viewportWidth(), context.viewportHeight(), editorCapeScrollPosition, context.viewChromeMetrics());
                    editorCapeScrollTarget = editorCapeScrollPosition;
                    if (origin == InteractionOrigin.KEYBOARD) context.requestRuntimeFocus("preset_editor", "editor.cape_item.OFFLINE.none");
                });
                default -> { }
            }
        } else return false;
        if (id.equals("editor.cape_disclosure") || id.startsWith("editor.cape_header.")) {
            persistCapeDisclosure(catalog.collapsed(), state.editor.capeCatalog().collapsed());
        }
        editorCapeScrollPosition = state.editor.normalizedCapeScrollPosition(
                context.viewportWidth(), context.viewportHeight(), editorCapeScrollPosition, context.viewChromeMetrics());
        editorCapeScrollTarget = editorCapeScrollPosition;
        context.publish();
        return true;
    }

    void persistCapeDisclosure(Set<String> before, Set<String> after) {
        if (before.equals(after)) return;
        UUID accountId = context.account().accountId();
        Set<String> values = Set.copyOf(after);
        context.capeDisclosureChanged(values);
        ScreenOperationTicket ticket = context.captureScreenOperation();
        long sequence = ++capeDisclosureSequence;
        capeDisclosureWrite = capeDisclosureWrite.handle((ignored, failure) -> null).thenRunAsync(() -> {
            try { context.uiPreferencesPort().setCollapsedCapeCollections(accountId, values); }
            catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                context.diagnose(DiagnosticEvent.CLIENT_PREFERENCES_SAVE_FAILED, failure);
                context.onClient(() -> {
                    if (!context.current(ticket) || capeDisclosureSequence != sequence || state.editor == null || state.editor.capeCatalog() == null) return;
                    context.capeDisclosureChanged(before);
                    state.editor = state.editor.withCapeCatalog(state.editor.capeCatalog().withCollapsed(before));
                    context.publish();
                });
            }
        }, context.worker());
    }

    void reloadEditorCatalog() {
        if (state.editor == null || context.account() == null) return;
        UUID accountId = context.account().accountId();
        long generation = ++editorCatalogGeneration;
        ScreenOperationTicket ticket = context.captureScreenOperation();
        CompletableFuture.supplyAsync(() -> {
            try { return context.catalogRead().loadCapeEditorData(accountId); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }, context.worker()).whenComplete((data, failure) -> context.onClient(() -> {
            if (!context.current(ticket) || generation != editorCatalogGeneration || state.editor == null
                    || !accountId.equals(context.account().accountId())) return;
            if (failure != null) { context.diagnose(DiagnosticEvent.CLIENT_ASYNC_OPERATION_FAILED, failure); return; }
            var previous = state.editor.capeCatalog();
            context.capeCatalogLoaded(data);
            var local = previous.offline();
            if (local != null && local.entryId() != null) {
                var reference = local;
                if (context.account().personalCapes().stream().noneMatch(entry -> entry.texture().equals(reference))) local = null;
            }
            var fresh = CapeCatalogModel.open(context.account(), context.providers(), local,
                    previous.minecraft(), state.editor.capeChoices(), data.resourceCollections(),
                    data.sourceHashes(), data.resourceGeneration(), context.textResolver());
            var inspection = previous.inspected() == null ? fresh.inspected() : fresh.cards().stream()
                    .filter(card -> card.widgetId().equals(previous.inspected().widgetId())).findFirst().orElse(null);
            state.editor = state.editor.withCapeCatalog(new CapeCatalogModel(fresh.cards(), fresh.providers(), fresh.offline(), previous.minecraft(),
                    previous.query(), previous.filter(), previous.collapsed(), inspection, previous.editing(), previous.deleting(), previous.renameValue(), previous.importError(), context.textResolver()));
            if (pendingCapeProviderInspection != null) {
                state.editor = state.editor.withCapeCatalog(
                        state.editor.capeCatalog().inspect(pendingCapeProviderInspection, context.account()));
                pendingCapeProviderInspection = null;
                editorCapeScrollPosition = state.editor.initialCapeScrollPosition(
                        context.viewportWidth(), context.viewportHeight(), context.viewChromeMetrics());
            } else {
                editorCapeScrollPosition = state.editor.normalizedCapeScrollPosition(
                        context.viewportWidth(), context.viewportHeight(), editorCapeScrollPosition, context.viewChromeMetrics());
            }
            editorCapeScrollTarget = editorCapeScrollPosition;
            context.publish();
        }));
    }

    void refreshCapeCatalog(com.naocraftlab.skins.core.model.LocalCapeReference local, Optional<String> owned) {
        if (state.editor == null) return;
        var previous = state.editor.capeCatalog();
        var fresh = previous.refreshed(
                context.account(), context.providers(), local, owned, state.editor.capeChoices());
        state.editor = state.editor.withCapeCatalog(fresh);
    }

    void chooseCapePng() {
        if (state.editor == null || state.editor.busy()) return;
        UUID accountId = context.account().accountId();
        String fallbackName = context.textResolver().resolve(UiMessage.info("options.modelPart.cape"));
        ScreenOperationTicket ticket = context.beginScreenOperation();
        state.editor = state.editor.withBusyWithoutStatus();
        context.publish();
        CompletableFuture<Optional<Path>> picked;
        try { picked = java.util.Objects.requireNonNull(context.filePicker().chooseCapePng()); }
        catch (RuntimeException failure) {
            context.finishScreenOperation(ticket);
            state.editor = state.editor.withoutStatus().withCapeCatalog(state.editor.capeCatalog().withImportError(UiMessage.error("nclskins.error.picker")));
            context.publish();
            return;
        }
        picked.whenComplete((selection, failure) -> context.onClient(() -> {
            if (!context.current(ticket) || state.editor == null) return;
            context.finishScreenOperation(ticket);
            state.editor = state.editor.withoutStatus();
            if (failure != null) {
                state.editor = state.editor.withoutStatus().withCapeCatalog(state.editor.capeCatalog().withImportError(UiMessage.error("nclskins.error.picker")));
                context.publish();
            } else if (selection != null && selection.isPresent()) {
                state.editor = state.editor.withBusyWithoutStatus();
                Set<String> resourceIdentities = state.editor.capeCatalog().cards().stream()
                        .map(CapeCatalogModel.Card::resource)
                        .filter(Objects::nonNull)
                        .map(CatalogRead.ResourceCapeSelection::contentIdentity)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                context.submit(UiMessage.info("nclskins.status.saving"), () -> {
                    var entry = context.libraryEditorPort().importCape(
                            accountId, selection.orElseThrow(), fallbackName);
                    Optional<AccountState> account = resourceIdentities.contains(entry.renderSha256())
                            ? context.libraryEditorPort().discardCapeIfUnreferenced(
                                    accountId, entry.texture().entryId())
                            : Optional.empty();
                    return new CapeImportResult(entry, account);
                }, result -> {
                    if (state.editor == null) return;
                    state.editor = state.editor.withoutStatus();
                    var entry = result.entry();
                    context.capeImported(result);
                    refreshCapeCatalog(entry.texture(), state.editor.capeId());
                    var catalog = state.editor.capeCatalog().withQuery("");
                    var selected = catalog.resourceOwner(entry.renderSha256()).orElseGet(() ->
                            catalog.cards().stream().filter(card -> entry.texture().equals(card.local()))
                                    .findFirst().orElseThrow());
                    state.editor = state.editor.withCapeCatalog(catalog.reveal(selected).choose(selected));
                    persistCapeDisclosure(catalog.collapsed(), state.editor.capeCatalog().collapsed());
                    editorCapeScrollPosition = state.editor.initialCapeScrollPosition(
                            context.viewportWidth(), context.viewportHeight(), context.viewChromeMetrics());
                    editorCapeScrollTarget = editorCapeScrollPosition;
                }, invalid -> {
                    if (state.editor != null) {
                        state.editor = state.editor.withoutStatus();
                        if (context.unwrap(invalid) instanceof com.naocraftlab.skins.core.png.PngValidationException) {
                            state.editor = state.editor.withCapeCatalog(state.editor.capeCatalog().error(true));
                        } else state.editor = state.editor.withCapeCatalog(state.editor.capeCatalog().withImportError(UiMessage.error("nclskins.capes.io_error")));
                    }
                });
            }
            context.publish();
        }));
    }

    void chooseEditorPng() {
        if (state.editor == null || state.editor.busy()) {
            return;
        }
        ScreenOperationTicket ticket = context.beginScreenOperation();
        state.editor = state.editor.withBusy(UiMessage.info("nclskins.status.choose_png"));
        context.publish();
        CompletableFuture<Optional<Path>> picked;
        try {
            picked = Objects.requireNonNull(context.filePicker().chooseSkinPng(), "picker future");
        } catch (RuntimeException unavailablePicker) {
            finishEditorPicker(ticket, null, unavailablePicker);
            return;
        }
        picked.whenComplete((selection, failure) -> context.onClient(() -> {
            if (!context.current(ticket) || state.editor == null) {
                return;
            }
            if (failure != null || selection == null) {
                finishEditorPicker(ticket, null, failure);
            } else if (selection.isEmpty()) {
                context.finishScreenOperation(ticket);
                state.editor = state.editor.withStatus(UiMessage.info("nclskins.status.cancelled"));
                context.publish();
            } else {
                Path path = selection.orElseThrow();
                CompletableFuture.supplyAsync(() -> context.readPng(path), context.worker())
                        .whenComplete((skin, pngFailure) -> context.onClient(() -> {
                            if (!context.current(ticket) || state.editor == null) {
                                return;
                            }
                            context.finishScreenOperation(ticket);
                            if (pngFailure != null) {
                                context.diagnose(DiagnosticEvent.CLIENT_IMPORT_FAILED, pngFailure);
                                state.editor = state.editor.withStatus(context.fileImportFailure(pngFailure));
                            } else {
                                state.editor = state.editor.withImportedPng(
                                        path.getFileName().toString(),
                                        skin.pngBytes(),
                                        skin.detectedVariant());
                                prepareEditorEvidence(state.editor);
                                context.rememberPreferredSkinVariant(skin.detectedVariant());
                            }
                            context.publish();
                        }));
            }
        }));
    }

    void finishEditorPicker(ScreenOperationTicket ticket, byte[] ignored, Throwable failure) {
        context.onClient(() -> {
            if (!context.current(ticket) || state.editor == null) {
                return;
            }
            context.diagnose(DiagnosticEvent.CLIENT_PICKER_FAILED, failure);
            context.finishScreenOperation(ticket);
            state.editor = state.editor.withStatus(UiMessage.error("nclskins.error.picker"));
            context.publish();
        });
    }

    void saveEditor() {
        if (state.editor == null || state.editor.busy() || state.editor.name().trim().isEmpty()) {
            return;
        }
        PresetEditorModel draft = state.editor;
        UUID editorAccountId = context.account().accountId();
        PersonalSkinSource personalSource = state.editorPersonalSource;
        state.editor = draft.withBusy(UiMessage.info("nclskins.status.saving"));
        context.submit(
                UiMessage.info("nclskins.status.saving"),
                () -> {
                    LibraryEditorPort.EditorSaveRequest request = draft.saveRequest();
                    com.naocraftlab.skins.core.model.LocalCapeReference offlineCape =
                            request.offlineCape();
                    Optional<CatalogRead.ResourceCapeSelection> resourceCape =
                            draft.capeCatalog() == null
                                    ? Optional.empty()
                                    : draft.capeCatalog().selectedResource();
                    if (resourceCape.isPresent()) {
                        offlineCape = context.catalogMaterialization().materializeResourceCape(
                                editorAccountId, resourceCape.orElseThrow()).texture();
                    }
                    return context.libraryEditorPort().saveEditor(new LibraryEditorPort.EditorSaveRequest(
                            request.originalPresetId(), request.name(), request.skin(),
                            request.initialVariant(), request.variant(), request.capeId(),
                            request.outerLayerVisibility(), request.pngBytes(), request.catalogOrigin(), request.personalSkinName(),
                            personalSource).withOfflineCape(offlineCape).withFrozenCatalogSelection(request.frozenCatalogSelection()));
                },
                saved -> {
                    state.editor = null;
                    state.editorEvidence = null;
                    state.editorPersonalSource = PersonalSkinSource.FILE;
                    state.pendingPresetName = null;
                    context.editorSaved(new SaveCompletion(saved,
                            draft.capeCatalog() != null && draft.capeCatalog().offline() != null));
                },
                failure -> {
                    if (state.editor != null) {
                        UiMessage saveFailure = UiMessage.error("nclskins.error.save");
                        state.editor = state.editor.withStatus(saveFailure);
                        context.showFeedback(saveFailure);
                    }
                });
    }

    void cancelEditor() {
        if (state.editor != null && !state.editor.busy()) {
            state.editor = null;
            state.editorEvidence = null;
            context.returnFromEditor();
            resetEditorScroll();
            context.publish();
        }
    }

    PresetEditorModel applyPendingPresetName(PresetEditorModel editor) {
        return state.pendingPresetName == null ? editor : editor.withName(state.pendingPresetName);
    }

    void reportEditorPreviewFailure(
            ViewSpec.Preview failed, String translationKey, boolean capeFailure) {
        Objects.requireNonNull(failed, "failed");
        Objects.requireNonNull(translationKey, "translationKey");
        context.onClient(() -> {
            if (context.disposed() || state.editor == null || !"editor.preview".equals(failed.id())) {
                return;
            }
            ViewSpec.Preview current = state.editor.present(
                            context.viewportWidth(),
                            context.viewportHeight(),
                            editorCapeScrollPosition,
                            editorModelScrollPosition,
                            context.viewChromeMetrics())
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
            context.publish();
        });
    }

    void clearEditorPreviewFailure(
            ViewSpec.Preview loaded, String translationKey, boolean capeFailure) {
        Objects.requireNonNull(loaded, "loaded");
        Objects.requireNonNull(translationKey, "translationKey");
        context.onClient(() -> {
            if (context.disposed() || state.editor == null || !"editor.preview".equals(loaded.id())) {
                return;
            }
            ViewSpec.Preview current = state.editor.present(
                            context.viewportWidth(),
                            context.viewportHeight(),
                            editorCapeScrollPosition,
                            editorModelScrollPosition,
                            context.viewChromeMetrics())
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
            context.publish();
        });
    }

    void queueEditorContentScroll(double pixelDelta) {
        if (state.editor == null || !Double.isFinite(pixelDelta) || pixelDelta == 0.0) {
            return;
        }
        setEditorContentPosition((state.editor.selectedEditorTab() == EditorTab.APPEARANCE
                ? editorModelScrollPosition : editorCapeScrollPosition) + pixelDelta);
    }

    double editorPositionFromScrollbar(int width, int height, double top) {
        return state.editor.selectedEditorTab() == EditorTab.APPEARANCE
                ? state.editor.modelPositionFromScrollbar(width, height, top)
                : state.editor.capePositionFromScrollbar(width, height, top, context.viewChromeMetrics());
    }

    void setEditorContentPosition(double position) {
        if (state.editor == null) {
            return;
        }
        if (state.editor.selectedEditorTab() == EditorTab.APPEARANCE) {
            double bounded = state.editor.normalizedModelScrollPosition(context.viewportWidth(), context.viewportHeight(), position);
            if (Math.abs(bounded - editorModelScrollPosition) > 0.001) {
                editorModelScrollPosition = bounded;
                context.publish();
            }
            return;
        }
        double bounded = state.editor.normalizedCapeScrollPosition(
                context.viewportWidth(), context.viewportHeight(), position, context.viewChromeMetrics());
        if (Math.abs(bounded - editorCapeScrollPosition) > 0.001
                || Math.abs(bounded - editorCapeScrollTarget) > 0.001) {
            editorCapeScrollPosition = bounded;
            editorCapeScrollTarget = bounded;
            context.publish();
        }
    }

    void resetEditorScroll() {
        pendingCapeProviderInspection = null;
        editorModelScrollPosition = 0.0;
        editorTabPreferenceSequence++;
        context.clearRuntimeFocus("preset_editor");
        if (state.editor != null) {
            if (state.editor.capeCatalog() != null && context.uiPreferences() != null) {
                state.editor = state.editor.withCapeCatalog(state.editor.capeCatalog()
                        .withCollapsed(context.uiPreferences().collapsedCapeCollections()));
            }
            boolean newPreset = state.editor.originalPresetId().isEmpty();
            state.editor = state.editor.withSelectedEditorTab(newPreset || context.uiPreferences() == null
                    ? EditorTab.APPEARANCE : context.uiPreferences().selectedEditorTab());
            if (newPreset) {
                context.requestRuntimeFocus("preset_editor", "editor.name");
            }
        }
        draggingEditorScrollbar = false;
        editorCapeScrollPosition = state.editor == null
                ? 0.0
                : state.editor.initialCapeScrollPosition(
                        context.viewportWidth(), context.viewportHeight(), context.viewChromeMetrics());
        editorCapeScrollTarget = editorCapeScrollPosition;
        reloadEditorCatalog();
    }

    void initializeEditorCapeCatalog(LocalCapeReference offlineSeed) {
        if (state.editor == null || context.account() == null) {
            return;
        }
        UUID accountId = context.account().accountId();
        Optional<CatalogRead.CapeEditorData> warmed =
                context.catalogRead().warmedCapeEditorData(accountId);
        state.editor = state.editor.withCapeCatalog(warmed
                .map(data -> CapeCatalogModel.open(
                        context.account(), context.providers(), offlineSeed, state.editor.capeId(),
                        state.editor.capeChoices(), data.resourceCollections(), data.sourceHashes(),
                        data.resourceGeneration(), context.textResolver()))
                .orElseGet(() -> CapeCatalogModel.open(
                        context.account(), context.providers(), offlineSeed, state.editor.capeId(),
                        state.editor.capeChoices(), context.textResolver())));
        context.installWarmedCapePreviews(accountId, true);
    }

    boolean clampEditorScroll() {
        double modelPosition = state.editor.normalizedModelScrollPosition(
                context.viewportWidth(), context.viewportHeight(), editorModelScrollPosition);
        boolean modelChanged = Math.abs(modelPosition - editorModelScrollPosition) > 0.001;
        editorModelScrollPosition = modelPosition;
        double position = state.editor.normalizedCapeScrollPosition(
                context.viewportWidth(), context.viewportHeight(), editorCapeScrollPosition, context.viewChromeMetrics());
        double target = state.editor.normalizedCapeScrollPosition(
                context.viewportWidth(), context.viewportHeight(), editorCapeScrollTarget, context.viewChromeMetrics());
        boolean changed = Math.abs(position - editorCapeScrollPosition) > 0.001
                || Math.abs(target - editorCapeScrollTarget) > 0.001;
        editorCapeScrollPosition = position;
        editorCapeScrollTarget = target;
        return changed || modelChanged;
    }

    void updateEditor(java.util.function.UnaryOperator<PresetEditorModel> update) {
        if (state.editor == null) {
            return;
        }
        state.editor = Objects.requireNonNull(update.apply(state.editor), "editor update");
        context.publish();
    }

    void selectEditorVariant(SkinVariant variant) {
        if (state.editor == null || state.editor.selectedEditorTab() != EditorTab.APPEARANCE) {
            return;
        }
        SkinVariant before = state.editor.variant();
        state.editor = state.editor.selectVariant(variant);
        if (state.editor.variant() != before) {
            prepareEditorEvidence(state.editor);
            context.rememberPreferredSkinVariant(state.editor.variant());
        }
        context.publish();
    }

    void prepareEditorEvidence(PresetEditorModel editor) {
        Objects.requireNonNull(editor, "editor");
        state.editorEvidence = null;
        Optional<UUID> assetId = editor.skin().optionalAssetId();
        if (editor.png().isPresent()) {
            try {
                SkinFeatureEvidence evidence = context.analyzeImportedSkinFeatureEvidence(
                        editor.png().orElseThrow().bytes());
                state.editorEvidence = evidence;
            } catch (PngValidationException ignored) {
            }
            return;
        }
        if (assetId.isEmpty()) {
            return;
        }
        SkinFeatureEvidence cached = context.cachedAssetEvidence(assetId.orElseThrow());
        if (cached != null) {
            state.editorEvidence = cached;
            return;
        }
        String revision = editorEvidenceRevision(editor);
        SkinVariant variant = editor.variant();
        CompletableFuture<Optional<byte[]>> loaded = context.loadSkinPreview(editor.skin());
        loaded.whenComplete((bytes, failure) -> {
            if (failure != null || bytes == null || bytes.isEmpty()) {
                return;
            }
            try {
                SkinFeatureEvidence evidence = context.analyzeStoredSkinFeatureEvidence(
                        bytes.orElseThrow());
                context.onClient(() -> {
                    boolean changed = context.acceptAssetEvidence(assetId.orElseThrow(), evidence);
                    if (editorEvidenceStillCurrent(revision, variant)) {
                        changed |= !evidence.equals(state.editorEvidence);
                        state.editorEvidence = evidence;
                    }
                    if (changed && !context.disposed()) {
                        context.publish();
                    }
                });
            } catch (PngValidationException ignored) {
            }
        });
    }

    boolean editorEvidenceStillCurrent(String revision, SkinVariant variant) {
        PresetEditorModel editor = state.editor;
        return editor != null
                && editorEvidenceRevision(editor).equals(revision)
                && editor.variant() == variant;
    }

    String editorEvidenceRevision(PresetEditorModel editor) {
        return editor.png()
                .map(PresetEditorModel.DraftPng::revision)
                .orElseGet(() -> editor.skin().optionalAssetId()
                        .map(id -> "asset:" + id)
                        .orElse("current-player"));
    }

    boolean dispatchEditorAction(String widgetId, boolean reverse, InteractionOrigin origin) {
        if (widgetId.startsWith("editor.outer_layer.")) {
            cycleEditorOuterLayer(
                    widgetId.substring("editor.outer_layer.".length()), reverse);
            return true;
        }
        if (state.editor != null && state.editor.capeCatalog() != null) {
            state.editor = state.editor.withCapeCatalog(state.editor.capeCatalog().error(false));
            if (dispatchCapeCatalog(widgetId, reverse, origin)) return true;
        }
        if (widgetId.startsWith("editor.cape_choice.")) {
            try {
                int index = Integer.parseInt(widgetId.substring("editor.cape_choice.".length()));
                updateEditor(editor -> editor.selectCape(index));
            } catch (NumberFormatException ignored) {

            }
            return true;
        }
        switch (widgetId) {
            case "editor.tab.appearance", "editor.tab.cape" -> {
                selectEditorTab(widgetId.endsWith("appearance") ? EditorTab.APPEARANCE : EditorTab.CAPE);
                context.retainKeyboardFocus(origin, "preset_editor", widgetId);
                return true;
            }
            case "editor.model_choice.classic" -> {
                selectEditorVariant(SkinVariant.CLASSIC);
                return true;
            }
            case "editor.model_choice.slim" -> {
                selectEditorVariant(SkinVariant.SLIM);
                return true;
            }
            case "editor.cape" -> {
                updateEditor(editor -> editor.cycleCape(reverse ? -1 : 1));
                return true;
            }
            case "editor.preview_mode" -> {
                updateEditor(editor -> editor.cyclePreviewMode(reverse ? -1 : 1));
                if (state.editor != null) {
                    context.editorPreviewModeChanged(state.editor.preview().capeMode());
                }
                return true;
            }
            case "editor.save" -> {
                saveEditor();
                return true;
            }
            case "editor.cancel" -> {
                cancelEditor();
                return true;
            }
            default -> { return false; }
        }
    }

    ViewSpec presentEditor(int width, int height) {
        PresetEditorModel editor = state.editor;
            ViewSpec editorView = editor.present(
                    width, height, editorCapeScrollPosition, editorModelScrollPosition, context.viewChromeMetrics());
            SkinFeatureEvidence evidence = state.editorEvidence;
            if (evidence != null) {
                SkinCompatibility compatibility = new SkinCompatibilityEvaluator().evaluate(
                        evidence, context.snapshot().skinExtensionEnvironment());
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
            return context.withRuntimeFocus(editorView);
            }

    void acceptDraft(EditorDraftTransfer transfer) {
        state.editor = transfer.draft();
        initializeEditorCapeCatalog(transfer.capeSeed());
        prepareEditorEvidence(state.editor);
        resetEditorScroll();
        transfer.source().ifPresent(source -> state.editorPersonalSource = source);
    }

    void acceptPreviewMode(PreviewRenderer.CapeMode mode) {
        if (state.editor != null) state.editor = state.editor.withPreview(state.editor.preview().withCapeMode(mode));
    }

    record SaveCompletion(LibraryEditorPort.EditorSave saved, boolean hadOfflineCape) {}

    void inspectProviderCape(BuiltinProvider provider) {
        selectEditorTab(EditorTab.CAPE);
        if (state.editor == null) return;
        if (state.editor.capeCatalog() != null) {
            state.editor = state.editor.withCapeCatalog(state.editor.capeCatalog().inspect(provider, context.account()));
            var inspected = state.editor.capeCatalog().inspected();
            pendingCapeProviderInspection = inspected != null && inspected.resource() == null ? provider : null;
            editorCapeScrollPosition = state.editor.initialCapeScrollPosition(
                    context.viewportWidth(), context.viewportHeight(), context.viewChromeMetrics());
            editorCapeScrollTarget = editorCapeScrollPosition;
        }
    }

    void resetSession() {
        state.editor = null;
        state.editorPersonalSource = PersonalSkinSource.FILE;
        state.pendingPresetName = null;
        state.editorEvidence = null;
    }

    void closeDraft() {
        state.editor = null;
        state.editorEvidence = null;
    }

    void renameDraft(String value) {
        state.editor = state.editor.withName(value);
    }

    void clearCapeImportError() {
        state.editor = state.editor.withCapeCatalog(state.editor.capeCatalog().error(false));
    }

    void beginPreviewRotation(Bounds previewBounds, double mouseX, double mouseY) {
        state.editor = state.editor.withPreview(
                        state.editor.preview().beginRotate(previewBounds, mouseX, mouseY));
    }

    void dragPreview(double deltaX, double deltaY) {
        state.editor = state.editor.withPreview(state.editor.preview().drag(deltaX, deltaY));
    }

    void endPreviewRotation() {
        state.editor = state.editor.withPreview(state.editor.preview().endRotate());
    }

    void acceptScrolledPreview(PreviewInteractionModel changed) {
        state.editor = state.editor.withPreview(changed);
    }

    boolean acceptPreviewEvidence(SkinFeatureEvidence evidence) {
        boolean changed = !evidence.equals(state.editorEvidence);
        state.editorEvidence = evidence;
        return changed;
    }

    void ownedCapesLoaded(OwnedCapeInventory value) {
        if (state.editor != null && state.editor.capeCatalog() != null) {
            state.editor = state.editor.withCapeCatalog(
                    state.editor.capeCatalog().withOwnedClassification(value.capes()));
        }
    }

    void prepareNewPresetName(String name) {
        state.pendingPresetName = name == null || name.isBlank() ? null : name;
    }

    PresetEditorModel editor() { return state.editor; }

    String pendingPresetName() { return state.pendingPresetName; }

    void releaseScrollbar() { draggingEditorScrollbar = false; }

    void grabScrollbar(ViewSpec.Scrollbar scrollbar, double mouseX, double mouseY) {
        draggingEditorScrollbar = true;
        editorScrollbarGrabOffset = scrollbar.thumb().contains(mouseX, mouseY)
                ? mouseY - scrollbar.thumb().y() : scrollbar.thumb().height() / 2.0;
    }

    void navigateModelsTo(double offsetPixels) {
        editorModelScrollPosition = state.editor.normalizedModelScrollPosition(
                context.viewportWidth(), context.viewportHeight(), offsetPixels);
    }

    void navigateCapesTo(double offsetPixels) {
        double bounded = state.editor.normalizedCapeScrollPosition(
                context.viewportWidth(), context.viewportHeight(), offsetPixels, context.viewChromeMetrics());
        editorCapeScrollPosition = bounded;
        editorCapeScrollTarget = bounded;
    }

    void resetCapeSearchScroll() {
        editorCapeScrollPosition = 0;
        editorCapeScrollTarget = 0;
    }

    double editorCapeScrollPosition() { return editorCapeScrollPosition; }

    double editorModelScrollPosition() { return editorModelScrollPosition; }

    double editorScrollbarGrabOffset() { return editorScrollbarGrabOffset; }

    boolean draggingEditorScrollbar() { return draggingEditorScrollbar; }

    interface Context {
        AccountState account();
        AccountUiPreferences uiPreferences();
        UiPreferencesPort uiPreferencesPort();
        LibraryEditorPort libraryEditorPort();
        CatalogRead catalogRead();
        CatalogMaterialization catalogMaterialization();
        Executor worker();
        ClientExecutor clientExecutor();
        AppearanceProviders providers();
        RemoteProfile remoteProfile();
        OwnedCapeInventory ownedCapes();
        UUID activePresetId();
        TextResolver textResolver();
        int viewportHeight();
        PreviewRenderer.CapeMode preferredCapeMode();
        int viewportWidth();
        ViewChromeMetrics viewChromeMetrics();
        FilePicker filePicker();
        boolean disposed();
        ClientSnapshot snapshot();
        ViewSpec view(int width, int height, int mouseX, int mouseY);
        ViewSpec view(
            int width,
            int height,
            int mouseX,
            int mouseY,
            ViewChromeMetrics chromeMetrics);
        ViewSpec withRuntimeFocus(ViewSpec view);
        void requestRuntimeFocus(String screenId, String widgetId);
        void clearRuntimeFocus(String screenId);
        void applyNavigationScroll(ViewSpec.NavigationNode node, double offsetPixels);
        CompletableFuture<Optional<byte[]>> loadSkinPreview(SkinReference reference);
        CompletableFuture<Optional<byte[]>> loadSkinPreview(ViewSpec.Preview preview);
        SkinFeatureEvidence analyzeImportedSkinFeatureEvidence(byte[] pngBytes)
            throws PngValidationException;
        SkinFeatureEvidence analyzeStoredSkinFeatureEvidence(byte[] pngBytes)
            throws PngValidationException;
        void diagnose(DiagnosticEvent event, Throwable failure);
        void retainKeyboardFocus(
            InteractionOrigin origin, String screenId, String widgetId);
        void returnFromEditor();
        void installWarmedCapePreviews(UUID accountId, boolean replaceResources);
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
        SkinVariant preferredSkinVariant();
        UiMessage fileImportFailure(Throwable failure);
        NormalizedSkin readPng(Path path);
        Throwable unwrap(Throwable failure);
        ScreenOperationTicket beginScreenOperation();
        ScreenOperationTicket captureScreenOperation();
        void finishScreenOperation(ScreenOperationTicket ticket);
        void showFeedback(UiMessage message);
        void editorTabSelected(EditorTab tab);
        void capeDisclosureChanged(Set<String> collapsed);
        void editorPreviewModeChanged(PreviewRenderer.CapeMode mode);
        void editorSaved(EditorFlow.SaveCompletion completion);
        void capeRenamed(AccountState account);
        void capeDeleted(LibraryEditorPort.CapeDeletion result);
        void capeCatalogLoaded(CatalogRead.CapeEditorData data);
        void capeImported(CapeImportResult result);
        SkinFeatureEvidence cachedAssetEvidence(UUID assetId);
        boolean acceptAssetEvidence(UUID assetId, SkinFeatureEvidence evidence);
    }

    private static final class State {
        PresetEditorModel editor;
        PersonalSkinSource editorPersonalSource = PersonalSkinSource.FILE;
        String pendingPresetName;
        SkinFeatureEvidence editorEvidence;
    }
}

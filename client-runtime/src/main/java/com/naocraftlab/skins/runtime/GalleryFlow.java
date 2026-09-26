package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

final class GalleryFlow {
    private final State state = new State();
    private final Context context;
    private final GalleryPresenter galleryPresenter = new GalleryPresenter();
    private boolean draggingGalleryScrollbar;
    private double galleryScrollbarGrabOffset;

    GalleryFlow(Context context) { this.context = Objects.requireNonNull(context, "context"); }

    void dispatchPresetWidget(String widgetId, InteractionOrigin origin) {
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
            case "edit" -> context.openEditor(presetId);
            case "duplicate" -> duplicatePreset(presetId);
            case "delete" -> requestPresetDeletion(presetId, origin);
            case "delete_confirm" -> deletePreset(presetId, origin);
            case "delete_cancel" -> cancelPresetDeletion(presetId, origin);
            default -> {

            }
        }
    }

    void selectGalleryCard(String widgetId, InteractionOrigin origin) {
        if (!galleryPresenter.cardIds(context.snapshot(), state.galleryQuery).contains(widgetId)) {
            return;
        }
        boolean changed = !widgetId.equals(state.gallerySelectedCardId);
        if (changed) {
            state.gallerySelectedCardId = widgetId;
        }
        if (origin.keyboard()) {
            context.requestRuntimeFocus("gallery", widgetId);
        }
        if (changed || origin.keyboard()) {
            context.publish();
        }
    }

    void requestPresetDeletion(UUID presetId, InteractionOrigin origin) {
        if (context.busy() || context.findPreset(presetId) == null) {
            return;
        }
        state.pendingPresetDeleteId = presetId;
        state.gallerySelectedCardId = "gallery.card." + presetId;
        if (origin.keyboard()) {
            context.requestRuntimeFocus(
                    "gallery", "gallery.preset." + presetId + ".delete_cancel");
        }
        context.publish();
    }

    void cancelPresetDeletion(UUID presetId, InteractionOrigin origin) {
        if (!presetId.equals(state.pendingPresetDeleteId) || context.busy()) {
            return;
        }
        state.pendingPresetDeleteId = null;
        if (origin.keyboard()) {
            context.requestRuntimeFocus("gallery", "gallery.preset." + presetId + ".delete");
        }
        context.publish();
    }

    void duplicatePreset(UUID presetId) {
        AppearancePreset source = context.findPreset(presetId);
        if (source == null || context.busy() || context.account() == null) {
            return;
        }
        String name = context.textResolver().resolve(UiMessage.info("nclskins.gallery.copy_name", source.name())).trim();
        if (name.length() > 128) {
            name = name.substring(0, 128).trim();
        }
        String copyName = name.isEmpty() ? source.name() : name;
        try {
            PresetEditorModel draft = PresetEditorModel.openDuplicate(
                    context.account(),
                    source,
                    copyName,
                    context.editorProfile(),
                    Optional.ofNullable(context.activePresetId()),
                    context.textResolver(),
                    context.viewportHeight(),
                    context.preferredCapeMode(),
                    context.preferredSkinVariant(),
                    context.editorOwnedCapes());
            context.acceptDraft(new EditorDraftTransfer(draft, source.offlineCape(), Optional.empty(), presetId));
            context.publish();
        } catch (IllegalStateException missingBundledSkin) {
            context.diagnose(DiagnosticEvent.CLIENT_BUNDLED_SKIN_MISSING, missingBundledSkin);
            context.showFeedback(UiMessage.error("nclskins.gallery.prepare_failed"));
            context.publish();
        }
    }

    void deletePreset(UUID presetId, InteractionOrigin origin) {
        if (!presetId.equals(state.pendingPresetDeleteId)) {
            return;
        }
        List<String> previousCards = galleryPresenter.cardIds(context.snapshot(), state.galleryQuery);
        int removedOrdinal = previousCards.indexOf("gallery.card." + presetId);
        context.submit(
                UiMessage.info("nclskins.status.deleting"),
                () -> context.libraryEditorPort().deletePreset(presetId),
                deletion -> {
                    UUID previousActivePresetId = context.activePresetId();
                    context.presetDeletionCompleted(presetId, deletion);
                    state.pendingPresetDeleteId = null;
                    boolean deleted = context.account().presets().stream()
                            .noneMatch(preset -> preset.id().equals(presetId));
                    if (!deleted) {
                        if (!deletion.cleanupWarnings().isEmpty()) {
                            context.showFeedback(UiMessage.literal(
                                    deletion.cleanupWarnings().get(0), UiMessage.Severity.ERROR));
                        }
                        return;
                    }
                    if (presetId.equals(state.selectedPresetId)) {
                        state.selectedPresetId = null;
                    }
                    List<String> remainingCards = galleryPresenter.cardIds(
                            Optional.of(context.account()),
                            Optional.ofNullable(context.activePresetId()),
                            state.galleryQuery);
                    int nextOrdinal = removedOrdinal < 0
                            ? 0
                            : Math.min(removedOrdinal, remainingCards.size() - 1);
                    state.gallerySelectedCardId = remainingCards.isEmpty()
                            ? "gallery.add"
                            : remainingCards.get(Math.max(0, nextOrdinal));
                    if (origin.keyboard()) {
                        context.requestRuntimeFocus("gallery", state.gallerySelectedCardId);
                    }
                    context.showFeedback(deletion.cleanupWarnings().isEmpty()
                            ? UiMessage.success("nclskins.status.deleted")
                            : UiMessage.literal(
                                    deletion.cleanupWarnings().get(0), UiMessage.Severity.ERROR));
                    centerGalleryIfActiveChanged(previousActivePresetId);
                });
    }

    void applyPreset(UUID presetId, boolean preserveGalleryOffset) {
        if (context.findPreset(presetId) == null) {
            return;
        }
        if (presetId.equals(context.activePresetId())
                && context.syncStatus() != AppearanceSyncStatus.OFFICIAL
                && context.clientSessionView().rateLimitRemaining().isPresent()) {
            state.selectedPresetId = presetId;
            context.armRateLimitRecovery();
            context.publish();
            return;
        }
        state.selectedPresetId = presetId;
        context.submitRemote(
                UiMessage.info("nclskins.status.applying"),
                () -> context.libraryEditorPort().usePreset(presetId),
                result -> acceptPresetUse(result, preserveGalleryOffset),
                result -> result.remoteResult().map(ClientOperations.RemoteResult::outcome));
    }

    void acceptPresetUse(LibraryEditorPort.PresetUse use, boolean preserveGalleryOffset) {
        UUID previous = context.activePresetId();
        state.selectedPresetId = use.activePresetId();
        context.presetUsed(use);
        if (!preserveGalleryOffset) centerGalleryIfActiveChanged(previous);
    }

    void queueGalleryScroll(double pixelDelta) {
        if (!Double.isFinite(pixelDelta) || pixelDelta == 0.0) {
            return;
        }
        setGalleryPosition(state.galleryScrollPosition + pixelDelta);
    }

    void setGalleryOffset(int offset) {
        int bounded = Math.max(0, Math.min(galleryMaximum(), offset));
        if (bounded != state.galleryOffset) {
            state.galleryOffset = bounded;
            state.galleryScrollPosition = bounded;
            state.galleryScrollTarget = bounded;
            context.publish();
        }
    }

    void setGalleryPosition(double position) {
        double bounded = Math.max(0.0, Math.min(galleryMaximum(), position));
        if (Math.abs(bounded - state.galleryScrollPosition) > 0.001
                || Math.abs(bounded - state.galleryScrollTarget) > 0.001) {
            state.galleryScrollPosition = bounded;
            state.galleryScrollTarget = bounded;
            state.galleryOffset = (int) Math.round(bounded);
            context.publish();
        }
    }

    void resetGalleryScroll() {
        draggingGalleryScrollbar = false;
        state.galleryOffset = 0;
        state.galleryScrollPosition = 0.0;
        state.galleryScrollTarget = 0.0;
    }

    void centerGalleryIfActiveChanged(UUID previousActivePresetId) {
        if (!Objects.equals(previousActivePresetId, context.activePresetId())) {
            centerGalleryOnActive();
        }
    }

    void centerGalleryOnActive() {
        draggingGalleryScrollbar = false;
        double centered = galleryPresenter.initialScrollPosition(
                Optional.ofNullable(context.account()),
                Optional.ofNullable(context.activePresetId()),
                state.galleryQuery,
                context.viewportWidth(),
                context.viewportHeight());
        state.galleryOffset = (int) Math.round(centered);
        state.galleryScrollPosition = centered;
        state.galleryScrollTarget = centered;
    }

    ViewSpec galleryView(
            int width, int height, int mouseX, int mouseY) {
        state.gallerySelectedCardId = galleryPresenter.normalizeSelectedCardId(
                context.snapshot(), state.galleryQuery, state.gallerySelectedCardId);
        return galleryPresenter.present(
                context.snapshot(),
                width,
                height,
                mouseX,
                mouseY,
                context.preferredCapeMode(),
                context.currentPlayerVariant(),
                state.galleryQuery,
                Optional.ofNullable(state.pendingPresetDeleteId),
                state.galleryScrollPosition,
                state.gallerySelectedCardId);
    }

    int galleryMaximum() {
        return galleryPresenter.maximumScroll(
                context.snapshot(), context.viewportWidth(), context.viewportHeight(), state.galleryQuery);
    }

    boolean clampGalleryScroll() {
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

    boolean dispatchGalleryAction(String widgetId, boolean reverse, InteractionOrigin origin) {
        if (widgetId.startsWith("gallery.preset.")) {
            dispatchPresetWidget(widgetId, origin);
            return true;
        }
        if (widgetId.startsWith("gallery.card.")) {
            selectGalleryCard(widgetId, origin);
            return true;
        }
        switch (widgetId) {
            case "gallery.add" -> {
                context.openAddSource();
                return true;
            }
            case "gallery.retry_session" -> {
                context.retrySession();
                return true;
            }
            case "gallery.retry_cape" -> {
                context.retrySelectedCape();
                return true;
            }
            case "gallery.done" -> {
                if (context.galleryReturnsToProviders()) {
                    context.closeToProviders();
                } else {
                    context.closeScreen();
                }
                return true;
            }
            default -> { return false; }
        }
    }

    void acceptDraftSelection(UUID presetId) {
        state.selectedPresetId = presetId;
    }

    void acceptSavedPreset(AppearancePreset preset, UUID presetId) {
        state.selectedPresetId = presetId;
        state.selectedSkinId = preset == null ? null : preset.skin().assetId();
    }

    void acceptExternalImport() {
        state.selectedPresetId = null;
    }

    void resetSession() {
        state.selectedSkinId = null;
        state.selectedPresetId = null;
        state.galleryOffset = 0;
        state.galleryScrollPosition = 0.0;
        state.galleryScrollTarget = 0.0;
        state.galleryQuery = "";
        state.gallerySelectedCardId = null;
        state.pendingPresetDeleteId = null;
    }

    void dismissDeletion() {
        state.pendingPresetDeleteId = null;
    }

    void clearFocusedCard() {
        state.gallerySelectedCardId = null;
    }

    void focusCard(String cardId) {
        state.gallerySelectedCardId = cardId;
    }

    void stopScrolling() {
        if (draggingGalleryScrollbar) state.galleryScrollTarget = state.galleryScrollPosition;
        draggingGalleryScrollbar = false;
    }

    void navigateToOffset(double bounded) {
        state.galleryScrollPosition = bounded;
        state.galleryScrollTarget = bounded;
        state.galleryOffset = (int) Math.round(bounded);
    }

    void search(String value) {
        state.galleryQuery = value;
        state.gallerySelectedCardId = galleryPresenter.normalizeSelectedCardId(
                context.snapshot(), value, state.gallerySelectedCardId);
        resetGalleryScroll();
        dismissDeletion();
    }

    void skinImported(AccountState account, java.util.Set<UUID> previous) {
        state.selectedSkinId = account.skinAssets().stream()
                .map(com.naocraftlab.skins.core.model.SkinAsset::id)
                .filter(id -> !previous.contains(id)).findFirst().orElse(null);
    }

    void skinDeleted(UUID skinId) {
        if (skinId.equals(state.selectedSkinId)) state.selectedSkinId = null;
    }

    String suggestedPresetName() {
        return galleryPresenter.matchingPresetCount(context.snapshot(), state.galleryQuery) == 0
                && !state.galleryQuery.isBlank() ? UntrustedDisplayName.sanitize(state.galleryQuery, "") : null;
    }

    UUID selectedSkinId() { return state.selectedSkinId; }

    UUID selectedPresetId() { return state.selectedPresetId; }

    int galleryOffset() { return state.galleryOffset; }

    String galleryQuery() { return state.galleryQuery; }

    UUID pendingPresetDeleteId() { return state.pendingPresetDeleteId; }

    void releaseScrollbar() { draggingGalleryScrollbar = false; }

    boolean grabScrollbar(ViewSpec.Scrollbar scrollbar, double mouseX, double mouseY) {
        draggingGalleryScrollbar = true;
        boolean grabbedThumb = scrollbar.thumb().contains(mouseX, mouseY);
        galleryScrollbarGrabOffset = grabbedThumb ? mouseX - scrollbar.thumb().x() : scrollbar.thumb().width() / 2.0;
        return grabbedThumb;
    }

    double galleryScrollbarGrabOffset() { return galleryScrollbarGrabOffset; }

    boolean draggingGalleryScrollbar() { return draggingGalleryScrollbar; }

    GalleryPresenter galleryPresenter() { return galleryPresenter; }

    interface Context {
        void acceptDraft(EditorDraftTransfer transfer);
        ClientSnapshot snapshot();
        boolean busy();
        AccountState account();
        TextResolver textResolver();
        UUID activePresetId();
        int viewportHeight();
        PreviewRenderer.CapeMode preferredCapeMode();
        LibraryEditorPort libraryEditorPort();
        ClientSessionView clientSessionView();
        AppearanceSyncStatus syncStatus();
        int viewportWidth();
        boolean galleryReturnsToProviders();
        void closeScreen();
        void armRateLimitRecovery();
        void requestRuntimeFocus(String screenId, String widgetId);
        void diagnose(DiagnosticEvent event, Throwable failure);
        void openAddSource();
        void openAddSource(AddSourceTab override);
        Optional<com.naocraftlab.skins.core.model.RemoteProfile> editorProfile();
        List<com.naocraftlab.skins.core.model.OwnedCapeEntry> editorOwnedCapes();
        void openEditor(UUID presetId);
        void closeToProviders();
        void retrySelectedCape();
        void retrySession();
        <T> void submit(
            UiMessage progress, ThrowingSupplier<T> operation, Consumer<T> completion);
        <T> void submitRemote(
            UiMessage progress,
            ThrowingSupplier<T> operation,
            Consumer<T> completion,
            Function<T, Optional<PresetApplicationOutcome>> completedOutcome);
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
        void publish();
        SkinVariant currentPlayerVariant();
        SkinVariant preferredSkinVariant();
        AppearancePreset findPreset(UUID id);
        void showFeedback(UiMessage message);
        void presetUsed(LibraryEditorPort.PresetUse use);
        void presetDeletionCompleted(UUID presetId, LibraryEditorPort.PresetDelete deletion);
    }

    private static final class State {
        UUID selectedSkinId;
        UUID selectedPresetId;
        int galleryOffset;
        double galleryScrollPosition;
        double galleryScrollTarget;
        String galleryQuery = "";
        String gallerySelectedCardId;
        UUID pendingPresetDeleteId;
    }
}

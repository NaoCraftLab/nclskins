package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.OuterLayerVisibilityController;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
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
    private ViewSpec.Preview bodyPreview;
    private GalleryPresenter.LeftProjection displayedLeft;
    private PreviewRenderer.DisplayKind displayedBodyKind = PreviewRenderer.DisplayKind.UNCONFIRMED;
    private com.naocraftlab.skins.client.PreviewAccessories accessories =
            com.naocraftlab.skins.client.PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LIVE_UNAVAILABLE);
    private ViewSpec.Preview accessoryPreview;
    private String accessoryFactsInput;
    private boolean accessoryCape;
    private boolean accessoryElytra;
    private boolean accessoryCapeContentEmpty;
    private boolean accessoryElytraContentEmpty;
    private long skinSelectionGeneration;
    private long sneakyTicket;
    private SneakyPreparation sneaky;
    private SneakyPreparation displayedSneaky;

    private static final class SneakyPreparation {
        final ViewSpec.BodySource source;
        final long ticket;
        PreviewAssets.SneakyLease lease;
        PreviewAssets.SneakyResult result;
        SneakyPreparation(ViewSpec.BodySource source, long ticket) { this.source = source; this.ticket = ticket; }
        String key() { return result != null && result.status() == PreviewAssets.SneakyStatus.VALUE
                ? "sneaky:preview:gallery:" + ticket + ":" + result.identity() : null; }
        void close() { if (lease != null) lease.close(); }
    }

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
        if (origin != InteractionOrigin.PROGRAMMATIC && galleryView(
                context.viewportWidth(), context.viewportHeight(), 0, 0).widget(widgetId)
                .filter(ViewSpec.Widget::enabled).filter(ViewSpec.Widget::activationEnabled).isEmpty()) return;
        switch (action) {
            case "apply" -> applyPreset(presetId, false);
            case "more" -> {
                if (context.busy() || context.findPreset(presetId) == null) return;
                state.gallerySelectedCardId = "gallery.card." + presetId;
                state.actionPresetId = presetId.equals(state.actionPresetId) ? null : presetId;
                state.pendingPresetDeleteId = null;
                context.publish();
            }
            case "favorite" -> {
                AppearancePreset preset = context.findPreset(presetId);
                if (preset != null) context.captureGalleryMetadataScope().ifPresent(scope -> {
                    var previous = state.pendingFavorites.get(presetId);
                    var update = new LibraryEditorPort.PresetFavoriteUpdate(scope, presetId,
                            !(previous == null ? preset.favorite() : previous.desired()));
                    state.pendingFavorites.put(presetId, update);
                    context.submitFavorite(update, origin);
                });
            }
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
        if (context.busy()) return;
        if (!galleryPresenter.cardIds(context.snapshot(), state.galleryQuery, draftInput()).contains(widgetId)) {
            return;
        }
        boolean changed = !widgetId.equals(state.gallerySelectedCardId);
        if (changed) {
            state.gallerySelectedCardId = widgetId;
        }
        UUID id = UUID.fromString(widgetId.substring("gallery.card.".length()));
        state.activeReveal = false;
        var preset = context.findPreset(id);
        boolean unchangedDisplay = preset != null && id.equals(state.selectedPresetId)
                && state.inspectionIdentity == null && state.baselineMask.equals(preset.outerLayerVisibility())
                && state.draftMask.equals(preset.outerLayerVisibility())
                && bodyPreview != null && previewStillCurrent(bodyPreview)
                && displayedBodyKind != PreviewRenderer.DisplayKind.UNCONFIRMED
                && (!expectedCpmBody(bodyPreview) || displayedBodyKind == PreviewRenderer.DisplayKind.CPM);
        if (!unchangedDisplay) selectDraft(preset);
        state.actionPresetId = null;
        state.pendingPresetDeleteId = null;
        if (origin.keyboard()) {
            context.requestRuntimeFocus("gallery", widgetId);
        }
        context.publish();
    }

    private void selectDraft(AppearancePreset preset) {
        skinSelectionGeneration++;
        state.inspectionIdentity = null;
        clearAccessories();
        state.selectedPresetId = preset == null ? null : preset.id();
        state.baselineMask = preset == null ? OuterLayerVisibility.allVisible() : preset.outerLayerVisibility();
        state.draftMask = state.baselineMask;
        state.draftGeneration++;
        state.draftInitialized = true;
    }

    void discardDraft() {
        state.openingGeneration++;
        state.inspectionIdentity = null;
        clearBody();
        clearAccessories();
        retireSneaky();
        state.draftInitialized = false;
        state.preview = null;
        state.leftScroll = 0;
        state.rotationSource = null;
        state.revealInitialized = false;
        state.activeReveal = true;
        state.revealWidth = 0;
        state.revealHeight = 0;
        state.metadataFocusPending = false;
        state.pendingFavorites.clear();
        state.pendingSections.clear();
        state.temporarilyRevealedSection = null;
        state.draftGeneration++;
        state.actionPresetId = null;
        state.pendingPresetDeleteId = null;
    }

    long draftGeneration() { return state.draftGeneration; }
    long openingGeneration() { return state.openingGeneration; }
    long uiIntent() { return state.uiIntent; }
    void uiIntentChanged() { state.uiIntent++; }

    void prepareDraft() {
        if (state.preview == null) state.preview = PreviewInteractionModel.gallery().withCapeMode(context.preferredCapeMode());
        if (!state.draftInitialized || state.selectedPresetId != null && context.findPreset(state.selectedPresetId) == null)
            selectDraft(context.findPreset(context.activePresetId()));
        prepareSneaky();
        if (!state.revealInitialized && context.galleryReady() && context.uiPreferences() != null) {
            state.revealInitialized = true;
            AppearancePreset active = context.findPreset(context.activePresetId());
            if (active != null) {
                String section = active.favorite() ? AccountUiPreferences.GALLERY_FAVORITES : AccountUiPreferences.GALLERY_YOUR_APPEARANCES;
                if (context.uiPreferences().collapsedGallerySections().contains(section)) state.temporarilyRevealedSection = section;
            }
        }
    }

    private GalleryPresenter.DraftInput draftInput() {
        return new GalleryPresenter.DraftInput(Optional.ofNullable(state.selectedPresetId), state.baselineMask,
                state.draftMask, state.draftGeneration, inspectionIdentity(),
                context.uiPreferences() == null ? java.util.Set.of() : context.uiPreferences().collapsedGallerySections(),
                Optional.ofNullable(state.temporarilyRevealedSection),
                bodyPreview != null && previewStillCurrent(bodyPreview) ? displayedBodyKind : PreviewRenderer.DisplayKind.UNCONFIRMED);
    }

    private GalleryPresenter.DraftInput sourceDraft() {
        return new GalleryPresenter.DraftInput(Optional.ofNullable(state.selectedPresetId), state.baselineMask,
                state.draftMask, state.draftGeneration, inspectionIdentity(), java.util.Set.of(), Optional.empty(),
                PreviewRenderer.DisplayKind.UNCONFIRMED);
    }

    private PlayerAppearanceStage.Appearance mainAppearance() {
        boolean known = Objects.equals(accessoryFactsInput, accessoryInput());
        return GalleryPresenter.mainAppearance(context.snapshot(), sourceDraft(), context.currentPlayerVariant(), capeChoices(),
                known && (accessoryCape || accessoryElytra), known && accessoryElytra);
    }

    String accessoryInput() {
        if (!state.draftInitialized) return null;
        var inputDraft = new GalleryPresenter.DraftInput(Optional.ofNullable(state.selectedPresetId),state.baselineMask,
                state.draftMask,skinSelectionGeneration,Optional.empty(),java.util.Set.of(),Optional.empty(),PreviewRenderer.DisplayKind.UNCONFIRMED);
        var base = GalleryPresenter.mainAppearance(context.snapshot(), inputDraft, context.currentPlayerVariant(), capeChoices(false), false, false);
        return context.previewSource(base.revision(), base.variant(), base.presetId(), skinSelectionGeneration)
                + ":" + state.openingGeneration + ":" + base.skin()
                + ":" + base.capeId() + ":" + base.capeHasElytra() + ":" + base.intent() + ":" + previewMask()
                + ":" + context.snapshot().providers().cape().configurationRevision()
                + ":" + context.snapshot().providers().cape().order();
    }

    private boolean knownAccessories() {
        return Objects.equals(accessoryFactsInput, accessoryInput()) && (accessoryCape || accessoryElytra);
    }

    private List<GalleryPresenter.CapeChoice> capeChoices() {
        return capeChoices(true);
    }

    private List<GalleryPresenter.CapeChoice> capeChoices(boolean includeNative) {
        var result = new java.util.ArrayList<GalleryPresenter.CapeChoice>();
        AppearancePreset selected = context.findPreset(state.selectedPresetId);
        var channel = context.snapshot().providers().cape();
        int applicable = 0;
        boolean allAbsent = true;
        for (var provider : channel.order()) {
            if (provider == BuiltinProvider.CPM) {
                if (includeNative && context.snapshot().skinExtensionEnvironment().cpmPresent() && channel.enabled(provider)) {
                    applicable++;
                    boolean known=Objects.equals(accessoryFactsInput,accessoryInput());
                    boolean content = accessoryCape && !accessoryCapeContentEmpty || accessoryElytra && !accessoryElytraContentEmpty;
                    if (!known || content) allAbsent=false;
                    if (known && content) result.add(new GalleryPresenter.CapeChoice(provider.name(),
                            UiMessage.info("nclskins.compatibility.feature.customizable_player_models"),Optional.empty(),accessoryElytra,GalleryPresenter.CapeChoice.Content.NATIVE));
                }
                continue;
            }
            if (provider == BuiltinProvider.SNEAKY) {
                applicable++;
                var value = sneakyCurrent(sneaky) ? sneaky.result : null;
                if (value == null || value.status() == PreviewAssets.SneakyStatus.FAILURE) allAbsent = false;
                else if (value.status() == PreviewAssets.SneakyStatus.VALUE) {
                    allAbsent = false;
                    result.add(new GalleryPresenter.CapeChoice(provider.name(), UiMessage.info("nclskins.providers.sneaky"),
                            Optional.of(sneaky.key()), value.hasElytra()));
                }
                continue;
            }
            if (!provider.canWrite(AppearanceProviders.Component.CAPE)) continue;
            applicable++;
            Optional<String> texture;
            boolean elytra;
            if (selected != null) {
                texture = provider == BuiltinProvider.OFFLINE
                        ? Optional.ofNullable(selected.offlineCape()).map(c -> "provider:cape:" + c.sha256())
                        : selected.optionalCapeId();
                elytra = provider == BuiltinProvider.OFFLINE
                        ? selected.offlineCape() != null && selected.offlineCape().hasElytra()
                        : context.editorOwnedCapes().stream().filter(c -> selected.optionalCapeId().filter(c.id()::equals).isPresent())
                                .noneMatch(c -> Boolean.FALSE.equals(c.hasElytra()));
            } else {
                var observation = channel.observation(provider);
                if (!observation.known()) { allAbsent = false; continue; }
                var cape = observation.value();
                texture = cape == null ? Optional.empty() : Optional.of(cape.optionalTextureCacheKey()
                        .map(key -> "provider:cape:" + key).orElse(cape.id()));
                elytra = cape == null || !Boolean.FALSE.equals(cape.hasElytra());
            }
            if (texture.isEmpty()) continue;
            allAbsent = false;
            result.add(new GalleryPresenter.CapeChoice(provider.name(), provider == BuiltinProvider.OFFLINE
                    ? UiMessage.info("nclskins.providers.offline") : UiMessage.literal("Minecraft", UiMessage.Severity.INFO), texture, elytra));
        }
        if (applicable > 0 && allAbsent) result.add(new GalleryPresenter.CapeChoice("none",
                UiMessage.info("nclskins.editor.no_cape"), Optional.empty(), false));
        return List.copyOf(result);
    }

    private Optional<ViewSpec.BodySource> sneakySource() {
        var preset = context.findPreset(state.selectedPresetId);
        if (!state.draftInitialized || !context.galleryReady()
                || !context.snapshot().providers().cape().enabled(BuiltinProvider.SNEAKY)) return Optional.empty();
        var variant = preset == null ? context.currentPlayerVariant() : preset.skin().optionalAssetId()
                .flatMap(id -> context.account().skinAssets().stream().filter(a -> a.id().equals(id)).findFirst())
                .map(com.naocraftlab.skins.core.model.SkinAsset::variant).orElse(context.currentPlayerVariant());
        return context.previewSource(preset == null ? "current-player" : preset.skin().optionalAssetId()
                        .map(id -> "asset:" + id).orElse("provider:default"), variant,
                Optional.ofNullable(state.selectedPresetId), skinSelectionGeneration);
    }

    private boolean sneakyCurrent(SneakyPreparation preparation) {
        return preparation != null && preparation == sneaky && sneakySource().filter(preparation.source::equals).isPresent();
    }

    private void prepareSneaky() {
        var source = sneakySource();
        if (sneaky != null && source.filter(sneaky.source::equals).isPresent()) return;
        var previous = sneaky;
        sneaky = null;
        if (previous != null && previous != displayedSneaky) previous.close();
        if (source.isEmpty()) return;
        var preparation = new SneakyPreparation(source.orElseThrow(), ++sneakyTicket);
        sneaky = preparation;
        var preset = context.findPreset(state.selectedPresetId);
        context.loadSkinPreview(preset == null ? com.naocraftlab.skins.core.model.SkinReference.accountDefault() : preset.skin())
                .whenComplete((bytes, failure) -> context.onClient(() -> {
                    if (!sneakyCurrent(preparation)) return;
                    if (failure != null || bytes == null || bytes.isEmpty()) {
                        preparation.result = PreviewAssets.SneakyResult.failure();
                        context.publish();
                        return;
                    }
                    preparation.lease = context.previewAssets().prepareSneaky(bytes.orElseThrow());
                    preparation.lease.result().whenComplete((result, failed) -> context.onClient(() -> {
                        if (!sneakyCurrent(preparation)) { preparation.close(); return; }
                        preparation.result = failed == null && result != null ? result : PreviewAssets.SneakyResult.failure();
                        context.publish();
                    }));
                }));
    }

    private void retireSneaky() {
        var candidate = sneaky;
        var displayed = displayedSneaky;
        sneaky = null;
        displayedSneaky = null;
        if (candidate != null) candidate.close();
        if (displayed != null && displayed != candidate) displayed.close();
    }

    Optional<byte[]> sneakyPreview(String key) {
        if (!context.snapshot().providers().cape().enabled(BuiltinProvider.SNEAKY)) return Optional.empty();
        if (sneakyCurrent(sneaky) && key.equals(sneaky.key())) return Optional.of(sneaky.result.atlas());
        if (displayedLeft != null && displayedSneaky != null && key.equals(displayedSneaky.key())
                && displayedLeft.controls().capes().stream().anyMatch(c -> c.texture().filter(key::equals).isPresent())
                && context.previewSource(displayedSneaky.source.imageRevision(), displayedSneaky.source.variant(),
                        displayedSneaky.source.presetId(), displayedSneaky.source.draftGeneration())
                        .filter(displayedSneaky.source::equals).isPresent()) return Optional.of(displayedSneaky.result.atlas());
        return Optional.empty();
    }

    private Optional<String> inspectionIdentity() {
        if (state.inspectionIdentity == null) return Optional.empty();
        boolean nativePending = state.inspectionIdentity.equals("CPM")
                && context.snapshot().skinExtensionEnvironment().cpmPresent()
                && context.snapshot().providers().cape().enabled(BuiltinProvider.CPM);
        if (!Objects.equals(state.inspectionScope, inspectionScope())
                || !nativePending && capeChoices().stream().noneMatch(c -> c.id().equals(state.inspectionIdentity))) {
            state.inspectionIdentity = null;
            return Optional.empty();
        }
        return Optional.of(state.inspectionIdentity);
    }

    private String inspectionScope() {
        var preset = context.findPreset(state.selectedPresetId);
        return context.previewSource(preset == null ? "current-player" : preset.skin().optionalAssetId()
                        .map(id -> "asset:" + id).orElse("provider:default"),
                context.currentPlayerVariant(), Optional.ofNullable(state.selectedPresetId), skinSelectionGeneration)
                + ":" + previewMask() + ":" + capeChoices(false)
                + ":" + context.snapshot().providers().cape().configurationRevision();
    }

    private OuterLayerVisibility previewMask() {
        return state.selectedPresetId == null ? context.outerLayerVisibilityController()
                .map(OuterLayerVisibilityController::current).orElse(OuterLayerVisibility.allVisible()) : state.draftMask;
    }

    private Optional<ViewSpec.BodySource> bodySource() {
        var appearance = mainAppearance();
        return context.previewSource(appearance.revision(), appearance.variant(), appearance.presetId(), state.draftGeneration)
                .map(source -> new ViewSpec.BodySource(source.role(), source.accountId(), source.accountEpoch(),
                        source.worldEpoch(), source.resourceEpoch(), source.screenGeneration(), source.presetId(),
                        source.imageRevision(), source.variant(), source.draftGeneration(), inspectionIdentity(), source.cpmExpected()));
    }

    boolean previewStillCurrent(ViewSpec.Preview preview) {
        var appearance = mainAppearance();
        boolean nativeAccessories = GalleryPresenter.nativeAccessories(context.snapshot(), sourceDraft(), capeChoices(), knownAccessories());
        return state.draftInitialized && preview.id().equals("gallery.preview")
                && preview.bodySource().isPresent() && preview.bodySource().equals(bodySource())
                && preview.skin().equals(appearance.skin()) && preview.capeId().equals(appearance.capeId())
                && preview.capeHasElytra() == appearance.capeHasElytra()
                && preview.variant() == appearance.variant() && preview.intent() == appearance.intent()
                && preview.nativeAccessories() == nativeAccessories
                && preview.capeMode() == (appearance.capeId().isPresent() || nativeAccessories
                        ? state.preview.capeMode() : PreviewRenderer.CapeMode.OFF)
                && preview.outerLayerVisibility().equals(previewMask());
    }

    boolean accessoryStillCurrent(ViewSpec.Preview preview) {
        var base = GalleryPresenter.mainAppearance(context.snapshot(), sourceDraft(), context.currentPlayerVariant(), capeChoices(),
                false, false);
        return state.draftInitialized && preview.id().equals("gallery.preview") && preview.bodySource().isPresent()
                && preview.bodySource().equals(bodySource()) && preview.skin().equals(base.skin())
                && preview.variant() == base.variant() && preview.intent() == base.intent()
                && (preview.nativeAccessories() || preview.capeId().equals(base.capeId()) && preview.capeHasElytra() == base.capeHasElytra())
                && preview.outerLayerVisibility().equals(previewMask());
    }

    boolean expectedCpmBody(ViewSpec.Preview preview) {
        return previewStillCurrent(preview) && context.snapshot().account().stream()
                .flatMap(a -> a.presets().stream()).filter(p -> preview.presetId().filter(p.id()::equals).isPresent())
                .map(context.snapshot()::compatibilityFor).anyMatch(c -> c.supportedFeatures().contains(
                        com.naocraftlab.skins.core.compatibility.SkinFeature.CUSTOMIZABLE_PLAYER_MODELS));
    }

    boolean acceptBody(ViewSpec.Preview preview, PreviewRenderer.DisplayKind kind) {
        if (!previewStillCurrent(preview)) return false;
        if (kind == PreviewRenderer.DisplayKind.UNCONFIRMED) {
            if (bodyPreview == null || !previewStillCurrent(bodyPreview)) return false;
            clearBody();
            return true;
        }
        boolean changed = displayedBodyKind != kind || bodyPreview == null;
        changed |= !Objects.equals(bodyPreview, preview);
        bodyPreview = preview;
        displayedBodyKind = kind;
        displayedLeft = new GalleryPresenter.LeftProjection(mainAppearance(), previewMask(), draftInput(),
                controlsInput(Optional.empty()), context.snapshot().account().stream().flatMap(a -> a.presets().stream())
                        .filter(p -> preview.presetId().filter(p.id()::equals).isPresent()).findFirst()
                        .map(context.snapshot()::compatibilityFor));
        var previous = displayedSneaky;
        displayedSneaky = sneakyCurrent(sneaky) ? sneaky : null;
        if (previous != null && previous != displayedSneaky && previous != sneaky) previous.close();
        return changed;
    }

    void clearBody() {
        bodyPreview = null; displayedLeft = null; displayedBodyKind = PreviewRenderer.DisplayKind.UNCONFIRMED;
        var previous = displayedSneaky;
        displayedSneaky = null;
        if (previous != null && previous != sneaky) previous.close();
    }

    private GalleryPresenter.ControlsInput controlsInput(Optional<GalleryPresenter.LeftProjection> retained) {
        return new GalleryPresenter.ControlsInput(context.outerLayerVisibilityController().isPresent(), previewMask(),
                state.preview, Optional.ofNullable(state.actionPresetId), capeChoices(), state.leftScroll, bodySource(),
                accessoryPreview != null && accessoryStillCurrent(accessoryPreview) ? accessories
                        : com.naocraftlab.skins.client.PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LOADING), retained,
                knownAccessories() && accessoryCape, knownAccessories() && accessoryElytra);
    }

    Optional<ViewSpec.Preview> candidatePreview() {
        prepareDraft();
        return publishedPreview();
    }

    Optional<ViewSpec.Preview> publishedPreview() {
        return presentGallery(context.viewportWidth(), context.viewportHeight(), 0, 0, Optional.empty()).previews()
                .stream().filter(p -> p.id().equals("gallery.preview")).findFirst();
    }

    Optional<ViewSpec.Preview> admissionPreview() {
        if (!state.draftInitialized || state.preview == null || context.snapshot().account().isEmpty()) return Optional.empty();
        var appearance = mainAppearance();
        var preview = PlayerAppearanceStage.present("gallery.preview", context.viewportWidth(), context.viewportHeight(),
                new Bounds(0,0,1,2), appearance,
                state.preview.withOuterLayerVisibility(previewMask())).withBodySource(bodySource());
        if (GalleryPresenter.nativeAccessories(context.snapshot(), sourceDraft(), capeChoices(), knownAccessories()))
            preview = preview.withNativeAccessories(true,state.preview.capeMode());
        return Optional.of(preview);
    }

    boolean acceptAccessories(ViewSpec.Preview preview, com.naocraftlab.skins.client.PreviewAccessories value) {
        if (!accessoryStillCurrent(preview)) return false;
        boolean changed = !accessories.equals(value);
        String input = accessoryInput();
        if (!Objects.equals(accessoryFactsInput, input)) {
            accessoryFactsInput = input;
            accessoryCape = false;
            accessoryElytra = false;
            accessoryCapeContentEmpty = false;
            accessoryElytraContentEmpty = false;
        }
        if (value.canInspect()) {
            changed |= accessoryCape != value.cape() || accessoryElytra != value.elytra();
            changed |= accessoryCapeContentEmpty != value.capeContentEmpty() || accessoryElytraContentEmpty != value.elytraContentEmpty();
            accessoryCape = value.cape();
            accessoryElytra = value.elytra();
            accessoryCapeContentEmpty = value.capeContentEmpty();
            accessoryElytraContentEmpty = value.elytraContentEmpty();
        }
        accessoryPreview = preview;
        accessories = value;
        return changed;
    }

    boolean clearAccessoryKnowledge() {
        boolean changed = accessoryCape || accessoryElytra;
        accessoryFactsInput = null;
        accessoryCape = false;
        accessoryElytra = false;
        accessoryCapeContentEmpty = false;
        accessoryElytraContentEmpty = false;
        return changed;
    }

    void clearAccessories() {
        clearAccessoryKnowledge();
        clearAccessoryObservation();
    }

    void clearAccessoryObservation() {
        accessoryPreview = null;
        accessories = com.naocraftlab.skins.client.PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LIVE_UNAVAILABLE);
    }

    private boolean collapsed(String section) {
        return draftInput().collapsedSections().contains(section) && !section.equals(state.temporarilyRevealedSection);
    }

    private boolean desiredCollapsed(String section) {
        var pending = state.pendingSections.get(section);
        return pending == null ? collapsed(section) : pending.collapsed().get(section);
    }

    void favoriteSettled(LibraryEditorPort.PresetFavoriteUpdate update) {
        state.pendingFavorites.remove(update.presetId(), update);
    }

    void sectionsSettled(UiPreferencesPort.GallerySectionsUpdate update) {
        update.collapsed().keySet().forEach(id -> state.pendingSections.remove(id, update));
    }

    private void updateSections(java.util.Map<String, Boolean> patch, InteractionOrigin origin, String anchor) {
        context.captureGalleryMetadataScope().ifPresent(scope -> {
            state.gallerySelectedCardId = anchor;
            var update = new UiPreferencesPort.GallerySectionsUpdate(scope, patch);
            patch.keySet().forEach(id -> state.pendingSections.put(id, update));
            context.submitSections(update, origin);
        });
    }

    void sectionsConfirmed(java.util.Set<String> sections, InteractionOrigin origin, boolean currentIntent) {
        if (state.temporarilyRevealedSection != null && sections.contains(state.temporarilyRevealedSection)) state.temporarilyRevealedSection = null;
        if (!currentIntent) {
            var eligible = galleryPresenter.cardIds(context.snapshot(), state.galleryQuery, draftInput());
            if (state.actionPresetId != null && !eligible.contains("gallery.card." + state.actionPresetId)) state.actionPresetId = null;
            if (state.pendingPresetDeleteId != null && !eligible.contains("gallery.card." + state.pendingPresetDeleteId)) state.pendingPresetDeleteId = null;
            state.gallerySelectedCardId = galleryPresenter.normalizeSelectedCardId(context.snapshot(), state.galleryQuery, state.gallerySelectedCardId, draftInput());
            return;
        }
        state.actionPresetId = null;
        state.pendingPresetDeleteId = null;
        state.gallerySelectedCardId = galleryPresenter.normalizeSelectedCardId(
                context.snapshot(), state.galleryQuery, state.gallerySelectedCardId, draftInput());
        if (origin.keyboard()) context.requestRuntimeFocus("gallery", state.gallerySelectedCardId);
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
        if (!presetId.equals(state.pendingPresetDeleteId) || context.busy()) {
            return;
        }
        var scope = context.captureGalleryMetadataScope();
        if (scope.isEmpty()) return;
        List<String> previousCards = galleryPresenter.cardIds(context.snapshot(), state.galleryQuery);
        int removedOrdinal = previousCards.indexOf("gallery.card." + presetId);
        context.submitDelete(scope.orElseThrow(), presetId, (deletion, currentIntent) -> {
                    if (presetId.equals(state.pendingPresetDeleteId)) state.pendingPresetDeleteId = null;
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
                        selectDraft(context.findPreset(context.activePresetId()));
                    }
                    List<String> remainingCards = galleryPresenter.cardIds(
                            Optional.of(context.account()),
                            Optional.ofNullable(context.activePresetId()),
                            state.galleryQuery);
                    int nextOrdinal = removedOrdinal < 0
                            ? 0
                            : Math.min(removedOrdinal, remainingCards.size() - 1);
                    if (currentIntent && ("gallery.card." + presetId).equals(state.gallerySelectedCardId)) {
                        state.gallerySelectedCardId = remainingCards.isEmpty()
                                ? "gallery.add"
                                : remainingCards.get(Math.max(0, nextOrdinal));
                    }
                    if (origin.keyboard() && currentIntent) {
                        context.requestRuntimeFocus("gallery", state.gallerySelectedCardId);
                    }
                    context.showFeedback(deletion.cleanupWarnings().isEmpty()
                            ? UiMessage.success("nclskins.status.deleted")
                            : UiMessage.literal(
                                    deletion.cleanupWarnings().get(0), UiMessage.Severity.ERROR));
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

    void setGalleryPosition(double position) {
        uiIntentChanged();
        state.activeReveal = false;
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
        state.activeReveal = true;
        state.revealWidth = 0;
        state.revealHeight = 0;
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
        prepareDraft();
        if (state.activeReveal && state.revealInitialized && (state.revealWidth != width || state.revealHeight != height)) {
            var view = presentGallery(width, height, mouseX, mouseY, Optional.empty());
            view.navigationNode("gallery.card." + context.activePresetId()).filter(ViewSpec.NavigationNode::enabled)
                    .flatMap(node -> ViewNavigationPolicy.ensureVisibleOffset(view, node)).ifPresent(offset -> {
                        state.galleryScrollPosition = offset;
                        state.galleryScrollTarget = offset;
                        state.galleryOffset = (int)Math.round(offset);
                    });
            state.revealWidth = width;
            state.revealHeight = height;
        }
        state.gallerySelectedCardId = galleryPresenter.normalizeSelectedCardId(
                context.snapshot(), state.galleryQuery, state.gallerySelectedCardId, draftInput());
        if (state.metadataFocusPending) {
            state.metadataFocusPending = false;
            context.requestRuntimeFocus("gallery", state.gallerySelectedCardId);
        }
        return presentGallery(width, height, mouseX, mouseY,
                bodyPreview != null && !previewStillCurrent(bodyPreview) ? Optional.ofNullable(displayedLeft) : Optional.empty());
    }

    private ViewSpec presentGallery(int width, int height, int mouseX, int mouseY,
            Optional<GalleryPresenter.LeftProjection> retained) {
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
                state.gallerySelectedCardId,
                draftInput(),
                controlsInput(retained));
    }

    int galleryMaximum() {
        return galleryPresenter.maximumScroll(
                context.snapshot(), context.viewportWidth(), context.viewportHeight(), state.galleryQuery, draftInput());
    }

    double positionFromScrollbar(double thumbTop) {
        return galleryPresenter.positionFromScrollbar(context.snapshot(), context.viewportWidth(),
                context.viewportHeight(), state.galleryQuery, thumbTop, draftInput());
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
        if (widgetId.startsWith("gallery.outer_layer.")) {
            String group = widgetId.substring("gallery.outer_layer.".length());
            if (!List.of("head", "body", "legs").contains(group) || context.busy()
                    || !context.galleryReady() || context.currentDisplayKind() != PreviewRenderer.DisplayKind.ORDINARY
                    || bodyPreview == null || !previewStillCurrent(bodyPreview)) return true;
            context.outerLayerVisibilityController().ifPresent(controller -> {
                if (state.selectedPresetId == null) {
                    controller.applyDurable(EditorOuterLayerCycle.cycle(group, controller.current(), reverse ? -1 : 1));
                } else {
                    state.draftMask = EditorOuterLayerCycle.cycle(group, state.draftMask, reverse ? -1 : 1);
                    state.draftGeneration++;
                }
                clearAccessories();
                if (origin.keyboard()) context.requestRuntimeFocus("gallery", widgetId);
                context.publish();
            });
            return true;
        }
        if (widgetId.startsWith("gallery.inspect.")) {
            if (context.busy() || !context.galleryReady()
                    || bodyPreview != null && !previewStillCurrent(bodyPreview)) return true;
            var capes = capeChoices();
            String id = widgetId.substring("gallery.inspect.".length());
            if (capes.stream().noneMatch(c -> c.id().equals(id))) return true;
            if (id.equals("CPM") && (accessoryPreview==null || !accessoryStillCurrent(accessoryPreview) || !accessories.canInspect())) return true;
            state.inspectionIdentity = !capes.isEmpty() && capes.get(0).id().equals(id)
                    && state.inspectionIdentity != null ? null : id;
            state.draftGeneration++;
            state.inspectionScope = inspectionScope();
            clearAccessoryObservation();
            context.publish();
            return true;
        }
        if (widgetId.startsWith("gallery.preset.")) {
            dispatchPresetWidget(widgetId, origin);
            return true;
        }
        if (widgetId.startsWith("gallery.card.")) {
            selectGalleryCard(widgetId, origin);
            return true;
        }
        switch (widgetId) {
            case "gallery.preview_mode" -> {
                if (context.busy() || !context.galleryReady()) return true;
                var view = galleryView(context.viewportWidth(), context.viewportHeight(), 0, 0);
                if (view.widget(widgetId).filter(ViewSpec.Widget::enabled).isEmpty()) return true;
                state.preview = state.preview.cycleCapeMode(true);
                state.draftGeneration++;
                clearAccessoryObservation();
                if (displayedLeft != null) displayedLeft = new GalleryPresenter.LeftProjection(displayedLeft.appearance(),
                        displayedLeft.mask(), displayedLeft.draft(), controlsInput(Optional.empty()), displayedLeft.compatibility());
                context.publish();
                return true;
            }
            case "gallery.favorites", "gallery.your_appearances" -> {
                updateSections(java.util.Map.of(widgetId, !desiredCollapsed(widgetId)), origin, widgetId);
                return true;
            }
            case "gallery.sections" -> {
                boolean desired = !(desiredCollapsed(AccountUiPreferences.GALLERY_FAVORITES) && desiredCollapsed(AccountUiPreferences.GALLERY_YOUR_APPEARANCES));
                updateSections(java.util.Map.of(AccountUiPreferences.GALLERY_FAVORITES, desired,
                        AccountUiPreferences.GALLERY_YOUR_APPEARANCES, desired), origin, state.gallerySelectedCardId);
                return true;
            }
            case "gallery.apply" -> {
                if (!context.busy() && state.selectedPresetId != null) {
                    context.captureGalleryScope(state.draftGeneration).ifPresent(scope ->
                            context.submitGalleryApply(new LibraryEditorPort.GalleryApplyRequest(scope,
                                    state.selectedPresetId, state.baselineMask.equals(state.draftMask) ? Optional.empty()
                                            : Optional.of(new LibraryEditorPort.GalleryMaskPatch(state.baselineMask, state.draftMask)),
                                    state.draftGeneration)));
                }
                return true;
            }
            case "gallery.reset" -> {
                context.captureGalleryScope(state.draftGeneration).ifPresent(context::resetGalleryDraft);
                return true;
            }
            case "gallery.back" -> {
                if (context.busy()) return true;
                if (context.galleryReturnsToProviders()) context.closeToProviders(); else context.closeScreen();
                return true;
            }
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
                if (!context.busy() && !draftInput().dirty(context.snapshot().activePresetId())) context.doneGallery();
                return true;
            }
            default -> { return false; }
        }
    }

    void favoriteConfirmed(UUID presetId, InteractionOrigin origin) {
        state.actionPresetId = null;
        state.pendingPresetDeleteId = null;
        state.gallerySelectedCardId = "gallery.card." + presetId;
        state.metadataFocusPending = origin.keyboard();
    }

    void acceptDraftSelection(UUID presetId) {
        discardDraft();
        state.selectedPresetId = presetId;
    }

    void resetToActive() {
        selectDraft(context.findPreset(context.activePresetId()));
        state.actionPresetId = null;
        state.pendingPresetDeleteId = null;
        state.gallerySelectedCardId = null;
        centerGalleryOnActive();
    }

    void acceptSavedPreset(AppearancePreset preset, UUID presetId) {
        selectDraft(preset);
        state.selectedPresetId = presetId;
        state.selectedSkinId = preset == null ? null : preset.skin().assetId();
    }

    void acceptExternalImport() {
        state.selectedPresetId = null;
    }

    void resetSession() {
        discardDraft();
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
        uiIntentChanged();
        state.activeReveal = false;
        state.galleryScrollPosition = bounded;
        state.galleryScrollTarget = bounded;
        state.galleryOffset = (int) Math.round(bounded);
    }

    void search(String value) {
        uiIntentChanged();
        state.activeReveal = false;
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

    void beginPreviewRotation(ViewSpec view, double x, double y) {
        if (context.busy() || !context.galleryReady()) return;
        var preview = view.previews().stream().filter(p -> p.id().equals("gallery.preview")).findFirst().orElseThrow();
        if (!preview.anchorBounds().contains(x, y) || ViewHostPolicy.pointerOwnerAt(view,x,y).isPresent()) return;
        state.preview = state.preview.beginRotate(preview.anchorBounds(), x, y);
        state.rotationSource = state.preview.rotating() ? candidatePreview().orElseThrow() : null;
    }

    boolean dragPreview(double dx, double dy) {
        if (state.preview == null || !state.preview.rotating()) return false;
        if (context.busy() || !context.galleryReady() || state.rotationSource == null || !previewStillCurrent(state.rotationSource)) {
            endPreviewRotation();
            return false;
        }
        state.preview = state.preview.drag(dx, dy);
        return true;
    }

    void endPreviewRotation() {
        if (state.preview != null) state.preview = state.preview.endRotate();
        state.rotationSource = null;
    }

    void scrollPreview(ViewSpec view, double x, double y, double amount, double pixels) {
        if (context.busy() || !context.galleryReady()) return;
        var surface = view.scrollSurface("gallery.stage").orElseThrow();
        var preview = view.previews().stream().filter(p -> p.id().equals("gallery.preview")).findFirst().orElseThrow();
        boolean control = ViewHostPolicy.pointerOwnerAt(view,x,y).isPresent();
        if (!control && preview.anchorBounds().contains(x, y)) state.preview = state.preview.scroll(preview.anchorBounds(), x, y, amount);
        else if (surface.viewport().contains(x,y)) scrollStageTo(view, surface.offsetPixels() + pixels);
        context.publish();
    }

    void scrollStageTo(ViewSpec view, double offset) {
        if (context.busy() || !context.galleryReady()) return;
        uiIntentChanged();
        state.leftScroll = Math.max(0, Math.min(view.scrollSurface("gallery.stage").orElseThrow().maximumPixels(), offset));
        context.publish();
    }

    private String scrollbarSurface = "gallery.cards";

    void grabStageScrollbar(ViewSpec view, ViewSpec.Scrollbar scrollbar, double mouseX, double mouseY) {
        grabScrollbar(scrollbar,mouseX,mouseY);
        scrollbarSurface="gallery.stage";
        dragScrollbar(view,mouseY);
    }

    void dragScrollbar(ViewSpec view, double mouseY) {
        var bar=ViewHostPolicy.scrollbar(view,scrollbarSurface).orElseThrow();
        int travel=bar.track().height()-bar.thumb().height();
        double offset=travel<=0 ? 0 : Math.max(0,Math.min(1,(mouseY-galleryScrollbarGrabOffset-bar.track().y())/travel))*bar.maximum();
        if (scrollbarSurface.equals("gallery.stage")) scrollStageTo(view,offset);
        else setGalleryPosition(offset);
    }

    boolean grabScrollbar(ViewSpec.Scrollbar scrollbar, double mouseX, double mouseY) {
        scrollbarSurface="gallery.cards";
        draggingGalleryScrollbar = true;
        boolean grabbedThumb = scrollbar.thumb().contains(mouseX, mouseY);
        galleryScrollbarGrabOffset = grabbedThumb ? mouseY - scrollbar.thumb().y() : scrollbar.thumb().height() / 2.0;
        return grabbedThumb;
    }

    double galleryScrollbarGrabOffset() { return galleryScrollbarGrabOffset; }

    boolean draggingGalleryScrollbar() { return draggingGalleryScrollbar; }

    GalleryPresenter galleryPresenter() { return galleryPresenter; }

    interface Context {
        PreviewAssets previewAssets();
        java.util.concurrent.CompletableFuture<Optional<byte[]>> loadSkinPreview(com.naocraftlab.skins.core.model.SkinReference skin);
        void onClient(Runnable action);
        Optional<OuterLayerVisibilityController> outerLayerVisibilityController();
        PreviewRenderer.DisplayKind currentDisplayKind();
        Optional<ViewSpec.BodySource> previewSource(String revision, SkinVariant variant, Optional<UUID> presetId, long draftGeneration);
        boolean galleryReady();
        AccountUiPreferences uiPreferences();
        void submitSections(UiPreferencesPort.GallerySectionsUpdate update, InteractionOrigin origin);
        Optional<LibraryEditorPort.GalleryScope> captureGalleryMetadataScope();
        void submitFavorite(LibraryEditorPort.PresetFavoriteUpdate update, InteractionOrigin origin);
        void submitDelete(LibraryEditorPort.GalleryScope scope, UUID presetId,
                java.util.function.BiConsumer<LibraryEditorPort.PresetDelete, Boolean> completion);
        Optional<LibraryEditorPort.GalleryScope> captureGalleryScope(long generation);
        void submitGalleryApply(LibraryEditorPort.GalleryApplyRequest request);
        void resetGalleryDraft(LibraryEditorPort.GalleryScope scope);
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
        void doneGallery();
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
    }

    private static final class State {
        long uiIntent;
        final java.util.Map<UUID, LibraryEditorPort.PresetFavoriteUpdate> pendingFavorites = new java.util.HashMap<>();
        final java.util.Map<String, UiPreferencesPort.GallerySectionsUpdate> pendingSections = new java.util.HashMap<>();
        PreviewInteractionModel preview;
        ViewSpec.Preview rotationSource;
        double leftScroll;
        String inspectionIdentity;
        String inspectionScope;
        boolean draftInitialized;
        boolean revealInitialized;
        long openingGeneration;
        boolean activeReveal = true;
        int revealWidth;
        int revealHeight;
        boolean metadataFocusPending;
        String temporarilyRevealedSection;

        volatile long draftGeneration;
        OuterLayerVisibility baselineMask = OuterLayerVisibility.allVisible();
        OuterLayerVisibility draftMask = OuterLayerVisibility.allVisible();
        UUID actionPresetId;
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

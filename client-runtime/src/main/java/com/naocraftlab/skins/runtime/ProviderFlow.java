package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.OuterLayerVisibilityController;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.ScreenDestination;
import com.naocraftlab.skins.core.compatibility.SkinCompatibility;
import com.naocraftlab.skins.core.compatibility.SkinCompatibilityEvaluator;
import com.naocraftlab.skins.core.compatibility.SkinCompatibilityStatus;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderCape;
import com.naocraftlab.skins.core.provider.ProviderObservation;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;

import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

final class ProviderFlow {
    private final State state = new State();
    private final Context context;
    private CompletableFuture<Void> providerConfigurationWrite = CompletableFuture.completedFuture(null);
    private OptiFineAccountLinkUseCases optiFineAccountLink;
    private boolean optiFineLinkClaimed;
    private UiMessage optiFineLinkFeedback;
    private long optiFineLinkAttempt;
    private static final URI SKINMC_ACCOUNT_URI = URI.create("https://skinmc.net/account/capes");
    private static final URI MINECRAFTCAPES_ACCOUNT_URI = URI.create("https://minecraftcapes.net/account/profile");
    private static final URI SNEAKY_EDITOR_URI = URI.create("https://penguinspy.neocities.org/projects/loom/");
    private UUID fixedAccountLinkAccountId;
    private BuiltinProvider fixedAccountLinkProvider;
    private boolean fixedAccountLinkPending;
    private boolean fixedAccountLinkClaimed;
    private boolean sneakyEditorLinkPending;
    private boolean sneakyEditorLinkClaimed;
    private boolean draggingProviderRowsScrollbar;
    private double providerRowsScrollbarGrabOffset;

    ProviderFlow(Context context) { this.context = Objects.requireNonNull(context, "context"); }

    Optional<URI> consumeReadyOptiFineAccountLink() {
        if (!liveOptiFineLinkView() || optiFineLinkClaimed) return Optional.empty();
        URI uri = optiFineAccountLink.readyUri(context.account().accountId());
        if (uri == null) return Optional.empty();
        optiFineLinkClaimed = true;
        return Optional.of(uri);
    }

    Optional<URI> currentOptiFineAccountLink() {
        if (!liveOptiFineLinkView() || !optiFineLinkClaimed) return Optional.empty();
        return Optional.ofNullable(optiFineAccountLink.readyUri(context.account().accountId()));
    }

    void finishOptiFineAccountLink() {
        context.onClient(this::cancelOptiFineAccountLink);
    }

    Optional<URI> consumeReadySkinMcAccountLink() {
        return consumeReadyFixedAccountLink(BuiltinProvider.SKINMC);
    }

    Optional<URI> currentSkinMcAccountLink() {
        return currentFixedAccountLink(BuiltinProvider.SKINMC);
    }

    void finishSkinMcAccountLink() {
        finishFixedAccountLink(BuiltinProvider.SKINMC);
    }

    Optional<URI> consumeReadyFixedAccountLink(BuiltinProvider provider) {
        if (!liveFixedAccountLinkView(provider) || !fixedAccountLinkPending || fixedAccountLinkClaimed)
            return Optional.empty();
        fixedAccountLinkClaimed = true;
        return Optional.of(fixedAccountUri(provider));
    }

    Optional<URI> currentFixedAccountLink(BuiltinProvider provider) {
        return liveFixedAccountLinkView(provider) && fixedAccountLinkClaimed
                ? Optional.of(fixedAccountUri(provider)) : Optional.empty();
    }

    void finishFixedAccountLink(BuiltinProvider provider) {
        context.onClient(() -> {
            if (fixedAccountLinkProvider == provider) cancelFixedAccountLink();
        });
    }

    Optional<URI> consumeReadySneakyEditorLink() {
        if (!liveSneakyEditorLinkView() || !sneakyEditorLinkPending || sneakyEditorLinkClaimed) return Optional.empty();
        sneakyEditorLinkClaimed = true;
        return Optional.of(SNEAKY_EDITOR_URI);
    }

    Optional<URI> currentSneakyEditorLink() {
        return liveSneakyEditorLinkView() && sneakyEditorLinkClaimed
                ? Optional.of(SNEAKY_EDITOR_URI) : Optional.empty();
    }

    void finishSneakyEditorLink() {
        context.onClient(this::cancelSneakyEditorLink);
    }

    void expireOptiFineAccountLink() {
        context.onClient(() -> {
            boolean expired = optiFineAccountLink != null && optiFineAccountLink.expired();
            cancelOptiFineAccountLink();
            optiFineLinkFeedback = expired
                    ? UiMessage.error("nclskins.providers.link_expired") : null;
            context.publish();
        });
    }

    void cancelOptiFineAccountLink() {
        optiFineLinkAttempt++;
        if (optiFineAccountLink != null) optiFineAccountLink.cancel();
        optiFineLinkClaimed = false;
        optiFineLinkFeedback = null;
    }

    void cancelFixedAccountLink() {
        fixedAccountLinkPending = false;
        fixedAccountLinkClaimed = false;
        fixedAccountLinkAccountId = null;
        fixedAccountLinkProvider = null;
    }

    void cancelSneakyEditorLink() {
        sneakyEditorLinkPending = false;
        sneakyEditorLinkClaimed = false;
    }

    boolean liveSneakyEditorLinkView() {
        return context.lifecycle() == ClientSnapshot.Lifecycle.READY
                && (context.providersOpen() || !context.providers().galleryAvailable())
                && !state.providerAdding && context.editor() == null
                && state.providerComponent == AppearanceProviders.Component.CAPE
                && context.providers().cape().enabled(BuiltinProvider.SNEAKY);
    }

    boolean liveFixedAccountLinkView(BuiltinProvider provider) {
        return (provider == BuiltinProvider.SKINMC || provider == BuiltinProvider.MINECRAFTCAPES)
                && fixedAccountLinkProvider == provider
                && context.account() != null && context.account().accountId().equals(fixedAccountLinkAccountId)
                && context.lifecycle() == ClientSnapshot.Lifecycle.READY
                && (context.providersOpen() || !context.providers().galleryAvailable())
                && !state.providerAdding && context.editor() == null
                && state.providerComponent == AppearanceProviders.Component.CAPE
                && context.providers().cape().enabled(provider);
    }

    private static URI fixedAccountUri(BuiltinProvider provider) {
        return switch (provider) {
            case SKINMC -> SKINMC_ACCOUNT_URI;
            case MINECRAFTCAPES -> MINECRAFTCAPES_ACCOUNT_URI;
            default -> throw new IllegalArgumentException("Provider has no fixed account link: " + provider);
        };
    }

    boolean liveOptiFineLinkView() {
        return optiFineAccountLink != null && context.account() != null
                && context.lifecycle() == ClientSnapshot.Lifecycle.READY
                && (context.providersOpen() || !context.providers().galleryAvailable())
                && !state.providerAdding && context.editor() == null
                && state.providerComponent == AppearanceProviders.Component.CAPE
                && context.providers().cape().enabled(BuiltinProvider.OPTIFINE);
    }

    void selectProvidersTab(AppearanceProviders.Component tab) {
        state.providerComponent = tab;
        if (context.account() == null || context.uiPreferences() == null
                || context.uiPreferences().selectedProvidersTab() == tab) return;
        UUID accountId = context.account().accountId();
        context.providersTabSelected(tab);
        context.persistUiPreference(() -> {
            context.uiPreferencesPort().setSelectedProvidersTab(accountId, tab);
            return null;
        });
    }

    void setProviderRowsFromScrollbar(ViewSpec.Scrollbar scrollbar, double thumbY) {
        int travel = scrollbar.track().height() - scrollbar.thumb().height();
        double fraction = travel <= 0 ? 0.0
                : (thumbY - scrollbar.track().y()) / travel;
        state.providerRowsOffset = Math.max(0, Math.min(scrollbar.maximum(),
                fraction * scrollbar.maximum()));
        context.publish();
    }

    void dispatchProviderWidget(String id) {
        dispatchProviderWidget(id, InteractionOrigin.PROGRAMMATIC);
    }

    void dispatchProviderWidget(String id, InteractionOrigin origin) {
        if (id.startsWith("providers.") && !id.equals("providers.back")) context.showProviders();
        if (id.equals("gallery.providers")) {
            context.showProviders();
            state.providerPreviewSources.clear();
            context.submit(UiMessage.info("nclskins.providers.title"), context.providerOperations()::reloadProviders, appearance -> {
                acceptProviderChange(appearance);
                adoptSharedCapeObservation(appearance.providers());
            });
            state.providerPreview = PreviewInteractionModel.editor(context.viewportHeight(), context.preferredCapeMode());
        } else if (id.equals("providers.back")) {
            cancelOptiFineAccountLink();
            cancelFixedAccountLink();
            cancelSneakyEditorLink();
            if (state.providerAdding) state.providerAdding = false;
            else if (context.rootDestination() == ScreenDestination.PROVIDERS) context.closeScreenOnClient();
            else if (context.providers().galleryAvailable()) context.returnToGallery();
            else context.closeScreenOnClient();
        } else if (id.startsWith("providers.tab.")) {
            cancelOptiFineAccountLink();
            cancelFixedAccountLink();
            cancelSneakyEditorLink();
            selectProvidersTab(AppearanceProviders.Component.valueOf(id.substring(14)));
            state.providerPreviewSources.clear();
            state.providerAdding = false;
            state.providerRowsOffset = 0;
        } else if (id.equals("providers.add")) {
            cancelOptiFineAccountLink();
            cancelFixedAccountLink();
            cancelSneakyEditorLink();
            state.providerAdding = true;
            state.providerChooserOffset = 0;
            state.providerRowsOffset = 0;
        } else if (id.equals("providers.preview_mode")) {
            state.providerPreview = state.providerPreview.cycleCapeMode(true);
            context.providerPreviewModeChanged(state.providerPreview.capeMode());
        } else {
            var component = state.providerComponent;
            String[] action = id.split("\\.");
            if (action.length < 2 || context.busy()) return;
            if (id.equals("providers.refresh")) {
                BuiltinProvider selected = normalizedSelection(component);
                ProviderRefreshRequest request = selected == null ? ProviderRefreshRequest.all(component)
                        : ProviderRefreshRequest.one(component, selected);
                if (refreshPending()) return;
                var cooldowns = new java.util.EnumMap<BuiltinProvider, java.time.Duration>(BuiltinProvider.class);
                for (BuiltinProvider provider : request.resolve(context.providers()))
                    context.capeObservations().capeProviderCooldown(provider).ifPresent(value -> cooldowns.put(provider, value));
                if (request.blockedByRateLimit(context.providers(), context.clientSessionView().rateLimitRemaining(), cooldowns)) return;
                context.submitProviderRefresh(UiMessage.info(selected == null
                        ? "nclskins.providers.refresh_all" : "nclskins.providers.refresh"),
                        () -> context.providerRefresh().refresh(request, context.providers()));
            } else if (action.length == 3) {
                BuiltinProvider provider = BuiltinProvider.valueOf(action[2]);
                if (provider == BuiltinProvider.CPM && (!context.snapshot().skinExtensionEnvironment().cpmPresent()
                        || component != AppearanceProviders.Component.CAPE)) return;
                String verb = action[1];
                if (verb.equals("edit")) {
                    if (state.providerAdding || !context.providers().galleryAvailable()
                            || !provider.canWrite(component)
                            || !(component == AppearanceProviders.Component.SKIN ? context.providers().skin().order()
                            : context.providers().cape().order()).contains(provider)) return;
                    cancelOptiFineAccountLink();
                    cancelFixedAccountLink();
                    cancelSneakyEditorLink();
                    context.editProvider(new EditRequest(component, provider));
                    context.publish();
                    return;
                }
                if (verb.equals("account")) {
                    if ((provider != BuiltinProvider.OPTIFINE && provider != BuiltinProvider.SKINMC
                            && provider != BuiltinProvider.SNEAKY && provider != BuiltinProvider.MINECRAFTCAPES)
                            || component != AppearanceProviders.Component.CAPE
                            || state.providerAdding || !context.providers().cape().order().contains(provider)) return;
                    if (provider == BuiltinProvider.OPTIFINE) prepareOptiFineAccountLink();
                    else if (provider == BuiltinProvider.SNEAKY) {
                        sneakyEditorLinkPending = true;
                        sneakyEditorLinkClaimed = false;
                        context.publish();
                    } else {
                        fixedAccountLinkAccountId = context.account().accountId();
                        fixedAccountLinkProvider = provider;
                        fixedAccountLinkPending = true;
                        fixedAccountLinkClaimed = false;
                        context.publish();
                    }
                    return;
                }
                if (verb.equals("row") && state.providerAdding && (component == AppearanceProviders.Component.SKIN
                        ? context.providers().skin().order() : context.providers().cape().order()).contains(provider)) return;
                if (verb.equals("row") && !state.providerAdding) {
                    if (provider == BuiltinProvider.CPM && !state.accessories.canInspect()) return;
                    if (component == AppearanceProviders.Component.SKIN)
                        acceptAccessories(com.naocraftlab.skins.client.PreviewAccessories.unavailable(
                                PreviewRenderer.LiveReason.LOADING));
                    state.providerPreviewSources.put(component, provider);
                    context.publish();
                    return;
                }
                if (verb.equals("remove") && provider == BuiltinProvider.OPTIFINE
                        && component == AppearanceProviders.Component.CAPE) cancelOptiFineAccountLink();
                if (verb.equals("remove") && provider == fixedAccountLinkProvider
                        && component == AppearanceProviders.Component.CAPE) cancelFixedAccountLink();
                if (verb.equals("remove") && provider == BuiltinProvider.SNEAKY
                        && component == AppearanceProviders.Component.CAPE) cancelSneakyEditorLink();
                int previousIndex = (component == AppearanceProviders.Component.SKIN ? context.providers().skin().order() : context.providers().cape().visibleOrder(context.snapshot().skinExtensionEnvironment().cpmPresent())).indexOf(provider);
                UUID accountId = context.account().accountId();
                long revision = (component == AppearanceProviders.Component.SKIN
                        ? context.providers().skin() : context.providers().cape()).configurationRevision();
                submitProviderConfiguration(() -> switch (verb) {
                    case "row" -> context.providerOperations().enableProvider(accountId, component, provider, revision);
                    case "remove" -> context.providerOperations().disableProvider(accountId, component, provider, revision);
                    case "up" -> context.providerOperations().moveProvider(accountId, component, provider, -1, revision);
                    case "down" -> context.providerOperations().moveProvider(accountId, component, provider, 1, revision);
                    default -> throw new IllegalArgumentException("Unknown provider action");
                }, appearance -> {
                    CompletableFuture<AppearanceRefresh.Result> providerRebind =
                            acceptProviderChange(appearance);
                    context.capeObservations().optiFineConfigurationChanged();
                    state.providerAdding = false;
                    if (origin == InteractionOrigin.KEYBOARD) {
                        var remaining = component == AppearanceProviders.Component.SKIN ? context.providers().skin().order()
                                : context.providers().cape().visibleOrder(context.snapshot().skinExtensionEnvironment().cpmPresent());
                        String target = "providers.row." + provider.name();
                        if (verb.equals("remove")) target = remaining.isEmpty() ? "providers.add"
                                : "providers.row." + remaining.get(Math.max(0, Math.min(previousIndex, remaining.size() - 1))).name();
                        context.requestRuntimeFocus("providers", target);
                    }
                    if (verb.equals("remove") && state.providerPreviewSources.get(component) == provider) state.providerPreviewSources.remove(component);
                    if (verb.equals("row")) context.reconcileAfterLocalRebind(
                            providerRebind, Trigger.LOCAL_INTENT);
                });
            }
        }
        context.publish();
    }

    void prepareOptiFineAccountLink() {
        if (!liveOptiFineLinkView() || optiFineAccountLink.preparing()) return;
        UUID accountId = context.account().accountId();
        long attempt = ++optiFineLinkAttempt;
        optiFineLinkClaimed = false;
        optiFineLinkFeedback = UiMessage.info("nclskins.providers.link_preparing");
        context.publish();
        optiFineAccountLink.begin(accountId).whenComplete((result, failure) -> context.onClient(() -> {
            if (context.disposed() || attempt != optiFineLinkAttempt || !liveOptiFineLinkView()
                    || !accountId.equals(context.account().accountId())) return;
            OptiFineAccountLinkUseCases.Outcome outcome = failure == null ? result.outcome() : OptiFineAccountLinkUseCases.Outcome.FAILED;
            optiFineLinkFeedback = switch (outcome) {
                case READY, CANCELLED -> null;
                case AUTH_REQUIRED -> UiMessage.error("nclskins.providers.link_auth_required");
                case FAILED -> UiMessage.error("nclskins.providers.link_failed");
                case EXPIRED -> UiMessage.error("nclskins.providers.link_expired");
            };
            context.publish();
        }));
    }

    void submitProviderConfiguration(ThrowingSupplier<ClientOperations.DurableAppearance> operation,
            Consumer<ClientOperations.DurableAppearance> completion) {
        UUID accountId = context.account().accountId();
        providerConfigurationWrite = providerConfigurationWrite.handle((ignored, failure) -> null).thenRunAsync(() -> {
            if (context.disposed() || context.account() == null || !accountId.equals(context.account().accountId())) return;
            try {
                var appearance = operation.get();
                context.onClient(() -> {
                    if (context.disposed() || context.lifecycle() == ClientSnapshot.Lifecycle.CLOSED || context.account() == null
                            || !accountId.equals(context.account().accountId()) || !context.currentSessionOwns(appearance)
                            || appearance.providers().skin().configurationRevision() < context.providers().skin().configurationRevision()
                            || appearance.providers().cape().configurationRevision() < context.providers().cape().configurationRevision()
                            || appearance.intentRevision() < context.intentRevision()) return;
                    completion.accept(appearance);
                    context.publish();
                });
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                context.diagnose(DiagnosticEvent.CLIENT_ASYNC_OPERATION_FAILED, failure);
                context.onClient(() -> {
                    if (context.account() != null && accountId.equals(context.account().accountId())) { context.showFeedback(context.operationFailure(failure)); context.publish(); }
                });
            }
        }, context.worker());
    }

    void acceptProviderSnapshot(ClientOperations.DurableAppearance appearance) {
        context.acceptProviderSnapshot(appearance);
    }

    CompletableFuture<AppearanceRefresh.Result> acceptProviderChange(
            ClientOperations.DurableAppearance appearance) {
        return context.acceptProviderChange(appearance);
    }

    boolean providerChainChanged(
            com.naocraftlab.skins.core.provider.ProviderChannel<?> before,
            com.naocraftlab.skins.core.provider.ProviderChannel<?> after) {
        if (!before.order().equals(after.order())) return true;
        for (BuiltinProvider provider : before.order()) {
            if (!before.observation(provider).equals(after.observation(provider))) return true;
        }
        return false;
    }

    void adoptSharedCapeObservation(AppearanceProviders providers) {
        if (context.account() == null) return;
        try {
            GameSessionTokenSource.SessionIdentity identity = context.clientSessionView().sessionIdentity();
            if (identity.profileId().equals(context.account().accountId())) {
                context.capeObservations().adoptSharedCapeObservation(identity.profileId(), identity.profileName(), providers);
            }
        } catch (RuntimeException unavailable) {
            return;
        }
    }

    boolean acceptAccessories(com.naocraftlab.skins.client.PreviewAccessories value) {
        if (state.accessories.equals(value)) return false;
        if (!AccessoryInspection.current(state.accessories, value))
            state.providerPreviewSources.remove(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM);
        state.accessories = value;
        return true;
    }

    boolean previewStillCurrent(ViewSpec.Preview preview) {
        var selected = normalizedSelection(AppearanceProviders.Component.SKIN);
        var skin = selected == null ? context.providers().skin().resolve().map(com.naocraftlab.skins.core.provider.ProviderChannel.Resolved::value)
                : java.util.Optional.ofNullable(context.providers().skin().observation(selected).value());
        return preview.imageRevision().equals(skin.map(value -> "provider:skin:" + value.sha256()).orElse("provider:default"))
                && preview.variant() == skin.map(com.naocraftlab.skins.core.provider.ProviderSkin::variant).orElse(context.currentPlayerVariant());
    }

    private boolean nativePreview() {
        if (!state.accessories.canInspect() || !state.accessories.present()) return false;
        BuiltinProvider selected = normalizedSelection(AppearanceProviders.Component.CAPE);
        if (selected != null) return selected == BuiltinProvider.CPM;
        for (BuiltinProvider provider : context.providers().cape().order()) {
            if (provider == BuiltinProvider.CPM) return context.snapshot().skinExtensionEnvironment().cpmPresent();
            if (context.providers().cape().observation(provider).value() != null) return false;
        }
        return false;
    }

    private BuiltinProvider normalizedSelection(AppearanceProviders.Component component) {
        BuiltinProvider selected = state.providerPreviewSources.get(component);
        var order = component == AppearanceProviders.Component.SKIN ? context.providers().skin().order()
                : context.providers().cape().visibleOrder(context.snapshot().skinExtensionEnvironment().cpmPresent());
        if (selected != null && !order.contains(selected)) {
            state.providerPreviewSources.remove(component);
            return null;
        }
        return selected;
    }

    private boolean showOuterLayers() {
        return context.providerScreenVisible() && !state.providerAdding && !context.providers().galleryAvailable()
                && context.currentDisplayKind() == com.naocraftlab.skins.client.PreviewRenderer.DisplayKind.ORDINARY;
    }

    boolean dispatchProviderAction(String widgetId, boolean reverse, InteractionOrigin origin) {
        if (widgetId.startsWith("providers.outer_layer.")) {
            String group = widgetId.substring("providers.outer_layer.".length());
            if (!List.of("head", "body", "legs").contains(group) || !showOuterLayers() || context.busy()) return true;
            context.outerLayerVisibilityController().ifPresent(controller -> {
                controller.applyDurable(EditorOuterLayerCycle.cycle(group, controller.current(), reverse ? -1 : 1));
                if (origin.keyboard()) context.requestRuntimeFocus("providers", widgetId);
                context.publish();
            });
            return true;
        }
        if (widgetId.equals("gallery.providers") || widgetId.startsWith("providers.")) {
            dispatchProviderWidget(widgetId, origin);
            return true;
        }
        switch (widgetId) {

            default -> { return false; }
        }
    }

    ViewSpec presentProvider(int width, int height) {
            if (state.providerAdding) return context.withRuntimeFocus(new ProvidersPresenter().presentChooser(
                    context.providers(), state.providerComponent, context.busy(), width, height,
                    state.providerChooserOffset, context.snapshot().skinExtensionEnvironment().cpmPresent()));
            ViewSpec providerView = new ProvidersPresenter().present(context.providers(), state.providerComponent,
                    state.providerAdding, context.busy(), state.providerPreview.withOuterLayerVisibility(
                    context.outerLayerVisibilityController().map(OuterLayerVisibilityController::current).orElse(OuterLayerVisibility.allVisible())),
                    context.currentPlayerVariant(), width, height, normalizedSelection(AppearanceProviders.Component.SKIN),
                    normalizedSelection(AppearanceProviders.Component.CAPE), context.snapshot().rateLimitProgress(),
                    optiFineAccountLink != null && optiFineAccountLink.preparing(), optiFineLinkFeedback,
                    state.providerRowsOffset, context.textResolver(), context.snapshot().capeProviderCooldowns(),
                    context.outerLayerVisibilityController().isPresent(), refreshPending(),
                    context.snapshot().skinExtensionEnvironment().cpmPresent(), showOuterLayers());
            SkinFeatureEvidence evidence = Optional.of(providerView.previews().get(0).imageRevision()).filter(revision -> revision.startsWith("provider:skin:")).flatMap(revision -> context.snapshot().account().flatMap(account ->
                    account.skinAssets().stream().filter(asset -> asset.sha256().equals(revision.substring(14))).findFirst()))
                    .map(asset -> context.snapshot().assetEvidence().getOrDefault(asset.id(), SkinFeatureEvidence.ORDINARY)).orElse(SkinFeatureEvidence.ORDINARY);
            SkinCompatibility compatibility = new SkinCompatibilityEvaluator().evaluate(evidence, context.snapshot().skinExtensionEnvironment());
            if (compatibility.status() != SkinCompatibilityStatus.ORDINARY) {
                providerView = providerView.withCompatibilityIndicator("providers.compatibility", new Bounds(2, Math.max(35, height - 55), 20, 20),
                        CompatibilityMessages.accessibleLabel(compatibility), CompatibilityMessages.icon(compatibility), Optional.empty());
            }
            providerView = AccessoryInspection.present(providerView, state.accessories, nativePreview(),
                    context.account() != null && CapeProjection.cpmWins(context.account().accountId()), state.providerPreview.capeMode(), context.busy());
            if (context.snapshot().skinExtensionEnvironment().cpmPresent()
                    && evidence.supportedFeatures().contains(com.naocraftlab.skins.core.compatibility.SkinFeature.CUSTOMIZABLE_PLAYER_MODELS)
                    && state.accessories.scope() != null
                    && state.accessories.availability() == com.naocraftlab.skins.client.PreviewAccessories.Availability.FAILED) {
                providerView = PlayerAppearanceStage.withError(providerView,
                        UiMessage.error("nclskins.editor.cpm_preview_failed"), context.textResolver());
            }
            ViewSpec focused = context.withRuntimeFocus(providerView);
            if (focused.focusRequest().filter(request -> request.widgetId().startsWith("providers.outer_layer.")
                    && focused.widget(request.widgetId()).isEmpty()).isPresent()) {
                context.requestRuntimeFocus("providers", "providers.tab." + state.providerComponent.name());
                return context.withRuntimeFocus(providerView);
            }
            return focused;
            }

    void acceptPreviewMode(PreviewRenderer.CapeMode mode) {
        state.providerPreview = state.providerPreview.withCapeMode(mode);
    }

    record EditRequest(AppearanceProviders.Component component, BuiltinProvider provider) {}

    void resetSession() {
        state.providerAdding = false;
        state.providerPreviewSources.clear();
    }

    void clearPreviewSources() {
        state.providerPreviewSources.clear();
    }

    void leaveChooser() {
        state.providerAdding = false;
    }

    void invalidateCapePreview() {
        state.providerPreviewSources.remove(AppearanceProviders.Component.CAPE);
    }

    void invalidateSkinPreview() {
        state.providerPreviewSources.remove(AppearanceProviders.Component.SKIN);
    }

    void restoreTab(AppearanceProviders.Component tab) {
        state.providerComponent = tab;
    }

    void scrollChooserTo(double offsetPixels) {
        state.providerChooserOffset = offsetPixels;
    }

    void scrollRowsTo(double offsetPixels) {
        state.providerRowsOffset = offsetPixels;
    }

    void beginPreviewRotation(double mouseX, double mouseY) {
        state.providerPreview = state.providerPreview.beginRotate(new Bounds(0, 0, context.viewportWidth() / 2, context.viewportHeight()), mouseX, mouseY);
    }

    void dragPreview(double deltaX, double deltaY) {
        state.providerPreview = state.providerPreview.drag(deltaX, deltaY);
    }

    void endPreviewRotation() {
        state.providerPreview = state.providerPreview.endRotate();
    }

    void scrollPreview(double mouseX, double mouseY, double verticalAmount) {
        state.providerPreview = state.providerPreview.scroll(new Bounds(0, 0, context.viewportWidth() / 2, context.viewportHeight()), mouseX, mouseY, verticalAmount);
    }

    boolean providerAdding() { return state.providerAdding; }

    PreviewInteractionModel providerPreview() { return state.providerPreview; }

    java.util.Optional<BuiltinProvider> inspectedCapeSource() {
        return java.util.Optional.ofNullable(
                state.providerPreviewSources.get(AppearanceProviders.Component.CAPE));
    }

    void releaseScrollbar() { draggingProviderRowsScrollbar = false; }

    void grabScrollbar(ViewSpec.Scrollbar scrollbar, double mouseX, double mouseY) {
        draggingProviderRowsScrollbar = true;
        providerRowsScrollbarGrabOffset = scrollbar.thumb().contains(mouseX, mouseY)
                ? mouseY - scrollbar.thumb().y() : scrollbar.thumb().height() / 2.0;
    }

    void useOptiFineAccountLink(OptiFineAccountLinkUseCases link) {
        optiFineAccountLink = Objects.requireNonNull(link, "link");
    }

    void clearAccountLinkFeedback() { optiFineLinkFeedback = null; }

    boolean expireAccountLink() {
        if (optiFineAccountLink == null || !optiFineAccountLink.expired()) return false;
        cancelOptiFineAccountLink();
        optiFineLinkFeedback = UiMessage.error("nclskins.providers.link_expired");
        return true;
    }

    double providerRowsScrollbarGrabOffset() { return providerRowsScrollbarGrabOffset; }

    boolean draggingProviderRowsScrollbar() { return draggingProviderRowsScrollbar; }

    boolean refreshPending() {
        if (context.initialProviderRefreshPending()) return true;
        if (context.account() == null) return false;
        var component = state.providerComponent;
        var selected = normalizedSelection(component);
        var request = selected == null ? ProviderRefreshRequest.all(component)
                : ProviderRefreshRequest.one(component, selected);
        return context.providerRefresh().refreshing(request, context.providers());
    }

    interface Context {
        AccountState account();
        ClientSnapshot.Lifecycle lifecycle();
        boolean providersOpen();
        boolean providerScreenVisible();
        com.naocraftlab.skins.client.PreviewRenderer.DisplayKind currentDisplayKind();
        AppearanceProviders providers();
        PresetEditorModel editor();
        AccountUiPreferences uiPreferences();
        UiPreferencesPort uiPreferencesPort();
        ProviderOperations providerOperations();
        ProviderRefreshUseCases providerRefresh();
        boolean initialProviderRefreshPending();
        void submitProviderRefresh(UiMessage progress, java.util.function.Supplier<CompletableFuture<ProviderRefreshResult>> operation);
        ClientSessionView clientSessionView();
        int viewportHeight();
        int viewportWidth();
        PreviewRenderer.CapeMode preferredCapeMode();
        ScreenDestination rootDestination();
        boolean busy();
        CapeObservationPort capeObservations();
        boolean disposed();
        long intentRevision();
        Executor worker();
        Optional<ServerAppearanceReadiness> serverAppearanceReadiness();
        Optional<OuterLayerVisibilityController> outerLayerVisibilityController();
        ClientSnapshot snapshot();
        TextResolver textResolver();
        void closeScreenOnClient();
        ViewSpec withRuntimeFocus(ViewSpec view);
        void requestRuntimeFocus(String screenId, String widgetId);
        void diagnose(DiagnosticEvent event, Throwable failure);
        boolean currentSessionOwns(ClientOperations.InitialData data);
        void persistUiPreference(ThrowingSupplier<Void> operation);
        void reconcileAfterLocalRebind(
            CompletableFuture<AppearanceRefresh.Result> localRebind,
            Trigger trigger);
        boolean currentSessionOwns(ClientOperations.DurableAppearance appearance);
        void reconcileAfterLocalRebind(
            CompletableFuture<AppearanceRefresh.Result> localRebind,
            ClientOperations.ReconciliationKey key,
            Trigger trigger);
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
        void publish();
        void onClient(Runnable action);
        SkinVariant currentPlayerVariant();
        UiMessage operationFailure(Throwable failure);
        void showFeedback(UiMessage message);
        void providersTabSelected(AppearanceProviders.Component tab);
        void providerPreviewModeChanged(PreviewRenderer.CapeMode mode);
        void showProviders();
        void returnToGallery();
        void editProvider(ProviderFlow.EditRequest request);
        void acceptProviderSnapshot(ClientOperations.DurableAppearance appearance);
        CompletableFuture<AppearanceRefresh.Result> acceptProviderChange(ClientOperations.DurableAppearance appearance);
    }

    private static final class State {
        com.naocraftlab.skins.client.PreviewAccessories accessories =
                com.naocraftlab.skins.client.PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LOADING);
        final java.util.EnumMap<AppearanceProviders.Component, BuiltinProvider> providerPreviewSources = new java.util.EnumMap<>(AppearanceProviders.Component.class);
        boolean providerAdding;
        double providerChooserOffset;
        double providerRowsOffset;
        AppearanceProviders.Component providerComponent = AppearanceProviders.Component.SKIN;
        PreviewInteractionModel providerPreview = PreviewInteractionModel.editor(240, PreviewRenderer.CapeMode.CAPE);
    }
}

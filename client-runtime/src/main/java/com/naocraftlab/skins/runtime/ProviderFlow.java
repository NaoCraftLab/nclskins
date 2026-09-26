package com.naocraftlab.skins.runtime;

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
        if (!liveSkinMcLinkView() || !skinMcLinkPending || skinMcLinkClaimed) return Optional.empty();
        skinMcLinkClaimed = true;
        return Optional.of(SKINMC_ACCOUNT_URI);
    }

    Optional<URI> currentSkinMcAccountLink() {
        return liveSkinMcLinkView() && skinMcLinkClaimed
                ? Optional.of(SKINMC_ACCOUNT_URI) : Optional.empty();
    }

    void finishSkinMcAccountLink() {
        context.onClient(this::cancelSkinMcAccountLink);
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

    void cancelSkinMcAccountLink() {
        skinMcLinkPending = false;
        skinMcLinkClaimed = false;
        skinMcLinkAccountId = null;
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

    boolean liveSkinMcLinkView() {
        return context.account() != null && context.account().accountId().equals(skinMcLinkAccountId)
                && context.lifecycle() == ClientSnapshot.Lifecycle.READY
                && (context.providersOpen() || !context.providers().galleryAvailable())
                && !state.providerAdding && context.editor() == null
                && state.providerComponent == AppearanceProviders.Component.CAPE
                && context.providers().cape().enabled(BuiltinProvider.SKINMC);
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
            cancelSkinMcAccountLink();
            cancelSneakyEditorLink();
            if (state.providerAdding) state.providerAdding = false;
            else if (context.rootDestination() == ScreenDestination.PROVIDERS) context.closeScreenOnClient();
            else if (context.providers().galleryAvailable()) context.returnToGallery();
            else context.closeScreenOnClient();
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
            context.providerPreviewModeChanged(state.providerPreview.capeMode());
        } else {
            var component = state.providerComponent;
            String[] action = id.split("\\.");
            if (action.length < 2 || context.busy()) return;
            if (id.equals("providers.refresh")) {
                RefreshComparison comparison = captureRefreshComparison(component);
                context.submit(UiMessage.info("nclskins.providers.refresh"),
                        () -> context.providerOperations().refreshProvidersWithObservation(component), result -> {
                    ClientOperations.DurableAppearance appearance = result.appearance();
                    acceptProviderChange(appearance);
                    if (component == AppearanceProviders.Component.CAPE) {
                        AppearanceProviders confirmedMinecraft = appearance.providers();
                        CompletableFuture<ProviderObservation<ProviderCape>> optifine = new CompletableFuture<>();
                        CompletableFuture<ProviderObservation<ProviderCape>> skinmc = new CompletableFuture<>();
                        CompletableFuture.allOf(optifine, skinmc).thenRun(() -> context.onClient(() ->
                                finishRefreshComparison(comparison, confirmedMinecraft,
                                        result.confirmedMinecraft(), optifine.join(), skinmc.join())));
                        try {
                            context.capeObservations().refreshOptiFineCapes(optifine::complete);
                        } catch (RuntimeException unavailable) {
                            optifine.complete(null);
                        }
                        try {
                            context.capeObservations().refreshSkinMcCapes(skinmc::complete);
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
                    if (state.providerAdding || !context.providers().galleryAvailable()
                            || !provider.writable()
                            || !(component == AppearanceProviders.Component.SKIN ? context.providers().skin().order()
                            : context.providers().cape().order()).contains(provider)) return;
                    cancelOptiFineAccountLink();
                    cancelSkinMcAccountLink();
                    cancelSneakyEditorLink();
                    context.editProvider(new EditRequest(component, provider));
                    context.publish();
                    return;
                }
                if (verb.equals("account")) {
                    if ((provider != BuiltinProvider.OPTIFINE && provider != BuiltinProvider.SKINMC
                            && provider != BuiltinProvider.SNEAKY)
                            || component != AppearanceProviders.Component.CAPE
                            || state.providerAdding || !context.providers().cape().order().contains(provider)) return;
                    if (provider == BuiltinProvider.OPTIFINE) prepareOptiFineAccountLink();
                    else if (provider == BuiltinProvider.SNEAKY) {
                        sneakyEditorLinkPending = true;
                        sneakyEditorLinkClaimed = false;
                        context.publish();
                    } else {
                        skinMcLinkAccountId = context.account().accountId();
                        skinMcLinkPending = true;
                        skinMcLinkClaimed = false;
                        context.publish();
                    }
                    return;
                }
                if (verb.equals("row") && state.providerAdding && (component == AppearanceProviders.Component.SKIN
                        ? context.providers().skin().order() : context.providers().cape().order()).contains(provider)) return;
                if (verb.equals("row") && !state.providerAdding) { state.providerPreviewSources.put(component, provider); context.publish(); return; }
                if (verb.equals("remove") && provider == BuiltinProvider.OPTIFINE
                        && component == AppearanceProviders.Component.CAPE) cancelOptiFineAccountLink();
                if (verb.equals("remove") && provider == BuiltinProvider.SKINMC
                        && component == AppearanceProviders.Component.CAPE) cancelSkinMcAccountLink();
                if (verb.equals("remove") && provider == BuiltinProvider.SNEAKY
                        && component == AppearanceProviders.Component.CAPE) cancelSneakyEditorLink();
                int previousIndex = (component == AppearanceProviders.Component.SKIN ? context.providers().skin().order() : context.providers().cape().order()).indexOf(provider);
                UUID accountId = context.account().accountId();
                submitProviderConfiguration(() -> switch (verb) {
                    case "row" -> context.providerOperations().enableProvider(accountId, component, provider);
                    case "remove" -> context.providerOperations().disableProvider(accountId, component, provider);
                    case "up" -> context.providerOperations().moveProvider(accountId, component, provider, -1);
                    case "down" -> context.providerOperations().moveProvider(accountId, component, provider, 1);
                    default -> throw new IllegalArgumentException("Unknown provider action");
                }, appearance -> {
                    CompletableFuture<AppearanceRefreshCoordinator.Result> providerRebind =
                            acceptProviderChange(appearance);
                    context.capeObservations().optiFineConfigurationChanged();
                    state.providerAdding = false;
                    if (origin == InteractionOrigin.KEYBOARD) {
                        var remaining = component == AppearanceProviders.Component.SKIN ? context.providers().skin().order() : context.providers().cape().order();
                        String target = "providers.row." + provider.name();
                        if (verb.equals("remove")) target = remaining.isEmpty() ? "providers.add"
                                : "providers.row." + remaining.get(Math.max(0, Math.min(previousIndex, remaining.size() - 1))).name();
                        context.requestRuntimeFocus("providers", target);
                    }
                    if (verb.equals("remove") && state.providerPreviewSources.get(component) == provider) state.providerPreviewSources.remove(component);
                    if (verb.equals("row")) context.reconcileAfterLocalRebind(
                            providerRebind, ClientOperations.ReconciliationTrigger.LOCAL_INTENT);
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
            OptiFineAccountLink.Outcome outcome = failure == null ? result.outcome() : OptiFineAccountLink.Outcome.FAILED;
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

    CompletableFuture<AppearanceRefreshCoordinator.Result> acceptProviderChange(
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

    RefreshComparison captureRefreshComparison(AppearanceProviders.Component component) {
        if (context.account() == null) return null;
        try {
            GameSessionTokenSource.SessionIdentity identity = context.clientSessionView().sessionIdentity();
            if (!identity.profileId().equals(context.account().accountId())) return null;
            return new RefreshComparison(identity.profileId(), identity.profileName(),
                    component, context.providers());
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    void finishRefreshComparison(RefreshComparison comparison,
            AppearanceProviders confirmedState, ProviderObservation<?> confirmedMinecraft,
            ProviderObservation<ProviderCape> optifine,
            ProviderObservation<ProviderCape> skinmc) {
        if (comparison == null || context.disposed() || context.account() == null
                || !comparison.accountId().equals(context.account().accountId())) return;
        GameSessionTokenSource.SessionIdentity identity;
        try {
            identity = context.clientSessionView().sessionIdentity();
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
                    && context.providers().cape().configurationRevision() != after.configurationRevision())) return;
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
            context.serverAppearanceReadiness().ifPresent(ServerAppearanceReadinessCoordinator::start);
        }
    }

    boolean sameCapeContent(ProviderCape left, ProviderCape right) {
        if (left == null || right == null) return left == right;
        if (left.textureCacheKey() != null && right.textureCacheKey() != null) {
            return left.textureCacheKey().equals(right.textureCacheKey())
                    && Objects.equals(left.hasElytra(), right.hasElytra());
        }
        return left.equals(right);
    }

    boolean dispatchProviderAction(String widgetId, boolean reverse, InteractionOrigin origin) {
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
                    context.providers(), state.providerComponent, context.busy(), width, height, state.providerChooserOffset));
            ViewSpec providerView = new ProvidersPresenter().present(context.providers(), state.providerComponent,
                    state.providerAdding, context.busy(), state.providerPreview.withOuterLayerVisibility(
                    context.outerLayerVisibilityController().map(OuterLayerVisibilityController::current).orElse(OuterLayerVisibility.allVisible())),
                    context.currentPlayerVariant(), width, height, state.providerPreviewSources.get(AppearanceProviders.Component.SKIN),
                    state.providerPreviewSources.get(AppearanceProviders.Component.CAPE), context.snapshot().rateLimitProgress(),
                    optiFineAccountLink != null && optiFineAccountLink.preparing(), optiFineLinkFeedback,
                    state.providerRowsOffset, context.textResolver(), context.snapshot().capeProviderCooldowns());
            SkinFeatureEvidence evidence = Optional.of(providerView.previews().get(0).imageRevision()).filter(revision -> revision.startsWith("provider:skin:")).flatMap(revision -> context.snapshot().account().flatMap(account ->
                    account.skinAssets().stream().filter(asset -> asset.sha256().equals(revision.substring(14))).findFirst()))
                    .map(asset -> context.snapshot().assetEvidence().getOrDefault(asset.id(), SkinFeatureEvidence.ORDINARY)).orElse(SkinFeatureEvidence.ORDINARY);
            SkinCompatibility compatibility = new SkinCompatibilityEvaluator().evaluate(evidence, context.snapshot().skinExtensionEnvironment());
            if (compatibility.status() != SkinCompatibilityStatus.ORDINARY) {
                providerView = providerView.withCompatibilityIndicator("providers.compatibility", new Bounds(2, Math.max(35, height - 55), 20, 20),
                        CompatibilityMessages.accessibleLabel(compatibility), CompatibilityMessages.icon(compatibility), Optional.empty());
            }
            return context.withRuntimeFocus(providerView);
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

    void releaseScrollbar() { draggingProviderRowsScrollbar = false; }

    void grabScrollbar(ViewSpec.Scrollbar scrollbar, double mouseX, double mouseY) {
        draggingProviderRowsScrollbar = true;
        providerRowsScrollbarGrabOffset = scrollbar.thumb().contains(mouseX, mouseY)
                ? mouseY - scrollbar.thumb().y() : scrollbar.thumb().height() / 2.0;
    }

    void useOptiFineAccountLink(OptiFineAccountLink link) {
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

    interface Context {
        AccountState account();
        ClientSnapshot.Lifecycle lifecycle();
        boolean providersOpen();
        AppearanceProviders providers();
        PresetEditorModel editor();
        AccountUiPreferences uiPreferences();
        UiPreferencesPort uiPreferencesPort();
        ProviderOperations providerOperations();
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
        Optional<ServerAppearanceReadinessCoordinator> serverAppearanceReadiness();
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
            CompletableFuture<AppearanceRefreshCoordinator.Result> localRebind,
            ClientOperations.ReconciliationTrigger trigger);
        boolean currentSessionOwns(ClientOperations.DurableAppearance appearance);
        void reconcileAfterLocalRebind(
            CompletableFuture<AppearanceRefreshCoordinator.Result> localRebind,
            ClientOperations.ReconciliationKey key,
            ClientOperations.ReconciliationTrigger trigger);
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
        CompletableFuture<AppearanceRefreshCoordinator.Result> acceptProviderChange(ClientOperations.DurableAppearance appearance);
    }

    private static final class State {
        final java.util.EnumMap<AppearanceProviders.Component, BuiltinProvider> providerPreviewSources = new java.util.EnumMap<>(AppearanceProviders.Component.class);
        boolean providerAdding;
        double providerChooserOffset;
        double providerRowsOffset;
        AppearanceProviders.Component providerComponent = AppearanceProviders.Component.SKIN;
        PreviewInteractionModel providerPreview = PreviewInteractionModel.editor(240, PreviewRenderer.CapeMode.CAPE);
    }
}

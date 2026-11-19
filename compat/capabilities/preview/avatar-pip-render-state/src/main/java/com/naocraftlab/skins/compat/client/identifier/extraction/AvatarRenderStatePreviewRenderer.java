package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.naocraftlab.skins.runtime.CapeProjection;
import com.naocraftlab.skins.client.PreviewAccessories;

import com.mojang.authlib.GameProfile;
import com.naocraftlab.skins.client.EditorPreviewSession;
import com.naocraftlab.skins.client.EditorPreviewClock;
import com.naocraftlab.skins.client.EditorPreviewTickGate;
import com.naocraftlab.skins.client.CenteredPlayerPreviewGeometry;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.PreviewDepthEnvelope;
import com.naocraftlab.skins.client.PreviewStageGeometry;
import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import com.naocraftlab.skins.compat.client.identifier.CapeMaterialSnapshot;
import com.naocraftlab.skins.diagnostics.DiagnosticDetails;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;


public final class AvatarRenderStatePreviewRenderer
        implements PreviewRenderer<GuiGraphicsExtractor>, AutoCloseable {
    private static final AtomicInteger NEXT_PREVIEW_ENTITY_ID = new AtomicInteger(-1);
    private static final float MODEL_HEIGHT = 2.125F;
    private static final float FIT_PADDING = 0.97F;
    private static final Holder<Item> PREVIEW_ELYTRA_HOLDER = Holder.direct(
            Items.ELYTRA,
            DataComponentMap.builder()
                    .set(
                            DataComponents.EQUIPPABLE,
                            Equippable.builder(EquipmentSlot.CHEST)
                                    .setAsset(EquipmentAssets.ELYTRA)
                                    .build())
                    .build());
    private NativeFit nativeFit;

    @Override public void suppressModelFit(String source) {
        session.suppressFit(source);
    }

    @Override public boolean modelFitApplicable() {
        var client = Minecraft.getInstance();
        return com.naocraftlab.skins.compat.client.identifier.measurement.AvatarFitMode.supported()
                && !closed && client.level != null && client.player != null;
    }

    @Override public java.util.Optional<ModelFitPreparation> prepareModelFit(String source, PreviewAppearance appearance) {
        var client = Minecraft.getInstance();
        if (!modelFitApplicable()) return java.util.Optional.empty();
        if (nativeFit != null && nativeFit.source.equals(source) && nativeFit.appearance.skin().equals(appearance.skin())
                && nativeFit.appearance.model() == appearance.model() && nativeFit.ownerCurrent())
            return java.util.Optional.of(nativeFit);
        var owner = session;
        var previous = nativeFit;
        nativeFit = null;
        if (previous != null) previous.close();
        if (closed || session != owner || nativeFit != null) return java.util.Optional.empty();
        var admission = owner.prepareFit();
        if (closed || session != owner || nativeFit != null) return java.util.Optional.empty();
        nativeFit = new NativeFit(source, appearance, owner, admission);
        return java.util.Optional.of(nativeFit);
    }

    private final class NativeFit implements ModelFitPreparation {
        private final String source;
        private final PreviewAppearance appearance;
        private final EditorPreviewSession owner;
        private final EditorPreviewSession.FitPreparation admission;
        private final Minecraft client = Minecraft.getInstance();
        private final Object world = client.level;
        private final Object local = client.player;
        private final java.util.Optional<NativePlayerSkinLifecycle.Registration> texture;
        private final java.util.concurrent.CompletableFuture<ModelFitMeasurement> result = new java.util.concurrent.CompletableFuture<>();
        private final com.naocraftlab.skins.client.ScreenOwnedRenderTarget target = new com.naocraftlab.skins.client.ScreenOwnedRenderTarget();
        private volatile boolean retired, requested;
        private boolean queued, eligible, custom, cpm, earsRequired;
        private volatile com.naocraftlab.skins.compat.client.identifier.measurement.AvatarEarsFitMode.EarsBody ears;
        private volatile com.naocraftlab.skins.client.PreviewModelFrames.Body cpmBody;
        private com.naocraftlab.skins.client.PreviewModelFrames.NeutralBody neutral;
        private ModelFitMeasurement reference;
        private volatile com.naocraftlab.skins.runtime.EffectiveSkinSource selectedSource;
        private com.naocraftlab.skins.client.PreviewModelNormalization.Fit fit;
        private com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture capture;

        private NativeFit(String source, PreviewAppearance appearance, EditorPreviewSession owner, EditorPreviewSession.FitPreparation admission) {
            this.source = source; this.appearance = appearance; this.owner = owner;
            this.admission = admission;
            texture = NativePlayerSkinLifecycle.registration(appearance.skin().location());
        }

        @Override public boolean ownerCurrent() {
            return !retired && !closed && owner == session && admission.ownerCurrent()
                    && client.level == world && client.player == local
                    && NativePlayerSkinLifecycle.registration(appearance.skin().location()).equals(texture)
                    && (selectedSource == null || selectedSource.active());
        }

        @Override public boolean current() {
            return !retired && !closed && admission.current() && client.level == world && client.player == local
                    && NativePlayerSkinLifecycle.registration(appearance.skin().location()).equals(texture)
                    && texture.map(value -> value.state() != NativePlayerSkinLifecycle.State.FAILED
                            && value.state() != NativePlayerSkinLifecycle.State.RETIRED).orElse(true)
                    && (selectedSource == null || selectedSource.active())
                    && (cpmBody == null || cpmBody.current().getAsBoolean())
                    && (ears == null || ears.current());
        }

        @Override public java.util.concurrent.CompletableFuture<ModelFitMeasurement> measure() {
            requested = true;
            return result;
        }

        @Override public void complete(ModelFitMeasurement value) {
            if (!current()) return;
            reference = Objects.requireNonNull(value);
            fit = value.fit();
            if (eligible && (!custom || reference != null)) {
                admission.complete();
                target.close();
                if (neutral != null) { neutral.close(); neutral = null; }
            }
        }

        @Override public void close() {
            if (!closed && fit != null && gallery != null && gallery.displays(owner)) return;
            if (retired) return;
            retired = true;
            admission.close();
            target.close();
            if (neutral != null) neutral.close();
            result.complete(null);
        }

        private boolean matches(PreviewRequest request) {
            return owner == session && appearance.skin().equals(request.appearance().skin())
                    && appearance.model() == request.appearance().model();
        }
    }

    private EditorPreviewSession session = new EditorPreviewSession();
    private EditorPreviewSession.Gallery gallery;
    private final java.util.Map<EditorPreviewSession, NativeSlot> gallerySlots = new java.util.LinkedHashMap<>();
    private EditorPreviewSession activeGallerySource;
    private java.util.function.Consumer<BodyStatus> bodyObserver = value -> {};

    private record NativeSlot(EditorPreviewSession session, GameProfile profile, PreviewPlayer player,
            ClientLevel level, EditorPreviewClock clock, EditorPreviewTickGate tickGate, AvatarPipPreviewRenderer baked,
            com.naocraftlab.skins.client.ScreenOwnedRenderTarget target, NativeFit fit) {}

    private void saveGallerySlot() {
        if (activeGallerySource != null && gallerySlots.containsKey(activeGallerySource)) gallerySlots.put(activeGallerySource,
                new NativeSlot(session, previewProfile, previewPlayer, previewLevel, previewClock, previewTickGate, bakedRenderer,
                        gallerySlots.get(activeGallerySource).target(), nativeFit));
    }

    private void selectGallerySession(EditorPreviewSession candidate) {
        gallerySlots.computeIfAbsent(candidate, ignored -> new NativeSlot(candidate,
                new GameProfile(UUID.randomUUID(), "NCLSkinPreview"), null, null,
                new EditorPreviewClock(), new EditorPreviewTickGate(), new AvatarPipPreviewRenderer(),
                new com.naocraftlab.skins.client.ScreenOwnedRenderTarget(), null));
        selectGallerySlot(candidate);
    }

    private void selectGallerySlot(EditorPreviewSession source) {
        saveGallerySlot();
        var slot = gallerySlots.get(source);
        if (slot == null) throw new IllegalStateException("Retired Gallery source");
        activeGallerySource = source;
        session = slot.session(); previewProfile = slot.profile(); previewPlayer = slot.player();
        previewLevel = slot.level(); previewClock = slot.clock(); previewTickGate = slot.tickGate(); bakedRenderer = slot.baked(); nativeFit = slot.fit();
    }

    private void retireGallerySlots() {
        saveGallerySlot();
        gallerySlots.entrySet().removeIf(entry -> {
            var slot = entry.getValue();
            if (gallery.retains(slot.session())) return false;
            if (slot.fit() != null) slot.fit().close();
            CapeProjection.retireSkin(slot.profile().id());
            try { slot.baked().close(); } finally { slot.target().close(); }
            return true;
        });
    }

    @Override public boolean prepareGalleryBody(String source, boolean cpm, java.util.function.BooleanSupplier candidateCurrent) {
        return prepareGalleryBody(source, source, cpm, candidateCurrent);
    }

    @Override public boolean prepareGalleryBody(String source, String demand, boolean cpm, java.util.function.BooleanSupplier candidateCurrent) {
        if (closed || !candidateCurrent.getAsBoolean() || closed) return false;
        if (gallery == null) {
            session.invalidateDisplayedBody();
            if (closed) return false;
            CapeProjection.retireSkin(previewProfile.id());
            bakedRenderer.close();
            gallery = new EditorPreviewSession.Gallery();
            gallery.observeBody(status -> { retireGallerySlots(); bodyObserver.accept(status); });
        }
        var client = Minecraft.getInstance();
        var candidate = gallery.prepare(source, demand, cpm, client.level != null && client.player != null, candidateCurrent);
        if (candidate == null || closed || gallery == null || !gallery.retains(candidate)) return false;
        saveGallerySlot();
        retireGallerySlots();
        selectGallerySession(candidate);
        retireGallerySlots();
        accessoryPreparation = candidate;
        return true;
    }

    @Override public boolean prepareGalleryImage(String source, boolean cpm, java.util.function.BooleanSupplier candidateCurrent) {
        if (!prepareGalleryBody(source, cpm, candidateCurrent)) return false;
        if (!session.imageTracked()) session.beginImage(source);
        return true;
    }

    @Override public boolean prepareGalleryImage(String source, boolean cpm, java.util.function.BooleanSupplier candidateCurrent,
            java.util.function.BooleanSupplier lifetime) {
        if (!prepareGalleryImage(source, cpm, candidateCurrent)) return false;
        gallery.lifetime(source, lifetime);
        return true;
    }

    @Override public boolean prepareGalleryImage(String source, String demand, boolean cpm,
            java.util.function.BooleanSupplier candidateCurrent, java.util.function.BooleanSupplier lifetime) {
        if (!prepareGalleryBody(source, demand, cpm, candidateCurrent)) return false;
        if (!session.imageTracked()) session.beginImage(source);
        gallery.lifetime(source, lifetime);
        return true;
    }

    @Override public void confirmGalleryBody(String source) {
        if (closed || gallery == null) return;
        gallery.confirm(source);
        if (!closed && gallery != null) retireGallerySlots();
    }

    @Override public java.util.Set<com.naocraftlab.skins.client.TextureRegistry.TextureHandle> retainedTextures() {
        return closed || gallery == null ? java.util.Set.of() : gallery.retainedTextures();
    }

    @Override public void transformRetained(String source, float yawDegrees, float pitchDegrees, float scale) {
        if (!closed && gallery != null) gallery.transformRetained(source, yawDegrees, pitchDegrees, scale);
    }

    @Override public void renderRetained(GuiGraphicsExtractor graphics, String source) {
        if (closed || gallery == null) return;
        var previous = gallery.retainedSource();
        if (previous == null) return;
        var request = gallery.retainedImage(previous);
        if (request == null) return;
        selectGallerySlot(gallery.retainedSession());
        try { render(graphics, request); }
        finally { saveGallerySlot(); if (!closed && gallerySlots.containsKey(gallery.session(source))) selectGallerySlot(gallery.session(source)); }
    }

    @Override public void renderGallery(GuiGraphicsExtractor graphics, String source, PreviewRequest request) {
        if (closed || gallery == null || !gallerySlots.containsKey(gallery.session(source))) return;
        renderRetained(graphics, source);
        if (closed || gallery == null || !gallerySlots.containsKey(gallery.session(source))) return;
        var client = Minecraft.getInstance();
        var world = client.level;
        var local = client.player;
        var registration = NativePlayerSkinLifecycle.registration(request.appearance().skin().location());
        if (gallery != null) gallery.image(source, request, () -> !closed && client.level == world && client.player == local
                && NativePlayerSkinLifecycle.registration(request.appearance().skin().location()).equals(registration));
        selectGallerySlot(gallery.session(source));
        render(graphics, request);
        saveGallerySlot();
    }

    @Override public PreviewAccessories galleryAccessories(String source, PreviewRequest request, java.util.function.BooleanSupplier inputCurrent) {
        if (closed || !inputCurrent.getAsBoolean() || closed)
            return PreviewAccessories.unavailable(LiveReason.LIVE_UNAVAILABLE);
        saveGallerySlot();
        var slot = gallerySlots.get(gallery.session(source));
        if (closed || slot == null || gallery == null)
            return PreviewAccessories.unavailable(LiveReason.LIVE_UNAVAILABLE);
        var owner = gallery;
        Minecraft client = Minecraft.getInstance();
        LiveReason reason = slot.session().liveReason(request.intent(), client.level != null && client.player != null,
                NativePlayerSkinLifecycle.isReady(request.appearance().skin().location()),
                slot.player() != null && slot.level() == client.level);
        var value = CapeProjection.previewAccessories(slot.profile().id(), request.appearance().skin().location(),
                request.appearance().model(), reason);
        return inputCurrent.getAsBoolean() && !closed && gallery == owner && gallerySlots.get(gallery.session(source)) == slot
                ? value : PreviewAccessories.unavailable(LiveReason.LIVE_UNAVAILABLE);
    }
    private java.util.function.Consumer<PreviewAccessories> accessoryObserver = value -> {};
    private EditorPreviewSession accessoryObserverSession;
    private EditorPreviewSession accessoryPreparation;
    private volatile boolean closed;

    @Override
    public void observeAccessories(java.util.function.Consumer<PreviewAccessories> observer) {
        accessoryObserver = Objects.requireNonNull(observer);
        accessoryObserverSession = accessoryPreparation == null ? session : accessoryPreparation;
    }

    @Override
    public PreviewAccessories accessories(PreviewRequest request) {
        Minecraft client = Minecraft.getInstance();
        LiveReason reason = closed ? LiveReason.LIVE_UNAVAILABLE : session.liveReason(request.intent(),
                client.level != null && client.player != null,
                NativePlayerSkinLifecycle.isReady(request.appearance().skin().location()),
                previewPlayer != null && previewLevel == client.level);
        return CapeProjection.previewAccessories(previewProfile.id(), request.appearance().skin().location(),
                request.appearance().model(), reason);
    }

    private java.util.function.Consumer<PreviewAccessories> accessoryObserverFor(EditorPreviewSession source) {
        var observer = accessoryObserver;
        var owner = accessoryObserverSession;
        return value -> {
            if (!closed && source == owner && accessoryObserverSession == owner && accessoryObserver == observer)
                observer.accept(value);
        };
    }

    private void clearAccessoryInspection() {
        accessoryObserverFor(session).accept(PreviewAccessories.unavailable(LiveReason.LIVE_UNAVAILABLE));
    }

    private final DiagnosticSink diagnostics;
    private EditorPreviewClock previewClock = new EditorPreviewClock();
    private EditorPreviewTickGate previewTickGate = new EditorPreviewTickGate();
    private boolean layerFailureLogged;
    private GameProfile previewProfile =
            new GameProfile(UUID.randomUUID(), "NCLSkinPreview");
    private AvatarPipPreviewRenderer bakedRenderer =
            new AvatarPipPreviewRenderer();
    private PreviewPlayer previewPlayer;
    private ClientLevel previewLevel;

    public AvatarRenderStatePreviewRenderer(DiagnosticSink diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Override public void observeBody(java.util.function.Consumer<BodyStatus> observer) {
        bodyObserver = Objects.requireNonNull(observer);
        if (gallery == null) session.observeBody(observer);
    }

    @Override public com.naocraftlab.skins.client.PreviewModelFrames.Body body() { return gallery == null ? session.body() : gallery.body(); }

    @Override public void retireBody() {
        if (gallery != null) {
            gallery.close();
            retireGallerySlots();
            gallery = null;
            activeGallerySource = null;
        }
        session.retireBody();
        if (nativeFit != null) nativeFit.close();
        nativeFit = null;
        CapeProjection.retireSkin(previewProfile.id());
        bakedRenderer.close();
        session = new EditorPreviewSession();
        session.observeBody(bodyObserver);
        previewProfile = new GameProfile(UUID.randomUUID(), "NCLSkinPreview");
        previewPlayer = null;
        previewLevel = null;
        previewClock = new EditorPreviewClock();
        previewTickGate = new EditorPreviewTickGate();
        bakedRenderer = new AvatarPipPreviewRenderer();
        accessoryObserverSession = null;
        accessoryPreparation = null;
    }

    @Override public void prepareBody(String source, boolean cpm) {
        prepareBody(source, source, cpm);
    }

    @Override public void prepareBody(String source, String demand, boolean cpm) {
        if (closed) return;
        if (gallery != null) {
            gallery.close(); retireGallerySlots(); gallery = null; activeGallerySource = null;
            if (closed) return;
            nativeFit = null;
            session = new EditorPreviewSession(); session.observeBody(bodyObserver);
            previewProfile = new GameProfile(UUID.randomUUID(), "NCLSkinPreview");
            previewPlayer = null; previewLevel = null;
            previewClock = new EditorPreviewClock(); previewTickGate = new EditorPreviewTickGate();
            bakedRenderer = new AvatarPipPreviewRenderer();
        }
        var client = Minecraft.getInstance();
        accessoryObserverSession = null;
        accessoryPreparation = session;
        session.beginBody(source, demand, cpm, !closed && client.level != null && client.player != null);
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, PreviewRequest request) {
        var image = session;
        if (closed) { image.invalidateDisplayedBody(); return; }
        var authorityClient = Minecraft.getInstance();
        image.displayAuthority(java.util.Arrays.asList(request.appearance().skin(), request.appearance().model(),
                request.intent(), NativePlayerSkinLifecycle.registration(request.appearance().skin().location())),
                authorityClient.level, authorityClient.player);
        if (!image.imageTracked()) { renderPrepared(graphics, request); return; }
        if (image.imageSpinner()) {
            var frame = com.naocraftlab.skins.client.PreviewLoading.frame((long) image.imageFrame() * 100_000_000L,
                    request.left(), request.top(), request.width(), request.height(), 32);
            GuiExtraction.blit(graphics, Identifier.parse(frame.resource()),
                    frame.left(), frame.top(), (float) frame.u(), (float) frame.v(), frame.size(), frame.size(),
                    frame.width(), frame.height(), frame.textureWidth(), frame.textureHeight(), -1);
        }
        if (!image.imageVisible() && !image.imagePreparing()) return;
        renderPrepared(graphics, image.imageVisible() ? request : request.hidden());
    }

    @Override public void prepareImage(Object source, long startedNanos, boolean inputsReady) {
        if (closed) return;
        prepareImage(source, startedNanos);
        session.imagePrerequisite(inputsReady, startedNanos);
    }

    @Override public void prepareImage(Object source, long startedNanos, boolean inputsReady, boolean indicator) {
        if (closed) return;
        if (gallery != null) {
            saveGallerySlot();
            var candidate = gallery.prepareImage(source, startedNanos, inputsReady, indicator);
            if (candidate == null) return;
            selectGallerySession(candidate);
            accessoryPreparation = candidate;
            retireGallerySlots();
        } else {
            session.imageIndicator(indicator);
            prepareImage(source, startedNanos, inputsReady);
        }
    }

    @Override public void prepareImage(Object source, long startedNanos) {
        if (!closed) session.beginImage(source, startedNanos);
    }

    private void renderPrepared(GuiGraphicsExtractor graphics, PreviewRequest request) {
        var session = this.session;
        var frameGallery = gallery;
        var frameTarget = closed || frameGallery == null ? null : gallerySlots.get(activeGallerySource).target();
        var frameAccessoryObserver = accessoryObserverFor(session);
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(request, "request");
        if (session.waitsForBody() && session.bodyStatus() != BodyStatus.READY) {
            if (session.bodyStatus() != BodyStatus.PENDING) { renderFallback(graphics, request); return; }
            if (!NativePlayerSkinLifecycle.isReady(request.appearance().skin().location())) return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (session.path(request.intent(), minecraft.level != null && player != null)
                        == EditorPreviewSession.Path.BAKED
                || !NativePlayerSkinLifecycle.isReady(request.appearance().skin().location())) {
            accessoryObserverFor(session).accept(accessories(request));
            renderFallback(graphics, request);
            return;
        }

        try {
            PreviewAppearance appearance = request.appearance();
            PlayerSkin skin = playerSkin(appearance);
            ItemStack chestEquipment = appearance.capeMode() == CapeMode.ELYTRA
                    ? previewElytraStack()
                    : ItemStack.EMPTY;
            PreviewPlayer renderPlayer = previewPlayer(minecraft, appearance, skin, chestEquipment);
            if (renderPlayer == null) {
                accessoryObserverFor(session).accept(accessories(request));
                renderFallback(graphics, request);
                return;
            }
            var fitting = nativeFit;
            if (fitting != null && fitting.matches(request) && fitting.cpmBody != null
                    && !fitting.cpmBody.current().getAsBoolean()) {
                fitting.eligible = false;
                fitting.result.complete(null);
                return;
            }
            normalization: if (fitting != null && fitting.matches(request) && fitting.current()
                    && (fitting.requested || fitting.fit != null)) {
                var source = CapeProjection.skinSource(previewProfile.id());
                if (source != fitting.selectedSource) {
                    fitting.selectedSource = source;
                    fitting.ears = null;
                }
                var requirements = CapeProjection.previewBodyRequirements(previewProfile.id());
                fitting.eligible = false;
                if (source != CapeProjection.skinSource(previewProfile.id()) || (source != null && !source.active())) return;
                fitting.cpm = requirements.cpm() == com.naocraftlab.skins.client.PreviewModelFrames.Requirement.REQUIRED;
                if (fitting.ears == null)
                    fitting.ears = com.naocraftlab.skins.compat.client.identifier.measurement.AvatarEarsFitMode.ears(fitting.selectedSource);
                fitting.earsRequired = fitting.ears != null || requirements.ears() == com.naocraftlab.skins.client.PreviewModelFrames.Requirement.REQUIRED;
                fitting.custom = fitting.cpm || fitting.earsRequired;
                if (!requirements.admitted()) {
                    fitting.result.complete(null);
                    session.declineFit();
                    break normalization;
                }
                if (fitting.earsRequired) {
                    if (!com.naocraftlab.skins.compat.client.identifier.measurement.AvatarEarsFitMode.earsAvailable()) {
                        fitting.result.complete(null);
                        session.declineFit();
                        break normalization;
                    }
                    if (fitting.ears == null) {
                        if (session.waitsForBody()) return;
                        break normalization;
                    }
                }
                if (fitting.cpm) {
                    var body = com.naocraftlab.skins.client.PreviewModelFrames.prepare(previewProfile, appearance);
                    if (body.status() == BodyStatus.PENDING) {
                        if (session.waitsForBody()) return;
                        break normalization;
                    }
                    if (body.status() != BodyStatus.READY || !body.current().getAsBoolean()) {
                        fitting.result.complete(null);
                        session.declineFit();
                        break normalization;
                    }
                    fitting.cpmBody = body;
                    if (fitting.fit == null || fitting.reference == null) {
                        if (fitting.neutral == null)
                            fitting.neutral = com.naocraftlab.skins.client.PreviewModelFrames.neutral(previewProfile, appearance);
                        if (fitting.neutral == null) {
                            if (session.waitsForBody()) return;
                            break normalization;
                        }
                        var neutralBody = fitting.neutral.body();
                        if (neutralBody.status() == BodyStatus.PENDING) {
                            if (session.waitsForBody()) return;
                            break normalization;
                        }
                        if (neutralBody.status() != BodyStatus.READY || !neutralBody.current().getAsBoolean()) {
                            fitting.result.complete(null);
                            session.declineFit();
                            break normalization;
                        }
                    }
                }
                fitting.eligible = true;
                if (fitting.eligible && fitting.fit != null && (!fitting.custom || fitting.reference != null)) {
                    fitting.admission.complete();
                    fitting.target.close();
                    if (fitting.neutral != null) { fitting.neutral.close(); fitting.neutral = null; }
                }
            }
            if (fitting != null && fitting.matches(request) && fitting.current()
                    && fitting.eligible
                    && ((fitting.requested && !fitting.result.isDone()) || (fitting.custom && fitting.fit != null && fitting.reference == null))
                    && !fitting.queued) {
                fitting.queued = true;
                if (!fitting.eligible) fitting.result.complete(null);
                else {
                    var neutralAppearance = new PreviewAppearance(appearance.skin(), appearance.model(), java.util.Optional.empty(),
                            CapeMode.OFF, com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(), false, false);
                    var neutralRequest = new PreviewRequest(neutralAppearance, 0, 0, 64, 128, 0, 0, 1, request.intent());
                    var captureContext = new AvatarPreviewContext(minecraft.player, neutralAppearance, playerSkin(neutralAppearance),
                            ItemStack.EMPTY, fitting::current, ignored -> {});
                    captureContext.target(fitting.target);
                    var neutralState = createRenderState(minecraft, renderPlayer, captureContext);
                    configurePreviewState(neutralState, neutralAppearance, playerSkin(neutralAppearance), neutralRequest);
                    boolean referencePass = fitting.custom && fitting.reference == null;
                    captureContext.measurement(() -> {
                        var neutralScope = fitting.neutral == null ? null : fitting.neutral.open(referencePass);
                        com.naocraftlab.skins.client.PreviewModelFrames.Lease earsScope = null;
                        try {
                            earsScope = fitting.ears == null ? null : fitting.ears.open(neutralState, referencePass);
                            var ownedEarsScope = earsScope;
                            fitting.capture = new com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture(neutralState, fitting::current);
                            return () -> {
                                try (neutralScope; ownedEarsScope) { fitting.capture.close(); }
                            };
                        } catch (RuntimeException | Error failure) {
                            var failedEarsScope = earsScope;
                            try (neutralScope; failedEarsScope) { throw failure; }
                        }
                    }, success -> {
                        var measured = success && fitting.capture != null ? fitting.capture.ordinary() : null;
                        if (measured == null) fitting.result.complete(null);
                        else if (referencePass) { fitting.reference = measured; fitting.queued = false; }
                        else if (fitting.custom) {
                            var value = measured.fit();
                            fitting.result.complete(new ModelFitMeasurement(fitting.reference.referenceHeight(),
                                    fitting.reference.referenceCenterX(), fitting.reference.referenceCenterY(), fitting.reference.referenceCenterZ(),
                                    com.naocraftlab.skins.client.PreviewModelNormalization.full(fitting.reference.referenceHeight(),
                                            value.bodyHeight(), value.centerX(), value.centerY(), value.centerZ(), value.halfWidth(), value.halfDepth())));
                        } else fitting.result.complete(measured);
                    });
                    var owned = (NclSkinsWideDepthState) neutralState;
                    owned.nclskins$setPreviewContext(captureContext);
                    owned.nclskins$setFailureSink(failure -> fitting.result.complete(null));
                    owned.nclskins$setLayerFailureSink(failure -> fitting.result.complete(null));
                    graphics.entity(neutralState, 64, new Vector3f(), new Quaternionf().rotateZ((float) Math.PI),
                            new Quaternionf(), -128, -256, -64, -128);
                }
                return;
            }
            if (session.waitsForBody()) {
                session.body(com.naocraftlab.skins.client.PreviewModelFrames.prepare(previewProfile, request.appearance()));
                if (session.bodyStatus() == BodyStatus.PENDING) return;
                if (session.bodyStatus() != BodyStatus.READY) { renderFallback(graphics, request); return; }
            }
            var frameCurrent = session.frame();
            var frameWorld = minecraft.level;
            var frameTexture = NativePlayerSkinLifecycle.registration(appearance.skin().location());
            var framePlayer = minecraft.player;
            boolean ordinaryFit = session.fitSuppressed() || fitting != null && fitting.matches(request)
                    && (!fitting.current() || !fitting.eligible || fitting.fit == null);
            var displayed = ordinaryFit ? session.ordinaryDisplay(() -> true) : session.displayBody(() -> true);
            AvatarPreviewContext context = new AvatarPreviewContext(minecraft.player, appearance, skin, chestEquipment,
                    () -> !closed && minecraft.level == frameWorld && minecraft.player == framePlayer
                            && gallery == frameGallery
                            && (frameGallery == null ? previewPlayer == renderPlayer && previewLevel == frameWorld : frameGallery.retains(session))
                            && NativePlayerSkinLifecycle.isReady(appearance.skin().location())
                            && NativePlayerSkinLifecycle.registration(appearance.skin().location()).equals(frameTexture)
                            && frameCurrent.getAsBoolean(), com.naocraftlab.skins.client.PreviewModelFrames.mapStatus(displayed,
                                    status -> ordinaryFit && status == BodyStatus.READY ? BodyStatus.ORDINARY : status));
            if (frameTarget != null) context.target(frameTarget);
            float previewAge = previewClock.ageTicks(renderPlayer.tickCount);
            applySettling(renderPlayer, previewAge, session.capeSettling(
                    request.intent(), true, appearance.capeMode(),
                    appearance.cape().isPresent(), previewAge));
            renderPlayer.tickCount = Math.max(0, (int) Math.floor(previewAge));
            AvatarRenderState state = createRenderState(minecraft, renderPlayer, context);
            state.ageInTicks = previewAge;
            configurePreviewState(state, appearance, skin, request);
            ((CapeMaterialSnapshot.Carrier) state).nclskins$setCapeMaterial(
                    CapeMaterialSnapshot.preview(
                            state.skin.cape() == null ? null : state.skin.cape().texturePath().toString(),
                            appearance.cape().map(TextureHandle::location).orElse(null),
                            appearance.capeMode() == CapeMode.CAPE, appearance.capeGlint(), appearance.capeValidity()));
            NclSkinsWideDepthState previewState = (NclSkinsWideDepthState) state;
            var fullFit = nativeFit != null && nativeFit.matches(request) && nativeFit.current() && nativeFit.eligible ? nativeFit.fit : null;
            float requestedScale = FIT_PADDING * request.height() / MODEL_HEIGHT * request.scale()
                    * (float) (fullFit == null ? 1 : fullFit.normalization());
            previewState.nclskins$setDepthExtent(PreviewDepthEnvelope.forRequest(
                    request.intent(), requestedScale, request.pitchDegrees(), appearance.capeMode()));
            previewState.nclskins$setFailureSink(failure -> { if (context.current()) onLiveRenderFailure(session, frameAccessoryObserver, failure); });
            previewState.nclskins$setLayerFailureSink(failure -> { if (context.current()) onLiveLayerFailure(failure); });
            previewState.nclskins$setPreviewContext(context);

            float pitchRadians = LivePreviewPitch.radians(request.pitchDegrees());
            Quaternionf cameraPitch = new Quaternionf().rotateX(pitchRadians);
            Quaternionf modelRotation = new Quaternionf()
                    .rotateZ((float) Math.PI)
                    .mul(cameraPitch);
            CenteredPlayerPreviewGeometry.EntityTranslation centeredTranslation =
                    CenteredPlayerPreviewGeometry.centeredEntityTranslation(
                            CenteredPlayerPreviewGeometry.STANDING_PLAYER_HEIGHT,
                            pitchRadians);
            Vector3f translation = new Vector3f(
                    PreviewStageGeometry.modelOffsetX(request, requestedScale),
                    centeredTranslation.y()
                            + PreviewStageGeometry.modelOffsetY(request, requestedScale),
                    centeredTranslation.z());
            if (fullFit != null) {
                float measuredDepth = (float) (requestedScale * ((Math.abs(fullFit.centerY()) + fullFit.bodyHeight() * 0.5) * Math.abs(Math.sin(pitchRadians))
                        + (Math.abs(-fullFit.centerX() * Math.sin(Math.toRadians(request.yawDegrees()))
                                + fullFit.centerZ() * Math.cos(Math.toRadians(request.yawDegrees())))
                                + Math.hypot(fullFit.halfWidth(), fullFit.halfDepth())) * Math.abs(Math.cos(pitchRadians))));
                previewState.nclskins$setDepthExtent(Math.min(PreviewDepthEnvelope.MAXIMUM,
                        previewState.nclskins$depthExtent() + measuredDepth));
            }

            graphics.entity(
                    state,
                    requestedScale,
                    translation,
                    modelRotation,
                    cameraPitch,
                    request.stageLeft(),
                    request.stageTop(),
                    request.stageLeft() + request.stageWidth(),
                    request.stageTop() + request.stageHeight());
        } catch (RuntimeException failure) {
            onLiveRenderFailure(session, frameAccessoryObserver, failure);
        }
    }

    private void renderFallback(GuiGraphicsExtractor graphics, PreviewRequest request) {
        var session = this.session;
        var frameGallery = gallery;
        var client = Minecraft.getInstance();
        var world = client.level;
        var local = client.player;
        var texture = NativePlayerSkinLifecycle.registration(request.appearance().skin().location());
        bakedRenderer.render(graphics, request, session.ordinaryDisplay(() -> !closed && client.level == world && client.player == local
                && gallery == frameGallery && (frameGallery == null ? this.session == session : frameGallery.retains(session))
                && NativePlayerSkinLifecycle.isReady(request.appearance().skin().location())
                && NativePlayerSkinLifecycle.registration(request.appearance().skin().location()).equals(texture)));
    }

    private AvatarRenderState createRenderState(
            Minecraft minecraft,
            AbstractClientPlayer player,
            AvatarPreviewContext context) {
        AvatarRenderState selector = rendererSelectorState();
        selector.skin = player.getSkin();
        EntityRenderer<?, ? super AvatarRenderState> genericRenderer =
                minecraft.getEntityRenderDispatcher().getRenderer(selector);
        if (!(genericRenderer instanceof AvatarRenderer<?> avatarRenderer)) {
            throw new IllegalStateException("Selected local player renderer is not an avatar renderer");
        }

        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        try (AvatarPreviewScope ignored = context.open(minecraft)) {
            return extractFromSelectedRenderer(avatarRenderer, player, partialTick);
        }
    }

    private PreviewPlayer previewPlayer(
            Minecraft minecraft,
            PreviewAppearance appearance,
            PlayerSkin skin,
            ItemStack chestEquipment) {
        ClientLevel level = minecraft.level;
        LocalPlayer localPlayer = minecraft.player;
        if (level == null || localPlayer == null) {
            previewPlayer = null;
            previewLevel = null;
            return null;
        }
        CapeProjection.effectiveSkin(previewProfile.id(), minecraft.player.getUUID(),
                appearance.skin().location(), appearance.model(), true);
        if (previewPlayer == null || previewLevel != level) {
            previewPlayer = new PreviewPlayer(level, previewProfile);
            previewPlayer.setId(nextPreviewEntityId());
            previewLevel = level;
        }

        previewPlayer.setPreviewSkin(skin);
        previewPlayer.setPreviewModelParts(previewModelParts(appearance));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            previewPlayer.setItemSlot(slot, ItemStack.EMPTY);
        }
        previewPlayer.setItemSlot(EquipmentSlot.CHEST, chestEquipment.copy());
        previewPlayer.setPos(localPlayer.position());
        previewPlayer.setYRot(0.0F);
        previewPlayer.yRotO = 0.0F;
        previewPlayer.setXRot(0.0F);
        previewPlayer.xRotO = 0.0F;
        previewPlayer.yBodyRot = 0.0F;
        previewPlayer.yBodyRotO = 0.0F;
        previewPlayer.yHeadRot = 0.0F;
        previewPlayer.yHeadRotO = 0.0F;
        previewPlayer.setOldPosAndRot();
        previewPlayer.setOnGround(true);
        previewPlayer.setPose(Pose.STANDING);
        previewPlayer.setInvisible(false);
        previewPlayer.avatarState().tick(previewPlayer.position(), Vec3.ZERO);
        return previewPlayer;
    }

    private void applySettling(
            PreviewPlayer player, float previewAge,
            EditorPreviewSession.SettlingMotion motion) {
        double baseX = player.getX();
        double baseY = player.getY();
        double baseZ = player.getZ();
        player.setPos(baseX, baseY + motion.currentYOffset(), baseZ);
        player.xo = baseX;
        player.yo = baseY + motion.previousYOffset();
        player.zo = baseZ;
        player.avatarState().tick(player.position(), Vec3.ZERO);
        player.tickCount = Math.max(0, (int) Math.floor(previewAge));
        if (previewTickGate.shouldTick(previewAge, motion.active())) {
            player.tick();
            player.tickCount = Math.max(0, (int) Math.floor(previewAge));
        }
    }

    private void onLiveRenderFailure(EditorPreviewSession session,
            java.util.function.Consumer<PreviewAccessories> observer, RuntimeException failure) {
        boolean firstFailure = session.disableLive(failure);
        observer.accept(PreviewAccessories.unavailable(LiveReason.LIVE_UNAVAILABLE));
        if (firstFailure) {
            diagnostics.report(
                    DiagnosticEvent.CLIENT_PREVIEW_LIVE_DISABLED,
                    () -> DiagnosticDetails.failure(failure));
        }
    }

    private void onLiveLayerFailure(RuntimeException failure) {
        if (!layerFailureLogged) {
            layerFailureLogged = true;
            diagnostics.report(
                    DiagnosticEvent.CLIENT_PREVIEW_LAYER_SKIPPED,
                    () -> DiagnosticDetails.failure(failure));
        }
    }

    @SuppressWarnings("unchecked")
    private static AvatarRenderState extractFromSelectedRenderer(
            AvatarRenderer<?> renderer,
            AbstractClientPlayer player,
            float partialTick) {
        return ((AvatarRenderer<AbstractClientPlayer>) renderer).createRenderState(player, partialTick);
    }

    private static AvatarRenderState rendererSelectorState() {
        AvatarRenderState state = new AvatarRenderState();
        state.entityType = ExtractionGuiApi.mannequin();
        state.boundingBoxWidth = 0.6F;
        state.boundingBoxHeight = 1.8F;
        state.eyeHeight = 1.62F;
        return state;
    }

    private static byte previewModelParts(PreviewAppearance appearance) {
        int mask = 0;
        for (PlayerModelPart part : PlayerModelPart.values()) {
            boolean visible = part == PlayerModelPart.CAPE
                    ? appearance.capeMode() == CapeMode.CAPE
                    : appearance.outerLayerVisibility().visible(outerLayerPart(part));
            if (visible) {
                mask |= part.getMask();
            }
        }
        return (byte) mask;
    }

    private static int nextPreviewEntityId() {
        int candidate = NEXT_PREVIEW_ENTITY_ID.getAndDecrement();
        return candidate == 0 ? NEXT_PREVIEW_ENTITY_ID.getAndDecrement() : candidate;
    }

    private static OuterLayerPart outerLayerPart(PlayerModelPart part) {
        return switch (part) {
            case HAT -> OuterLayerPart.HEAD;
            case JACKET -> OuterLayerPart.BODY;
            case LEFT_SLEEVE -> OuterLayerPart.LEFT_ARM;
            case RIGHT_SLEEVE -> OuterLayerPart.RIGHT_ARM;
            case LEFT_PANTS_LEG -> OuterLayerPart.LEFT_LEG;
            case RIGHT_PANTS_LEG -> OuterLayerPart.RIGHT_LEG;
            case CAPE -> throw new IllegalArgumentException("cape is not an outer-layer part");
        };
    }

    private static void configurePreviewState(
            AvatarRenderState state,
            PreviewAppearance appearance,
            PlayerSkin skin,
            PreviewRequest request) {
        state.skin = skin;


        state.bodyRot = 180.0F - request.yawDegrees();
        state.yRot = 0.0F;
        state.xRot = 0.0F;

        if (state.scale > 0.0F) {
            state.boundingBoxWidth /= state.scale;
            state.boundingBoxHeight /= state.scale;
        }
        state.scale = 1.0F;
        state.ageScale = 1.0F;
        state.distanceToCameraSq = 0.0;
        state.shadowPieces.clear();
        state.outlineColor = 0;
        state.nameTag = null;
        state.scoreText = null;
        state.displayFireAnimation = false;
        state.isInvisible = false;
        state.isInvisibleToPlayer = false;
        state.isSpectator = false;
        state.isUpsideDown = false;
        state.isFullyFrozen = false;
        state.hasRedOverlay = false;
        state.isBaby = false;
        state.pose = Pose.STANDING;

        state.walkAnimationPos = 0.0F;
        state.walkAnimationSpeed = 0.0F;
        AvatarAttackAnimation.reset(state);
        state.swimAmount = 0.0F;
        state.isCrouching = false;
        state.isFallFlying = false;
        state.isVisuallySwimming = false;
        state.isPassenger = false;
        state.isUsingItem = false;

        state.showHat = appearance.outerLayerVisibility().visible(OuterLayerPart.HEAD);
        state.showJacket = appearance.outerLayerVisibility().visible(OuterLayerPart.BODY);
        state.showLeftPants = appearance.outerLayerVisibility().visible(OuterLayerPart.LEFT_LEG);
        state.showRightPants = appearance.outerLayerVisibility().visible(OuterLayerPart.RIGHT_LEG);
        state.showLeftSleeve = appearance.outerLayerVisibility().visible(OuterLayerPart.LEFT_ARM);
        state.showRightSleeve = appearance.outerLayerVisibility().visible(OuterLayerPart.RIGHT_ARM);
        state.showCape = appearance.capeMode() == CapeMode.CAPE;
        state.showExtraEars = false;

        state.fallFlyingTimeInTicks = 0.0F;
        state.shouldApplyFlyingYRot = false;
        state.flyingYRot = 0.0F;
        state.elytraRotX = 0.2617994F;
        state.elytraRotY = 0.0F;
        state.elytraRotZ = -0.2617994F;
        state.arrowCount = 0;
        state.stingerCount = 0;
        state.parrotOnLeftShoulder = null;
        state.parrotOnRightShoulder = null;

        state.headEquipment = ItemStack.EMPTY;
        state.chestEquipment = appearance.capeMode() == CapeMode.ELYTRA
                ? previewElytraStack()
                : ItemStack.EMPTY;
        state.legsEquipment = ItemStack.EMPTY;
        state.feetEquipment = ItemStack.EMPTY;
        state.rightHandItemStack = ItemStack.EMPTY;
        state.leftHandItemStack = ItemStack.EMPTY;
        state.rightHandItemState.clear();
        state.leftHandItemState.clear();
        state.headItem.clear();
        state.heldOnHead.clear();
    }


    private static ItemStack previewElytraStack() {
        return new ItemStack(PREVIEW_ELYTRA_HOLDER);
    }

    private static PlayerSkin playerSkin(PreviewAppearance appearance) {
        ClientAsset.Texture body = texture(appearance.skin());
        ClientAsset.Texture selectedCape = appearance.cape().map(AvatarRenderStatePreviewRenderer::texture).orElse(null);
        ClientAsset.Texture cape = appearance.capeMode() == CapeMode.CAPE
                ? selectedCape
                : null;
        ClientAsset.Texture elytra = appearance.capeMode() == CapeMode.ELYTRA && appearance.capeHasElytra() ? selectedCape : null;
        PlayerModelType model = appearance.model() == SkinModel.SLIM
                ? PlayerModelType.SLIM
                : PlayerModelType.WIDE;
        return PlayerSkin.insecure(body, cape, elytra, model);
    }

    private static ClientAsset.Texture texture(TextureHandle handle) {
        Identifier location = Identifier.parse(handle.location());
        return new ClientAsset.ResourceTexture(location, location);
    }

    private static final class PreviewPlayer extends RemotePlayer {
        private PlayerSkin previewSkin;

        private PreviewPlayer(ClientLevel level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public PlayerSkin getSkin() {
            return previewSkin;
        }

        private void setPreviewSkin(PlayerSkin previewSkin) {
            this.previewSkin = Objects.requireNonNull(previewSkin, "previewSkin");
        }

        private void setPreviewModelParts(byte modelParts) {
            getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, modelParts);
        }
    }

    @Override
    public void close() {
        closed = true;
        if (nativeFit != null) nativeFit.close();
        if (gallery != null) { gallery.close(); retireGallerySlots(); }
        session.invalidateDisplayedBody();
        clearAccessoryInspection();
        accessoryObserver = value -> {};
        CapeProjection.retireSkin(previewProfile.id());
        bakedRenderer.close();
        previewPlayer = null;
        previewLevel = null;
    }

}

package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup;

import com.naocraftlab.skins.runtime.CapeProjection;
import com.naocraftlab.skins.client.PreviewAccessories;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.naocraftlab.skins.compat.client.cape.CapeTranslucentFoilMaterial;
import com.naocraftlab.skins.client.CenteredPlayerPreviewGeometry;
import com.naocraftlab.skins.client.EditorPreviewLayerGuard;
import com.naocraftlab.skins.client.EditorPreviewClock;
import com.naocraftlab.skins.client.EditorPreviewSession;
import com.naocraftlab.skins.client.EditorPreviewTickGate;
import com.naocraftlab.skins.client.LegacyPreviewDepth;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.VanillaBackEquipmentTransform;
import com.naocraftlab.skins.client.VanillaPlayerModelTransform;
import com.naocraftlab.skins.diagnostics.DiagnosticDetails;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.joml.Quaternionf;
import org.joml.Vector3f;


public final class VanillaAppearancePreviewRenderer implements PreviewRenderer<GuiGraphics>, AutoCloseable {
    private static final AtomicInteger NEXT_PREVIEW_ENTITY_ID = new AtomicInteger(-1);
    private static final PlayerTeam PREVIEW_TEAM = previewTeam();
    private static final float WORLDLESS_CAPE_ATTACHMENT_Z = 0.15625F;
    private static final float MODEL_HEIGHT = 2.125F;
    private static final float FIT_PADDING = 0.97F;
    private static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180.0);
    private static final EquipmentSlot[] PREVIEW_EQUIPMENT = {
        EquipmentSlot.MAINHAND,
        EquipmentSlot.OFFHAND,
        EquipmentSlot.FEET,
        EquipmentSlot.LEGS,
        EquipmentSlot.CHEST,
        EquipmentSlot.HEAD
    };
    private static final VanillaPlayerModelTransform.Operations<PoseStack> POSE_OPERATIONS =
            new VanillaPlayerModelTransform.Operations<>() {
                @Override
                public void scale(PoseStack pose, float x, float y, float z) {
                    pose.scale(x, y, z);
                }

                @Override
                public void rotateZThenX(PoseStack pose, float zRadians, float xRadians) {
                    pose.mulPose(new Quaternionf().rotateZ(zRadians).rotateX(xRadians));
                }

                @Override
                public void rotateY(PoseStack pose, float radians) {
                    pose.mulPose(new Quaternionf().rotateY(radians));
                }

                @Override
                public void translate(PoseStack pose, float x, float y, float z) {
                    pose.translate(x, y, z);
                }
            };
    private static final VanillaBackEquipmentTransform.Operations<PoseStack>
            BACK_EQUIPMENT_OPERATIONS = new VanillaBackEquipmentTransform.Operations<>() {
                @Override
                public void scale(PoseStack pose, float x, float y, float z) {
                    pose.scale(x, y, z);
                }

                @Override
                public void rotateZThenX(
                        PoseStack pose, float zRadians, float xRadians) {
                    pose.mulPose(new Quaternionf().rotateZ(zRadians).rotateX(xRadians));
                }

                @Override
                public void rotateX(PoseStack pose, float radians) {
                    pose.mulPose(new Quaternionf().rotateX(radians));
                }

                @Override
                public void rotateY(PoseStack pose, float radians) {
                    pose.mulPose(new Quaternionf().rotateY(radians));
                }

                @Override
                public void translate(PoseStack pose, float x, float y, float z) {
                    pose.translate(x, y, z);
                }
            };

    private NativeFit nativeFit;
    private long bodyPreparationGeneration;

    @Override public boolean modelFitApplicable() {
        return !closed && minecraft.level != null && minecraft.player != null;
    }

    @Override public void suppressModelFit(String source) { session.suppressFit(source); }

    @Override public java.util.Optional<ModelFitPreparation> prepareModelFit(String source, PreviewAppearance appearance) {
        if (!modelFitApplicable()) return java.util.Optional.empty();
        if (nativeFit != null && nativeFit.source.equals(source) && nativeFit.matches(appearance)
                && nativeFit.ownerCurrent()) return java.util.Optional.of(nativeFit);
        var owner = session;
        long generation = bodyPreparationGeneration;
        var previous = nativeFit;
        nativeFit = null;
        if (previous != null) previous.close();
        if (closed || session != owner || nativeFit != null || bodyPreparationGeneration != generation) return java.util.Optional.empty();
        var admission = owner.prepareFit();
        if (closed || session != owner || nativeFit != null || bodyPreparationGeneration != generation) return java.util.Optional.empty();
        nativeFit = new NativeFit(source, appearance, owner, admission);
        return java.util.Optional.of(nativeFit);
    }

    private final class NativeFit implements ModelFitPreparation {
        private final String source;
        private final PreviewAppearance appearance;
        private final EditorPreviewSession owner;
        private final EditorPreviewSession.FitPreparation admission;
        private final ClientLevel world = minecraft.level;
        private final LocalPlayer local = minecraft.player;
        private final java.util.Optional<NativePlayerSkinLifecycle.Registration> texture;
        private final java.util.concurrent.CompletableFuture<ModelFitMeasurement> result = new java.util.concurrent.CompletableFuture<>();
        private boolean retired, requested, eligible;
        private com.naocraftlab.skins.runtime.EffectiveSkinSource selectedSource;
        private com.naocraftlab.skins.client.PreviewModelFrames.Body cpmBody;
        private com.naocraftlab.skins.client.PreviewModelFrames.NeutralBody neutral;
        private PlayerSkinEarsFitMode.EarsBody ears;
        private ModelFitMeasurement reference, measured;
        private PreviewPlayer subject;

        private NativeFit(String source, PreviewAppearance appearance, EditorPreviewSession owner,
                EditorPreviewSession.FitPreparation admission) {
            this.source = source; this.appearance = appearance; this.owner = owner; this.admission = admission;
            texture = NativePlayerSkinLifecycle.registration(appearance.skin().location());
        }

        private boolean matches(PreviewAppearance value) {
            return appearance.skin().equals(value.skin()) && appearance.model() == value.model();
        }

        @Override public boolean ownerCurrent() {
            return !retired && !closed && admission.ownerCurrent() && minecraft.level == world && minecraft.player == local
                    && (gallery == null ? session == owner : gallerySlots.values().stream().anyMatch(slot -> slot.session() == owner))
                    && NativePlayerSkinLifecycle.registration(appearance.skin().location()).equals(texture)
                    && (selectedSource == null || selectedSource.active());
        }

        @Override public boolean current() {
            return ownerCurrent() && admission.current()
                    && texture.map(value -> value.state() != NativePlayerSkinLifecycle.State.FAILED
                            && value.state() != NativePlayerSkinLifecycle.State.RETIRED).orElse(true)
                    && (cpmBody == null || cpmBody.current().getAsBoolean())
                    && (ears == null || ears.current());
        }

        @Override public java.util.concurrent.CompletableFuture<ModelFitMeasurement> measure() {
            requested = true;
            return result;
        }

        @Override public void complete(ModelFitMeasurement value) {
            if (!current()) return;
            measured = Objects.requireNonNull(value);
            if (eligible) {
                admission.complete();
                releaseNeutral();
            }
        }

        private void releaseNeutral() {
            if (neutral != null) { neutral.close(); neutral = null; }
            subject = null;
        }

        @Override public void close() {
            if (!closed && measured != null && gallery != null && gallery.displays(owner)) return;
            if (retired) return;
            retired = true;
            admission.close();
            releaseNeutral();
            result.complete(null);
        }

        private void decline() {
            eligible = false;
            result.complete(null);
            owner.declineFit();
            releaseNeutral();
        }

        private boolean prepare(GuiGraphics graphics) {
            selectedSource = CapeProjection.skinSource(previewProfile.getId());
            var requirements = CapeProjection.previewBodyRequirements(previewProfile.getId());
            eligible = false;
            if (!current()) return false;
            if (!requirements.admitted()) {
                decline();
                return false;
            }
            boolean custom = requirements.cpm() == com.naocraftlab.skins.client.PreviewModelFrames.Requirement.REQUIRED;
            boolean requiredEars = requirements.ears() == com.naocraftlab.skins.client.PreviewModelFrames.Requirement.REQUIRED;
            if (requiredEars) {
                if (!PlayerSkinEarsFitMode.available()) { decline(); return false; }
                if (ears == null) ears = PlayerSkinEarsFitMode.prepare(selectedSource);
                if (ears == null) return owner.waitsForBody();
                if (!ears.current()) { decline(); return false; }
            }
            if (custom) {
                var body = com.naocraftlab.skins.client.PreviewModelFrames.prepare(previewProfile, appearance);
                if (body.status() == BodyStatus.PENDING) return owner.waitsForBody();
                if (body.status() != BodyStatus.READY || !body.current().getAsBoolean()) { decline(); return false; }
                cpmBody = body;
                if (measured == null && requested && !result.isDone()) {
                    if (neutral == null) neutral = com.naocraftlab.skins.client.PreviewModelFrames.neutral(previewProfile, appearance);
                    if (neutral == null || neutral.body().status() == BodyStatus.PENDING) return owner.waitsForBody();
                    if (neutral.body().status() != BodyStatus.READY || !neutral.body().current().getAsBoolean()) {
                        decline(); return false;
                    }
                }
            }
            eligible = true;
            if (measured != null) { admission.complete(); return false; }
            if (!requested || result.isDone()) return false;
            boolean extended = custom || requiredEars;
            boolean referencePass = extended && reference == null;
            var captureAppearance = new PreviewAppearance(appearance.skin(), appearance.model(), java.util.Optional.empty(),
                    CapeMode.OFF, com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(), false, false);
            if (subject == null) {
                subject = new PreviewPlayer(world, previewProfile);
                subject.setId(nextPreviewEntityId());
                subject.setPreviewSkin(playerSkin(captureAppearance));
                subject.setPreviewModelParts(previewModelParts(captureAppearance));
                subject.setOnGround(true);
                subject.setPose(Pose.STANDING);
            }
            graphics.flush();
            var pose = new PoseStack();
            var capture = new com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture(subject, this::current);
            try (var scope = PreviewScope.open(minecraft, subject, captureAppearance);
                    var neutralScope = neutral == null ? null : neutral.open(referencePass);
                    var earsScope = ears == null ? null : ears.open(subject, referencePass);
                    capture) {
                com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture.root(subject, pose.last().pose());
                try {
                    minecraft.getEntityRenderDispatcher().render(subject, 0, 0, 0, 0, 0, pose,
                            graphics.bufferSource(), LightTexture.FULL_BRIGHT);
                } finally {
                    graphics.bufferSource().endBatch();
                }
            }
            var value = capture.ordinary();
            if (value == null) decline();
            else if (referencePass) reference = value;
            else if (extended) {
                var fit = value.fit();
                result.complete(new ModelFitMeasurement(reference.referenceHeight(), reference.referenceCenterX(),
                        reference.referenceCenterY(), reference.referenceCenterZ(),
                        com.naocraftlab.skins.client.PreviewModelNormalization.full(reference.referenceHeight(), fit.bodyHeight(),
                                fit.centerX(), fit.centerY(), fit.centerZ(), fit.halfWidth(), fit.halfDepth())));
            } else result.complete(value);
            return true;
        }
    }

    private final Minecraft minecraft;
    private final DiagnosticSink diagnostics;
    private EditorPreviewSession session = new EditorPreviewSession();
    private EditorPreviewSession.Gallery gallery;
    private final java.util.Map<EditorPreviewSession, NativeSlot> gallerySlots = new java.util.LinkedHashMap<>();
    private EditorPreviewSession activeGallerySource;
    private int renderDepth;
    private java.util.function.Consumer<BodyStatus> bodyObserver = value -> {};

    private record NativeSlot(EditorPreviewSession session, GameProfile profile, PreviewPlayer player,
            ClientLevel level, EditorPreviewClock clock, EditorPreviewTickGate tickGate, NativeFit fit) {}

    private void saveGallerySlot() {
        if (activeGallerySource != null && gallerySlots.containsKey(activeGallerySource))
            gallerySlots.put(activeGallerySource, new NativeSlot(session, previewProfile, previewPlayer,
                    previewLevel, previewClock, previewTickGate, nativeFit));
    }

    private void selectGallerySession(EditorPreviewSession candidate) {
        gallerySlots.computeIfAbsent(candidate, ignored -> new NativeSlot(candidate,
                new GameProfile(UUID.randomUUID(), "NCLSkinPreview"), null, null,
                new EditorPreviewClock(), new EditorPreviewTickGate(), null));
        if (renderDepth == 0) selectGallerySlot(candidate);
    }

    private void selectGallerySlot(EditorPreviewSession source) {
        saveGallerySlot();
        var slot = gallerySlots.get(source);
        activeGallerySource = source;
        session = slot.session(); previewProfile = slot.profile(); previewPlayer = slot.player();
        previewLevel = slot.level(); previewClock = slot.clock(); previewTickGate = slot.tickGate(); nativeFit = slot.fit();
    }

    private void retireGallerySlots() {
        saveGallerySlot();
        if (renderDepth != 0) return;
        var retired = new java.util.ArrayList<NativeSlot>();
        for (var entry : new java.util.LinkedHashMap<>(gallerySlots).entrySet()) {
            var slot = entry.getValue();
            if (!closed && gallery != null && gallery.retains(slot.session())) continue;
            if (gallerySlots.remove(entry.getKey(), slot)) retired.add(slot);
        }
        for (var slot : retired) {
            if (slot.fit() != null) slot.fit().close();
            CapeProjection.retireSkin(slot.profile().getId());
        }
    }

    @Override public boolean prepareGalleryBody(String source, boolean cpm, java.util.function.BooleanSupplier candidateCurrent) {
        return prepareGalleryBody(source, source, cpm, candidateCurrent);
    }

    @Override public boolean prepareGalleryBody(String source, String demand, boolean cpm, java.util.function.BooleanSupplier candidateCurrent) {
        long generation = ++bodyPreparationGeneration;
        if (closed || !candidateCurrent.getAsBoolean() || closed || bodyPreparationGeneration != generation) return false;
        if (gallery == null) {
            var previousSession = session;
            var previousFit = nativeFit;
            nativeFit = null;
            previousSession.retireBody();
            if (previousFit != null) previousFit.close();
            if (!candidateCurrent.getAsBoolean() || closed || session != previousSession
                    || gallery != null || nativeFit != null || bodyPreparationGeneration != generation) return false;
            CapeProjection.retireSkin(previewProfile.getId());
            if (!candidateCurrent.getAsBoolean() || closed || session != previousSession
                    || gallery != null || nativeFit != null || bodyPreparationGeneration != generation) return false;
            gallery = new EditorPreviewSession.Gallery();
            gallery.observeBody(status -> { retireGallerySlots(); bodyObserver.accept(status); });
        }
        var owner = gallery;
        var candidate = owner.prepare(source, demand, cpm, minecraft.level != null && minecraft.player != null, candidateCurrent);
        if (candidate == null || closed || gallery != owner || bodyPreparationGeneration != generation) return false;
        retireGallerySlots();
        if (closed || gallery != owner || !owner.retains(candidate) || closed || gallery != owner
                || bodyPreparationGeneration != generation) return false;
        selectGallerySession(candidate);
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

    @Override public void renderRetained(GuiGraphics graphics, String source) {
        if (closed || gallery == null) return;
        var previous = gallery.retainedSource();
        if (previous == null) return;
        var request = gallery.retainedImage(previous);
        if (request == null) return;
        selectGallerySlot(gallery.retainedSession());
        try { render(graphics, request); }
        finally { saveGallerySlot(); if (!closed && gallerySlots.containsKey(gallery.session(source))) selectGallerySlot(gallery.session(source)); }
    }

    @Override public void renderGallery(GuiGraphics graphics, String source, PreviewRequest request) {
        if (closed || gallery == null || !gallerySlots.containsKey(gallery.session(source))) return;
        renderRetained(graphics, source);
        if (closed || gallery == null || !gallerySlots.containsKey(gallery.session(source))) return;
        var client = Minecraft.getInstance();
        var world = client.level;
        var local = client.player;
        var registration = NativePlayerSkinLifecycle.registration(request.appearance().skin().location());
        if (gallery != null) gallery.image(source, request, () -> !closed && client.level == world && client.player == local
                && NativePlayerSkinLifecycle.registration(request.appearance().skin().location()).equals(registration));
        var slot = gallerySlots.get(gallery.session(source));
        if (closed || renderDepth != 0 || slot == null || gallery == null || !gallery.retains(slot.session())) return;
        selectGallerySlot(gallery.session(source));
        renderDepth++;
        try { render(graphics, request); }
        finally { saveGallerySlot(); renderDepth--; retireGallerySlots(); }
    }

    @Override public PreviewAccessories galleryAccessories(String source, PreviewRequest request, java.util.function.BooleanSupplier inputCurrent) {
        if (closed || !inputCurrent.getAsBoolean() || closed)
            return PreviewAccessories.unavailable(LiveReason.LIVE_UNAVAILABLE);
        saveGallerySlot();
        var slot = gallerySlots.get(gallery.session(source));
        if (closed || slot == null || gallery == null)
            return PreviewAccessories.unavailable(LiveReason.LIVE_UNAVAILABLE);
        var owner = gallery;
        LiveReason reason = slot.session().liveReason(request.intent(), minecraft.level != null && minecraft.player != null,
                NativePlayerSkinLifecycle.isReady(request.appearance().skin().location()),
                slot.player() != null && slot.level() == minecraft.level);
        var value = CapeProjection.previewAccessories(slot.profile().getId(), request.appearance().skin().location(),
                request.appearance().model(), reason);
        return inputCurrent.getAsBoolean() && !closed && gallery == owner && gallerySlots.get(gallery.session(source)) == slot
                ? value : PreviewAccessories.unavailable(LiveReason.LIVE_UNAVAILABLE);
    }

    private java.util.function.Consumer<PreviewAccessories> accessoryObserver = value -> {};
    private EditorPreviewSession accessoryObserverSession;
    private EditorPreviewSession accessoryPreparation;
    private boolean closed;

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
        return CapeProjection.previewAccessories(previewProfile.getId(), request.appearance().skin().location(),
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

    private EditorPreviewClock previewClock = new EditorPreviewClock();
    private EditorPreviewTickGate previewTickGate = new EditorPreviewTickGate();
    private boolean layerFailureLogged;
    private GameProfile previewProfile =
            new GameProfile(UUID.randomUUID(), "NCLSkinPreview");
    private final PlayerModel<?> classic;
    private final PlayerModel<?> slim;
    private final ModelPart classicCloak;
    private final ModelPart slimCloak;
    private final ModelPart elytraRoot;
    private final ElytraModel<?> elytra;
    private PreviewPlayer previewPlayer;
    private ClientLevel previewLevel;

    public VanillaAppearancePreviewRenderer(Minecraft minecraft, DiagnosticSink diagnostics) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        VanillaPreviewModels models =
                VanillaPreviewModels.instance();
        classic = models.classic;
        slim = models.slim;
        classicCloak = models.classicCloak;
        slimCloak = models.slimCloak;
        elytraRoot = models.elytraRoot;
        elytra = models.elytra;
    }

    @Override public com.naocraftlab.skins.client.PreviewModelFrames.Body body() { return gallery == null ? session.body() : gallery.body(); }

    @Override public void observeBody(java.util.function.Consumer<BodyStatus> observer) {
        bodyObserver = Objects.requireNonNull(observer);
        if (gallery == null) session.observeBody(observer);
    }

    @Override public void prepareBody(String source, boolean cpm) { prepareBody(source, source, cpm); }

    @Override public void prepareBody(String source, String demand, boolean cpm) {
        if (closed) return;
        long generation = ++bodyPreparationGeneration;
        if (gallery != null) {
            saveGallerySlot();
            var retired = gallery;
            gallery = null; activeGallerySource = null;
            nativeFit = null;
            session = new EditorPreviewSession(); session.observeBody(bodyObserver);
            previewProfile = new GameProfile(UUID.randomUUID(), "NCLSkinPreview");
            previewPlayer = null; previewLevel = null;
            previewClock = new EditorPreviewClock(); previewTickGate = new EditorPreviewTickGate();
            retired.close(); retireGallerySlots();
            if (closed || bodyPreparationGeneration != generation) return;
        }
        var client = minecraft;
        accessoryObserverSession = null;
        accessoryPreparation = session;
        session.beginBody(source, demand, cpm, !closed && client.level != null && client.player != null);
    }

    @Override
    public void render(GuiGraphics graphics, PreviewRequest request) {
        var image = session;
        if (closed) { image.invalidateDisplayedBody(); return; }
        image.displayAuthority(java.util.Arrays.asList(request.appearance().skin(), request.appearance().model(),
                request.intent(), NativePlayerSkinLifecycle.registration(request.appearance().skin().location())),
                minecraft.level, minecraft.player);
        if (!image.imageTracked()) { renderPrepared(graphics, request); return; }
        if (image.imageSpinner()) {
            var frame = com.naocraftlab.skins.client.PreviewLoading.frame((long) image.imageFrame() * 100_000_000L,
                    request.left(), request.top(), request.width(), request.height(), 32);
            graphics.blit(ResourceLocation.tryParse(frame.resource()), frame.left(), frame.top(), frame.size(), frame.size(),
                    (float) frame.u(), (float) frame.v(), frame.width(), frame.height(), frame.textureWidth(), frame.textureHeight());
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

    private void renderPrepared(GuiGraphics graphics, PreviewRequest request) {
        var session = this.session;
        var frameAccessoryObserver = accessoryObserverFor(session);
        if (session.waitsForBody() && session.bodyStatus() != BodyStatus.READY) {
            if (session.bodyStatus() != BodyStatus.PENDING) { renderFallback(graphics, request); return; }
            if (!NativePlayerSkinLifecycle.isReady(request.appearance().skin().location())) return;
        }
        LocalPlayer localPlayer = minecraft.player;
        if (session.path(request.intent(), minecraft.level != null && localPlayer != null)
                        == EditorPreviewSession.Path.BAKED
                || !NativePlayerSkinLifecycle.isReady(request.appearance().skin().location())) {
            accessoryObserverFor(session).accept(accessories(request));
            renderFallback(graphics, request);
            return;
        }

        PreviewPlayer player = previewPlayer(request.appearance());
        if (player == null) {
            accessoryObserverFor(session).accept(accessories(request));
            renderFallback(graphics, request);
            return;
        }

        var fitting = nativeFit;
        if (fitting != null && fitting.matches(request.appearance()) && fitting.current()
                && (fitting.requested || fitting.measured != null) && fitting.prepare(graphics)) return;
        if (closed || this.session != session) return;
        try {
            if (session.waitsForBody()) {
                session.body(com.naocraftlab.skins.client.PreviewModelFrames.prepare(previewProfile, request.appearance()));
                if (session.bodyStatus() == BodyStatus.PENDING) return;
                if (session.bodyStatus() != BodyStatus.READY) { renderFallback(graphics, request); return; }
            }
        } catch (RuntimeException failure) {
            session.disableLive(failure);
            renderFallback(graphics, request);
            return;
        }

        var frameWorld = minecraft.level;
        var frameTexture = NativePlayerSkinLifecycle.registration(request.appearance().skin().location());
        var frameCurrent = session.frame();
        boolean ordinaryFit = session.fitSuppressed() || fitting != null && fitting.matches(request.appearance())
                && (!fitting.current() || !fitting.eligible || fitting.measured == null);
        java.util.function.BooleanSupplier displayCurrent = () -> !closed && minecraft.level == frameWorld
                && minecraft.player == localPlayer
                && (gallery == null ? previewPlayer == player && previewLevel == frameWorld : gallery.retains(session))
                && NativePlayerSkinLifecycle.isReady(request.appearance().skin().location())
                && NativePlayerSkinLifecycle.registration(request.appearance().skin().location()).equals(frameTexture)
                && frameCurrent.getAsBoolean();
        var completeBody = ordinaryFit ? session.ordinaryDisplay(displayCurrent) : session.displayBody(displayCurrent);
        var candidate = new com.naocraftlab.skins.client.PreviewModelFrames.Body(BodyStatus.ORDINARY, () -> true);
        boolean completed = false;
        boolean[] layersSucceeded = {true};
        try {
            try (PreviewScope scope = PreviewScope.open(
                    minecraft, player, request.appearance())) {
                configureEntity(player, request);
                PoseStack pose = graphics.pose();
                var incomingPose = pose.last();
                pose.pushPose();
                try {
                    var measurement = fitting != null && fitting.matches(request.appearance()) && fitting.current()
                            && fitting.eligible ? fitting.measured : null;
                    var fullFit = measurement == null ? null : measurement.fit();
                    float fittedScale = FIT_PADDING * request.height() / MODEL_HEIGHT * request.scale()
                            * (float) (fullFit == null ? 1 : fullFit.normalization());

                    pose.translate(0.0F, 0.0F, LegacyPreviewDepth.additional(fittedScale, 50.0F));
                    float pitchRadians = request.pitchDegrees() * DEGREES_TO_RADIANS;
                    Quaternionf cameraPitch = new Quaternionf().rotateX(pitchRadians);
                    Quaternionf modelRotation = new Quaternionf()
                            .rotateZ((float) Math.PI)
                            .mul(cameraPitch);
                    CenteredPlayerPreviewGeometry.EntityTranslation centeredTranslation =
                            CenteredPlayerPreviewGeometry.centeredEntityTranslation(
                                    player.getBbHeight(), pitchRadians);
                    Vector3f translation = new Vector3f(
                            0.0F, centeredTranslation.y(), centeredTranslation.z());


                    try (var projection = fullFit == null ? null : PlayerSkinProjection.open(measurement, request, fittedScale, player.getBbHeight());
                            EditorPreviewLayerGuard ignoredLayers =
                            EditorPreviewLayerGuard.open(failure -> {
                            layersSucceeded[0] = false;
                            onLiveLayerFailure(failure);
                        })) {
                        try { InventoryScreen.renderEntityInInventory(
                                graphics,
                                request.left() + request.width() / 2.0F,
                                request.top() + request.height() / 2.0F,
                                fittedScale / player.getScale(),
                                translation,
                                modelRotation,
                                cameraPitch,
                                player); }
                        finally { graphics.flush(); }
                    }
                } finally {
                    while (pose.last() != incomingPose) pose.popPose();
                }
                candidate = scope.body();
            }
            completed = layersSucceeded[0];
        } catch (RuntimeException failure) {
            boolean firstFailure = session.disableLive(failure);
            frameAccessoryObserver.accept(PreviewAccessories.unavailable(LiveReason.LIVE_UNAVAILABLE));
            if (firstFailure) {
                diagnostics.report(
                        DiagnosticEvent.CLIENT_PREVIEW_LIVE_DISABLED,
                        () -> DiagnosticDetails.failure(failure));
            }
        } finally {
            if (ordinaryFit && candidate.status() == BodyStatus.READY)
                candidate = new com.naocraftlab.skins.client.PreviewModelFrames.Body(BodyStatus.ORDINARY, candidate.current());
            completeBody.accept(completed ? candidate
                    : new com.naocraftlab.skins.client.PreviewModelFrames.Body(BodyStatus.FAILED, () -> true));
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

    private PreviewPlayer previewPlayer(PreviewAppearance appearance) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            previewPlayer = null;
            previewLevel = null;
            return null;
        }
        if (minecraft.player != null) CapeProjection.effectiveSkin(previewProfile.getId(), minecraft.player.getUUID(),
                appearance.skin().location(), appearance.model(), true);
        if (previewPlayer == null || previewLevel != level) {
            previewPlayer = new PreviewPlayer(level, previewProfile);
            previewPlayer.setId(nextPreviewEntityId());
            previewLevel = level;
        }
        previewPlayer.setPreviewSkin(playerSkin(appearance));
        previewPlayer.setPreviewModelParts(previewModelParts(appearance));
        for (EquipmentSlot slot : PREVIEW_EQUIPMENT) {
            previewPlayer.setItemSlot(slot, ItemStack.EMPTY);
        }
        if (appearance.capeMode() == CapeMode.ELYTRA) {
            previewPlayer.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        }
        return previewPlayer;
    }

    private void configureEntity(PreviewPlayer player, PreviewRequest request) {
        Entity cameraEntity = minecraft.getCameraEntity();
        if (cameraEntity != null) {
            player.setPos(cameraEntity.getX(), cameraEntity.getY(), cameraEntity.getZ());
        }
        float previewAge = previewClock.ageTicks(player.tickCount);
        EditorPreviewSession.SettlingMotion settling = session.capeSettling(
                request.intent(), true, request.appearance().capeMode(),
                request.appearance().cape().isPresent(), previewAge);
        double baseX = player.getX();
        double baseY = player.getY();
        double baseZ = player.getZ();
        player.setPos(baseX, baseY + settling.currentYOffset(), baseZ);
        player.xo = baseX;
        player.yo = baseY + settling.previousYOffset();
        player.zo = baseZ;
        float yaw = 180.0F - request.yawDegrees();
        player.setPose(Pose.STANDING);
        player.setInvisible(false);

        player.yBodyRot = yaw;
        player.yBodyRotO = yaw;
        player.setYRot(yaw);
        player.yRotO = yaw;
        player.setYHeadRot(yaw);
        player.yHeadRotO = yaw;


        player.setXRot(0.0F);
        player.xRotO = 0.0F;

        player.xCloak = player.getX();
        player.yCloak = player.getY();
        player.zCloak = player.getZ();
        player.xCloakO = baseX;
        player.yCloakO = baseY + settling.previousYOffset();
        player.zCloakO = baseZ;
        player.elytraRotX = 0.2617994F;
        player.elytraRotY = 0.0F;
        player.elytraRotZ = -0.2617994F;
        player.tickCount = Math.max(0, (int) Math.floor(previewAge));
        if (previewTickGate.shouldTick(previewAge, settling.active())) {
            player.tick();
            player.tickCount = Math.max(0, (int) Math.floor(previewAge));
        }
    }

    private static byte previewModelParts(PreviewAppearance appearance) {
        int mask = 0;
        for (PlayerModelPart part : PlayerModelPart.values()) {
            boolean visible = part == PlayerModelPart.CAPE
                    ? appearance.capeMode() != CapeMode.OFF
                    : appearance.outerLayerVisibility().visible(outerLayerPart(part));
            if (visible) {
                mask |= part.getMask();
            }
        }
        return (byte) mask;
    }

    private static PlayerSkin playerSkin(PreviewAppearance appearance) {
        ResourceLocation skin = parseTexture(appearance.skin().location());
        ResourceLocation selectedCape = appearance.cape()
                .map(handle -> parseTexture(handle.location()))
                .orElse(null);
        ResourceLocation cape = appearance.capeMode() == CapeMode.CAPE ? selectedCape : null;
        ResourceLocation elytra = appearance.capeMode() == CapeMode.ELYTRA && appearance.capeHasElytra() ? selectedCape : null;
        PlayerSkin.Model model = appearance.model() == SkinModel.SLIM
                ? PlayerSkin.Model.SLIM
                : PlayerSkin.Model.WIDE;
        return new PlayerSkin(skin, "", cape, elytra, model, false);
    }

    private static int nextPreviewEntityId() {
        int candidate = NEXT_PREVIEW_ENTITY_ID.getAndDecrement();
        return candidate == 0 ? NEXT_PREVIEW_ENTITY_ID.getAndDecrement() : candidate;
    }

    private static PlayerTeam previewTeam() {
        PlayerTeam team = new PlayerTeam(new Scoreboard(), "nclskins_preview");
        team.setNameTagVisibility(Team.Visibility.NEVER);
        return team;
    }

    private void renderFallback(GuiGraphics graphics, PreviewRequest request) {
        var session = this.session;
        var frameGallery = gallery;
        var world = minecraft.level;
        var local = minecraft.player;
        var texture = NativePlayerSkinLifecycle.registration(request.appearance().skin().location());
        var completion = session.ordinaryDisplay(() -> !closed && minecraft.level == world && minecraft.player == local
                && gallery == frameGallery && (frameGallery == null ? this.session == session : frameGallery.retains(session))
                && NativePlayerSkinLifecycle.isReady(request.appearance().skin().location())
                && NativePlayerSkinLifecycle.registration(request.appearance().skin().location()).equals(texture));
        try {
        ResourceLocation skin = parseTexture(request.appearance().skin().location());
        ResourceLocation cape = request.appearance().cape()
                .map(handle -> request.appearance().capeMode() == CapeMode.ELYTRA && !request.appearance().capeHasElytra()
                        ? ResourceLocation.withDefaultNamespace("textures/entity/elytra.png") : parseTexture(handle.location()))
                .orElse(null);
        boolean slimModel = request.appearance().model() == SkinModel.SLIM;
        PlayerModel<?> player = slimModel ? slim : classic;
        ModelPart cloak = slimModel ? slimCloak : classicCloak;

        BakedPlayerPose.configure(player, request.appearance().outerLayerVisibility());
        classicCloak.resetPose();
        classicCloak.visible = false;
        slimCloak.resetPose();
        slimCloak.visible = false;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            CenteredPlayerPreviewGeometry.Layout layout =
                    CenteredPlayerPreviewGeometry.fit(
                            request.left(),
                            request.top(),
                            request.width(),
                            request.height(),
                            request.scale());
            float modelScale = layout.scale();
            pose.translate(
                    layout.centerX(),
                    layout.centerY(),
                    LegacyPreviewDepth.required(modelScale));
            VanillaPlayerModelTransform.applyCentered(
                    pose,
                    modelScale,
                    request.yawDegrees(),
                    request.pitchDegrees(),
                    POSE_OPERATIONS);

            Lighting.setupForEntityInInventory();
            MultiBufferSource.BufferSource buffers = graphics.bufferSource();

            VertexConsumer skinBuffer = buffers.getBuffer(RenderType.entityTranslucent(skin));
            player.renderToBuffer(
                    pose, skinBuffer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            if (cape != null) {
                renderBackEquipment(
                        pose,
                        buffers,
                        cloak,
                        request.appearance().capeMode(),
                        request.appearance().capeGlint(),
                        cape);
            }
            graphics.flush();
        } finally {
            pose.popPose();
            Lighting.setupFor3DItems();
        }
        completion.accept(new com.naocraftlab.skins.client.PreviewModelFrames.Body(BodyStatus.ORDINARY, () -> true));
        } catch (RuntimeException | Error failure) {
            completion.accept(new com.naocraftlab.skins.client.PreviewModelFrames.Body(BodyStatus.FAILED, () -> true));
            throw failure;
        }
    }

    private void renderBackEquipment(
            PoseStack pose,
            MultiBufferSource.BufferSource buffers,
            ModelPart cloak,
            CapeMode capeMode,
            boolean capeGlint,
            ResourceLocation texture) {
        if (capeMode == CapeMode.CAPE) {
            pose.pushPose();
            try {
                VanillaBackEquipmentTransform.applyCapeAttachment(
                        pose,
                        WORLDLESS_CAPE_ATTACHMENT_Z,
                        BACK_EQUIPMENT_OPERATIONS);
                cloak.resetPose();
                cloak.visible = true;
                try {
                    VertexConsumer capeBuffer = CapeTranslucentFoilMaterial.buffer(buffers, texture, capeGlint,
                            RenderType.entityTranslucent(texture));
                    cloak.render(
                            pose,
                            capeBuffer,
                            LightTexture.FULL_BRIGHT,
                            OverlayTexture.NO_OVERLAY);
                } finally {
                    cloak.visible = false;
                }
            } finally {
                pose.popPose();
            }
        } else if (capeMode == CapeMode.ELYTRA) {
            pose.pushPose();
            try {
                VanillaBackEquipmentTransform.applyElytraAttachment(
                        pose, BACK_EQUIPMENT_OPERATIONS);
                elytraRoot.getAllParts().forEach(ModelPart::resetPose);
                elytra.young = false;
                elytra.riding = false;
                elytra.attackTime = 0.0F;
                elytra.renderToBuffer(
                        pose,
                        buffers.getBuffer(RenderType.armorCutoutNoCull(texture)),
                        LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY,
                        0xFFFFFFFF);
            } finally {
                pose.popPose();
            }
        }
    }

    private static ResourceLocation parseTexture(String value) {
        ResourceLocation location = ResourceLocation.tryParse(value);
        if (location == null) {
            throw new IllegalArgumentException("Invalid preview texture location");
        }
        return location;
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

    private static final class PreviewPlayer extends RemotePlayer {
        private PlayerSkin previewSkin;

        private PreviewPlayer(ClientLevel level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public PlayerSkin getSkin() {
            return previewSkin == null ? DefaultPlayerSkin.get(getUUID()) : previewSkin;
        }

        @Override
        public boolean isSpectator() {
            return false;
        }

        @Override
        public boolean isCreative() {
            return false;
        }

        @Override
        public PlayerTeam getTeam() {
            return PREVIEW_TEAM;
        }

        private void setPreviewSkin(PlayerSkin previewSkin) {
            this.previewSkin = previewSkin;
        }

        private void setPreviewModelParts(byte modelParts) {
            getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, modelParts);
        }
    }
    @Override public void retireBody() {
        bodyPreparationGeneration++;
        var previousSession = session;
        var previous = nativeFit;
        var retiredGallery = gallery;
        nativeFit = null;
        gallery = null;
        activeGallerySource = null;
        if (previous != null) previousSession.suppressFit(previous.source);
        previousSession.retireBody();
        if (retiredGallery != null) retiredGallery.close();
        retireGallerySlots();
        if (previous != null) previous.close();
    }

    @Override public void close() {
        closed = true;
        bodyPreparationGeneration++;
        if (nativeFit != null) nativeFit.close();
        session.invalidateDisplayedBody();
        if (gallery != null) gallery.close();
        retireGallerySlots();
        clearAccessoryInspection();
        accessoryObserver = value -> {};
        if (renderDepth == 0) CapeProjection.retireSkin(previewProfile.getId());
        previewPlayer = null;
        previewLevel = null;
    }

}

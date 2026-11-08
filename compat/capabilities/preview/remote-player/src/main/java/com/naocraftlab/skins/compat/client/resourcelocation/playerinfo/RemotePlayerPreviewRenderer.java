package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

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
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry;
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
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.joml.Quaternionf;


public final class RemotePlayerPreviewRenderer implements PreviewRenderer<GuiGraphics>, AutoCloseable {
    private static final AtomicInteger NEXT_PREVIEW_ID = new AtomicInteger(-1);
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final float WORLDLESS_CAPE_ATTACHMENT_Z = 0.15625F;
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
                    PreviewPoseScale.apply(pose, x, y, z);
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
                    PreviewPoseScale.apply(pose, x, y, z);
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

    private final Minecraft minecraft;
    private final DiagnosticSink diagnostics;
    private EditorPreviewSession session = new EditorPreviewSession();
    private EditorPreviewSession.Gallery gallery;
    private final java.util.Map<String, NativeSlot> gallerySlots = new java.util.LinkedHashMap<>();
    private String activeGallerySource;
    private int renderDepth;
    private java.util.function.Consumer<BodyStatus> bodyObserver = value -> {};

    private record NativeSlot(EditorPreviewSession session, GameProfile profile, PreviewPlayer player,
            ClientLevel level, EditorPreviewClock clock, EditorPreviewTickGate tickGate) {}

    private void saveGallerySlot() {
        if (activeGallerySource != null && gallerySlots.containsKey(activeGallerySource))
            gallerySlots.put(activeGallerySource, new NativeSlot(session, previewProfile, previewPlayer,
                    previewLevel, previewClock, previewTickGate));
    }

    private void selectGallerySlot(String source) {
        saveGallerySlot();
        var slot = gallerySlots.get(source);
        activeGallerySource = source;
        session = slot.session(); previewProfile = slot.profile(); previewPlayer = slot.player();
        previewLevel = slot.level(); previewClock = slot.clock(); previewTickGate = slot.tickGate();
    }

    private void retireGallerySlots() {
        saveGallerySlot();
        if (renderDepth != 0) return;
        gallerySlots.entrySet().removeIf(entry -> {
            if (!closed && gallery != null && gallery.retains(entry.getValue().session())) return false;
            CapeProjection.retireSkin(entry.getValue().profile().getId());
            return true;
        });
    }

    @Override public boolean prepareGalleryBody(String source, boolean cpm, java.util.function.BooleanSupplier candidateCurrent) {
        if (closed || !candidateCurrent.getAsBoolean() || closed) return false;
        if (gallery == null) {
            session.invalidateDisplayedBody();
            if (closed) return false;
            CapeProjection.retireSkin(previewProfile.getId());
            gallery = new EditorPreviewSession.Gallery();
            gallery.observeBody(status -> { retireGallerySlots(); bodyObserver.accept(status); });
        }
        var candidate = gallery.prepare(source, cpm, minecraft.level != null && minecraft.player != null, candidateCurrent);
        if (candidate == null || closed || gallery == null) return false;
        retireGallerySlots();
        if (closed || !gallery.retains(candidate)) return false;
        gallerySlots.computeIfAbsent(source, ignored -> new NativeSlot(candidate,
                new GameProfile(UUID.randomUUID(), "NCLSkinPreview"), null, null,
                new EditorPreviewClock(), new EditorPreviewTickGate()));
        if (renderDepth == 0) selectGallerySlot(source);
        accessoryPreparation = candidate;
        return true;
    }

    @Override public void confirmGalleryBody(String source) {
        if (closed || gallery == null) return;
        gallery.confirm(source);
        if (!closed && gallery != null) retireGallerySlots();
    }

    @Override public void renderGallery(GuiGraphics graphics, String source, PreviewRequest request) {
        var slot = gallerySlots.get(source);
        if (closed || renderDepth != 0 || slot == null || gallery == null || !gallery.retains(slot.session())) return;
        selectGallerySlot(source);
        renderDepth++;
        try { render(graphics, request); }
        finally { saveGallerySlot(); renderDepth--; retireGallerySlots(); }
    }

    @Override public PreviewAccessories galleryAccessories(String source, PreviewRequest request, java.util.function.BooleanSupplier inputCurrent) {
        if (closed || !inputCurrent.getAsBoolean() || closed)
            return PreviewAccessories.unavailable(LiveReason.LIVE_UNAVAILABLE);
        saveGallerySlot();
        var slot = gallerySlots.get(source);
        if (closed || slot == null || gallery == null)
            return PreviewAccessories.unavailable(LiveReason.LIVE_UNAVAILABLE);
        var owner = gallery;
        LiveReason reason = slot.session().liveReason(request.intent(), minecraft.level != null && minecraft.player != null,
                NativePlayerSkinLifecycle.isReady(request.appearance().skin().location()),
                slot.player() != null && slot.level() == minecraft.level);
        var value = CapeProjection.previewAccessories(slot.profile().getId(), request.appearance().skin().location(),
                request.appearance().model(), reason);
        return inputCurrent.getAsBoolean() && !closed && gallery == owner && gallerySlots.get(source) == slot
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
    private final PlayerModel<LivingEntity> classicModel;
    private final PlayerModel<LivingEntity> slimModel;
    private final ModelPart classicCloak;
    private final ModelPart slimCloak;
    private final ModelPart elytraRoot;
    private final ElytraModel<LivingEntity> elytraModel;
    private PreviewPlayer previewPlayer;
    private ClientLevel previewLevel;

    public RemotePlayerPreviewRenderer(DiagnosticSink diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        minecraft = Minecraft.getInstance();
        VanillaPreviewModels models =
                VanillaPreviewModels.instance();
        classicModel = models.classic;
        slimModel = models.slim;
        classicCloak = models.classicCloak;
        slimCloak = models.slimCloak;
        elytraRoot = models.elytraRoot;
        elytraModel = models.elytra;
    }

    @Override public com.naocraftlab.skins.client.PreviewModelFrames.Body body() { return gallery == null ? session.body() : gallery.body(); }

    @Override public void observeBody(java.util.function.Consumer<BodyStatus> observer) {
        bodyObserver = Objects.requireNonNull(observer);
        if (gallery == null) session.observeBody(observer);
    }

    @Override public void prepareBody(String source, boolean cpm) {
        if (closed) return;
        if (gallery != null) {
            gallery.close(); retireGallerySlots(); gallery = null; activeGallerySource = null;
            if (closed) return;
            session = new EditorPreviewSession(); session.observeBody(bodyObserver);
            previewProfile = new GameProfile(UUID.randomUUID(), "NCLSkinPreview");
            previewPlayer = null; previewLevel = null;
            previewClock = new EditorPreviewClock(); previewTickGate = new EditorPreviewTickGate();
        }
        var client = minecraft;
        accessoryObserverSession = null;
        accessoryPreparation = session;
        session.beginBody(source, cpm, !closed && client.level != null && client.player != null);
    }

    @Override
    public void render(GuiGraphics graphics, PreviewRequest request) {
        var session = this.session;
        var frameAccessoryObserver = accessoryObserverFor(session);
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(request, "request");
        if (closed) { session.invalidateDisplayedBody(); return; }
        session.displayAuthority(java.util.Arrays.asList(request.appearance().skin(), request.appearance().model(),
                request.intent(), NativePlayerSkinLifecycle.registration(request.appearance().skin().location())),
                minecraft.level, minecraft.player);
        if (session.waitsForBody() && session.bodyStatus() != BodyStatus.READY) {
            if (session.bodyStatus() != BodyStatus.PENDING) { renderFallback(graphics, request); return; }
            if (!NativePlayerSkinLifecycle.isReady(request.appearance().skin().location())) { session.invalidateDisplayedBody(); return; }
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
        var completeBody = session.displayBody(() -> !closed && minecraft.level == frameWorld
                && minecraft.player == localPlayer
                && (gallery == null ? previewPlayer == player && previewLevel == frameWorld : gallery.retains(session))
                && NativePlayerSkinLifecycle.isReady(request.appearance().skin().location())
                && NativePlayerSkinLifecycle.registration(request.appearance().skin().location()).equals(frameTexture)
                && frameCurrent.getAsBoolean());
        var candidate = new com.naocraftlab.skins.client.PreviewModelFrames.Body(BodyStatus.ORDINARY, () -> true);
        boolean completed = false;
        boolean[] layersSucceeded = {true};
        try {
            try (PreviewScope scope = PreviewScope.open(
                    minecraft, player, request.appearance())) {
                configureEntity(player, request);
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
                int fittedScale = Math.max(1, Math.round(layout.scale()));
                int renderedEntityScale = Math.max(
                        1,
                        Math.round(fittedScale / player.getScale()));
                int legacyAnchorY = Math.round(CenteredPlayerPreviewGeometry.legacyEntityAnchorY(
                        layout.centerY(), renderedEntityScale, player.getBbHeight(),
                        request.pitchDegrees() * DEGREES_TO_RADIANS));


                pose.translate(0.0F, 0.0F, LegacyPreviewDepth.additional(fittedScale, 50.0F));
                float pitchRadians = request.pitchDegrees() * DEGREES_TO_RADIANS;
                Quaternionf cameraPitch = new Quaternionf().rotateX(pitchRadians);
                Quaternionf modelRotation = new Quaternionf()
                        .rotateZ((float) Math.PI)
                        .mul(cameraPitch);

                try (EditorPreviewLayerGuard ignoredLayers =
                        EditorPreviewLayerGuard.open(failure -> {
                            layersSucceeded[0] = false;
                            onLiveLayerFailure(failure);
                        })) {
                    InventoryScreen.renderEntityInInventory(
                            graphics,
                            Math.round(layout.centerX()),
                            legacyAnchorY,
                            renderedEntityScale,
                            modelRotation,
                            cameraPitch,
                            player);
                }
                } finally {
                    pose.popPose();
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
            previewPlayer.setId(nextPreviewId());
            previewLevel = level;
        }
        previewPlayer.setAppearance(appearance);
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
        player.setCustomNameVisible(false);

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

    private void renderFallback(GuiGraphics graphics, PreviewRequest request) {
        var session = this.session;
        var world = minecraft.level;
        var local = minecraft.player;
        var texture = NativePlayerSkinLifecycle.registration(request.appearance().skin().location());
        var completion = session.ordinaryDisplay(() -> !closed && minecraft.level == world && minecraft.player == local
                && NativePlayerSkinLifecycle.isReady(request.appearance().skin().location())
                && NativePlayerSkinLifecycle.registration(request.appearance().skin().location()).equals(texture));
        try {
        PreviewAppearance appearance = request.appearance();
        PlayerModel<LivingEntity> model = appearance.model() == SkinModel.SLIM
                ? slimModel
                : classicModel;
        configureLayers(model, appearance.outerLayerVisibility());
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
            float scale = layout.scale();
            pose.translate(
                    layout.centerX(),
                    layout.centerY(),
                    LegacyPreviewDepth.required(scale));
            VanillaPlayerModelTransform.applyCentered(
                    pose,
                    scale,
                    request.yawDegrees(),
                    request.pitchDegrees(),
                    POSE_OPERATIONS);

            BakedPreviewLighting.setup();
            MultiBufferSource.BufferSource buffers = graphics.bufferSource();
            ResourceLocation skin = location(appearance.skin());
            model.renderToBuffer(
                    pose,
                    buffers.getBuffer(model.renderType(skin)),
                    FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY,
                    1.0F,
                    1.0F,
                    1.0F,
                    1.0F);

            appearance.cape().ifPresent(capeHandle -> renderBackEquipment(
                    pose,
                    buffers,
                    model,
                    model == slimModel ? slimCloak : classicCloak,
                    appearance.capeMode(),
                    appearance.capeGlint(),
                    appearance.capeMode() == CapeMode.ELYTRA && !appearance.capeHasElytra()
                            ? new ResourceLocation("minecraft", "textures/entity/elytra.png") : location(capeHandle)));
            buffers.endBatch();
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
            PlayerModel<LivingEntity> model,
            ModelPart cloak,
            CapeMode capeMode,
            boolean capeGlint,
            ResourceLocation capeTexture) {
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
                    VertexConsumer capeBuffer = CapeTranslucentFoilMaterial.buffer(buffers, capeTexture, capeGlint,
                            RenderType.entityTranslucent(capeTexture));
                    cloak.render(
                            pose,
                            capeBuffer,
                            FULL_BRIGHT,
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
                model.copyPropertiesTo(elytraModel);
                elytraModel.young = false;
                elytraModel.renderToBuffer(
                        pose,
                        buffers.getBuffer(RenderType.armorCutoutNoCull(capeTexture)),
                        FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY,
                        1.0F,
                        1.0F,
                        1.0F,
                        1.0F);
            } finally {
                pose.popPose();
            }
        }
    }

    private static ResourceLocation location(TextureRegistry.TextureHandle handle) {
        ResourceLocation location = ResourceLocation.tryParse(handle.location());
        if (location == null) {
            throw new IllegalArgumentException("Invalid texture location");
        }
        return location;
    }

    private static int nextPreviewId() {
        int candidate = NEXT_PREVIEW_ID.getAndDecrement();
        return candidate == 0 ? NEXT_PREVIEW_ID.getAndDecrement() : candidate;
    }

    private static void configureLayers(PlayerModel<?> model, OuterLayerVisibility outerLayer) {
        model.head.resetPose();
        model.body.resetPose();
        model.rightArm.resetPose();
        model.leftArm.resetPose();
        model.rightLeg.resetPose();
        model.leftLeg.resetPose();
        model.hat.resetPose();
        model.jacket.resetPose();
        model.rightSleeve.resetPose();
        model.leftSleeve.resetPose();
        model.rightPants.resetPose();
        model.leftPants.resetPose();
        model.setAllVisible(true);
        model.attackTime = 0.0F;
        model.crouching = false;
        model.riding = false;
        model.young = false;
        model.hat.visible = outerLayer.visible(OuterLayerPart.HEAD);
        model.jacket.visible = outerLayer.visible(OuterLayerPart.BODY);
        model.leftSleeve.visible = outerLayer.visible(OuterLayerPart.LEFT_ARM);
        model.rightSleeve.visible = outerLayer.visible(OuterLayerPart.RIGHT_ARM);
        model.leftPants.visible = outerLayer.visible(OuterLayerPart.LEFT_LEG);
        model.rightPants.visible = outerLayer.visible(OuterLayerPart.RIGHT_LEG);
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
        private static final PlayerTeam HIDDEN_NAME_TEAM = hiddenNameTeam();
        private ResourceLocation previewSkin;
        private ResourceLocation previewCape;
        private ResourceLocation previewElytra;
        private String previewModel;

        private PreviewPlayer(ClientLevel level, GameProfile profile) {
            super(level, profile);
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
        public boolean isCapeLoaded() {
            return previewCape != null;
        }

        @Override
        public ResourceLocation getCloakTextureLocation() {
            return previewCape;
        }

        @Override
        public boolean isSkinLoaded() {
            return true;
        }

        @Override
        public ResourceLocation getSkinTextureLocation() {
            return previewSkin == null ? DefaultPlayerSkin.getDefaultSkin(getUUID()) : previewSkin;
        }

        @Override
        public boolean isElytraLoaded() {
            return previewElytra != null;
        }

        @Override
        public ResourceLocation getElytraTextureLocation() {
            return previewElytra;
        }

        @Override
        public String getModelName() {
            return previewModel == null
                    ? DefaultPlayerSkin.getSkinModelName(getUUID())
                    : previewModel;
        }

        @Override
        public boolean shouldShowName() {
            return false;
        }

        @Override
        public Team getTeam() {
            return HIDDEN_NAME_TEAM;
        }

        private void setAppearance(PreviewAppearance appearance) {
            previewSkin = location(appearance.skin());
            ResourceLocation selectedCape = appearance.cape()
                    .map(RemotePlayerPreviewRenderer::location)
                    .orElse(null);
            previewCape = appearance.capeMode() == CapeMode.CAPE ? selectedCape : null;
            previewElytra = appearance.capeMode() == CapeMode.ELYTRA && selectedCape != null
                    ? appearance.capeHasElytra() ? selectedCape : new ResourceLocation("minecraft", "textures/entity/elytra.png") : null;
            previewModel = appearance.model() == SkinModel.SLIM ? "slim" : "default";
        }

        private void setPreviewModelParts(byte modelParts) {
            getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, modelParts);
        }

        private static PlayerTeam hiddenNameTeam() {
            Scoreboard scoreboard = new Scoreboard();
            PlayerTeam team = scoreboard.addPlayerTeam("nclskins_preview");
            team.setNameTagVisibility(Team.Visibility.NEVER);
            return team;
        }
    }
    @Override public void close() {
        closed = true;
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

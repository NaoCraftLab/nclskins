package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
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


public final class RemotePlayerPreviewRenderer implements PreviewRenderer<GuiGraphics> {
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

    private final Minecraft minecraft;
    private final DiagnosticSink diagnostics;
    private final EditorPreviewSession session = new EditorPreviewSession();
    private final EditorPreviewClock previewClock = new EditorPreviewClock();
    private final EditorPreviewTickGate previewTickGate = new EditorPreviewTickGate();
    private boolean layerFailureLogged;
    private final GameProfile previewProfile =
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

    @Override
    public void render(GuiGraphics graphics, PreviewRequest request) {
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(request, "request");
        LocalPlayer localPlayer = minecraft.player;
        if (session.path(request.intent(), minecraft.level != null && localPlayer != null)
                        == EditorPreviewSession.Path.BAKED
                || !NativePlayerSkinLifecycle.isReady(request.appearance().skin().location())) {
            renderFallback(graphics, request);
            return;
        }

        PreviewPlayer player = previewPlayer(request.appearance());
        if (player == null) {
            renderFallback(graphics, request);
            return;
        }

        try (PreviewScope ignored = PreviewScope.open(
                minecraft, localPlayer, request.appearance())) {
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
                    EditorPreviewLayerGuard.open(this::onLiveLayerFailure)) {
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
        } catch (RuntimeException failure) {
            if (session.disableLive(failure)) {
                diagnostics.report(
                        DiagnosticEvent.CLIENT_PREVIEW_LIVE_DISABLED,
                        () -> DiagnosticDetails.failure(failure));
            }
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

            Lighting.setupForEntityInInventory();
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
                    location(capeHandle)));
            buffers.endBatch();
        } finally {
            pose.popPose();
            Lighting.setupFor3DItems();
        }
    }

    private void renderBackEquipment(
            PoseStack pose,
            MultiBufferSource.BufferSource buffers,
            PlayerModel<LivingEntity> model,
            ModelPart cloak,
            CapeMode capeMode,
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
                    cloak.render(
                            pose,
                            buffers.getBuffer(RenderType.entitySolid(capeTexture)),
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
            previewElytra = appearance.capeMode() == CapeMode.ELYTRA ? selectedCape : null;
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
}

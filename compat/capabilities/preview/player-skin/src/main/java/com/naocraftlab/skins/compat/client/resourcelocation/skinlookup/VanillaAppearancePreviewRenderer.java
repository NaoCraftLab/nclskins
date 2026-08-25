package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.naocraftlab.skins.client.CenteredPlayerPreviewGeometry;
import com.naocraftlab.skins.client.EditorPreviewLayerGuard;
import com.naocraftlab.skins.client.EditorPreviewClock;
import com.naocraftlab.skins.client.EditorPreviewSession;
import com.naocraftlab.skins.client.LegacyPreviewDepth;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
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


public final class VanillaAppearancePreviewRenderer implements PreviewRenderer<GuiGraphics> {
    private static final AtomicInteger NEXT_PREVIEW_ENTITY_ID = new AtomicInteger(-1);
    private static final PlayerTeam PREVIEW_TEAM = previewTeam();
    private static final float WORLDLESS_CAPE_ATTACHMENT_Z = 0.15625F;
    private static final float MODEL_HEIGHT = 2.125F;
    private static final float FIT_PADDING = 0.97F;
    private static final float ENTITY_Y_OFFSET = 0.0625F;
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
    private boolean layerFailureLogged;
    private final GameProfile previewProfile =
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

    @Override
    public void render(GuiGraphics graphics, PreviewRequest request) {
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
            float fittedScale = FIT_PADDING * request.height() / MODEL_HEIGHT * request.scale();


            pose.translate(0.0F, 0.0F, LegacyPreviewDepth.additional(fittedScale, 50.0F));
            float pitchRadians = request.pitchDegrees() * DEGREES_TO_RADIANS;
            Quaternionf cameraPitch = new Quaternionf().rotateX(pitchRadians);
            Quaternionf modelRotation = new Quaternionf()
                    .rotateZ((float) Math.PI)
                    .mul(cameraPitch);
            Vector3f translation = new Vector3f(
                    0.0F,
                    player.getBbHeight() / 2.0F + ENTITY_Y_OFFSET,
                    0.0F);

            try (EditorPreviewLayerGuard ignoredLayers =
                    EditorPreviewLayerGuard.open(this::onLiveLayerFailure)) {
                InventoryScreen.renderEntityInInventory(
                        graphics,
                        request.left() + request.width() / 2.0F,
                        request.top() + request.height() / 2.0F,
                        fittedScale / player.getScale(),
                        translation,
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
        player.xCloakO = player.getX();
        player.yCloakO = player.getY();
        player.zCloakO = player.getZ();
        player.elytraRotX = 0.2617994F;
        player.elytraRotY = 0.0F;
        player.elytraRotZ = -0.2617994F;
        player.tickCount = Math.max(0, (int) Math.floor(previewClock.ageTicks(player.tickCount)));
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
        ResourceLocation cape = appearance.capeMode() == CapeMode.OFF ? null : selectedCape;
        ResourceLocation elytra = appearance.capeMode() == CapeMode.ELYTRA ? selectedCape : null;
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
        ResourceLocation skin = parseTexture(request.appearance().skin().location());
        ResourceLocation cape = request.appearance().cape()
                .map(handle -> parseTexture(handle.location()))
                .orElse(null);
        boolean slimModel = request.appearance().model() == SkinModel.SLIM;
        PlayerModel<?> player = slimModel ? slim : classic;
        ModelPart cloak = slimModel ? slimCloak : classicCloak;

        configurePlayerModel(player, request.appearance().outerLayerVisibility());
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
                        cape);
            }
            graphics.flush();
        } finally {
            pose.popPose();
            Lighting.setupFor3DItems();
        }
    }

    private void renderBackEquipment(
            PoseStack pose,
            MultiBufferSource.BufferSource buffers,
            ModelPart cloak,
            CapeMode capeMode,
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
                    cloak.render(
                            pose,
                            buffers.getBuffer(RenderType.entitySolid(texture)),
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

    private static void configurePlayerModel(PlayerModel<?> player, OuterLayerVisibility outerLayer) {
        player.head.resetPose();
        player.body.resetPose();
        player.rightArm.resetPose();
        player.leftArm.resetPose();
        player.rightLeg.resetPose();
        player.leftLeg.resetPose();
        player.hat.resetPose();
        player.jacket.resetPose();
        player.rightSleeve.resetPose();
        player.leftSleeve.resetPose();
        player.rightPants.resetPose();
        player.leftPants.resetPose();
        player.setAllVisible(true);
        player.attackTime = 0.0F;
        player.crouching = false;
        player.riding = false;
        player.young = false;

        player.rightArm.zRot = 0.06F;
        player.leftArm.zRot = -0.06F;
        player.rightLeg.zRot = 0.01F;
        player.leftLeg.zRot = -0.01F;
        player.hat.visible = outerLayer.visible(OuterLayerPart.HEAD);
        player.jacket.visible = outerLayer.visible(OuterLayerPart.BODY);
        player.rightSleeve.visible = outerLayer.visible(OuterLayerPart.RIGHT_ARM);
        player.leftSleeve.visible = outerLayer.visible(OuterLayerPart.LEFT_ARM);
        player.rightPants.visible = outerLayer.visible(OuterLayerPart.RIGHT_LEG);
        player.leftPants.visible = outerLayer.visible(OuterLayerPart.LEFT_LEG);
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
}

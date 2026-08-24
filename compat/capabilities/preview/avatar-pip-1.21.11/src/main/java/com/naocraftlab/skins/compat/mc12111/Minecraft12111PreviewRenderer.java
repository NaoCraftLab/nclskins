package com.naocraftlab.skins.compat.mc12111;

import com.mojang.authlib.GameProfile;
import com.naocraftlab.skins.client.CenteredPipPreviewTransform;
import com.naocraftlab.skins.client.CenteredPlayerPreviewGeometry;
import com.naocraftlab.skins.client.EditorPreviewSession;
import com.naocraftlab.skins.client.EditorPreviewClock;
import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.diagnostics.DiagnosticDetails;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Quaternionf;
import org.joml.Vector3f;


public final class Minecraft12111PreviewRenderer
        implements PreviewRenderer<GuiGraphics>, AutoCloseable {
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(-1);
    private static final float MODEL_HEIGHT = 2.125F;

    private final EditorPreviewSession session = new EditorPreviewSession();
    private final DiagnosticSink diagnostics;
    private final EditorPreviewClock previewClock = new EditorPreviewClock();
    private final Minecraft12111SimplePreviewRenderer baked =
            new Minecraft12111SimplePreviewRenderer();
    private final GameProfile profile = new GameProfile(UUID.randomUUID(), "NCLSkinPreview");
    private boolean layerFailureLogged;
    private PreviewPlayer previewPlayer;
    private ClientLevel previewLevel;

    public Minecraft12111PreviewRenderer(DiagnosticSink diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Override
    public void render(GuiGraphics graphics, PreviewRequest request) {
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(request, "request");
        Minecraft minecraft = Minecraft.getInstance();
        if (session.path(request.intent(), minecraft.level != null && minecraft.player != null)
                        == EditorPreviewSession.Path.BAKED
                || !NativePlayerSkinLifecycle.isReady(request.appearance().skin().location())) {
            baked.render(graphics, request);
            return;
        }
        try {
            PreviewAppearance appearance = request.appearance();
            PlayerSkin skin = playerSkin(appearance);
            ItemStack chestEquipment = appearance.capeMode() == CapeMode.ELYTRA
                    ? new ItemStack(Items.ELYTRA)
                    : ItemStack.EMPTY;
            Minecraft12111PreviewContext context = new Minecraft12111PreviewContext(
                    minecraft.player, appearance, skin, chestEquipment);
            PreviewPlayer renderPlayer = previewPlayer(
                    minecraft, appearance, skin, chestEquipment);
            if (renderPlayer == null) {
                baked.render(graphics, request);
                return;
            }
            float previewAge = previewClock.ageTicks(renderPlayer.tickCount);
            float pitch = (float) Math.toRadians(request.pitchDegrees());
            Quaternionf cameraPitch = new Quaternionf().rotateX(pitch);
            Quaternionf modelRotation = new Quaternionf().rotateZ((float) Math.PI).mul(cameraPitch);
            var centered = CenteredPlayerPreviewGeometry.centeredEntityTranslation(
                    CenteredPlayerPreviewGeometry.STANDING_PLAYER_HEIGHT, pitch);
            float scale = 0.97F * request.height() / MODEL_HEIGHT * request.scale();
            Vector3f translation = new Vector3f(0.0F, centered.y(), centered.z());
            Minecraft12111LivePreviewRenderState state =
                    new Minecraft12111LivePreviewRenderState(
                            renderPlayer,
                            context,
                            request,
                            previewAge,
                            translation,
                            modelRotation,
                            cameraPitch,
                            scale,
                            this::onLiveRenderFailure,
                            this::onLiveLayerFailure,
                            null);
            try (Minecraft12111LivePreviewSubmission submission =
                    Minecraft12111LivePreviewSubmission.open(graphics, state)) {
                graphics.submitEntityRenderState(
                        new AvatarRenderState(),
                        scale,
                        translation,
                        modelRotation,
                        cameraPitch,
                        request.left(),
                        request.top(),
                        request.left() + request.width(),
                        request.top() + request.height());
                submission.requireConsumed();
            }
        } catch (RuntimeException failure) {
            onLiveRenderFailure(failure);
            baked.render(graphics, request);
        }
    }

    private PreviewPlayer previewPlayer(
            Minecraft minecraft,
            PreviewAppearance appearance,
            PlayerSkin skin,
            ItemStack chestEquipment) {
        if (minecraft.level == null || minecraft.player == null) {
            previewPlayer = null;
            previewLevel = null;
            return null;
        }
        if (previewPlayer == null || previewLevel != minecraft.level) {
            previewPlayer = new PreviewPlayer(minecraft.level, profile);
            int id = NEXT_ENTITY_ID.getAndDecrement();
            previewPlayer.setId(id == 0 ? NEXT_ENTITY_ID.getAndDecrement() : id);
            previewLevel = minecraft.level;
        }
        previewPlayer.skin = skin;
        previewPlayer.setModelParts(modelParts(appearance));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            previewPlayer.setItemSlot(slot, ItemStack.EMPTY);
        }
        previewPlayer.setItemSlot(EquipmentSlot.CHEST, chestEquipment.copy());
        previewPlayer.setPos(minecraft.player.position());
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
        return previewPlayer;
    }

    private void onLiveRenderFailure(RuntimeException failure) {
        if (session.disableLive(failure)) {
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

    static void configure(AvatarRenderState state, PreviewRequest request) {
        PreviewAppearance appearance = request.appearance();
        state.skin = playerSkin(appearance);
        state.bodyRot = 180.0F - request.yawDegrees();
        state.yRot = 0.0F;
        state.xRot = 0.0F;
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
        state.swimAmount = 0.0F;
        state.isCrouching = false;
        state.isFallFlying = false;
        state.isVisuallySwimming = false;
        state.isPassenger = false;
        state.isUsingItem = false;
        state.elytraRotX = CenteredPipPreviewTransform.ELYTRA_ROT_X;
        state.elytraRotY = CenteredPipPreviewTransform.ELYTRA_ROT_Y;
        state.elytraRotZ = CenteredPipPreviewTransform.ELYTRA_ROT_Z;
        state.showHat = appearance.outerLayerVisibility().visible(OuterLayerPart.HEAD);
        state.showJacket = appearance.outerLayerVisibility().visible(OuterLayerPart.BODY);
        state.showLeftPants = appearance.outerLayerVisibility().visible(OuterLayerPart.LEFT_LEG);
        state.showRightPants = appearance.outerLayerVisibility().visible(OuterLayerPart.RIGHT_LEG);
        state.showLeftSleeve = appearance.outerLayerVisibility().visible(OuterLayerPart.LEFT_ARM);
        state.showRightSleeve = appearance.outerLayerVisibility().visible(OuterLayerPart.RIGHT_ARM);
        state.showCape = appearance.capeMode() == CapeMode.CAPE;
        state.headEquipment = ItemStack.EMPTY;
        state.chestEquipment = appearance.capeMode() == CapeMode.ELYTRA
                ? new ItemStack(Items.ELYTRA)
                : ItemStack.EMPTY;
        state.legsEquipment = ItemStack.EMPTY;
        state.feetEquipment = ItemStack.EMPTY;
        state.headItem.clear();
    }

    private static byte modelParts(PreviewAppearance appearance) {
        int mask = 0;
        for (PlayerModelPart part : PlayerModelPart.values()) {
            boolean visible = switch (part) {
                case CAPE -> appearance.capeMode() == CapeMode.CAPE;
                case HAT -> appearance.outerLayerVisibility().visible(OuterLayerPart.HEAD);
                case JACKET -> appearance.outerLayerVisibility().visible(OuterLayerPart.BODY);
                case LEFT_SLEEVE -> appearance.outerLayerVisibility().visible(OuterLayerPart.LEFT_ARM);
                case RIGHT_SLEEVE -> appearance.outerLayerVisibility().visible(OuterLayerPart.RIGHT_ARM);
                case LEFT_PANTS_LEG -> appearance.outerLayerVisibility().visible(OuterLayerPart.LEFT_LEG);
                case RIGHT_PANTS_LEG -> appearance.outerLayerVisibility().visible(OuterLayerPart.RIGHT_LEG);
            };
            if (visible) {
                mask |= part.getMask();
            }
        }
        return (byte) mask;
    }

    private static PlayerSkin playerSkin(PreviewAppearance appearance) {
        ClientAsset.Texture body = texture(appearance.skin());
        ClientAsset.Texture selectedCape = appearance.cape()
                .map(Minecraft12111PreviewRenderer::texture).orElse(null);
        return PlayerSkin.insecure(
                body,
                appearance.capeMode() == CapeMode.CAPE ? selectedCape : null,
                appearance.capeMode() == CapeMode.ELYTRA ? selectedCape : null,
                appearance.model() == SkinModel.SLIM ? PlayerModelType.SLIM : PlayerModelType.WIDE);
    }

    private static ClientAsset.Texture texture(TextureHandle handle) {
        Identifier id = Identifier.parse(handle.location());
        return new ClientAsset.ResourceTexture(id, id);
    }

    @Override
    public void close() {
        baked.close();
        previewPlayer = null;
        previewLevel = null;
    }

    static final class PreviewPlayer extends RemotePlayer {
        private PlayerSkin skin;

        private PreviewPlayer(ClientLevel level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public PlayerSkin getSkin() {
            return Objects.requireNonNull(skin, "skin");
        }

        private void setModelParts(byte value) {
            getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, value);
        }
    }
}

package com.naocraftlab.skins.compat.mc262;

import com.mojang.authlib.GameProfile;
import com.naocraftlab.skins.client.EditorPreviewSession;
import com.naocraftlab.skins.client.EditorPreviewClock;
import com.naocraftlab.skins.client.CenteredPlayerPreviewGeometry;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
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


public final class Minecraft262PreviewRenderer
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
    private final EditorPreviewSession session = new EditorPreviewSession();
    private final DiagnosticSink diagnostics;
    private final EditorPreviewClock previewClock = new EditorPreviewClock();
    private boolean layerFailureLogged;
    private final GameProfile previewProfile =
            new GameProfile(UUID.randomUUID(), "NCLSkinPreview");
    private final Minecraft262SimplePreviewRenderer bakedRenderer =
            new Minecraft262SimplePreviewRenderer();
    private PreviewPlayer previewPlayer;
    private ClientLevel previewLevel;

    public Minecraft262PreviewRenderer(DiagnosticSink diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, PreviewRequest request) {
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(request, "request");
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (session.path(request.intent(), minecraft.level != null && player != null)
                        == EditorPreviewSession.Path.BAKED
                || !NativePlayerSkinLifecycle.isReady(request.appearance().skin().location())) {
            bakedRenderer.render(graphics, request);
            return;
        }

        try {
            PreviewAppearance appearance = request.appearance();
            PlayerSkin skin = playerSkin(appearance);
            ItemStack chestEquipment = appearance.capeMode() == CapeMode.ELYTRA
                    ? previewElytraStack()
                    : ItemStack.EMPTY;
            Minecraft262PreviewContext context = new Minecraft262PreviewContext(
                    player, appearance, skin, chestEquipment);
            PreviewPlayer renderPlayer = previewPlayer(minecraft, appearance, skin, chestEquipment);
            if (renderPlayer == null) {
                bakedRenderer.render(graphics, request);
                return;
            }
            float previewAge = previewClock.ageTicks(renderPlayer.tickCount);
            renderPlayer.tickCount = Math.max(0, (int) Math.floor(previewAge));
            AvatarRenderState state = createRenderState(minecraft, renderPlayer, context);
            state.ageInTicks = previewAge;
            configurePreviewState(state, appearance, skin, request);
            NclSkinsWideDepthState previewState = (NclSkinsWideDepthState) state;
            previewState.nclskins$setWideDepth(true);
            previewState.nclskins$setFailureSink(this::onLiveRenderFailure);
            previewState.nclskins$setLayerFailureSink(this::onLiveLayerFailure);
            previewState.nclskins$setPreviewContext(context);

            float pitchRadians = Minecraft262LivePitch.radians(request.pitchDegrees());
            float requestedScale = FIT_PADDING * request.height() / MODEL_HEIGHT * request.scale();
            Quaternionf cameraPitch = new Quaternionf().rotateX(pitchRadians);
            Quaternionf modelRotation = new Quaternionf()
                    .rotateZ((float) Math.PI)
                    .mul(cameraPitch);
            CenteredPlayerPreviewGeometry.EntityTranslation centeredTranslation =
                    CenteredPlayerPreviewGeometry.centeredEntityTranslation(
                            CenteredPlayerPreviewGeometry.STANDING_PLAYER_HEIGHT,
                            pitchRadians);
            Vector3f translation = new Vector3f(
                    0.0F,
                    centeredTranslation.y(),
                    centeredTranslation.z());

            graphics.entity(
                    state,
                    requestedScale,
                    translation,
                    modelRotation,
                    cameraPitch,
                    request.left(),
                    request.top(),
                    request.left() + request.width(),
                    request.top() + request.height());
        } catch (RuntimeException failure) {
            onLiveRenderFailure(failure);
        }
    }

    private AvatarRenderState createRenderState(
            Minecraft minecraft,
            AbstractClientPlayer player,
            Minecraft262PreviewContext context) {
        AvatarRenderState selector = rendererSelectorState();
        selector.skin = player.getSkin();
        EntityRenderer<?, ? super AvatarRenderState> genericRenderer =
                minecraft.getEntityRenderDispatcher().getRenderer(selector);
        if (!(genericRenderer instanceof AvatarRenderer<?> avatarRenderer)) {
            throw new IllegalStateException("Selected local player renderer is not an avatar renderer");
        }

        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        try (Minecraft262PreviewScope ignored = context.open(minecraft)) {
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

    @SuppressWarnings("unchecked")
    private static AvatarRenderState extractFromSelectedRenderer(
            AvatarRenderer<?> renderer,
            AbstractClientPlayer player,
            float partialTick) {
        return ((AvatarRenderer<AbstractClientPlayer>) renderer).createRenderState(player, partialTick);
    }

    private static AvatarRenderState rendererSelectorState() {
        AvatarRenderState state = new AvatarRenderState();
        state.entityType = Minecraft26Api.mannequin();
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
        state.attackTime = 0.0F;
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
        ClientAsset.Texture selectedCape = appearance.cape().map(Minecraft262PreviewRenderer::texture).orElse(null);
        ClientAsset.Texture cape = appearance.capeMode() == CapeMode.CAPE
                ? selectedCape
                : null;
        ClientAsset.Texture elytra = appearance.capeMode() == CapeMode.ELYTRA ? selectedCape : null;
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
        bakedRenderer.close();
        previewPlayer = null;
        previewLevel = null;
    }

}

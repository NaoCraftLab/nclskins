package com.naocraftlab.skins.compat.mc262;

import com.mojang.authlib.GameProfile;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
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


public final class Minecraft262PreviewRenderer implements PreviewRenderer<GuiGraphicsExtractor> {
    private static final AtomicInteger NEXT_PREVIEW_ENTITY_ID = new AtomicInteger(-1);
    private static final float MODEL_HEIGHT = 2.125F;
    private static final float FIT_PADDING = 0.97F;
    private static final float ENTITY_Y_OFFSET = 0.0625F;
    private static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180.0);
    private static final Holder<Item> PREVIEW_ELYTRA_HOLDER = Holder.direct(
            Items.ELYTRA,
            DataComponentMap.builder()
                    .set(
                            DataComponents.EQUIPPABLE,
                            Equippable.builder(EquipmentSlot.CHEST)
                                    .setAsset(EquipmentAssets.ELYTRA)
                                    .build())
                    .build());
    private PreviewPlayer previewPlayer;
    private ClientLevel previewLevel;
    private Identifier previewBodyTexture;
    private CompletableFuture<Optional<PlayerSkin>> previewProfileSkin;

    @Override
    public void render(GuiGraphicsExtractor graphics, PreviewRequest request) {
        PreviewAppearance appearance = request.appearance();
        PlayerSkin entitySkin = playerSkin(appearance, true);
        PreviewState preview = createRenderState(entitySkin);
        AvatarRenderState state = preview.state();
        PlayerSkin renderedSkin = preview.entityBacked()
                ? entitySkin
                : playerSkin(appearance, false);
        configurePreviewState(state, appearance, renderedSkin, request, preview.entityBacked());
        NclSkinsWideDepthState previewState = (NclSkinsWideDepthState) state;
        previewState.nclskins$setWideDepth(true);
        previewState.nclskins$setWorldlessCapeTexture(
                !preview.entityBacked() && appearance.capeMode() == CapeMode.CAPE
                        ? appearance.cape()
                                .map(TextureHandle::location)
                                .map(Identifier::parse)
                                .orElse(null)
                        : null);

        float pitchRadians = request.pitchDegrees() * DEGREES_TO_RADIANS;
        float requestedScale = FIT_PADDING * request.height() / MODEL_HEIGHT * request.scale();
        Quaternionf cameraPitch = new Quaternionf().rotateX(pitchRadians);
        Quaternionf modelRotation = new Quaternionf()
                .rotateZ((float) Math.PI)
                .mul(cameraPitch);
        Vector3f translation = new Vector3f(
                0.0F,
                state.boundingBoxHeight / 2.0F + ENTITY_Y_OFFSET,
                0.0F);

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
    }

    private PreviewState createRenderState(PlayerSkin selectedSkin) {
        Minecraft minecraft = Minecraft.getInstance();
        PreviewPlayer player = previewPlayer(minecraft, selectedSkin);
        if (player == null) {
            return new PreviewState(rendererSelectorState(), false);
        }


        AvatarRenderState selector = rendererSelectorState();
        selector.skin = selectedSkin;
        EntityRenderer<?, ? super AvatarRenderState> genericRenderer =
                minecraft.getEntityRenderDispatcher().getRenderer(selector);
        if (!(genericRenderer instanceof AvatarRenderer<?> avatarRenderer)) {
            return new PreviewState(rendererSelectorState(), false);
        }


        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        return new PreviewState(
                extractFromSelectedRenderer(avatarRenderer, player, partialTick),
                true);
    }

    private PreviewPlayer previewPlayer(Minecraft minecraft, PlayerSkin selectedSkin) {
        if (minecraft.player == null || minecraft.level == null) {
            clearPreviewPlayer();
            return null;
        }

        Identifier bodyTexture = selectedSkin.body().texturePath();
        if (previewPlayer == null
                || previewLevel != minecraft.level
                || !bodyTexture.equals(previewBodyTexture)) {
            UUID previewId = UUID.nameUUIDFromBytes(
                    ("nclskins:preview:" + bodyTexture).getBytes(StandardCharsets.UTF_8));


            GameProfile profile = minecraft.player.getGameProfile();
            previewPlayer = new PreviewPlayer(minecraft.level, profile, selectedSkin);
            previewPlayer.setUUID(previewId);


            previewPlayer.setId(nextPreviewEntityId());
            previewLevel = minecraft.level;
            previewBodyTexture = bodyTexture;
            previewProfileSkin = minecraft.getSkinManager().get(profile);
        } else {
            previewPlayer.setPreviewSkin(selectedSkin);
        }


        if (previewProfileSkin == null
                || !previewProfileSkin.isDone()
                || previewProfileSkin.isCompletedExceptionally()
                || previewProfileSkin.getNow(Optional.empty()).isEmpty()) {
            return null;
        }

        previewPlayer.tickCount = (int) (Util.getMillis() / 50L);
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
        previewPlayer.avatarState().tick(previewPlayer.position(), Vec3.ZERO);
        return previewPlayer;
    }

    private void clearPreviewPlayer() {
        previewPlayer = null;
        previewLevel = null;
        previewBodyTexture = null;
        previewProfileSkin = null;
    }

    private static int nextPreviewEntityId() {
        int candidate = NEXT_PREVIEW_ENTITY_ID.getAndDecrement();
        if (candidate == 0) {
            candidate = NEXT_PREVIEW_ENTITY_ID.getAndDecrement();
        }
        return candidate;
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

    private static void configurePreviewState(
            AvatarRenderState state,
            PreviewAppearance appearance,
            PlayerSkin skin,
            PreviewRequest request,
            boolean entityBacked) {
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
        state.showCape = entityBacked && appearance.capeMode() == CapeMode.CAPE;
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

    private static PlayerSkin playerSkin(PreviewAppearance appearance, boolean includeCape) {
        ClientAsset.Texture body = texture(appearance.skin());
        ClientAsset.Texture selectedCape = appearance.cape().map(Minecraft262PreviewRenderer::texture).orElse(null);
        ClientAsset.Texture cape = includeCape && appearance.capeMode() == CapeMode.CAPE
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


    private record PreviewState(AvatarRenderState state, boolean entityBacked) {
    }


    private static final class PreviewPlayer extends RemotePlayer {
        private PlayerSkin previewSkin;

        private PreviewPlayer(ClientLevel level, GameProfile profile, PlayerSkin previewSkin) {
            super(level, profile);
            this.previewSkin = previewSkin;
            int modelParts = 0;
            for (PlayerModelPart part : PlayerModelPart.values()) {
                modelParts |= part.getMask();
            }
            getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) modelParts);
        }

        private void setPreviewSkin(PlayerSkin previewSkin) {
            this.previewSkin = previewSkin;
        }

        @Override
        public PlayerSkin getSkin() {
            return previewSkin;
        }
    }

}

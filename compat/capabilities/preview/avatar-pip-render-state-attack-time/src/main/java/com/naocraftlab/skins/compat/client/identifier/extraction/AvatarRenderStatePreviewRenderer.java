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
    private final EditorPreviewSession session = new EditorPreviewSession();
    private java.util.function.Consumer<PreviewAccessories> accessoryObserver = value -> {};
    private boolean closed;

    @Override
    public void observeAccessories(java.util.function.Consumer<PreviewAccessories> observer) {
        accessoryObserver = Objects.requireNonNull(observer);
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

    private void clearAccessoryInspection() {
        accessoryObserver.accept(PreviewAccessories.unavailable(LiveReason.LIVE_UNAVAILABLE));
    }

    private final DiagnosticSink diagnostics;
    private final EditorPreviewClock previewClock = new EditorPreviewClock();
    private final EditorPreviewTickGate previewTickGate = new EditorPreviewTickGate();
    private boolean layerFailureLogged;
    private final GameProfile previewProfile =
            new GameProfile(UUID.randomUUID(), "NCLSkinPreview");
    private final AvatarPipPreviewRenderer bakedRenderer =
            new AvatarPipPreviewRenderer();
    private PreviewPlayer previewPlayer;
    private ClientLevel previewLevel;

    public AvatarRenderStatePreviewRenderer(DiagnosticSink diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Override public void observeBody(java.util.function.Consumer<BodyStatus> observer) { session.observeBody(observer); }

    @Override public com.naocraftlab.skins.client.PreviewModelFrames.Body body() { return session.body(); }

    @Override public void prepareBody(String source, boolean cpm) {
        var client = Minecraft.getInstance();
        session.beginBody(source, cpm, !closed && client.level != null && client.player != null);
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, PreviewRequest request) {
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(request, "request");
        if (closed) { session.invalidateDisplayedBody(); return; }
        var authorityClient = Minecraft.getInstance();
        session.displayAuthority(java.util.Arrays.asList(request.appearance().skin(), request.appearance().model(),
                request.intent(), NativePlayerSkinLifecycle.registration(request.appearance().skin().location())),
                authorityClient.level, authorityClient.player);
        if (session.waitsForBody() && session.bodyStatus() != BodyStatus.READY) {
            if (session.bodyStatus() != BodyStatus.PENDING) { renderFallback(graphics, request); return; }
            if (!NativePlayerSkinLifecycle.isReady(request.appearance().skin().location())) { session.invalidateDisplayedBody(); return; }
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (session.path(request.intent(), minecraft.level != null && player != null)
                        == EditorPreviewSession.Path.BAKED
                || !NativePlayerSkinLifecycle.isReady(request.appearance().skin().location())) {
            accessoryObserver.accept(accessories(request));
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
                accessoryObserver.accept(accessories(request));
                renderFallback(graphics, request);
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
            AvatarPreviewContext context = new AvatarPreviewContext(minecraft.player, appearance, skin, chestEquipment,
                    () -> !closed && minecraft.level == frameWorld && minecraft.player == framePlayer
                            && previewPlayer == renderPlayer && previewLevel == frameWorld
                            && NativePlayerSkinLifecycle.isReady(appearance.skin().location())
                            && NativePlayerSkinLifecycle.registration(appearance.skin().location()).equals(frameTexture)
                            && frameCurrent.getAsBoolean(), session.displayBody(() -> true));
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
            float requestedScale = FIT_PADDING * request.height() / MODEL_HEIGHT * request.scale();
            previewState.nclskins$setDepthExtent(PreviewDepthEnvelope.forRequest(
                    request.intent(), requestedScale, request.pitchDegrees(), appearance.capeMode()));
            previewState.nclskins$setFailureSink(failure -> { if (context.current()) onLiveRenderFailure(failure); });
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
            onLiveRenderFailure(failure);
        }
    }

    private void renderFallback(GuiGraphicsExtractor graphics, PreviewRequest request) {
        var client = Minecraft.getInstance();
        var world = client.level;
        var local = client.player;
        var texture = NativePlayerSkinLifecycle.registration(request.appearance().skin().location());
        bakedRenderer.render(graphics, request, session.ordinaryDisplay(() -> !closed && client.level == world && client.player == local
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

    private void onLiveRenderFailure(RuntimeException failure) {
        boolean firstFailure = session.disableLive(failure);
        clearAccessoryInspection();
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
        session.invalidateDisplayedBody();
        closed = true;
        clearAccessoryInspection();
        accessoryObserver = value -> {};
        CapeProjection.retireSkin(previewProfile.id());
        bakedRenderer.close();
        previewPlayer = null;
        previewLevel = null;
    }

}

package com.naocraftlab.skins.compat.client.identifier.submission;

import com.naocraftlab.skins.runtime.CapeProjection;
import com.naocraftlab.skins.client.PreviewAccessories;

import com.mojang.authlib.GameProfile;
import com.naocraftlab.skins.client.CenteredPipPreviewTransform;
import com.naocraftlab.skins.client.CenteredPlayerPreviewGeometry;
import com.naocraftlab.skins.client.EditorPreviewSession;
import com.naocraftlab.skins.client.EditorPreviewTickGate;
import com.naocraftlab.skins.client.EditorPreviewClock;
import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.PreviewStageGeometry;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.diagnostics.DiagnosticDetails;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import com.naocraftlab.skins.compat.client.identifier.CapeMaterialSnapshot;
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
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;


public final class SubmissionPreviewRenderer
        implements PreviewRenderer<GuiGraphics>, AutoCloseable {
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(-1);
    private static final float MODEL_HEIGHT = 2.125F;

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
        return CapeProjection.previewAccessories(profile.id(), request.appearance().skin().location(),
                request.appearance().model(), reason);
    }

    private void clearAccessoryInspection() {
        accessoryObserver.accept(PreviewAccessories.unavailable(LiveReason.LIVE_UNAVAILABLE));
    }

    private final DiagnosticSink diagnostics;
    private final EditorPreviewClock previewClock = new EditorPreviewClock();
    private final EditorPreviewTickGate previewTickGate = new EditorPreviewTickGate();
    private final SimplePreviewRenderer baked =
            new SimplePreviewRenderer();
    private final GameProfile profile = new GameProfile(UUID.randomUUID(), "NCLSkinPreview");
    private boolean layerFailureLogged;
    private PreviewPlayer previewPlayer;
    private ClientLevel previewLevel;

    public SubmissionPreviewRenderer(DiagnosticSink diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Override public com.naocraftlab.skins.client.PreviewModelFrames.Body body() { return session.body(); }

    @Override public void observeBody(java.util.function.Consumer<BodyStatus> observer) { session.observeBody(observer); }

    @Override public void prepareBody(String source, boolean cpm) {
        var client = Minecraft.getInstance();
        session.beginBody(source, cpm, !closed && client.level != null && client.player != null);
    }

    @Override
    public void render(GuiGraphics graphics, PreviewRequest request) {
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
        if (session.path(request.intent(), minecraft.level != null && minecraft.player != null)
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
                    ? new ItemStack(Items.ELYTRA)
                    : ItemStack.EMPTY;
            PreviewPlayer renderPlayer = previewPlayer(
                    minecraft, appearance, skin, chestEquipment);
            if (renderPlayer == null) {
                accessoryObserver.accept(accessories(request));
                renderFallback(graphics, request);
                return;
            }
            if (session.waitsForBody()) {
                session.body(com.naocraftlab.skins.client.PreviewModelFrames.prepare(profile, request.appearance()));
                if (session.bodyStatus() == BodyStatus.PENDING) return;
                if (session.bodyStatus() != BodyStatus.READY) { renderFallback(graphics, request); return; }
            }
            var frameCurrent = session.frame();
            var frameWorld = minecraft.level;
            var frameTexture = NativePlayerSkinLifecycle.registration(appearance.skin().location());
            var framePlayer = minecraft.player;
            PreviewContext context = new PreviewContext(minecraft.player, appearance, skin, chestEquipment,
                    () -> !closed && minecraft.level == frameWorld && minecraft.player == framePlayer && previewPlayer == renderPlayer && previewLevel == frameWorld
                            && NativePlayerSkinLifecycle.isReady(appearance.skin().location())
                            && NativePlayerSkinLifecycle.registration(appearance.skin().location()).equals(frameTexture)
                            && frameCurrent.getAsBoolean(),
                    session.displayBody(() -> true));
            float previewAge = previewClock.ageTicks(renderPlayer.tickCount);
            applySettling(renderPlayer, previewAge, session.capeSettling(
                    request.intent(), true, appearance.capeMode(),
                    appearance.cape().isPresent(), previewAge));
            float pitch = (float) Math.toRadians(request.pitchDegrees());
            Quaternionf cameraPitch = new Quaternionf().rotateX(pitch);
            Quaternionf modelRotation = new Quaternionf().rotateZ((float) Math.PI).mul(cameraPitch);
            var centered = CenteredPlayerPreviewGeometry.centeredEntityTranslation(
                    CenteredPlayerPreviewGeometry.STANDING_PLAYER_HEIGHT, pitch);
            float scale = 0.97F * request.height() / MODEL_HEIGHT * request.scale();
            Vector3f translation = new Vector3f(
                    PreviewStageGeometry.modelOffsetX(request, scale),
                    centered.y() + PreviewStageGeometry.modelOffsetY(request, scale),
                    centered.z());
            LivePreviewRenderState state =
                    new LivePreviewRenderState(
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
            try (LivePreviewSubmission submission =
                    LivePreviewSubmission.open(graphics, state)) {
                graphics.submitEntityRenderState(
                        new AvatarRenderState(),
                        scale,
                        translation,
                        modelRotation,
                        cameraPitch,
                        request.stageLeft(),
                        request.stageTop(),
                        request.stageLeft() + request.stageWidth(),
                        request.stageTop() + request.stageHeight());
                submission.requireConsumed();
            }
        } catch (RuntimeException failure) {
            onLiveRenderFailure(failure);
            accessoryObserver.accept(accessories(request));
            renderFallback(graphics, request);
        }
    }

    private void renderFallback(GuiGraphics graphics, PreviewRequest request) {
        var client = Minecraft.getInstance();
        var world = client.level;
        var local = client.player;
        var texture = NativePlayerSkinLifecycle.registration(request.appearance().skin().location());
        baked.render(graphics, request, session.ordinaryDisplay(() -> !closed && client.level == world && client.player == local
                && NativePlayerSkinLifecycle.isReady(request.appearance().skin().location())
                && NativePlayerSkinLifecycle.registration(request.appearance().skin().location()).equals(texture)));
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
        CapeProjection.effectiveSkin(profile.id(), minecraft.player.getUUID(),
                appearance.skin().location(), appearance.model(), true);
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

    static void configure(AvatarRenderState state, PreviewRequest request) {
        PreviewAppearance appearance = request.appearance();
        state.skin = playerSkin(appearance);
        ((CapeMaterialSnapshot.Carrier) state).nclskins$setCapeMaterial(
                CapeMaterialSnapshot.preview(
                        state.skin.cape() == null ? null : state.skin.cape().texturePath().toString(),
                        appearance.cape().map(TextureHandle::location).orElse(null),
                        appearance.capeMode() == CapeMode.CAPE, appearance.capeGlint(), appearance.capeValidity()));
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
                .map(SubmissionPreviewRenderer::texture).orElse(null);
        return PlayerSkin.insecure(
                body,
                appearance.capeMode() == CapeMode.CAPE ? selectedCape : null,
                appearance.capeMode() == CapeMode.ELYTRA && appearance.capeHasElytra() ? selectedCape : null,
                appearance.model() == SkinModel.SLIM ? PlayerModelType.SLIM : PlayerModelType.WIDE);
    }

    private static ClientAsset.Texture texture(TextureHandle handle) {
        Identifier id = Identifier.parse(handle.location());
        return new ClientAsset.ResourceTexture(id, id);
    }

    @Override
    public void close() {
        session.invalidateDisplayedBody();
        closed = true;
        clearAccessoryInspection();
        accessoryObserver = value -> {};
        CapeProjection.retireSkin(profile.id());
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

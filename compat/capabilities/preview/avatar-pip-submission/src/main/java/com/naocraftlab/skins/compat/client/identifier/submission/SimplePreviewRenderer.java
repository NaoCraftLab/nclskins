package com.naocraftlab.skins.compat.client.identifier.submission;

import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.PreviewStageGeometry;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.VanillaBackEquipmentTransform;
import java.util.Optional;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.object.equipment.ElytraModel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.resources.Identifier;


public final class SimplePreviewRenderer
        implements PreviewRenderer<GuiGraphics>, BackEquipmentPreviewRenderer<GuiGraphics>, AutoCloseable {
    private static final float MODEL_HEIGHT = 2.125F;
    private static final float FIT_PADDING = 0.97F;
    private final PlayerModel wide = model(false);
    private final PlayerModel slim = model(true);
    private final AttachmentModels cape = capeModels();
    private final AttachmentModels elytra = elytraModels();
    private BakedPreviewTarget target =
            new BakedPreviewTarget();

    private Object imageSource;
    @Override public void prepareImage(Object source, long startedNanos, boolean inputsReady) {
        prepareImage(source, startedNanos);
        imageSession.imagePrerequisite(inputsReady, startedNanos);
    }

    @Override public void prepareImage(Object source, long startedNanos) {
        java.util.Objects.requireNonNull(source);
        if (!java.util.Objects.equals(imageSource, source) && displayedImageSession != null) {
            if (imageSession != displayedImageSession) { imageSession.invalidateDisplayedBody(); target.close(); }
            imageSession = imageSession.nextImageSession();
            target = new BakedPreviewTarget();
        }
        imageSource = java.util.Objects.requireNonNull(source);
        imageSession.beginImage(source, startedNanos);
    }

    private com.naocraftlab.skins.client.EditorPreviewSession imageSession =
            new com.naocraftlab.skins.client.EditorPreviewSession();

    private static Object appearanceIdentity(PreviewAppearance appearance) {
        Object capeOwner = appearance.capeAssetOwner() == null
                ? appearance.cape().orElse(null) : appearance.capeAssetOwner();
        return new MenuAppearanceIdentity(appearance.skin(), appearance.model(),
                appearance.cape().isPresent(), capeOwner, appearance.capeMode(),
                appearance.outerLayerVisibility(), appearance.capeHasElytra(),
                appearance.capeGlint(), appearance.nativeAccessories());
    }

    private record MenuAppearanceIdentity(
            com.naocraftlab.skins.client.TextureRegistry.TextureHandle skin,
            SkinModel model, boolean capePresent, Object capeIdentity, CapeMode capeMode,
            OuterLayerVisibility outerLayerVisibility, boolean capeHasElytra,
            boolean capeGlint, boolean nativeAccessories) {}

    private static com.naocraftlab.skins.client.PreviewModelFrames.Completion inputBound(
            com.naocraftlab.skins.client.PreviewModelFrames.Completion completion,
            PreviewAppearance appearance) {
        var owner = appearance.capeAssetOwner();
        if (owner == null) return completion;
        return new com.naocraftlab.skins.client.PreviewModelFrames.Completion() {
            public boolean requiresConsumption() { return completion.requiresConsumption(); }
            public boolean needsOwnInputPreparation() { return true; }
            public boolean current() {
                return completion.current() && appearance.cape()
                        .map(handle -> owner.ownsFrame(handle.location())).orElse(false);
            }
            public boolean currentInput(String location) {
                return current() && owner.ownsFrame(location);
            }
            public void accept(com.naocraftlab.skins.client.PreviewModelFrames.Body body) {
                if (current()) completion.accept(body);
            }
        };
    }

    private Object implicitImageSource;
    private PreviewRequest displayedImage;
    private com.naocraftlab.skins.client.EditorPreviewSession displayedImageSession;
    private BakedPreviewTarget displayedImageTarget;
    private java.util.function.BooleanSupplier displayedImageLifetime;
    private boolean imageClosed;

    @Override
    public void render(GuiGraphics graphics, PreviewRequest request) {
        if (imageClosed) return;
        String skin = request.appearance().skin().location();
        var registration = com.naocraftlab.skins.client.NativePlayerSkinLifecycle.registration(skin);
        if (imageSource == null) {
            var identity = java.util.List.of(appearanceIdentity(request.appearance()), registration);
            if (!java.util.Objects.equals(implicitImageSource, identity)) {
                if (displayedImageSession != null) {
                    if (imageSession != displayedImageSession) { imageSession.invalidateDisplayedBody(); target.close(); }
                    imageSession = imageSession.nextImageSession();
                    target = new BakedPreviewTarget();
                }
                implicitImageSource = identity;
            }
            imageSession.beginImage(identity);
        }
        if (!imageSession.imageVisible() && !imageSession.imageSpinner() && imageSession.imagePreparing()
                && displayedImage != null && displayedImageSession.imageVisible() && displayedImageLifetime.getAsBoolean()) {
            var desiredTarget = target;
            try {
                target = displayedImageTarget;
                render(graphics, displayedImage, new com.naocraftlab.skins.client.PreviewModelFrames.Completion() {
                    public boolean current() { return displayedImageLifetime.getAsBoolean(); }
                    public boolean reusesConfirmedOutput() { return true; }
                    public void accept(com.naocraftlab.skins.client.PreviewModelFrames.Body body) {}
                });
            }
            finally { target = desiredTarget; }
        }
        if (!com.naocraftlab.skins.client.NativePlayerSkinLifecycle.isReady(skin)) {
            if (registration.filter(value -> value.state() != com.naocraftlab.skins.client.NativePlayerSkinLifecycle.State.PENDING).isPresent())
                imageSession.imagePreparation(() -> true).accept(new com.naocraftlab.skins.client.PreviewModelFrames.Body(BodyStatus.FAILED, () -> true));
            renderLoading(graphics, imageSession, request);
            return;
        }
        renderLoading(graphics, imageSession, request);
        if (imageSession.imageVisible()) {
            var session = imageSession;
            var frameTarget = target;
            var capeOwner = request.appearance().capeAssetOwner();
            var client = net.minecraft.client.Minecraft.getInstance();
            var world = client.level;
            var player = client.player;
            render(graphics, request, new com.naocraftlab.skins.client.PreviewModelFrames.Completion() {
                public boolean requiresConsumption() { return true; }
                public boolean needsOwnInputPreparation() { return capeOwner != null; }
                public boolean current() { return !imageClosed && imageSession == session && target == frameTarget
                        && (capeOwner == null || request.appearance().cape()
                                .map(handle -> capeOwner.ownsFrame(handle.location())).orElse(false))
                        && client.level == world && client.player == player
                        && com.naocraftlab.skins.client.NativePlayerSkinLifecycle.registration(skin).equals(registration); }
                public boolean currentInput(String location) {
                    return current() && (capeOwner == null || capeOwner.ownsFrame(location));
                }
                public void accept(com.naocraftlab.skins.client.PreviewModelFrames.Body body) {
                    if (!current() || body.status() != BodyStatus.ORDINARY || !body.current().getAsBoolean()) return;
                    var previous = displayedImageTarget;
                    var previousSession = displayedImageSession;
                    displayedImage = request;
                    displayedImageTarget = frameTarget;
                    displayedImageSession = session;
                    displayedImageLifetime = () -> !imageClosed && client.level == world && client.player == player
                            && body.current().getAsBoolean()
                            && com.naocraftlab.skins.client.NativePlayerSkinLifecycle.registration(skin).equals(registration);
                    if (previousSession != null && previousSession != session) previousSession.invalidateDisplayedBody();
                    if (previous != null && previous != frameTarget) previous.close();
                }
            });
        }
        else if (imageSession.imagePreparing()) render(graphics, request.hidden(), inputBound(
                imageSession.imagePreparation(
                        () -> com.naocraftlab.skins.client.NativePlayerSkinLifecycle.registration(skin).equals(registration)
                                && com.naocraftlab.skins.client.NativePlayerSkinLifecycle.isReady(skin)),
                request.appearance()));
    }

    static void renderLoading(GuiGraphics graphics, com.naocraftlab.skins.client.EditorPreviewSession session, PreviewRequest request) {
        if (!session.imageSpinner()) return;
        var frame = com.naocraftlab.skins.client.PreviewLoading.frame((long) session.imageFrame() * 100_000_000L,
                request.left(), request.top(), request.width(), request.height(), 32);
        graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, Identifier.parse(frame.resource()),
                frame.left(), frame.top(), (float) frame.u(), (float) frame.v(), frame.size(), frame.size(),
                frame.width(), frame.height(), frame.textureWidth(), frame.textureHeight(), -1);
    }

    void render(GuiGraphics graphics, PreviewRequest request,
            com.naocraftlab.skins.client.PreviewModelFrames.Completion completion) {
        completion = com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame.afterConsumption(completion);
        PlayerModel model = request.appearance().model() == SkinModel.SLIM ? slim : wide;
        Optional<com.naocraftlab.skins.client.TextureRegistry.TextureHandle> attachmentTexture =
                request.appearance().cape()
                        .filter(ignored -> request.appearance().capeMode() != CapeMode.OFF);
        AttachmentModels attachment = attachmentTexture.isEmpty()
                ? null
                : request.appearance().capeMode() == CapeMode.ELYTRA ? elytra : cape;
        float scale = FIT_PADDING * request.height() / MODEL_HEIGHT * request.scale();
        BakedPreviewRenderState state = new BakedPreviewRenderState(
                target,
                model,
                attachment == null ? null : attachment.renderModel(),
                attachment == null ? null : attachment.capePoseModel(),
                attachment == null ? null : attachment.elytraPoseModel(),
                Identifier.parse(request.appearance().skin().location()),
                attachmentTexture.map(value -> Identifier.parse(request.appearance().capeMode() == CapeMode.ELYTRA && !request.appearance().capeHasElytra()
                        ? "minecraft:textures/entity/equipment/wings/elytra.png" : value.location())).orElse(null),
                attachment == null ? CapeMode.OFF : request.appearance().capeMode(),
                request.appearance().outerLayerVisibility(),
                request.pitchDegrees(),
                request.yawDegrees(),
                request.stageLeft(),
                request.stageTop(),
                request.stageLeft() + request.stageWidth(),
                request.stageTop() + request.stageHeight(),
                PreviewStageGeometry.modelOffsetX(request, scale),
                PreviewStageGeometry.modelOffsetY(request, scale),
                scale,
                request.appearance().capeGlint() && request.appearance().capeMode() == CapeMode.CAPE,
                false,
                completion,
                null);
        submit(graphics, model, state.skinTexture(), scale, request, state);
    }

    @Override
    public void render(GuiGraphics graphics, BackEquipmentPreviewRenderer.Request request) {
        if (imageSource == null) imageSession.beginImage(java.util.List.of(request.texture(), request.mode(), request.capeHasElytra()));
        if (!imageSession.imageVisible() && !imageSession.imagePreparing()) return;
        if (imageSession.imageSpinner()) {
            var frame = com.naocraftlab.skins.client.PreviewLoading.frame((long) imageSession.imageFrame() * 100_000_000L,
                    request.left(), request.top(), request.width(), request.height(), 32);
            graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, Identifier.parse(frame.resource()),
                    frame.left(), frame.top(), (float) frame.u(), (float) frame.v(), frame.size(), frame.size(),
                    frame.width(), frame.height(), frame.textureWidth(), frame.textureHeight(), -1);
        }
        if (imageSession.imageVisible()) { renderEquipment(graphics, request, null); return; }
        var hidden = new BackEquipmentPreviewRenderer.Request(request.texture(), request.mode(), -request.width() - 1,
                request.top(), request.width(), request.height(), request.capeHasElytra());
        var completion = imageSession.imagePreparation(() -> true);
        renderEquipment(graphics, hidden, completion);
    }

    private void renderEquipment(GuiGraphics graphics, BackEquipmentPreviewRenderer.Request request,
            com.naocraftlab.skins.client.PreviewModelFrames.Completion completion) {
        completion = com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame.afterConsumption(completion);
        AttachmentModels attachment = request.mode() == BackEquipmentPreviewRenderer.Mode.CAPE
                ? cape
                : elytra;
        CapeMode mode = request.mode() == BackEquipmentPreviewRenderer.Mode.CAPE
                ? CapeMode.CAPE
                : CapeMode.ELYTRA;
        Identifier texture = Identifier.parse(request.mode() == BackEquipmentPreviewRenderer.Mode.ELYTRA && !request.capeHasElytra()
                        ? "minecraft:textures/entity/equipment/wings/elytra.png" : request.texture().location());
        float scale = VanillaBackEquipmentTransform.fitScale(
                request.width(), request.height());
        BakedPreviewRenderState state = new BakedPreviewRenderState(
                target,
                null,
                attachment.renderModel(),
                attachment.capePoseModel(),
                attachment.elytraPoseModel(),
                null,
                texture,
                mode,
                OuterLayerVisibility.allVisible(),
                0.0F,
                180.0F,
                request.left(),
                request.top(),
                request.left() + request.width(),
                request.top() + request.height(),
                0.0F,
                0.0F,
                scale,
                false,
                true,
                completion,
                null);
        submit(
                graphics,
                wide,
                texture,
                scale,
                request.left(),
                request.top(),
                request.left() + request.width(),
                request.top() + request.height(),
                state);
    }

    private static PlayerModel model(boolean slim) {
        return new PlayerModel(
                LayerDefinition.create(
                                PlayerModel.createMesh(CubeDeformation.NONE, slim), 64, 64)
                        .bakeRoot(),
                slim);
    }

    private static AttachmentModels capeModels() {
        PlayerCapeModel capeModel = new PlayerCapeModel(
                PlayerCapeModel.createCapeLayer().bakeRoot());
        return new AttachmentModels(capeModel, capeModel, null);
    }

    private static AttachmentModels elytraModels() {
        ElytraModel elytraModel = new ElytraModel(ElytraModel.createLayer().bakeRoot());
        return new AttachmentModels(elytraModel, null, elytraModel);
    }

    private static void submit(
            GuiGraphics graphics,
            PlayerModel vanillaModel,
            Identifier vanillaTexture,
            float scale,
            PreviewRequest request,
            BakedPreviewRenderState state) {
        submit(
                graphics,
                vanillaModel,
                vanillaTexture,
                scale,
                request.stageLeft(),
                request.stageTop(),
                request.stageLeft() + request.stageWidth(),
                request.stageTop() + request.stageHeight(),
                state);
    }

    private static void submit(
            GuiGraphics graphics,
            PlayerModel vanillaModel,
            Identifier vanillaTexture,
            float scale,
            int left,
            int top,
            int right,
            int bottom,
            BakedPreviewRenderState state) {
        try (BakedPreviewSubmission submission =
                BakedPreviewSubmission.open(graphics, state)) {
            graphics.submitSkinRenderState(
                    vanillaModel,
                    vanillaTexture,
                    scale,
                    0.0F,
                    0.0F,
                    0.0F,
                    left,
                    top,
                    right,
                    bottom);
            submission.requireConsumed();
        }
    }

    @Override
    public void close() {
        if (imageClosed) return;
        imageClosed = true;
        imageSession.invalidateDisplayedBody();
        if (displayedImageSession != null && displayedImageSession != imageSession) displayedImageSession.invalidateDisplayedBody();
        try { target.close(); }
        finally { if (displayedImageTarget != null && displayedImageTarget != target) displayedImageTarget.close(); }
        displayedImage = null;
    }

    private record AttachmentModels(
            Model<?> renderModel,
            PlayerCapeModel capePoseModel,
            ElytraModel elytraPoseModel) {}
}

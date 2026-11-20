package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewDepthEnvelope;
import com.naocraftlab.skins.client.PreviewStageGeometry;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.VanillaBackEquipmentTransform;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.resources.Identifier;


final class AvatarPipPreviewRenderer
        implements PreviewRenderer<GuiGraphicsExtractor>,
                BackEquipmentPreviewRenderer<GuiGraphicsExtractor>, AutoCloseable {
    private static final float MODEL_HEIGHT = 2.125F;
    private static final float FIT_PADDING = 0.97F;
    private static final BakedSkinSubmitter SKIN_SUBMITTER =
            new NativeBakedSkinSubmitter();
    private static final boolean PLAYER_MODEL_SKIN = SKIN_SUBMITTER.usesPlayerModel();

    private final VanillaPreviewModels models = VanillaPreviewModels.INSTANCE;
    private NclBakedPlayerTarget target = new NclBakedPlayerTarget();

    AvatarPipPreviewRenderer() {
    }

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
            target = new NclBakedPlayerTarget();
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
    private NclBakedPlayerTarget displayedImageTarget;
    private java.util.function.BooleanSupplier displayedImageLifetime;
    private boolean imageClosed;

    @Override
    public void render(GuiGraphicsExtractor graphics, PreviewRequest request) {
        if (imageClosed) return;
        String skin = request.appearance().skin().location();
        var registration = com.naocraftlab.skins.client.NativePlayerSkinLifecycle.registration(skin);
        if (imageSource == null) {
            var identity = java.util.List.of(appearanceIdentity(request.appearance()), registration);
            if (!java.util.Objects.equals(implicitImageSource, identity)) {
                if (displayedImageSession != null) {
                    if (imageSession != displayedImageSession) { imageSession.invalidateDisplayedBody(); target.close(); }
                    imageSession = imageSession.nextImageSession();
                    target = new NclBakedPlayerTarget();
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

    static void renderLoading(GuiGraphicsExtractor graphics, com.naocraftlab.skins.client.EditorPreviewSession session, PreviewRequest request) {
        if (!session.imageSpinner()) return;
        var frame = com.naocraftlab.skins.client.PreviewLoading.frame((long) session.imageFrame() * 100_000_000L,
                request.left(), request.top(), request.width(), request.height(), 32);
        GuiExtraction.blit(graphics, Identifier.parse(frame.resource()),
                frame.left(), frame.top(), (float) frame.u(), (float) frame.v(), frame.size(), frame.size(),
                frame.width(), frame.height(), frame.textureWidth(), frame.textureHeight(), -1);
    }

    void render(GuiGraphicsExtractor graphics, PreviewRequest request,
            com.naocraftlab.skins.client.PreviewModelFrames.Completion completion) {
        completion = com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame.afterConsumption(completion);
        PreviewAppearance appearance = request.appearance();
        Model.Simple simpleModel = appearance.model() == SkinModel.SLIM
                ? models.slimModel
                : models.wideModel;
        PlayerModel playerModel = appearance.model() == SkinModel.SLIM
                ? models.slimPlayerModel
                : models.widePlayerModel;
        float scale = FIT_PADDING * request.height() / MODEL_HEIGHT * request.scale();
        Identifier attachmentTexture = appearance.cape()
                .filter(ignored -> appearance.capeMode() != CapeMode.OFF)
                .map(cape -> Identifier.parse(appearance.capeMode() == CapeMode.ELYTRA && !appearance.capeHasElytra()
                        ? "minecraft:textures/entity/equipment/wings/elytra.png" : cape.location()))
                .orElse(null);
        VanillaPreviewModels.AttachmentModels attachment = attachmentTexture == null
                ? null
                : attachment(appearance.capeMode());
        NclBakedPlayerRenderState state = new NclBakedPlayerRenderState(
                target,
                PLAYER_MODEL_SKIN ? playerModel : null,
                PLAYER_MODEL_SKIN ? null : simpleModel,
                attachment == null || !PLAYER_MODEL_SKIN ? null : attachment.player(),
                attachment == null || PLAYER_MODEL_SKIN ? null : attachment.simple(),
                attachment == null
                        ? null
                        : PLAYER_MODEL_SKIN
                                ? attachment.playerCapePose()
                                : attachment.simpleCapePose(),
                attachment == null
                        ? null
                        : PLAYER_MODEL_SKIN
                                ? attachment.playerElytraPose()
                                : attachment.simpleElytraPose(),
                Identifier.parse(appearance.skin().location()),
                attachmentTexture,
                appearance.capeMode(),
                appearance.outerLayerVisibility(),
                request.pitchDegrees(),
                request.yawDegrees(),
                request.stageLeft(),
                request.stageTop(),
                request.stageLeft() + request.stageWidth(),
                request.stageTop() + request.stageHeight(),
                PreviewStageGeometry.modelOffsetX(request, scale),
                PreviewStageGeometry.modelOffsetY(request, scale),
                scale,
                appearance.capeGlint() && appearance.capeMode() == CapeMode.CAPE,
                false,
                PreviewDepthEnvelope.forRequest(
                        request.intent(), scale, request.pitchDegrees(), appearance.capeMode()),
                completion,
                null);
        try (NclBakedPlayerSubmission submission =
                NclBakedPlayerSubmission.open(graphics, state)) {
            submit(
                    graphics,
                    PLAYER_MODEL_SKIN ? playerModel : simpleModel,
                    state.skinTexture(),
                    scale,
                    request);
            submission.requireConsumed();
        }
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, BackEquipmentPreviewRenderer.Request request) {
        if (imageSource == null) imageSession.beginImage(java.util.List.of(request.texture(), request.mode(), request.capeHasElytra(), request.fitPolicy()));
        if (!imageSession.imageVisible() && !imageSession.imagePreparing()) return;
        if (imageSession.imageSpinner()) {
            var frame = com.naocraftlab.skins.client.PreviewLoading.frame((long) imageSession.imageFrame() * 100_000_000L,
                    request.left(), request.top(), request.width(), request.height(), 32);
            GuiExtraction.blit(graphics, Identifier.parse(frame.resource()),
                    frame.left(), frame.top(), (float) frame.u(), (float) frame.v(), frame.size(), frame.size(),
                    frame.width(), frame.height(), frame.textureWidth(), frame.textureHeight(), -1);
        }
        if (imageSession.imageVisible()) { renderEquipment(graphics, request, null); return; }
        var hidden = new BackEquipmentPreviewRenderer.Request(request.texture(), request.mode(), -request.width() - 1,
                request.top(), request.width(), request.height(), request.capeHasElytra(), request.fitPolicy());
        var completion = imageSession.imagePreparation(() -> true);
        renderEquipment(graphics, hidden, completion);
    }

    private void renderEquipment(GuiGraphicsExtractor graphics, BackEquipmentPreviewRenderer.Request request,
            com.naocraftlab.skins.client.PreviewModelFrames.Completion completion) {
        completion = com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame.afterConsumption(completion);
        VanillaPreviewModels.AttachmentModels attachment = attachment(request.mode());
        Object submittedModel = PLAYER_MODEL_SKIN ? attachment.player() : attachment.simple();
        float scale = VanillaBackEquipmentTransform.fitScale(
                request.width(), request.height(), request.mode(), request.fitPolicy());
        PreviewRenderer.CapeMode mode = request.mode() == BackEquipmentPreviewRenderer.Mode.ELYTRA
                ? PreviewRenderer.CapeMode.ELYTRA
                : PreviewRenderer.CapeMode.CAPE;
        NclBakedPlayerRenderState state = new NclBakedPlayerRenderState(
                target,
                null,
                null,
                PLAYER_MODEL_SKIN ? attachment.player() : null,
                PLAYER_MODEL_SKIN ? null : attachment.simple(),
                PLAYER_MODEL_SKIN ? attachment.playerCapePose() : attachment.simpleCapePose(),
                PLAYER_MODEL_SKIN ? attachment.playerElytraPose() : attachment.simpleElytraPose(),
                null,
                Identifier.parse(request.mode() == BackEquipmentPreviewRenderer.Mode.ELYTRA && !request.capeHasElytra()
                        ? "minecraft:textures/entity/equipment/wings/elytra.png" : request.texture().location()),
                mode,
                OuterLayerVisibility.allVisible(),
                0.0F,
                180.0F,
                request.left(),
                request.top(),
                request.left() + request.width(),
                request.top() + request.height(),
                0.0F,
                VanillaBackEquipmentTransform.modelOffsetY(request.mode(), request.fitPolicy()),
                scale,
                false,
                true,
                0.0F,
                completion,
                null);
        try (NclBakedPlayerSubmission submission = NclBakedPlayerSubmission.open(graphics, state)) {
            submit(
                    graphics,
                    submittedModel,
                    state.attachmentTexture(),
                    scale,
                    0.0F,
                    180.0F,
                    0.0F,
                    request.left(),
                    request.top(),
                    request.left() + request.width(),
                    request.top() + request.height());
            submission.requireConsumed();
        }
    }

    private static void submit(
            GuiGraphicsExtractor graphics,
            Object model,
            Identifier texture,
            float scale,
            PreviewRequest request) {
        submit(
                graphics,
                model,
                texture,
                scale,
                request.pitchDegrees(),
                request.yawDegrees(),
                0.0F,
                request.stageLeft(),
                request.stageTop(),
                request.stageLeft() + request.stageWidth(),
                request.stageTop() + request.stageHeight());
    }

    private static void submit(
            GuiGraphicsExtractor graphics,
            Object model,
            Identifier texture,
            float scale,
            float pitchDegrees,
            float yawDegrees,
            float pivotY,
            int left,
            int top,
            int right,
            int bottom) {
        SKIN_SUBMITTER.submit(
                graphics, model, texture, scale, pitchDegrees, yawDegrees, pivotY,
                left, top, right, bottom);
    }

    private VanillaPreviewModels.AttachmentModels attachment(CapeMode mode) {
        return mode == CapeMode.ELYTRA ? models.elytra : models.cape;
    }

    private VanillaPreviewModels.AttachmentModels attachment(
            BackEquipmentPreviewRenderer.Mode mode) {
        return mode == BackEquipmentPreviewRenderer.Mode.ELYTRA
                ? models.elytra
                : models.cape;
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

}

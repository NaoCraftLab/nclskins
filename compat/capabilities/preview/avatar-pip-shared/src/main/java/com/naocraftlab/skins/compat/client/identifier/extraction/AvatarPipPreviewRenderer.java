package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.SkinModel;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.resources.Identifier;


final class AvatarPipPreviewRenderer
        implements PreviewRenderer<GuiGraphicsExtractor>,
                BackEquipmentPreviewRenderer<GuiGraphicsExtractor>, AutoCloseable {
    private static final float MODEL_HEIGHT = 2.125F;
    private static final float FIT_PADDING = 0.97F;
    private static final float EQUIPMENT_MODEL_WIDTH = 1.5F;
    private static final float EQUIPMENT_MODEL_HEIGHT = 1.25F;
    private static final float EQUIPMENT_FIT_PADDING = 0.88F;
    private static final BakedSkinSubmitter SKIN_SUBMITTER =
            new NativeBakedSkinSubmitter();
    private static final boolean PLAYER_MODEL_SKIN = SKIN_SUBMITTER.usesPlayerModel();

    private final VanillaPreviewModels models = VanillaPreviewModels.INSTANCE;
    private final NclBakedPlayerTarget target = new NclBakedPlayerTarget();

    AvatarPipPreviewRenderer() {
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, PreviewRequest request) {
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
                .map(cape -> Identifier.parse(cape.location()))
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
                request.left(),
                request.top(),
                request.left() + request.width(),
                request.top() + request.height(),
                scale,
                false,
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
    public void render(
            GuiGraphicsExtractor graphics,
            BackEquipmentPreviewRenderer.Request request) {
        VanillaPreviewModels.AttachmentModels attachment = attachment(request.mode());
        Object submittedModel = PLAYER_MODEL_SKIN ? attachment.player() : attachment.simple();
        float scale = EQUIPMENT_FIT_PADDING * Math.min(
                request.width() / EQUIPMENT_MODEL_WIDTH,
                request.height() / EQUIPMENT_MODEL_HEIGHT);
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
                Identifier.parse(request.texture().location()),
                mode,
                OuterLayerVisibility.allVisible(),
                0.0F,
                180.0F,
                request.left(),
                request.top(),
                request.left() + request.width(),
                request.top() + request.height(),
                scale,
                true,
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
                request.left(),
                request.top(),
                request.left() + request.width(),
                request.top() + request.height());
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
        target.close();
    }

}

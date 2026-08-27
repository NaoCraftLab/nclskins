package com.naocraftlab.skins.compat.client.identifier.submission;

import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.PreviewStageGeometry;
import com.naocraftlab.skins.client.SkinModel;
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
    private static final float EQUIPMENT_MODEL_WIDTH = 1.5F;
    private static final float EQUIPMENT_MODEL_HEIGHT = 1.25F;
    private static final float FIT_PADDING = 0.97F;
    private static final float EQUIPMENT_FIT_PADDING = 0.88F;
    private final PlayerModel wide = model(false);
    private final PlayerModel slim = model(true);
    private final AttachmentModels cape = capeModels();
    private final AttachmentModels elytra = elytraModels();
    private final BakedPreviewTarget target =
            new BakedPreviewTarget();

    @Override
    public void render(GuiGraphics graphics, PreviewRequest request) {
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
                attachmentTexture.map(value -> Identifier.parse(value.location())).orElse(null),
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
                false,
                null);
        submit(graphics, model, state.skinTexture(), scale, request, state);
    }

    @Override
    public void render(GuiGraphics graphics, BackEquipmentPreviewRenderer.Request request) {
        AttachmentModels attachment = request.mode() == BackEquipmentPreviewRenderer.Mode.CAPE
                ? cape
                : elytra;
        CapeMode mode = request.mode() == BackEquipmentPreviewRenderer.Mode.CAPE
                ? CapeMode.CAPE
                : CapeMode.ELYTRA;
        Identifier texture = Identifier.parse(request.texture().location());
        float scale = EQUIPMENT_FIT_PADDING * Math.min(
                request.width() / EQUIPMENT_MODEL_WIDTH,
                request.height() / EQUIPMENT_MODEL_HEIGHT);
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
                true,
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
        target.close();
    }

    private record AttachmentModels(
            Model<?> renderModel,
            PlayerCapeModel capePoseModel,
            ElytraModel elytraPoseModel) {}
}

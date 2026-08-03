package com.naocraftlab.skins.compat.mc262;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.SkinModel;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.object.equipment.ElytraModel;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;


final class Minecraft262SimplePreviewRenderer implements PreviewRenderer<GuiGraphicsExtractor> {
    private static final float MODEL_HEIGHT = 2.125F;
    private static final float FIT_PADDING = 0.97F;
    private static final float PIVOT_Y = -MODEL_HEIGHT / 2.0F;

    private final Model.Simple wideModel;
    private final Model.Simple slimModel;
    private final Model.Simple capeModel;
    private final Model.Simple elytraModel;

    Minecraft262SimplePreviewRenderer() {


        wideModel = simplePlayerModel(false);
        slimModel = simplePlayerModel(true);
        capeModel = new Model.Simple(
                PlayerCapeModel.createCapeLayer().bakeRoot(), RenderTypes::entityTranslucent);
        elytraModel = new Model.Simple(ElytraModel.createLayer().bakeRoot(), RenderTypes::entityTranslucent);
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, PreviewRequest request) {
        PreviewAppearance appearance = request.appearance();
        Model.Simple playerModel = appearance.model() == SkinModel.SLIM ? slimModel : wideModel;
        configureOuterLayer(playerModel.root(), appearance.outerLayerVisibility());
        float scale = FIT_PADDING * request.height() / MODEL_HEIGHT * request.scale();

        appearance.cape().filter(ignored -> appearance.capeMode() != CapeMode.OFF).ifPresent(cape -> {
            Model.Simple attachment = appearance.capeMode() == CapeMode.ELYTRA ? elytraModel : capeModel;
            attachment.resetPose();


            submit(
                    graphics,
                    attachment,
                    Identifier.parse(cape.location()),
                    scale,
                    request);
        });
        submit(
                graphics,
                playerModel,
                Identifier.parse(appearance.skin().location()),
                scale,
                request);
    }

    private static void submit(
            GuiGraphicsExtractor graphics,
            Model.Simple model,
            Identifier texture,
            float scale,
            PreviewRequest request) {
        graphics.skin(
                model,
                texture,
                scale,
                request.pitchDegrees(),
                request.yawDegrees(),
                PIVOT_Y,
                request.left(),
                request.top(),
                request.left() + request.width(),
                request.top() + request.height());
    }

    private static void configureOuterLayer(ModelPart root, OuterLayerVisibility visible) {
        root.resetPose();
        root.getChild("head").getChild("hat").visible = visible.visible(OuterLayerPart.HEAD);
        root.getChild("body").getChild("jacket").visible = visible.visible(OuterLayerPart.BODY);
        root.getChild("left_arm").getChild("left_sleeve").visible = visible.visible(OuterLayerPart.LEFT_ARM);
        root.getChild("right_arm").getChild("right_sleeve").visible = visible.visible(OuterLayerPart.RIGHT_ARM);
        root.getChild("left_leg").getChild("left_pants").visible = visible.visible(OuterLayerPart.LEFT_LEG);
        root.getChild("right_leg").getChild("right_pants").visible = visible.visible(OuterLayerPart.RIGHT_LEG);
    }

    private static Model.Simple simplePlayerModel(boolean slim) {
        ModelPart root = LayerDefinition.create(
                        PlayerModel.createMesh(CubeDeformation.NONE, slim), 64, 64)
                .bakeRoot();
        return new Model.Simple(root, RenderTypes::entityTranslucent);
    }
}

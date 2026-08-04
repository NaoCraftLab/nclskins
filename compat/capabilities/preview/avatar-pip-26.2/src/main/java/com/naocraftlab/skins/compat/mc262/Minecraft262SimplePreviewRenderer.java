package com.naocraftlab.skins.compat.mc262;

import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.object.equipment.ElytraModel;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;


final class Minecraft262SimplePreviewRenderer
        implements PreviewRenderer<GuiGraphicsExtractor>, BackEquipmentPreviewRenderer<GuiGraphicsExtractor> {
    private static final float MODEL_HEIGHT = 2.125F;
    private static final float FIT_PADDING = 0.97F;
    private static final float PIVOT_Y = -MODEL_HEIGHT / 2.0F;
    private static final float EQUIPMENT_MODEL_WIDTH = 1.5F;
    private static final float EQUIPMENT_MODEL_HEIGHT = 1.25F;
    private static final float EQUIPMENT_PIVOT_Y = -EQUIPMENT_MODEL_HEIGHT / 2.0F;
    private static final float EQUIPMENT_FIT_PADDING = 0.88F;
    private static final Method SKIN_METHOD = skinMethod();
    private static final boolean PLAYER_MODEL_SKIN =
            SKIN_METHOD.getParameterTypes()[0] == PlayerModel.class;

    private final Model.Simple wideModel;
    private final Model.Simple slimModel;
    private final PlayerModel widePlayerModel;
    private final PlayerModel slimPlayerModel;
    private final Model.Simple simpleCapeModel;
    private final Model.Simple simpleElytraModel;
    private final PlayerModel playerCapeModel;
    private final PlayerModel playerElytraModel;

    Minecraft262SimplePreviewRenderer() {


        wideModel = simplePlayerModel(false);
        slimModel = simplePlayerModel(true);
        widePlayerModel = playerModel(false);
        slimPlayerModel = playerModel(true);
        simpleCapeModel = new Model.Simple(
                PlayerCapeModel.createCapeLayer().bakeRoot(), RenderTypes::entityTranslucent);
        simpleElytraModel = new Model.Simple(
                ElytraModel.createLayer().bakeRoot(), RenderTypes::entityTranslucent);
        playerCapeModel = new PlayerCapeModel(PlayerCapeModel.createCapeLayer().bakeRoot());
        playerElytraModel = new PlayerModel(playerElytraRoot(), false);
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, PreviewRequest request) {
        PreviewAppearance appearance = request.appearance();
        Model.Simple simpleModel = appearance.model() == SkinModel.SLIM ? slimModel : wideModel;
        PlayerModel playerModel = appearance.model() == SkinModel.SLIM ? slimPlayerModel : widePlayerModel;
        ModelPart playerRoot = PLAYER_MODEL_SKIN ? playerModel.root() : simpleModel.root();
        configureOuterLayer(playerRoot, appearance.outerLayerVisibility());
        float scale = FIT_PADDING * request.height() / MODEL_HEIGHT * request.scale();

        appearance.cape().filter(ignored -> appearance.capeMode() != CapeMode.OFF).ifPresent(cape -> {
            Object attachment = attachment(appearance.capeMode());
            ((Model<?>) attachment).resetPose();


            submit(
                    graphics,
                    attachment,
                    Identifier.parse(cape.location()),
                    scale,
                    request);
        });
        submit(
                graphics,
                PLAYER_MODEL_SKIN ? playerModel : simpleModel,
                Identifier.parse(appearance.skin().location()),
                scale,
                request);
    }

    @Override
    public void render(
            GuiGraphicsExtractor graphics,
            BackEquipmentPreviewRenderer.Request request) {
        Object attachment = attachment(request.mode());
        ((Model<?>) attachment).resetPose();
        float scale = EQUIPMENT_FIT_PADDING * Math.min(
                request.width() / EQUIPMENT_MODEL_WIDTH,
                request.height() / EQUIPMENT_MODEL_HEIGHT);
        submit(
                graphics,
                attachment,
                Identifier.parse(request.texture().location()),
                scale,
                0.0F,
                180.0F,
                EQUIPMENT_PIVOT_Y,
                request.left(),
                request.top(),
                request.left() + request.width(),
                request.top() + request.height());
    }

    void renderAttachment(
            GuiGraphicsExtractor graphics,
            PreviewRequest request,
            TextureHandle texture) {
        Object cape = attachment(CapeMode.CAPE);
        ((Model<?>) cape).resetPose();
        float scale = FIT_PADDING * request.height() / MODEL_HEIGHT * request.scale();
        submit(graphics, cape, Identifier.parse(texture.location()), scale, request);
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
                PIVOT_Y,
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
        try {
            SKIN_METHOD.invoke(
                    graphics,
                    model,
                    texture,
                    scale,
                    pitchDegrees,
                    yawDegrees,
                    pivotY,
                    left,
                    top,
                    right,
                    bottom);
        } catch (IllegalAccessException | InvocationTargetException error) {
            throw new IllegalStateException("Cannot submit 26.x simple player preview", error);
        }
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

    private static PlayerModel playerModel(boolean slim) {
        ModelPart root = LayerDefinition.create(
                        PlayerModel.createMesh(CubeDeformation.NONE, slim), 64, 64)
                .bakeRoot();
        return new PlayerModel(root, slim);
    }

    private Object attachment(CapeMode mode) {
        if (PLAYER_MODEL_SKIN) {
            return mode == CapeMode.ELYTRA ? playerElytraModel : playerCapeModel;
        }
        return mode == CapeMode.ELYTRA ? simpleElytraModel : simpleCapeModel;
    }

    private Object attachment(BackEquipmentPreviewRenderer.Mode mode) {
        if (PLAYER_MODEL_SKIN) {
            return mode == BackEquipmentPreviewRenderer.Mode.ELYTRA
                    ? playerElytraModel
                    : playerCapeModel;
        }
        return mode == BackEquipmentPreviewRenderer.Mode.ELYTRA
                ? simpleElytraModel
                : simpleCapeModel;
    }

    private static ModelPart playerElytraRoot() {
        MeshDefinition mesh = PlayerModel.createMesh(CubeDeformation.NONE, false);
        mesh.getRoot().clearRecursively();
        CubeDeformation deformation = new CubeDeformation(1.0F);
        mesh.getRoot().addOrReplaceChild(
                "left_wing",
                CubeListBuilder.create().texOffs(22, 0)
                        .addBox(-10.0F, 0.0F, 0.0F, 10.0F, 20.0F, 2.0F, deformation),
                PartPose.offsetAndRotation(5.0F, 0.0F, 0.0F, 0.2617994F, 0.0F, -0.2617994F));
        mesh.getRoot().addOrReplaceChild(
                "right_wing",
                CubeListBuilder.create().texOffs(22, 0).mirror()
                        .addBox(0.0F, 0.0F, 0.0F, 10.0F, 20.0F, 2.0F, deformation),
                PartPose.offsetAndRotation(-5.0F, 0.0F, 0.0F, 0.2617994F, 0.0F, 0.2617994F));
        return LayerDefinition.create(mesh, 64, 32).bakeRoot();
    }

    private static Method skinMethod() {
        for (Method method : GuiGraphicsExtractor.class.getMethods()) {
            if (method.getName().equals("skin") && method.getParameterCount() == 10) {
                return method;
            }
        }
        throw new IllegalStateException("Missing 26.x GuiGraphicsExtractor.skin overload");
    }
}

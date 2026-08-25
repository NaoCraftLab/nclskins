package com.naocraftlab.skins.compat.client.identifier.extraction;

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

final class VanillaPreviewModels {
    static final VanillaPreviewModels INSTANCE = new VanillaPreviewModels();

    final Model.Simple wideModel;
    final Model.Simple slimModel;
    final PlayerModel widePlayerModel;
    final PlayerModel slimPlayerModel;
    final AttachmentModels cape;
    final AttachmentModels elytra;

    private VanillaPreviewModels() {
        wideModel = simplePlayerModel(false);
        slimModel = simplePlayerModel(true);
        widePlayerModel = playerModel(false);
        slimPlayerModel = playerModel(true);
        cape = capeModels();
        elytra = elytraModels();
    }

    private static AttachmentModels capeModels() {
        ModelPart simpleRoot = PlayerCapeModel.createCapeLayer().bakeRoot();
        ModelPart playerRoot = PlayerCapeModel.createCapeLayer().bakeRoot();
        return new AttachmentModels(
                new Model.Simple(simpleRoot, RenderTypes::entityTranslucent),
                new PlayerCapeModel(playerRoot),
                new PlayerCapeModel(simpleRoot),
                new PlayerCapeModel(playerRoot),
                null,
                null);
    }

    private static AttachmentModels elytraModels() {
        ModelPart simpleRoot = ElytraModel.createLayer().bakeRoot();
        ModelPart playerRoot = playerElytraRoot();
        return new AttachmentModels(
                new Model.Simple(simpleRoot, RenderTypes::entityTranslucent),
                new PlayerModel(playerRoot, false),
                null,
                null,
                new ElytraModel(simpleRoot),
                new ElytraModel(playerRoot));
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

    record AttachmentModels(
            Model.Simple simple,
            PlayerModel player,
            PlayerCapeModel simpleCapePose,
            PlayerCapeModel playerCapePose,
            ElytraModel simpleElytraPose,
            ElytraModel playerElytraPose) {
    }
}

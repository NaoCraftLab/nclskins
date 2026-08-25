package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.world.entity.LivingEntity;

final class VanillaPreviewModels {
    private static final VanillaPreviewModels INSTANCE =
            new VanillaPreviewModels();

    final PlayerModel<LivingEntity> classic;
    final PlayerModel<LivingEntity> slim;
    final ModelPart classicCloak;
    final ModelPart slimCloak;
    final ModelPart elytraRoot;
    final ElytraModel<LivingEntity> elytra;

    private VanillaPreviewModels() {
        ModelPart classicRoot = playerRoot(false);
        ModelPart slimRoot = playerRoot(true);
        classic = new PlayerModel<>(classicRoot, false);
        slim = new PlayerModel<>(slimRoot, true);
        classicCloak = classicRoot.getChild("cloak");
        slimCloak = slimRoot.getChild("cloak");
        elytraRoot = ElytraModel.createLayer().bakeRoot();
        elytra = new ElytraModel<>(elytraRoot);
    }

    static VanillaPreviewModels instance() {
        return INSTANCE;
    }

    private static ModelPart playerRoot(boolean slim) {
        return LayerDefinition.create(
                        PlayerModel.createMesh(CubeDeformation.NONE, slim), 64, 64)
                .bakeRoot();
    }
}

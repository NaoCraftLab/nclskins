package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup;

import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;

final class VanillaPreviewModels {
    private static final VanillaPreviewModels INSTANCE =
            new VanillaPreviewModels();

    final PlayerModel<?> classic;
    final PlayerModel<?> slim;
    final ModelPart classicCloak;
    final ModelPart slimCloak;
    final ModelPart elytraRoot;
    final ElytraModel<?> elytra;

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

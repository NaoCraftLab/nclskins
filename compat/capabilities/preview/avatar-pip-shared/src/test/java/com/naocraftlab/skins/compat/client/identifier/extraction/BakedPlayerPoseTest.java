package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.tck.BakedPlayerPoseAssertions;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerModel;
import org.junit.jupiter.api.Test;

class BakedPlayerPoseTest {
    @Test
    void bakedNativeMeshHasNeutralAlignedLayers() throws Exception {
        var configure = BakedPlayerPose.class.getDeclaredMethod("configureOuterLayer",
                ModelPart.class, OuterLayerVisibility.class);
        configure.setAccessible(true);
        String[] names = {"head", "body", "left_arm", "right_arm", "left_leg", "right_leg"};
        String[] overlays = {"hat", "jacket", "left_sleeve", "right_sleeve", "left_pants", "right_pants"};
        OuterLayerPart[] flags = {OuterLayerPart.HEAD, OuterLayerPart.BODY,
                OuterLayerPart.LEFT_ARM, OuterLayerPart.RIGHT_ARM,
                OuterLayerPart.LEFT_LEG, OuterLayerPart.RIGHT_LEG};
        for (boolean slim : new boolean[]{false, true}) {
            ModelPart root = LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, slim),
                    64, 64).bakeRoot();
            for (int mask = 0; mask < 64; mask++) {
                OuterLayerVisibility visible = OuterLayerVisibility.noneVisible();
                for (OuterLayerPart flag : flags) {
                    visible = visible.with(flag, (mask & (1 << flag.ordinal())) != 0);
                }
                configure.invoke(null, root, visible);
                for (int i = 0; i < names.length; i++) {
                    ModelPart base = root.getChild(names[i]);
                    ModelPart overlay = base.getChild(overlays[i]);
                    float[] basePose = transform(base);
                    float[] overlayPose = transform(overlay);
                    for (int j = 0; j < basePose.length; j++) overlayPose[j] += basePose[j];
                    BakedPlayerPoseAssertions.neutral(basePose, overlayPose,
                            overlay.visible, visible.visible(flags[i]));
                }
            }
        }
    }

    private static float[] transform(ModelPart part) {
        return new float[]{part.x, part.y, part.z, part.xRot, part.yRot, part.zRot};
    }
}

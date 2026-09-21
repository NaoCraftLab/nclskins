package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.tck.BakedPlayerPoseAssertions;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import org.junit.jupiter.api.Test;

class BakedPlayerPoseTest {
    @Test
    void sharedBakedPoseResetsModelsAndEveryMask() {
        for (PlayerModel<?> model : new PlayerModel<?>[]{VanillaPreviewModels.instance().classic,
                VanillaPreviewModels.instance().slim, VanillaPreviewModels.instance().classic}) {
            for (int mask = 0; mask < 64; mask++) {
                OuterLayerVisibility visible = OuterLayerVisibility.noneVisible();
                for (OuterLayerPart part : OuterLayerPart.values()) {
                    visible = visible.with(part, (mask & (1 << part.ordinal())) != 0);
                }
                ModelPart[] base = {model.head, model.body, model.leftArm, model.rightArm,
                        model.leftLeg, model.rightLeg};
                ModelPart[] overlay = {model.hat, model.jacket, model.leftSleeve,
                        model.rightSleeve, model.leftPants, model.rightPants};
                for (ModelPart[] parts : new ModelPart[][]{base, overlay}) {
                    for (ModelPart part : parts) {
                        part.xRot = 0.4F;
                        part.yRot = -0.3F;
                        part.zRot = 0.5F;
                        part.x += 2;
                        part.visible = false;
                    }
                }
                model.attackTime = 1.0F;
                model.young = true;
                model.riding = true;
                model.crouching = true;
                BakedPlayerPose.configure(model, visible);
                assertEquals(0.0F, model.attackTime);
                assertFalse(model.young);
                assertFalse(model.riding);
                assertFalse(model.crouching);
                OuterLayerPart[] flags = {OuterLayerPart.HEAD, OuterLayerPart.BODY,
                        OuterLayerPart.LEFT_ARM, OuterLayerPart.RIGHT_ARM,
                        OuterLayerPart.LEFT_LEG, OuterLayerPart.RIGHT_LEG};
                for (int i = 0; i < base.length; i++) {
                    assertTrue(base[i].visible);
                    assertEquals(base[i].getInitialPose().x, base[i].x);
                    BakedPlayerPoseAssertions.neutral(transform(base[i]), transform(overlay[i]),
                            overlay[i].visible, visible.visible(flags[i]));
                }
            }
        }
    }

    private static float[] transform(ModelPart part) {
        return new float[]{part.x, part.y, part.z, part.xRot, part.yRot, part.zRot};
    }
}

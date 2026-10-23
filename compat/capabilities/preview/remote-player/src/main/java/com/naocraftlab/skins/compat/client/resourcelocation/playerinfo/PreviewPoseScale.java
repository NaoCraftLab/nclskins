package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

import com.mojang.blaze3d.vertex.PoseStack;

final class PreviewPoseScale {
    private PreviewPoseScale() {
    }

    static void apply(PoseStack pose, float x, float y, float z) {
        if (Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)
                && x != 0.0F && Math.abs(x) == Math.abs(y) && Math.abs(x) == Math.abs(z)) {
            pose.last().pose().scale(x, y, z);
            pose.last().normal().scale(Math.signum(x), Math.signum(y), Math.signum(z));
        } else {
            pose.scale(x, y, z);
        }
    }
}

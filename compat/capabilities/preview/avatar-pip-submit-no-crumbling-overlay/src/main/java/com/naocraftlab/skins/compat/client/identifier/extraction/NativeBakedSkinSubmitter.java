package com.naocraftlab.skins.compat.client.identifier.extraction;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.Model;
import net.minecraft.resources.Identifier;

final class NativeBakedSkinSubmitter implements BakedSkinSubmitter {
    @Override
    public boolean usesPlayerModel() {
        return false;
    }

    @Override
    public void submit(
            GuiGraphicsExtractor graphics, Object model, Identifier texture,
            float scale, float pitchDegrees, float yawDegrees, float pivotY,
            int left, int top, int right, int bottom) {
        graphics.skin((Model.Simple) model, texture, scale, pitchDegrees, yawDegrees, pivotY,
                left, top, right, bottom);
    }
}

package com.naocraftlab.skins.compat.client.identifier.extraction;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

interface BakedSkinSubmitter {
    boolean usesPlayerModel();

    void submit(
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
            int bottom);
}

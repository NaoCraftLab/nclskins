package com.naocraftlab.skins.compat.mc262;

import com.naocraftlab.skins.client.CenteredPipPreviewTransform;

final class Minecraft262LivePitch {
    private Minecraft262LivePitch() {
    }

    static float radians(float pitchDegrees) {
        return CenteredPipPreviewTransform.pitchRadians(pitchDegrees);
    }
}

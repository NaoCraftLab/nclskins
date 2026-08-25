package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.naocraftlab.skins.client.CenteredPipPreviewTransform;

final class LivePreviewPitch {
    private LivePreviewPitch() {
    }

    static float radians(float pitchDegrees) {
        return CenteredPipPreviewTransform.pitchRadians(pitchDegrees);
    }
}

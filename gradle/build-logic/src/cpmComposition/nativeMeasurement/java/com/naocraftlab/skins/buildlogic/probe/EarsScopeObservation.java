package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture;
import com.unascribed.ears.api.features.EarsFeatures;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

public final class EarsScopeObservation {
    public static int reference, neutral, live;
    public static EarsFeatures observe(EarsFeatures selected, AvatarRenderState state) {
        var original = diy.y2k.five.thermite.Thermite.getEarsFeatures(state);
        if (!original.enabled) return selected;
        if (AvatarBodyCapture.excludes(state)) {
            if (selected == EarsFeatures.DISABLED) reference++;
            else {
                PipMaterialProbe.check(selected != original && !selected.animateTail && !selected.capeEnabled
                        && selected.wingAnimationMode == EarsFeatures.WingAnimationMode.NONE,
                        "real installed delegate reads production neutral immutable features");
                PipMaterialProbe.check(selected.earMode == original.earMode && selected.tailMode == original.tailMode
                        && selected.tailSegments == original.tailSegments && selected.wingMode == original.wingMode,
                        "real native measurement retains ears tail wings BODY detail");
                neutral++;
            }
        } else {
            PipMaterialProbe.check(selected == original, "native live feature read remains exact original immutable object");
            live++;
        }
        return selected;
    }
}

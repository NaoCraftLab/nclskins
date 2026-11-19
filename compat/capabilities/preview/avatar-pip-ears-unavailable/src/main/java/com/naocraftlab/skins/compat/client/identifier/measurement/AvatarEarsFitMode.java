package com.naocraftlab.skins.compat.client.identifier.measurement;

import com.naocraftlab.skins.client.PreviewModelFrames;
import com.naocraftlab.skins.runtime.EffectiveSkinSource;

public final class AvatarEarsFitMode {
    private AvatarEarsFitMode() {}

    public interface EarsBody {
        boolean current();
        PreviewModelFrames.Lease open(Object state, boolean reference);
    }

    public static boolean earsAvailable() {
        return false;
    }

    public static EarsBody ears(EffectiveSkinSource source) {
        return null;
    }
}
package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup;

import com.naocraftlab.skins.client.PreviewModelFrames;
import com.naocraftlab.skins.runtime.EffectiveSkinSource;

public final class PlayerSkinEarsFitMode {
    private PlayerSkinEarsFitMode() {}
    public interface EarsBody {
        boolean current();
        PreviewModelFrames.Lease open(Object player, boolean reference);
    }
    public static boolean available() {
        return PlayerSkinEarsFitMode.class.getClassLoader().getResource("diy/y2k/five/buzz/support/EarsFeaturesHolder.class") != null;
    }
    public static EarsBody prepare(EffectiveSkinSource source) {
        return available() ? com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.ears.EarsNeutralBody.prepare(source) : null;
    }
}

package com.naocraftlab.skins.compat.client.identifier.measurement;

public final class AvatarEarsFitMode {
    private AvatarEarsFitMode() {}

    public interface EarsBody {
        boolean current();
        com.naocraftlab.skins.client.PreviewModelFrames.Lease open(Object state, boolean reference);
    }

    public static boolean earsAvailable() {
        return AvatarEarsFitMode.class.getClassLoader().getResource("diy/y2k/five/thermite/FeaturesHolder.class") != null;
    }

    public static EarsBody ears(com.naocraftlab.skins.runtime.EffectiveSkinSource source) {
        return earsAvailable() ? com.naocraftlab.skins.compat.client.identifier.ears.EarsNeutralBody.prepare(source) : null;
    }
}

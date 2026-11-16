package com.naocraftlab.skins.compat.client.identifier.measurement;

public final class WardEarsFitMode {
    private WardEarsFitMode() {}

    public interface EarsBody {
        boolean current();
        com.naocraftlab.skins.client.PreviewModelFrames.Lease open(Object state, boolean reference);
    }

    public static boolean earsAvailable() {
        return WardEarsFitMode.class.getClassLoader().getResource("diy/y2k/five/ward/FeaturesHolder.class") != null;
    }

    public static EarsBody ears(com.naocraftlab.skins.runtime.EffectiveSkinSource source) {
        return earsAvailable() ? com.naocraftlab.skins.compat.client.identifier.ward.EarsNeutralBody.prepare(source) : null;
    }
}

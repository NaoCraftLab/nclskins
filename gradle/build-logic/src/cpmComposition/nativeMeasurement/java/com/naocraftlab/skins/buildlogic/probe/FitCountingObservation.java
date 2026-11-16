package com.naocraftlab.skins.buildlogic.probe;

public final class FitCountingObservation {
    public static int classifiers, copies, captures, decodes;
    public static int classifiersOutsideAssetIo;
    public static void capture() { captures++; }
    public static void decode() { decodes++; }
    public static void classifier() {
        classifiers++;
        if(!StackWalker.getInstance().walk(frames->frames.anyMatch(frame->frame.getClassName().equals("com.naocraftlab.skins.core.png.PngValidator")
                || frame.getClassName().equals("com.naocraftlab.skins.runtime.CapeProjection"))))classifiersOutsideAssetIo++;
    }
    public static void copy() { copies++; }
}

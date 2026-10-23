package com.naocraftlab.skins.buildlogic.probe.wavey;

public final class BakedGuiInputs {
    public static Object scissorStack() throws Exception {
        var constructor = Class.forName("net.minecraft.client.gui.GuiGraphicsExtractor$ScissorStack").getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}

package com.naocraftlab.skins.buildlogic.probe.wavey;

import net.minecraft.client.gui.navigation.ScreenRectangle;

public final class BakedGuiInputs {
    public static Object scissorStack() throws Exception {
        var constructor = Class.forName("net.minecraft.client.gui.GuiGraphicsExtractor$ScissorStack").getDeclaredConstructor(ScreenRectangle.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new ScreenRectangle(0, 0, 100, 160));
    }
}

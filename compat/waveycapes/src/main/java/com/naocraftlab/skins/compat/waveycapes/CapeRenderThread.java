package com.naocraftlab.skins.compat.waveycapes;

import net.minecraft.client.Minecraft;

public final class CapeRenderThread {
    public static boolean isClientThread() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.isSameThread();
    }
    private CapeRenderThread() {}
}

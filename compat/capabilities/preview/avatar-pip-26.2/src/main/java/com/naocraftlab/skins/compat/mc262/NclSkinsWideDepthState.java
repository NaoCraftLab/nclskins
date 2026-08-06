package com.naocraftlab.skins.compat.mc262;

import net.minecraft.resources.Identifier;


public interface NclSkinsWideDepthState {
    boolean nclskins$usesWideDepth();

    void nclskins$setWideDepth(boolean wideDepth);

    Identifier nclskins$worldlessCapeTexture();

    void nclskins$setWorldlessCapeTexture(Identifier texture);
}

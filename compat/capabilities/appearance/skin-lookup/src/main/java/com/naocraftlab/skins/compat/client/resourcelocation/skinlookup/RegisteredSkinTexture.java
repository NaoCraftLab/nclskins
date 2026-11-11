package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup;

import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import net.minecraft.resources.ResourceLocation;

public interface RegisteredSkinTexture {
    void nclskins$registeredSkinLocation(ResourceLocation location);

    void nclskins$skinRegistration(NativePlayerSkinLifecycle.Registration registration);
}

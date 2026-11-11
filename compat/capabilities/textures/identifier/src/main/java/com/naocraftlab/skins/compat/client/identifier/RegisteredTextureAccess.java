package com.naocraftlab.skins.compat.client.identifier;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

public interface RegisteredTextureAccess {
    boolean nclskins$isRegistered(Identifier location, AbstractTexture texture);
}

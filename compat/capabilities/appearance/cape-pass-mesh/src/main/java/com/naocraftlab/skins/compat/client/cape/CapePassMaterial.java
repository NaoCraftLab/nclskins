package com.naocraftlab.skins.compat.client.cape;

import com.mojang.blaze3d.vertex.MeshData;
import com.naocraftlab.skins.client.TextureUseValidity;
import net.minecraft.client.renderer.RenderType;

public final class CapePassMaterial extends CapturedCapePass {
    public CapePassMaterial(RenderType original, TextureUseValidity validity) { super(original, validity); }

    @Override public void draw(MeshData mesh) {
        if (isValid()) original.draw(mesh);
        else mesh.close();
    }
}

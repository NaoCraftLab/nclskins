package com.naocraftlab.skins.compat.client.cape;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.naocraftlab.skins.client.TextureUseValidity;
import net.minecraft.client.renderer.RenderType;

public final class CapePassMaterial extends CapturedCapePass {
    public CapePassMaterial(RenderType original, TextureUseValidity validity) { super(original, validity); }

    @Override public void end(BufferBuilder builder, VertexSorting sorting) {
        if (isValid()) original.end(builder, sorting);
        else if (builder.building()) builder.end().release();
    }
}

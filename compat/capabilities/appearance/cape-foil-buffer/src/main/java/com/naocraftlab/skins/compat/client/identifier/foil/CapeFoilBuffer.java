package com.naocraftlab.skins.compat.client.identifier.foil;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

public final class CapeFoilBuffer {
    public static VertexConsumer buffer(MultiBufferSource buffers, Identifier texture,
            boolean glint, RenderType plain) {
        VertexConsumer base = buffers.getBuffer(glint ? CapeTranslucentFoilMaterial.forTexture(texture) : plain);
        return glint && CapeTranslucentFoilMaterial.inWorld()
                ? VertexMultiConsumer.create(buffers.getBuffer(RenderTypes.entityGlint()), base) : base;
    }

    private CapeFoilBuffer() {}
}

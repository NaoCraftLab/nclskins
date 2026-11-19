package com.naocraftlab.skins.compat.client.identifier.measurement.builder.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BufferBuilder.class)
public interface BufferBuilderFormatAccessor {
    @Accessor("format")
    VertexFormat nclskins$bodyVertexFormat();
}
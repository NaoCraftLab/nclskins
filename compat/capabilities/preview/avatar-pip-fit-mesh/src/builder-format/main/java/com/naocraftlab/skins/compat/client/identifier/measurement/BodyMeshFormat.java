package com.naocraftlab.skins.compat.client.identifier.measurement;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.naocraftlab.skins.compat.client.identifier.measurement.builder.mixin.BufferBuilderFormatAccessor;

public record BodyMeshFormat(int stride, int positionOffset) {
    public static BodyMeshFormat resolve(BufferBuilder builder, MeshData.DrawState draw) {
        var format = ((BufferBuilderFormatAccessor) builder).nclskins$bodyVertexFormat();
        return new BodyMeshFormat(format.getVertexSize(), format.getElement("Position").offset());
    }
}
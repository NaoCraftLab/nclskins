package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture;

public final class PlayerSkinBodyCapture {
    private PlayerSkinBodyCapture() {}

    public static MeshData drain(MeshData mesh) {
        if (mesh == null || !AvatarBodyCapture.hiding()) return mesh;
        try (mesh) {
            var draw = mesh.drawState();
            var format = draw.format();
            var bytes = mesh.vertexBuffer().duplicate().order(java.nio.ByteOrder.nativeOrder());
            int stride = format.getVertexSize();
            int position = format.getOffset(VertexFormatElement.POSITION);
            for (int i = 0; i < draw.vertexCount(); i++) {
                int offset = i * stride + position;
                AvatarBodyCapture.vertex(bytes.getFloat(offset), bytes.getFloat(offset + 4), bytes.getFloat(offset + 8));
            }
        }
        return null;
    }
}

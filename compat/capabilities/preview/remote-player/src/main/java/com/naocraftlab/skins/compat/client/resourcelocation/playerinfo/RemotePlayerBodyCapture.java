package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture;

public final class RemotePlayerBodyCapture {
    private RemotePlayerBodyCapture() {}

    public static void drain(BufferBuilder.RenderedBuffer mesh) {
        try {
            var state = mesh.drawState();
            var format = state.format();
            int position = 0;
            boolean found = false;
            for (var element : format.getElements()) {
                if (element.getUsage() == VertexFormatElement.Usage.POSITION) { found = true; break; }
                position += element.getByteSize();
            }
            if (!found) throw new IllegalStateException("Missing BODY position element");
            var bytes = mesh.vertexBuffer().duplicate().order(java.nio.ByteOrder.nativeOrder());
            for (int i = 0; i < state.vertexCount(); i++) {
                int offset = i * format.getVertexSize() + position;
                AvatarBodyCapture.vertex(bytes.getFloat(offset), bytes.getFloat(offset + 4), bytes.getFloat(offset + 8));
            }
        } finally { mesh.release(); }
    }
}

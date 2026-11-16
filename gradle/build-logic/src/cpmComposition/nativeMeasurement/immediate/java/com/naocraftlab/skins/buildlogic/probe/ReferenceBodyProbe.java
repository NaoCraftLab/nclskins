package com.naocraftlab.skins.buildlogic.probe;

import com.mojang.blaze3d.vertex.MeshData;

public final class ReferenceBodyProbe {
    public static int verticesCount;
    static void reset() { verticesCount = 0; }
    public static void mesh(MeshData mesh) {
        if (mesh != null) verticesCount = Math.addExact(verticesCount, mesh.drawState().vertexCount());
    }
}

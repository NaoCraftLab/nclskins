package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.*;
import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.CapeRenderer;
import java.util.*;
import net.minecraft.client.renderer.rendertype.RenderType;

public final class BufferedCapeHost {
    private static final Map<BufferBuilder, Integer> vertices = new IdentityHashMap<>();
    private static final Set<MeshData> openMeshes = Collections.newSetFromMap(new IdentityHashMap<>());
    public static int draws, geometry, foreignVertices, ownVertices, closes;
    public static boolean abortGeometry;
    public static Runnable drawBoundary;
    public static java.util.function.Consumer<VertexConsumer> geometrySink;
    public static final Set<CapeRenderer> renderers = new HashSet<>();
    public static void reset() {
        vertices.clear(); openMeshes.clear();
        renderers.clear();
        draws = geometry = foreignVertices = ownVertices = closes = 0;
        abortGeometry = false;
        drawBoundary = null; geometrySink = null;
    }
    public static VertexConsumer vertex(BufferBuilder builder, float x, float y, float z) {
        vertices.merge(builder, 1, Integer::sum);
        return builder;
    }
    public static MeshData build(BufferBuilder builder) {
        int count = vertices.getOrDefault(builder, 0);
        vertices.remove(builder);
        if (count == 0) return null;
        MeshData mesh = new MeshData(null, new MeshData.DrawState(null, count,
                count, VertexFormat.Mode.QUADS, VertexFormat.IndexType.SHORT));
        openMeshes.add(mesh);
        return mesh;
    }
    public static void close(MeshData mesh) {
        if (!openMeshes.remove(mesh)) throw new AssertionError("Mesh not closed exactly once");
        closes++;
    }
    public static void draw(RenderType type, MeshData mesh) {
        CapturedCapePass capture = CapturedCapePass.of(type);
        if (capture != null && !capture.validity().isValid()) throw new AssertionError("BUFFERED_RETIRED_AT_DRAW");
        draws++;
        if (drawBoundary != null) drawBoundary.run();
        if (capture == null) foreignVertices += mesh.drawState().vertexCount();
        else ownVertices += mesh.drawState().vertexCount();
        mesh.close();
    }
    public static void hostGeometry(PlayerWrapper actor, CapeRenderer renderer, VertexConsumer supplied,
            PoseStack pose, int light, float delta) {
        geometry++;
        renderers.add(renderer);
        if (abortGeometry) throw new IllegalStateException("OWNED_GEOMETRY_ABORT");
        if (geometrySink == null) supplied.addVertex(0, 0, 0);
        else geometrySink.accept(supplied);
    }
    public static void verifyClosed() {
        if (!openMeshes.isEmpty() || !vertices.isEmpty()) throw new AssertionError("Host staging retained output");
    }
}

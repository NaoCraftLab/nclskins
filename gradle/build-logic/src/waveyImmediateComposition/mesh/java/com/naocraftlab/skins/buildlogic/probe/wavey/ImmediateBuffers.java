package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.*;
import java.util.*;
import net.minecraft.client.renderer.*;

public final class ImmediateBuffers {
    private static final Set<MeshData> pending = Collections.newSetFromMap(new IdentityHashMap<>());
    public static MultiBufferSource.BufferSource create() {
        return new Source(ImmediateCapeHost.allocate(ByteBufferBuilder.class));
    }
    public static void emit(VertexConsumer consumer) {
        Builder builder = (Builder) consumer;
        ImmediateCapeHost.check(builder.active, "Geometry consumer not begun by native source");
        builder.emitted = true;
    }
    public static void draw(MeshData mesh) { ImmediateCapeHost.draw(); mesh.close(); }
    public static int pending() { return pending.size(); }
    public static void verifyFixedFoil() {
        for (boolean direct : new boolean[]{false, true}) {
            ImmediateCapeHost.reset();
            MultiBufferSource.BufferSource source = create();
            RenderType foilType = direct ? RenderType.entityGlintDirect() : RenderType.entityGlint();
            RenderType baseType = RenderType.entityTranslucent(net.minecraft.resources.ResourceLocation.tryParse("nclskins:fixed_foil_probe"));
            VertexConsumer[] base = new VertexConsumer[1], foil = new VertexConsumer[1];
            VertexConsumer combined = com.naocraftlab.skins.compat.client.cape.CapeTranslucentFoilMaterial.withFoil(
                    source, () -> { foil[0] = ImmediateCapeHost.lastReturned; return base[0] = source.getBuffer(baseType); }, direct);
            ImmediateCapeHost.check(foil[0] != base[0], "IMMEDIATE_FIXED_FOIL_SHARED_BUILDER");
            combined.addVertex(0, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0);
            ImmediateCapeHost.check(ImmediateCapeHost.vertexWrites == 2 && ImmediateCapeHost.drawn.isEmpty(), "IMMEDIATE_FIXED_FOIL_CLOSED_AFTER_BASE");
            source.endBatch();
            ImmediateCapeHost.check(ImmediateCapeHost.drawn.equals(List.of(baseType, foilType))
                    && ImmediateCapeHost.releases == 2 && pending() == 0, "IMMEDIATE_FIXED_FOIL_NATIVE_DRAIN");
            verifyInactive(foil[0]); verifyInactive(base[0]);
            source.endBatch();
            ImmediateCapeHost.check(ImmediateCapeHost.releases == 2 && pending() == 0, "IMMEDIATE_FIXED_FOIL_DUPLICATE_RELEASE");
        }
        ImmediateCapeHost.reset();
        System.out.println("IMMEDIATE_FIXED_FOIL_PASS direct/entity; actual withFoil -> native base acquisition -> both active vertex writes -> native endBatch/exact release; closed-consumer sensitivity=PASS; pending=0; GPU NOT_RUN");
    }
    public static void verifyInactive(VertexConsumer consumer) {
        int before = ImmediateCapeHost.vertexWrites;
        try {
            consumer.addVertex(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            throw new IllegalStateException("IMMEDIATE_INACTIVE_VERTEX_ACCEPTED");
        } catch (AssertionError expected) {
            ImmediateCapeHost.check("Geometry consumer not begun by native source".equals(expected.getMessage()), "IMMEDIATE_INACTIVE_VERTEX_WRONG_GUARD");
        }
        ImmediateCapeHost.check(ImmediateCapeHost.vertexWrites == before, "IMMEDIATE_INACTIVE_VERTEX_COUNTED");
    }
    private static final class Source extends MultiBufferSource.BufferSource {
        Source(ByteBufferBuilder buffer) {
            super(buffer, new LinkedHashMap<>(Map.of(
                    RenderType.entityGlint(), ImmediateCapeHost.allocate(ByteBufferBuilder.class),
                    RenderType.entityGlintDirect(), ImmediateCapeHost.allocate(ByteBufferBuilder.class))));
        }
        @Override public VertexConsumer getBuffer(RenderType type) {
            ImmediateCapeHost.acquired.add(type);
            VertexConsumer result = super.getBuffer(type); ImmediateCapeHost.lastReturned = result; return result;
        }
    }
    public static final class Builder extends BufferBuilder {
        private boolean active = true, emitted;
        public Builder(ByteBufferBuilder buffer, VertexFormat.Mode mode, VertexFormat format) { super(buffer, mode, format); }
        @Override public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
                float normalX, float normalY, float normalZ) {
            emit(this); ImmediateCapeHost.vertexWrites++;
        }
        @Override public MeshData build() {
            ImmediateCapeHost.check(active, "Native source built inactive builder"); active = false;
            if (!emitted) return null;
            MeshData mesh = new Mesh(); pending.add(mesh); return mesh;
        }
    }
    private static final class Mesh extends MeshData {
        Mesh() { super(null, null); }
        @Override public SortState sortQuads(ByteBufferBuilder buffer, VertexSorting sorting) { return null; }
        @Override public void close() {
            ImmediateCapeHost.check(pending.remove(this), "Mesh released twice"); ImmediateCapeHost.releases++;
        }
    }
}

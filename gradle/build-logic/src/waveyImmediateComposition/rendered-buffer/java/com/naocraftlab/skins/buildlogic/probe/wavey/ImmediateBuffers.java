package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.*;
import java.util.*;
import net.minecraft.client.renderer.*;

public final class ImmediateBuffers {
    private static final Set<BufferBuilder.RenderedBuffer> pending = Collections.newSetFromMap(new IdentityHashMap<>());
    public static MultiBufferSource.BufferSource create() {
        return new Source(ImmediateCapeHost.allocate(Builder.class));
    }
    public static void emit(VertexConsumer consumer) {
        Builder builder = (Builder) consumer;
        ImmediateCapeHost.check(builder.active, "Geometry consumer not begun by native source");
    }
    public static void draw(BufferBuilder.RenderedBuffer buffer) { ImmediateCapeHost.draw(); release(buffer); }
    public static void release(BufferBuilder.RenderedBuffer buffer) {
        ImmediateCapeHost.check(pending.remove(buffer), "Rendered buffer released twice");
        ImmediateCapeHost.releases++;
    }
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
            combined.vertex(0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 1, 0);
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
            consumer.vertex(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            throw new IllegalStateException("IMMEDIATE_INACTIVE_VERTEX_ACCEPTED");
        } catch (AssertionError expected) {
            ImmediateCapeHost.check("Geometry consumer not begun by native source".equals(expected.getMessage()), "IMMEDIATE_INACTIVE_VERTEX_WRONG_GUARD");
        }
        ImmediateCapeHost.check(ImmediateCapeHost.vertexWrites == before, "IMMEDIATE_INACTIVE_VERTEX_COUNTED");
    }
    private static final class Source extends MultiBufferSource.BufferSource {
        Source(BufferBuilder builder) {
            super(builder, new LinkedHashMap<>(Map.of(
                    RenderType.entityGlint(), ImmediateCapeHost.allocate(Builder.class),
                    RenderType.entityGlintDirect(), ImmediateCapeHost.allocate(Builder.class))));
        }
        @Override public VertexConsumer getBuffer(RenderType type) {
            ImmediateCapeHost.acquired.add(type);
            VertexConsumer result = super.getBuffer(type); ImmediateCapeHost.lastReturned = result; return result;
        }
    }
    public static final class Builder extends BufferBuilder {
        private boolean active;
        private Builder() { super(0); }
        @Override public void vertex(float x, float y, float z, float red, float green, float blue, float alpha,
                float u, float v, int overlay, int light, float normalX, float normalY, float normalZ) {
            emit(this); ImmediateCapeHost.vertexWrites++;
        }
        @Override public void begin(VertexFormat.Mode mode, VertexFormat format) {
            ImmediateCapeHost.check(!active, "Native source reused live builder"); active = true;
        }
        @Override public boolean building() { return active; }
        @Override public void setQuadSorting(VertexSorting sorting) {}
        @Override public RenderedBuffer end() {
            ImmediateCapeHost.check(active, "Native source ended inactive builder"); active = false;
            RenderedBuffer buffer = ImmediateCapeHost.allocate(RenderedBuffer.class); pending.add(buffer); return buffer;
        }
    }
}

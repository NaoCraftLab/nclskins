package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.naocraftlab.skins.compat.client.identifier.extraction.NclBakedPlayerRenderState;
import com.naocraftlab.skins.compat.client.identifier.foil.CapeTranslucentFoilMaterial;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.rendertype.*;

public final class ExtractionBakedConsumer {
    private static final class Buffers extends MultiBufferSource.BufferSource {
        final List<RenderType> acquired = new ArrayList<>();
        Buffers(ByteBufferBuilder foilBuffer) { super(null, new LinkedHashMap<>(Map.of(RenderTypes.entityGlint(), foilBuffer))); }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer getBuffer(RenderType type) { acquired.add(type); return super.getBuffer(type); }
    }
    public static void consume(NclBakedPlayerRenderState state, boolean foil) throws Exception {
        try (ByteBufferBuilder foilBuffer = new ByteBufferBuilder(256)) {
            Buffers buffers = new Buffers(foilBuffer);
            Class<?> type = Class.forName("com.naocraftlab.skins.compat.client.identifier.extraction.NclBakedPlayerRenderer$TargetRenderer");
            Object consumer = CapePublicationProbe.allocate(type);
            var field = net.minecraft.client.gui.render.pip.PictureInPictureRenderer.class.getDeclaredField("bufferSource");
            field.setAccessible(true); field.set(consumer, buffers);
            var render = type.getDeclaredMethod("renderToTexture", NclBakedPlayerRenderState.class, PoseStack.class); render.setAccessible(true);
            int draws = BufferedCapeHost.draws, closes = BufferedCapeHost.closes;
            int samples = BufferedCapeHost.foreignVertices + BufferedCapeHost.ownVertices;
            render.invoke(consumer, state, new PoseStack());
            RenderType expected = state.attachmentTexture() == null ? null : CapeTranslucentFoilMaterial.inWorld()
                    ? RenderTypes.entityGlint() : CapeTranslucentFoilMaterial.forTexture(state.attachmentTexture());
            if (buffers.acquired.stream().filter(t -> t == expected).count() != (foil ? 1 : 0)) throw new AssertionError("EXTRACTION_BAKED_NATIVE_FOIL_MISSING_OR_LEAKED");
            int count = (state.attachmentPlayerModel() == null ? 0 : 1) + (state.standaloneEquipment() ? 0 : 1) + (foil && CapeTranslucentFoilMaterial.inWorld() ? 1 : 0);
            System.out.println("EXTRACTION_BAKED_NATIVE_COUNTS expected=" + count + " drawDelta=" + (BufferedCapeHost.draws - draws)
                    + " closeDelta=" + (BufferedCapeHost.closes - closes) + " drawnSamples="
                    + (BufferedCapeHost.foreignVertices + BufferedCapeHost.ownVertices - samples));
            if (BufferedCapeHost.draws - draws != count || BufferedCapeHost.closes - closes != count) throw new AssertionError("EXTRACTION_BAKED_NATIVE_DRAIN_MISSING");
            BufferedCapeHost.verifyClosed();
        }
    }
}

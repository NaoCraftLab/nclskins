package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.compat.client.identifier.extraction.NclBakedPlayerRenderState;
import com.naocraftlab.skins.compat.client.identifier.foil.CapeTranslucentFoilMaterial;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.*;
import net.minecraft.client.renderer.rendertype.*;

public final class ExtractionBakedConsumer {
    public static void consume(NclBakedPlayerRenderState state, boolean foil) throws Exception {
        Class<?> type = Class.forName("com.naocraftlab.skins.compat.client.identifier.extraction.NclBakedPlayerRenderer$TargetRenderer");
        Object consumer = CapePublicationProbe.allocate(type);
        var render = type.getDeclaredMethod("renderToTexture", NclBakedPlayerRenderState.class, PoseStack.class, SubmitNodeCollector.class); render.setAccessible(true);
        SubmitNodeStorage storage = new SubmitNodeStorage(); render.invoke(consumer, state, new PoseStack(), storage);
        int prepared = CapeHost.prepared.size(), draws = CapeHost.draws, vertices = CapeHost.foreignVertices;
        var renderers = new FeatureRendererMap(); renderers.put(ModelFeatureRenderer.TYPE, new ModelFeatureRenderer());
        try (CapeNativeDriver driver = new CapeNativeDriver(renderers)) { driver.drain(storage); }
        RenderType expected = state.attachmentTexture() == null ? null : CapeTranslucentFoilMaterial.inWorld()
                ? RenderTypes.entityGlint() : CapeTranslucentFoilMaterial.forTexture(state.attachmentTexture());
        if (CapeHost.prepared.subList(prepared, CapeHost.prepared.size()).stream().filter(t -> expected != null && t.pipeline() == expected.pipeline()).count() != (foil ? 1 : 0))
            throw new AssertionError("EXTRACTION_BAKED_NATIVE_FOIL_MISSING_OR_LEAKED");
        int count = (state.simpleAttachmentModel() == null ? 0 : 1) + (state.standaloneEquipment() ? 0 : 1) + (foil && CapeTranslucentFoilMaterial.inWorld() ? 1 : 0);
        if (CapeHost.draws == draws || CapeHost.foreignVertices - vertices != count) throw new AssertionError("EXTRACTION_BAKED_NATIVE_DRAIN_MISSING");
        CapeStaged.reset();
    }
}

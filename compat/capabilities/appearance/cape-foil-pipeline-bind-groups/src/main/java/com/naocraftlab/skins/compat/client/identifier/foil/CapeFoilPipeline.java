package com.naocraftlab.skins.compat.client.identifier.foil;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

final class CapeFoilPipeline {
    static RenderPipeline create() {
        RenderPipeline base = RenderPipelines.ENTITY_TRANSLUCENT;
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation("pipeline/nclskins_cape_translucent_foil")
                .withVertexShader(base.getVertexShader())
                .withFragmentShader(Identifier.fromNamespaceAndPath("nclskins", "core/cape_entity_foil"))
                .withShaderDefine("ALPHA_CUTOUT", 0.1f)
                .withShaderDefine("PER_FACE_LIGHTING")
                .withCull(base.isCull())
                .withPolygonMode(base.getPolygonMode());
        builder.withDepthStencilState(base.getDepthStencilState())
                .withColorTargetState(base.getColorTargetState())
                .withPrimitiveTopology(base.getPrimitiveTopology());
        var formats = base.getVertexFormatBindings();
        for (int index = 0; index < formats.length; index++) {
            builder.withVertexBinding(index, formats[index]);
        }
        base.getBindGroupLayouts().forEach(builder::withBindGroupLayout);
        builder.withBindGroupLayout(BindGroupLayout.builder().withSampler("GlintSampler").build());
        return builder.build();
    }

    private CapeFoilPipeline() {}
}

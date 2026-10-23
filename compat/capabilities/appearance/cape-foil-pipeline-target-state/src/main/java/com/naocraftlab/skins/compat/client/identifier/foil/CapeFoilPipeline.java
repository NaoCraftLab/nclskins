package com.naocraftlab.skins.compat.client.identifier.foil;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
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
        builder.withVertexFormat(base.getVertexFormat(), base.getVertexFormatMode());
        base.getSamplers().forEach(builder::withSampler);
        builder.withSampler("GlintSampler");
        builder.withUniform("Globals", UniformType.UNIFORM_BUFFER);
        base.getUniforms().forEach(uniform -> {
            if (uniform.textureFormat() == null) builder.withUniform(uniform.name(), uniform.type());
            else builder.withUniform(uniform.name(), uniform.type(), uniform.textureFormat());
        });
        builder.withDepthStencilState(base.getDepthStencilState())
                .withColorTargetState(base.getColorTargetState());
        return builder.build();
    }

    private CapeFoilPipeline() {}
}

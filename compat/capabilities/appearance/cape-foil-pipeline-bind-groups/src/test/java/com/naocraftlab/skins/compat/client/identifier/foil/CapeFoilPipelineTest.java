package com.naocraftlab.skins.compat.client.identifier.foil;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapeFoilPipelineTest {
    @Test
    void productionPipelineKeepsBaseDepthBlendAndNativeUniformsWithCapeAndGlintSamplers() {
        var base = RenderPipelines.ENTITY_TRANSLUCENT;
        var actual = CapeFoilPipeline.create();
        assertEquals(base.getBindGroupLayouts().size() + 1, actual.getBindGroupLayouts().size());
        assertTrue(actual.getBindGroupLayouts().containsAll(base.getBindGroupLayouts()));
        assertTrue(BindGroupLayout.flattenSamplers(actual.getBindGroupLayouts()).contains("GlintSampler"));
        assertTrue(BindGroupLayout.flattenUniforms(actual.getBindGroupLayouts()).stream()
                .anyMatch(uniform -> uniform.name().equals("Globals")));
        assertEquals(base.getDepthStencilState(), actual.getDepthStencilState());
        assertEquals(base.getColorTargetState(), actual.getColorTargetState());
    }
}

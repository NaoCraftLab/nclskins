package com.naocraftlab.skins.compat.client.identifier.foil;

import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapeFoilPipelineTest {
    @Test
    void productionPipelineKeepsBaseDepthBlendAndNativeUniformsWithCapeAndGlintSamplers() {
        var base = RenderPipelines.ENTITY_TRANSLUCENT;
        var actual = CapeFoilPipeline.create();
        assertEquals(base.getSamplers().size() + 1, actual.getSamplers().size());
        assertTrue(actual.getSamplers().containsAll(base.getSamplers()));
        assertTrue(actual.getSamplers().contains("GlintSampler"));
        assertEquals(base.getUniforms().size() + 1, actual.getUniforms().size());
        assertTrue(actual.getUniforms().containsAll(base.getUniforms()));
        var globals = RenderPipelines.GLINT.getUniforms().stream()
                .filter(uniform -> uniform.name().equals("Globals")).findFirst().orElseThrow();
        assertTrue(actual.getUniforms().contains(globals));
        assertEquals(base.getDepthStencilState(), actual.getDepthStencilState());
        assertEquals(base.getColorTargetState(), actual.getColorTargetState());
    }
}

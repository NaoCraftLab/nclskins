package com.naocraftlab.skins.compat.client.identifier.foil;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapeTranslucentFoilReferenceTest {
    @Test
    void worldExperimentSelectsNativeGlintWithoutReplacingWorldlessDescriptor() {
        var texture = Identifier.fromNamespaceAndPath("nclskins", "synthetic_world_cape");
        var worldless = CapeTranslucentFoilMaterial.forTexture(texture, false);
        assertSame(net.minecraft.client.renderer.rendertype.RenderTypes.entitySolidGlint(texture),
                CapeTranslucentFoilMaterial.forTexture(texture, true));
        assertSame(worldless, CapeTranslucentFoilMaterial.forTexture(texture, false));
        assertNotSame(worldless,
                net.minecraft.client.renderer.rendertype.RenderTypes.entitySolidGlint(texture));
    }

    @Test
    void combinedReferenceKeepsCapeAndGlintInOneNativeEntityPipeline() {
        var texture = Identifier.fromNamespaceAndPath("nclskins", "synthetic_reference");
        var material = CapeTranslucentFoilMaterial.forTexture(texture);
        assertSame(material, CapeTranslucentFoilMaterial.forTexture(texture));
        var samplers = BindGroupLayout.flattenUniforms(material.pipeline().getBindGroupLayouts()).stream()
                .map(BindGroupLayout.UniformDescription::name).toList();
        assertTrue(samplers.contains("Sampler0"));
        assertTrue(samplers.contains("GlintSampler"));
        assertEquals(RenderTypes.entityTranslucent(texture).format(), material.format());
        assertEquals(RenderPipelines.ENTITY_TRANSLUCENT.getDepthStencilState(), material.pipeline().getDepthStencilState());
        assertTrue(material.outline().isPresent());
        assertSame(RenderPipelines.ENTITY_TRANSLUCENT, RenderTypes.entityTranslucent(texture).pipeline());
    }

    @Test
    void descriptorCacheIsBoundedWithoutAffectingPlainMaterials() {
        var first = Identifier.fromNamespaceAndPath("nclskins", "synthetic_first");
        var oldest = CapeTranslucentFoilMaterial.forTexture(first);
        for (int index = 0; index < 257; index++) {
            CapeTranslucentFoilMaterial.forTexture(
                    Identifier.fromNamespaceAndPath("nclskins", "synthetic_" + index));
        }
        assertNotSame(oldest, CapeTranslucentFoilMaterial.forTexture(first));
        assertSame(RenderPipelines.ENTITY_TRANSLUCENT, RenderTypes.entityTranslucent(first).pipeline());
    }
    @Test
    void everyOitPhaseUsesCapeAlphaWithoutGlintOpacityPromotion() throws Exception {
        var field = CapeTranslucentFoilMaterial.class.getDeclaredField("OIT");
        field.setAccessible(true);
        var oit = (OitPipelineSet) field.get(null);
        var fragment = Identifier.fromNamespaceAndPath("nclskins", "core/cape_entity_foil");
        var material = CapeTranslucentFoilMaterial.forTexture(
                Identifier.fromNamespaceAndPath("nclskins", "synthetic_fragment"));
        assertEquals(fragment, material.pipeline().getShaders().get(ShaderType.FRAGMENT));
        assertEquals(fragment, oit.depthBoundsPipeline().getShaders().get(ShaderType.FRAGMENT));
        assertEquals(fragment, oit.transmittancePipeline().getShaders().get(ShaderType.FRAGMENT));
        assertEquals(fragment, oit.accumulatePipeline().getShaders().get(ShaderType.FRAGMENT));
        try (var stream = getClass().getResourceAsStream("/assets/nclskins/shaders/core/cape_entity_foil.fsh")) {
            assertNotNull(stream);
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(source.contains("texture(Sampler0, texCoord0)"));
            assertTrue(source.contains("color.a <= 0.0"));
            assertTrue(source.contains("texture(GlintSampler, texCoordGlint)"));
            assertFalse(source.contains("color.a = max(color.a, GlintAlpha)"));
        }
    }

}

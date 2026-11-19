package com.naocraftlab.skins.compat.client.identifier.foil;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapeTranslucentFoilReferenceTest {
    @Test
    void nativeWorldProviderDoesNotReplaceWorldlessMaterial() {
        var texture = Identifier.fromNamespaceAndPath("nclskins", "synthetic_world_cape");
        var worldless = CapeTranslucentFoilMaterial.forTexture(texture, false);
        assertSame(RenderTypes.entitySolidGlint(texture), CapeTranslucentFoilMaterial.forTexture(texture, true));
        assertSame(worldless, CapeTranslucentFoilMaterial.forTexture(texture, false));
        assertNotSame(RenderTypes.entitySolidGlint(texture), worldless);
    }

    @Test
    void stageSelectorKeepsCapeAlphaAndAddsGlintOnlyToColorPasses() throws Exception {
        var texture = Identifier.fromNamespaceAndPath("nclskins", "synthetic_reference");
        var material = CapeTranslucentFoilMaterial.forTexture(texture, false);
        var plain = RenderTypes.entityTranslucent(texture);
        var provider = pipelines(material);
        var main = provider.apply(null);
        var fragment = Identifier.fromNamespaceAndPath("nclskins", "core/cape_entity_foil");
        assertEquals(plain.format(), material.format());
        assertEquals(pipelines(plain).apply(null).getDepthStencilState(), main.getDepthStencilState());
        assertTrue(material.outline().isPresent());
        assertEquals(fragment, main.getShaders().get(ShaderType.FRAGMENT));
        assertTrue(hasSampler(main, "Sampler0"));
        assertTrue(hasSampler(main, "GlintSampler"));
        for (var stage : OitStage.values()) {
            var pipeline = provider.apply(stage);
            assertNotSame(main, pipeline);
            assertEquals(fragment, pipeline.getShaders().get(ShaderType.FRAGMENT));
            assertTrue(hasSampler(pipeline, "Sampler0"), stage.name());
            assertEquals(stage == OitStage.ACCUMULATE, hasSampler(pipeline, "GlintSampler"), stage.name());
        }
        try (var stream = getClass().getResourceAsStream("/assets/nclskins/shaders/core/cape_entity_foil.fsh")) {
            assertNotNull(stream);
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(source.contains("texture(Sampler0, texCoord0)"));
            assertTrue(source.contains("color.a <= 0.0"));
            assertTrue(source.contains("texture(GlintSampler, texCoordGlint)"));
            assertFalse(source.contains("color.a = max(color.a, GlintAlpha)"));
        }
    }

    @Test
    void descriptorCacheIsBoundedWithoutAffectingPlainMaterials() {
        var first = Identifier.fromNamespaceAndPath("nclskins", "synthetic_first");
        var plain = RenderTypes.entityTranslucent(first);
        var oldest = CapeTranslucentFoilMaterial.forTexture(first, false);
        for (int index = 0; index < 257; index++) {
            CapeTranslucentFoilMaterial.forTexture(Identifier.fromNamespaceAndPath("nclskins", "synthetic_" + index), false);
        }
        assertNotSame(oldest, CapeTranslucentFoilMaterial.forTexture(first, false));
        assertSame(plain, RenderTypes.entityTranslucent(first));
    }

    private static boolean hasSampler(RenderPipeline pipeline, String name) {
        return BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts()).stream()
                .anyMatch(uniform -> uniform.name().equals(name));
    }

    @SuppressWarnings("unchecked")
    private static Function<OitStage, RenderPipeline> pipelines(RenderType material) throws ReflectiveOperationException {
        var state = RenderType.class.getDeclaredField("state");
        state.setAccessible(true);
        var provider = RenderSetup.class.getDeclaredField("pipelineProvider");
        provider.setAccessible(true);
        return (Function<OitStage, RenderPipeline>) provider.get(state.get(material));
    }
}

package com.naocraftlab.skins.compat.client.identifier.foil;

import com.naocraftlab.skins.compat.client.identifier.CapeMaterialSnapshot;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapeTranslucentFoilMaterialTest {
    @Test
    void worldExperimentSelectsNativeBaseWithoutReplacingWorldlessDescriptor() {
        Identifier texture = Identifier.fromNamespaceAndPath("nclskins", "synthetic_world_cape");
        var worldless = CapeTranslucentFoilMaterial.forTexture(texture, false);
        assertSame(RenderTypes.entityTranslucent(texture),
                CapeTranslucentFoilMaterial.forTexture(texture, true));
        assertSame(worldless, CapeTranslucentFoilMaterial.forTexture(texture, false));
        assertNotSame(worldless, RenderTypes.entityTranslucent(texture));
        assertNotSame(worldless.pipeline(), RenderPipelines.ENTITY_TRANSLUCENT);
    }

    @Test
    void packagedFragmentUsesNativeGlobalsAndOneCapeUvTransform() throws Exception {
        try (var stream = getClass().getResourceAsStream("/assets/nclskins/shaders/core/cape_entity_foil.fsh")) {
            assertNotNull(stream);
            String body = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(body.contains("#moj_import <minecraft:globals.glsl>"));
            assertTrue(body.contains("GlintAlpha * texture(GlintSampler, (TextureMat * vec4(texCoord0, 0.0, 1.0)).xy).rgb"));
            assertTrue(body.contains("color.rgb += foil * foil;"));
            assertFalse(body.contains("color.a ="));
        }
    }

    @Test
    void productionDescriptorRetainsNativeEntityGeometryAndOwnsOnlyCapeDraw() {
        Identifier texture = Identifier.fromNamespaceAndPath("nclskins", "synthetic_cape");
        var material = CapeTranslucentFoilMaterial.forTexture(texture);
        assertSame(material, CapeTranslucentFoilMaterial.forTexture(texture));
        assertEquals(RenderPipelines.ENTITY_TRANSLUCENT.getVertexShader(), material.pipeline().getVertexShader());
        assertEquals(Identifier.fromNamespaceAndPath("nclskins", "core/cape_entity_foil"),
                material.pipeline().getFragmentShader());
        assertEquals(RenderTypes.entityTranslucent(texture).format(), material.format());
        assertEquals(RenderTypes.entityTranslucent(texture).sortOnUpload(), material.sortOnUpload());
        assertEquals(RenderTypes.entityTranslucent(texture).affectsCrumbling(), material.affectsCrumbling());
        assertEquals(RenderPipelines.ENTITY_TRANSLUCENT.isCull(), material.pipeline().isCull());
        assertTrue(material.outline().isPresent());
        assertNotSame(material.pipeline(), RenderTypes.entityTranslucent(texture).pipeline());
        assertSame(RenderPipelines.ENTITY_TRANSLUCENT, RenderTypes.entityTranslucent(texture).pipeline());
    }

    @Test
    void boundedDescriptorsEvictWithoutOwningOrReplacingCapeTextures() {
        Identifier first = Identifier.fromNamespaceAndPath("nclskins", "synthetic_first");
        var oldest = CapeTranslucentFoilMaterial.forTexture(first);
        for (int index = 0; index < 257; index++) {
            CapeTranslucentFoilMaterial.forTexture(
                    Identifier.fromNamespaceAndPath("nclskins", "synthetic_" + index));
        }
        assertNotSame(oldest, CapeTranslucentFoilMaterial.forTexture(first));
        assertSame(RenderPipelines.ENTITY_TRANSLUCENT, RenderTypes.entityTranslucent(first).pipeline());
    }

    @Test
    void inspectionSnapshotRejectsElytraPlainAndMismatchedTextures() {
        var validity = com.naocraftlab.skins.client.TextureUseValidity.INVALID;
        assertEquals(new CapeMaterialSnapshot("nclskins:frame0", true, validity),
                CapeMaterialSnapshot.preview("nclskins:frame0", "nclskins:frame0", true, true, validity));
        assertNull(CapeMaterialSnapshot.preview("nclskins:frame0", "nclskins:frame1", true, true, validity));
        assertNull(CapeMaterialSnapshot.preview(null, "nclskins:frame0", true, true, validity));
        assertNull(CapeMaterialSnapshot.preview("nclskins:frame0", "nclskins:frame0", false, true, validity));
        assertNull(CapeMaterialSnapshot.preview("nclskins:frame0", "nclskins:frame0", true, false, validity));
    }
}

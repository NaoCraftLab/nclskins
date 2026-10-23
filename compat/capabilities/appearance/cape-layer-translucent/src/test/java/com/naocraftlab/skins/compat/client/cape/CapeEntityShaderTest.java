package com.naocraftlab.skins.compat.client.cape;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapeEntityShaderTest {
    @Test
    void nativeGlintUniformAndPackagedFragmentUseSquaredStrengthWithoutAlphaPromotion() throws Exception {
        try (var stream = getClass().getResourceAsStream("/assets/nclskins/shaders/core/cape_entity_foil.fsh")) {
            assertNotNull(stream);
            String body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("uniform float GlintAlpha;"));
            assertTrue(body.contains("GlintAlpha * texture(Sampler3, (TextureMat * vec4(texCoord0, 0.0, 1.0)).xy).rgb"));
            assertTrue(body.contains("color.rgb += foil * foil;"));
            assertFalse(body.contains("color.a ="));
        }
    }

    @Test
    void packagedShaderUsesNativeEntityVertexWithIndependentOverlayLightAndFoilSamplers() throws Exception {
        try (var stream = getClass().getResourceAsStream("/assets/nclskins/shaders/core/cape_entity_foil.json")) {
            assertNotNull(stream);
            var shader = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("rendertype_entity_translucent", shader.get("vertex").getAsString());
            assertEquals("nclskins:cape_entity_foil", shader.get("fragment").getAsString());
            var samplers = new HashSet<String>();
            shader.getAsJsonArray("samplers").forEach(value -> samplers.add(value.getAsJsonObject().get("name").getAsString()));
            assertEquals(java.util.Set.of("Sampler0", "Sampler1", "Sampler2", "Sampler3"), samplers);
            var uniforms = new HashSet<String>();
            shader.getAsJsonArray("uniforms").forEach(value -> uniforms.add(value.getAsJsonObject().get("name").getAsString()));
            assertTrue(uniforms.containsAll(java.util.Set.of("TextureMat", "Light0_Direction", "Light1_Direction", "FogShape", "GlintAlpha")));
        }
    }
}

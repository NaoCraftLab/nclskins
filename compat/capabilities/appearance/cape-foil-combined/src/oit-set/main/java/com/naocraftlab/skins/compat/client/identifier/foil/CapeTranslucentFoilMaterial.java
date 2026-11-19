package com.naocraftlab.skins.compat.client.identifier.foil;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.resources.Identifier;

public final class CapeTranslucentFoilMaterial {
    private static final int MAX_CACHED_TEXTURES = 256;
    private static final Identifier FRAGMENT = Identifier.fromNamespaceAndPath("nclskins", "core/cape_entity_foil");
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(
                    RenderPipelines.ENTITY_SNIPPET, RenderPipelines.GLINT_SNIPPET)
            .withLocation("pipeline/nclskins_cape_translucent_foil")
            .withFragmentShader(FRAGMENT)
            .withShaderDefine("ALPHA_CUTOUT", 0.1f)
            .withShaderDefine("PER_FACE_LIGHTING")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withCull(false)
            .build();
    @SuppressWarnings("deprecation")
    private static final OitPipelineSet OIT = OitPipelineSet.builder(
                    "nclskins_cape_translucent_foil",
                    RenderPipeline.builder(RenderPipelines.OIT_ENTITY_SNIPPET).withCull(false))
            .withDepthBoundsModifier(builder -> builder.withFragmentShader(FRAGMENT))
            .withTransmittanceModifier(builder -> builder.withFragmentShader(FRAGMENT))
            .withAccumulateModifier(builder -> builder
                    .withSnippet(RenderPipelines.GLINT_SNIPPET)
                    .withFragmentShader(FRAGMENT)
                    .withShaderDefine("PER_FACE_LIGHTING")
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER2))
            .build();
    private static final Map<Identifier, RenderType> TYPES = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Identifier, RenderType> eldest) {
            return size() > MAX_CACHED_TEXTURES;
        }
    };

    public static RenderType forTexture(Identifier texture) {
        Minecraft minecraft = Minecraft.getInstance();
        return forTexture(texture, minecraft != null && minecraft.level != null);
    }

    static synchronized RenderType forTexture(Identifier texture, boolean inWorld) {
        if (inWorld) return RenderTypes.entitySolidGlint(texture);
        return TYPES.computeIfAbsent(texture, CapeTranslucentFoilMaterial::create);
    }

    private static RenderType create(Identifier texture) {
        RenderSetup setup = RenderSetup.builder(PIPELINE)
                .setOitPipelines(OIT)
                .withTexture("Sampler0", texture)
                .withTexture("GlintSampler", ItemFeatureRenderer.ENCHANTED_GLINT_ITEM)
                .setTextureTransform(TextureTransform.ENTITY_GLINT_TEXTURING)
                .useLightmap()
                .useOverlay()
                .affectsCrumbling()
                .sortOnUpload()
                .setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE)
                .createRenderSetup();
        return RenderType.create("nclskins_cape_translucent_foil", setup);
    }

    private CapeTranslucentFoilMaterial() {}
}

package com.naocraftlab.skins.compat.client.identifier.foil;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.pipeline.RenderPipelines;
import net.minecraft.client.renderer.pipeline.variant.EntityRenderPipelineVariant;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.resources.Identifier;

public final class CapeTranslucentFoilMaterial {
    private static final int MAX_CACHED_TEXTURES = 256;
    private static final Identifier FRAGMENT = Identifier.fromNamespaceAndPath("nclskins", "core/cape_entity_foil");

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
        RenderSetup setup = RenderSetup.builder(WorldlessPipelines::forStage)
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

    private static final class WorldlessPipelines {
        private static final RenderPipeline MAIN = create("main", null, true);
        private static final RenderPipeline DEPTH_BOUNDS = create(
                "depth_bounds", RenderPipelines.OIT_DEPTH_BOUNDS_SNIPPET, false);
        private static final RenderPipeline TRANSMITTANCE = create(
                "transmittance", RenderPipelines.OIT_TRANSMITTANCE_SNIPPET, false);
        private static final RenderPipeline ACCUMULATE = create(
                "accumulate", RenderPipelines.OIT_ACCUMULATE_SNIPPET, true);

        private static RenderPipeline forStage(OitStage stage) {
            return stage == null ? MAIN : switch (stage) {
                case DEPTH_BOUNDS -> DEPTH_BOUNDS;
                case TRANSMITTANCE -> TRANSMITTANCE;
                case ACCUMULATE -> ACCUMULATE;
            };
        }

        private static RenderPipeline create(String pass, RenderPipeline.Snippet oit, boolean glint) {
            var builder = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("nclskins", "pipeline/cape_translucent_foil/" + pass))
                    .withVertexShader("core/entity")
                    .withFragmentShader(FRAGMENT)
                    .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                    .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                    .withBindGroupLayout(BindGroupLayouts.FOG)
                    .withBindGroupLayout(BindGroupLayouts.LIGHTING)
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                    .withShaderDefine("ALPHA_CUTOUT", RenderPipelines.ALPHA_CUTOUT_THRESHOLD_DEFAULT)
                    .withVertexBinding(0, DefaultVertexFormat.ENTITY)
                    .withPrimitiveTopology(PrimitiveTopology.QUADS)
                    .withCull(false);
            if (glint) {
                builder.withSnippet(RenderPipelines.GLINT_SNIPPET)
                        .withShaderDefine("PER_FACE_LIGHTING")
                        .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                        .withBindGroupLayout(BindGroupLayouts.SAMPLER2);
            }
            if (oit == null) {
                builder.withColorTargetState(EntityRenderPipelineVariant.TRANSLUCENT.nonOitColorTargetState())
                        .withDepthStencilState(EntityRenderPipelineVariant.TRANSLUCENT.nonOitDepthStencilState());
            } else {
                builder.withSnippet(oit);
            }
            return builder.build();
        }
    }

    private CapeTranslucentFoilMaterial() {}
}

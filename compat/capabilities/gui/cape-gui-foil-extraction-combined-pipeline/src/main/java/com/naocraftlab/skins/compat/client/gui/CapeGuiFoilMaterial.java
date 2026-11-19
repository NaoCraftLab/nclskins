package com.naocraftlab.skins.compat.client.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.GuiPipeline;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.pipeline.RenderPipelines;
import net.minecraft.client.renderer.pipeline.variant.GuiRenderPipelineVariant;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

public final class CapeGuiFoilMaterial {
    private static final GuiPipeline PIPELINE = GuiPipeline.of(() -> RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withLocation("pipeline/nclskins_gui_cape_foil")
            .withVertexShader("core/position_tex_color")
            .withFragmentShader(Identifier.fromNamespaceAndPath("nclskins", "core/gui_cape_foil"))
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(GuiRenderPipelineVariant.TEXTURED.colorTargetState())
            .build());

    public static void draw(GuiGraphicsExtractor graphics, Identifier cape, int x, int y, int width, int height,
                            float u, float v, int sourceWidth, int sourceHeight, int textureWidth, int textureHeight) {
        var textures = Minecraft.getInstance().getTextureManager();
        var capeTexture = textures.getTexture(cape);
        var glintTexture = textures.getTexture(ItemFeatureRenderer.ENCHANTED_GLINT_ITEM);
        var setup = TextureSetup.doubleTexture(capeTexture.textureView(), capeTexture.sampler(),
                glintTexture.textureView(), glintTexture.sampler());
        graphics.guiRenderState.addGuiElement(new BlitRenderState(PIPELINE, setup,
                new Matrix3x2f(graphics.pose()), x, y, x + width, y + height,
                u / textureWidth, (u + sourceWidth) / textureWidth,
                v / textureHeight, (v + sourceHeight) / textureHeight,
                -1, graphics.scissorStack.peek()));
    }

    private CapeGuiFoilMaterial() {}
}

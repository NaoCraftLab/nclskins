package com.naocraftlab.skins.compat.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public final class CapeGuiFoilMaterial {
    public static final ResourceLocation SHADER = new ResourceLocation("nclskins", "gui_cape_foil");
    private static final ResourceLocation GLINT =
            new ResourceLocation("minecraft", "textures/misc/enchanted_glint_item.png");
    private static ShaderInstance shader;

    public static void shaderLoaded(ShaderInstance loaded) {
        shader = loaded;
    }

    public static void draw(GuiGraphics graphics, ResourceLocation cape, int x, int y, int width, int height,
                            float u, float v, int sourceWidth, int sourceHeight, int textureWidth, int textureHeight) {
        ShaderInstance current = shader;
        if (current == null) {
            graphics.blit(cape, x, y, width, height, u, v, sourceWidth, sourceHeight, textureWidth, textureHeight);
            return;
        }
        graphics.flush();
        current.getUniform("FoilTime").set((System.nanoTime() / 1_000_000L % 100_000L) / 100_000.0F);
        RenderSystem.setShader(() -> current);
        RenderSystem.setShaderTexture(0, cape);
        RenderSystem.setShaderTexture(1, GLINT);
        RenderSystem.enableBlend();
        float u0 = u / textureWidth;
        float u1 = (u + sourceWidth) / textureWidth;
        float v0 = v / textureHeight;
        float v1 = (v + sourceHeight) / textureHeight;
        Matrix4f pose = graphics.pose().last().pose();
        BufferBuilder vertices = Tesselator.getInstance().getBuilder();
        vertices.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        vertices.vertex(pose, x, y + height, 0).uv(u0, v1).color(255, 255, 255, 255).endVertex();
        vertices.vertex(pose, x + width, y + height, 0).uv(u1, v1).color(255, 255, 255, 255).endVertex();
        vertices.vertex(pose, x + width, y, 0).uv(u1, v0).color(255, 255, 255, 255).endVertex();
        vertices.vertex(pose, x, y, 0).uv(u0, v0).color(255, 255, 255, 255).endVertex();
        BufferUploader.drawWithShader(vertices.end());
    }

    private CapeGuiFoilMaterial() {}
}

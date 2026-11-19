package com.naocraftlab.skins.compat.client.identifier.extraction;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

final class GuiExtraction {
    private static final int OFFSCREEN_MOUSE_COORDINATE = -1_000_000;

    private GuiExtraction() {}

    static boolean isVisible(AbstractWidget widget) {
        return widget.visible;
    }

    static void setVisible(AbstractWidget widget, boolean visible) {
        widget.visible = visible;
    }

    static void extract(Renderable renderable, GuiGraphicsExtractor graphics,
            int mouseX, int mouseY, float partialTick, boolean receivesPointer) {
        renderable.extractRenderState(graphics,
                receivesPointer ? mouseX : OFFSCREEN_MOUSE_COORDINATE,
                receivesPointer ? mouseY : OFFSCREEN_MOUSE_COORDINATE,
                partialTick);
    }

    static void blit(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, float u, float v,
            int width, int height, int textureWidth, int textureHeight) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v,
                width, height, textureWidth, textureHeight);
    }

    static void blit(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, float u, float v,
            int width, int height, int textureWidth, int textureHeight, int color) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v,
                width, height, textureWidth, textureHeight, color);
    }

    static void blit(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, float u, float v,
            int width, int height, int regionWidth, int regionHeight,
            int textureWidth, int textureHeight, int color) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v,
                width, height, regionWidth, regionHeight, textureWidth, textureHeight, color);
    }

    static void blitSprite(GuiGraphicsExtractor graphics, Identifier sprite,
            int x, int y, int width, int height) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height);
    }

    abstract static class ScreenHost extends Screen {
        protected ScreenHost(Component title) {
            super(title);
        }

        @Override
        public final void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            prepareBackground(mouseX, mouseY);
            super.extractBackground(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public final void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            extractFrame(graphics, mouseX, mouseY, partialTick);
        }

        protected abstract void prepareBackground(int mouseX, int mouseY);

        protected abstract void extractFrame(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick);
    }

    abstract static class UnderlayHost implements Renderable {
        @Override
        public final void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            drawUnderlay(graphics, mouseX, mouseY);
        }

        protected abstract void drawUnderlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY);
    }

    abstract static class ButtonHost extends AbstractButton {
        protected ButtonHost(int x, int y, int width, int height, Component label) {
            super(x, y, width, height, label);
        }

        @Override
        protected final void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            drawContents(graphics);
        }

        protected abstract void drawContents(GuiGraphicsExtractor graphics);
    }

    abstract static class ScrollAreaHost extends AbstractScrollArea {
        protected ScrollAreaHost(Component label) {
            super(0, 0, 1, 1, label, defaultSettings(0));
        }

        @Override
        protected final void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            if (shouldExtractScrollbar()) {
                extractScrollbar(graphics, mouseX, mouseY);
            }
        }

        protected abstract boolean shouldExtractScrollbar();
    }
}

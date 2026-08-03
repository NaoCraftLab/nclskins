package com.naocraftlab.skins.mc1211;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.ViewSpec;


final class NclSkinsVanillaScreenStyle {
    static final int SCROLLBAR_SIZE = 6;

    private static final ResourceLocation MENU_LIST_BACKGROUND =
            ResourceLocation.withDefaultNamespace("textures/gui/menu_list_background.png");
    private static final ResourceLocation INWORLD_MENU_LIST_BACKGROUND =
            ResourceLocation.withDefaultNamespace("textures/gui/inworld_menu_list_background.png");
    private static final ResourceLocation SCROLLER =
            ResourceLocation.withDefaultNamespace("widget/scroller");
    private static final ResourceLocation SCROLLER_BACKGROUND =
            ResourceLocation.withDefaultNamespace("widget/scroller_background");

    private NclSkinsVanillaScreenStyle() {}

    static void renderListPanel(GuiGraphics graphics, int left, int top, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        ResourceLocation texture = Minecraft.getInstance().level == null
                ? MENU_LIST_BACKGROUND
                : INWORLD_MENU_LIST_BACKGROUND;
        RenderSystem.enableBlend();
        graphics.blit(
                texture,
                left,
                top,
                left + width,
                top + height,
                width,
                height,
                32,
                32);
        RenderSystem.disableBlend();
    }

    static void renderFramePanel(
            GuiGraphics graphics, Bounds bounds, ViewSpec.Panel.Style style) {
        renderListPanel(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height());
        boolean inWorld = Minecraft.getInstance().level != null;
        ResourceLocation separator;
        int separatorY;
        if (style == ViewSpec.Panel.Style.VANILLA_HEADER) {
            separator = inWorld ? Screen.INWORLD_HEADER_SEPARATOR : Screen.HEADER_SEPARATOR;
            separatorY = bounds.bottom() - 2;
        } else if (style == ViewSpec.Panel.Style.VANILLA_FOOTER) {
            separator = inWorld ? Screen.INWORLD_FOOTER_SEPARATOR : Screen.FOOTER_SEPARATOR;
            separatorY = bounds.y();
        } else {
            throw new IllegalArgumentException("Not a frame panel: " + style);
        }
        RenderSystem.enableBlend();
        graphics.blit(separator, bounds.x(), separatorY, 0.0F, 0.0F, bounds.width(), 2, 32, 2);
        RenderSystem.disableBlend();
    }

    static void renderHorizontalScrollbar(
            GuiGraphics graphics,
            int left,
            int top,
            int width,
            int thumbLeft,
            int thumbWidth) {
        RenderSystem.enableBlend();
        graphics.blitSprite(SCROLLER_BACKGROUND, left, top, width, SCROLLBAR_SIZE);
        graphics.blitSprite(SCROLLER, thumbLeft, top, thumbWidth, SCROLLBAR_SIZE);
        RenderSystem.disableBlend();
    }
}

package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup;

import java.util.Optional;
import com.naocraftlab.skins.runtime.VerticalTabStyle;
import com.mojang.math.Axis;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.VanillaListSurface;
import com.naocraftlab.skins.runtime.ViewSpec;


final class NclSkinsVanillaScreenStyle {
    static final int SCROLLBAR_SIZE = 6;

    private static final ResourceLocation INWORLD_MENU_BACKGROUND =
            ResourceLocation.withDefaultNamespace("textures/gui/inworld_menu_background.png");
    private static final ResourceLocation MENU_BACKGROUND =
            ResourceLocation.withDefaultNamespace("textures/gui/menu_background.png");
    private static final ResourceLocation MENU_LIST_BACKGROUND =
            ResourceLocation.withDefaultNamespace("textures/gui/menu_list_background.png");
    private static final ResourceLocation INWORLD_MENU_LIST_BACKGROUND =
            ResourceLocation.withDefaultNamespace("textures/gui/inworld_menu_list_background.png");
    private static final ResourceLocation SCROLLER =
            ResourceLocation.withDefaultNamespace("widget/scroller");
    private static final ResourceLocation SCROLLER_BACKGROUND =
            ResourceLocation.withDefaultNamespace("widget/scroller_background");

    private NclSkinsVanillaScreenStyle() {}

    static void renderListPanel(
            GuiGraphics graphics,
            int left,
            int top,
            int width,
            int height,
            int textureU,
            int textureV) {
        if (width <= 0 || height <= 0) {
            return;
        }
        boolean inWorld = Minecraft.getInstance().level != null;
        VanillaListSurface.Boundaries boundaries = VanillaListSurface.boundaries(
                new Bounds(left, top, width, height));
        RenderSystem.enableBlend();
        renderListBackground(
                graphics, left, top, width, height, textureU, textureV, inWorld);
        graphics.blit(
                inWorld ? Screen.INWORLD_HEADER_SEPARATOR : Screen.HEADER_SEPARATOR,
                left, boundaries.topY(), 0.0F, 0.0F, width, 2, 32, 2);
        graphics.blit(
                inWorld ? Screen.INWORLD_FOOTER_SEPARATOR : Screen.FOOTER_SEPARATOR,
                left, boundaries.bottomY(), 0.0F, 0.0F, width, 2, 32, 2);
        RenderSystem.disableBlend();
    }

    private static void renderListBackground(
            GuiGraphics graphics,
            int left,
            int top,
            int width,
            int height,
            int textureU,
            int textureV,
            boolean inWorld) {
        graphics.blit(
                inWorld ? INWORLD_MENU_LIST_BACKGROUND : MENU_LIST_BACKGROUND,
                left, top, textureU, textureV, width, height, 32, 32);
    }

    static void renderFramePanel(
            GuiGraphics graphics, Bounds bounds, ViewSpec.Panel.Style style) {
        boolean inWorld = Minecraft.getInstance().level != null;
        RenderSystem.enableBlend();
        renderListBackground(
                graphics,
                bounds.x(),
                bounds.y(),
                bounds.width(),
                bounds.height(),
                bounds.right(),
                bounds.bottom(),
                inWorld);
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
    static void renderTabContentPanel(
            GuiGraphics graphics,
            Bounds bounds,
            Optional<Bounds> verticalTabGroupBounds) {
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }
        boolean inWorld = Minecraft.getInstance().level != null;
        RenderSystem.enableBlend();
        VerticalTabStyle.backgroundSegments(bounds)
                .forEach(segment -> renderMenuBackground(
                        graphics,
                        segment.x(),
                        segment.y(),
                        segment.width(),
                        segment.height(),
                        inWorld));
        verticalTabGroupBounds
                .map(group -> VerticalTabStyle.separatorSegments(bounds, group))
                .orElseGet(() -> java.util.List.of(new Bounds(
                        bounds.x(), bounds.y(), Math.min(2, bounds.width()), bounds.height())))
                .forEach(segment -> renderVerticalSeparator(graphics, segment, inWorld));
        RenderSystem.disableBlend();
    }

    static void renderSelectedTabUnderlay(
            GuiGraphics graphics, int x, int y, int width, int height) {
        if (width <= 4 || height <= 4) {
            return;
        }
        Bounds underlay = VerticalTabStyle.selectedUnderlay(new Bounds(x, y, width, height));
        RenderSystem.enableBlend();
        renderMenuBackground(
                graphics,
                underlay.x(),
                underlay.y(),
                underlay.width(),
                underlay.height(),
                Minecraft.getInstance().level != null);
        RenderSystem.disableBlend();
    }

    private static void renderVerticalSeparator(
            GuiGraphics graphics, Bounds segment, boolean inWorld) {
        graphics.pose().pushPose();
        graphics.pose().translate(segment.x(), segment.bottom(), 0.0F);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(-90.0F));
        graphics.blit(
                inWorld ? Screen.INWORLD_HEADER_SEPARATOR : Screen.HEADER_SEPARATOR,
                0, 0, 0.0F, 0.0F, segment.height(), segment.width(), 32, 2);
        graphics.pose().popPose();
    }

    private static void renderMenuBackground(
            GuiGraphics graphics,
            int left,
            int top,
            int width,
            int height,
            boolean inWorld) {
        graphics.blit(
                inWorld ? INWORLD_MENU_BACKGROUND : MENU_BACKGROUND,
                left, top, left, top, width, height, 32, 32);
    }

}

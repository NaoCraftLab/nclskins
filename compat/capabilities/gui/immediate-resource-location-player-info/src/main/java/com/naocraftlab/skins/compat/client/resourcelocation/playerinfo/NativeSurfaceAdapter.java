package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

import java.util.Optional;
import com.mojang.math.Axis;
import com.mojang.blaze3d.systems.RenderSystem;
import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.ViewSpec;
import com.naocraftlab.skins.runtime.VerticalTabStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

final class NativeSurfaceAdapter {
    private static final net.minecraft.resources.ResourceLocation TAB_BUTTON =
            new net.minecraft.resources.ResourceLocation("textures/gui/tab_button.png");

    private NativeSurfaceAdapter() {}

    static void renderBackground(
            GuiGraphics graphics, ViewSpec view, int width, int height, Runnable dirtBackground) {
        switch (view.surfaceRole()) {
            case IMPORT -> renderChatOptionsBackground(graphics, width, height, dirtBackground);
            case TABBED_MENU -> renderCreateWorldBackground(graphics, width, height);
            case WORKSPACE -> renderPresetEditorBackground(graphics, view, dirtBackground);
            case MENU -> dirtBackground.run();
        }
    }

    static boolean shouldRenderFramePanel(ViewSpec view, ViewSpec.Panel panel) {
        return view.surfaceRole() != ViewSpec.SurfaceRole.IMPORT
                || (panel.style() != ViewSpec.Panel.Style.VANILLA_HEADER
                        && panel.style() != ViewSpec.Panel.Style.VANILLA_FOOTER);
    }

    static Bounds resolveTextBounds(ViewSpec view, ViewSpec.Text text) {
        Bounds bounds = text.bounds();
        if (view.surfaceRole() == ViewSpec.SurfaceRole.IMPORT
                && ("external.title".equals(text.id())
                        || "external.review.title".equals(text.id()))) {
            return new Bounds(bounds.x(), 20, bounds.width(), bounds.height());
        }
        return bounds;
    }

    static void renderPanel(
            GuiGraphics graphics,
            ViewSpec.Panel panel,
            int textureU,
            int textureV,
            Optional<Bounds> selectedVerticalTabBounds,
            Optional<Bounds> verticalTabGroupBounds) {
        Bounds bounds = panel.bounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }
        if (panel.style() == ViewSpec.Panel.Style.VANILLA_LIST) {
            graphics.fill(
                    bounds.x(),
                    bounds.y(),
                    bounds.right(),
                    bounds.bottom(),
                    0x80000000);
            return;
        }
        if (panel.style() == ViewSpec.Panel.Style.VANILLA_TAB_CONTENT) {
            VerticalTabStyle.backgroundSegments(bounds)
                    .forEach(segment -> renderLightDirtBackground(graphics, segment));
            RenderSystem.enableBlend();
            selectedVerticalTabBounds
                    .map(tab -> VerticalTabStyle.separatorSegments(bounds, tab))
                    .orElseGet(() -> java.util.List.of(new Bounds(
                            bounds.x(), bounds.y(), Math.min(2, bounds.width()), bounds.height())))
                    .forEach(segment -> renderVerticalSeparator(graphics, segment));
            RenderSystem.disableBlend();
            return;
        }
        graphics.setColor(0.125F, 0.125F, 0.125F, 1.0F);
        graphics.blit(
                Screen.BACKGROUND_LOCATION,
                bounds.x(),
                bounds.y(),
                0.0F,
                0.0F,
                bounds.width(),
                bounds.height(),
                32,
                32);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    static void renderScrollbar(GuiGraphics graphics, ViewSpec.Scrollbar scrollbar) {
        com.naocraftlab.skins.runtime.Bounds track = scrollbar.track();
        com.naocraftlab.skins.runtime.Bounds thumb = scrollbar.thumb();
        graphics.fill(track.x(), track.y(), track.right(), track.bottom(), 0xFF000000);
        graphics.fill(thumb.x(), thumb.y(), thumb.right(), thumb.bottom(), 0xFF808080);
        graphics.fill(
                thumb.x(),
                thumb.y(),
                Math.max(thumb.x(), thumb.right() - 1),
                Math.max(thumb.y(), thumb.bottom() - 1),
                0xFFC0C0C0);
    }

    static void renderVerticalTab(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            int height,
            boolean selected,
            boolean highlighted,
        boolean active) {
        int textureY = selected ? (highlighted ? 24 : 0) : (highlighted ? 72 : 48);
        Bounds underlay = VerticalTabStyle.underlay(
                new Bounds(x, y, width, height), selected);
        renderLightDirtBackground(graphics, underlay);
        Bounds edge = VerticalTabStyle.rightEdge(new Bounds(x, y, width, height));
        graphics.enableScissor(x, y, edge.x(), edge.bottom());
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(x, y + height, 0.0F);
            graphics.pose().mulPose(Axis.ZP.rotationDegrees(-90.0F));
            graphics.blitNineSliced(
                    TAB_BUTTON,
                    0,
                    0,
                    height,
                    width,
                    2,
                    2,
                    2,
                    0,
                    130,
                    24,
                    0,
                    textureY);
        } finally {
            graphics.pose().popPose();
            graphics.disableScissor();
        }
    }

    private static void renderLightDirtBackground(GuiGraphics graphics, Bounds bounds) {
        graphics.blit(
                CreateWorldScreen.LIGHT_DIRT_BACKGROUND,
                bounds.x(),
                bounds.y(),
                (float) bounds.x(),
                (float) bounds.y(),
                bounds.width(),
                bounds.height(),
                32,
                32);
    }

    private static void renderVerticalSeparator(GuiGraphics graphics, Bounds segment) {
        graphics.pose().pushPose();
        graphics.pose().translate(segment.x(), segment.bottom(), 0.0F);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(-90.0F));
        graphics.blit(
                CreateWorldScreen.HEADER_SEPERATOR,
                0,
                0,
                0.0F,
                0.0F,
                segment.height(),
                segment.width(),
                32,
                2);
        graphics.pose().popPose();
    }

    private static void renderChatOptionsBackground(GuiGraphics graphics, int width, int height, Runnable dirtBackground) {
        dirtBackground.run();
        int top = 32;
        int bottom = Math.max(top, height - 32);
        graphics.setColor(0.125F, 0.125F, 0.125F, 1.0F);
        graphics.blit(
                Screen.BACKGROUND_LOCATION,
                0,
                top,
                (float) width,
                (float) bottom,
                width,
                bottom - top,
                32,
                32);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.fillGradient(0, top, width, top + 4, 0xFF000000, 0x00000000);
        graphics.fillGradient(0, bottom - 4, width, bottom, 0x00000000, 0xFF000000);
    }

    private static void renderCreateWorldBackground(GuiGraphics graphics, int width, int height) {
        graphics.blit(
                CreateWorldScreen.LIGHT_DIRT_BACKGROUND,
                0,
                0,
                0.0F,
                0.0F,
                width,
                height,
                32,
                32);
        RenderSystem.enableBlend();
        graphics.blit(
                CreateWorldScreen.FOOTER_SEPERATOR,
                0,
                height - 38,
                0.0F,
                0.0F,
                width,
                2,
                32,
                2);
        RenderSystem.disableBlend();
    }

    private static void renderPresetEditorBackground(GuiGraphics graphics, ViewSpec view, Runnable dirtBackground) {
        dirtBackground.run();
        view.panels().stream()
                .filter(panel -> panel.style() == ViewSpec.Panel.Style.VANILLA_TAB_CONTENT)
                .map(ViewSpec.Panel::bounds)
                .findFirst()
                .ifPresent(bounds -> graphics.fill(
                        0,
                        bounds.y(),
                        bounds.x(),
                        bounds.bottom(),
                        0x40000000));
    }
}

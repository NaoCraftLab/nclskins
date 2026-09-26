package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.ViewSpec;
import com.naocraftlab.skins.runtime.VerticalTabStyle;
import com.naocraftlab.skins.runtime.VanillaListSurface;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import static net.minecraft.client.gui.screens.Screen.HEADER_SEPARATOR;
import static net.minecraft.client.gui.screens.Screen.INWORLD_HEADER_SEPARATOR;
import static net.minecraft.client.gui.screens.Screen.FOOTER_SEPARATOR;
import static net.minecraft.client.gui.screens.Screen.INWORLD_FOOTER_SEPARATOR;
import net.minecraft.client.gui.GuiGraphicsExtractor;

final class NativeSurfaceAdapter {
    private static final Identifier MENU_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/menu_background.png");
    private static final Identifier INWORLD_MENU_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/inworld_menu_background.png");
    private static final Identifier MENU_LIST_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/menu_list_background.png");
    private static final Identifier INWORLD_MENU_LIST_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/inworld_menu_list_background.png");
    private static final Identifier TAB_HEADER_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/tab_header_background.png");

    private NativeSurfaceAdapter() {}

    static void drawVanillaListPanel(
            GuiGraphicsExtractor graphics, ViewSpec view, ViewSpec.Panel panel) {
        Bounds bounds = panel.bounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }
        Identifier background = Minecraft.getInstance().level == null
                ? MENU_LIST_BACKGROUND
                : INWORLD_MENU_LIST_BACKGROUND;
        VanillaListSurface.Sample sample = VanillaListSurface.sample(view, panel);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                background,
                bounds.x(),
                bounds.y(),
                sample.u(),
                sample.v(),
                bounds.width(),
                bounds.height(),
                32,
                32);
        Identifier top = Minecraft.getInstance().level == null ? HEADER_SEPARATOR : INWORLD_HEADER_SEPARATOR;
        Identifier bottom = Minecraft.getInstance().level == null ? FOOTER_SEPARATOR : INWORLD_FOOTER_SEPARATOR;
        VanillaListSurface.Boundaries boundaries = VanillaListSurface.boundaries(bounds);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                top,
                bounds.x(), boundaries.topY(), 0.0F, 0.0F, bounds.width(), 2, 32, 2);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                bottom,
                bounds.x(), boundaries.bottomY(),
                0.0F, 0.0F, bounds.width(), 2, 32, 2);
    }

    static void drawVanillaTabContentPanel(
            GuiGraphicsExtractor graphics, ViewSpec view, ViewSpec.Panel panel) {
        Bounds bounds = panel.bounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }
        Identifier background = Minecraft.getInstance().level == null
                ? MENU_BACKGROUND
                : INWORLD_MENU_BACKGROUND;
        Identifier separator = Minecraft.getInstance().level == null
                ? HEADER_SEPARATOR
                : INWORLD_HEADER_SEPARATOR;
        Optional<Bounds> selectedVerticalTabBounds = VerticalTabStyle.selectedBounds(view);
        VerticalTabStyle.backgroundSegments(bounds)
                .forEach(segment -> graphics.blit(
                        RenderPipelines.GUI_TEXTURED,
                        background,
                        segment.x(),
                        segment.y(),
                        segment.x(),
                        segment.y(),
                        segment.width(),
                        segment.height(),
                        32,
                        32));
        selectedVerticalTabBounds
                .map(tab -> VerticalTabStyle.separatorSegments(bounds, tab))
                .orElseGet(() -> List.of(new Bounds(
                        bounds.x(), bounds.y(), Math.min(2, bounds.width()), bounds.height())))
                .forEach(segment -> {
                    graphics.pose().pushMatrix();
                    graphics.pose().translate(segment.x(), segment.bottom());
                    graphics.pose().rotate((float) (-Math.PI / 2.0));
                    graphics.blit(
                            RenderPipelines.GUI_TEXTURED,
                            separator,
                            0, 0, 0.0F, 0.0F,
                            segment.height(), segment.width(), 32, 2);
                    graphics.pose().popMatrix();
                });
    }

    static void drawFrameBackgrounds(GuiGraphicsExtractor graphics, ViewSpec view) {
        Identifier background = Minecraft.getInstance().level == null
                ? MENU_LIST_BACKGROUND
                : INWORLD_MENU_LIST_BACKGROUND;
        for (ViewSpec.Panel panel : view.panels()) {
            if (panel.style() != ViewSpec.Panel.Style.VANILLA_HEADER
                    && panel.style() != ViewSpec.Panel.Style.VANILLA_FOOTER) {
                continue;
            }
            Bounds bounds = panel.bounds();
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    background,
                    bounds.x(),
                    bounds.y(),
                    0.0F,
                    (float) bounds.y(),
                    bounds.width(),
                    bounds.height(),
                    32,
                    32);
        }
    }

    static void drawFrameSeparators(GuiGraphicsExtractor graphics, ViewSpec view, int width, int height) {
        Identifier header = Minecraft.getInstance().level == null ? HEADER_SEPARATOR : INWORLD_HEADER_SEPARATOR;
        Identifier footer = Minecraft.getInstance().level == null ? FOOTER_SEPARATOR : INWORLD_FOOTER_SEPARATOR;
        if (view.surfaceRole() == ViewSpec.SurfaceRole.TABBED_MENU) {
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    footer,
                    0,
                    height - 33,
                    0.0F,
                    0.0F,
                    width,
                    2,
                    32,
                    2);
        }
        boolean tabBarOwnsHeaderSeparator = view.tabGroups().stream()
                .anyMatch(group -> group.orientation() == ViewSpec.TabOrientation.HORIZONTAL);
        for (ViewSpec.Panel panel : view.panels()) {
            Bounds bounds = panel.bounds();
            if (panel.style() == ViewSpec.Panel.Style.VANILLA_HEADER
                    && !tabBarOwnsHeaderSeparator) {
                graphics.blit(
                        RenderPipelines.GUI_TEXTURED,
                        header,
                        bounds.x(),
                        bounds.bottom() - 2,
                        0.0F,
                        0.0F,
                        bounds.width(),
                        2,
                        32,
                        2);
            } else if (panel.style() == ViewSpec.Panel.Style.VANILLA_FOOTER) {
                graphics.blit(
                        RenderPipelines.GUI_TEXTURED,
                        footer,
                        bounds.x(),
                        bounds.y(),
                        0.0F,
                        0.0F,
                        bounds.width(),
                        2,
                        32,
                        2);
            }
        }
    }

    static void drawCreateWorldTabBackground(GuiGraphicsExtractor graphics, int width) {
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                TAB_HEADER_BACKGROUND,
                0,
                0,
                0.0F,
                0.0F,
                width,
                24,
                16,
                16);
    }

    static void renderSelectedTabUnderlay(GuiGraphicsExtractor graphics, Bounds bounds) {
        Bounds underlay = VerticalTabStyle.selectedUnderlay(bounds);
        Identifier background = Minecraft.getInstance().level == null
                ? MENU_BACKGROUND : INWORLD_MENU_BACKGROUND;
        graphics.blit(RenderPipelines.GUI_TEXTURED, background,
                underlay.x(), underlay.y(), underlay.x(), underlay.y(),
                underlay.width(), underlay.height(), 32, 32);
    }
}

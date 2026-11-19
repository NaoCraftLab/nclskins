package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.ViewSpec;
import com.naocraftlab.skins.runtime.VerticalTabStyle;
import com.naocraftlab.skins.runtime.VanillaListSurface;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

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

    private static final Identifier HEADER_SEPARATOR =
            Identifier.withDefaultNamespace("textures/gui/header_separator.png");
    private static final Identifier FOOTER_SEPARATOR =
            Identifier.withDefaultNamespace("textures/gui/footer_separator.png");
    private static final Identifier INWORLD_HEADER_SEPARATOR =
            Identifier.withDefaultNamespace("textures/gui/inworld_header_separator.png");
    private static final Identifier INWORLD_FOOTER_SEPARATOR =
            Identifier.withDefaultNamespace("textures/gui/inworld_footer_separator.png");

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
        GuiExtraction.blit(graphics,
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
        GuiExtraction.blit(graphics,
                top,
                bounds.x(), boundaries.topY(), 0.0F, 0.0F, bounds.width(), 2, 32, 2);
        GuiExtraction.blit(graphics,
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
                .forEach(segment -> GuiExtraction.blit(graphics,
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
                    GuiExtraction.blit(graphics,
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
            GuiExtraction.blit(graphics,
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
            GuiExtraction.blit(graphics,
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
                GuiExtraction.blit(graphics,
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
                GuiExtraction.blit(graphics,
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
        GuiExtraction.blit(graphics,
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
        GuiExtraction.blit(graphics, background,
                underlay.x(), underlay.y(), underlay.x(), underlay.y(),
                underlay.width(), underlay.height(), 32, 32);
    }
}

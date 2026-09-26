package com.naocraftlab.skins.compat.client.identifier.submission;

import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.ViewSpec;
import com.naocraftlab.skins.runtime.VerticalTabStyle;
import com.naocraftlab.skins.runtime.VanillaListSurface;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import static net.minecraft.client.gui.screens.Screen.HEADER_SEPARATOR;
import static net.minecraft.client.gui.screens.Screen.INWORLD_HEADER_SEPARATOR;
import static net.minecraft.client.gui.screens.Screen.FOOTER_SEPARATOR;
import static net.minecraft.client.gui.screens.Screen.INWORLD_FOOTER_SEPARATOR;
import net.minecraft.client.gui.GuiGraphics;

final class NativeSurfaceAdapter {
    private static final Identifier MENU_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/menu_background.png");
    private static final Identifier INWORLD_MENU_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/inworld_menu_background.png");
    private static final Identifier MENU_LIST_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/menu_list_background.png");
    private static final Identifier INWORLD_MENU_LIST_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/inworld_menu_list_background.png");

    private NativeSurfaceAdapter() {}

    static void renderPanel(GuiGraphics graphics, ViewSpec current, ViewSpec.Panel panel) {
        Identifier background = Minecraft.getInstance().level == null
                ? MENU_LIST_BACKGROUND : INWORLD_MENU_LIST_BACKGROUND;
        Identifier top = Minecraft.getInstance().level == null ? HEADER_SEPARATOR : INWORLD_HEADER_SEPARATOR;
        Identifier bottom = Minecraft.getInstance().level == null ? FOOTER_SEPARATOR : INWORLD_FOOTER_SEPARATOR;
        Bounds b = panel.bounds();
        if (b.width() <= 0 || b.height() <= 0) {
            return;
        }
        VanillaListSurface.Sample sample = VanillaListSurface.sample(current, panel);
        if (panel.style() == ViewSpec.Panel.Style.VANILLA_TAB_CONTENT) {
            Identifier tabBackground = Minecraft.getInstance().level == null
                    ? MENU_BACKGROUND : INWORLD_MENU_BACKGROUND;
            VerticalTabStyle.backgroundSegments(b).forEach(segment -> graphics.blit(
                    RenderPipelines.GUI_TEXTURED, tabBackground,
                    segment.x(), segment.y(),
                    sample.u() + segment.x() - b.x(), sample.v() + segment.y() - b.y(),
                    segment.width(), segment.height(), 32, 32));
            VerticalTabStyle.selectedBounds(current)
                    .map(tab -> VerticalTabStyle.separatorSegments(b, tab))
                    .orElseGet(() -> List.of(new Bounds(
                            b.x(), b.y(), Math.min(2, b.width()), b.height())))
                    .forEach(segment -> {
                        graphics.pose().pushMatrix();
                        graphics.pose().translate(segment.x(), segment.bottom());
                        graphics.pose().rotate((float) (-Math.PI / 2.0));
                        blitSeparator(graphics, top, 0, 0, segment.height());
                        graphics.pose().popMatrix();
                    });
            return;
        }
        graphics.blit(RenderPipelines.GUI_TEXTURED, background,
                b.x(), b.y(), sample.u(), sample.v(), b.width(), b.height(), 32, 32);
        VanillaListSurface.Boundaries boundaries = VanillaListSurface.boundaries(b);
        blitSeparator(graphics, top, b.x(), boundaries.topY(), b.width());
        blitSeparator(graphics, bottom, b.x(), boundaries.bottomY(), b.width());
    }

    static void renderFramePanels(GuiGraphics graphics, ViewSpec current) {
        Identifier background = Minecraft.getInstance().level == null
                ? MENU_LIST_BACKGROUND
                : INWORLD_MENU_LIST_BACKGROUND;
        if (current.surfaceRole() == ViewSpec.SurfaceRole.TABBED_MENU) {
            int footerY = Math.max(0, current.height() - 33);
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    background,
                    0, footerY, 0.0F, (float) footerY,
                    current.width(), current.height() - footerY, 32, 32);
            blitSeparator(
                    graphics,
                    Minecraft.getInstance().level == null ? FOOTER_SEPARATOR : INWORLD_FOOTER_SEPARATOR,
                    0, footerY, current.width());
        }
        boolean tabBarOwnsHeaderSeparator = current.tabGroups().stream()
                .anyMatch(group -> group.orientation() == ViewSpec.TabOrientation.HORIZONTAL);
        for (ViewSpec.Panel panel : current.panels()) {
            if (panel.style() == ViewSpec.Panel.Style.VANILLA_LIST
                    || panel.style() == ViewSpec.Panel.Style.VANILLA_TAB_CONTENT) {
                continue;
            }
            Bounds b = panel.bounds();
            graphics.blit(
                    RenderPipelines.GUI_TEXTURED,
                    background,
                    b.x(), b.y(), 0.0F, (float) b.y(), b.width(), b.height(), 32, 32);
            if (panel.style() == ViewSpec.Panel.Style.VANILLA_HEADER
                    && !tabBarOwnsHeaderSeparator) {
                blitSeparator(
                        graphics,
                        Minecraft.getInstance().level == null ? HEADER_SEPARATOR : INWORLD_HEADER_SEPARATOR,
                        b.x(), b.bottom() - 2, b.width());
            } else if (panel.style() == ViewSpec.Panel.Style.VANILLA_FOOTER) {
                blitSeparator(
                        graphics,
                        Minecraft.getInstance().level == null ? FOOTER_SEPARATOR : INWORLD_FOOTER_SEPARATOR,
                        b.x(), b.y(), b.width());
            }
        }
    }

    private static void blitSeparator(
            GuiGraphics graphics, Identifier texture, int x, int y, int width) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0F, 0.0F, width, 2, 32, 2);
    }

    static void renderSelectedTabUnderlay(GuiGraphics graphics, Bounds bounds) {
        Bounds underlay = VerticalTabStyle.selectedUnderlay(bounds);
        Identifier background = Minecraft.getInstance().level == null
                ? MENU_BACKGROUND : INWORLD_MENU_BACKGROUND;
        graphics.blit(RenderPipelines.GUI_TEXTURED, background,
                underlay.x(), underlay.y(), underlay.x(), underlay.y(),
                underlay.width(), underlay.height(), 32, 32);
    }
}

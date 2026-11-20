package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class PlayerAppearanceStage {
    record Appearance(SkinReference skin, String revision, SkinVariant variant, Optional<String> capeId,
                      Optional<UUID> presetId, Optional<ViewSpec.CatalogImage> catalogImage,
                      boolean capeHasElytra, PreviewRenderer.PreviewIntent intent) {}

    record Control(String id, UiMessage label, GuiIcon icon, boolean enabled) {}

    static int galleryColumnWidth(int width, int height) {
        return Math.min((int) Math.round(galleryBaseColumnWidth(width, height) * 4.0 / 3), Math.max(70, width - 60));
    }

    static int galleryFitWidth(int width, int height) {
        return Math.max(1, Math.min((height - 73) / 4, galleryBaseColumnWidth(width, height) - 6));
    }

    private static int galleryBaseColumnWidth(int width, int height) {
        return Math.min(Math.max(64, (height - 73) / 4) + 6, Math.max(70, width - 60));
    }

    static Bounds galleryPreviewArea(int columnWidth, int fitWidth) {
        return new Bounds(4, 36, Math.max(1, columnWidth - 6), (int) Math.round(fitWidth * 2 * 1.1));
    }

    static ViewSpec.Preview present(String id, int width, int height, Bounds anchor,
                                    Appearance appearance, PreviewInteractionModel transform) {
        return new ViewSpec.Preview(id, new Bounds(0, 0, width, height), anchor,
                appearance.skin(), appearance.revision(), appearance.variant(), appearance.capeId(),
                appearance.capeId().isPresent() ? transform.capeMode() : PreviewRenderer.CapeMode.OFF,
                transform.outerLayerVisibility(), transform.yawDegrees(), transform.pitchDegrees(), transform.scale(),
                appearance.presetId(), appearance.catalogImage(), appearance.intent())
                .withCapeElytra(appearance.capeHasElytra());
    }

    static ViewSpec.Preview presentEditor(String id, int width, int height, Bounds anchor,
            Appearance appearance, PreviewInteractionModel transform) {
        return present(id, width, height, anchor, appearance, transform).withLayoutScale(editorLayoutScale(height));
    }

    private static float editorLayoutScale(int height) {
        int visibleHeight = Math.max(1, height - 2 * (33 + 6));
        return Math.max(0.68F, Math.min(2.0F, visibleHeight / (0.97F * Math.max(1, height))));
    }

    static List<ViewSpec.Widget> controls(Bounds anchor, Optional<Control> capeMode, List<Control> outerLayers) {
        var widgets = new ArrayList<ViewSpec.Widget>();
        int x = anchor.x() + 2;
        capeMode.ifPresent(control -> widgets.add(widget(control, new Bounds(x, 35, 20, 20))));
        int contentHeight = Math.max(0, anchor.height() - 66);
        int stackHeight = outerLayers.size() * 20 + Math.max(0, outerLayers.size() - 1) * 2;
        int y = 33 + Math.max(0, (contentHeight - stackHeight) / 2);
        for (int index = 0; index < outerLayers.size(); index++) {
            widgets.add(widget(outerLayers.get(index), new Bounds(x, y + index * 22, 20, 20)));
        }
        return List.copyOf(widgets);
    }

    static List<Control> outerLayers(OuterLayerVisibility visibility, String prefix, boolean enabled) {
        return List.of("head", "body", "legs").stream().map(group -> {
            var state = EditorOuterLayerCycle.state(group, visibility);
            return new Control(prefix + group, state.label(), state.icon(), enabled);
        }).toList();
    }

    static Control capeMode(String id, PreviewInteractionModel transform, boolean enabled) {
        boolean elytra = transform.capeMode() == PreviewRenderer.CapeMode.ELYTRA;
        return new Control(id, UiMessage.info(elytra ? "item.minecraft.elytra" : "options.modelPart.cape"),
                elytra ? GuiIcon.APPEARANCE_BACK_ELYTRA : GuiIcon.APPEARANCE_BACK_CAPE, enabled);
    }

    private static ViewSpec.Widget widget(Control control, Bounds bounds) {
        return ViewSpec.Widget.iconOnlyButton(control.id(), bounds, control.label(), control.icon(), control.enabled());
    }

    private PlayerAppearanceStage() {}

    static ViewSpec withError(ViewSpec view, UiMessage message, TextResolver text) {
        if (view.previews().isEmpty()) return view;
        ViewSpec.Preview preview = view.previews().get(0);
        Bounds original = preview.anchorBounds();
        int textX = original.x() + 26;
        int textWidth = Math.max(1, original.width() - 32);
        int textHeight = text.wrappedHeight(message, textWidth);
        int footerTop = view.panels().stream().filter(panel -> panel.style() == ViewSpec.Panel.Style.VANILLA_FOOTER)
                .mapToInt(panel -> panel.bounds().y()).min().orElse(view.height() - 33);
        int bottom = Math.max(34, footerTop - 4);
        boolean editor = view.screenId().equals("preset_editor");
        if (editor && textHeight <= bottom - 33) {
            int captionTop = bottom - textHeight;
            int captionRight = textX + textWidth;
            int tabsLeft = view.tabGroups().stream().filter(group -> group.orientation() == ViewSpec.TabOrientation.VERTICAL)
                    .map(ViewSpec.TabGroup::bounds)
                    .filter(tabs -> tabs.y() < bottom && tabs.bottom() > captionTop && tabs.x() < captionRight)
                    .mapToInt(Bounds::x).min().orElse(captionRight);
            if (tabsLeft < captionRight) {
                textWidth = Math.max(1, tabsLeft - textX - 2);
                textHeight = text.wrappedHeight(message, textWidth);
            }
        }
        var texts = new ArrayList<>(view.texts());
        var tooltips = new ArrayList<>(view.tooltipRegions());
        if (editor && textHeight > bottom - 33) {
            int glyphWidth = Math.min(9, Math.max(1, original.width() - 24));
            Bounds bounds = new Bounds(Math.max(original.x() + 24, textX + (textWidth - glyphWidth) / 2),
                    bottom - 9, glyphWidth, 9);
            UiMessage glyph = UiMessage.literal("!", message.severity());
            texts.add(new ViewSpec.Text("editor.preview.error.glyph", bounds, glyph, ViewSpec.Text.Alignment.CENTER));
            tooltips.add(new ViewSpec.TooltipRegion("editor.preview.error", bounds, glyph,
                    ViewSpec.Text.Alignment.CENTER, message));
        } else {
            Bounds error = new Bounds(textX, Math.max(33, bottom - textHeight), textWidth, textHeight);
            texts.add(new ViewSpec.Text("editor.preview.error", error, message,
                    ViewSpec.Text.Alignment.CENTER, ViewSpec.Text.Layout.WRAP));
        }
        return new ViewSpec(view.screenId(), view.title(), view.width(), view.height(), view.panels(), texts,
                view.widgets(), view.previews(), view.scrollbar(), view.tabGroups(), view.focusRequest(), view.clipRegions(),
                view.backEquipmentPreviews(), view.iconDecorations(), view.scrollSurfaces(), tooltips,
                view.progressDecorations(), view.navigationNodes());
    }
}

package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewRenderer;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PlayerAppearanceStageTest {
    @Test
    void bottomErrorPreservesWholePreviewAndEveryNonTextSurfaceAcrossViewports() {
        String authored = "Zeitüberschreitung beim Warten auf das CPM-Modell. Der Basis-Skin wird angezeigt";
        TextResolver resolver = message -> authored;
        var editor = PresetEditorModel.open(TestFixtures.account(0), Optional.empty(), Optional.empty(),
                Optional.empty(), resolver, 240, PreviewRenderer.CapeMode.OFF);
        for (String reason : List.of("failed", "blocked", "timeout")) {
            for (int[] size : List.of(new int[]{854, 480}, new int[]{240, 240}, new int[]{854, 180}, new int[]{1280, 240}, new int[]{240, 151})) {
                ViewSpec original = editor.present(size[0], size[1]);
                ViewSpec view = PlayerAppearanceStage.withError(original,
                        UiMessage.error("nclskins.editor.cpm_preview_" + reason), resolver);
                var message = UiMessage.error("nclskins.editor.cpm_preview_" + reason);
                int height = size[1];
                int width = original.previews().get(0).anchorBounds().width() - 32;
                int expectedHeight = resolver.wrappedHeight(message, width);
                var tabs = original.tabGroups().get(0).bounds();
                if (expectedHeight <= height - 70 && height - 37 - expectedHeight < tabs.bottom()) {
                    expectedHeight = resolver.wrappedHeight(message, Math.max(1, tabs.x() - 28));
                }
                boolean compact = expectedHeight > size[1] - 70;
                var error = view.texts().stream().filter(text -> text.id().equals(
                        compact ? "editor.preview.error.glyph" : "editor.preview.error")).findFirst().orElseThrow();
                assertEquals(original.previews(), view.previews(), "Caption must preserve the entire preview, including the actual fit anchor");
                assertEquals(ViewSpec.Text.Alignment.CENTER, error.alignment());
                if (compact) {
                    assertEquals(UiMessage.literal("!", UiMessage.Severity.ERROR), error.message());
                    assertEquals(9, error.bounds().height());
                    var tooltip = view.tooltipRegions().stream().filter(region -> region.id().equals("editor.preview.error")).findFirst().orElseThrow();
                    assertEquals(UiMessage.error("nclskins.editor.cpm_preview_" + reason), tooltip.tooltip());
                    assertEquals(error.bounds(), tooltip.textBounds());
                    assertEquals(error.message(), tooltip.text());
                    assertEquals(original.tooltipRegions().size() + 1, view.tooltipRegions().size());
                    assertTrue(view.tooltipRegions().containsAll(original.tooltipRegions()));
                    assertTrue(view.texts().stream().noneMatch(text -> text.id().equals("editor.preview.error")));
                } else {
                    assertEquals(ViewSpec.Text.Layout.WRAP, error.layout());
                    assertEquals(expectedHeight, error.bounds().height());
                    assertEquals(original.tooltipRegions(), view.tooltipRegions());
                }
                assertEquals(size[1] - 37, error.bounds().bottom());
                assertTrue(error.bounds().y() >= 33);
                for (var widget : view.widgets()) assertFalse(overlaps(error.bounds(), widget.bounds()), widget.id());
                var expectedTexts = new java.util.ArrayList<>(original.texts());
                expectedTexts.add(error);
                assertEquals(new ViewSpec(original.screenId(), original.title(), original.width(), original.height(),
                        original.panels(), expectedTexts, original.widgets(), original.previews(), original.scrollbar(),
                        original.tabGroups(), original.focusRequest(), original.clipRegions(), original.backEquipmentPreviews(),
                        original.iconDecorations(), original.scrollSurfaces(), view.tooltipRegions(),
                        original.progressDecorations(), original.navigationNodes()), view);
            }
        }
    }

    @Test
    void compactCaptionReprojectsCurrentCauseAndUsesActualFooter() {
        TextResolver resolver = TextResolver.withLayout(UiMessage::key, (message, width) -> width >= 100 ? 9 : 200);
        var model = PresetEditorModel.open(TestFixtures.account(0), Optional.empty(), Optional.empty(),
                Optional.empty(), resolver, 151, PreviewRenderer.CapeMode.OFF);
        for (int footer : List.of(33, 38)) {
            var base = model.present(240, 151, 0, 0, new ViewChromeMetrics(footer));
            var blocked = PlayerAppearanceStage.withError(base, UiMessage.error("nclskins.editor.cpm_preview_blocked"), resolver);
            var glyph = blocked.texts().stream().filter(text -> text.id().equals("editor.preview.error.glyph")).findFirst().orElseThrow();
            assertEquals(151 - footer - 4, glyph.bounds().bottom());
            assertEquals(9, glyph.bounds().width());
            assertEquals(Optional.of(UiMessage.error("nclskins.editor.cpm_preview_blocked")), ViewHostPolicy.previewErrorMessage(blocked));
            var timeout = PlayerAppearanceStage.withError(base, UiMessage.error("nclskins.editor.cpm_preview_timeout"), resolver);
            assertEquals(Optional.of(UiMessage.error("nclskins.editor.cpm_preview_timeout")), ViewHostPolicy.previewErrorMessage(timeout));
            assertEquals(1, timeout.tooltipRegions().size() - base.tooltipRegions().size());
            var resized = model.present(854, 480, 0, 0, new ViewChromeMetrics(footer));
            var full = PlayerAppearanceStage.withError(resized, UiMessage.error("nclskins.editor.cpm_preview_timeout"), resolver);
            assertTrue(full.texts().stream().anyMatch(text -> text.id().equals("editor.preview.error")));
            assertEquals(resized.tooltipRegions(), full.tooltipRegions());
            assertEquals(ViewHostPolicy.previewErrorMessage(timeout), ViewHostPolicy.previewErrorMessage(full));
            assertTrue(ViewHostPolicy.previewErrorMessage(base).isEmpty());
            assertTrue(base.texts().stream().noneMatch(text -> text.id().startsWith("editor.preview.error")));
            assertEquals(base.widgets(), blocked.widgets());
            assertEquals(base.navigationNodes(), blocked.navigationNodes());
        }
    }

    @Test
    void logicalUnitZoomSeparatesPortraitFitFromUserZoomAcrossSurfaceLayouts() {
        for (int[] size : List.of(new int[]{240,180}, new int[]{240,240}, new int[]{854,480}, new int[]{1280,240}, new int[]{200,480})) {
            var editor = PresetEditorModel.open(TestFixtures.account(0), Optional.empty(), Optional.empty(),
                    Optional.empty(), message -> message.key(), size[1], PreviewRenderer.CapeMode.OFF);
            var preview = editor.present(size[0],size[1]).previews().get(0);
            var request = request(preview);
            float legacy = Math.max(0.68F, Math.min(2F, Math.max(1,size[1]-78)/(0.97F*size[1])));
            int preferred = (size[1] - 78) / 2;
            int divider = Math.min(preferred, size[0] - 140);
            assertEquals(legacy * divider / preferred, request.scale(), 0.000001F, "layout fit is separate from user zoom");
            assertEquals(new Bounds(0,0,request.width(),size[1]), preview.anchorBounds());
            assertEquals(new Bounds(0,0,size[0],size[1]), preview.bounds());
            assertEquals(size[1], request.height(), "actual fit height, not an invented inner slot");
            assertEquals(1F, preview.scale(), "logical user zoom must not contain layout");
            var changed = editor.withPreview(editor.preview().scroll(preview.anchorBounds(),10,50,1));
            for (var tab : com.naocraftlab.skins.core.model.EditorTab.values()) {
                var resized = changed.withSelectedEditorTab(tab).present(size[0]+20,size[1]+60).previews().get(0);
                assertEquals(1.08F,resized.scale(),0.000001F);
                assertEquals(changed.preview().yawDegrees(),resized.yawDegrees());
                assertEquals(changed.preview().pitchDegrees(),resized.pitchDegrees());
                int resizedPreferred = (size[1] + 60 - 78) / 2;
                int resizedDivider = Math.min(resizedPreferred, size[0] + 20 - 140);
                assertEquals(resized.scale()*Math.max(0.68F,Math.min(2F,(size[1]+60-78)/(0.97F*(size[1]+60)))) * resizedDivider / resizedPreferred,
                        request(resized).scale(),0.000001F,"resize derives layout without resetting zoom");
            }
        }
    }

    private static PreviewRenderer.PreviewRequest request(ViewSpec.Preview preview) {
        var appearance = new PreviewRenderer.PreviewAppearance(
                new com.naocraftlab.skins.client.TextureRegistry.TextureHandle("synthetic:ordinary",64,64),
                com.naocraftlab.skins.client.SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,
                preview.outerLayerVisibility());
        return ViewHostPolicy.previewRequest(preview,Optional.of(appearance),Optional.of(appearance)).orElseThrow();
    }

    private static boolean overlaps(Bounds a, Bounds b) {
        return a.x() < b.right() && a.right() > b.x() && a.y() < b.bottom() && a.bottom() > b.y();
    }
}

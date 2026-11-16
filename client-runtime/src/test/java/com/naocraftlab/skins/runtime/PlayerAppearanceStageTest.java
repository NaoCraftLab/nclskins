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
            for (int[] size : List.of(new int[]{854, 480}, new int[]{240, 240}, new int[]{854, 180}, new int[]{1280, 240})) {
                ViewSpec original = editor.present(size[0], size[1]);
                ViewSpec view = PlayerAppearanceStage.withError(original,
                        UiMessage.error("nclskins.editor.cpm_preview_" + reason), resolver);
                var error = view.texts().stream().filter(text -> text.id().equals("editor.preview.error")).findFirst().orElseThrow();
                assertEquals(original.previews(), view.previews(), "Caption must preserve the entire preview, including the actual fit anchor");
                assertEquals(ViewSpec.Text.Alignment.CENTER, error.alignment());
                assertEquals(ViewSpec.Text.Layout.WRAP, error.layout());
                assertEquals(resolver.wrappedHeight(error.message(), error.bounds().width()), error.bounds().height());
                assertEquals(size[1] - 37, error.bounds().bottom());
                for (var widget : view.widgets()) assertFalse(overlaps(error.bounds(), widget.bounds()), widget.id());
                var expectedTexts = new java.util.ArrayList<>(original.texts());
                expectedTexts.add(error);
                assertEquals(new ViewSpec(original.screenId(), original.title(), original.width(), original.height(),
                        original.panels(), expectedTexts, original.widgets(), original.previews(), original.scrollbar(),
                        original.tabGroups(), original.focusRequest(), original.clipRegions(), original.backEquipmentPreviews(),
                        original.iconDecorations(), original.scrollSurfaces(), original.tooltipRegions(),
                        original.progressDecorations(), original.navigationNodes()), view);
            }
        }
    }

    @Test
    void logicalUnitZoomPreservesLegacyOrdinarySizeAndAnchorAcrossSurfaceLayouts() {
        for (int[] size : List.of(new int[]{240,180}, new int[]{240,240}, new int[]{854,480}, new int[]{1280,240})) {
            var editor = PresetEditorModel.open(TestFixtures.account(0), Optional.empty(), Optional.empty(),
                    Optional.empty(), message -> message.key(), size[1], PreviewRenderer.CapeMode.OFF);
            var preview = editor.present(size[0],size[1]).previews().get(0);
            var request = request(preview);
            float legacy = Math.max(0.68F, Math.min(2F, Math.max(1,size[1]-78)/(0.97F*size[1])));
            assertEquals(legacy, request.scale(), 0.000001F, "ordinary request size at opening");
            assertEquals(new Bounds(0,0,size[0]/2,size[1]), preview.anchorBounds());
            assertEquals(size[1], request.height(), "actual fit height, not an invented inner slot");
            assertEquals(1F, preview.scale(), "logical user zoom must not contain layout");
            var changed = editor.withPreview(editor.preview().scroll(preview.anchorBounds(),10,50,1));
            for (var tab : com.naocraftlab.skins.core.model.EditorTab.values()) {
                var resized = changed.withSelectedEditorTab(tab).present(size[0]+20,size[1]+60).previews().get(0);
                assertEquals(1.08F,resized.scale(),0.000001F);
                assertEquals(changed.preview().yawDegrees(),resized.yawDegrees());
                assertEquals(changed.preview().pitchDegrees(),resized.pitchDegrees());
                assertEquals(resized.scale()*Math.max(0.68F,Math.min(2F,(size[1]+60-78)/(0.97F*(size[1]+60)))),
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

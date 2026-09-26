package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.EditorTab;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class PresentationCompositionCharacterizationTest {
    @Test
    void surfaceRolesPreserveTheExistingNativeScreenFamilies() {
        for (String screen : List.of("gallery", "providers", "provider_chooser", "settings", "unknown")) {
            assertEquals(ViewSpec.SurfaceRole.MENU, view(screen).surfaceRole());
        }
        assertEquals(ViewSpec.SurfaceRole.WORKSPACE, view("preset_editor").surfaceRole());
        assertEquals(ViewSpec.SurfaceRole.TABBED_MENU, view("add_source").surfaceRole());
        assertEquals(ViewSpec.SurfaceRole.IMPORT, view("external_chooser").surfaceRole());
        assertEquals(ViewSpec.SurfaceRole.IMPORT, view("external_review").surfaceRole());
    }

    private static ViewSpec view(String screenId) {
        return new ViewSpec(screenId, UiMessage.literal("Fixture", UiMessage.Severity.INFO), 320, 240,
                List.of(), List.of(), List.of(), List.of(), Optional.empty());
    }

    @Test
    void editorAndProvidersKeepIndependentTabsAndTransformsThroughRepeatedResizing() {
        var editor = PresetEditorModel.open(TestFixtures.account(0), Optional.empty(),
                Optional.empty(), Optional.empty(), message -> message.key(), 480,
                PreviewRenderer.CapeMode.CAPE);
        var editorTransform = new PreviewInteractionModel(120, -30, 2,
                OuterLayerVisibility.allVisible().with(OuterLayerPart.HEAD, false),
                PreviewRenderer.CapeMode.ELYTRA, false);
        editor = editor.withPreview(editorTransform).withSelectedEditorTab(EditorTab.CAPE);
        var providerTransform = PreviewInteractionModel.editor(480, PreviewRenderer.CapeMode.CAPE);
        var presenter = new ProvidersPresenter();
        for (int[] size : List.of(new int[]{320, 240}, new int[]{854, 480}, new int[]{1600, 720}, new int[]{320, 240})) {
            int width = size[0];
            int height = size[1];
            var before = editor.present(width, height);
            var providers = presenter.present(AppearanceProviders.initial(), AppearanceProviders.Component.SKIN,
                    false, false, providerTransform, SkinVariant.SLIM, width, height);
            assertEquals(before, editor.present(width, height));
            for (var view : List.of(before, providers)) {
                var stage = view.previews().get(0);
                assertEquals(new Bounds(0, 0, width, height), stage.bounds());
                assertEquals(new Bounds(0, 0, width / 2, height), stage.anchorBounds());
                assertEquals(new Bounds(width / 2 - 22, 45, 24, 48), view.tabGroups().get(0).bounds());
            }
            assertEquals(Optional.of("selected"), before.widget("editor.tab.cape").orElseThrow().value());
            assertEquals(Optional.of("selected"), providers.widget("providers.tab.SKIN").orElseThrow().value());
            assertEquals(120, before.previews().get(0).yawDegrees());
            assertEquals(2, before.previews().get(0).scale());
            assertFalse(before.previews().get(0).outerLayerVisibility().visible(OuterLayerPart.HEAD));
            assertEquals(providerTransform.yawDegrees(), providers.previews().get(0).yawDegrees());
            assertEquals(providerTransform.outerLayerVisibility(), providers.previews().get(0).outerLayerVisibility());
            assertEquals("nclskins.editor.outer_head_off", before.widget("editor.outer_layer.head").orElseThrow().label().key());
        }
    }
}

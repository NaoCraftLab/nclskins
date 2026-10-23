package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.SkinVariant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

final class OwnedDraftPreviewTest {
    @Test
    void ordinaryAndConvertedPngUseLoadedPreviewWithoutChangingSavePayload() {
        for (SkinVariant variant : SkinVariant.values()) {
            PresetEditorModel ordinary = PresetEditorModel.open(TestFixtures.account(0), Optional.empty(),
                    Optional.empty(), Optional.empty(), UiMessage::key, 240, PreviewRenderer.CapeMode.OFF);
            assertTrue(ordinary.present(320, 240).previews().get(0).requiresLoadedSkin());
            byte[] bytes = new byte[] {1, 2, 3, (byte) variant.ordinal()};
            for (PresetEditorModel draft : List.of(ordinary.withImportedPng("skin.png", bytes, variant),
                    ordinary.withConvertedPng("model.png", bytes, variant))) {
                ViewSpec.Preview preview = draft.present(320, 240).previews().get(0);
                assertTrue(preview.requiresLoadedSkin(), "Owned creation PNG must reach the native loaded branch");
                assertEquals(variant, preview.variant());
                assertArrayEquals(bytes, draft.saveRequest().pngBytes().orElseThrow());
                assertTrue(preview.withCapeElytra(false).requiresLoadedSkin());
                assertTrue(preview.withNativeAccessories(true, PreviewRenderer.CapeMode.OFF).requiresLoadedSkin());
                var replacement = draft.withImportedPng("replacement.png", new byte[] {9, 8, 7}, variant);
                var next = replacement.present(320, 240).previews().get(0);
                assertTrue(next.requiresLoadedSkin());
                assertNotEquals(preview.imageRevision(), next.imageRevision());
                assertArrayEquals(new byte[] {9, 8, 7}, replacement.saveRequest().pngBytes().orElseThrow());
            }
        }
    }
}

package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.CatalogOrigin;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class CatalogPresetEditorTest {
    @Test
    void catalogPairToggleSwapsTheCopiedPngRevisionAndKeepsOriginForSave() {
        CatalogOrigin origin = new CatalogOrigin("minecraft", "minecraft", "steve");
        PresetEditorModel model = PresetEditorModel.openCatalog(
                "Steve",
                origin,
                Map.of(
                        SkinVariant.CLASSIC, new byte[] {1, 2, 3},
                        SkinVariant.SLIM, new byte[] {4, 5, 6}),
                SkinVariant.CLASSIC,
                Optional.empty(),
                480,
                PreviewRenderer.CapeMode.CAPE);

        PresetEditorModel slim = model.toggleVariant();

        assertEquals(SkinVariant.CLASSIC, model.variant());
        assertEquals(SkinVariant.SLIM, slim.variant());
        assertArrayEquals(new byte[] {1, 2, 3}, model.png().orElseThrow().bytes());
        assertArrayEquals(new byte[] {4, 5, 6}, slim.png().orElseThrow().bytes());
        assertNotEquals(model.png().orElseThrow().revision(), slim.png().orElseThrow().revision());
        assertEquals(origin, slim.saveRequest().catalogOrigin().orElseThrow());
        assertArrayEquals(new byte[] {4, 5, 6}, slim.saveRequest().pngBytes().orElseThrow());
        assertEquals(Set.of(SkinVariant.CLASSIC, SkinVariant.SLIM), slim.availableCatalogVariants());
    }

    @Test
    void replacingCatalogPngClearsOriginAndStopsPairSpecificToggleSwaps() {
        PresetEditorModel catalog = PresetEditorModel.openCatalog(
                "Alex",
                new CatalogOrigin("minecraft", "minecraft", "alex"),
                Map.of(
                        SkinVariant.CLASSIC, new byte[] {1},
                        SkinVariant.SLIM, new byte[] {2}),
                SkinVariant.CLASSIC,
                Optional.empty(),
                480,
                PreviewRenderer.CapeMode.CAPE);

        PresetEditorModel replacement = catalog.withPng("replacement.png", new byte[] {9, 8, 7});
        PresetEditorModel toggled = replacement.toggleVariant();

        assertTrue(replacement.catalogOrigin().isEmpty());
        assertTrue(replacement.availableCatalogVariants().isEmpty());
        assertTrue(replacement.saveRequest().catalogOrigin().isEmpty());
        assertArrayEquals(new byte[] {9, 8, 7}, replacement.saveRequest().pngBytes().orElseThrow());
        assertEquals(SkinVariant.SLIM, toggled.variant());
        assertArrayEquals(new byte[] {9, 8, 7}, toggled.png().orElseThrow().bytes());
        assertFalse(toggled.saveRequest().catalogOrigin().isPresent());
    }
}

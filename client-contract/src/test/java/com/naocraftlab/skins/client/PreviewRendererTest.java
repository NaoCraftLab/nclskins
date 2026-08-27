package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreviewRendererTest {
    @Test
    void backEquipmentRequiresCape() {
        TextureRegistry.TextureHandle skin = new TextureRegistry.TextureHandle("nclskins:test", 64, 64);

        for (PreviewRenderer.CapeMode mode : new PreviewRenderer.CapeMode[]{
                PreviewRenderer.CapeMode.CAPE,
                PreviewRenderer.CapeMode.ELYTRA
        }) {
            assertThrows(IllegalArgumentException.class, () -> new PreviewRenderer.PreviewAppearance(
                    skin,
                    SkinModel.CLASSIC,
                    Optional.empty(),
                    mode,
                    true));
        }
    }

    @Test
    void previewIntentIsExplicitAndLegacyConstructionRemainsStatic() {
        TextureRegistry.TextureHandle skin = new TextureRegistry.TextureHandle("nclskins:test", 64, 64);
        PreviewRenderer.PreviewAppearance appearance = new PreviewRenderer.PreviewAppearance(
                skin,
                SkinModel.CLASSIC,
                Optional.empty(),
                PreviewRenderer.CapeMode.OFF,
                true);

        PreviewRenderer.PreviewRequest legacy = new PreviewRenderer.PreviewRequest(
                appearance, 0, 0, 64, 96, 0.0F, 0.0F, 1.0F);
        PreviewRenderer.PreviewRequest editor = new PreviewRenderer.PreviewRequest(
                appearance,
                0,
                0,
                64,
                96,
                0.0F,
                0.0F,
                1.0F,
                PreviewRenderer.PreviewIntent.EDITOR_DRAFT);

        assertEquals(PreviewRenderer.PreviewIntent.ASSET_THUMBNAIL, legacy.intent());
        assertEquals(PreviewRenderer.PreviewIntent.EDITOR_DRAFT, editor.intent());
        assertEquals(legacy.left(), legacy.stageLeft());
        assertEquals(legacy.width(), legacy.stageWidth());
        PreviewRenderer.PreviewRequest staged = new PreviewRenderer.PreviewRequest(
                appearance, 12, 8, 120, 200, 0.0F, 0.0F, 1.0F,
                PreviewRenderer.PreviewIntent.EDITOR_DRAFT, 0, 0, 320, 240);
        assertEquals(12, staged.left());
        assertEquals(0, staged.stageLeft());
        assertEquals(320, staged.stageWidth());
        assertThrows(NullPointerException.class, () -> new PreviewRenderer.PreviewRequest(
                appearance, 0, 0, 64, 96, 0.0F, 0.0F, 1.0F, null));
    }
}

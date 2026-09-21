package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreviewRendererTest {
    @Test
    void staticPreviewPreservesEachOuterLayerWithoutChangingSkinModelOrCape() {
        var skin = new TextureRegistry.TextureHandle("nclskins:test", 64, 64);
        var cape = new TextureRegistry.TextureHandle("nclskins:cape", 64, 32);
        for (SkinModel model : SkinModel.values()) {
            for (boolean withCape : new boolean[]{false, true}) {
                for (int bits = 0; bits < 64; bits++) {
                    var parts = java.util.EnumSet.noneOf(OuterLayerPart.class);
                    for (OuterLayerPart part : OuterLayerPart.values()) {
                        if ((bits & (1 << part.ordinal())) != 0) parts.add(part);
                    }
                    OuterLayerVisibility visibility = OuterLayerVisibility.of(parts);
                    parts.clear();
                    var appearance = new PreviewRenderer.PreviewAppearance(
                            skin, model, withCape ? Optional.of(cape) : Optional.empty(),
                            withCape ? PreviewRenderer.CapeMode.CAPE : PreviewRenderer.CapeMode.OFF,
                            visibility);
                    var request = new PreviewRenderer.PreviewRequest(
                            appearance, 0, 0, 64, 96, 0, 0, 1,
                            PreviewRenderer.PreviewIntent.CURRENT_APPEARANCE);
                    assertEquals(skin, request.appearance().skin());
                    assertEquals(model, request.appearance().model());
                    assertEquals(withCape ? Optional.of(cape) : Optional.empty(), request.appearance().cape());
                    for (OuterLayerPart part : OuterLayerPart.values()) {
                        assertEquals((bits & (1 << part.ordinal())) != 0,
                                request.appearance().outerLayerVisibility().visible(part));
                    }
                }
            }
        }
    }

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

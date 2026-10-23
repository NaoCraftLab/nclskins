package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PreviewSkinSourceTest {
    @Test
    void onlyAccountDefaultPreviewBorrowsTheCurrentPlayerSkin() {
        assertFalse(preview(SkinReference.accountDefault()).requiresLoadedSkin());
        assertTrue(preview(SkinReference.asset(UUID.randomUUID())).requiresLoadedSkin());
    }

    @Test
    void explicitOwnedSourceDoesNotDependOnDraftRevisionTextOrCpmIntent() {
        for (SkinVariant variant : SkinVariant.values()) {
            var borrowed = new ViewSpec.Preview("preview", new Bounds(0, 0, 64, 96),
                    SkinReference.accountDefault(), "draft:synthetic", variant, Optional.empty(),
                    PreviewRenderer.CapeMode.OFF, OuterLayerVisibility.allVisible(), 0, 0, 1,
                    Optional.empty());
            assertFalse(borrowed.requiresLoadedSkin());
            assertFalse(borrowed.withCapeElytra(false).requiresLoadedSkin());
            assertFalse(borrowed.withNativeAccessories(true, PreviewRenderer.CapeMode.OFF).requiresLoadedSkin());
            assertTrue(borrowed.withOwnedPng(true).requiresLoadedSkin());
            assertFalse(borrowed.withOwnedPng(true).withOwnedPng(false).requiresLoadedSkin());
        }
    }

    private static ViewSpec.Preview preview(SkinReference skin) {
        return new ViewSpec.Preview(
                "preview",
                new Bounds(0, 0, 64, 96),
                skin,
                "revision",
                SkinVariant.CLASSIC,
                Optional.empty(),
                PreviewRenderer.CapeMode.OFF,
                OuterLayerVisibility.allVisible(),
                0.0F,
                0.0F,
                1.0F,
                Optional.empty());
    }
}

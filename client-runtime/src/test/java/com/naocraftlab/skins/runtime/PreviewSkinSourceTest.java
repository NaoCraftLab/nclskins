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

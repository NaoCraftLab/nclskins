package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


final class BackEquipmentPreviewRendererTest {
    private static final TextureRegistry.TextureHandle CAPE =
            new TextureRegistry.TextureHandle("nclskins:test_cape", 64, 32);

    @Test
    void requestCarriesOnlyStandaloneEquipmentState() {
        BackEquipmentPreviewRenderer.Request request = new BackEquipmentPreviewRenderer.Request(
                CAPE,
                BackEquipmentPreviewRenderer.Mode.ELYTRA,
                10,
                20,
                48,
                64);

        assertEquals(CAPE, request.texture());
        assertEquals(BackEquipmentPreviewRenderer.Mode.ELYTRA, request.mode());
        assertEquals(10, request.left());
        assertEquals(20, request.top());
        assertEquals(48, request.width());
        assertEquals(64, request.height());
    }

    @Test
    void requestRejectsEmptyBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BackEquipmentPreviewRenderer.Request(
                        CAPE,
                        BackEquipmentPreviewRenderer.Mode.CAPE,
                        0,
                        0,
                        0,
                        20));
    }
}

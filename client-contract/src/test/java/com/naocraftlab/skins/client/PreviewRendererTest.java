package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PreviewRendererTest {
    @Test
    void elytraRequiresCape() {
        TextureRegistry.TextureHandle skin = new TextureRegistry.TextureHandle("nclskins:test", 64, 64);

        assertThrows(IllegalArgumentException.class, () -> new PreviewRenderer.PreviewAppearance(
                skin,
                SkinModel.CLASSIC,
                Optional.empty(),
                PreviewRenderer.CapeMode.ELYTRA,
                true));
    }
}

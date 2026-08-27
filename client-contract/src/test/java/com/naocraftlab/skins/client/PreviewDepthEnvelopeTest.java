package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreviewDepthEnvelopeTest {
    @Test
    void onlyEditorRequestsReceiveABoundedPerRequestEnvelope() {
        assertEquals(0.0F, PreviewDepthEnvelope.forRequest(
                PreviewRenderer.PreviewIntent.ASSET_THUMBNAIL,
                256.0F, 30.0F, PreviewRenderer.CapeMode.ELYTRA));

        float body = PreviewDepthEnvelope.forRequest(
                PreviewRenderer.PreviewIntent.EDITOR_DRAFT,
                128.0F, 0.0F, PreviewRenderer.CapeMode.OFF);
        float pitched = PreviewDepthEnvelope.forRequest(
                PreviewRenderer.PreviewIntent.EDITOR_DRAFT,
                128.0F, 30.0F, PreviewRenderer.CapeMode.OFF);
        float cape = PreviewDepthEnvelope.forRequest(
                PreviewRenderer.PreviewIntent.EDITOR_DRAFT,
                128.0F, 30.0F, PreviewRenderer.CapeMode.CAPE);
        float elytra = PreviewDepthEnvelope.forRequest(
                PreviewRenderer.PreviewIntent.EDITOR_DRAFT,
                128.0F, 30.0F, PreviewRenderer.CapeMode.ELYTRA);

        assertEquals(PreviewDepthEnvelope.UPSTREAM_MINIMUM, body);
        assertEquals(PreviewDepthEnvelope.UPSTREAM_MINIMUM, pitched);
        assertEquals(PreviewDepthEnvelope.UPSTREAM_MINIMUM, cape);
        assertEquals(PreviewDepthEnvelope.UPSTREAM_MINIMUM, elytra);

        float extreme = PreviewDepthEnvelope.forRequest(
                PreviewRenderer.PreviewIntent.EDITOR_DRAFT,
                20_000.0F, 30.0F, PreviewRenderer.CapeMode.ELYTRA);
        assertEquals(PreviewDepthEnvelope.MAXIMUM, extreme);
    }
}

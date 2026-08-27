package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import static com.naocraftlab.skins.client.EditorPreviewSession.Path.BAKED;
import static com.naocraftlab.skins.client.EditorPreviewSession.Path.LIVE;
import static com.naocraftlab.skins.client.PreviewRenderer.PreviewIntent.ASSET_THUMBNAIL;
import static com.naocraftlab.skins.client.PreviewRenderer.PreviewIntent.CURRENT_APPEARANCE;
import static com.naocraftlab.skins.client.PreviewRenderer.PreviewIntent.EDITOR_DRAFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorPreviewSessionTest {
    @Test
    void worldlessPreviewAlwaysUsesTheBakedRenderer() {
        assertEquals(BAKED, new EditorPreviewSession().path(EDITOR_DRAFT, false));
    }

    @Test
    void onlyInWorldEditorDraftUsesTheCanonicalLiveRenderer() {
        EditorPreviewSession session = new EditorPreviewSession();

        assertEquals(LIVE, session.path(EDITOR_DRAFT, true));
        assertEquals(BAKED, session.path(CURRENT_APPEARANCE, true));
        assertEquals(BAKED, session.path(ASSET_THUMBNAIL, true));
    }

    @Test
    void runtimeFailureDisablesLiveRenderingForTheRestOfTheSession() {
        EditorPreviewSession session = new EditorPreviewSession();

        assertTrue(session.disableLive(new RuntimeException("renderer")));
        assertEquals(BAKED, session.path(EDITOR_DRAFT, true));
        assertFalse(session.disableLive(new RuntimeException("renderer again")));
    }

    @Test
    void reopeningTheEditorRetriesTheLiveRenderer() {
        EditorPreviewSession closedSession = new EditorPreviewSession();
        closedSession.disableLive(new RuntimeException("renderer"));

        assertEquals(LIVE, new EditorPreviewSession().path(EDITOR_DRAFT, true));
    }

    @Test
    void failureMustBeExplicitlyProvidedWithoutBeingRetained() {
        EditorPreviewSession session = new EditorPreviewSession();
        assertThrows(NullPointerException.class, () -> session.disableLive(null));
        assertThrows(NullPointerException.class, () -> session.path(null, true));
        assertEquals(LIVE, session.path(EDITOR_DRAFT, true));
    }

    @Test
    void capeSettlesOnceAcrossSixMonotonicTicksWithoutMovingTheScreenAnchor() {
        EditorPreviewSession session = new EditorPreviewSession();
        assertEquals(EditorPreviewSession.SettlingMotion.NONE,
                session.capeSettling(EDITOR_DRAFT, true,
                        PreviewRenderer.CapeMode.CAPE, false, 30.0F));

        EditorPreviewSession.SettlingMotion first = session.capeSettling(
                EDITOR_DRAFT, true, PreviewRenderer.CapeMode.CAPE, true, 40.0F);
        EditorPreviewSession.SettlingMotion repeated = session.capeSettling(
                EDITOR_DRAFT, true, PreviewRenderer.CapeMode.CAPE, true, 40.0F);
        assertTrue(first.active());
        assertEquals(first, repeated);

        float previous = first.currentYOffset();
        for (int tick = 1; tick < EditorPreviewSession.SETTLING_TICKS; tick++) {
            EditorPreviewSession.SettlingMotion motion = session.capeSettling(
                    EDITOR_DRAFT, true, PreviewRenderer.CapeMode.CAPE, true, 40.0F + tick);
            assertTrue(motion.active());
            assertTrue(motion.currentYOffset() < previous);
            previous = motion.currentYOffset();
        }
        assertEquals(EditorPreviewSession.SettlingMotion.NONE,
                session.capeSettling(EDITOR_DRAFT, true,
                        PreviewRenderer.CapeMode.CAPE, true, 46.0F));
        assertEquals(EditorPreviewSession.SettlingMotion.NONE,
                session.capeSettling(EDITOR_DRAFT, true,
                        PreviewRenderer.CapeMode.CAPE, true, 100.0F));
    }

    @Test
    void worldlessCapeOffAndCircuitBreakerNeverStartSettling() {
        EditorPreviewSession session = new EditorPreviewSession();
        assertEquals(EditorPreviewSession.SettlingMotion.NONE,
                session.capeSettling(EDITOR_DRAFT, false,
                        PreviewRenderer.CapeMode.CAPE, true, 1.0F));
        assertEquals(EditorPreviewSession.SettlingMotion.NONE,
                session.capeSettling(EDITOR_DRAFT, true,
                        PreviewRenderer.CapeMode.OFF, true, 2.0F));
        session.disableLive(new RuntimeException("renderer"));
        assertEquals(EditorPreviewSession.SettlingMotion.NONE,
                session.capeSettling(EDITOR_DRAFT, true,
                        PreviewRenderer.CapeMode.CAPE, true, 3.0F));
    }
}

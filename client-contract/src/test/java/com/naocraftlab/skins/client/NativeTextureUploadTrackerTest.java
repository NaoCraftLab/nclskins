package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeTextureUploadTrackerTest {
    @Test
    void onlyTheExactTrackedTextureCompletesTheRegistration() {
        String location = "nclskins:test/upload/exact";
        NativePlayerSkinLifecycle.Registration registration =
                NativePlayerSkinLifecycle.pending(location);
        EqualTexture tracked = new EqualTexture();
        EqualTexture equalButDifferent = new EqualTexture();
        NativeTextureUploadTracker.track(tracked, registration);

        NativeTextureUploadTracker.uploaded(equalButDifferent);
        assertFalse(NativePlayerSkinLifecycle.isReady(location));

        NativeTextureUploadTracker.uploaded(tracked);
        assertTrue(NativePlayerSkinLifecycle.isReady(location));
        NativeTextureUploadTracker.forget(tracked);
        registration.retire();
    }

    private static final class EqualTexture {
        @Override
        public boolean equals(Object ignored) {
            return true;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}

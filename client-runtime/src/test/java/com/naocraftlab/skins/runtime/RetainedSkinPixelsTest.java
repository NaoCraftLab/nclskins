package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetainedSkinPixelsTest {
    @Test
    void liveNativePixelsSurviveMoreCapturesThanTheDerivedCacheCapacity() {
        RetainedSkinPixels pixels = new RetainedSkinPixels();
        Object originalTexture = new Object();
        java.util.List<Object> otherTextures = new java.util.ArrayList<>();
        int[] original = new int[4096];
        original[0] = 0x01234567;
        pixels.retain("original", original, originalTexture);
        long revision = pixels.snapshot("original").revision();
        try {
            for (int index = 0; index < 1024; index++) {
                Object texture = new Object();
                otherTextures.add(texture);
                pixels.retain("other-" + index, new int[4096], texture);
            }
            assertNotNull(pixels.snapshot("original"));
            assertEquals(revision, pixels.snapshot("original").revision());
            assertArrayEquals(original, pixels.read("original"));
        } finally {
            java.lang.ref.Reference.reachabilityFence(originalTexture);
            java.lang.ref.Reference.reachabilityFence(otherTextures);
        }
    }

    @Test
    void nativeRetirementReleasesPixelsWithoutRemovingReplacement() throws Exception {
        RetainedSkinPixels pixels = new RetainedSkinPixels();
        Object original = new Object();
        Object replacement = new Object();
        try {
            pixels.retain("skin", new int[4096], original);
            java.lang.reflect.Field texturesField = RetainedSkinPixels.class.getDeclaredField("textures");
            texturesField.setAccessible(true);
            java.util.Map<?, ?> textures = (java.util.Map<?, ?>) texturesField.get(pixels);
            java.lang.ref.Reference<?> retired = (java.lang.ref.Reference<?>) textures.get("skin");
            int[] current = new int[4096];
            current[0] = 0x01234567;
            pixels.retain("skin", current, replacement);
            retired.clear();
            retired.enqueue();
            assertArrayEquals(current, pixels.read("skin"));
            java.lang.ref.Reference<?> live = (java.lang.ref.Reference<?>) textures.get("skin");
            live.clear();
            live.enqueue();
            assertNull(pixels.snapshot("skin"));
            assertTrue(textures.isEmpty());
        } finally {
            java.lang.ref.Reference.reachabilityFence(original);
            java.lang.ref.Reference.reachabilityFence(replacement);
        }
    }

    @Test
    void sameNativeIdentityKeepsPreAlphaPixelsAndDefensiveCopies() {
        RetainedSkinPixels pixels = new RetainedSkinPixels();
        Object texture = new Object();
        int[] original = new int[4096];
        original[0] = 0x01234567;
        pixels.retain("skin", original, texture);
        original[0] = 0xff234567;
        pixels.retain("skin", original, texture);
        int[] first = pixels.read("skin");
        assertEquals(0x01234567, first[0]);
        first[0] = 0;
        assertEquals(0x01234567, pixels.read("skin")[0]);
        Object replacement = new Object();
        pixels.retain("skin", original, replacement);
        assertEquals(0xff234567, pixels.read("skin")[0]);
    }
}

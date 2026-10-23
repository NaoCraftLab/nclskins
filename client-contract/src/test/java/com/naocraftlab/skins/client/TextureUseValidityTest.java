package com.naocraftlab.skins.client;

import java.io.IOException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextureUseValidityTest {
    private static final String HASH = "a".repeat(64);

    @Test
    void unsupportedOwnersFailClosedAndAppearanceSinkRemainsFunctional() {
        PlayerAppearanceSink<Object> sink = resolved -> PlayerAppearanceSink.ApplyResult.DEFERRED;
        assertFalse(sink.captureCapeTexture(java.util.UUID.randomUUID(),
                PlayerAppearanceSink.CapeSource.MINECRAFTCAPES, "test:frame").isValid());
        TextureRegistry unsupported = new TextureRegistry() {
            @Override public TextureHandle register(TextureKind kind, String hash, java.nio.file.Path path) {
                throw new UnsupportedOperationException();
            }
            @Override public TextureHandle register(TextureKind kind, String hash, byte[] bytes) {
                throw new UnsupportedOperationException();
            }
            @Override public void release(TextureHandle handle) {}
            @Override public void close() {}
        };
        assertFalse(unsupported.capture(new TextureRegistry.TextureHandle("test:frame", 1, 1)).isValid());
    }

    @Test
    void failedLoadCannotReviveRetiredCapture() throws IOException {
        Registry registry = new Registry();
        var first = registry.register(TextureRegistry.TextureKind.IMAGE, HASH, new byte[]{1});
        var capture = registry.capture(first);
        registry.release(first);
        registry.failLoad = true;
        assertThrows(IOException.class, () -> registry.register(TextureRegistry.TextureKind.IMAGE, HASH, new byte[]{1}));
        assertFalse(capture.isValid());
        assertFalse(registry.capture(first).isValid());
        registry.failLoad = false;
        var replacement = registry.register(TextureRegistry.TextureKind.IMAGE, HASH, new byte[]{1});
        assertTrue(registry.capture(replacement).isValid());
        assertFalse(capture.isValid());
        registry.close();
    }

    @Test
    void captureSurvivesReferencesAndBudgetPromotionUntilFinalUnload() throws IOException {
        Registry registry = new Registry();
        var handle = registry.register(TextureRegistry.TextureKind.IMAGE, HASH, new byte[]{1});
        var capture = registry.capture(handle);
        assertTrue(capture.isValid());
        var shared = registry.registerRgba(TextureRegistry.TextureKind.IMAGE, HASH,
                1, 1, new byte[4], () -> {});
        assertSame(capture, registry.capture(shared));
        registry.release(handle);
        assertTrue(capture.isValid());
        registry.beforeUnload = () -> assertFalse(capture.isValid());
        registry.release(shared);
        assertFalse(capture.isValid());
        assertEquals(1, registry.unloads);
        registry.close();
    }

    @Test
    void staleValueEqualHandleCannotCaptureNewIncarnation() throws IOException {
        Registry registry = new Registry();
        var old = registry.register(TextureRegistry.TextureKind.IMAGE, HASH, new byte[]{1});
        var retired = registry.capture(old);
        registry.release(old);
        var current = registry.register(TextureRegistry.TextureKind.IMAGE, HASH, new byte[]{1});
        assertEquals(old, current);
        assertNotSame(old, current);
        assertFalse(retired.isValid());
        assertFalse(registry.capture(old).isValid());
        assertFalse(registry.capture(new TextureRegistry.TextureHandle(current.location(), 1, 1)).isValid());
        var live = registry.capture(current);
        assertTrue(live.isValid());
        assertNotSame(retired, live);
        registry.close();
        assertFalse(live.isValid());
        assertFalse(registry.capture(current).isValid());
    }

    @Test
    void closeInvalidatesEveryCaptureBeforeFirstNativeUnloadEvenOnFailure() throws IOException {
        Registry registry = new Registry();
        var first = registry.capture(registry.register(TextureRegistry.TextureKind.IMAGE, HASH, new byte[]{1}));
        var second = registry.capture(registry.register(TextureRegistry.TextureKind.IMAGE, "b".repeat(64), new byte[]{1}));
        registry.beforeUnload = () -> {
            assertFalse(first.isValid());
            assertFalse(second.isValid());
            throw new IllegalStateException("unload failure");
        };
        assertThrows(IllegalStateException.class, registry::close);
        assertFalse(first.isValid());
        assertFalse(second.isValid());
        registry.close();
    }

    @Test
    void captureAndMutationsUseTheExistingThreadGuard() throws IOException {
        Registry registry = new Registry();
        var handle = registry.register(TextureRegistry.TextureKind.IMAGE, HASH, new byte[]{1});
        registry.allowThread = false;
        assertThrows(IllegalStateException.class, () -> registry.capture(handle));
        assertThrows(IllegalStateException.class, () -> registry.release(handle));
        assertThrows(IllegalStateException.class, registry::close);
        registry.allowThread = true;
        assertTrue(registry.capture(handle).isValid());
        registry.close();
    }

    @Test
    void collisionDoesNotRetireExistingCapture() throws IOException {
        Registry registry = new Registry();
        var handle = registry.register(TextureRegistry.TextureKind.IMAGE, HASH, new byte[]{1});
        var capture = registry.capture(handle);
        registry.collide = true;
        assertThrows(IllegalStateException.class, () -> registry.register(
                TextureRegistry.TextureKind.IMAGE, "b".repeat(64), new byte[]{1}));
        assertTrue(capture.isValid());
        registry.close();
        assertFalse(capture.isValid());
    }

    private static final class Registry extends AbstractTextureRegistry<String> {
        private Runnable beforeUnload = () -> {};
        private boolean allowThread = true;
        private boolean collide;
        private boolean failLoad;
        private int unloads;

        @Override protected LoadedTexture<String> load(TextureKind kind, String hash, byte[] bytes) throws IOException {
            if (failLoad) throw new IOException("load failure");
            String location = "test:" + (collide ? HASH : hash);
            return new LoadedTexture<>(new TextureHandle(location, 1, 1), location);
        }

        @Override protected void unload(String resource) {
            unloads++;
            beforeUnload.run();
        }

        @Override protected void checkClientThread() {
            if (!allowThread) throw new IllegalStateException("wrong thread");
        }
    }
}

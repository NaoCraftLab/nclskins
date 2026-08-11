package com.naocraftlab.skins.compat.mc12111;

import com.naocraftlab.skins.client.ScreenOwnedRenderTarget;
import java.util.function.Supplier;


public final class Minecraft12111BakedPreviewTarget implements AutoCloseable {
    private final ScreenOwnedRenderTarget target = new ScreenOwnedRenderTarget();

    public <T extends AutoCloseable> T acquire(
            Object expectedOwner, Supplier<T> factory, Class<T> type) {
        return target.acquire(expectedOwner, factory, type);
    }

    public void release(Object expectedOwner) {
        target.release(expectedOwner);
    }

    @Override
    public void close() {
        target.close();
    }
}

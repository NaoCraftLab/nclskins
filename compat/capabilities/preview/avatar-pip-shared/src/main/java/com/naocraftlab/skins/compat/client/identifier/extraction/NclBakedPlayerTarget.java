package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.naocraftlab.skins.client.ScreenOwnedRenderTarget;
import java.util.function.Supplier;

public final class NclBakedPlayerTarget implements AutoCloseable {
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

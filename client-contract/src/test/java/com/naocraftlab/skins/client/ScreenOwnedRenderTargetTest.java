package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScreenOwnedRenderTargetTest {
    @Test
    void oneSlotKeepsOneResourceAndClosesItExactlyOnce() {
        ScreenOwnedRenderTarget target = new ScreenOwnedRenderTarget();
        Object dispatcher = new Object();
        AtomicInteger closes = new AtomicInteger();
        Resource resource = target.acquire(
                dispatcher, () -> new Resource(closes), Resource.class);

        assertSame(
                resource,
                target.acquire(dispatcher, () -> new Resource(closes), Resource.class));

        target.close();
        target.close();
        assertEquals(1, closes.get());
        assertThrows(
                IllegalStateException.class,
                () -> target.acquire(dispatcher, () -> new Resource(closes), Resource.class));
    }

    @Test
    void dispatcherReleaseAllowsTheSameScreenSlotToRebind() {
        ScreenOwnedRenderTarget target = new ScreenOwnedRenderTarget();
        Object firstDispatcher = new Object();
        Object secondDispatcher = new Object();
        AtomicInteger closes = new AtomicInteger();

        target.acquire(firstDispatcher, () -> new Resource(closes), Resource.class);
        assertThrows(
                IllegalStateException.class,
                () -> target.acquire(
                        secondDispatcher, () -> new Resource(closes), Resource.class));

        target.release(firstDispatcher);
        target.acquire(secondDispatcher, () -> new Resource(closes), Resource.class);
        target.close();

        assertEquals(2, closes.get());
    }

    private record Resource(AtomicInteger closes) implements AutoCloseable {
        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}

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
    void replacementDispatcherClosesTheOldResourceAndRebindsImmediately() {
        ScreenOwnedRenderTarget target = new ScreenOwnedRenderTarget();
        Object firstDispatcher = new Object();
        Object secondDispatcher = new Object();
        AtomicInteger closes = new AtomicInteger();

        Resource first = target.acquire(
                firstDispatcher, () -> new Resource(closes), Resource.class);
        Resource second = target.acquire(
                secondDispatcher, () -> new Resource(closes), Resource.class);

        assertEquals(1, closes.get());
        target.release(firstDispatcher);
        assertEquals(1, closes.get());
        assertSame(
                second,
                target.acquire(
                        secondDispatcher, () -> new Resource(closes), Resource.class));
        target.close();

        assertEquals(2, closes.get());
    }

    @Test
    void failedReplacementFactoryLeavesTheSlotAvailableForRetry() {
        ScreenOwnedRenderTarget target = new ScreenOwnedRenderTarget();
        Object firstDispatcher = new Object();
        Object secondDispatcher = new Object();
        AtomicInteger closes = new AtomicInteger();

        target.acquire(firstDispatcher, () -> new Resource(closes), Resource.class);
        assertThrows(
                IllegalStateException.class,
                () -> target.acquire(
                        secondDispatcher,
                        () -> {
                            throw new IllegalStateException("factory failed");
                        },
                        Resource.class));
        Resource replacement = target.acquire(
                secondDispatcher, () -> new Resource(closes), Resource.class);

        assertEquals(1, closes.get());
        assertSame(
                replacement,
                target.acquire(
                        secondDispatcher, () -> new Resource(closes), Resource.class));
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

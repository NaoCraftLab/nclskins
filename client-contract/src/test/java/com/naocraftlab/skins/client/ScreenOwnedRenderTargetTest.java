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

    @Test
    void queuedMaterialSurvivesCandidatePreparationAndLogicalRetirementUntilConsumerRelease() throws Exception {
        var a = new ScreenOwnedRenderTarget();
        var b = new ScreenOwnedRenderTarget();
        Object dispatcher = new Object();
        AtomicInteger closes = new AtomicInteger();
        var visible = a.acquire(dispatcher, () -> new Material("A", closes), Material.class);
        AutoCloseable consumer;
        try {
            consumer = (AutoCloseable) ScreenOwnedRenderTarget.class.getMethod("retain").invoke(a);
        } catch (NoSuchMethodException missingLifetime) {
            consumer = () -> {};
        }
        var hidden = b.acquire(dispatcher, () -> new Material("B", closes), Material.class);
        a.close();
        org.junit.jupiter.api.Assertions.assertFalse(a.owns(dispatcher, visible));
        assertEquals("B", hidden.read());
        assertEquals("A", visible.read());
        assertEquals(0, closes.get());
        consumer.close();
        consumer.close();
        assertEquals(1, closes.get());
        assertThrows(IllegalStateException.class, visible::read);
        b.close();
        assertEquals(2, closes.get());
    }

    private static final class Material implements AutoCloseable {
        private final String data;
        private final AtomicInteger closes;
        private boolean closed;
        private Material(String data, AtomicInteger closes) { this.data = data; this.closes = closes; }
        String read() {
            if (closed) throw new IllegalStateException("Queued material closed before GUI consumption");
            return data;
        }
        public void close() { closed = true; closes.incrementAndGet(); }
    }

    private record Resource(AtomicInteger closes) implements AutoCloseable {
        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}

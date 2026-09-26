package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SneakyCapeProcessorTest {
    @Test
    void preparationRunsOnWorkerAndDeliveryOnClientWithNegativeCaching() {
        Harness h = new Harness();
        AtomicInteger decodes = new AtomicInteger();
        SneakyCapeProcessor.Preparation decode = () -> {
            assertTrue(h.worker.running);
            assertFalse(h.client.running);
            decodes.incrementAndGet();
            return new SneakyCapeProcessor.Prepared(null, null);
        };
        h.processor.prepare("skin", decode);
        h.processor.prepare("skin", decode);
        assertEquals(1, h.worker.jobs.size());
        assertEquals(0, decodes.get());
        h.worker.drain();
        assertTrue(h.ready.isEmpty());
        h.client.drain();
        assertEquals(List.of("skin"), h.ready);
        h.processor.prepare("skin", new int[4096]);
        assertEquals(1, decodes.get());
        assertTrue(h.worker.jobs.isEmpty());
        assertNotNull(h.processor.prepared("skin"));
        assertNull(h.processor.prepared("skin").cape());
    }

    @Test
    void failedPreparationDoesNotCacheAbsenceAndCanRetry() {
        Harness h = new Harness();
        h.processor.prepare("skin", () -> { throw new IOException(); });
        h.drain();
        assertFalse(h.processor.contains("skin"));
        assertTrue(h.ready.isEmpty());
        h.processor.prepare("skin", new int[4096]);
        h.drain();
        assertNotNull(h.processor.prepared("skin"));
    }

    @Test
    void staleCompletionDoesNotCacheNegativeOrConsumeNewDemand() {
        Harness h = new Harness();
        h.processor.prepare("skin", new int[4096]);
        h.processor.invalidate();
        h.processor.prepare("skin", new int[4096]);
        h.worker.runOne();
        h.client.runOne();
        assertNull(h.processor.prepared("skin"));
        assertTrue(h.ready.isEmpty());
        h.drain();
        assertEquals(List.of("skin"), h.ready);
    }

    @Test
    void changedAccountBeforeLifecycleNotificationCannotCacheAbsence() {
        Harness h = new Harness();
        h.processor.prepare("skin", new int[4096]);
        h.current = false;
        h.drain();
        assertFalse(h.processor.contains("skin"));
        assertTrue(h.ready.isEmpty());
        h.current = true;
        h.processor.prepare("skin", new int[4096]);
        h.drain();
        assertEquals(List.of("skin"), h.ready);
    }

    @Test
    void overloadRetainsLatestTrackedDemandWithoutSubmittingUnboundedWork() {
        Harness h = new Harness();
        for (int index = 0; index < 513; index++) {
            h.processor.prepare("queued-" + index, new int[4096]);
        }
        assertEquals(4, h.worker.jobs.size());
        h.retained.add("A");
        h.processor.prepare("A", new int[4096]);
        h.retained.remove("A");
        h.processor.forget("A");
        h.retained.add("B");
        h.processor.prepare("B", new int[4096]);
        for (int index = 0; index < 1000; index++) h.processor.prepare("B", new int[4096]);
        assertEquals(4, h.worker.jobs.size());
        h.drain();
        assertFalse(h.ready.contains("A"));
        assertEquals(1, h.ready.stream().filter("B"::equals).count());
        assertTrue(h.ready.size() <= 513);
    }

    @Test
    void untrackAndCloseRemovePendingDemand() {
        Harness h = new Harness();
        h.processor.prepare("departed", new int[4096]);
        h.processor.forget("departed");
        h.drain();
        assertNull(h.processor.prepared("departed"));
        h.processor.prepare("closed", new int[4096]);
        h.processor.close();
        h.drain();
        assertTrue(h.ready.isEmpty());
        assertNull(h.processor.prepared("closed"));
    }

    private static final class Harness {
        private final Queue worker = new Queue();
        private final Queue client = new Queue();
        private final List<String> ready = new ArrayList<>();
        private boolean current = true;
        private final Set<String> retained = new HashSet<>();
        private final SneakyCapeProcessor processor = new SneakyCapeProcessor(worker, client, key -> {
            assertTrue(client.running);
            ready.add(key);
        }, () -> retained, () -> current);

        private void drain() {
            int iterations = 0;
            while (!worker.jobs.isEmpty() || !client.jobs.isEmpty()) {
                assertTrue(iterations++ < 1000);
                worker.drain();
                client.drain();
            }
        }
    }

    private static final class Queue implements Executor, ClientExecutor {
        private final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        private boolean running;
        public boolean isClientThread() { return running; }
        public void execute(Runnable action) { jobs.add(action); }
        private void runOne() {
            running = true;
            try { jobs.remove().run(); }
            finally { running = false; }
        }
        private void drain() { while (!jobs.isEmpty()) runOne(); }
    }
}

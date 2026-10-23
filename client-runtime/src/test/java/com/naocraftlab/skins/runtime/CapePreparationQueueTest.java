package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class CapePreparationQueueTest {
    @Test
    void remoteReadCapacityNoticeWaitsForDescriptorAllowanceWithoutRequiringDecoderCpu() {
        long descriptor = 64L * 1024;
        long headroom = 2L * 1024 * 1024 * 1024 - CapePreparationQueue.CAPE_PREPARATION_PEAK;
        var scratch = CapePreparationQueue.tryReserveScratch(headroom);
        var cpu = CapePreparationQueue.tryReserveCpu(64L * 1024 * 1024);
        assertNotNull(scratch);
        assertNotNull(cpu);
        List<String> ready = new ArrayList<>();
        var notice = CapePreparationQueue.capacityNotice(CapePreparationQueue.CAPE_PREPARATION_PEAK
                + descriptor, 0, ignored -> ready.add("read"));
        try {
            notice.arm();
            assertTrue(ready.isEmpty(), "the remote read also reserves its descriptor allowance");
            scratch.shrinkTo(headroom - descriptor);
            assertEquals(List.of("read"), ready);
            assertNull(CapePreparationQueue.tryReserveCpu(1));
            scratch.close();
            assertEquals(List.of("read"), ready, "capacity wakeup is one-shot");
        } finally {
            notice.close();
            scratch.close();
            cpu.close();
        }
    }

    @Test
    void failedCapacityCallbackDoesNotEscapeReleaseOrStarveOtherNotices() {
        var pressure = CapePreparationQueue.tryReserveScratch(512L * 1024 * 1024);
        assertNotNull(pressure);
        List<String> delivered = new ArrayList<>();
        var rejected = CapePreparationQueue.capacityNotice(ignored -> {
            throw new RejectedExecutionException("closed client executor");
        });
        var healthy = CapePreparationQueue.capacityNotice(ignored -> delivered.add("released"));
        rejected.arm();
        healthy.arm();
        assertDoesNotThrow(pressure::close);
        assertEquals(List.of("released"), delivered);
        assertEquals(0, CapePreparationQueue.reservedScratchBytes());
        assertDoesNotThrow(() -> CapePreparationQueue.capacityNotice(ignored -> {
            throw new IllegalStateException("stale immediate subscriber");
        }).arm());
        var immediate = CapePreparationQueue.capacityNotice(ignored -> delivered.add("immediate"));
        immediate.arm();
        assertEquals(List.of("released", "immediate"), delivered);
        rejected.close();
        healthy.close();
        immediate.close();
    }

    @Test
    void capacityNoticeIsOneShotCancellableAndIgnoresUnrelatedRelease() {
        List<String> notifications = new ArrayList<>();
        var scratch = CapePreparationQueue.tryReserveScratch(512L * 1024 * 1024);
        var cpu = CapePreparationQueue.tryReserveCpu(40L * 1024 * 1024);
        assertNotNull(scratch);
        assertNotNull(cpu);
        try {
            var notice = CapePreparationQueue.capacityNotice(ignored -> notifications.add("ready"));
            notice.arm();
            scratch.close();
            assertTrue(notifications.isEmpty(), "scratch alone cannot satisfy the decoder CPU bound");
            cpu.close();
            assertEquals(List.of("ready"), notifications);
            notice.close();
            var sourcePixels = CapePreparationQueue.tryReserveCpu(40L * 1024 * 1024);
            assertNotNull(sourcePixels);
            var nativeRelease = CapePreparationQueue.capacityNotice(ignored -> {
                assertFalse(Thread.holdsLock(CapePreparationQueue.class),
                        "capacity callbacks must not run inside the quota monitor");
                notifications.add("native");
            });
            nativeRelease.arm();
            sourcePixels.sourcePixelsReleased(0);
            assertEquals(List.of("ready", "native"), notifications);
            nativeRelease.close();
            sourcePixels.close();
            var immediate = CapePreparationQueue.capacityNotice(ignored -> notifications.add("immediate"));
            immediate.arm();
            assertEquals(List.of("ready", "native", "immediate"), notifications,
                    "release before registration must not lose the wakeup");
            immediate.close();
            var occupied = CapePreparationQueue.tryReserveScratch(512L * 1024 * 1024);
            assertNotNull(occupied);
            try (occupied) {
                var cancelled = CapePreparationQueue.capacityNotice(
                        ignored -> fail("cancelled runtime must not receive capacity"));
                cancelled.arm();
                cancelled.close();
            }
            assertEquals(List.of("ready", "native", "immediate"), notifications);
        } finally {
            scratch.close();
            cpu.close();
        }
    }

    @Test
    void repeatedPreparationRetainsOnlyLatestWorkPerRole() {
        ArrayDeque<Runnable> worker = new ArrayDeque<>();
        List<Integer> completed = new ArrayList<>();
        CapePreparationQueue queue = new CapePreparationQueue(worker::add);
        queue.submit(CapePreparationQueue.Slot.SELF_CANDIDATES, () -> completed.add(0));
        for (int index = 1; index <= 1000; index++) {
            int value = index;
            queue.submit(CapePreparationQueue.Slot.SELF_CANDIDATES, () -> completed.add(value));
        }
        assertEquals(1, worker.size());
        worker.remove().run();
        assertEquals(1, worker.size());
        worker.remove().run();
        assertEquals(List.of(0, 1000), completed);
    }

    @Test
    void closeDoesNotDispatchDirtyWork() {
        ArrayDeque<Runnable> worker = new ArrayDeque<>();
        List<Integer> completed = new ArrayList<>();
        CapePreparationQueue queue = new CapePreparationQueue(worker::add);
        queue.submit(CapePreparationQueue.Slot.OPTIFINE_ADOPTION, () -> completed.add(0));
        queue.submit(CapePreparationQueue.Slot.OPTIFINE_ADOPTION, () -> fail("retired demand"));
        queue.close();
        worker.remove().run();
        assertTrue(worker.isEmpty());
        queue.submit(CapePreparationQueue.Slot.SKINMC_ADOPTION, () -> fail("closed queue"));
        assertTrue(worker.isEmpty());
    }

    @Test
    @SuppressWarnings("try")
    void closeWakesPreparationWaitingForAggregateScratchCapacity() throws Exception {
        CapePreparationQueue queue = new CapePreparationQueue(Runnable::run);
        try (var occupied = CapePreparationQueue.tryReserveScratch(2L * 1024 * 1024 * 1024)) {
            assertNotNull(occupied, "prior preparation owners must release scratch capacity before this test");
            var worker = Executors.newSingleThreadExecutor();
            CountDownLatch entered = new CountDownLatch(1);
            try {
                var waiting = worker.submit(() -> {
                    entered.countDown();
                    try (var ignored = CapePreparationQueue.reserveScratch(1, () -> !queueClosed.get())) {
                        return false;
                    } catch (InterruptedException closed) {
                        return true;
                    }
                });
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                queueClosed.set(true);
                queue.close();
                assertTrue(waiting.get(1, TimeUnit.SECONDS));
            } finally {
                worker.shutdownNow();
                queueClosed.set(false);
            }
        }
    }

    private final AtomicBoolean queueClosed = new AtomicBoolean();

    @Test
    @SuppressWarnings("try")
    void retainedNativeFramesAndDecoderShareTheSameCpuCeiling() {
        try (var current = CapePreparationQueue.tryReserveCpu(16L * 1024 * 1024);
                var previous = CapePreparationQueue.tryReserveCpu(16L * 1024 * 1024);
                var decoder = CapePreparationQueue.tryReserveCpu(32L * 1024 * 1024)) {
            assertNotNull(current);
            assertNotNull(previous);
            assertNotNull(decoder);
            assertNull(CapePreparationQueue.tryReserveCpu(1),
                    "a third frame must leave the installed frame visible until capacity returns");
        }
        try (var full = CapePreparationQueue.tryReserveCpu(64L * 1024 * 1024)) {
            assertNotNull(full, "all native and decoder leases must return after retirement");
        }
    }

    @Test
    @SuppressWarnings("try")
    void retiredFrameCancelsAWaitingCpuReservation() throws Exception {
        AtomicBoolean active = new AtomicBoolean(true);
        try (var occupied = CapePreparationQueue.tryReserveCpu(64L * 1024 * 1024)) {
            assertNotNull(occupied);
            var worker = Executors.newSingleThreadExecutor();
            CountDownLatch entered = new CountDownLatch(1);
            try {
                var waiting = worker.submit(() -> {
                    entered.countDown();
                    try (var ignored = CapePreparationQueue.reserveCpu(16L * 1024 * 1024,
                            active::get)) {
                        return false;
                    } catch (InterruptedException retired) {
                        return true;
                    }
                });
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                active.set(false);
                CapePreparationQueue.wakeWaiters();
                assertTrue(waiting.get(1, TimeUnit.SECONDS));
            } finally {
                worker.shutdownNow();
            }
        }
    }

    @Test
    void rejectedDispatchDoesNotStrandLatestSlot() {
        AtomicBoolean reject = new AtomicBoolean(true);
        List<Integer> completed = new ArrayList<>();
        CapePreparationQueue queue = new CapePreparationQueue(action -> {
            if (reject.get()) throw new RejectedExecutionException();
            action.run();
        });
        queue.submit(CapePreparationQueue.Slot.SELF_CANDIDATES, () -> completed.add(1));
        reject.set(false);
        queue.submit(CapePreparationQueue.Slot.SELF_CANDIDATES, () -> completed.add(2));
        assertEquals(List.of(2), completed);
    }
}

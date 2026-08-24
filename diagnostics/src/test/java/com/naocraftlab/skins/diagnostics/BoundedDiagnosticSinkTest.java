package com.naocraftlab.skins.diagnostics;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;


final class BoundedDiagnosticSinkTest {
    @Test
    void disabledLevelDoesNotEvaluateDetails() {
        RecordingSink sink = new RecordingSink(new AtomicLong()::get, false);
        AtomicInteger evaluations = new AtomicInteger();

        sink.report(DiagnosticEvent.CLIENT_PICKER_FAILED, () -> {
            evaluations.incrementAndGet();
            return DiagnosticDetails.failure(new IllegalStateException("secret"));
        });

        assertEquals(0, evaluations.get());
        assertTrue(sink.entries.isEmpty());
    }

    @Test
    void trafficUsesOneFixedSlotAndEscalatesOnlyAtThreshold() {
        AtomicLong time = new AtomicLong();
        RecordingSink sink = new RecordingSink(time::get, true);
        int initialWindows = sink.retainedWindowCount();
        AtomicInteger evaluations = new AtomicInteger();

        for (int index = 0; index < 1_000_000; index++) {
            sink.report(DiagnosticEvent.RELAY_MALFORMED, () -> {
                evaluations.incrementAndGet();
                return DiagnosticDetails.none();
            });
        }

        assertEquals(initialWindows, sink.retainedWindowCount());
        assertEquals(2, evaluations.get());
        assertEquals(List.of(DiagnosticLevel.DEBUG, DiagnosticLevel.WARN),
                sink.entries.stream().map(Entry::level).toList());
        assertTrue(sink.entries.get(1).message().contains("suppressed=8"));
    }

    @Test
    void windowSlotsAreReusedAcrossRollover() {
        AtomicLong time = new AtomicLong();
        RecordingSink sink = new RecordingSink(time::get, true);

        for (int index = 0; index < 10_000; index++) {
            sink.report(DiagnosticEvent.CLIENT_IMPORT_FAILED, DiagnosticDetails::none);
            time.addAndGet(Duration.ofSeconds(61).toNanos());
        }

        assertEquals(DiagnosticEvent.values().length, sink.retainedWindowCount());
        assertEquals(10_000, sink.entries.size());
    }

    @Test
    void concurrentStormKeepsStateBounded() throws InterruptedException {
        RecordingSink sink = new RecordingSink(new AtomicLong()::get, true);
        int workers = 12;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int worker = 0; worker < workers; worker++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int index = 0; index < 10_000; index++) {
                    sink.report(DiagnosticEvent.RELAY_STALE, DiagnosticDetails::none);
                }
            });
            thread.start();
            threads.add(thread);
        }
        ready.await();
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(DiagnosticEvent.values().length, sink.retainedWindowCount());
        assertEquals(2, sink.entries.size());
    }

    @Test
    void sanitizedFailureDropsMessagesSuppressedAndUntrustedValues() {
        String canary = "Bearer abc.def.ghi https://example.invalid/a?token=secret /Users/private";
        IllegalArgumentException cause = new IllegalArgumentException(canary);
        IllegalStateException failure = new IllegalStateException(canary, cause);
        failure.addSuppressed(new RuntimeException(canary));

        SanitizedFailure sanitized = SanitizedFailure.from(failure);
        Throwable safe = sanitized.asThrowable();
        StringWriter output = new StringWriter();
        safe.printStackTrace(new PrintWriter(output));

        assertEquals(2, sanitized.causeCount());
        assertTrue(sanitized.frameCount() <= SanitizedFailure.MAX_FRAMES);
        assertFalse(output.toString().contains(canary));
        assertFalse(output.toString().contains("Bearer"));
        assertEquals(0, safe.getSuppressed().length);
        assertEquals(0, safe.getCause().getSuppressed().length);
    }

    @Test
    void closeClearsAndRejectsFurtherReports() {
        RecordingSink sink = new RecordingSink(new AtomicLong()::get, true);
        sink.report(DiagnosticEvent.PLUGIN_READY, DiagnosticDetails::none);
        Entry beforeClose = sink.entries.get(0);

        sink.close();
        sink.report(DiagnosticEvent.PLUGIN_READY, DiagnosticDetails::none);

        assertEquals(1, sink.entries.size());
        assertSame(beforeClose, sink.entries.get(0));
    }

    private record Entry(DiagnosticLevel level, String message, Throwable failure) {}

    private static final class RecordingSink extends BoundedDiagnosticSink {
        private final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());
        private final boolean debugEnabled;

        private RecordingSink(AtomicLong unused, boolean debugEnabled) {
            this(unused::get, debugEnabled);
        }

        private RecordingSink(
                java.util.function.LongSupplier time, boolean debugEnabled) {
            super(time, Duration.ofSeconds(60));
            this.debugEnabled = debugEnabled;
        }

        @Override
        protected boolean enabled(DiagnosticLevel level) {
            return level != DiagnosticLevel.DEBUG || debugEnabled;
        }

        @Override
        protected void emit(
                DiagnosticLevel level, String message, Throwable failure) {
            entries.add(new Entry(level, message, failure));
        }
    }
}

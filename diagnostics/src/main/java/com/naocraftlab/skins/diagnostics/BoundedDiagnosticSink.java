package com.naocraftlab.skins.diagnostics;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;


public abstract class BoundedDiagnosticSink implements DiagnosticSink, AutoCloseable {
    private static final int SUPPRESSED = -1;
    private static final int BASE_LEVEL = 0;
    private static final int WARNING_LEVEL = 1;

    private final EventWindow[] windows;
    private final LongSupplier nanoTime;
    private final long windowNanos;
    private volatile boolean closed;

    protected BoundedDiagnosticSink() {
        this(System::nanoTime, DiagnosticEvent.DEFAULT_WINDOW);
    }

    protected BoundedDiagnosticSink(LongSupplier nanoTime, Duration window) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        Duration checkedWindow = Objects.requireNonNull(window, "window");
        if (checkedWindow.isZero() || checkedWindow.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.windowNanos = checkedWindow.toNanos();
        DiagnosticEvent[] events = DiagnosticEvent.values();
        windows = new EventWindow[events.length];
        for (int index = 0; index < windows.length; index++) {
            windows[index] = new EventWindow();
        }
    }

    @Override
    public final void report(
            DiagnosticEvent event, Supplier<DiagnosticDetails> detailsSupplier) {
        DiagnosticEvent checkedEvent = Objects.requireNonNull(event, "event");
        Supplier<DiagnosticDetails> checkedSupplier =
                Objects.requireNonNull(detailsSupplier, "details");
        if (closed) {
            return;
        }
        EventWindow window = windows[checkedEvent.ordinal()];
        Decision decision = decide(checkedEvent, window, nanoTime.getAsLong());
        if (decision.code() == SUPPRESSED) {
            return;
        }
        DiagnosticLevel level = decision.code() == WARNING_LEVEL
                ? DiagnosticLevel.WARN : checkedEvent.level();
        if (!enabled(level)) {
            return;
        }
        DiagnosticDetails details;
        try {
            details = Objects.requireNonNull(checkedSupplier.get(), "diagnostic details");
        } catch (RuntimeException detailsFailure) {
            details = DiagnosticDetails.failure(detailsFailure);
        }
        emit(level, format(checkedEvent.message(), details, decision.suppressed()),
                details.failure().map(SanitizedFailure::asThrowable).orElse(null));
    }

    protected abstract boolean enabled(DiagnosticLevel level);

    protected abstract void emit(
            DiagnosticLevel level, String message, Throwable failure);

    @Override
    public final void close() {
        closed = true;
        for (EventWindow window : windows) {
            window.clear();
        }
    }

    final int retainedWindowCount() {
        return windows.length;
    }

    private Decision decide(DiagnosticEvent event, EventWindow window, long now) {
        return switch (event.suppression()) {
            case ALWAYS -> new Decision(BASE_LEVEL, 0L);
            case ONCE -> window.once();
            case WINDOW -> window.window(now, windowNanos);
            case TRAFFIC_THRESHOLD -> window.traffic(now, windowNanos);
        };
    }

    private static String format(
            String message, DiagnosticDetails details, long suppressed) {
        StringBuilder output = new StringBuilder(message);
        details.status().ifPresent(status -> output.append(" status=").append(status));
        details.count().ifPresent(count -> output.append(" count=").append(count));
        details.attempt().ifPresent(attempt -> output.append(" attempt=").append(attempt));
        details.duration().ifPresent(duration ->
                output.append(" durationMs=").append(duration.toMillis()));
        if (suppressed > 0L) {
            output.append(" suppressed=").append(suppressed);
        }
        return output.toString();
    }

    private record Decision(int code, long suppressed) {}

    private static final class EventWindow {
        private long windowStart;
        private long count;
        private boolean initialized;
        private boolean emitted;

        private synchronized Decision once() {
            if (emitted) {
                return new Decision(SUPPRESSED, 0L);
            }
            emitted = true;
            return new Decision(BASE_LEVEL, 0L);
        }

        private synchronized Decision window(long now, long duration) {
            if (!initialized || elapsed(now, windowStart, duration)) {
                long suppressed = initialized ? Math.max(0L, count - 1L) : 0L;
                initialized = true;
                windowStart = now;
                count = 1L;
                return new Decision(BASE_LEVEL, suppressed);
            }
            count = increment(count);
            return new Decision(SUPPRESSED, 0L);
        }

        private synchronized Decision traffic(long now, long duration) {
            if (!initialized || elapsed(now, windowStart, duration)) {
                initialized = true;
                windowStart = now;
                count = 1L;
                return new Decision(BASE_LEVEL, 0L);
            }
            count = increment(count);
            if (count == DiagnosticEvent.TRAFFIC_WARNING_THRESHOLD) {
                return new Decision(WARNING_LEVEL,
                        DiagnosticEvent.TRAFFIC_WARNING_THRESHOLD - 2L);
            }
            return new Decision(SUPPRESSED, 0L);
        }

        private synchronized void clear() {
            windowStart = 0L;
            count = 0L;
            initialized = false;
            emitted = false;
        }

        private static boolean elapsed(long now, long start, long duration) {
            return now - start >= duration || now - start < 0L;
        }

        private static long increment(long value) {
            return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
        }
    }
}

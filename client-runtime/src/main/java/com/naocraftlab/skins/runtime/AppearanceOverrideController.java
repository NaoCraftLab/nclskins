package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ExpectedAppearance;
import com.naocraftlab.skins.client.PlayerAppearanceSink.ApplyResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


public final class AppearanceOverrideController<N, H> implements AutoCloseable {
    public interface Strategy<N, H> {
        ExpectedAppearance expected(N override);

        List<H> handles(N override);


        ApplyResult attach(N override);


        void restore();

        void release(H handle);
    }

    private final Strategy<N, H> strategy;
    private N active;
    private boolean closed;

    public AppearanceOverrideController(Strategy<N, H> strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    public Optional<N> active() {
        return Optional.ofNullable(active);
    }


    public ApplyResult install(N replacement) {
        Objects.requireNonNull(replacement, "replacement");
        ensureOpen();
        ApplyResult result = Objects.requireNonNull(
                strategy.attach(replacement), "attachment result");
        N previous = active;
        active = replacement;
        release(previous);
        return result;
    }


    public Optional<ApplyResult> reattachIfActive(ExpectedAppearance expected) {
        Objects.requireNonNull(expected, "expected");
        ensureOpen();
        N installed = active;
        if (installed == null || !strategy.expected(installed).equals(expected)) {
            return Optional.empty();
        }
        return Optional.of(Objects.requireNonNull(
                strategy.attach(installed), "attachment result"));
    }

    public ApplyResult reattach(ExpectedAppearance expected) {
        return reattachIfActive(expected).orElse(ApplyResult.DEFERRED);
    }


    public void clear() {
        ensureOpen();
        N installed = active;
        active = null;
        if (installed != null) {
            strategy.restore();
            release(installed);
        }
    }


    public void invalidate(ExpectedAppearance expected) {
        Objects.requireNonNull(expected, "expected");
        ensureOpen();
        N installed = active;
        if (installed == null) {
            return;
        }
        if (strategy.expected(installed).equals(expected)) {
            strategy.attach(installed);
            return;
        }
        active = null;
        strategy.restore();
        release(installed);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        N installed = active;
        active = null;
        if (installed != null) {
            strategy.restore();
            release(installed);
        }
    }

    private void release(N installed) {
        if (installed == null) {
            return;
        }
        List<H> handles = Objects.requireNonNull(strategy.handles(installed), "override handles");
        for (H handle : handles) {
            if (handle != null) {
                try {
                    strategy.release(handle);
                } catch (RuntimeException ignored) {


                }
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Appearance override controller is closed");
        }
    }
}

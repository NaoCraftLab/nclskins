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
    private N attached;
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
        N previousActive = active;
        N previousAttached = attached;
        active = replacement;
        if (result == ApplyResult.UPDATED) {
            attached = replacement;
        }
        releaseRemoved(previousActive, previousAttached, active, attached);
        return result;
    }


    public Optional<ApplyResult> reattachIfActive(ExpectedAppearance expected) {
        Objects.requireNonNull(expected, "expected");
        ensureOpen();
        N installed = active;
        if (installed == null || !strategy.expected(installed).equals(expected)) {
            return Optional.empty();
        }
        ApplyResult result = Objects.requireNonNull(
                strategy.attach(installed), "attachment result");
        if (result == ApplyResult.UPDATED) {
            N previousAttached = attached;
            attached = installed;
            releaseRemoved(null, previousAttached, active, attached);
        }
        return Optional.of(result);
    }

    public ApplyResult reattach(ExpectedAppearance expected) {
        return reattachIfActive(expected).orElse(ApplyResult.DEFERRED);
    }


    public void clear() {
        ensureOpen();
        N previousActive = active;
        N previousAttached = attached;
        active = null;
        attached = null;
        if (previousActive != null || previousAttached != null) {
            strategy.restore();
            releaseRemoved(previousActive, previousAttached, null, null);
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
            ApplyResult result = Objects.requireNonNull(
                    strategy.attach(installed), "attachment result");
            if (result == ApplyResult.UPDATED) {
                N previousAttached = attached;
                attached = installed;
                releaseRemoved(null, previousAttached, active, attached);
            }
            return;
        }
        N previousAttached = attached;
        active = null;
        attached = null;
        strategy.restore();
        releaseRemoved(installed, previousAttached, null, null);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        N previousActive = active;
        N previousAttached = attached;
        active = null;
        attached = null;
        if (previousActive != null || previousAttached != null) {
            strategy.restore();
            releaseRemoved(previousActive, previousAttached, null, null);
        }
    }

    private void releaseRemoved(
            N previousActive,
            N previousAttached,
            N nextActive,
            N nextAttached) {
        if (!retained(previousActive, nextActive, nextAttached)) {
            release(previousActive);
        }
        if (previousAttached != previousActive
                && !retained(previousAttached, nextActive, nextAttached)) {
            release(previousAttached);
        }
    }

    private static boolean retained(Object candidate, Object active, Object attached) {
        return candidate == null || candidate == active || candidate == attached;
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

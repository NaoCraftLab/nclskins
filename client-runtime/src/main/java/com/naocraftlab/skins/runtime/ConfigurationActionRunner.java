package com.naocraftlab.skins.runtime;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;


public final class ConfigurationActionRunner {
    public enum State {
        IDLE,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    private final ConfigurationAction action;
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private volatile ConfigurationAction.Result result;

    public ConfigurationActionRunner(ConfigurationAction action) {
        this.action = Objects.requireNonNull(action, "action");
    }

    public State state() {
        return state.get();
    }

    public ConfigurationAction.Result result() {
        return result;
    }

    public CompletionStage<ConfigurationAction.Result> run() {
        if (!state.compareAndSet(State.IDLE, State.RUNNING)
                && !state.compareAndSet(State.SUCCEEDED, State.RUNNING)
                && !state.compareAndSet(State.FAILED, State.RUNNING)) {
            return CompletableFuture.completedFuture(
                    ConfigurationAction.Result.failed("Action is already running"));
        }
        final CompletionStage<ConfigurationAction.Result> execution;
        try {
            execution = Objects.requireNonNull(action.execute(), "action result");
        } catch (RuntimeException failure) {
            ConfigurationAction.Result failed = ConfigurationAction.Result.failed(failure.getMessage());
            result = failed;
            state.set(State.FAILED);
            return CompletableFuture.completedFuture(failed);
        }
        return execution.handle((completed, failure) -> {
            ConfigurationAction.Result outcome = failure == null
                    ? Objects.requireNonNull(completed, "completed action result")
                    : ConfigurationAction.Result.failed(failure.getMessage());
            result = outcome;
            state.set(outcome.succeeded() ? State.SUCCEEDED : State.FAILED);
            return outcome;
        });
    }
}

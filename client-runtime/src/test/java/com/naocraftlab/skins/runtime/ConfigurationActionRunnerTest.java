package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;


final class ConfigurationActionRunnerTest {
    @Test
    void executesAgainAfterSuccessAndPublishesTheLatestResult() {
        AtomicInteger executions = new AtomicInteger();
        ConfigurationActionRunner runner = new ConfigurationActionRunner(() ->
                CompletableFuture.completedFuture(
                        ConfigurationAction.Result.succeeded(
                                "run-" + executions.incrementAndGet())));

        assertEquals("run-1", runner.run().toCompletableFuture().join().message());
        assertEquals(ConfigurationActionRunner.State.SUCCEEDED, runner.state());
        assertEquals("run-2", runner.run().toCompletableFuture().join().message());
        assertEquals(2, executions.get());
        assertEquals("run-2", runner.result().message());
    }

    @Test
    void rejectsConcurrentActivationWithoutStartingASecondExecution() {
        AtomicInteger executions = new AtomicInteger();
        CompletableFuture<ConfigurationAction.Result> pending = new CompletableFuture<>();
        ConfigurationActionRunner runner = new ConfigurationActionRunner(() -> {
            executions.incrementAndGet();
            return pending;
        });

        runner.run();
        ConfigurationAction.Result rejected = runner.run().toCompletableFuture().join();

        assertEquals(1, executions.get());
        assertEquals("Action is already running", rejected.message());
        assertEquals(ConfigurationActionRunner.State.RUNNING, runner.state());
        pending.complete(ConfigurationAction.Result.succeeded("done"));
        assertEquals(ConfigurationActionRunner.State.SUCCEEDED, runner.state());
    }

    @Test
    void turnsSynchronousFailureIntoAReusableFailedState() {
        AtomicInteger executions = new AtomicInteger();
        ConfigurationActionRunner runner = new ConfigurationActionRunner(() -> {
            if (executions.incrementAndGet() == 1) {
                throw new IllegalStateException("broken");
            }
            return CompletableFuture.completedFuture(
                    ConfigurationAction.Result.succeeded("recovered"));
        });

        assertEquals("broken", runner.run().toCompletableFuture().join().message());
        assertEquals(ConfigurationActionRunner.State.FAILED, runner.state());
        assertEquals("recovered", runner.run().toCompletableFuture().join().message());
        assertEquals(ConfigurationActionRunner.State.SUCCEEDED, runner.state());
    }
}

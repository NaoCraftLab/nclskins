package com.naocraftlab.skins.runtime.composition;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.config.ClientConfiguration;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import com.naocraftlab.skins.runtime.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class ClientCompositionRootTest {
    @TempDir Path directory;
    private final AtomicInteger configurationReads = new AtomicInteger();

    @Test
    void coldWorldlessGraphDoesNotReadSessionCatalogConfigurationOrSubmitWork() {
        List<Worker> workers = new ArrayList<>();
        ClientRuntime runtime = create(name -> {
            Worker worker = new Worker();
            workers.add(worker);
            return new ClientCompositionRoot.Worker(worker, true);
        });
        AtomicInteger nativeCloses = new AtomicInteger();
        ClientApplicationHost<Object> host = new ClientApplicationHost<>(new com.naocraftlab.skins.runtime.DefaultRuntimeServices(), runtime, nativeCloses::incrementAndGet);
        assertSame(runtime, host.runtime());
        assertSame(host.runtime(), host.runtime());
        assertEquals(ClientSnapshot.Lifecycle.NEW, runtime.snapshot().lifecycle());
        assertEquals(4, workers.size());
        assertEquals(0, configurationReads.get());
        assertFalse(Files.exists(directory.resolve("data")));
        assertTrue(workers.stream().allMatch(worker -> worker.submissions == 0));
        host.close();
        host.close();
        assertTrue(runtime.closed());
        assertEquals(1, nativeCloses.get());
        assertTrue(workers.stream().allMatch(worker -> worker.closes == 1));
    }

    @Test
    void failureCreatingEachWorkerClosesOnlyPreviouslyCreatedOwnedWorkers() {
        for (int failAt = 0; failAt < 4; failAt++) {
            List<Worker> workers = new ArrayList<>();
            int failureIndex = failAt;
            RuntimeException expected = new IllegalStateException("startup");
            RuntimeException actual = assertThrows(RuntimeException.class, () -> create(name -> {
                if (workers.size() == failureIndex) throw expected;
                Worker worker = new Worker();
                workers.add(worker);
                return new ClientCompositionRoot.Worker(worker, true);
            }));
            assertSame(expected, actual);
            assertEquals(failAt, workers.size());
            assertTrue(workers.stream().allMatch(worker -> worker.closes == 1));
            assertTrue(workers.stream().allMatch(worker -> worker.submissions == 0));
        }
    }

    @Test
    void externalWorkersSurviveBothFailedCompositionAndRepeatedRuntimeClose() {
        List<Worker> workers = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> create(name -> {
            if (workers.size() == 3) throw new IllegalStateException("startup");
            Worker worker = new Worker();
            workers.add(worker);
            return new ClientCompositionRoot.Worker(worker, false);
        }));
        assertTrue(workers.stream().allMatch(worker -> worker.closes == 0));
        workers.clear();
        ClientRuntime runtime = create(name -> {
            Worker worker = new Worker();
            workers.add(worker);
            return new ClientCompositionRoot.Worker(worker, false);
        });
        runtime.close();
        runtime.close();
        assertTrue(workers.stream().allMatch(worker -> worker.closes == 0));
    }

    @Test
    void cleanupContinuesAfterFailureAndPreservesTheStartupFailure() {
        List<Worker> workers = new ArrayList<>();
        RuntimeException startup = new IllegalStateException("startup");
        RuntimeException actual = assertThrows(RuntimeException.class, () -> create(name -> {
            if (workers.size() == 3) throw startup;
            Worker worker = new Worker();
            worker.failOnClose = true;
            workers.add(worker);
            return new ClientCompositionRoot.Worker(worker, true);
        }));
        assertSame(startup, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertEquals(2, actual.getSuppressed()[0].getSuppressed().length);
        assertTrue(workers.stream().allMatch(worker -> worker.closes == 1));
    }

    @Test
    void partialGraphFailureClosesWorkersAndBoundServicesOnceInReverseOrder() {
        List<String> phases = List.of("worker", "reconciliation", "session", "cape worker", "cape binding", "refresh");
        for (int completed = 1; completed <= phases.size(); completed++) {
            List<String> closed = new ArrayList<>();
            var resources = new ClientCompositionRoot.StartupResources();
            int count = completed;
            assertThrows(IllegalStateException.class, () -> {
                try (resources) {
                    for (int index = 0; index < count; index++) {
                        String phase = phases.get(index);
                        resources.add(() -> closed.add(phase));
                    }
                    throw new IllegalStateException("graph construction failed");
                }
            });
            resources.close();
            List<String> expected = new ArrayList<>(phases.subList(0, completed));
            java.util.Collections.reverse(expected);
            assertEquals(expected, closed);
        }
    }

    @Test
    void successfulTransferLeavesCleanupToTheRuntimeOwner() {
        AtomicInteger closed = new AtomicInteger();
        try (var resources = new ClientCompositionRoot.StartupResources()) {
            resources.add(closed::incrementAndGet);
            resources.transfer();
        }
        assertEquals(0, closed.get());
    }

    private ClientRuntime create(ClientCompositionRoot.WorkerFactory factory) {
        ClientExecutor client = new ClientExecutor() {
            public boolean isClientThread() { return true; }
            public void execute(Runnable action) { action.run(); }
        };
        GameSessionTokenSource tokens = new GameSessionTokenSource() {
            public SessionIdentity currentSession() { throw new AssertionError("session read during composition"); }
            public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                throw new AssertionError("credential access during composition");
            }
        };
        ClientCapabilitySet capabilities = new ClientCapabilitySet(tokens,
                (collection, skin, model) -> { throw new AssertionError("catalog read during composition"); },
                () -> { throw new AssertionError("appearance read during composition"); }, client,
                () -> CompletableFuture.completedFuture(Optional.empty()), SignedTextureVerifier.rejecting(),
                resolved -> PlayerAppearanceSink.ApplyResult.UPDATED, visibility -> {},
                ServerAppearanceRefreshNotifier.NO_OP);
        return ClientCompositionRoot.createRuntime(capabilities, UiMessage::key, directory.resolve("data"),
                () -> { configurationReads.incrementAndGet(); return ClientConfiguration.defaults(); },
                DiagnosticSinks.discarding(), factory);
    }

    private static final class Worker extends AbstractExecutorService {
        int closes;
        int submissions;
        boolean failOnClose;
        public void shutdown() { shutdownNow(); }
        public List<Runnable> shutdownNow() {
            closes++;
            if (failOnClose) throw new IllegalStateException("cleanup");
            return List.of();
        }
        public boolean isShutdown() { return closes > 0; }
        public boolean isTerminated() { return isShutdown(); }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
        public void execute(Runnable command) { submissions++; }
    }
}

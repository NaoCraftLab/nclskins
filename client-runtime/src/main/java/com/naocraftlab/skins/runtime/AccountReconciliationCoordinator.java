package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import static com.naocraftlab.skins.runtime.AccountReconciliationPort.*;

final class AccountReconciliationCoordinator implements AccountReconciliation {
    private final AccountReconciliationPort operations;
    private final Executor worker;
    private final Consumer<Runnable> client;
    private final Completion completion;
    private final Runnable idle;
    private final BiConsumer<DiagnosticEvent, Throwable> diagnose;
    private final Map<ReconciliationKey, Trigger> pending = new LinkedHashMap<>();
    private Request active;
    private boolean running;
    private volatile boolean closed;

    AccountReconciliationCoordinator(AccountReconciliationPort operations, Executor worker,
            Consumer<Runnable> client, Completion completion, Runnable idle,
            BiConsumer<DiagnosticEvent, Throwable> diagnose) {
        this.operations = Objects.requireNonNull(operations);
        this.worker = Objects.requireNonNull(worker);
        this.client = Objects.requireNonNull(client);
        this.completion = Objects.requireNonNull(completion);
        this.idle = Objects.requireNonNull(idle);
        this.diagnose = Objects.requireNonNull(diagnose);
    }

    public void request(ReconciliationKey key, Trigger trigger) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(trigger);
        boolean start;
        synchronized (this) {
            if (closed) return;
            Trigger previous = pending.get(key);
            if (previous != null) {
                if (trigger.ordinal() > previous.ordinal()) pending.put(key, trigger);
            } else if (active == null || !active.key().equals(key)
                    || trigger.ordinal() > active.trigger().ordinal()) {
                pending.put(key, trigger);
            }
            start = !running;
            if (start) running = true;
        }
        if (start) CompletableFuture.runAsync(this::drain, worker);
    }

    public synchronized boolean busy() {
        return running || active != null || !pending.isEmpty();
    }

    private void drain() {
        while (!closed) {
            Request request;
            synchronized (this) {
                if (pending.isEmpty()) {
                    running = false;
                    active = null;
                    client.accept(() -> { if (!closed) idle.run(); });
                    return;
                }
                var next = pending.entrySet().iterator().next();
                request = new Request(next.getKey(), next.getValue());
                pending.remove(next.getKey());
                active = request;
            }
            Optional<ReconciliationResult> result = Optional.empty();
            Optional<DurableAppearance> durable = Optional.empty();
            Throwable failure = null;
            try {
                if (operations.reconciliationKey().filter(request.key()::equals).isPresent()) {
                    result = Objects.requireNonNull(
                            operations.reconcileAppearance(request.key(), request.trigger()));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = interrupted;
            } catch (Exception unavailable) {
                failure = unavailable;
                Throwable cause = unavailable;
                while ((cause instanceof java.util.concurrent.CompletionException
                        || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof RemoteMutationSettlementException) {
                    try {
                        durable = operations.durableAppearance()
                                .filter(value -> value.accountId().equals(request.key().accountId()));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        diagnose.accept(DiagnosticEvent.CLIENT_RECONCILIATION_CLEANUP_FAILED, interrupted);
                    } catch (Exception cleanup) {
                        diagnose.accept(DiagnosticEvent.CLIENT_RECONCILIATION_CLEANUP_FAILED, cleanup);
                    }
                }
            }
            if (failure != null) diagnose.accept(DiagnosticEvent.CLIENT_RECONCILIATION_FAILED, failure);
            var completed = result;
            var completedDurable = durable;
            var completedFailure = failure;
            client.accept(() -> {
                if (!closed) completion.accept(request, completed, completedDurable, completedFailure);
            });
            synchronized (this) {
                if (request.equals(active)) active = null;
            }
        }
        close();
    }

    @Override
    public synchronized void close() {
        closed = true;
        pending.clear();
        active = null;
        running = false;
    }

}

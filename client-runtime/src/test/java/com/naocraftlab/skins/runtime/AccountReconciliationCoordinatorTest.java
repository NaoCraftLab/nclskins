package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static com.naocraftlab.skins.runtime.AccountReconciliationPort.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountReconciliationCoordinatorTest {
    @Test
    void queuedTriggersCoalesceAndStaleKeysDoNotReachEffects() {
        ArrayDeque<Runnable> worker = new ArrayDeque<>();
        ArrayDeque<Runnable> client = new ArrayDeque<>();
        var key = new ReconciliationKey(new UUID(0, 1), 2, 3, 4);
        List<Trigger> executed = new ArrayList<>();
        var operations = operations(key, executed);
        List<ReconciliationKey> completed = new ArrayList<>();
        var coordinator = new AccountReconciliationCoordinator(operations, worker::add, client::add,
                (request, result, durable, failure) -> { assertNull(failure); completed.add(request.key()); },
                () -> {}, (event, failure) -> fail(failure));
        coordinator.request(new ReconciliationKey(key.accountId(), 1, 3, 4), Trigger.LOCAL_INTENT);
        coordinator.request(key, Trigger.LOCAL_INTENT);
        coordinator.request(key, Trigger.EXPLICIT_RETRY);
        coordinator.request(key, Trigger.RECONNECT);
        assertEquals(1, worker.size());
        worker.remove().run();
        assertEquals(List.of(Trigger.EXPLICIT_RETRY), executed);
        assertTrue(completed.isEmpty());
        while (!client.isEmpty()) client.remove().run();
        assertEquals(2, completed.size());
        assertFalse(coordinator.busy());
    }

    @Test
    void closeDropsQueuedAndLateCompletions() {
        ArrayDeque<Runnable> worker = new ArrayDeque<>();
        ArrayDeque<Runnable> client = new ArrayDeque<>();
        var key = new ReconciliationKey(new UUID(0, 1), 2, 3, 4);
        List<Trigger> executed = new ArrayList<>();
        var coordinator = new AccountReconciliationCoordinator(operations(key, executed), worker::add, client::add,
                (request, result, durable, failure) -> fail("late completion"),
                () -> fail("late idle"), (event, failure) -> fail(failure));
        coordinator.request(key, Trigger.LOCAL_INTENT);
        worker.remove().run();
        coordinator.close();
        while (!client.isEmpty()) client.remove().run();
        coordinator.request(key, Trigger.EXPLICIT_RETRY);
        assertTrue(worker.isEmpty());
        assertEquals(List.of(Trigger.LOCAL_INTENT), executed);
        assertFalse(coordinator.busy());
    }

    private AccountReconciliationPort operations(ReconciliationKey key, List<Trigger> executed) {
        return new AccountReconciliationPort() {
            public GameSessionTokenSource.SessionIdentity sessionIdentity() {
                return new GameSessionTokenSource.SessionIdentity(key.accountId(), "Fixture");
            }
            public Optional<DurableAppearance> durableAppearance() { return Optional.empty(); }
            public Optional<ReconciliationKey> reconciliationKey() { return Optional.of(key); }
            public Optional<ReconciliationResult> reconcileAppearance(Trigger trigger) {
                executed.add(trigger); return Optional.empty();
            }
        };
    }
}

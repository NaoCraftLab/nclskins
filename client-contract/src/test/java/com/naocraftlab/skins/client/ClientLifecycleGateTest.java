package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientLifecycleGateTest {
    @Test
    void pendingReloadsCoalesceAndDrainAfterInitialization() {
        List<String> events = new ArrayList<>();
        ClientLifecycleGate<String> gate =
                new ClientLifecycleGate<>(runtime -> events.add("reload:" + runtime),
                        runtime -> events.add("close:" + runtime));

        gate.resourcesReloaded();
        gate.resourcesReloaded();
        assertTrue(gate.install("runtime", runtime -> events.add("init:" + runtime)));

        assertEquals(List.of("init:runtime", "reload:runtime"), events);
        assertEquals(ClientLifecycleGate.State.INSTALLED, gate.state());
    }

    @Test
    void closeIsTerminalAndTrailingCallbacksNeverReachRuntime() {
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        ClientLifecycleGate<Object> gate =
                new ClientLifecycleGate<>(ignored -> callbacks.incrementAndGet(),
                        ignored -> closes.incrementAndGet());
        Object runtime = new Object();
        gate.install(runtime, ignored -> { });
        assertTrue(gate.dispatch(ignored -> callbacks.incrementAndGet()));

        gate.close();
        gate.close();
        gate.resourcesReloaded();

        assertFalse(gate.dispatch(ignored -> callbacks.incrementAndGet()));
        assertFalse(gate.install(new Object(), ignored -> callbacks.incrementAndGet()));
        assertEquals(1, callbacks.get());
        assertEquals(1, closes.get());
        assertEquals(ClientLifecycleGate.State.CLOSED, gate.state());
    }

    @Test
    void duplicateInstallIsRejected() {
        ClientLifecycleGate<Object> gate =
                new ClientLifecycleGate<>(ignored -> { }, ignored -> { });
        gate.install(new Object(), ignored -> { });

        assertThrows(
                IllegalStateException.class,
                () -> gate.install(new Object(), ignored -> { }));
    }

    @Test
    void closeBeforeInstallDropsPendingReload() {
        AtomicInteger callbacks = new AtomicInteger();
        ClientLifecycleGate<Object> gate =
                new ClientLifecycleGate<>(ignored -> callbacks.incrementAndGet(),
                        ignored -> callbacks.incrementAndGet());
        gate.resourcesReloaded();

        gate.close();

        assertFalse(gate.install(new Object(), ignored -> callbacks.incrementAndGet()));
        assertEquals(0, callbacks.get());
    }

    @Test
    void closeDuringInitializationCannotResurrectRuntime() {
        AtomicInteger closes = new AtomicInteger();
        ClientLifecycleGate<Object> gate =
                new ClientLifecycleGate<>(ignored -> { }, ignored -> closes.incrementAndGet());

        assertFalse(gate.install(new Object(), ignored -> gate.close()));

        assertEquals(ClientLifecycleGate.State.CLOSED, gate.state());
        assertEquals(1, closes.get());
        assertFalse(gate.dispatch(ignored -> { }));
    }
}

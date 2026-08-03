package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.runtime.AppearanceRefreshCoordinator.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class ClientProcessHostTest {
    @Test
    void tickRunsRuntimeThenStartsOneReconnectForTheCurrentReadyConnection() {
        List<String> events = new ArrayList<>();
        Connection connection = new Connection();
        FakeProcess process = new FakeProcess(events);
        ClientProcessHost<Connection> host = new ClientProcessHost<>(process);

        host.tick(connection, true);
        host.tick(connection, true);

        assertEquals(List.of("tick", "reconnect", "tick"), events);
    }

    @Test
    void disconnectedOrUnreadyTickCannotStartReconnect() {
        List<String> events = new ArrayList<>();
        FakeProcess process = new FakeProcess(events);
        ClientProcessHost<Connection> host = new ClientProcessHost<>(process);

        host.tick(null, false);
        host.tick(new Connection(), false);

        assertEquals(List.of("tick", "tick"), events);
    }

    @Test
    void deferredReconnectDoesNotScheduleTimedRetriesForTheSameConnection() {
        List<String> events = new ArrayList<>();
        Connection connection = new Connection();
        FakeProcess process = new FakeProcess(events);
        process.reconnectResult = Result.DEFERRED;
        ClientProcessHost<Connection> host = new ClientProcessHost<>(process);

        for (int tick = 0; tick < 200; tick++) {
            host.tick(connection, true);
        }

        assertEquals(200, events.stream().filter("tick"::equals).count());
        assertEquals(1, events.stream().filter("reconnect"::equals).count());

        Connection nextGeneration = new Connection();
        host.tick(nextGeneration, true);

        assertEquals(2, events.stream().filter("reconnect"::equals).count());
    }

    @Test
    void closeClosesRuntimeAndNativeResourcesExactlyOnce() {
        List<String> events = new ArrayList<>();
        FakeProcess process = new FakeProcess(events);
        ClientProcessHost<Connection> host = new ClientProcessHost<>(process);

        host.close();
        host.close();

        assertEquals(List.of("close"), events);
        assertTrue(host.closed());
        assertFalse(process.open);
    }

    @Test
    void directWarmAndTickRemainStrictAfterClose() {
        List<String> events = new ArrayList<>();
        FakeProcess process = new FakeProcess(events);
        ClientProcessHost<Connection> host = new ClientProcessHost<>(process);
        host.close();

        assertThrows(IllegalStateException.class, host::warmSession);
        assertThrows(IllegalStateException.class, () ->
                host.tick(null, false));
        assertEquals(List.of("close"), events);
    }

    private static final class Connection {}

    private static final class FakeProcess implements ClientProcessHost.Process {
        private final List<String> events;
        private boolean open = true;
        private Result reconnectResult = Result.UPDATED;

        private FakeProcess(List<String> events) {
            this.events = events;
        }

        @Override
        public void warmSession() {}

        @Override
        public void tick() {
            events.add("tick");
        }

        @Override
        public CompletableFuture<Result> afterReconnect() {
            events.add("reconnect");
            return CompletableFuture.completedFuture(reconnectResult);
        }

        @Override
        public void close() {
            events.add("close");
            open = false;
        }
    }
}

package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.naocraftlab.skins.client.ServerAppearanceRefreshNotifier;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ServerAppearanceReadinessCoordinatorTest {
    @Test
    void signalsImmediatelyWithoutClientProfileProbeOrScheduledWork() {
        TestNotifier notifier = new TestNotifier();
        ServerAppearanceReadinessCoordinator coordinator =
                new ServerAppearanceReadinessCoordinator(notifier);

        assertEquals(
                ServerAppearanceReadinessCoordinator.StartResult.STARTED,
                coordinator.start());
        assertEquals(1, notifier.notifications.get());
    }

    @Test
    void unavailableConnectionAndCloseNeverSignal() {
        TestNotifier notifier = new TestNotifier();
        notifier.connection = OptionalLong.empty();
        ServerAppearanceReadinessCoordinator coordinator =
                new ServerAppearanceReadinessCoordinator(notifier);

        assertEquals(
                ServerAppearanceReadinessCoordinator.StartResult.UNAVAILABLE,
                coordinator.start());
        notifier.connection = OptionalLong.of(2L);
        coordinator.close();
        assertEquals(
                ServerAppearanceReadinessCoordinator.StartResult.CLOSED,
                coordinator.start());
        assertEquals(0, notifier.notifications.get());
    }

    private static final class TestNotifier implements ServerAppearanceRefreshNotifier {
        private OptionalLong connection = OptionalLong.of(1L);
        private final AtomicInteger notifications = new AtomicInteger();

        @Override
        public OptionalLong activeConnectionGeneration() {
            return connection;
        }

        @Override
        public void requestOfficialProfileRefresh() {
            notifications.incrementAndGet();
        }
    }
}

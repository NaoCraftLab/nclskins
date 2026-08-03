package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AppearanceReconnectTrackerTest {
    @Test
    void connectionRunsExactlyOnceUntilDisconnect() {
        AppearanceReconnectTracker<Object> tracker = new AppearanceReconnectTracker<>();
        Object connection = new Object();

        assertTrue(tracker.begin(connection));
        assertFalse(tracker.begin(connection));
        assertFalse(tracker.begin(connection));

        tracker.disconnected();
        assertTrue(tracker.begin(connection));
    }

    @Test
    void onlyDisconnectOrNewConnectionCanOpenAnotherCheckpoint() {
        AppearanceReconnectTracker<Object> tracker = new AppearanceReconnectTracker<>();
        Object first = new Object();

        assertTrue(tracker.begin(first));
        assertFalse(tracker.begin(first));

        Object second = new Object();
        assertTrue(tracker.begin(second));
        assertFalse(tracker.begin(second));

        tracker.disconnected();
        assertTrue(tracker.begin(second));
    }
}

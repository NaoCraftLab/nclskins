package com.naocraftlab.skins.runtime;

import java.util.UUID;

final class TestCapeProjection {
    private static CapeProjection.Registration registration;

    static void publish(CapeProjection.Snapshot snapshot) {
        if (registration == null || !registration.active()) registration = CapeProjection.installEvents(events());
        registration.publish(snapshot);
    }

    static void clear() {
        if (registration != null) registration.close();
        registration = null;
    }

    static CapeProjection.Events events() {
        return new CapeProjection.Events() {
            public void trackedPlayer(UUID id, String name) {}
            public void playerInfoUpdated(UUID id, String name) {}
            public void untrackedPlayer(UUID id) {}
            public void worldChanged() {}
            public void worldEntered() {}
        };
    }
}

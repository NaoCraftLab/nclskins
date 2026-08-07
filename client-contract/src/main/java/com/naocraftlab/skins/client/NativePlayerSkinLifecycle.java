package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class NativePlayerSkinLifecycle {
    private static final ConcurrentMap<String, Registration> CURRENT = new ConcurrentHashMap<>();

    private NativePlayerSkinLifecycle() {
    }

    public static Registration pending(String location) {
        Registration registration = new Registration(requireLocation(location));
        CURRENT.put(registration.location, registration);
        return registration;
    }

    public static boolean isReady(String location) {
        return managedState(location).map(state -> state == State.READY).orElse(true);
    }

    public static Optional<State> managedState(String location) {
        Registration registration = CURRENT.get(requireLocation(location));
        return registration == null
                ? Optional.empty()
                : Optional.of(registration.state);
    }

    public enum State {
        PENDING,
        READY,
        FAILED,
        RETIRED
    }

    public static final class Registration {
        private final String location;
        private volatile State state = State.PENDING;

        private Registration(String location) {
            this.location = location;
        }

        public String location() {
            return location;
        }

        public State state() {
            return state;
        }

        public void ready() {
            transition(State.READY, false);
        }

        public void failed() {
            transition(State.FAILED, false);
        }

        public void retire() {
            transition(State.RETIRED, true);
        }

        private void transition(State next, boolean remove) {
            CURRENT.compute(location, (ignored, current) -> {
                if (current != this) {
                    return current;
                }
                state = next;
                return remove ? null : this;
            });
        }
    }

    private static String requireLocation(String location) {
        String value = Objects.requireNonNull(location, "location");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Texture location must not be blank");
        }
        return value;
    }
}

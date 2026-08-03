package com.naocraftlab.skins.runtime;

import java.util.Objects;


public final class AppearanceReconnectTracker<C> {
    private C connection;
    private boolean started;


    public boolean begin(C candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (connection != candidate) {
            connection = candidate;
            started = false;
        }
        if (started) {
            return false;
        }
        started = true;
        return true;
    }

    public void disconnected() {
        connection = null;
        started = false;
    }
}

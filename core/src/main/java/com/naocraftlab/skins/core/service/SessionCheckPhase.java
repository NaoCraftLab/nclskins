package com.naocraftlab.skins.core.service;


public enum SessionCheckPhase {
    TOKEN_SOURCE("token-source"),
    PROFILE("profile"),
    UNKNOWN("unknown");

    private final String diagnosticName;

    SessionCheckPhase(String diagnosticName) {
        this.diagnosticName = diagnosticName;
    }

    public String diagnosticName() {
        return diagnosticName;
    }
}

package com.naocraftlab.skins.runtime.update;

public enum UpdateChannel {
    ALPHA(0),
    BETA(1),
    RELEASE(2);

    private final int precedence;

    UpdateChannel(int precedence) {
        this.precedence = precedence;
    }

    int precedence() {
        return precedence;
    }

    public boolean allows(UpdateChannel candidate) {
        return candidate.precedence >= precedence;
    }

    public String catalogName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}

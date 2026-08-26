package com.naocraftlab.skins.runtime;

public enum InteractionOrigin {
    KEYBOARD,
    POINTER,
    PROGRAMMATIC;

    public boolean keyboard() {
        return this == KEYBOARD;
    }
}

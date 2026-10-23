package com.naocraftlab.skins.core.png;

import java.io.IOException;

public final class CapeIntegrityException extends IOException {
    private static final long serialVersionUID = 1L;
    public CapeIntegrityException(String message) { super(message); }
    public CapeIntegrityException(String message, Throwable cause) { super(message, cause); }
    public static boolean isIntegrityFailure(Throwable failure) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (failure != null && seen.add(failure)) {
            if (failure instanceof CapeIntegrityException) return true;
            failure = failure.getCause();
        }
        return false;
    }
}

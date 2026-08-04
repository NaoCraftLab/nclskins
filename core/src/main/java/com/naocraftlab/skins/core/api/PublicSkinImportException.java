package com.naocraftlab.skins.core.api;

import java.io.IOException;
import java.util.Objects;


public final class PublicSkinImportException extends IOException {
    private static final long serialVersionUID = 1L;
    private final Code code;

    public PublicSkinImportException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_IDENTIFIER,
        PROFILE_NOT_FOUND,
        PROFILE_REJECTED,
        RATE_LIMITED,
        SERVICE_UNAVAILABLE,
        SITE_BLOCKED,
        UNSAFE_URL,
        REDIRECT_REJECTED,
        NETWORK_FAILURE,
        OVERSIZED,
        INVALID_PNG
    }
}

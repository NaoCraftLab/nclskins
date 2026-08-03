package com.naocraftlab.skins.core.storage;

import java.io.IOException;


public final class TextureCacheException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        HOST_NOT_ALLOWLISTED,
        REDIRECT_REJECTED,
        OVERSIZED,
        INVALID_TEXTURE,
        HTTP_FAILURE,
        NETWORK_FAILURE
    }

    private final Code code;

    public TextureCacheException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}

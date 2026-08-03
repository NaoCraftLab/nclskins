package com.naocraftlab.skins.core.storage;

import java.io.IOException;


public final class StorageException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_STATE,
        UNSUPPORTED_SCHEMA,
        ATOMIC_MOVE_UNSUPPORTED,
        ASSET_INTEGRITY_FAILURE
    }

    private final Code code;

    public StorageException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public StorageException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}

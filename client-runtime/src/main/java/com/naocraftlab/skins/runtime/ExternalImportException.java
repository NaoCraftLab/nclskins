package com.naocraftlab.skins.runtime;

import java.io.IOException;
import java.util.Objects;


public final class ExternalImportException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        NOT_FOUND,
        NO_VALID_APPEARANCES,
        DEPENDENCY_MISSING
    }

    private final Code code;

    public ExternalImportException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ExternalImportException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}

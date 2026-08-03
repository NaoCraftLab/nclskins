package com.naocraftlab.skins.core.service;


public final class LibraryOperationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        SKIN_NOT_FOUND,
        PERSONAL_SKIN_NOT_FOUND,
        PRESET_NOT_FOUND,
        SKIN_IN_USE,
        PRESET_REFERENCES_MISSING_SKIN
    }

    private final Code code;

    public LibraryOperationException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}

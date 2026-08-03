package com.naocraftlab.skins.core.png;


public final class PngValidationException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        EMPTY,
        OVERSIZED,
        BAD_SIGNATURE,
        MALFORMED_CHUNK,
        UNKNOWN_CRITICAL_CHUNK,
        BAD_CHECKSUM,
        INVALID_HEADER,
        UNSUPPORTED_DIMENSIONS,
        MISSING_IMAGE_DATA,
        DECOMPRESSION_LIMIT,
        DECODE_FAILED
    }

    private final Reason reason;

    public PngValidationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

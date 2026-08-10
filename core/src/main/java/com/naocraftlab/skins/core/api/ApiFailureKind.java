package com.naocraftlab.skins.core.api;

public enum ApiFailureKind {
    TOKEN_UNAVAILABLE,
    INVALID_SESSION,
    SESSION_EXPIRED,
    FORBIDDEN,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER_ERROR,
    NETWORK,
    INVALID_RESPONSE,
    REDIRECT_REJECTED
}

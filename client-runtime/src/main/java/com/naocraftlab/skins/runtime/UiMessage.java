package com.naocraftlab.skins.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;


public record UiMessage(String key, List<Object> arguments, Severity severity, boolean literal) {
    public enum Severity {
        INFO,
        SUCCESS,
        ERROR
    }

    public UiMessage {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        Objects.requireNonNull(severity, "severity");
    }

    public UiMessage(String key, List<Object> arguments, Severity severity) {
        this(key, arguments, severity, false);
    }

    public static UiMessage translated(String key, Severity severity, Object... arguments) {
        Objects.requireNonNull(arguments, "arguments");
        return new UiMessage(key, Arrays.asList(arguments.clone()), severity, false);
    }

    public static UiMessage info(String key, Object... arguments) {
        return translated(key, Severity.INFO, arguments);
    }

    public static UiMessage success(String key, Object... arguments) {
        return translated(key, Severity.SUCCESS, arguments);
    }

    public static UiMessage error(String key, Object... arguments) {
        return translated(key, Severity.ERROR, arguments);
    }

    public static UiMessage literal(String value, Severity severity) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("literal value must not be blank");
        }
        return new UiMessage(value, List.of(), severity, true);
    }
}

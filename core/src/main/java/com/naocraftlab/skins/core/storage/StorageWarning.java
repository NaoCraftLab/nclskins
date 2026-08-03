package com.naocraftlab.skins.core.storage;

import java.util.Objects;


public record StorageWarning(String message) {
    public StorageWarning {
        Objects.requireNonNull(message, "message");
    }
}

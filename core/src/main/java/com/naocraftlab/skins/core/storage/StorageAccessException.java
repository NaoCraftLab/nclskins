package com.naocraftlab.skins.core.storage;

import java.nio.file.Path;
import java.util.Objects;


public final class StorageAccessException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public StorageAccessException(Path root, Throwable cause) {
        super(message(root), Objects.requireNonNull(cause, "cause"));
    }

    private static String message(Path root) {
        Path normalized = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        return "NCL Skins (nclskins) requires read and write access to \""
                + normalized
                + "\" to store its state. Check the directory permissions and restart Minecraft.";
    }
}

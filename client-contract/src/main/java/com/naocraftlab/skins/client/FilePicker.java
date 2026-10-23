package com.naocraftlab.skins.client;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;


public interface FilePicker {
    final class InvalidSelection extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public enum Reason { MODEL_NOT_ALLOWED }

        private final Reason reason;

        public InvalidSelection(Reason reason) {
            super(Objects.requireNonNull(reason, "reason").name());
            this.reason = reason;
        }

        public Reason reason() { return reason; }
    }

    CompletableFuture<Optional<Path>> chooseSkinPng();

    default CompletableFuture<Optional<Path>> chooseSkinImportFile(boolean allowCpmModel) {
        return chooseSkinPng();
    }

    default CompletableFuture<Optional<Path>> chooseCapePng() {
        return chooseSkinPng();
    }

    default CompletableFuture<Optional<Path>> chooseDirectory() {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Directory picker is unavailable"));
    }

    default CompletableFuture<Optional<Path>> chooseDirectory(Path initialDirectory) {
        Objects.requireNonNull(initialDirectory, "initialDirectory");
        return chooseDirectory();
    }

    default CompletableFuture<Optional<Path>> chooseSqliteDatabase() {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("SQLite database picker is unavailable"));
    }
}

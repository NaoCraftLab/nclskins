package com.naocraftlab.skins.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;


public final class FilePickerCoordinator {
    private final Executor worker;
    private final AtomicBoolean dialogOpen = new AtomicBoolean();

    public FilePickerCoordinator(Executor worker) {
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    public CompletableFuture<Optional<Path>> choose(Dialog dialog) {
        Objects.requireNonNull(dialog, "dialog");
        if (!dialogOpen.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A system file picker is already open"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return validate(dialog.open());
                } catch (LinkageError | RuntimeException failure) {
                    throw new IllegalStateException("The system PNG picker is unavailable", failure);
                } finally {
                    dialogOpen.set(false);
                }
            }, worker);
        } catch (RuntimeException schedulingFailure) {
            dialogOpen.set(false);
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "The system PNG picker could not be started", schedulingFailure));
        }
    }

    private static Optional<Path> validate(Optional<Path> selection) {
        Objects.requireNonNull(selection, "dialog selection");
        if (selection.isEmpty()) {
            return Optional.empty();
        }
        Path path = selection.orElseThrow().toAbsolutePath().normalize();
        Path fileName = path.getFileName();
        if (fileName == null
                || !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".png")
                || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("The selected file is not a local PNG");
        }
        return Optional.of(path);
    }

    @FunctionalInterface
    public interface Dialog {
        Optional<Path> open();
    }
}

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
        return choose(dialog, SelectionType.PNG);
    }

    public CompletableFuture<Optional<Path>> chooseDirectory(Dialog dialog) {
        return choose(dialog, SelectionType.DIRECTORY);
    }

    public CompletableFuture<Optional<Path>> chooseDirectory(Dialog dialog, Executor executor) {
        return choose(dialog, SelectionType.DIRECTORY, executor);
    }

    private CompletableFuture<Optional<Path>> choose(Dialog dialog, SelectionType selectionType) {
        return choose(dialog, selectionType, worker);
    }

    private CompletableFuture<Optional<Path>> choose(
            Dialog dialog, SelectionType selectionType, Executor executor) {
        Objects.requireNonNull(dialog, "dialog");
        Objects.requireNonNull(selectionType, "selectionType");
        Objects.requireNonNull(executor, "executor");
        if (!dialogOpen.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A system file picker is already open"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return validate(dialog.open(), selectionType);
                } catch (LinkageError | RuntimeException failure) {
                    throw new IllegalStateException(
                            selectionType == SelectionType.PNG
                                    ? "The system PNG picker is unavailable"
                                    : "The system directory picker is unavailable",
                            failure);
                } finally {
                    dialogOpen.set(false);
                }
            }, executor);
        } catch (RuntimeException schedulingFailure) {
            dialogOpen.set(false);
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            selectionType == SelectionType.PNG
                                    ? "The system PNG picker could not be started"
                                    : "The system directory picker could not be started",
                            schedulingFailure));
        }
    }

    private static Optional<Path> validate(
            Optional<Path> selection, SelectionType selectionType) {
        Objects.requireNonNull(selection, "dialog selection");
        if (selection.isEmpty()) {
            return Optional.empty();
        }
        Path path = selection.orElseThrow().toAbsolutePath().normalize();
        if (selectionType == SelectionType.DIRECTORY) {
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException("The selected path is not a local directory");
            }
        } else {
            Path fileName = path.getFileName();
            if (fileName == null
                    || !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".png")
                    || !Files.isRegularFile(path)) {
                throw new IllegalArgumentException("The selected file is not a local PNG");
            }
        }
        return Optional.of(path);
    }

    private enum SelectionType {
        PNG,
        DIRECTORY
    }

    @FunctionalInterface
    public interface Dialog {
        Optional<Path> open();
    }
}

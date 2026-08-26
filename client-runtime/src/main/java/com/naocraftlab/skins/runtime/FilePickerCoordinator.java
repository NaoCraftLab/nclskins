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

    public CompletableFuture<Optional<Path>> chooseSqliteDatabase(Dialog dialog) {
        return choose(dialog, SelectionType.SQLITE_DATABASE);
    }

    public CompletableFuture<Optional<Path>> chooseAsync(
            AsyncDialog dialog, Executor starter) {
        return chooseAsync(dialog, SelectionType.PNG, starter);
    }

    public CompletableFuture<Optional<Path>> chooseDirectoryAsync(
            AsyncDialog dialog, Executor starter) {
        return chooseAsync(dialog, SelectionType.DIRECTORY, starter);
    }

    public CompletableFuture<Optional<Path>> chooseSqliteDatabaseAsync(
            AsyncDialog dialog, Executor starter) {
        return chooseAsync(dialog, SelectionType.SQLITE_DATABASE, starter);
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
                            pickerUnavailable(selectionType),
                            failure);
                } finally {
                    dialogOpen.set(false);
                }
            }, executor);
        } catch (RuntimeException schedulingFailure) {
            dialogOpen.set(false);
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            pickerCouldNotStart(selectionType),
                            schedulingFailure));
        }
    }

    private CompletableFuture<Optional<Path>> chooseAsync(
            AsyncDialog dialog, SelectionType selectionType, Executor starter) {
        Objects.requireNonNull(dialog, "dialog");
        Objects.requireNonNull(selectionType, "selectionType");
        Objects.requireNonNull(starter, "starter");
        if (!dialogOpen.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A system file picker is already open"));
        }
        CompletableFuture<Optional<Path>> result = new CompletableFuture<>();
        try {
            starter.execute(() -> {
                final CompletableFuture<Optional<Path>> opened;
                try {
                    opened = Objects.requireNonNull(dialog.open(), "dialog result");
                } catch (LinkageError | RuntimeException failure) {
                    dialogOpen.set(false);
                    result.completeExceptionally(new IllegalStateException(
                            pickerUnavailable(selectionType), failure));
                    return;
                }
                opened.whenComplete((selection, failure) -> {
                    try {
                        if (failure instanceof Error error) {
                            result.completeExceptionally(error);
                            throw error;
                        }
                        if (failure != null) {
                            result.completeExceptionally(new IllegalStateException(
                                    pickerUnavailable(selectionType), failure));
                        } else {
                            result.complete(validate(selection, selectionType));
                        }
                    } catch (LinkageError | RuntimeException validationFailure) {
                        result.completeExceptionally(new IllegalStateException(
                                pickerUnavailable(selectionType), validationFailure));
                    } finally {
                        dialogOpen.set(false);
                    }
                });
            });
        } catch (RuntimeException schedulingFailure) {
            dialogOpen.set(false);
            result.completeExceptionally(new IllegalStateException(
                    pickerCouldNotStart(selectionType), schedulingFailure));
        }
        return result;
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
        } else if (selectionType == SelectionType.PNG) {
            Path fileName = path.getFileName();
            if (fileName == null
                    || !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".png")
                    || !Files.isRegularFile(path)) {
                throw new IllegalArgumentException("The selected file is not a local PNG");
            }
        } else if (!Files.isRegularFile(path)
                || path.getFileName() == null
                || !"app.db".equalsIgnoreCase(path.getFileName().toString())) {
            throw new IllegalArgumentException("The selected file is not an app.db SQLite database");
        }
        return Optional.of(path);
    }

    private static String pickerUnavailable(SelectionType type) {
        return switch (type) {
            case PNG -> "The system PNG picker is unavailable";
            case DIRECTORY -> "The system directory picker is unavailable";
            case SQLITE_DATABASE -> "The system SQLite database picker is unavailable";
        };
    }

    private static String pickerCouldNotStart(SelectionType type) {
        return switch (type) {
            case PNG -> "The system PNG picker could not be started";
            case DIRECTORY -> "The system directory picker could not be started";
            case SQLITE_DATABASE -> "The system SQLite database picker could not be started";
        };
    }

    private enum SelectionType {
        PNG,
        DIRECTORY,
        SQLITE_DATABASE
    }

    @FunctionalInterface
    public interface Dialog {
        Optional<Path> open();
    }

    @FunctionalInterface
    public interface AsyncDialog {
        CompletableFuture<Optional<Path>> open();
    }
}

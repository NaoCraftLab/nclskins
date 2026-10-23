package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.FilePicker;

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

    public CompletableFuture<Optional<Path>> chooseCape(Dialog dialog) {
        return choose(dialog, SelectionType.CAPE);
    }

    public CompletableFuture<Optional<Path>> chooseSkinImport(Dialog dialog) {
        return choose(dialog, SelectionType.SKIN_IMPORT);
    }

    public CompletableFuture<Optional<Path>> chooseSkinImportAsync(AsyncDialog dialog, Executor starter) {
        return chooseAsync(dialog, SelectionType.SKIN_IMPORT, starter);
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

    public CompletableFuture<Optional<Path>> chooseCapeAsync(
            AsyncDialog dialog, Executor starter) {
        return chooseAsync(dialog, SelectionType.CAPE, starter);
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
                } catch (FilePicker.InvalidSelection invalidSelection) {
                    throw invalidSelection;
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
                    } catch (FilePicker.InvalidSelection invalidSelection) {
                        result.completeExceptionally(invalidSelection);
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
        } else if (selectionType == SelectionType.PNG || selectionType == SelectionType.CAPE
                || selectionType == SelectionType.SKIN_IMPORT) {
            Path fileName = path.getFileName();
            String name = fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);
            byte[] prefix = new byte[0];
            if (Files.isRegularFile(path)) {
                try (var input = Files.newInputStream(path)) { prefix = input.readNBytes(8); }
                catch (java.io.IOException failure) { throw new IllegalStateException("Selected file is unavailable", failure); }
            }
            boolean png = java.util.Arrays.equals(prefix, new byte[]{(byte)137,80,78,71,13,10,26,10});
            boolean model = prefix.length > 0 && prefix[0] == 83;
            boolean project = prefix.length >= 4 && prefix[0] == 80 && prefix[1] == 75 && prefix[2] == 3 && prefix[3] == 4;
            if (selectionType == SelectionType.PNG && (model || project)) {
                throw new FilePicker.InvalidSelection(FilePicker.InvalidSelection.Reason.MODEL_NOT_ALLOWED);
            }
            if (!Files.isRegularFile(path)
                    || !(png || name.endsWith(".png") && !model && !project
                    || selectionType == SelectionType.SKIN_IMPORT && (model || project)
                    || selectionType == SelectionType.CAPE && !model && !project
                    && (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif")))) {
                throw new IllegalArgumentException("The selected file is not a supported local image");
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
            case SKIN_IMPORT -> "The system skin file picker is unavailable";
            case CAPE -> "The system cape picker is unavailable";
            case DIRECTORY -> "The system directory picker is unavailable";
            case SQLITE_DATABASE -> "The system SQLite database picker is unavailable";
        };
    }

    private static String pickerCouldNotStart(SelectionType type) {
        return switch (type) {
            case PNG -> "The system PNG picker could not be started";
            case SKIN_IMPORT -> "The system skin file picker could not be started";
            case CAPE -> "The system cape picker could not be started";
            case DIRECTORY -> "The system directory picker could not be started";
            case SQLITE_DATABASE -> "The system SQLite database picker could not be started";
        };
    }

    private enum SelectionType {
        SKIN_IMPORT,
        PNG,
        CAPE,
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

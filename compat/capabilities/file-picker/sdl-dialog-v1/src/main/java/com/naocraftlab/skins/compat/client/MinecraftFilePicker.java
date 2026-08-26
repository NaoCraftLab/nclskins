package com.naocraftlab.skins.compat.client;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.runtime.FilePickerCoordinator;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;


public final class MinecraftFilePicker implements FilePicker {
    private static final ExecutorService CALLBACK_HANDOFF =
            Executors.newSingleThreadExecutor(action -> {
                Thread thread = new Thread(action, "NCL Skins SDL dialog callback");
                thread.setDaemon(true);
                return thread;
            });
    private static final FilePickerCoordinator COORDINATOR =
            new FilePickerCoordinator(Runnable::run);

    private final ClientExecutor clientExecutor;

    public MinecraftFilePicker(ClientExecutor clientExecutor) {
        this.clientExecutor = Objects.requireNonNull(clientExecutor, "clientExecutor");
    }

    @Override
    public CompletableFuture<Optional<Path>> chooseSkinPng() {
        return chooseFile(
                "nclskins.editor.file_picker_title",
                null,
                "PNG image",
                "png",
                SelectionType.PNG);
    }

    @Override
    public CompletableFuture<Optional<Path>> chooseDirectory() {
        return chooseDirectoryFrom(
                null,
                "nclskins.external_import.folder_picker_title");
    }

    @Override
    public CompletableFuture<Optional<Path>> chooseDirectory(Path initialDirectory) {
        return chooseDirectoryFrom(
                Objects.requireNonNull(initialDirectory, "initialDirectory"),
                "nclskins.config.client.storage.data_directory.picker_title");
    }

    @Override
    public CompletableFuture<Optional<Path>> chooseSqliteDatabase() {
        return chooseFile(
                "nclskins.external_import.database_picker_title",
                null,
                "SQLite app.db",
                "db",
                SelectionType.SQLITE_DATABASE);
    }

    private CompletableFuture<Optional<Path>> chooseDirectoryFrom(
            Path initialDirectory,
            String titleKey) {
        final String title;
        try {
            title = Component.translatable(titleKey).getString();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "The system directory picker could not be prepared", failure));
        }
        return COORDINATOR.chooseDirectoryAsync(
                () -> openDialog(
                        SDLDialog.SDL_FILEDIALOG_OPENFOLDER,
                        title,
                        initialDirectory,
                        null,
                        null),
                clientExecutor::execute);
    }

    private CompletableFuture<Optional<Path>> chooseFile(
            String titleKey,
            Path initialDirectory,
            String filterName,
            String filterPattern,
            SelectionType selectionType) {
        final String title;
        try {
            title = Component.translatable(titleKey).getString();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    selectionType == SelectionType.PNG
                            ? "The system PNG picker could not be prepared"
                            : "The system database picker could not be prepared",
                    failure));
        }
        FilePickerCoordinator.AsyncDialog dialog = () -> openDialog(
                SDLDialog.SDL_FILEDIALOG_OPENFILE,
                title,
                initialDirectory,
                filterName,
                filterPattern);
        return selectionType == SelectionType.PNG
                ? COORDINATOR.chooseAsync(dialog, clientExecutor::execute)
                : COORDINATOR.chooseSqliteDatabaseAsync(dialog, clientExecutor::execute);
    }

    private CompletableFuture<Optional<Path>> openDialog(
            int type,
            String title,
            Path initialDirectory,
            String filterName,
            String filterPattern) {
        PendingDialog pending = new PendingDialog(
                clientExecutor,
                type,
                title,
                initialDirectory,
                filterName,
                filterPattern);
        pending.open();
        return pending.result;
    }

    private enum SelectionType {
        PNG,
        SQLITE_DATABASE
    }

    private static final class PendingDialog {
        private final ClientExecutor clientExecutor;
        private final int type;
        private final String title;
        private final Path initialDirectory;
        private final CompletableFuture<Optional<Path>> result = new CompletableFuture<>();
        private final SDL_DialogFileCallback callback;
        private final SDL_DialogFileFilter.Buffer filters;
        private final ByteBuffer filterName;
        private final ByteBuffer filterPattern;
        private final Object callbackMonitor = new Object();

        private boolean callbackActive;
        private int properties;

        private PendingDialog(
                ClientExecutor clientExecutor,
                int type,
                String title,
                Path initialDirectory,
                String filterName,
                String filterPattern) {
            this.clientExecutor = clientExecutor;
            this.type = type;
            this.title = title;
            this.initialDirectory = initialDirectory;
            this.callback = SDL_DialogFileCallback.create(this::accept);
            if (filterName == null) {
                this.filters = null;
                this.filterName = null;
                this.filterPattern = null;
            } else {
                this.filterName = MemoryUtil.memUTF8(filterName);
                this.filterPattern = MemoryUtil.memUTF8(filterPattern);
                this.filters = SDL_DialogFileFilter.calloc(1)
                        .name(this.filterName)
                        .pattern(this.filterPattern);
            }
            result.whenComplete((ignored, failure) -> close());
        }

        private void open() {
            try {
                properties = SDLProperties.SDL_CreateProperties();
                if (properties == 0
                        || !SDLProperties.SDL_SetStringProperty(
                                properties,
                                SDLDialog.SDL_PROP_FILE_DIALOG_TITLE_STRING,
                                title)
                        || !SDLProperties.SDL_SetPointerProperty(
                                properties,
                                SDLDialog.SDL_PROP_FILE_DIALOG_WINDOW_POINTER,
                                Minecraft.getInstance().getWindow().handle())
                        || !SDLProperties.SDL_SetBooleanProperty(
                                properties,
                                SDLDialog.SDL_PROP_FILE_DIALOG_MANY_BOOLEAN,
                                false)) {
                    throw new IllegalStateException("SDL dialog properties are unavailable");
                }
                if (initialDirectory != null && !SDLProperties.SDL_SetStringProperty(
                        properties,
                        SDLDialog.SDL_PROP_FILE_DIALOG_LOCATION_STRING,
                        initialDirectory.toAbsolutePath().normalize().toString())) {
                    throw new IllegalStateException("SDL dialog location is unavailable");
                }
                if (filters != null
                        && (!SDLProperties.SDL_SetPointerProperty(
                                properties,
                                SDLDialog.SDL_PROP_FILE_DIALOG_FILTERS_POINTER,
                                filters.address())
                        || !SDLProperties.SDL_SetNumberProperty(
                                properties,
                                SDLDialog.SDL_PROP_FILE_DIALOG_NFILTERS_NUMBER,
                                filters.remaining()))) {
                    throw new IllegalStateException("SDL dialog filters are unavailable");
                }
                SDLDialog.SDL_ShowFileDialogWithProperties(type, callback, 0L, properties);
            } catch (LinkageError | RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        }

        private void accept(long userdata, long fileList, int filter) {
            Optional<Path> selection = null;
            RuntimeException failure = null;
            synchronized (callbackMonitor) {
                callbackActive = true;
            }
            try {
                if (fileList == MemoryUtil.NULL) {
                    failure = new IllegalStateException("SDL file dialog failed");
                } else {
                    long first = MemoryUtil.memGetAddress(fileList);
                    selection = first == MemoryUtil.NULL
                            ? Optional.empty()
                            : Optional.of(Path.of(MemoryUtil.memUTF8(first)));
                }
            } catch (RuntimeException decodeFailure) {
                failure = decodeFailure;
            }
            Optional<Path> completedSelection = selection;
            RuntimeException completedFailure = failure;
            CALLBACK_HANDOFF.execute(() -> completeAfterCallback(
                    completedSelection,
                    completedFailure));
            synchronized (callbackMonitor) {
                callbackActive = false;
                callbackMonitor.notifyAll();
            }
        }

        private void completeAfterCallback(
                Optional<Path> selection,
                RuntimeException failure) {
            synchronized (callbackMonitor) {
                while (callbackActive) {
                    try {
                        callbackMonitor.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        failure = new IllegalStateException(
                                "SDL file dialog callback handoff was interrupted",
                                interrupted);
                        break;
                    }
                }
            }
            RuntimeException completedFailure = failure;
            try {
                clientExecutor.execute(() -> complete(selection, completedFailure));
            } catch (RuntimeException schedulingFailure) {
                complete(selection, schedulingFailure);
            }
        }

        private void complete(Optional<Path> selection, RuntimeException failure) {
            if (failure == null) {
                result.complete(selection);
            } else {
                result.completeExceptionally(failure);
            }
        }

        private void close() {
            if (properties != 0) {
                SDLProperties.SDL_DestroyProperties(properties);
                properties = 0;
            }
            if (filters != null) {
                filters.free();
            }
            if (filterName != null) {
                MemoryUtil.memFree(filterName);
            }
            if (filterPattern != null) {
                MemoryUtil.memFree(filterPattern);
            }
            callback.free();
        }
    }
}

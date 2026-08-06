package com.naocraftlab.skins.compat.client;

import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.runtime.FilePickerCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.network.chat.Component;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;


public final class MinecraftFilePicker implements FilePicker {
    private static final ExecutorService DIALOG_WORKER = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "NCL Skins file picker");
        thread.setDaemon(true);
        return thread;
    });
    private static final FilePickerCoordinator COORDINATOR =
            new FilePickerCoordinator(DIALOG_WORKER);
    private final ClientExecutor clientExecutor;

    public MinecraftFilePicker(ClientExecutor clientExecutor) {
        this.clientExecutor = Objects.requireNonNull(clientExecutor, "clientExecutor");
    }

    @Override
    public CompletableFuture<Optional<Path>> chooseSkinPng() {
        final String title;
        try {
            title = Component.translatable("nclskins.editor.file_picker_title").getString();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("The system PNG picker could not be prepared", failure));
        }
        return COORDINATOR.choose(() -> selectLocalPng(title));
    }

    @Override
    public CompletableFuture<Optional<Path>> chooseDirectory() {
        final String title;
        try {
            title = Component.translatable("nclskins.external_import.folder_picker_title").getString();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("The system directory picker could not be prepared", failure));
        }
        return COORDINATOR.chooseDirectory(
                () -> selectLocalDirectory(title), clientExecutor::execute);
    }

    @Override
    public CompletableFuture<Optional<Path>> chooseSqliteDatabase() {
        final String title;
        try {
            title = Component.translatable(
                    "nclskins.external_import.database_picker_title").getString();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("The system database picker could not be prepared", failure));
        }
        return COORDINATOR.chooseSqliteDatabase(() -> selectAppDatabase(title));
    }

    private static Optional<Path> selectLocalPng(String title) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.png")).flip();

            String selected = TinyFileDialogs.tinyfd_openFileDialog(
                    title,
                    null,
                    filters,
                    "PNG image",
                    false);
            if (selected == null) {
                return Optional.empty();
            }
            return Optional.of(Path.of(selected));
        }
    }

    private static Optional<Path> selectLocalDirectory(String title) {
        try {
            String selected = TinyFileDialogs.tinyfd_selectFolderDialog(title, null);
            return selected == null ? Optional.empty() : Optional.of(Path.of(selected));
        } catch (LinkageError | RuntimeException unavailableFolderDialog) {
            String selected = TinyFileDialogs.tinyfd_openFileDialog(
                    title,
                    null,
                    null,
                    null,
                    false);
            if (selected == null) {
                return Optional.empty();
            }
            Path path = Path.of(selected);
            if (Files.isDirectory(path)) {
                return Optional.of(path);
            }
            return Optional.ofNullable(path.getParent());
        }
    }

    private static Optional<Path> selectAppDatabase(String title) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.db")).flip();
            String selected = TinyFileDialogs.tinyfd_openFileDialog(
                    title,
                    null,
                    filters,
                    "SQLite app.db",
                    false);
            return selected == null ? Optional.empty() : Optional.of(Path.of(selected));
        }
    }
}

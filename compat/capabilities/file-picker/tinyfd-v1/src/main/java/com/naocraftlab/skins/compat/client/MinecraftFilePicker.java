package com.naocraftlab.skins.compat.client;

import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.runtime.FilePickerCoordinator;
import java.nio.file.Path;
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
}

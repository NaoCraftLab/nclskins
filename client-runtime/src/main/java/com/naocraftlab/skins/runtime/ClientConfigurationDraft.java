package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.core.config.ClientConfiguration;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;


public final class ClientConfigurationDraft {
    private final FilePicker filePicker;
    private final AtomicReference<ClientConfiguration> value;

    public ClientConfigurationDraft(
            ClientConfiguration initialValue,
            FilePicker filePicker) {
        value = new AtomicReference<>(Objects.requireNonNull(initialValue, "initialValue"));
        this.filePicker = Objects.requireNonNull(filePicker, "filePicker");
    }

    public ClientConfiguration value() {
        return value.get();
    }

    public void setTitleScreenPreview(boolean enabled) {
        value.updateAndGet(current -> current.withTitleScreenPreview(enabled));
    }

    public void setPauseMenuPreview(boolean enabled) {
        value.updateAndGet(current -> current.withPauseMenuPreview(enabled));
    }

    public void setDataDirectory(String directory) {
        String checked = Objects.requireNonNull(directory, "directory");
        if (!ClientConfiguration.validDataDirectory(checked)) {
            throw new IllegalArgumentException("Data directory must be empty or absolute");
        }
        value.updateAndGet(current -> current.withDataDirectory(checked));
    }

    public CompletableFuture<Optional<String>> selectDataDirectory(
            Path defaultDirectory,
            String currentDirectory) {
        Path checkedDefault = Objects.requireNonNull(
                defaultDirectory, "defaultDirectory").toAbsolutePath().normalize();
        String checkedCurrent = Objects.requireNonNull(currentDirectory, "currentDirectory");
        if (!ClientConfiguration.validDataDirectory(checkedCurrent)) {
            throw new IllegalArgumentException("Current data directory must be empty or absolute");
        }
        Path initialDirectory = checkedCurrent.isEmpty()
                ? checkedDefault
                : Path.of(checkedCurrent).toAbsolutePath().normalize();
        return filePicker.chooseDirectory(initialDirectory).thenApply(selection -> selection.map(path ->
                path.toAbsolutePath().normalize().toString()));
    }

    public static String abbreviatedDataDirectory(String directory) {
        String checked = Objects.requireNonNull(directory, "directory");
        if (checked.isEmpty()) {
            return "";
        }
        Path path = Path.of(checked).normalize();
        int count = path.getNameCount();
        if (count == 0) {
            return ".../" + checked;
        }
        int start = Math.max(0, count - 2);
        StringBuilder abbreviated = new StringBuilder("...");
        for (int index = start; index < count; index++) {
            abbreviated.append('/').append(path.getName(index));
        }
        return abbreviated.toString();
    }
}

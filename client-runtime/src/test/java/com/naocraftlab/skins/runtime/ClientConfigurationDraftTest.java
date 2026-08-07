package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.core.config.ClientConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;


final class ClientConfigurationDraftTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void selectionStaysInYaclUntilAppliedAndResetRestoresSystemDefault() throws Exception {
        Path selected = Files.createDirectory(temporaryDirectory.resolve("shared skins"));
        QueueDirectoryPicker picker = new QueueDirectoryPicker(
                Optional.of(selected),
                Optional.empty());
        ClientConfiguration persisted = ClientConfiguration.defaults();
        ClientConfigurationDraft draft = new ClientConfigurationDraft(persisted, picker);

        String selectedPath = selected.toAbsolutePath().normalize().toString();
        assertEquals(Optional.of(selectedPath),
                draft.selectDataDirectory(temporaryDirectory, "").get());
        assertEquals(temporaryDirectory.toAbsolutePath().normalize(), picker.lastInitialDirectory);
        assertEquals("", draft.value().storage().dataDirectory());
        assertEquals("", persisted.storage().dataDirectory());

        ClientConfigurationDraft cancelledScreen = new ClientConfigurationDraft(
                persisted, picker);
        assertEquals(Optional.empty(), cancelledScreen.selectDataDirectory(
                temporaryDirectory, selectedPath).get());
        assertEquals(selected.toAbsolutePath().normalize(), picker.lastInitialDirectory);
        assertEquals("", persisted.storage().dataDirectory());

        draft.setDataDirectory(selectedPath);
        persisted = draft.value();
        assertEquals(selectedPath, persisted.storage().dataDirectory());

        draft.setDataDirectory(ClientConfiguration.defaults().storage().dataDirectory());
        assertEquals("", draft.value().storage().dataDirectory());
    }

    @Test
    void abbreviatedPathUsesOnlyTheLastTwoSections() {
        assertEquals("", ClientConfigurationDraft.abbreviatedDataDirectory(""));
        assertEquals(".../folder1/folderX", ClientConfigurationDraft.abbreviatedDataDirectory(
                Path.of("/parent/folder1/folderX").toString()));
        assertEquals(".../folderX", ClientConfigurationDraft.abbreviatedDataDirectory(
                Path.of("/folderX").toString()));
    }

    private static final class QueueDirectoryPicker implements FilePicker {
        private final Queue<Optional<Path>> selections = new ArrayDeque<>();
        private Path lastInitialDirectory;

        @SafeVarargs
        private QueueDirectoryPicker(Optional<Path>... selections) {
            for (Optional<Path> selection : selections) {
                this.selections.add(selection);
            }
        }

        @Override
        public CompletableFuture<Optional<Path>> chooseSkinPng() {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<Optional<Path>> chooseDirectory(Path initialDirectory) {
            lastInitialDirectory = initialDirectory;
            return CompletableFuture.completedFuture(selections.remove());
        }
    }
}

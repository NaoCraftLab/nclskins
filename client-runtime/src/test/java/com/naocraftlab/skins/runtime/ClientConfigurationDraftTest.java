package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.core.config.ClientConfiguration;
import com.naocraftlab.skins.core.config.ConfigurationDescriptions;
import com.naocraftlab.skins.core.config.Json5ConfigurationRepository;
import com.naocraftlab.skins.core.config.MenuPreviewPlacement;
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
    void previewDraftSaveCancelAndResetKeepContextsIndependent() {
        var service = configurationService();
        var original = service.client();
        var draft = new ClientConfigurationDraft(original, new QueueDirectoryPicker());
        draft.setTitleScreenPreview(MenuPreviewPlacement.LEFT);
        assertEquals(MenuPreviewPlacement.RIGHT, draft.value().menuPreview().pauseMenu());
        draft.setPauseMenuPreview(MenuPreviewPlacement.OFF);
        assertEquals(original, service.client());
        assertEquals(original, configurationService().client());
        service.saveClient(draft.value());
        assertEquals(MenuPreviewPlacement.LEFT, service.client().menuPreview().titleScreen());
        assertEquals(MenuPreviewPlacement.OFF, configurationService().client().menuPreview().pauseMenu());
        var cancelled = new ClientConfigurationDraft(service.client(), new QueueDirectoryPicker());
        cancelled.setTitleScreenPreview(MenuPreviewPlacement.OFF);
        assertEquals(MenuPreviewPlacement.LEFT, service.client().menuPreview().titleScreen());
        draft.setTitleScreenPreview(ClientConfiguration.defaults().menuPreview().titleScreen());
        assertEquals(MenuPreviewPlacement.OFF, draft.value().menuPreview().pauseMenu());
        assertEquals(MenuPreviewPlacement.LEFT, service.client().menuPreview().titleScreen());
        service.saveClient(draft.value());
        assertEquals(MenuPreviewPlacement.RIGHT, service.client().menuPreview().titleScreen());
    }

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

    @Test
    void compatibilitySettingsRemainIndependentInDraft() {
        ClientConfigurationDraft draft = new ClientConfigurationDraft(
                ClientConfiguration.defaults(), new QueueDirectoryPicker());

        draft.setHideIncompatibleCatalogSkins(true);
        assertEquals(true, draft.value().compatibility().hideIncompatibleCatalogSkins());
        assertEquals(false, draft.value().compatibility().hideIncompatibleGalleryLooks());

        draft.setHideIncompatibleGalleryLooks(true);
        draft.setHideIncompatibleCatalogSkins(false);
        assertEquals(false, draft.value().compatibility().hideIncompatibleCatalogSkins());
        assertEquals(true, draft.value().compatibility().hideIncompatibleGalleryLooks());
    }

    private ClientConfigurationService configurationService() {
        var descriptions = new ConfigurationDescriptions(java.util.Map.ofEntries(
                java.util.Map.entry(ConfigurationDescriptions.CLIENT_TITLE_SCREEN, "Test description"),
                java.util.Map.entry(ConfigurationDescriptions.CLIENT_PAUSE_MENU, "Test description"),
                java.util.Map.entry(ConfigurationDescriptions.CLIENT_DATA_DIRECTORY, "Test description"),
                java.util.Map.entry(ConfigurationDescriptions.CLIENT_HIDE_INCOMPATIBLE_CATALOG, "Test description"),
                java.util.Map.entry(ConfigurationDescriptions.CLIENT_HIDE_INCOMPATIBLE_GALLERY, "Test description"),
                java.util.Map.entry(ConfigurationDescriptions.SERVER_ENABLED, "Test description"),
                java.util.Map.entry(ConfigurationDescriptions.SERVER_TRUSTED_PROXY, "Test description"),
                java.util.Map.entry(ConfigurationDescriptions.SERVER_MAX_CONCURRENT, "Test description"),
                java.util.Map.entry(ConfigurationDescriptions.SERVER_LOOKUP_RATE, "Test description"),
                java.util.Map.entry(ConfigurationDescriptions.SERVER_LOOKUP_BURST, "Test description")));
        return new ClientConfigurationService(temporaryDirectory,
                new Json5ConfigurationRepository(temporaryDirectory, descriptions));
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

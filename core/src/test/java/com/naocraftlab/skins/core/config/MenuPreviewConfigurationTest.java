package com.naocraftlab.skins.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.naocraftlab.skins.core.config.MenuPreviewPlacement.LEFT;
import static com.naocraftlab.skins.core.config.MenuPreviewPlacement.OFF;
import static com.naocraftlab.skins.core.config.MenuPreviewPlacement.RIGHT;
import static com.naocraftlab.skins.core.config.MenuPreviewPlacement.values;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MenuPreviewConfigurationTest {
    @TempDir Path directory;

    @Test
    void migratesEveryPublishedBooleanCombinationAndPreservesOtherSettings() throws Exception {
        for (boolean title : new boolean[] {true, false}) {
            for (boolean pause : new boolean[] {true, false}) {
                String fixture;
                try (var input = getClass().getResourceAsStream("/config/published-client.json5")) {
                    fixture = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                Files.writeString(file(), fixture.replace("\"titleScreen\": true", "\"titleScreen\": " + title)
                        .replace("\"pauseMenu\": true", "\"pauseMenu\": " + pause));
                var loaded = repository().loadClient();
                assertEquals(title ? RIGHT : OFF, loaded.menuPreview().titleScreen());
                assertEquals(pause ? RIGHT : OFF, loaded.menuPreview().pauseMenu());
                assertTrue(loaded.compatibility().hideIncompatibleCatalogSkins());
                assertFalse(loaded.compatibility().hideIncompatibleGalleryLooks());
                assertEquals("", loaded.storage().dataDirectory());
                String canonical = Files.readString(file());
                assertTrue(canonical.contains("\"titleScreen\": \"" + (title ? "right" : "off") + "\""));
                assertEquals(loaded, repository().loadClient());
                assertEquals(canonical, Files.readString(file()));
            }
        }
    }

    @Test
    void roundTripsAllNineModesAndDefaultsOnlyMissingFields() throws Exception {
        assertEquals(ClientConfiguration.defaults(), repository().loadClient());
        for (var title : values()) {
            for (var pause : values()) {
                var configuration = ClientConfiguration.defaults()
                        .withTitleScreenPreview(title).withPauseMenuPreview(pause);
                repository().saveClient(configuration);
                assertEquals(configuration, repository().loadClient());
            }
        }
        assertModes("{menuPreview:{pauseMenu:\"left\"}}", RIGHT, LEFT);
        assertModes("{menuPreview:{titleScreen:\"left\",pauseMenu:false}}", LEFT, OFF);
        assertThrows(NullPointerException.class, () -> new ClientConfiguration.MenuPreview(null, RIGHT));
        assertThrows(NullPointerException.class, () -> new ClientConfiguration.MenuPreview(RIGHT, null));
    }

    @Test
    void invalidValuesAndAnyDuplicateDefaultOnlyTheirOwnField() throws Exception {
        for (String invalid : new String[] {"null", "1", "{}", "[]", "\"true\"", "\"false\"",
                "\"RIGHT\"", "\" right\"", "\"left \"", "\"unknown\""}) {
            assertModes("{menuPreview:{titleScreen:" + invalid + ",pauseMenu:\"left\"}}", RIGHT, LEFT);
            assertModes("{menuPreview:{titleScreen:false,titleScreen:" + invalid + ",pauseMenu:\"off\"}}", RIGHT, OFF);
            assertModes("{menuPreview:{titleScreen:" + invalid + ",titleScreen:false,pauseMenu:\"off\"}}", RIGHT, OFF);
        }
        assertModes("{menuPreview:{titleScreen:false,titleScreen:false,pauseMenu:\"left\"}}", RIGHT, LEFT);
        assertModes("{menuPreview:{titleScreen:\"off\",pauseMenu:\"left\"},unknown:\"titleScreen:false\"}", OFF, LEFT);
    }

    @Test
    void salvagesMalformedStringsAndBooleansButNotAmbiguousValues() throws Exception {
        assertModes("{ menuPreview: { titleScreen: \"left\", pauseMenu: false,", LEFT, OFF);
        assertModes("{ menuPreview: { titleScreen: false, titleScreen: null, pauseMenu: \"left\",", RIGHT, LEFT);
        assertModes(" ".repeat(256 * 1024 + 1), RIGHT, RIGHT);
    }

    @Test
    void reportsIoFailuresAndLeavesUnrelatedLegacyFilesAlone() throws Exception {
        Path old = directory.resolve("nclskins-client.toml");
        Files.writeString(old, "titleScreen = false");
        repository().loadClient();
        assertEquals("titleScreen = false", Files.readString(old));
        Path blocked = directory.resolve("blocked");
        Files.writeString(blocked, "file");
        assertThrows(ConfigurationException.class,
                () -> Json5ConfigurationRepository.bundled(blocked).saveClient(ClientConfiguration.defaults()));
        Files.delete(file());
        Files.createDirectory(file());
        assertThrows(ConfigurationException.class, () -> repository().loadClient());
    }

    private void assertModes(String document, MenuPreviewPlacement title, MenuPreviewPlacement pause)
            throws Exception {
        Files.writeString(file(), document);
        assertEquals(new ClientConfiguration.MenuPreview(title, pause), repository().loadClient().menuPreview());
    }

    private Path file() {
        return directory.resolve(Json5ConfigurationRepository.CLIENT_FILE_NAME);
    }

    private Json5ConfigurationRepository repository() {
        return Json5ConfigurationRepository.bundled(directory);
    }
}

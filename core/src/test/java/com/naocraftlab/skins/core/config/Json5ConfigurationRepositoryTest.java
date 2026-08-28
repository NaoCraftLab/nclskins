package com.naocraftlab.skins.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


final class Json5ConfigurationRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsCanonicalFilesWithBundledEnglishDescriptions() throws IOException {
        Json5ConfigurationRepository repository = bundledRepository();

        assertEquals(ClientConfiguration.defaults(), repository.loadClient());
        assertEquals(ServerConfiguration.defaults(), repository.loadServer());

        String client = read(Json5ConfigurationRepository.CLIENT_FILE_NAME);
        String server = read(Json5ConfigurationRepository.SERVER_FILE_NAME);
        assertTrue(client.contains("// Show the clickable NCL Skins player preview on the title screen."));
        assertTrue(client.contains("\"dataDirectory\": \"\""));
        assertTrue(client.contains("\"hideIncompatibleCatalogSkins\": false"));
        assertTrue(client.contains("\"hideIncompatibleGalleryLooks\": false"));
        assertTrue(server.contains("// Update your skin for other players without reconnecting"));
        assertTrue(server.contains("\"lookupRatePerSecond\": 10.0"));
        assertFalse(server.contains("\"advanced\""));
        assertTrue(client.endsWith("\n"));
        assertTrue(server.endsWith("\n"));
    }

    @Test
    void compatibilityHideSettingsRoundTripIndependentlyAndMissingFieldsDefaultFalse()
            throws IOException {
        Json5ConfigurationRepository repository = bundledRepository();
        for (boolean catalog : new boolean[] {false, true}) {
            for (boolean gallery : new boolean[] {false, true}) {
                ClientConfiguration configured = ClientConfiguration.defaults()
                        .withHideIncompatibleCatalogSkins(catalog)
                        .withHideIncompatibleGalleryLooks(gallery);
                repository.saveClient(configured);
                ClientConfiguration loaded = repository.loadClient();
                assertEquals(catalog, loaded.compatibility().hideIncompatibleCatalogSkins());
                assertEquals(gallery, loaded.compatibility().hideIncompatibleGalleryLooks());
            }
        }

        Files.writeString(
                temporaryDirectory.resolve(Json5ConfigurationRepository.CLIENT_FILE_NAME),
                "{ menuPreview: { titleScreen: true, pauseMenu: true }, storage: { dataDirectory: \"\" } }",
                StandardCharsets.UTF_8);
        ClientConfiguration legacy = repository.loadClient();
        assertFalse(legacy.compatibility().hideIncompatibleCatalogSkins());
        assertFalse(legacy.compatibility().hideIncompatibleGalleryLooks());
    }

    @Test
    void keepsIndependentValidValuesAndDropsUnknownOrInvalidValues() throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(Json5ConfigurationRepository.CLIENT_FILE_NAME),
                """
                        {
                          menuPreview: {
                            titleScreen: false,
                            pauseMenu: "not-a-boolean",
                            removed: true
                          },
                          storage: { dataDirectory: "relative/path" },
                          unknown: 7
                        }
                        """,
                StandardCharsets.UTF_8);

        ClientConfiguration loaded = bundledRepository().loadClient();

        assertFalse(loaded.menuPreview().titleScreen());
        assertTrue(loaded.menuPreview().pauseMenu());
        assertEquals("", loaded.storage().dataDirectory());
        String rewritten = read(Json5ConfigurationRepository.CLIENT_FILE_NAME);
        assertFalse(rewritten.contains("removed"));
        assertFalse(rewritten.contains("unknown"));
        assertFalse(rewritten.contains("relative/path"));
    }

    @Test
    void salvagesKnownScalarsFromMalformedDocument() throws IOException {
        Path absolute = temporaryDirectory.resolve("shared data").toAbsolutePath();
        Files.writeString(
                temporaryDirectory.resolve(Json5ConfigurationRepository.CLIENT_FILE_NAME),
                """
                        {
                          "menuPreview": {
                            "titleScreen": false,
                            "pauseMenu": true,
                          // the storage object delimiter is intentionally missing
                          "dataDirectory": "%s"
                        """.formatted(escape(absolute.toString())),
                StandardCharsets.UTF_8);

        ClientConfiguration loaded = bundledRepository().loadClient();

        assertFalse(loaded.menuPreview().titleScreen());
        assertTrue(loaded.menuPreview().pauseMenu());
        assertEquals(absolute.normalize().toString(), loaded.storage().dataDirectory());
        assertEquals(
                bundledRepository().canonicalClient(loaded),
                read(Json5ConfigurationRepository.CLIENT_FILE_NAME));
    }

    @Test
    void duplicateKnownValueFallsBackAndCanonicalRewriteRemovesDuplicate() throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(Json5ConfigurationRepository.SERVER_FILE_NAME),
                """
                        {
                          realtimeRefresh: {
                            enabled: false,
                            enabled: false,
                            trustedProxyForwarding: true,
                            advanced: {
                              maxConcurrentLookups: 4,
                              lookupRatePerSecond: -1,
                              lookupBurst: 30
                            }
                          }
                        }
                        """,
                StandardCharsets.UTF_8);

        ServerConfiguration loaded = bundledRepository().loadServer();

        assertTrue(loaded.realtimeRefresh().enabled());
        assertTrue(loaded.realtimeRefresh().trustedProxyForwarding());
        assertEquals(4, loaded.realtimeRefresh().maxConcurrentLookups());
        assertEquals(10.0d, loaded.realtimeRefresh().lookupRatePerSecond());
        assertEquals(30, loaded.realtimeRefresh().lookupBurst());
        String rewritten = read(Json5ConfigurationRepository.SERVER_FILE_NAME);
        assertEquals(1, occurrences(rewritten, "\"enabled\""));
        assertFalse(rewritten.contains("\"advanced\""));
    }

    @Test
    void keepsAllFiveServerValuesInTheFlatRealtimeRefreshObject() throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(Json5ConfigurationRepository.SERVER_FILE_NAME),
                """
                        {
                          realtimeRefresh: {
                            enabled: false,
                            trustedProxyForwarding: true,
                            maxConcurrentLookups: 7,
                            lookupRatePerSecond: 3.5,
                            lookupBurst: 11,
                            removed: 99
                          }
                        }
                        """,
                StandardCharsets.UTF_8);

        ServerConfiguration loaded = bundledRepository().loadServer();

        assertFalse(loaded.realtimeRefresh().enabled());
        assertTrue(loaded.realtimeRefresh().trustedProxyForwarding());
        assertEquals(7, loaded.realtimeRefresh().maxConcurrentLookups());
        assertEquals(3.5d, loaded.realtimeRefresh().lookupRatePerSecond());
        assertEquals(11, loaded.realtimeRefresh().lookupBurst());
        String rewritten = read(Json5ConfigurationRepository.SERVER_FILE_NAME);
        assertFalse(rewritten.contains("removed"));
        assertFalse(rewritten.contains("advanced"));
        assertTrue(rewritten.indexOf("\"trustedProxyForwarding\"")
                < rewritten.indexOf("\"maxConcurrentLookups\""));
    }

    @Test
    void refreshesOutdatedCommentsWithoutChangingValues() throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(Json5ConfigurationRepository.CLIENT_FILE_NAME),
                """
                        {
                          "menuPreview": {
                            // Old description.
                            "titleScreen": false,
                            "pauseMenu": false
                          },
                          "storage": {
                            "dataDirectory": ""
                          }
                        }
                        """,
                StandardCharsets.UTF_8);

        ClientConfiguration loaded = bundledRepository().loadClient();

        assertFalse(loaded.menuPreview().titleScreen());
        assertFalse(loaded.menuPreview().pauseMenu());
        String rewritten = read(Json5ConfigurationRepository.CLIENT_FILE_NAME);
        assertFalse(rewritten.contains("Old description"));
        assertTrue(rewritten.contains("Show the clickable NCL Skins player preview"));
    }

    @Test
    void emptyOrAbsoluteDataRootIsAcceptedAndRelativePathIsRejected() {
        assertTrue(ClientConfiguration.validDataDirectory(""));
        assertTrue(ClientConfiguration.validDataDirectory(temporaryDirectory.toAbsolutePath().toString()));
        assertFalse(ClientConfiguration.validDataDirectory("relative/path"));
    }

    private Json5ConfigurationRepository bundledRepository() {
        return new Json5ConfigurationRepository(
                temporaryDirectory,
                ConfigurationDescriptions.loadEnglish(getClass().getClassLoader()));
    }

    private String read(String fileName) throws IOException {
        return Files.readString(temporaryDirectory.resolve(fileName), StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}

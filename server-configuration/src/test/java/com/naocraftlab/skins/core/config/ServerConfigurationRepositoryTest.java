package com.naocraftlab.skins.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


final class ServerConfigurationRepositoryTest {
    private static final Map<String, String> DESCRIPTIONS = Map.of(
            ServerConfigurationRepository.ENABLED_DESCRIPTION, "Enabled.",
            ServerConfigurationRepository.TRUSTED_PROXY_DESCRIPTION, "Trusted proxy.",
            ServerConfigurationRepository.MAX_CONCURRENT_DESCRIPTION, "Maximum concurrent.",
            ServerConfigurationRepository.LOOKUP_RATE_DESCRIPTION, "Lookup rate.",
            ServerConfigurationRepository.LOOKUP_BURST_DESCRIPTION, "Lookup burst.");

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsCanonicalServerConfiguration() throws IOException {
        ServerConfigurationRepository repository = repository();

        assertEquals(ServerConfiguration.defaults(), repository.load());
        String written = Files.readString(
                temporaryDirectory.resolve(ServerConfigurationRepository.FILE_NAME));
        assertTrue(written.contains("// Enabled."));
        assertTrue(written.contains("\"lookupRatePerSecond\": 10.0"));
        assertTrue(written.endsWith("\n"));
    }

    @Test
    void salvagesUniqueKnownValuesAndRejectsDuplicates() throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(ServerConfigurationRepository.FILE_NAME),
                """
                        {
                          realtimeRefresh: {
                            enabled: false,
                            enabled: false,
                            trustedProxyForwarding: true,
                            maxConcurrentLookups: 7,
                            lookupRatePerSecond: -1,
                            lookupBurst: 11,
                            removed: 99
                          }
                        }
                        """,
                StandardCharsets.UTF_8);

        ServerConfiguration loaded = repository().load();

        assertTrue(loaded.realtimeRefresh().enabled());
        assertTrue(loaded.realtimeRefresh().trustedProxyForwarding());
        assertEquals(7, loaded.realtimeRefresh().maxConcurrentLookups());
        assertEquals(10.0d, loaded.realtimeRefresh().lookupRatePerSecond());
        assertEquals(11, loaded.realtimeRefresh().lookupBurst());
        String rewritten = Files.readString(
                temporaryDirectory.resolve(ServerConfigurationRepository.FILE_NAME));
        assertFalse(rewritten.contains("removed"));
    }

    private ServerConfigurationRepository repository() {
        return new ServerConfigurationRepository(temporaryDirectory, DESCRIPTIONS::get);
    }
}

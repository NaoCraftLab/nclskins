package com.naocraftlab.skins.runtime.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class UpdateCatalogTest {
    private final UpdateCatalogParser parser = new UpdateCatalogParser();
    private final UpdateSelector selector = new UpdateSelector();

    @Test
    void selectorFiltersByExactTargetBeforeChannelAndVersion() {
        UpdateCatalog catalog = parser.parse(catalog(
                releases(
                        release("1.0.0-alpha.4", "alpha"),
                        release("1.0.0-beta.1", "beta"),
                        release("1.0.0", "release")),
                targets(
                        target("fabric-1.21.1", "fabric", "1.21.1", "1.0.0-alpha.4"),
                        target("neoforge-26.2", "neoforge", "26.2", "1.0.0-beta.1"),
                        target("forge-1.20.1", "forge", "1.20.1", "1.0.0"))));

        assertEquals("1.0.0-alpha.4", selector.select(
                catalog, "fabric-1.21.1", "1.0.0-alpha.1", UpdateChannel.ALPHA)
                .orElseThrow().version().toString());
        assertTrue(selector.select(
                catalog, "fabric-1.21.1", "1.0.0-alpha.1", UpdateChannel.BETA).isEmpty());
        assertEquals("1.0.0-beta.1", selector.select(
                catalog, "neoforge-26.2", "1.0.0-alpha.1", UpdateChannel.BETA)
                .orElseThrow().version().toString());
        assertEquals("1.0.0", selector.select(
                catalog, "forge-1.20.1", "1.0.0-beta.9", UpdateChannel.RELEASE)
                .orElseThrow().version().toString());
    }

    @Test
    void betaPreferenceChoosesNewestStableOrBetaAndNeverAlpha() {
        UpdateCatalog catalog = parser.parse(catalog(
                releases(
                        release("1.0.1-alpha.1", "alpha"),
                        release("1.0.0-beta.3", "beta"),
                        release("1.0.0", "release")),
                targets(target("fabric-26.2", "fabric", "26.2",
                        "1.0.1-alpha.1", "1.0.0-beta.3", "1.0.0"))));

        UpdateCandidate candidate = selector.select(
                catalog, "fabric-26.2", "1.0.0-beta.1", UpdateChannel.BETA)
                .orElseThrow();

        assertEquals("1.0.0", candidate.version().toString());
        assertEquals("https://github.com/NaoCraftLab/nclskins/releases/tag/1.0.0",
                candidate.url().toString());
    }

    @Test
    void missingTargetAndCurrentAheadOrEqualProduceNoCandidate() {
        UpdateCatalog catalog = parser.parse(catalog(
                releases(release("1.0.0-beta.3", "beta")),
                targets(target("fabric-26.2", "fabric", "26.2", "1.0.0-beta.3"))));

        assertTrue(selector.select(
                catalog, "fabric-1.21.1", "1.0.0-alpha.1", UpdateChannel.ALPHA).isEmpty());
        assertTrue(selector.select(
                catalog, "fabric-26.2", "1.0.0-beta.3", UpdateChannel.ALPHA).isEmpty());
        assertTrue(selector.select(
                catalog, "fabric-26.2", "1.0.0", UpdateChannel.ALPHA).isEmpty());
    }

    @Test
    void wrongSchemaProjectUrlChannelAndDanglingReferenceAreRejected() {
        String valid = catalog(
                releases(release("1.0.0-beta.3", "beta")),
                targets(target("fabric-26.2", "fabric", "26.2", "1.0.0-beta.3")));

        assertInvalid(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
        assertInvalid(valid.replace("\"project\":\"nclskins\"", "\"project\":\"other\""));
        assertInvalid(valid.replace("github.com/NaoCraftLab", "example.com/NaoCraftLab"));
        assertInvalid(valid.replace("\"channel\":\"beta\"", "\"channel\":\"release\""));
        assertInvalid(valid.replace("1.0.0-beta.3\"]", "1.0.0-beta.4\"]"));
    }

    @Test
    void duplicateFieldsVersionsAndUnreferencedReleasesAreRejected() {
        String duplicateRoot = "{\"schemaVersion\":1,\"schemaVersion\":1,"
                + "\"project\":\"nclskins\",\"releases\":{},\"targets\":{}}";
        assertInvalid(duplicateRoot);
        assertInvalid(catalog(
                releases(release("1.0.0-beta.3", "beta")),
                targets(target("fabric-26.2", "fabric", "26.2",
                        "1.0.0-beta.3", "1.0.0-beta.3"))));
        assertInvalid(catalog(
                releases(release("1.0.0-beta.3", "beta")),
                targets(target("fabric-26.2", "fabric", "26.2"))));
    }

    @Test
    void targetIdentityMustMatchLoaderAndMinecraftVersion() {
        String valid = catalog(
                releases(release("1.0.0-beta.3", "beta")),
                targets(target("fabric-26.2", "fabric", "26.2", "1.0.0-beta.3")));

        assertInvalid(valid.replace("\"loader\":\"fabric\"", "\"loader\":\"neoforge\""));
        assertInvalid(valid.replace("\"minecraftVersion\":\"26.2\"",
                "\"minecraftVersion\":\"26.1\""));
        assertFalse(parser.parse(valid).targets().isEmpty());
    }

    private void assertInvalid(String document) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> parser.parse(document));
        assertEquals("Invalid NCL update catalog", failure.getMessage());
    }

    private static String catalog(String releases, String targets) {
        return "{\"schemaVersion\":1,\"project\":\"nclskins\",\"releases\":{"
                + releases + "},\"targets\":{" + targets + "}}";
    }

    private static String releases(String... entries) {
        return String.join(",", entries);
    }

    private static String release(String version, String channel) {
        return "\"" + version + "\":{\"channel\":\"" + channel + "\",\"url\":"
                + "\"https://github.com/NaoCraftLab/nclskins/releases/tag/" + version + "\"}";
    }

    private static String targets(String... entries) {
        return String.join(",", entries);
    }

    private static String target(
            String targetId, String loader, String minecraftVersion, String... versions) {
        String values = java.util.Arrays.stream(versions)
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return "\"" + targetId + "\":{\"loader\":\"" + loader
                + "\",\"minecraftVersion\":\"" + minecraftVersion
                + "\",\"versions\":[" + values + "]}";
    }
}

package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


final class SkinExtensionResourceDetectorTest {
    @Test
    void detectsEffectiveFreshAndExpressionSignaturesWithoutPackNames() throws Exception {
        FakeResources resources = new FakeResources();
        resources.put("minecraft:optifine/cem/player.jem", fresh());
        resources.put("minecraft:optifine/cem/player_slim.jem", fresh());
        resources.put("minecraft:emf/cem/player.jem", expressions());
        resources.put("minecraft:emf/cem/player_slim.jem", expressions());
        resources.put("minecraft:emf/cem/player_face.jpm", "{\"var.JEface_id\":\"2*pi\"}");

        SkinExtensionResourceDetector.Result result =
                new SkinExtensionResourceDetector(resources).detect();

        assertTrue(result.freshMoves());
        assertTrue(result.justExpressions());
    }

    @Test
    void disabledOverriddenPartialAndMalformedProfilesAreInactive() throws Exception {
        FakeResources resources = new FakeResources();
        resources.put("minecraft:optifine/cem/player.jem", fresh());
        resources.put("minecraft:optifine/cem/player_slim.jem", "{\"animations\":[]}");
        resources.put("minecraft:emf/cem/player.jem", expressions());
        resources.put("minecraft:emf/cem/player_slim.jem", expressions());

        SkinExtensionResourceDetector.Result result =
                new SkinExtensionResourceDetector(resources).detect();

        assertFalse(result.freshMoves());
        assertFalse(result.justExpressions());
    }

    @Test
    void adapterReadFailureRemainsDistinguishableFromMissingResource() {
        SkinCatalogSource failing = new FakeResources() {
            @Override
            public Optional<byte[]> findResource(String identifier) throws IOException {
                throw new IOException("synthetic failure");
            }
        };

        assertThrows(IOException.class,
                () -> new SkinExtensionResourceDetector(failing).detect());
    }

    private static String fresh() {
        return "{\"id\":\"Created_by_FreshLX_for_Fresh_Animations\",\"animations\":[]}";
    }

    private static String expressions() {
        return "{\"model\": \"player_face.jpm\"}";
    }

    private static class FakeResources implements SkinCatalogSource {
        private final Map<String, byte[]> resources = new HashMap<>();

        void put(String identifier, String value) {
            resources.put(identifier, value.getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public byte[] load(String collectionId, String skinId, SkinModel model) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<byte[]> findResource(String identifier) throws IOException {
            return Optional.ofNullable(resources.get(identifier)).map(byte[]::clone);
        }
    }
}

package com.naocraftlab.skins.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;


public final class SkinExtensionResourceDetector {
    private static final int MAX_MODEL_BYTES = 512 * 1024;
    private final SkinCatalogSource resources;

    public SkinExtensionResourceDetector(SkinCatalogSource resources) {
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public Result detect() throws IOException {
        Optional<byte[]> freshClassic = bounded("minecraft:optifine/cem/player.jem");
        Optional<byte[]> freshSlim = bounded("minecraft:optifine/cem/player_slim.jem");
        Optional<byte[]> expressionsClassic = bounded("minecraft:emf/cem/player.jem");
        Optional<byte[]> expressionsSlim = bounded("minecraft:emf/cem/player_slim.jem");
        Optional<byte[]> expressionsFace = bounded("minecraft:emf/cem/player_face.jpm");
        boolean freshMoves = freshClassic.filter(SkinExtensionResourceDetector::freshModel)
                        .isPresent()
                && freshSlim.filter(SkinExtensionResourceDetector::freshModel).isPresent();
        boolean justExpressions = expressionsClassic
                        .filter(SkinExtensionResourceDetector::expressionsModel)
                        .isPresent()
                && expressionsSlim.filter(SkinExtensionResourceDetector::expressionsModel)
                        .isPresent()
                && expressionsFace.filter(bytes -> contains(bytes, "\"var.JEface_id\""))
                        .isPresent();
        return new Result(freshMoves, justExpressions);
    }

    private Optional<byte[]> bounded(String identifier) throws IOException {
        Optional<byte[]> bytes = resources.findResource(identifier);
        if (bytes.isPresent() && (bytes.orElseThrow().length == 0
                || bytes.orElseThrow().length > MAX_MODEL_BYTES)) {
            return Optional.empty();
        }
        return bytes;
    }

    private static boolean freshModel(byte[] bytes) {
        return contains(bytes, "Created_by_FreshLX_for_Fresh_Animations")
                && contains(bytes, "\"animations\"");
    }

    private static boolean expressionsModel(byte[] bytes) {
        return contains(bytes, "\"model\": \"player_face.jpm\"");
    }

    private static boolean contains(byte[] bytes, String token) {
        byte[] expected = token.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int offset = 0; offset <= bytes.length - expected.length; offset++) {
            for (int index = 0; index < expected.length; index++) {
                if (bytes[offset + index] != expected[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    public record Result(boolean freshMoves, boolean justExpressions) {}
}

package com.naocraftlab.skins.server.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.naocraftlab.skins.server.ServerPlayerIdentity;
import com.naocraftlab.skins.server.TextureAppearance;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;


public final class OfficialTextureAppearanceParser {
    private static final String TEXTURE_HOST = "textures.minecraft.net";
    private static final String TEXTURE_PATH_PREFIX = "/texture/";
    private static final int MAX_ENCODED_PAYLOAD_CHARS = 131_072;
    private static final int MAX_DECODED_PAYLOAD_BYTES = 98_304;
    private static final Set<String> TEXTURE_KEYS = Set.of("SKIN", "CAPE", "ELYTRA");

    private OfficialTextureAppearanceParser() {}


    public static Optional<TextureAppearance> parseVerified(
            String verifiedEncodedPayload,
            ServerPlayerIdentity expectedIdentity) {
        Objects.requireNonNull(verifiedEncodedPayload, "verifiedEncodedPayload");
        Objects.requireNonNull(expectedIdentity, "expectedIdentity");
        if (verifiedEncodedPayload.isEmpty()
                || verifiedEncodedPayload.length() > MAX_ENCODED_PAYLOAD_CHARS
                || verifiedEncodedPayload.length() % 4 != 0) {
            return Optional.empty();
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(verifiedEncodedPayload);
            if (decoded.length > MAX_DECODED_PAYLOAD_BYTES) {
                return Optional.empty();
            }
            String decodedVerifiedPayload = decodeUtf8(decoded);
            JsonElement parsed = JsonParser.parseString(decodedVerifiedPayload);
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject object = parsed.getAsJsonObject();
            if (!matchesIdentity(object, expectedIdentity)) {
                return Optional.empty();
            }
            long sourceTimestamp = requiredTimestamp(object);

            JsonElement texturesElement = object.get("textures");
            if (texturesElement == null) {
                return Optional.of(TextureAppearance.accountDefault()
                        .withVerifiedSourceTimestamp(sourceTimestamp));
            }
            if (!texturesElement.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject textures = texturesElement.getAsJsonObject();
            if (textures.size() == 0) {
                return Optional.of(TextureAppearance.accountDefault()
                        .withVerifiedSourceTimestamp(sourceTimestamp));
            }
            if (!TEXTURE_KEYS.containsAll(textures.keySet())) {
                return Optional.empty();
            }

            Optional<String> skinIdentity = Optional.empty();
            Optional<TextureAppearance.SkinModel> model = Optional.empty();
            JsonElement skinElement = textures.get("SKIN");
            if (skinElement != null) {
                if (!skinElement.isJsonObject()) {
                    return Optional.empty();
                }
                JsonObject skin = skinElement.getAsJsonObject();
                String parsedSkinIdentity = textureIdentity(skin);
                TextureAppearance.SkinModel parsedModel = skinModel(skin);
                if (parsedSkinIdentity == null || parsedModel == null) {
                    return Optional.empty();
                }
                skinIdentity = Optional.of(parsedSkinIdentity);
                model = Optional.of(parsedModel);
            }
            Optional<String> cape = optionalTextureIdentity(textures, "CAPE");
            Optional<String> elytra = optionalTextureIdentity(textures, "ELYTRA");
            if (cape == null || elytra == null) {
                return Optional.empty();
            }
            return Optional.of(TextureAppearance.verified(
                    skinIdentity,
                    model,
                    cape,
                    elytra).withVerifiedSourceTimestamp(sourceTimestamp));
        } catch (RuntimeException | CharacterCodingException invalidPayload) {
            return Optional.empty();
        }
    }

    private static boolean matchesIdentity(
            JsonObject object, ServerPlayerIdentity expectedIdentity) {
        String profileId = requiredString(object, "profileId");
        String profileName = requiredString(object, "profileName");
        String compactId;
        if (profileId.length() == 32 && isLowerOrUpperHex(profileId)) {
            compactId = profileId;
        } else if (profileId.length() == 36) {
            try {
                compactId = UUID.fromString(profileId).toString().replace("-", "");
            } catch (IllegalArgumentException invalidProfileId) {
                return false;
            }
        } else {
            return false;
        }
        String expectedId = expectedIdentity.profileId().toString().replace("-", "");
        return expectedId.equalsIgnoreCase(compactId)
                && expectedIdentity.profileName().equals(profileName);
    }

    private static TextureAppearance.SkinModel skinModel(JsonObject skin) {
        JsonElement metadataElement = skin.get("metadata");
        if (metadataElement == null) {
            return TextureAppearance.SkinModel.CLASSIC;
        }
        if (!metadataElement.isJsonObject()) {
            return null;
        }
        JsonObject metadata = metadataElement.getAsJsonObject();
        JsonElement modelElement = metadata.get("model");
        if (modelElement == null) {
            return TextureAppearance.SkinModel.CLASSIC;
        }
        if (!modelElement.isJsonPrimitive() || !modelElement.getAsJsonPrimitive().isString()) {
            return null;
        }
        return switch (modelElement.getAsString()) {
            case "classic" -> TextureAppearance.SkinModel.CLASSIC;
            case "slim" -> TextureAppearance.SkinModel.SLIM;
            default -> null;
        };
    }


    private static Optional<String> optionalTextureIdentity(JsonObject textures, String key) {
        JsonElement element = textures.get(key);
        if (element == null) {
            return Optional.empty();
        }
        if (!element.isJsonObject()) {
            return null;
        }
        String identity = textureIdentity(element.getAsJsonObject());
        return identity == null ? null : Optional.of(identity);
    }


    private static String textureIdentity(JsonObject texture) {
        String rawUrl = requiredString(texture, "url");
        final URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException invalidUri) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null
                || !TEXTURE_HOST.equalsIgnoreCase(uri.getHost())
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getRawPath() == null
                || uri.getRawPath().indexOf('%') >= 0) {
            return null;
        }
        int port = uri.getPort();
        if (port != -1
                && !("http".equalsIgnoreCase(scheme) && port == 80)
                && !("https".equalsIgnoreCase(scheme) && port == 443)) {
            return null;
        }
        String path = uri.getRawPath();
        if (!path.startsWith(TEXTURE_PATH_PREFIX)) {
            return null;
        }
        String identity = path.substring(TEXTURE_PATH_PREFIX.length());
        if (identity.length() != 64 || !isLowerOrUpperHex(identity)) {
            return null;
        }
        return identity.toLowerCase(Locale.ROOT);
    }

    private static String requiredString(JsonObject object, String member) {
        JsonElement value = object.get(member);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Required verified payload member is invalid");
        }
        String result = value.getAsString();
        if (result.isBlank()) {
            throw new IllegalArgumentException("Required verified payload member is blank");
        }
        return result;
    }

    private static long requiredTimestamp(JsonObject object) {
        JsonElement value = object.get("timestamp");
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Verified payload timestamp is invalid");
        }
        BigDecimal timestamp = value.getAsBigDecimal();
        long result = timestamp.longValueExact();
        if (result < 0L) {
            throw new IllegalArgumentException("Verified payload timestamp is negative");
        }
        return result;
    }

    private static boolean isLowerOrUpperHex(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f'
                    || character >= 'A' && character <= 'F')) {
                return false;
            }
        }
        return !value.isEmpty();
    }

    private static String decodeUtf8(byte[] payload) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload))
                .toString();
    }
}

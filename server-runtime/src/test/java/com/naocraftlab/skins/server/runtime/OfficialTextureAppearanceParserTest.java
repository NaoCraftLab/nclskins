package com.naocraftlab.skins.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.server.ServerPlayerIdentity;
import com.naocraftlab.skins.server.TextureAppearance;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class OfficialTextureAppearanceParserTest {
    private static final String SKIN_A = "a".repeat(64);
    private static final String SKIN_B = "b".repeat(64);
    private static final String CAPE_A = "c".repeat(64);
    private static final String CAPE_B = "d".repeat(64);
    private static final String ELYTRA_A = "e".repeat(64);
    private static final ServerPlayerIdentity IDENTITY = new ServerPlayerIdentity(
            UUID.nameUUIDFromBytes("verified-payload-player".getBytes(StandardCharsets.UTF_8)),
            "VerifiedPlayer");

    @Test
    void timestampTransportAndSignatureChangesDoNotChangeTheSemanticKey() {
        String first = payload(
                1L,
                textures(
                        texture("http://textures.minecraft.net:80/texture/" + SKIN_A, "slim"),
                        texture("https://textures.minecraft.net/texture/" + CAPE_A, null),
                        texture("https://textures.minecraft.net/texture/" + ELYTRA_A, null)));
        String second = payload(
                999_999L,
                textures(
                        texture("https://textures.minecraft.net:443/texture/" + SKIN_A, "slim"),
                        texture("http://textures.minecraft.net/texture/" + CAPE_A, null),
                        texture("https://textures.minecraft.net/texture/" + ELYTRA_A, null)));

        TextureAppearance firstKey = parse(first).orElseThrow();
        TextureAppearance secondKey = parse(second).orElseThrow();

        assertEquals(firstKey, secondKey);
        assertTrue(firstKey.isVerified());
        assertEquals("TextureAppearance[verified]", firstKey.toString());
        assertFalse(firstKey.toString().contains(SKIN_A));
        assertFalse(firstKey.toString().contains(CAPE_A));
    }

    @Test
    void skinModelCapeAndElytraAllParticipateInTheKey() {
        TextureAppearance baseline = parse(payload(
                1L,
                textures(
                        texture(textureUrl(SKIN_A), null),
                        texture(textureUrl(CAPE_A), null),
                        texture(textureUrl(ELYTRA_A), null)))).orElseThrow();
        TextureAppearance changedSkin = parse(payload(
                2L,
                textures(
                        texture(textureUrl(SKIN_B), null),
                        texture(textureUrl(CAPE_A), null),
                        texture(textureUrl(ELYTRA_A), null)))).orElseThrow();
        TextureAppearance changedModel = parse(payload(
                3L,
                textures(
                        texture(textureUrl(SKIN_A), "slim"),
                        texture(textureUrl(CAPE_A), null),
                        texture(textureUrl(ELYTRA_A), null)))).orElseThrow();
        TextureAppearance changedCape = parse(payload(
                4L,
                textures(
                        texture(textureUrl(SKIN_A), null),
                        texture(textureUrl(CAPE_B), null),
                        texture(textureUrl(ELYTRA_A), null)))).orElseThrow();
        TextureAppearance noElytra = parse(payload(
                5L,
                textures(
                        texture(textureUrl(SKIN_A), null),
                        texture(textureUrl(CAPE_A), null),
                        null))).orElseThrow();
        TextureAppearance capeOnly = parse(payload(
                6L,
                "{\"CAPE\":" + texture(textureUrl(CAPE_A), null) + '}')).orElseThrow();

        assertNotEquals(baseline, changedSkin);
        assertNotEquals(baseline, changedModel);
        assertNotEquals(baseline, changedCape);
        assertNotEquals(baseline, noElytra);
        assertTrue(capeOnly.isVerified());
        assertFalse(capeOnly.isAccountDefault());
    }

    @Test
    void absentOrEmptyTexturesIsVerifiedAccountDefault() {
        TextureAppearance absent = parse(payloadWithoutTextures(1L)).orElseThrow();
        TextureAppearance empty = parse(payload(2L, "{}")).orElseThrow();

        assertTrue(absent.isAccountDefault());
        assertEquals(absent, empty);
    }

    @Test
    void rejectsIdentityMismatchMalformedSemanticsAndNonCanonicalHosts() {
        String expectedId = IDENTITY.profileId().toString().replace("-", "");
        String mismatchedId = (expectedId.charAt(0) == '0' ? "1" : "0")
                + expectedId.substring(1);
        for (String invalid : new String[] {
            "{}",
            payloadWithIdentity(mismatchedId, IDENTITY.profileName(), "{}"),
            payloadWithIdentity(expectedId, "OtherPlayer", "{}"),
            payload(1L, "{\"SKIN\":"
                    + texture("https://textures.minecraft.net.evil.example/texture/" + SKIN_A, null)
                    + '}'),
            payload(1L, "{\"SKIN\":"
                    + texture("https://textures.minecraft.net/other/" + SKIN_A, null) + '}'),
            payload(1L, "{\"SKIN\":"
                    + texture(textureUrl(SKIN_A) + "?tracking=1", null) + '}'),
            payload(1L, "{\"SKIN\":" + texture(textureUrl(SKIN_A), "wide") + '}'),
            payload(1L, "{\"SKIN\":" + texture(textureUrl(SKIN_A), null)
                    + ",\"FUTURE\":" + texture(textureUrl(CAPE_A), null) + '}')
        }) {
            assertTrue(parse(invalid).isEmpty());
        }
        assertTrue(OfficialTextureAppearanceParser.parseVerified(
                "not-valid-base64!", IDENTITY).isEmpty());
    }

    private static Optional<TextureAppearance> parse(String payload) {
        String encoded = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return OfficialTextureAppearanceParser.parseVerified(encoded, IDENTITY);
    }

    private static String payload(long timestamp, String textures) {
        return payloadWithIdentity(
                IDENTITY.profileId().toString().replace("-", ""),
                IDENTITY.profileName(),
                textures).replace("\"timestamp\":0", "\"timestamp\":" + timestamp);
    }

    private static String payloadWithoutTextures(long timestamp) {
        return "{\"timestamp\":" + timestamp + ",\"profileId\":\""
                + IDENTITY.profileId().toString().replace("-", "")
                + "\",\"profileName\":\"" + IDENTITY.profileName() + "\"}";
    }

    private static String payloadWithIdentity(String profileId, String profileName, String textures) {
        return "{\"timestamp\":0,\"profileId\":\"" + profileId
                + "\",\"profileName\":\"" + profileName
                + "\",\"textures\":" + textures + '}';
    }

    private static String textures(String skin, String cape, String elytra) {
        StringBuilder result = new StringBuilder("{\"SKIN\":").append(skin);
        if (cape != null) {
            result.append(",\"CAPE\":").append(cape);
        }
        if (elytra != null) {
            result.append(",\"ELYTRA\":").append(elytra);
        }
        return result.append('}').toString();
    }

    private static String texture(String url, String model) {
        StringBuilder result = new StringBuilder("{\"url\":\"").append(url).append('"');
        if (model != null) {
            result.append(",\"metadata\":{\"model\":\"").append(model).append("\"}");
        }
        return result.append('}').toString();
    }

    private static String textureUrl(String identity) {
        return "https://textures.minecraft.net/texture/" + identity;
    }
}

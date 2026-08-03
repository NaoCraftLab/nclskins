package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpectedAppearanceTest {
    @Test
    void canonicalizesOfficialHttpTexturesToHttps() {
        var expected = new ExpectedAppearance(
                UUID.fromString("12345678-1234-5678-9abc-def012345678"),
                Optional.of(URI.create("http://textures.minecraft.net:80/texture/skin?a=1")),
                Optional.of(SkinModel.CLASSIC),
                Optional.empty());

        assertEquals(
                URI.create("https://textures.minecraft.net/texture/skin?a=1"),
                expected.skinTexture().orElseThrow());
    }

    @Test
    void rejectsNonAllowlistedTextureHosts() {
        assertThrows(IllegalArgumentException.class, () -> new ExpectedAppearance(
                UUID.randomUUID(),
                Optional.of(URI.create("https://textures.minecraft.net.evil.example/texture/skin")),
                Optional.of(SkinModel.CLASSIC),
                Optional.empty()));
    }

    @Test
    void distinguishesClassicAndSlimForTheSameTexture() {
        UUID profileId = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        URI texture = URI.create("https://textures.minecraft.net/texture/same");
        var classic = new ExpectedAppearance(
                profileId, Optional.of(texture), Optional.of(SkinModel.CLASSIC), Optional.empty());
        var slim = new ExpectedAppearance(
                profileId, Optional.of(texture), Optional.of(SkinModel.SLIM), Optional.empty());

        assertNotEquals(classic, slim);
    }

    @Test
    void requiresModelExactlyWhenSkinIsPresent() {
        assertThrows(IllegalArgumentException.class, () -> new ExpectedAppearance(
                UUID.randomUUID(),
                Optional.of(URI.create("https://textures.minecraft.net/texture/skin")),
                Optional.empty(),
                Optional.empty()));
    }

    @Test
    void acceptsAContentAddressedLocalSkinWithoutInventingAnOfficialUrl() {
        String hash = "a".repeat(64);
        var expected = new ExpectedAppearance(
                UUID.randomUUID(),
                Optional.empty(),
                Optional.of(hash),
                Optional.of(SkinModel.SLIM),
                Optional.empty());

        assertEquals(Optional.of(hash), expected.localSkinSha256());
        assertTrue(expected.skinTexture().isEmpty());
    }

    @Test
    void rejectsAValueThatIsBothLocalAndRemote() {
        assertThrows(IllegalArgumentException.class, () -> new ExpectedAppearance(
                UUID.randomUUID(),
                Optional.of(URI.create("https://textures.minecraft.net/texture/skin")),
                Optional.of("a".repeat(64)),
                Optional.of(SkinModel.CLASSIC),
                Optional.empty()));
    }
}

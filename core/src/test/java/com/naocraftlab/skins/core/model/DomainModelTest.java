package com.naocraftlab.skins.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainModelTest {
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void skinAssetEnforcesCanonicalHashAndTimestamps() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkinAsset(UUID.randomUUID(), "Skin", "ABC", SkinVariant.CLASSIC, SkinSource.IMPORTED, NOW, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkinAsset(
                        UUID.randomUUID(),
                        "Skin",
                        HASH,
                        SkinVariant.CLASSIC,
                        SkinSource.IMPORTED,
                        NOW,
                        NOW.minusSeconds(1)));
    }

    @Test
    void accountStateRejectsDanglingPresetReferences() {
        AppearancePreset preset = new AppearancePreset(
                UUID.randomUUID(),
                "Missing",
                SkinReference.asset(UUID.randomUUID()),
                null,
                NOW,
                NOW);
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccountState(
                        AccountState.CURRENT_SCHEMA_VERSION,
                        UUID.randomUUID(),
                        List.of(),
                        List.of(preset),
                        NOW));
    }

    @Test
    void accountStateValidatesPersonalSkinAssetMappings() {
        UUID assetId = UUID.randomUUID();
        SkinAsset asset = new SkinAsset(
                assetId,
                "Skin",
                HASH,
                SkinVariant.CLASSIC,
                SkinSource.IMPORTED,
                NOW,
                NOW);
        PersonalSkinEntry wrongVariant = new PersonalSkinEntry(
                HASH,
                "Skin",
                PersonalSkinSource.FILE,
                NOW,
                NOW,
                Map.of(SkinVariant.SLIM, assetId),
                true);
        PersonalSkinEntry missingAsset = new PersonalSkinEntry(
                HASH,
                "Skin",
                PersonalSkinSource.FILE,
                NOW,
                NOW,
                Map.of(SkinVariant.CLASSIC, UUID.randomUUID()),
                true);

        assertThrows(
                IllegalArgumentException.class,
                () -> new AccountState(
                        AccountState.CURRENT_SCHEMA_VERSION,
                        UUID.randomUUID(),
                        List.of(asset),
                        List.of(wrongVariant),
                        List.of(),
                        NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AccountState(
                        AccountState.CURRENT_SCHEMA_VERSION,
                        UUID.randomUUID(),
                        List.of(asset),
                        List.of(missingAsset),
                        List.of(),
                        NOW));
    }

    @Test
    void remoteProfileExposesActiveAssetsAndAllCapes() {
        RemoteSkin skin = new RemoteSkin(
                "skin",
                RemoteAssetState.ACTIVE,
                URI.create("https://textures.minecraft.net/texture/abc"),
                SkinVariant.SLIM,
                "ALEX");
        RemoteCape inactive = new RemoteCape(
                "cape-a", RemoteAssetState.INACTIVE, URI.create("https://textures.minecraft.net/texture/a"), "A");
        RemoteCape active = new RemoteCape(
                "cape-b", RemoteAssetState.ACTIVE, URI.create("https://textures.minecraft.net/texture/b"), "B");
        RemoteProfile profile = new RemoteProfile(
                UUID.randomUUID(), "Player", List.of(skin), List.of(inactive, active), Set.of());

        assertEquals(skin, profile.activeSkin().orElseThrow());
        assertEquals(active, profile.activeCape().orElseThrow());
        assertTrue(profile.ownsCape("cape-a"));
        assertEquals(2, profile.capes().size());
    }
}

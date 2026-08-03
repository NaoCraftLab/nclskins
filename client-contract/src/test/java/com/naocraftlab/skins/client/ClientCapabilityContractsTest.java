package com.naocraftlab.skins.client;

import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource.PlayerAppearance;
import com.naocraftlab.skins.client.SignedProfileResolver.ResolvedProfile;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientCapabilityContractsTest {
    @Test
    void bundledSkinCopiesCanBeOwnedWithoutSharingMutableArrays() {
        byte[] supplied = {1, 2, 3};

        byte[] owned = SkinCatalogSource.ownedCopy(supplied);
        supplied[0] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, owned);
        assertNotSame(supplied, owned);
        assertThrows(IllegalArgumentException.class, () -> SkinCatalogSource.ownedCopy(new byte[0]));
    }

    @Test
    void currentAppearanceIsNeutralAndValidatesBorrowedHandles() {
        var skin = new TextureRegistry.TextureHandle("minecraft:steve", 64, 64);
        var cape = new TextureRegistry.TextureHandle("minecraft:cape", 64, 32);

        var appearance = new PlayerAppearance(skin, SkinModel.CLASSIC, Optional.of(cape));

        assertEquals(SkinModel.CLASSIC, appearance.model());
        assertEquals(Optional.of(cape), appearance.cape());
        assertThrows(
                NullPointerException.class,
                () -> new PlayerAppearance(skin, null, Optional.empty()));
    }

    @Test
    void resolvedSignedProfileCannotCrossAccountBoundaries() {
        UUID expectedId = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        ExpectedAppearance expected = new ExpectedAppearance(
                expectedId,
                Optional.of(URI.create("https://textures.minecraft.net/texture/skin")),
                Optional.of(SkinModel.SLIM),
                Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> new ResolvedProfile<>(UUID.randomUUID(), expected, new Object()));
        ResolvedProfile<String> resolved = new ResolvedProfile<>(expectedId, expected, "signed");
        assertEquals(expected, resolved.expectedAppearance());
    }

    @Test
    void textureHandlesRejectBlankNativeLocations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TextureRegistry.TextureHandle("  ", 64, 64));
    }

    @Test
    void serverAppearanceRefreshNotifierCarriesNoAccountData() {
        AtomicInteger requests = new AtomicInteger();
        ServerAppearanceRefreshNotifier notifier = requests::incrementAndGet;

        notifier.requestOfficialProfileRefresh();
        ServerAppearanceRefreshNotifier.NO_OP.requestOfficialProfileRefresh();

        assertEquals(1, requests.get());
        assertTrue(notifier.activeConnectionGeneration().isPresent());
        assertTrue(ServerAppearanceRefreshNotifier.NO_OP.activeConnectionGeneration().isEmpty());
    }
}

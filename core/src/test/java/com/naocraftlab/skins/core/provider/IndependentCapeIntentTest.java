package com.naocraftlab.skins.core.provider;

import com.naocraftlab.skins.core.model.SkinVariant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IndependentCapeIntentTest {
    @Test void reenableUsesOwnLatestValueAndNoCapeFallsThrough() {
        ProviderChannel<String> channel = ProviderChannel.<String>initial()
                .select(1, "local-a", "owned-b").observeMinecraft("owned-b");
        assertEquals("local-a", channel.resolve().orElseThrow().value());
        channel = channel.disable(BuiltinProvider.OFFLINE).select(2, "local-c", "owned-d");
        assertEquals("local-a", channel.offline().value());
        channel = channel.enable(BuiltinProvider.OFFLINE);
        assertEquals("local-c", channel.offline().value());
        assertEquals("owned-d", channel.desired());
        channel = channel.move(BuiltinProvider.OFFLINE, -1).select(3, null, "owned-d");
        assertEquals("owned-b", channel.resolve().orElseThrow().value());
    }

    @Test
    void reviseComparesCapeIdentityWithoutDiscardingMetadata() {
        ProviderSkin skin = new ProviderSkin("a".repeat(64), SkinVariant.CLASSIC);
        ProviderCape local = new ProviderCape("local-a", "b".repeat(64), true);
        ProviderCape owned = new ProviderCape("owned-a", "c".repeat(64), false);
        AppearanceProviders selected = AppearanceProviders.initial().select(1, skin, local, owned);
        selected = new AppearanceProviders(
                selected.skin().settle(
                        selected.skin().minecraftDelivery(), ProviderDelivery.Status.CONFIRMED, skin),
                selected.cape().settle(
                        selected.cape().minecraftDelivery(), ProviderDelivery.Status.CONFIRMED, owned));

        AppearanceProviders metadataOnly = selected.revise(
                2,
                skin,
                new ProviderCape("local-a", null, null),
                new ProviderCape("owned-a", null, null));

        assertEquals(selected.skin().minecraftDelivery(), metadataOnly.skin().minecraftDelivery());
        assertEquals(selected.cape().minecraftDelivery(), metadataOnly.cape().minecraftDelivery());
        assertEquals(owned, metadataOnly.cape().desired());
        assertEquals("local-a", metadataOnly.cape().offline().value().id());

        AppearanceProviders capeChanged = selected.revise(
                2,
                skin,
                local,
                new ProviderCape("owned-b", null, null));
        assertEquals(selected.skin().minecraftDelivery(), capeChanged.skin().minecraftDelivery());
        assertNotEquals(selected.cape().minecraftDelivery(), capeChanged.cape().minecraftDelivery());
        assertEquals(2, capeChanged.cape().minecraftDelivery().intentRevision());
        assertEquals(ProviderDelivery.Status.PENDING, capeChanged.cape().minecraftDelivery().status());
    }

    @Test
    void reviseTreatsSkinVariantChangeAsAnIndependentIdentityChange() {
        ProviderSkin classic = new ProviderSkin("a".repeat(64), SkinVariant.CLASSIC);
        ProviderCape cape = new ProviderCape("owned-a", "c".repeat(64), false);
        AppearanceProviders selected = AppearanceProviders.initial().select(1, classic, null, cape);
        selected = new AppearanceProviders(
                selected.skin().settle(
                        selected.skin().minecraftDelivery(), ProviderDelivery.Status.CONFIRMED, classic),
                selected.cape().settle(
                        selected.cape().minecraftDelivery(), ProviderDelivery.Status.CONFIRMED, cape));

        AppearanceProviders revised = selected.revise(
                2, new ProviderSkin(classic.sha256(), SkinVariant.SLIM), null, cape);

        assertEquals(2, revised.skin().minecraftDelivery().intentRevision());
        assertEquals(ProviderDelivery.Status.PENDING,
                revised.skin().minecraftDelivery().status());
        assertEquals(selected.cape().minecraftDelivery(), revised.cape().minecraftDelivery());
    }

    @Test
    void reviseTreatsSkinContentChangeAsAnIndependentIdentityChange() {
        ProviderSkin classic = new ProviderSkin("a".repeat(64), SkinVariant.CLASSIC);
        ProviderCape cape = new ProviderCape("owned-a", null, null);
        AppearanceProviders selected = AppearanceProviders.initial().select(1, classic, null, cape);
        selected = new AppearanceProviders(
                selected.skin().settle(
                        selected.skin().minecraftDelivery(), ProviderDelivery.Status.CONFIRMED, classic),
                selected.cape().settle(
                        selected.cape().minecraftDelivery(), ProviderDelivery.Status.CONFIRMED, cape));

        AppearanceProviders revised = selected.revise(
                2, new ProviderSkin("b".repeat(64), SkinVariant.CLASSIC), null, cape);

        assertEquals(2, revised.skin().minecraftDelivery().intentRevision());
        assertEquals(ProviderDelivery.Status.PENDING,
                revised.skin().minecraftDelivery().status());
        assertEquals(selected.cape().minecraftDelivery(), revised.cape().minecraftDelivery());
    }

    @Test
    void reviseTreatsSkinToAccountDefaultAsAnIndependentIdentityChange() {
        ProviderSkin skin = new ProviderSkin("a".repeat(64), SkinVariant.CLASSIC);
        ProviderCape cape = new ProviderCape("owned-a", null, null);
        AppearanceProviders selected = AppearanceProviders.initial().select(1, skin, null, cape);
        selected = new AppearanceProviders(
                selected.skin().settle(
                        selected.skin().minecraftDelivery(), ProviderDelivery.Status.CONFIRMED, skin),
                selected.cape().settle(
                        selected.cape().minecraftDelivery(), ProviderDelivery.Status.CONFIRMED, cape));

        AppearanceProviders revised = selected.revise(2, null, null, cape);

        assertNull(revised.skin().desired());
        assertEquals(2, revised.skin().minecraftDelivery().intentRevision());
        assertEquals(ProviderDelivery.Status.PENDING,
                revised.skin().minecraftDelivery().status());
        assertEquals(selected.cape().minecraftDelivery(), revised.cape().minecraftDelivery());
    }

    @Test
    void reviseTreatsCapeRemovalAsIndependentFromConfirmedSkin() {
        ProviderSkin skin = new ProviderSkin("a".repeat(64), SkinVariant.CLASSIC);
        ProviderCape cape = new ProviderCape("owned-a", null, null);
        AppearanceProviders selected = AppearanceProviders.initial().select(1, skin, null, cape);
        selected = new AppearanceProviders(
                selected.skin().settle(
                        selected.skin().minecraftDelivery(), ProviderDelivery.Status.CONFIRMED, skin),
                selected.cape().settle(
                        selected.cape().minecraftDelivery(), ProviderDelivery.Status.CONFIRMED, cape));

        AppearanceProviders revised = selected.revise(2, skin, null, null);

        assertEquals(selected.skin().minecraftDelivery(), revised.skin().minecraftDelivery());
        assertNull(revised.cape().desired());
        assertEquals(2, revised.cape().minecraftDelivery().intentRevision());
        assertEquals(ProviderDelivery.Status.PENDING,
                revised.cape().minecraftDelivery().status());
    }

    @Test
    void reviseAssignsTheFirstNullIntentForEnabledMinecraft() {
        AppearanceProviders revised = AppearanceProviders.initial().revise(1, null, null, null);

        assertEquals(ProviderDelivery.Status.PENDING,
                revised.skin().minecraftDelivery().status());
        assertEquals(ProviderDelivery.Status.PENDING,
                revised.cape().minecraftDelivery().status());
        assertEquals(1, revised.skin().minecraftDelivery().intentRevision());
        assertEquals(1, revised.cape().minecraftDelivery().intentRevision());
    }

    @Test
    void reviseKeepsDeliveryStatusRulesAndIndependentDisabledDestinations() {
        ProviderSkin skin = new ProviderSkin("a".repeat(64), SkinVariant.CLASSIC);
        ProviderCape local = new ProviderCape("local-a", "b".repeat(64), true);
        ProviderCape owned = new ProviderCape("owned-a", "c".repeat(64), false);
        for (ProviderDelivery.Status status : List.of(
                ProviderDelivery.Status.IDLE,
                ProviderDelivery.Status.PENDING,
                ProviderDelivery.Status.CONFIRMED,
                ProviderDelivery.Status.UNKNOWN,
                ProviderDelivery.Status.ATTEMPTING)) {
            AppearanceProviders current = new AppearanceProviders(
                    new ProviderChannel<>(
                            List.of(BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFT),
                            ProviderObservation.observed(skin),
                            ProviderObservation.observed(skin),
                            7,
                            3,
                            skin,
                            new ProviderDelivery(3, 7, status),
                            skin),
                    new ProviderChannel<>(
                            List.of(BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFT),
                            ProviderObservation.observed(local),
                            ProviderObservation.observed(owned),
                            7,
                            3,
                            owned,
                            new ProviderDelivery(3, 7, status),
                            local));
            AppearanceProviders revised = current.revise(
                    4,
                    skin,
                    new ProviderCape("local-b", null, null),
                    new ProviderCape("owned-b", null, null));

            assertEquals(skin, revised.skin().offline().value());
            assertEquals(ProviderDelivery.Status.CONFIRMED == status
                            || ProviderDelivery.Status.IDLE == status
                            || ProviderDelivery.Status.PENDING == status
                            ? ProviderDelivery.Status.PENDING
                            : ProviderDelivery.Status.UNKNOWN,
                    revised.cape().minecraftDelivery().status());
            assertEquals(7, revised.cape().minecraftDelivery().activation());
            assertEquals("local-b", revised.cape().offline().value().id());
            assertEquals("owned-b", revised.cape().desired().id());
        }

        AppearanceProviders base = AppearanceProviders.initial().select(3, skin, local, owned);
        AppearanceProviders changedSkin = base.revise(
                4,
                new ProviderSkin("d".repeat(64), SkinVariant.SLIM),
                local,
                owned);
        assertEquals(4, changedSkin.skin().minecraftDelivery().intentRevision());
        assertEquals(ProviderDelivery.Status.PENDING,
                changedSkin.skin().minecraftDelivery().status());
        assertEquals(base.cape().minecraftDelivery(), changedSkin.cape().minecraftDelivery());

        ProviderChannel<ProviderCape> disabledOffline = new ProviderChannel<>(
                List.of(BuiltinProvider.MINECRAFT),
                ProviderObservation.observed(local),
                ProviderObservation.observed(owned),
                7,
                3,
                owned,
                new ProviderDelivery(3, 7, ProviderDelivery.Status.CONFIRMED),
                local);
        AppearanceProviders disabled = new AppearanceProviders(
                base.skin(), disabledOffline).revise(
                        4, skin, new ProviderCape("local-b", null, null),
                        new ProviderCape("owned-b", null, null));
        assertEquals("local-a", disabled.cape().offline().value().id());
        assertEquals("local-b", disabled.cape().offlineDesired().id());
        assertEquals(ProviderDelivery.Status.PENDING,
                disabled.cape().minecraftDelivery().status());

        ProviderChannel<ProviderCape> disabledMinecraft = disabled.cape().disable(BuiltinProvider.MINECRAFT);
        AppearanceProviders latestDisabled = new AppearanceProviders(disabled.skin(), disabledMinecraft)
                .revise(5, skin, new ProviderCape("local-c", null, null), new ProviderCape("owned-c", null, null));
        assertEquals(ProviderDelivery.Status.PENDING,
                latestDisabled.cape().minecraftDelivery().status());
        assertEquals("owned-c", latestDisabled.cape().desired().id());
        AppearanceProviders reenabled = new AppearanceProviders(
                latestDisabled.skin(), latestDisabled.cape().enable(BuiltinProvider.MINECRAFT));
        assertEquals(5, reenabled.cape().minecraftDelivery().intentRevision());
        assertEquals(ProviderDelivery.Status.PENDING,
                reenabled.cape().minecraftDelivery().status());
    }
}

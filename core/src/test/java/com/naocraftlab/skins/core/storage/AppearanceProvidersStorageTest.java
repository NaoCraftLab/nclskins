package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.core.model.AccountAppearanceState;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.ProviderCape;
import com.naocraftlab.skins.core.provider.ProviderChannel;
import com.naocraftlab.skins.core.provider.ProviderDelivery;
import com.naocraftlab.skins.core.provider.ProviderSkin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static com.naocraftlab.skins.core.provider.BuiltinProvider.MINECRAFT;
import static com.naocraftlab.skins.core.provider.BuiltinProvider.OFFLINE;
import static com.naocraftlab.skins.core.provider.BuiltinProvider.OPTIFINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppearanceProvidersStorageTest {
    @TempDir Path directory;

    @Test
    void publishedSchemasPreserveAcknowledgementAndUncertaintyDuringMigration() throws Exception {
        AccountAppearanceStateJson json = new AccountAppearanceStateJson();
        for (int schema : new int[] {1, 2}) {
            for (AppearanceSyncStatus status : AppearanceSyncStatus.values()) {
                AccountAppearanceState original = new AccountAppearanceState(
                        AccountAppearanceState.CURRENT_SCHEMA_VERSION, UUID.randomUUID(), 1, UUID.randomUUID(),
                        "a".repeat(64), SkinVariant.SLIM, "cape-a", status,
                        status == AppearanceSyncStatus.OFFICIAL ? 1 : 0, Instant.EPOCH);
                var document = com.google.gson.JsonParser.parseString(new String(json.encode(original), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                document.remove("providers");
                document.addProperty("schemaVersion", schema);
                if (schema == 1) {
                    document.remove("outerLayerVisibility");
                }
                AccountAppearanceStateJson.Decoded migrated = json.decode(
                        document.toString().getBytes(StandardCharsets.UTF_8));
                assertTrue(migrated.migrated());
                AppearanceProviders providers = migrated.state().providers();
                assertEquals(original.skinSha256(), providers.skin().offline().value().sha256());
                assertEquals(status == AppearanceSyncStatus.OFFICIAL || status == AppearanceSyncStatus.PARTIAL,
                        providers.skin().minecraft().known());
                assertEquals(status == AppearanceSyncStatus.OFFICIAL, providers.cape().minecraft().known());
                if (status == AppearanceSyncStatus.UNKNOWN || status == AppearanceSyncStatus.ATTEMPTING) {
                    assertEquals(ProviderDelivery.Status.UNKNOWN, providers.skin().minecraftDelivery().status());
                    assertEquals(ProviderDelivery.Status.UNKNOWN, providers.cape().minecraftDelivery().status());
                }
            }
        }
    }

    @Test
    void configurationAndIndependentStatesSurviveAnotherInstance() throws Exception {
        UUID account = UUID.randomUUID();
        NclSkinsStorage first = storage();
        first.loadOrCreateAccount(account);
        ProviderSkin skin = new ProviderSkin("a".repeat(64), SkinVariant.SLIM);
        ProviderCape cape = new ProviderCape("cape-a", "b".repeat(64));
        AppearanceProviders providers = new AppearanceProviders(
                ProviderChannel.<ProviderSkin>initial().observeMinecraft(skin).disable(OFFLINE),
                ProviderChannel.<ProviderCape>initial().observeMinecraft(cape).disable(MINECRAFT));
        AccountAppearanceState written = first.updateAppearance(account,
                state -> state.withProviders(providers));
        assertEquals(written, storage().loadAppearance(account));
        assertEquals(cape, storage().loadAppearance(account).providers().cape().offline().value());
        assertEquals(AppearanceProviders.initial(), storage().loadAppearance(UUID.randomUUID()).providers());
    }

    @Test
    void roundTripPreservesNewLocalRevisionWithOlderDeliveryRevision() throws Exception {
        UUID account = UUID.randomUUID();
        NclSkinsStorage first = storage();
        first.loadOrCreateAccount(account);
        ProviderSkin skin = new ProviderSkin("a".repeat(64), SkinVariant.SLIM);
        ProviderCape cape = new ProviderCape("cape-a", "b".repeat(64), true);
        AppearanceProviders providers = new AppearanceProviders(
                new ProviderChannel<>(
                        java.util.List.of(OFFLINE, MINECRAFT),
                        com.naocraftlab.skins.core.provider.ProviderObservation.observed(skin),
                        com.naocraftlab.skins.core.provider.ProviderObservation.observed(skin),
                        7,
                        8,
                        skin,
                        new ProviderDelivery(3, 7, ProviderDelivery.Status.UNKNOWN),
                        skin),
                new ProviderChannel<>(
                        java.util.List.of(OFFLINE, MINECRAFT),
                        com.naocraftlab.skins.core.provider.ProviderObservation.observed(cape),
                        com.naocraftlab.skins.core.provider.ProviderObservation.observed(cape),
                        9,
                        8,
                        cape,
                        new ProviderDelivery(5, 9, ProviderDelivery.Status.CONFIRMED),
                        cape));
        AccountAppearanceState original = new AccountAppearanceState(
                AccountAppearanceState.CURRENT_SCHEMA_VERSION,
                account,
                8,
                UUID.randomUUID(),
                skin.sha256(),
                skin.variant(),
                cape.id(),
                com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(),
                AppearanceSyncStatus.UNKNOWN,
                4,
                Instant.EPOCH,
                providers);

        first.updateAppearance(account, ignored -> original);

        NclSkinsStorage second = storage();
        assertEquals(original, second.loadAppearance(account));
        assertEquals(8, second.loadAppearance(account).providers().skin().intentRevision());
        assertEquals(3, second.loadAppearance(account).providers().skin().minecraftDelivery().intentRevision());
        assertEquals(ProviderDelivery.Status.UNKNOWN,
                second.loadAppearance(account).providers().skin().minecraftDelivery().status());
    }

    @Test
    void invalidProviderDocumentIsNotReplacedWithDefaults() throws Exception {
        UUID account = UUID.randomUUID();
        NclSkinsStorage storage = storage();
        storage.loadOrCreateAccount(account);
        storage.updateAppearance(account, state -> state);
        Path path = storage.layout().accountAppearance(account);
        String valid = Files.readString(path);
        String invalid = valid.replace("\"OFFLINE\",", "\"MINECRAFT\",");
        Files.writeString(path, invalid);
        assertThrows(StorageException.class, () -> storage.loadAppearance(account));
        assertEquals(invalid, Files.readString(path));
    }

    @Test
    void newSchemaRequiresProviderState() throws Exception {
        AccountAppearanceState state = AccountAppearanceState.empty(UUID.randomUUID(), Instant.EPOCH);
        AccountAppearanceStateJson json = new AccountAppearanceStateJson();
        var document = com.google.gson.JsonParser.parseString(new String(json.encode(state), StandardCharsets.UTF_8))
                .getAsJsonObject();
        document.remove("providers");
        assertThrows(StorageException.class,
                () -> json.decode(document.toString().getBytes(StandardCharsets.UTF_8)));
        document.addProperty("schemaVersion", 2);
        assertEquals(state.providers(), json.decode(document.toString().getBytes(StandardCharsets.UTF_8))
                .state().providers());
    }

    @Test
    void optifineObservationRoundTripsAndOldDocumentDefaultsToUnknown() throws Exception {
        UUID account = UUID.randomUUID();
        NclSkinsStorage storage = storage();
        storage.loadOrCreateAccount(account);
        ProviderCape cape = new ProviderCape("optifine", "a".repeat(64), false);
        storage.updateAppearance(account, state -> state.withProviders(state.providers()
                .enable(AppearanceProviders.Component.CAPE, OPTIFINE)
                .withCapeTexture(cape.id(), cape.textureCacheKey(), cape.hasElytra())));
        storage.updateAppearance(account, state -> state.withProviders(new AppearanceProviders(
                state.providers().skin(), state.providers().cape().observeOptifine(cape))));
        assertEquals(cape, storage().loadAppearance(account).providers().cape().optifine().value());

        Path path = storage.layout().accountAppearance(account);
        var document = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        document.getAsJsonObject("providers").getAsJsonObject("cape").remove("optifine");
        Files.writeString(path, document.toString());
        var loaded = storage().loadAppearance(account).providers();
        assertTrue(!loaded.cape().optifine().known());
        assertEquals(java.util.List.of(OFFLINE, MINECRAFT, OPTIFINE), loaded.cape().order());
    }

    private NclSkinsStorage storage() {
        return new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
    }
}

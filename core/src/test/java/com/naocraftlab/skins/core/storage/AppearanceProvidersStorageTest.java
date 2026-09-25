package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.core.model.AccountAppearanceState;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.SkinReference;
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
import static com.naocraftlab.skins.core.provider.BuiltinProvider.SKINMC;
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

    @Test
    void skinmcIsCapeOnlyDisabledByDefaultAndOldDocumentLoadsUnknownObservation() throws Exception {
        UUID account = UUID.randomUUID();
        NclSkinsStorage storage = storage();
        storage.loadOrCreateAccount(account);
        storage.updateAppearance(account, state -> state);
        Path path = storage.layout().accountAppearance(account);
        var old = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        old.getAsJsonObject("providers").getAsJsonObject("cape").remove("skinmc");
        Files.writeString(path, old.toString());

        var loaded = storage.loadAppearance(account).providers();
        assertTrue(!loaded.skin().enabled(SKINMC));
        assertTrue(!loaded.cape().enabled(SKINMC));
        assertTrue(!loaded.cape().skinmc().known());

        ProviderCape cape = new ProviderCape("skinmc", "a".repeat(64), true);
        storage.updateAppearance(account, state -> state.withProviders(state.providers()
                .enable(AppearanceProviders.Component.CAPE, SKINMC)));
        storage.updateAppearance(account, state -> state.withProviders(new AppearanceProviders(
                state.providers().skin(), state.providers().cape().observeSkinmc(cape))));
        assertEquals(cape, storage().loadAppearance(account).providers().cape().skinmc().value());
        assertTrue(!storage().loadAppearance(account).providers().skin().enabled(SKINMC));
        assertThrows(IllegalArgumentException.class,
                () -> storage().loadAppearance(account).providers()
                        .enable(AppearanceProviders.Component.SKIN, SKINMC));
    }

    @Test
    void futureProviderOrderAndOpaqueFieldsSurviveAllAppearanceWrites() throws Exception {
        UUID account = UUID.randomUUID();
        UUID preset = UUID.randomUUID();
        NclSkinsStorage storage = storage();
        storage.updateAccount(account, current -> new AccountState(current.schemaVersion(),
                account, current.skinAssets(), current.personalSkins(),
                java.util.List.of(new AppearancePreset(preset, "Preset", SkinReference.accountDefault(),
                        null, Instant.EPOCH, Instant.EPOCH)), current.updatedAt(), current.personalCapes()));
        storage.updateAppearance(account, state -> advanced(state, 1, preset));
        Path path = storage.layout().accountAppearance(account);
        writeFutureFixture(path);

        assertEquals(java.util.List.of(OFFLINE, MINECRAFT),
                storage.loadAppearance(account).providers().skin().order());
        assertEquals(java.util.List.of(OFFLINE, MINECRAFT),
                storage.loadAppearance(account).providers().cape().order());

        storage.updateAppearance(account, state -> state.withProviders(state.providers()
                .move(AppearanceProviders.Component.CAPE, MINECRAFT, -1)));
        assertFuturePreserved(path, java.util.List.of("MINECRAFT", "FUTURE_CAPE", "OFFLINE"));
        storage.updateAppearance(account, state -> state.withProviders(state.providers()
                .disable(AppearanceProviders.Component.CAPE, MINECRAFT)));
        assertFuturePreserved(path, java.util.List.of("OFFLINE", "FUTURE_CAPE"));

        storage.updateAppearanceIntent(account, (state, revision) -> advanced(state, revision, preset));
        assertFuturePreserved(path, java.util.List.of("OFFLINE", "FUTURE_CAPE"));
        var current = storage.loadAppearance(account);
        storage.updateAppearanceIntentIfCurrent(account, current.intentRevision(), current.syncStatus(),
                (state, revision) -> advanced(state, revision, preset));
        assertFuturePreserved(path, java.util.List.of("OFFLINE", "FUTURE_CAPE"));
        current = storage.loadAppearance(account);
        storage.updateAppearanceIntentIfRevisionAndPreset(account, current.intentRevision(), preset,
                (state, revision) -> advanced(state, revision, preset));
        assertFuturePreserved(path, java.util.List.of("OFFLINE", "FUTURE_CAPE"));
        storage.updateAppearanceIntentIfPresetActive(account, preset,
                (ignored, state, revision) -> advanced(state, revision, preset));
        assertFuturePreserved(path, java.util.List.of("OFFLINE", "FUTURE_CAPE"));
        storage.mutateAccountAndAppearance(account, (known, state, revision) ->
                NclSkinsStorage.AccountAppearanceMutationPlan.appearanceOnly(
                        known, advanced(state, revision, preset)));
        assertFuturePreserved(path, java.util.List.of("OFFLINE", "FUTURE_CAPE"));
        assertEquals(3, storage().loadAppearance(account).schemaVersion());
    }

    @Test
    void futureProviderDoesNotHideMalformedKnownDataOrUnboundedOrder() throws Exception {
        UUID account = UUID.randomUUID();
        NclSkinsStorage storage = storage();
        storage.updateAppearance(account, state -> state);
        Path path = storage.layout().accountAppearance(account);
        writeFutureFixture(path);
        var valid = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        var cape = valid.getAsJsonObject("providers").getAsJsonObject("cape");

        cape.getAsJsonObject("minecraft").remove("known");
        Files.writeString(path, valid.toString());
        assertThrows(StorageException.class, () -> storage.loadAppearance(account));
        cape.getAsJsonObject("minecraft").addProperty("known", false);
        cape.getAsJsonArray("order").add("FUTURE_CAPE");
        Files.writeString(path, valid.toString());
        assertThrows(StorageException.class, () -> storage.loadAppearance(account));
        cape.getAsJsonArray("order").remove(cape.getAsJsonArray("order").size() - 1);
        for (int index = 0; index < 32; index++) cape.getAsJsonArray("order").add("FUTURE_" + index);
        Files.writeString(path, valid.toString());
        assertThrows(StorageException.class, () -> storage.loadAppearance(account));
        cape.getAsJsonArray("order").remove(cape.getAsJsonArray("order").size() - 1);
        valid.addProperty("schemaVersion", 99);
        Files.writeString(path, valid.toString());
        assertEquals(StorageException.Code.UNSUPPORTED_SCHEMA,
                assertThrows(StorageException.class, () -> storage.loadAppearance(account)).code());
    }

    @Test
    void transactionRecoveryRetainsFutureProviderDocument() throws Exception {
        UUID account = UUID.randomUUID();
        NclSkinsStorage storage = storage();
        storage.updateAccount(account, current -> current);
        storage.updateAppearance(account, current -> current);
        Path appearancePath = storage.layout().accountAppearance(account);
        writeFutureFixture(appearancePath);
        var marker = new com.google.gson.JsonObject();
        marker.add("account", com.google.gson.JsonParser.parseString(
                Files.readString(storage.layout().accountState(account))));
        marker.add("appearance", com.google.gson.JsonParser.parseString(Files.readString(appearancePath)));
        Path journal = storage.layout().accountState(account).getParent().resolve("cape-transaction.json");
        Files.writeString(journal, marker.toString());

        storage.loadAppearance(account);

        assertTrue(!Files.exists(journal));
        assertFuturePreserved(appearancePath,
                java.util.List.of("OFFLINE", "FUTURE_CAPE", "MINECRAFT"));
    }

    @Test
    void recoverableCapeDeletionKeepsFutureProviderDataAndSkinmcObservation() throws Exception {
        UUID account = UUID.randomUUID();
        NclSkinsStorage storage = storage();
        var image = new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, 0xff123456);
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        var entry = storage.importCape(account, "Cape", output.toByteArray());
        var offline = new ProviderCape(entry.texture().entryId().toString(),
                entry.texture().sha256(), false);
        var skinmc = new ProviderCape("skinmc", "b".repeat(64), true);
        storage.updateAppearance(account, state -> advanced(state, 1, UUID.randomUUID())
                .withProviders(state.providers().select(1, null, offline, null)
                        .enable(AppearanceProviders.Component.CAPE, SKINMC)));
        storage.updateAppearance(account, state -> state.withProviders(new AppearanceProviders(
                state.providers().skin(), state.providers().cape().observeSkinmc(skinmc))));
        Path path = storage.layout().accountAppearance(account);
        writeFutureFixture(path);

        storage.deleteCape(account, entry.texture().entryId());

        assertFuturePreserved(path, java.util.List.of("OFFLINE", "FUTURE_CAPE", "MINECRAFT", "SKINMC"));
        assertEquals(skinmc, storage.loadAppearance(account).providers().cape().skinmc().value());
    }

    private static AccountAppearanceState advanced(AccountAppearanceState state, long revision, UUID preset) {
        return new AccountAppearanceState(state.schemaVersion(), state.accountId(), revision, preset,
                state.skinSha256(), state.skinVariant(), state.capeId(),
                com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(),
                AppearanceSyncStatus.LOCAL_ONLY, 0, state.updatedAt().plusNanos(1), state.providers());
    }

    private static void writeFutureFixture(Path path) throws Exception {
        var root = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        var providers = root.getAsJsonObject("providers");
        var skin = providers.getAsJsonObject("skin");
        var cape = providers.getAsJsonObject("cape");
        var skinOrder = new com.google.gson.JsonArray();
        for (String id : java.util.List.of("OFFLINE", "FUTURE_SKIN", "MINECRAFT")) skinOrder.add(id);
        skin.add("order", skinOrder);
        var capeOrder = new com.google.gson.JsonArray();
        for (String id : java.util.List.of("OFFLINE", "FUTURE_CAPE", "MINECRAFT")) capeOrder.add(id);
        if (strings(cape.getAsJsonArray("order")).contains("SKINMC")) capeOrder.add("SKINMC");
        cape.add("order", capeOrder);
        var skinData = new com.google.gson.JsonObject();
        skinData.addProperty("enabled", true);
        skinData.addProperty("opaque", "future-skin-state");
        skin.add("FUTURE_SKIN", skinData);
        var capeData = new com.google.gson.JsonObject();
        capeData.addProperty("enabled", true);
        capeData.addProperty("opaque", "future-cape-state");
        cape.add("FUTURE_CAPE", capeData);
        var disabled = new com.google.gson.JsonObject();
        disabled.addProperty("enabled", false);
        disabled.addProperty("opaque", "disabled-future-state");
        cape.add("FUTURE_DISABLED", disabled);
        Files.writeString(path, root.toString());
    }

    private static void assertFuturePreserved(Path path, java.util.List<String> capeOrder) throws Exception {
        var providers = com.google.gson.JsonParser.parseString(Files.readString(path))
                .getAsJsonObject().getAsJsonObject("providers");
        var skin = providers.getAsJsonObject("skin");
        var cape = providers.getAsJsonObject("cape");
        assertEquals(java.util.List.of("OFFLINE", "FUTURE_SKIN", "MINECRAFT"),
                strings(skin.getAsJsonArray("order")));
        assertEquals(capeOrder, strings(cape.getAsJsonArray("order")));
        assertEquals("future-skin-state", skin.getAsJsonObject("FUTURE_SKIN").get("opaque").getAsString());
        assertTrue(skin.getAsJsonObject("FUTURE_SKIN").get("enabled").getAsBoolean());
        assertEquals("future-cape-state", cape.getAsJsonObject("FUTURE_CAPE").get("opaque").getAsString());
        assertTrue(cape.getAsJsonObject("FUTURE_CAPE").get("enabled").getAsBoolean());
        assertEquals("disabled-future-state",
                cape.getAsJsonObject("FUTURE_DISABLED").get("opaque").getAsString());
        assertTrue(!cape.getAsJsonObject("FUTURE_DISABLED").get("enabled").getAsBoolean());
    }

    private static java.util.List<String> strings(com.google.gson.JsonArray values) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        values.forEach(value -> ids.add(value.getAsString()));
        return ids;
    }

    private NclSkinsStorage storage() {
        return new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
    }
}

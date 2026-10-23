package com.naocraftlab.skins.core.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class OptionalProviderMetadataTest {
    @Test
    void firstActivationPreservesMaximumLegacyOrderAndRemainsDecodable() {
        JsonObject root = AppearanceProvidersJson.encode(AppearanceProviders.initial());
        var order = new com.google.gson.JsonArray();
        order.add("OFFLINE");
        order.add("MINECRAFT");
        for (int index = 0; index < 30; index++) order.add("FUTURE_" + index);
        root.getAsJsonObject("cape").add("order", order);
        var initial = AppearanceProvidersJson.decode(root);
        var activated = new AppearanceProviders(initial.skin(), initial.cape().initializeCpm());
        var saved = AppearanceProvidersJson.mergeUnknown(root, activated);
        org.junit.jupiter.api.Assertions.assertEquals(activated, AppearanceProvidersJson.decode(saved));
        org.junit.jupiter.api.Assertions.assertEquals(33, activated.cape().serializedOrder().size());
        org.junit.jupiter.api.Assertions.assertEquals(initial.cape().serializedOrder(),
                activated.cape().serializedOrder().subList(1, 33));
    }

    @Test
    void explicitAddOverridesDisabledSlotAndPreservesUnknownEntries() {
        for (String order : new String[] {
                "[\"OFFLINE\",\"FUTURE\",\"CPM\",\"MINECRAFT\"]",
                "[\"OFFLINE\",\"CPM\",\"FUTURE\",\"MINECRAFT\"]"
        }) {
            JsonObject root = AppearanceProvidersJson.encode(AppearanceProviders.initial());
            root.getAsJsonObject("cape").add("order", JsonParser.parseString(order));
            var decoded = AppearanceProvidersJson.decode(root);
            int slot = root.getAsJsonObject("cape").getAsJsonArray("order").asList()
                    .indexOf(new com.google.gson.JsonPrimitive("CPM"));
            var disabled = decoded.disable(AppearanceProviders.Component.CAPE,
                    com.naocraftlab.skins.core.provider.BuiltinProvider.CPM);
            JsonObject saved = AppearanceProvidersJson.mergeUnknown(root, disabled);
            org.junit.jupiter.api.Assertions.assertEquals(slot, saved.getAsJsonObject("cape")
                    .getAsJsonObject("optionalProviders").getAsJsonObject("CPM").get("disabledSlot").getAsInt());
            var enabled = AppearanceProvidersJson.decode(saved).enable(AppearanceProviders.Component.CAPE,
                    com.naocraftlab.skins.core.provider.BuiltinProvider.CPM);
            JsonObject restored = AppearanceProvidersJson.mergeUnknown(saved, enabled);
            org.junit.jupiter.api.Assertions.assertEquals(JsonParser.parseString(
                    "[\"OFFLINE\",\"FUTURE\",\"MINECRAFT\",\"CPM\"]"),
                    restored.getAsJsonObject("cape").getAsJsonArray("order"));
            org.junit.jupiter.api.Assertions.assertFalse(restored.getAsJsonObject("cape")
                    .getAsJsonObject("optionalProviders").getAsJsonObject("CPM").has("disabledSlot"));
        }
    }

    @Test
    void initializationAndDisabledSlotRoundTripWithNestedUnknownMetadata() {
        var initial = AppearanceProviders.initial();
        var initialized = new AppearanceProviders(initial.skin(), initial.cape().initializeCpm());
        JsonObject original = AppearanceProvidersJson.encode(initialized);
        JsonObject metadata = original.getAsJsonObject("cape").getAsJsonObject("optionalProviders");
        metadata.add("FUTURE", JsonParser.parseString("{\"opaque\":[1,2]}"));
        metadata.getAsJsonObject("CPM").add("future", JsonParser.parseString("{\"nested\":true}"));
        var decoded = AppearanceProvidersJson.decode(original);
        org.junit.jupiter.api.Assertions.assertEquals(initialized, decoded);
        var disabled = decoded.disable(AppearanceProviders.Component.CAPE,
                com.naocraftlab.skins.core.provider.BuiltinProvider.CPM);
        JsonObject saved = AppearanceProvidersJson.mergeUnknown(original, disabled);
        org.junit.jupiter.api.Assertions.assertEquals(disabled, AppearanceProvidersJson.decode(saved));
        JsonObject savedMetadata = saved.getAsJsonObject("cape").getAsJsonObject("optionalProviders");
        org.junit.jupiter.api.Assertions.assertEquals(metadata.get("FUTURE"), savedMetadata.get("FUTURE"));
        org.junit.jupiter.api.Assertions.assertEquals(metadata.getAsJsonObject("CPM").get("future"),
                savedMetadata.getAsJsonObject("CPM").get("future"));
        var enabled = disabled.enable(AppearanceProviders.Component.CAPE,
                com.naocraftlab.skins.core.provider.BuiltinProvider.CPM);
        JsonObject reenabled = AppearanceProvidersJson.mergeUnknown(saved, enabled);
        org.junit.jupiter.api.Assertions.assertFalse(reenabled.getAsJsonObject("cape")
                .getAsJsonObject("optionalProviders").getAsJsonObject("CPM").has("disabledSlot"));
        org.junit.jupiter.api.Assertions.assertEquals(enabled, AppearanceProvidersJson.decode(reenabled));
    }

    @Test
    void malformedKnownInitializationIsRejectedWithoutInterpretingUnknownProviders() {
        for (String metadata : new String[] {
                "null", "[]", "true", "{\"initialized\":false}",
                "{\"initialized\":\"true\"}", "{}",
                "{\"initialized\":true,\"disabledSlot\":-1}",
                "{\"initialized\":true,\"disabledSlot\":1.5}",
                "{\"initialized\":true,\"disabledSlot\":\"1\"}",
                "{\"initialized\":true,\"disabledSlot\":2147483648}"
        }) {
            JsonObject root = AppearanceProvidersJson.encode(AppearanceProviders.initial());
            JsonObject optional = new JsonObject();
            optional.add("CPM", JsonParser.parseString(metadata));
            root.getAsJsonObject("cape").add("optionalProviders", optional);
            assertThrows(RuntimeException.class, () -> AppearanceProvidersJson.decode(root), metadata);
        }
    }
}

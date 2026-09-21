package com.naocraftlab.skins.core.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderCape;
import com.naocraftlab.skins.core.provider.ProviderChannel;
import com.naocraftlab.skins.core.provider.ProviderDelivery;
import com.naocraftlab.skins.core.provider.ProviderObservation;
import com.naocraftlab.skins.core.provider.ProviderSkin;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class AppearanceProvidersJson {
    static JsonObject encode(AppearanceProviders state) {
        JsonObject result = new JsonObject();
        result.add("skin", encodeChannel(state.skin(), AppearanceProvidersJson::encodeSkin));
        result.add("cape", encodeChannel(state.cape(), AppearanceProvidersJson::encodeCape));
        return result;
    }

    static AppearanceProviders decode(JsonObject root) {
        return new AppearanceProviders(
                decodeChannel(object(root, "skin"), AppearanceProvidersJson::decodeSkin),
                decodeChannel(object(root, "cape"), AppearanceProvidersJson::decodeCape));
    }

    private static <T> JsonObject encodeChannel(ProviderChannel<T> channel, Function<T, JsonObject> encode) {
        JsonObject result = new JsonObject();
        JsonArray order = new JsonArray();
        channel.order().forEach(provider -> order.add(provider.name()));
        result.add("order", order);
        result.addProperty("configurationRevision", channel.configurationRevision());
        result.addProperty("intentRevision", channel.intentRevision());
        result.add("offline", encodeObservation(channel.offline(), encode));
        result.add("minecraft", encodeObservation(channel.minecraft(), encode));
        if (channel.desired() != null) {
            result.add("desired", encode.apply(channel.desired()));
        }
        result.add("offlineDesired", channel.offlineDesired() == null ? com.google.gson.JsonNull.INSTANCE : encode.apply(channel.offlineDesired()));
        JsonObject delivery = new JsonObject();
        delivery.addProperty("intentRevision", channel.minecraftDelivery().intentRevision());
        delivery.addProperty("activation", channel.minecraftDelivery().activation());
        delivery.addProperty("status", channel.minecraftDelivery().status().name());
        result.add("delivery", delivery);
        return result;
    }

    private static <T> ProviderChannel<T> decodeChannel(JsonObject root, Function<JsonObject, T> decode) {
        JsonElement elements = root.get("order");
        if (elements == null || !elements.isJsonArray() || elements.getAsJsonArray().size() > 2) {
            throw new JsonParseException("Invalid provider order");
        }
        List<BuiltinProvider> order = new ArrayList<>();
        for (JsonElement element : elements.getAsJsonArray()) {
            order.add(BuiltinProvider.valueOf(element.getAsString()));
        }
        JsonObject delivery = object(root, "delivery");
        return new ProviderChannel<>(order,
                decodeObservation(object(root, "offline"), decode),
                decodeObservation(object(root, "minecraft"), decode),
                number(root, "configurationRevision"), number(root, "intentRevision"),
                root.has("desired") ? decode.apply(object(root, "desired")) : null,
                new ProviderDelivery(number(delivery, "intentRevision"), number(delivery, "activation"),
                        ProviderDelivery.Status.valueOf(string(delivery, "status"))),
                root.has("offlineDesired")
                        ? root.get("offlineDesired").isJsonNull() ? null : decode.apply(object(root, "offlineDesired"))
                        : root.has("desired") ? decode.apply(object(root, "desired")) : null);
    }

    private static <T> JsonObject encodeObservation(ProviderObservation<T> observation, Function<T, JsonObject> encode) {
        JsonObject result = new JsonObject();
        result.addProperty("known", observation.known());
        if (observation.value() != null) {
            result.add("value", encode.apply(observation.value()));
        }
        return result;
    }

    private static <T> ProviderObservation<T> decodeObservation(JsonObject root, Function<JsonObject, T> decode) {
        JsonElement known = root.get("known");
        if (known == null || !known.isJsonPrimitive() || !known.getAsJsonPrimitive().isBoolean()) {
            throw new JsonParseException("Invalid provider observation");
        }
        return new ProviderObservation<>(known.getAsBoolean(),
                root.has("value") ? decode.apply(object(root, "value")) : null);
    }

    private static JsonObject encodeSkin(ProviderSkin value) {
        JsonObject result = new JsonObject();
        result.addProperty("sha256", value.sha256());
        result.addProperty("variant", value.variant().name());
        return result;
    }

    private static ProviderSkin decodeSkin(JsonObject root) {
        return new ProviderSkin(string(root, "sha256"), SkinVariant.valueOf(string(root, "variant")));
    }

    private static JsonObject encodeCape(ProviderCape value) {
        JsonObject result = new JsonObject();
        result.addProperty("id", value.id());
        if (value.hasElytra() != null) result.addProperty("hasElytra", value.hasElytra());
        value.optionalTextureCacheKey().ifPresent(key -> result.addProperty("textureCacheKey", key));
        return result;
    }

    private static ProviderCape decodeCape(JsonObject root) {
        return new ProviderCape(string(root, "id"),
                root.has("textureCacheKey") ? string(root, "textureCacheKey") : null,
                root.has("hasElytra") ? root.get("hasElytra").getAsBoolean() : null);
    }

    private static JsonObject object(JsonObject root, String key) {
        JsonElement value = root.get(key);
        if (value == null || !value.isJsonObject()) {
            throw new JsonParseException("Missing provider object");
        }
        return value.getAsJsonObject();
    }

    private static String string(JsonObject root, String key) {
        JsonElement value = root.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("Missing provider string");
        }
        return value.getAsString();
    }

    private static long number(JsonObject root, String key) {
        JsonElement value = root.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("Missing provider revision");
        }
        return value.getAsBigDecimal().longValueExact();
    }

    private AppearanceProvidersJson() {}
}

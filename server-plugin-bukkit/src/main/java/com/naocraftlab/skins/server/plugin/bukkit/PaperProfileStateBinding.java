package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.server.SignedTexturesProperty;
import com.naocraftlab.skins.server.VerifiedOfficialProfile;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.UUID;


final class PaperProfileStateBinding {
    private static final String TEXTURES = "textures";

    private final Method mutableProperties;
    private final Method mutableRemoveAll;
    private final Method mutablePut;
    private final Method immutableProfileId;
    private final Method immutableProfileName;
    private final Method immutableEmpty;
    private final Method immutableSingleton;
    private final Constructor<?> immutablePropertyMapConstructor;
    private final Constructor<?> immutableProfileConstructor;
    private final Field immutableLiveProfile;
    private final Constructor<?> propertyConstructor;

    private PaperProfileStateBinding(
            Method mutableProperties,
            Method mutableRemoveAll,
            Method mutablePut,
            Method immutableProfileId,
            Method immutableProfileName,
            Method immutableEmpty,
            Method immutableSingleton,
            Constructor<?> immutablePropertyMapConstructor,
            Constructor<?> immutableProfileConstructor,
            Field immutableLiveProfile,
            Constructor<?> propertyConstructor) {
        this.mutableProperties = mutableProperties;
        this.mutableRemoveAll = mutableRemoveAll;
        this.mutablePut = mutablePut;
        this.immutableProfileId = immutableProfileId;
        this.immutableProfileName = immutableProfileName;
        this.immutableEmpty = immutableEmpty;
        this.immutableSingleton = immutableSingleton;
        this.immutablePropertyMapConstructor = immutablePropertyMapConstructor;
        this.immutableProfileConstructor = immutableProfileConstructor;
        this.immutableLiveProfile = immutableLiveProfile;
        this.propertyConstructor = Objects.requireNonNull(
                propertyConstructor, "propertyConstructor");
    }

    static PaperProfileStateBinding resolve(
            ClassLoader classLoader,
            Class<?> serverPlayer,
            String authlibFamily) throws ReflectiveOperationException {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(serverPlayer, "serverPlayer");
        Objects.requireNonNull(authlibFamily, "authlibFamily");
        Class<?> gameProfile = Class.forName(
                "com.mojang.authlib.GameProfile", false, classLoader);
        Class<?> propertyMap = Class.forName(
                "com.mojang.authlib.properties.PropertyMap", false, classLoader);
        Class<?> property = Class.forName(
                "com.mojang.authlib.properties.Property", false, classLoader);
        Constructor<?> propertyConstructor = property.getConstructor(
                String.class, String.class, String.class);
        return switch (authlibFamily) {
            case "authlib-v4", "authlib-v6" -> resolveMutable(
                    gameProfile, propertyMap, propertyConstructor, authlibFamily);
            case "authlib-v7", "authlib-v9" -> {
                Class<?> multimap = Class.forName(
                        "com.google.common.collect.Multimap", false, classLoader);
                Class<?> immutableMultimap = Class.forName(
                        "com.google.common.collect.ImmutableMultimap", false, classLoader);
                Class<?> player = Class.forName(
                        "net.minecraft.world.entity.player.Player", false, classLoader);
                yield resolveImmutable(
                        gameProfile, propertyMap, multimap, immutableMultimap,
                        player, serverPlayer, propertyConstructor);
            }
            default -> throw new NoSuchMethodException(
                    "Unsupported Paper authlib family " + authlibFamily);
        };
    }

    static PaperProfileStateBinding resolveImmutable(
            Class<?> gameProfile,
            Class<?> propertyMap,
            Class<?> multimap,
            Class<?> immutableMultimap,
            Class<?> player,
            Class<?> serverPlayer,
            Constructor<?> propertyConstructor) throws ReflectiveOperationException {
        Method profileId = gameProfile.getMethod("id");
        requireReturnType(profileId, UUID.class);
        Method profileName = gameProfile.getMethod("name");
        requireReturnType(profileName, String.class);
        Method empty = immutableMultimap.getMethod("of");
        requireReturnType(empty, immutableMultimap);
        Method singleton = immutableMultimap.getMethod(
                "of", Object.class, Object.class);
        requireReturnType(singleton, immutableMultimap);
        Constructor<?> propertyMapConstructor = propertyMap.getConstructor(multimap);
        Constructor<?> profileConstructor = gameProfile.getConstructor(
                UUID.class, String.class, propertyMap);
        Field liveProfile = serverPlayer.getField("gameProfile");
        if (liveProfile.getDeclaringClass() != player
                || liveProfile.getType() != gameProfile
                || !Modifier.isPublic(liveProfile.getModifiers())
                || Modifier.isFinal(liveProfile.getModifiers())) {
            throw new NoSuchFieldException(
                    player.getName() + "#gameProfile is not the exact public mutable profile field");
        }
        return new PaperProfileStateBinding(
                null, null, null,
                profileId, profileName, empty, singleton,
                propertyMapConstructor, profileConstructor, liveProfile,
                propertyConstructor);
    }

    void install(
            Object actorHandle,
            Object liveProfile,
            VerifiedOfficialProfile profile) throws ReflectiveOperationException {
        Objects.requireNonNull(actorHandle, "actorHandle");
        Objects.requireNonNull(liveProfile, "liveProfile");
        VerifiedOfficialProfile checkedProfile = Objects.requireNonNull(profile, "profile");
        if (immutableLiveProfile == null) {
            installMutable(liveProfile, checkedProfile);
            return;
        }
        Object propertyEntries;
        if (checkedProfile.textures().isEmpty()) {
            propertyEntries = immutableEmpty.invoke(null);
        } else {
            propertyEntries = immutableSingleton.invoke(
                    null, TEXTURES, newProperty(checkedProfile.textures().orElseThrow()));
        }
        Object replacementProperties = immutablePropertyMapConstructor.newInstance(propertyEntries);
        Object replacementProfile = immutableProfileConstructor.newInstance(
                immutableProfileId.invoke(liveProfile),
                immutableProfileName.invoke(liveProfile),
                replacementProperties);
        immutableLiveProfile.set(actorHandle, replacementProfile);
    }

    static PaperProfileStateBinding resolveMutable(
            Class<?> gameProfile,
            Class<?> propertyMap,
            Constructor<?> propertyConstructor,
            String authlibFamily) throws ReflectiveOperationException {
        String accessorName = authlibFamily.equals("authlib-v4")
                || authlibFamily.equals("authlib-v6")
                ? "getProperties" : "properties";
        Method properties = gameProfile.getMethod(accessorName);
        requireReturnType(properties, propertyMap);
        Method removeAll = propertyMap.getMethod("removeAll", Object.class);
        Method put = propertyMap.getMethod("put", Object.class, Object.class);
        requireReturnType(put, boolean.class);
        return new PaperProfileStateBinding(
                properties, removeAll, put,
                null, null, null, null, null, null, null,
                propertyConstructor);
    }

    private void installMutable(
            Object liveProfile,
            VerifiedOfficialProfile profile) throws ReflectiveOperationException {
        Object properties = mutableProperties.invoke(liveProfile);
        mutableRemoveAll.invoke(properties, TEXTURES);
        if (profile.textures().isPresent()) {
            mutablePut.invoke(
                    properties, TEXTURES, newProperty(profile.textures().orElseThrow()));
        }
    }

    private Object newProperty(SignedTexturesProperty textures)
            throws ReflectiveOperationException {
        return propertyConstructor.newInstance(
                TEXTURES, textures.value(), textures.signature());
    }

    private static void requireReturnType(Method method, Class<?> expected)
            throws NoSuchMethodException {
        if (method.getReturnType() != expected) {
            throw new NoSuchMethodException(method.getDeclaringClass().getName()
                    + '#' + method.getName() + " has return type "
                    + method.getReturnType().getName());
        }
    }
}

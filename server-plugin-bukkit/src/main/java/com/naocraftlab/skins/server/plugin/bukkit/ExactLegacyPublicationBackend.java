package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.server.SignedTexturesProperty;
import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ExactLegacyPublicationBackend implements BukkitPublicationBackend {
    private static final String TEXTURES = "textures";

    private final Method getHandle;
    private final Method getProfile;
    private final Method profileId;
    private final Method profileName;
    private final Method profileProperties;
    private final Constructor<?> profileConstructor;
    private final Constructor<?> propertyConstructor;
    private final Method propertyEntries;
    private final Method propertyPut;
    private final Field liveProfile;
    private final Method serverLevel;
    private final Method chunkSource;
    private final Field chunkMap;
    private final Field trackedEntities;
    private final Method entityId;
    private final Field seenBy;
    private final Method observerHandle;
    private final Method bukkitEntity;
    private final Method untrack;
    private final Method retrack;
    private final Field playerConnection;
    private final Constructor<?> removePacket;
    private final Method updatePacket;
    private final Method sendPacket;

    private ExactLegacyPublicationBackend(
            Method getHandle,
            Method getProfile,
            Method profileId,
            Method profileName,
            Method profileProperties,
            Constructor<?> profileConstructor,
            Constructor<?> propertyConstructor,
            Method propertyEntries,
            Method propertyPut,
            Field liveProfile,
            Method serverLevel,
            Method chunkSource,
            Field chunkMap,
            Field trackedEntities,
            Method entityId,
            Field seenBy,
            Method observerHandle,
            Method bukkitEntity,
            Method untrack,
            Method retrack,
            Field playerConnection,
            Constructor<?> removePacket,
            Method updatePacket,
            Method sendPacket) {
        this.getHandle = getHandle;
        this.getProfile = getProfile;
        this.profileId = profileId;
        this.profileName = profileName;
        this.profileProperties = profileProperties;
        this.profileConstructor = profileConstructor;
        this.propertyConstructor = propertyConstructor;
        this.propertyEntries = propertyEntries;
        this.propertyPut = propertyPut;
        this.liveProfile = liveProfile;
        this.serverLevel = serverLevel;
        this.chunkSource = chunkSource;
        this.chunkMap = chunkMap;
        this.trackedEntities = trackedEntities;
        this.entityId = entityId;
        this.seenBy = seenBy;
        this.observerHandle = observerHandle;
        this.bukkitEntity = bukkitEntity;
        this.untrack = untrack;
        this.retrack = retrack;
        this.playerConnection = playerConnection;
        this.removePacket = removePacket;
        this.updatePacket = updatePacket;
        this.sendPacket = sendPacket;
    }

    public static ExactLegacyPublicationBackend resolve(
            ClassLoader classLoader, String craftServerPackage)
            throws ReflectiveOperationException {
        Class<?> craftPlayer = type(classLoader, craftServerPackage + ".entity.CraftPlayer");
        Class<?> entityPlayer = type(classLoader, "net.minecraft.server.level.EntityPlayer");
        Class<?> entityHuman = type(classLoader, "net.minecraft.world.entity.player.EntityHuman");
        Class<?> entity = type(classLoader, "net.minecraft.world.entity.Entity");
        Class<?> worldServer = type(classLoader, "net.minecraft.server.level.WorldServer");
        Class<?> chunkProvider = type(classLoader, "net.minecraft.server.level.ChunkProviderServer");
        Class<?> playerChunkMap = type(classLoader, "net.minecraft.server.level.PlayerChunkMap");
        Class<?> tracker = type(classLoader,
                "net.minecraft.server.level.PlayerChunkMap$EntityTracker");
        Class<?> observerConnection = type(classLoader,
                "net.minecraft.server.network.ServerPlayerConnection");
        Class<?> connection = type(classLoader, "net.minecraft.server.network.PlayerConnection");
        Class<?> packet = type(classLoader, "net.minecraft.network.protocol.Packet");
        Class<?> gameProfile = type(classLoader, "com.mojang.authlib.GameProfile");
        Class<?> propertyMap = type(classLoader, "com.mojang.authlib.properties.PropertyMap");
        Class<?> property = type(classLoader, "com.mojang.authlib.properties.Property");
        Class<?> remove = type(classLoader,
                "net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket");
        Class<?> update = type(classLoader,
                "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");

        Method getHandle = exactMethod(craftPlayer, "getHandle", entityPlayer);
        Method getProfile = exactMethod(craftPlayer, "getProfile", gameProfile);
        Method profileId = exactMethod(gameProfile, "getId", UUID.class);
        Method profileName = exactMethod(gameProfile, "getName", String.class);
        Method profileProperties = exactMethod(gameProfile, "getProperties", propertyMap);
        Constructor<?> profileConstructor = gameProfile.getConstructor(UUID.class, String.class);
        Constructor<?> propertyConstructor = property.getConstructor(
                String.class, String.class, String.class);
        Method propertyEntries = propertyMap.getMethod("entries");
        Method propertyPut = propertyMap.getMethod("put", Object.class, Object.class);
        Field liveProfile = exactField(entityHuman, "cp", gameProfile);
        Method serverLevel = exactMethod(entityPlayer, "x", worldServer);
        Method chunkSource = exactMethod(worldServer, "k", chunkProvider);
        Field chunkMap = exactField(chunkProvider, "a", playerChunkMap);
        Field trackedEntities = exactField(playerChunkMap, "K",
                type(classLoader, "it.unimi.dsi.fastutil.ints.Int2ObjectMap"));
        Method entityId = exactMethod(entity, "af", int.class);
        Field seenBy = exactField(tracker, "f", java.util.Set.class);
        Method observerHandle = exactMethod(observerConnection, "f", entityPlayer);
        Method bukkitEntity = entity.getMethod("getBukkitEntity");
        Method untrack = exactMethod(tracker, "a", void.class, entityPlayer);
        Method retrack = exactMethod(tracker, "b", void.class, entityPlayer);
        Field playerConnection = exactField(entityPlayer, "c", connection);
        Constructor<?> removePacket = remove.getConstructor(List.class);
        Method updatePacket = exactMethod(update, "a", update, Collection.class);
        if (!Modifier.isStatic(updatePacket.getModifiers())) {
            throw new NoSuchMethodException(update.getName() + "#a must be static");
        }
        Method sendPacket = exactMethod(connection, "a", void.class, packet);
        return new ExactLegacyPublicationBackend(
                getHandle, getProfile, profileId, profileName, profileProperties,
                profileConstructor, propertyConstructor, propertyEntries, propertyPut,
                liveProfile, serverLevel, chunkSource, chunkMap, trackedEntities,
                entityId, seenBy, observerHandle, bukkitEntity, untrack, retrack,
                playerConnection, removePacket, updatePacket, sendPacket);
    }

    @Override
    public Publication installAndSnapshot(
            Plugin plugin, Player actor, VerifiedOfficialProfile profile) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(profile, "profile");
        try {
            Object handle = getHandle.invoke(actor);
            Object current = getProfile.invoke(actor);
            Object replacement = profileConstructor.newInstance(
                    profileId.invoke(current), profileName.invoke(current));
            Object replacementProperties = profileProperties.invoke(replacement);
            Collection<?> entries = (Collection<?>) propertyEntries.invoke(
                    profileProperties.invoke(current));
            for (Object raw : entries) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) raw;
                if (!TEXTURES.equals(entry.getKey())) {
                    propertyPut.invoke(replacementProperties, entry.getKey(), entry.getValue());
                }
            }
            if (profile.textures().isPresent()) {
                SignedTexturesProperty textures = profile.textures().orElseThrow();
                Object value = propertyConstructor.newInstance(
                        TEXTURES, textures.value(), textures.signature());
                propertyPut.invoke(replacementProperties, TEXTURES, value);
            }
            liveProfile.set(handle, replacement);
            Object level = serverLevel.invoke(handle);
            Object source = chunkSource.invoke(level);
            Object map = chunkMap.get(source);
            int id = (Integer) entityId.invoke(handle);
            Object tracked = ((Map<?, ?>) trackedEntities.get(map)).get(id);
            return tracked == null ? EmptyPublication.INSTANCE
                    : publication(actor, handle, tracked);
        } catch (ReflectiveOperationException failure) {
            throw bindingFailure("install legacy 1.20.1 profile", failure);
        }
    }

    private Publication publication(Player actor, Object actorHandle, Object tracked)
            throws ReflectiveOperationException {
        Collection<?> connections = (Collection<?>) seenBy.get(tracked);
        List<Player> observers = new ArrayList<>();
        for (Object connection : connections) {
            Object handle = observerHandle.invoke(connection);
            Player observer = (Player) bukkitEntity.invoke(handle);
            if (observer != actor) observers.add(observer);
        }
        return new LegacyPublication(actorHandle, tracked, connections, List.copyOf(observers));
    }

    private final class LegacyPublication implements Publication {
        private final Object actorHandle;
        private final Object tracked;
        private final Collection<?> connections;
        private final List<Player> observers;

        private LegacyPublication(
                Object actorHandle, Object tracked, Collection<?> connections,
                List<Player> observers) {
            this.actorHandle = actorHandle;
            this.tracked = tracked;
            this.connections = connections;
            this.observers = observers;
        }

        @Override
        public List<Player> observers() {
            return observers;
        }

        @Override
        public void untrack(Player observer) {
            invokeTracking(untrack, observer);
        }

        @Override
        public void sendPlayerInfo(Player observer) {
            try {
                Object connection = playerConnection.get(getHandle.invoke(observer));
                UUID actorId = ((Player) bukkitEntity.invoke(actorHandle)).getUniqueId();
                Object remove = removePacket.newInstance(List.of(actorId));
                Object update = updatePacket.invoke(null, List.of(actorHandle));
                sendPacket.invoke(connection, remove);
                sendPacket.invoke(connection, update);
            } catch (ReflectiveOperationException failure) {
                throw bindingFailure("send legacy 1.20.1 player info", failure);
            }
        }

        @Override
        public void retrack(Player observer) {
            invokeTracking(retrack, observer);
        }

        @Override
        public boolean isTracking(Player observer) {
            try {
                Object connection = playerConnection.get(getHandle.invoke(observer));
                return connections.contains(connection);
            } catch (ReflectiveOperationException failure) {
                throw bindingFailure("verify legacy 1.20.1 tracking", failure);
            }
        }

        private void invokeTracking(Method method, Player observer) {
            try {
                method.invoke(tracked, getHandle.invoke(observer));
            } catch (ReflectiveOperationException failure) {
                throw bindingFailure("invoke legacy 1.20.1 tracking", failure);
            }
        }
    }

    private enum EmptyPublication implements Publication {
        INSTANCE;

        @Override
        public List<Player> observers() {
            return List.of();
        }

        @Override
        public void untrack(Player observer) {
        }

        @Override
        public void sendPlayerInfo(Player observer) {
        }

        @Override
        public void retrack(Player observer) {
        }

        @Override
        public boolean isTracking(Player observer) {
            return true;
        }
    }

    private static Class<?> type(ClassLoader classLoader, String name)
            throws ClassNotFoundException {
        return Class.forName(name, false, classLoader);
    }

    private static Method exactMethod(
            Class<?> owner, String name, Class<?> returnType, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameters);
        if (method.getReturnType() != returnType) {
            throw new NoSuchMethodException(owner.getName() + '#' + name
                    + " has return type " + method.getReturnType().getName());
        }
        return method;
    }

    private static Field exactField(Class<?> owner, String name, Class<?> type)
            throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != type) {
            throw new NoSuchFieldException(owner.getName() + '#' + name
                    + " has type " + field.getType().getName());
        }
        field.setAccessible(true);
        return field;
    }

    private static IllegalStateException bindingFailure(
            String operation, ReflectiveOperationException failure) {
        Throwable cause = failure instanceof InvocationTargetException invocation
                && invocation.getCause() != null ? invocation.getCause() : failure;
        return new IllegalStateException("Unable to " + operation, cause);
    }
}

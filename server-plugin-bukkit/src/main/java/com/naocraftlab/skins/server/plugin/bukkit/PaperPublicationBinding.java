package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

final class PaperPublicationBinding {
    private final Method getProfile;
    private final Method getHandle;
    private final Method unregisterEntity;
    private final Method trackAndShowEntity;
    private final PaperProfileStateBinding profileState;

    private PaperPublicationBinding(
            Method getProfile,
            Method getHandle,
            Method unregisterEntity,
            Method trackAndShowEntity,
            PaperProfileStateBinding profileState) {
        this.getProfile = getProfile;
        this.getHandle = getHandle;
        this.unregisterEntity = unregisterEntity;
        this.trackAndShowEntity = trackAndShowEntity;
        this.profileState = profileState;
    }

    static PaperPublicationBinding resolve(
            ClassLoader classLoader,
            String craftServerPackage,
            String authlibFamily) throws ReflectiveOperationException {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(craftServerPackage, "craftServerPackage");
        Objects.requireNonNull(authlibFamily, "authlibFamily");
        Class<?> craftPlayer = Class.forName(
                craftServerPackage + ".entity.CraftPlayer", false, classLoader);
        Class<?> gameProfile = Class.forName(
                "com.mojang.authlib.GameProfile", false, classLoader);
        Class<?> entity = Class.forName(
                "net.minecraft.world.entity.Entity", false, classLoader);
        Class<?> serverPlayer = Class.forName(
                authlibFamily.equals("authlib-v4")
                        ? "net.minecraft.server.level.EntityPlayer"
                        : "net.minecraft.server.level.ServerPlayer",
                false,
                classLoader);

        return resolve(craftPlayer, gameProfile, entity, serverPlayer,
                PaperProfileStateBinding.resolve(classLoader, serverPlayer, authlibFamily));
    }

    static PaperPublicationBinding resolve(
            Class<?> craftPlayer, Class<?> gameProfile, Class<?> entity,
            Class<?> serverPlayer, PaperProfileStateBinding profileState)
            throws ReflectiveOperationException {
        Method getProfile = craftPlayer.getMethod("getProfile");
        requireReturnType(getProfile, gameProfile);
        Method getHandle = craftPlayer.getMethod("getHandle");
        requireReturnType(getHandle, serverPlayer);
        Method unregisterEntity = craftPlayer.getDeclaredMethod(
                "unregisterEntity", entity);
        requireObserverMethod(unregisterEntity);
        unregisterEntity.setAccessible(true);
        Method trackAndShowEntity = craftPlayer.getDeclaredMethod(
                "trackAndShowEntity", org.bukkit.entity.Entity.class, java.util.UUID.class);
        requireObserverMethod(trackAndShowEntity);
        trackAndShowEntity.setAccessible(true);
        return new PaperPublicationBinding(
                getProfile, getHandle, unregisterEntity, trackAndShowEntity,
                profileState);
    }

    Actor install(Player actor, VerifiedOfficialProfile profile) {
        Player checkedActor = Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(profile, "profile");
        try {
            Object handle = getHandle.invoke(checkedActor);
            Object liveProfile = getProfile.invoke(checkedActor);
            profileState.install(handle, liveProfile, profile);
            return new Actor(checkedActor, handle);
        } catch (ReflectiveOperationException failure) {
            throw bindingFailure("update Paper live profile", failure);
        }
    }

    private static void requireReturnType(Method method, Class<?> expected)
            throws NoSuchMethodException {
        if (method.getReturnType() != expected || Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException(method.getDeclaringClass().getName()
                    + '#' + method.getName() + " does not match the exact instance method contract");
        }
    }

    private static void requireObserverMethod(Method method) throws NoSuchMethodException {
        requireReturnType(method, void.class);
        if (!Modifier.isPrivate(method.getModifiers())) {
            throw new NoSuchMethodException(method.getDeclaringClass().getName()
                    + '#' + method.getName() + " is not the exact private observer operation");
        }
    }

    private static IllegalStateException bindingFailure(
            String operation, ReflectiveOperationException failure) {
        Throwable cause = failure instanceof InvocationTargetException invocation
                && invocation.getCause() != null ? invocation.getCause() : failure;
        return new IllegalStateException("Unable to " + operation, cause);
    }

    final class Actor {
        private final Player player;
        private final Object handle;

        private Actor(Player player, Object handle) {
            this.player = player;
            this.handle = Objects.requireNonNull(handle, "handle");
        }

        void untrack(Player observer) {
            try {
                unregisterEntity.invoke(observer, handle);
            } catch (ReflectiveOperationException failure) {
                throw bindingFailure("publish Paper observer profile", failure);
            }
        }

        void retrack(Player observer) {
            try {
                trackAndShowEntity.invoke(observer, player, player.getUniqueId());
            } catch (ReflectiveOperationException failure) {
                throw bindingFailure("publish Paper observer profile", failure);
            }
        }
    }
}

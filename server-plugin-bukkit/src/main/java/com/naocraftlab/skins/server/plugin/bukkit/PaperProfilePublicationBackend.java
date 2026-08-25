package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

final class PaperProfilePublicationBackend implements BukkitPublicationBackend {
    private final Method getProfile;
    private final Method getHandle;
    private final Method unregisterEntity;
    private final Method trackAndShowEntity;
    private final PaperProfileStateBinding profileState;

    private PaperProfilePublicationBackend(
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

    static PaperProfilePublicationBackend resolve(
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

        Method getProfile = craftPlayer.getMethod("getProfile");
        requireReturnType(getProfile, gameProfile);
        Method getHandle = craftPlayer.getMethod("getHandle");
        requireReturnType(getHandle, serverPlayer);
        Method unregisterEntity = craftPlayer.getDeclaredMethod(
                "unregisterEntity", entity);
        requireReturnType(unregisterEntity, void.class);
        unregisterEntity.setAccessible(true);
        Method trackAndShowEntity = craftPlayer.getDeclaredMethod(
                "trackAndShowEntity", org.bukkit.entity.Entity.class, java.util.UUID.class);
        requireReturnType(trackAndShowEntity, void.class);
        trackAndShowEntity.setAccessible(true);
        return new PaperProfilePublicationBackend(
                getProfile, getHandle, unregisterEntity, trackAndShowEntity,
                PaperProfileStateBinding.resolve(
                        classLoader, serverPlayer, authlibFamily));
    }

    @Override
    public Publication installAndSnapshot(
            Plugin plugin, Player actor, VerifiedOfficialProfile profile) {
        Plugin checkedPlugin = Objects.requireNonNull(plugin, "plugin");
        Player checkedActor = Objects.requireNonNull(actor, "actor");
        VerifiedOfficialProfile checkedProfile = Objects.requireNonNull(profile, "profile");
        Object actorHandle;
        try {
            actorHandle = getHandle.invoke(checkedActor);
            Object liveProfile = getProfile.invoke(checkedActor);
            profileState.install(actorHandle, liveProfile, checkedProfile);
        } catch (ReflectiveOperationException failure) {
            throw bindingFailure("update Paper live profile", failure);
        }
        List<Player> observers = Bukkit.getOnlinePlayers().stream()
                .filter(observer -> observer != checkedActor
                        && observer.isOnline()
                        && observer.canSee(checkedActor))
                .map(Player.class::cast)
                .toList();
        return new PaperPublication(checkedActor, actorHandle, observers);
    }

    private static void requireReturnType(Method method, Class<?> expected)
            throws NoSuchMethodException {
        if (method.getReturnType() != expected) {
            throw new NoSuchMethodException(method.getDeclaringClass().getName()
                    + '#' + method.getName() + " has return type "
                    + method.getReturnType().getName());
        }
    }

    private static IllegalStateException bindingFailure(
            String operation, ReflectiveOperationException failure) {
        Throwable cause = failure instanceof InvocationTargetException invocation
                && invocation.getCause() != null ? invocation.getCause() : failure;
        return new IllegalStateException("Unable to " + operation, cause);
    }

    private final class PaperPublication implements Publication {
        private final Player actor;
        private final Object actorHandle;
        private final List<Player> observers;

        private PaperPublication(Player actor, Object actorHandle, List<Player> observers) {
            this.actor = Objects.requireNonNull(actor, "actor");
            this.actorHandle = Objects.requireNonNull(actorHandle, "actorHandle");
            this.observers = List.copyOf(observers);
        }

        @Override
        public List<Player> observers() {
            return observers;
        }

        @Override
        public void untrack(Player observer) {
            invokeObserver(unregisterEntity, observer, actorHandle);
        }

        @Override
        public void sendPlayerInfo(Player observer) {
        }

        @Override
        public void retrack(Player observer) {
            invokeObserver(trackAndShowEntity, observer, actor, actor.getUniqueId());
        }

        @Override
        public boolean isTracking(Player observer) {
            return observer.canSee(actor);
        }

        private void invokeObserver(Method method, Player observer, Object... arguments) {
            try {
                method.invoke(observer, arguments);
            } catch (ReflectiveOperationException failure) {
                throw bindingFailure("publish Paper observer profile", failure);
            }
        }
    }
}

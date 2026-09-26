package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.server.ServerPlayerIdentity;
import com.naocraftlab.skins.server.TextureAppearance;
import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PaperPublicationBindingTest {
    @Test
    void installsBeforeObserverOperationsAndKeepsNativeArguments() throws Exception {
        List<String> calls = new ArrayList<>();
        Handle handle = new Handle();
        Profile profile = new Profile(calls);
        UUID id = new UUID(0, 1);
        CraftPlayer actor = proxy((method, args) -> switch (method) {
            case "getHandle" -> { calls.add("handle"); yield handle; }
            case "getProfile" -> { calls.add("profile"); yield profile; }
            case "getUniqueId" -> id;
            default -> throw new AssertionError(method);
        });
        CraftPlayer observer = proxy((method, args) -> {
            switch (method) {
                case "recordUntrack" -> {
                    assertSame(handle, args[0]);
                    calls.add("untrack");
                }
                case "recordRetrack" -> {
                    assertSame(actor, args[0]);
                    assertEquals(id, args[1]);
                    calls.add("retrack");
                }
                default -> throw new AssertionError(method);
            }
            return null;
        });

        PaperPublicationBinding.Actor installed = binding(CraftPlayer.class).install(actor, verified());
        assertEquals(List.of("handle", "profile", "install"), calls);
        installed.untrack(observer);
        installed.retrack(observer);
        assertEquals(List.of("handle", "profile", "install", "untrack", "retrack"), calls);
    }

    @Test
    void invocationFailuresRetainCauseAndNeverProduceAnInstalledActor() throws Exception {
        IllegalStateException expected = new IllegalStateException("fixture failure");
        CraftPlayer actor = proxy((method, args) -> { throw expected; });
        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> binding(CraftPlayer.class).install(actor, verified()));
        assertSame(expected, actual.getCause());
    }

    @Test
    void observerFailurePreservesCause() throws Exception {
        CraftPlayer actor = proxy((method, args) -> switch (method) {
            case "getHandle" -> new Handle();
            case "getProfile" -> new Profile(new ArrayList<>());
            case "getUniqueId" -> new UUID(0, 1);
            default -> throw new AssertionError(method);
        });
        IllegalStateException expected = new IllegalStateException("observer failure");
        CraftPlayer observer = proxy((method, args) -> { throw expected; });
        PaperPublicationBinding.Actor installed = binding(CraftPlayer.class).install(actor, verified());
        assertSame(expected, assertThrows(IllegalStateException.class,
                () -> installed.untrack(observer)).getCause());
        assertSame(expected, assertThrows(IllegalStateException.class,
                () -> installed.retrack(observer)).getCause());
    }

    @Test
    void rejectsWrongReturnTypeAndStaticLookalikeBeforeInvocation() {
        assertThrows(NoSuchMethodException.class, () -> binding(WrongProfile.class));
        assertThrows(NoSuchMethodException.class, () -> binding(StaticProfile.class));
        assertThrows(NoSuchMethodException.class, () -> binding(MissingObserverOperations.class));
        assertThrows(NoSuchMethodException.class, () -> binding(PublicObserverOperations.class));
    }

    private static PaperPublicationBinding binding(Class<?> craftPlayer) throws Exception {
        PaperProfileStateBinding state = PaperProfileStateBinding.resolveMutable(
                Profile.class, Properties.class,
                Property.class.getConstructor(String.class, String.class, String.class), "authlib-v6");
        return PaperPublicationBinding.resolve(craftPlayer, Profile.class, Handle.class, Handle.class, state);
    }

    private static VerifiedOfficialProfile verified() {
        return new VerifiedOfficialProfile(new ServerPlayerIdentity(new UUID(0, 1), "Fixture"),
                TextureAppearance.accountDefault(), Optional.empty());
    }

    private static CraftPlayer proxy(Invocation invocation) {
        return (CraftPlayer) Proxy.newProxyInstance(PaperPublicationBindingTest.class.getClassLoader(),
                new Class<?>[] {CraftPlayer.class},
                (proxy, method, args) -> invocation.call(method.getName(), args));
    }

    private interface Invocation {
        Object call(String method, Object[] args);
    }

    public interface CraftPlayer extends Player {
        Profile getProfile();
        Handle getHandle();
        void recordUntrack(Handle entity);
        void recordRetrack(Entity entity, UUID id);
        private void unregisterEntity(Handle entity) { recordUntrack(entity); }
        private void trackAndShowEntity(Entity entity, UUID id) { recordRetrack(entity, id); }
    }

    public static final class Handle {}

    public static final class Profile {
        private final Properties properties;
        Profile(List<String> calls) { properties = new Properties(calls); }
        public Properties getProperties() { return properties; }
    }

    public static final class Properties {
        private final List<String> calls;
        Properties(List<String> calls) { this.calls = calls; }
        public Object removeAll(Object key) { calls.add("install"); return null; }
        public boolean put(Object key, Object value) { return true; }
    }

    public static final class Property {
        public Property(String name, String value, String signature) {}
    }

    public static final class WrongProfile {
        public Object getProfile() { throw new AssertionError(); }
    }

    public static final class StaticProfile {
        public static Profile getProfile() { throw new AssertionError(); }
    }

    public static final class MissingObserverOperations {
        public Profile getProfile() { throw new AssertionError(); }
        public Handle getHandle() { throw new AssertionError(); }
    }

    public static final class PublicObserverOperations {
        public Profile getProfile() { throw new AssertionError(); }
        public Handle getHandle() { throw new AssertionError(); }
        public void unregisterEntity(Handle entity) { throw new AssertionError(); }
    }
}

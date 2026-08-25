package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.server.ServerPlayerIdentity;
import com.naocraftlab.skins.server.SignedTexturesProperty;
import com.naocraftlab.skins.server.TextureAppearance;
import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;


final class PaperProfileStateBindingTest {
    @Test
    void keepsMutableProfileIdentityAndReplacesOnlyTextures() throws Exception {
        Constructor<FakeProperty> propertyConstructor = FakeProperty.class.getConstructor(
                String.class, String.class, String.class);
        PaperProfileStateBinding binding = PaperProfileStateBinding.resolveMutable(
                FakeMutableGameProfile.class,
                FakeMutablePropertyMap.class,
                propertyConstructor,
                "authlib-v6");
        UUID profileId = UUID.nameUUIDFromBytes("mutable-profile".getBytes());
        FakeMutablePropertyMap properties = new FakeMutablePropertyMap();
        properties.put("textures", new FakeProperty("textures", "old", "old-signature"));
        properties.put("unrelated", new FakeProperty("unrelated", "kept", "kept-signature"));
        FakeMutableGameProfile original = new FakeMutableGameProfile(properties);

        binding.install(new Object(), original, verifiedProfile(profileId));

        assertSame(properties, original.getProperties());
        assertEquals("new-value", ((FakeProperty) properties.entries.get("textures")).value);
        assertEquals("kept", ((FakeProperty) properties.entries.get("unrelated")).value);
    }

    @Test
    void replacesImmutableProfileStateWithoutMutatingOriginalMap() throws Exception {
        PaperProfileStateBinding binding = immutableBinding();
        UUID profileId = UUID.nameUUIDFromBytes("immutable-profile".getBytes());
        FakeProperty oldProperty = new FakeProperty("textures", "old", "old-signature");
        FakePropertyMap oldProperties = new FakePropertyMap(
                FakeImmutableMultimap.of("textures", oldProperty));
        FakeGameProfile original = new FakeGameProfile(
                profileId, "ImmutablePlayer", oldProperties);
        FakeServerPlayer actor = new FakeServerPlayer(original);

        binding.install(actor, original, verifiedProfile(profileId));

        assertNotSame(original, actor.gameProfile);
        assertEquals(profileId, actor.gameProfile.id());
        assertEquals("ImmutablePlayer", actor.gameProfile.name());
        assertSame(oldProperty, original.properties().entries.get("textures"));
        FakeProperty installed = (FakeProperty) actor.gameProfile.properties()
                .entries.get("textures");
        assertEquals("new-value", installed.value);
        assertEquals("new-signature", installed.signature);
    }

    @Test
    void installsEmptyImmutablePropertiesForAccountDefault() throws Exception {
        PaperProfileStateBinding binding = immutableBinding();
        UUID profileId = UUID.nameUUIDFromBytes("default-profile".getBytes());
        FakeGameProfile original = new FakeGameProfile(
                profileId,
                "DefaultPlayer",
                new FakePropertyMap(FakeImmutableMultimap.of(
                        "textures", new FakeProperty("textures", "old", "signature"))));
        FakeServerPlayer actor = new FakeServerPlayer(original);
        VerifiedOfficialProfile accountDefault = new VerifiedOfficialProfile(
                new ServerPlayerIdentity(profileId, "DefaultPlayer"),
                TextureAppearance.accountDefault(),
                Optional.empty());

        binding.install(actor, original, accountDefault);

        assertEquals(Map.of(), actor.gameProfile.properties().entries);
        assertEquals(1, original.properties().entries.size());
    }

    private static PaperProfileStateBinding immutableBinding() throws Exception {
        Constructor<FakeProperty> propertyConstructor = FakeProperty.class.getConstructor(
                String.class, String.class, String.class);
        return PaperProfileStateBinding.resolveImmutable(
                FakeGameProfile.class,
                FakePropertyMap.class,
                FakeMultimap.class,
                FakeImmutableMultimap.class,
                FakePlayer.class,
                FakeServerPlayer.class,
                propertyConstructor);
    }

    private static VerifiedOfficialProfile verifiedProfile(UUID profileId) {
        return new VerifiedOfficialProfile(
                new ServerPlayerIdentity(profileId, "ImmutablePlayer"),
                TextureAppearance.verified(
                        Optional.of("a".repeat(64)),
                        Optional.of(TextureAppearance.SkinModel.CLASSIC),
                        Optional.empty(),
                        Optional.empty()),
                Optional.of(new SignedTexturesProperty(
                        "new-value", "new-signature")));
    }

    public interface FakeMultimap {
        Map<Object, Object> entries();
    }

    public static final class FakeImmutableMultimap implements FakeMultimap {
        private final Map<Object, Object> entries;

        private FakeImmutableMultimap(Map<Object, Object> entries) {
            this.entries = Map.copyOf(entries);
        }

        public static FakeImmutableMultimap of() {
            return new FakeImmutableMultimap(Map.of());
        }

        public static FakeImmutableMultimap of(Object key, Object value) {
            Map<Object, Object> entries = new LinkedHashMap<>();
            entries.put(key, value);
            return new FakeImmutableMultimap(entries);
        }

        @Override
        public Map<Object, Object> entries() {
            return entries;
        }
    }

    public static final class FakePropertyMap {
        private final Map<Object, Object> entries;

        public FakePropertyMap(FakeMultimap properties) {
            this.entries = Map.copyOf(properties.entries());
        }
    }

    public static final class FakeMutablePropertyMap {
        private final Map<Object, Object> entries = new LinkedHashMap<>();

        public Object removeAll(Object key) {
            return entries.remove(key);
        }

        public boolean put(Object key, Object value) {
            entries.put(key, value);
            return true;
        }
    }

    public static final class FakeMutableGameProfile {
        private final FakeMutablePropertyMap properties;

        FakeMutableGameProfile(FakeMutablePropertyMap properties) {
            this.properties = properties;
        }

        public FakeMutablePropertyMap getProperties() {
            return properties;
        }
    }

    public static final class FakeProperty {
        private final String name;
        private final String value;
        private final String signature;

        public FakeProperty(String name, String value, String signature) {
            this.name = name;
            this.value = value;
            this.signature = signature;
        }
    }

    public static final class FakeGameProfile {
        private final UUID id;
        private final String name;
        private final FakePropertyMap properties;

        public FakeGameProfile(UUID id, String name, FakePropertyMap properties) {
            this.id = id;
            this.name = name;
            this.properties = properties;
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        FakePropertyMap properties() {
            return properties;
        }
    }

    public static class FakePlayer {
        public FakeGameProfile gameProfile;

        FakePlayer(FakeGameProfile gameProfile) {
            this.gameProfile = gameProfile;
        }
    }

    public static final class FakeServerPlayer extends FakePlayer {
        FakeServerPlayer(FakeGameProfile gameProfile) {
            super(gameProfile);
        }
    }
}

package com.naocraftlab.skins.buildlogic.probe;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource.PlayerAppearance;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.tools.ToolProvider;
import net.minecraft.resources.ResourceLocation;

public final class NativeCurrentAppearanceProbe {
    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);
    private static final PlayerAppearance INSTALLED = new PlayerAppearance(
            new TextureHandle("nclskins:fixture/installed", 64, 64), SkinModel.SLIM, Optional.empty());
    private static final List<Object[]> LOOKUPS = new ArrayList<>();
    private static int attempts;
    private static boolean rejectNext;

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void lookup(GameProfile profile, Object callback) {
        attempts++;
        if (rejectNext) { rejectNext = false; throw new IllegalStateException("Synthetic registration unavailable"); }
        LOOKUPS.add(new Object[] { profile, callback });
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("setup")) { setup(Path.of(args[1])); return; }
        require(args[0].equals("run"), "Known native appearance phase");
        installed(); unavailable(); lookupAndCallbacks(); retry(); player(); reentrant(); overridden(); edges();
        System.out.println("NATIVE_CURRENT_APPEARANCE_PASS groups=8 game-launched=false graphics=false network=false");
    }

    private static void setup(Path root) throws Exception {
        String minecraft = "package net.minecraft.client; public final class Minecraft {"
                + "public static User current; public static net.minecraft.client.resources.SkinManager manager;"
                + "public net.minecraft.client.player.LocalPlayer player;"
                + "private static final Minecraft INSTANCE=new Minecraft();"
                + "public static Minecraft getInstance(){return INSTANCE;} public User getUser(){return current;}"
                + "public net.minecraft.client.resources.SkinManager getSkinManager(){return manager;} }";
        String manager = "package net.minecraft.client.resources; public final class SkinManager {"
                + "public interface SkinTextureCallback { void onSkinTextureAvailable(com.mojang.authlib.minecraft.MinecraftProfileTexture.Type type,net.minecraft.resources.ResourceLocation location,com.mojang.authlib.minecraft.MinecraftProfileTexture texture); }"
                + "public void registerSkins(com.mojang.authlib.GameProfile profile,SkinTextureCallback callback,boolean secure){"
                + "com.naocraftlab.skins.buildlogic.probe.NativeCurrentAppearanceProbe.lookup(profile,callback);} }";
        String player = "package net.minecraft.client.player; public abstract class AbstractClientPlayer {"
                + "private final net.minecraft.resources.ResourceLocation skin,cape; private final String model;"
                + "public AbstractClientPlayer(net.minecraft.resources.ResourceLocation skin,net.minecraft.resources.ResourceLocation cape,String model){this.skin=skin;this.cape=cape;this.model=model;}"
                + "public net.minecraft.resources.ResourceLocation getSkinTextureLocation(){return skin;}"
                + "public net.minecraft.resources.ResourceLocation getCloakTextureLocation(){return cape;}"
                + "public String getModelName(){return model;} }";
        String localPlayer = "package net.minecraft.client.player; public final class LocalPlayer extends AbstractClientPlayer {"
                + "public LocalPlayer(net.minecraft.resources.ResourceLocation skin,net.minecraft.resources.ResourceLocation cape,String model){super(skin,cape,model);} }";
        String overridden = "package com.naocraftlab.skins.buildlogic.probe.fixture; public final class OverriddenUser extends net.minecraft.client.User {"
                + "public OverriddenUser(){super(\"Fixture\",\"00000000000000000000000000000001\",\"synthetic-unused-placeholder\",java.util.Optional.empty(),java.util.Optional.empty(),net.minecraft.client.User.Type.values()[0]);}"
                + "@Override public java.util.UUID getProfileId(){return new java.util.UUID(0,91);}"
                + "@Override public com.mojang.authlib.GameProfile getGameProfile(){return new com.mojang.authlib.GameProfile(new java.util.UUID(0,92),\"Decorated\");} }";
        List<String> files = new ArrayList<>();
        for (var entry : Map.of("net/minecraft/client/Minecraft.java", minecraft,
                "net/minecraft/client/resources/SkinManager.java", manager,
                "net/minecraft/client/player/AbstractClientPlayer.java", player,
                "net/minecraft/client/player/LocalPlayer.java", localPlayer,
                "com/naocraftlab/skins/buildlogic/probe/fixture/OverriddenUser.java", overridden).entrySet()) {
            Path file = root.resolve("sources").resolve(entry.getKey());
            Files.createDirectories(file.getParent()); Files.writeString(file, entry.getValue()); files.add(file.toString());
        }
        List<String> options = new ArrayList<>(List.of("--release", "17", "-encoding", "UTF-8", "-cp", System.getProperty("java.class.path"), "-d", root.toString()));
        options.addAll(files);
        require(ToolProvider.getSystemJavaCompiler().run(null, null, null, options.toArray(String[]::new)) == 0,
                "Owned data-only host compiles against selected SDK");
        System.out.println("NATIVE_CURRENT_HOST_PASS owned-host-only=true original-user-default-skin-resource-location=true");
    }

    private static Object user(String raw) throws Exception {
        Class<?> type = Class.forName("net.minecraft.client.User");
        Class<?> account = Class.forName("net.minecraft.client.User$Type");
        return type.getConstructor(String.class, String.class, String.class, Optional.class, Optional.class, account)
                .newInstance("Fixture", raw, "synthetic-unused-placeholder", Optional.empty(), Optional.empty(), account.getEnumConstants()[0]);
    }

    private static void current(Object user) throws Exception { mc().getField("current").set(null, user); }
    private static Class<?> mc() throws Exception { return Class.forName("net.minecraft.client.Minecraft"); }
    private static Object instance() throws Exception { return mc().getMethod("getInstance").invoke(null); }
    private static void manager(boolean present) throws Exception {
        mc().getField("manager").set(null, present ? Class.forName("net.minecraft.client.resources.SkinManager").getConstructor().newInstance() : null);
    }
    private static CurrentPlayerAppearanceSource source(Function<UUID, Optional<PlayerAppearance>> installed) throws Exception {
        return (CurrentPlayerAppearanceSource) Class.forName("com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.ResourceLocationCurrentPlayerAppearanceSource")
                .getConstructor(Function.class).newInstance(installed);
    }
    private static CurrentPlayerAppearanceSource source() throws Exception { return source(ignored -> Optional.empty()); }
    private static void reset() throws Exception {
        current(user(FIRST.toString().replace("-", ""))); manager(false); mc().getField("player").set(instance(), null);
        LOOKUPS.clear(); attempts = 0; rejectNext = false; counters();
    }
    private static void counters() { NativeUserProfileReadProbe.identityReads = 0; NativeUserProfileReadProbe.profileReads = 0; NativeUserProfileReadProbe.tokenReads = 0; }
    private static void noProfiles(String scenario) {
        require(NativeUserProfileReadProbe.profileReads == 0 && NativeUserProfileReadProbe.identityReads == 0,
                scenario + " must not retrieve GameProfile or invoke legacy UUID conversion");
        require(NativeUserProfileReadProbe.tokenReads == 0, scenario + " never reads token");
    }
    private static void installed() throws Exception {
        reset(); List<UUID> ids = new ArrayList<>();
        var source = source(id -> { ids.add(id); return Optional.of(INSTALLED); });
        for (int i = 0; i < 5; i++) require(source.currentPlayerAppearance().equals(INSTALLED), "Installed appearance remains exact");
        require(ids.equals(List.of(FIRST, FIRST, FIRST, FIRST, FIRST)), "Every installed read sees current identity");
        noProfiles("Installed appearance");
        current(user(SECOND.toString())); source.currentPlayerAppearance();
        require(ids.get(5).equals(SECOND), "User replacement is observed on next read"); noProfiles("Replaced installed appearance");
        source.defaultPlayerAppearance(); noProfiles("Default appearance");
    }
    private static void unavailable() throws Exception {
        reset(); var source = source(); var first = source.currentPlayerAppearance();
        for (int i = 0; i < 5; i++) require(source.currentPlayerAppearance().equals(first), "Unavailable manager keeps defaults stable");
        require(attempts == 0, "Unavailable manager does not mark lookup started"); noProfiles("Unavailable manager");
        manager(true); source.currentPlayerAppearance(); require(attempts == 1, "Manager availability admits cold lookup");
    }
    private static void callback(int index, MinecraftProfileTexture.Type type, String location) throws Exception {
        Object callback = LOOKUPS.get(index)[1];
        callback.getClass().getInterfaces()[0].getMethod("onSkinTextureAvailable", MinecraftProfileTexture.Type.class, ResourceLocation.class, MinecraftProfileTexture.class)
                .invoke(callback, type, new ResourceLocation(location), new MinecraftProfileTexture("https://fixture.invalid/unused", Map.of("model", "slim")));
    }
    private static void lookupAndCallbacks() throws Exception {
        reset(); manager(true); var source = source(); source.currentPlayerAppearance();
        for (int i = 0; i < 8; i++) source.currentPlayerAppearance();
        require(attempts == 1 && NativeUserProfileReadProbe.profileReads == 1 && NativeUserProfileReadProbe.identityReads == 1,
                "Only admitted cold lookup retrieves original full profile");
        require(((GameProfile) LOOKUPS.get(0)[0]).getId().equals(FIRST), "Cold lookup retains original profile");
        current(user(SECOND.toString().replace("-", ""))); var second = source.currentPlayerAppearance();
        require(attempts == 2, "Identity change resets lookup admission");
        callback(0, MinecraftProfileTexture.Type.SKIN, "nclskins:fixture/stale");
        require(source.currentPlayerAppearance().equals(second), "Old identity callback cannot update new appearance");
        callback(1, MinecraftProfileTexture.Type.SKIN, "nclskins:fixture/accepted");
        callback(1, MinecraftProfileTexture.Type.CAPE, "nclskins:fixture/cape");
        require(source.currentPlayerAppearance().equals(new PlayerAppearance(new TextureHandle("nclskins:fixture/accepted",64,64),
                SkinModel.SLIM, Optional.of(new TextureHandle("nclskins:fixture/cape",64,32)))), "Current callbacks update native appearance");
        require(NativeUserProfileReadProbe.tokenReads == 0, "Lookup path never reads token");
    }
    private static void retry() throws Exception {
        reset(); manager(true); rejectNext = true; var source = source(); source.currentPlayerAppearance(); source.currentPlayerAppearance(); source.currentPlayerAppearance();
        require(attempts == 2 && LOOKUPS.size() == 1 && NativeUserProfileReadProbe.profileReads == 2,
                "Registration failure permits exactly one retry then warm reuse");
    }
    private static void player() throws Exception {
        reset(); Object player = Class.forName("net.minecraft.client.player.LocalPlayer")
                .getConstructor(ResourceLocation.class, ResourceLocation.class, String.class)
                .newInstance(new ResourceLocation("nclskins:fixture/player"),new ResourceLocation("nclskins:fixture/player-cape"),"slim");
        mc().getField("player").set(instance(), player); var source = source();
        var expected = new PlayerAppearance(new TextureHandle("nclskins:fixture/player",64,64),SkinModel.SLIM,
                Optional.of(new TextureHandle("nclskins:fixture/player-cape",64,32)));
        require(source.currentPlayerAppearance().equals(expected), "Player skin/cape/model stay native"); noProfiles("Player appearance");
        require(attempts == 0, "Player path does not start lookup");
    }
    private static void reentrant() throws Exception {
        reset(); manager(true); var source = source(id -> {
            try { current(user(SECOND.toString().replace("-", ""))); } catch (Exception failure) { throw new AssertionError(failure); }
            return Optional.empty();
        });
        source.currentPlayerAppearance();
        require(((GameProfile) LOOKUPS.get(0)[0]).getId().equals(FIRST), "Installed callback cannot replace already captured User for cold lookup");
        source.currentPlayerAppearance(); require(((GameProfile) LOOKUPS.get(1)[0]).getId().equals(SECOND), "Next read observes replacement");
    }
    private static void overridden() throws Exception {
        reset(); current(Class.forName("com.naocraftlab.skins.buildlogic.probe.fixture.OverriddenUser").getConstructor().newInstance());
        var session = (GameSessionTokenSource) Class.forName("com.naocraftlab.skins.compat.client.MinecraftGameSessionTokenSource").getConstructor().newInstance();
        require(session.currentSession().profileId().equals(new UUID(0,91)), "Subclass profile-id override is authoritative");
        manager(true); var source = source(id -> { require(id.equals(new UUID(0,92)), "Subclass full-profile override is authoritative"); return Optional.empty(); });
        source.currentPlayerAppearance(); source.currentPlayerAppearance();
        require(((GameProfile) LOOKUPS.get(0)[0]).getName().equals("Decorated"), "Original subclass full profile reaches lookup");
    }
    private static void edges() throws Exception {
        reset(); var session = (GameSessionTokenSource) Class.forName("com.naocraftlab.skins.compat.client.MinecraftGameSessionTokenSource").getConstructor().newInstance();
        for (String raw : Arrays.asList(null,"","invalid","1-1-1-1-1","00000000-0000-0000-0000-00000000000+",
                "0000000000000000000000000000000+"," 00000000000000000000000000000001","{00000000-0000-0000-0000-000000000001}",
                "0000000000000000000000000000000\u0661","00000000-0000-0000-0000-0000000000010")) {
            Object value = user(raw); current(value); UUID expected = null; Class<?> failure = null;
            try { expected = (UUID) value.getClass().getMethod("getProfileId").invoke(value); if (expected == null) failure = NullPointerException.class; }
            catch (java.lang.reflect.InvocationTargetException original) { failure = original.getCause().getClass(); }
            try { UUID actual = session.currentSession().profileId(); require(failure == null && actual.equals(expected), "Noncanonical session preserves native result"); }
            catch (RuntimeException actual) { require(actual.getClass() == failure, "Noncanonical session preserves native failure"); }
            GameProfile original = null; failure = null;
            try { original = (GameProfile) value.getClass().getMethod("getGameProfile").invoke(value); }
            catch (java.lang.reflect.InvocationTargetException rejected) { failure = rejected.getCause().getClass(); }
            final UUID profile = original == null || original.getId() == null ? new UUID(0,0) : original.getId();
            var appearance = source(id -> { require(id.equals(profile), "Noncanonical appearance preserves native profile projection"); return Optional.of(INSTALLED); });
            try { require(appearance.currentPlayerAppearance().equals(INSTALLED) && failure == null, "Noncanonical appearance preserves native result"); }
            catch (RuntimeException actual) { require(actual.getClass() == failure, "Noncanonical appearance preserves native failure"); }
        }
        require(NativeUserProfileReadProbe.tokenReads == 0, "Fallbacks never inspect token");
    }
}

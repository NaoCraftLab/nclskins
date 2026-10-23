package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.runtime.CapeProjection;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public final class MinecraftCapeFallbackProbe {
    private static final UUID REMOTE = new UUID(0, 1);
    private static final UUID SELF = new UUID(0, 2);
    private static final Location CAPE = new Location("fixture:official-cape");
    private static final Location ELYTRA = new Location("fixture:official-elytra");
    private static final Location BODY = new Location("fixture:body");
    private static Method nativeWings;
    private static Class<?> previewAdapter;
    private static boolean legacyPreview;

    static void sneakyPreview(com.naocraftlab.skins.client.PreviewRenderer.PreviewAppearance appearance) throws Exception {
        if (previewAdapter == null) {
            String owner = null;
            for (String candidate : List.of(
                    "com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/RemotePlayerPreviewRenderer",
                    "com/naocraftlab/skins/compat/client/resourcelocation/skinlookup/VanillaAppearancePreviewRenderer",
                    "com/naocraftlab/skins/compat/client/identifier/submission/SubmissionPreviewRenderer",
                    "com/naocraftlab/skins/compat/client/identifier/extraction/AvatarRenderStatePreviewRenderer")) {
                if (MinecraftCapeFallbackProbe.class.getClassLoader().getResource(candidate + ".class") != null) {
                    check(owner == null, "one exact compiled preview material adapter");
                    owner = candidate;
                }
            }
            check(owner != null, "compiled ordinary preview material adapter exists");
            legacyPreview = owner.contains("playerinfo");
            String generated = "com/naocraftlab/skins/buildlogic/probe/LinkedSneakyPreviewMaterial";
            ClassNode node = readClass(legacyPreview ? owner + "$PreviewPlayer" : owner);
            Set<String> retained = legacyPreview ? Set.of("setAppearance") : Set.of("playerSkin", "texture", "parseTexture");
            node.methods.removeIf(method -> !retained.contains(method.name) && !method.name.startsWith("lambda$playerSkin$"));
            check(node.methods.stream().filter(method -> method.name.equals(legacyPreview ? "setAppearance" : "playerSkin")).count() == 1,
                    "exact compiled Sneaky preview material producer");
            if (legacyPreview) {
                ClassNode outer = readClass(owner);
                var location = outer.methods.stream().filter(method -> method.name.equals("location")).toList();
                check(location.size() == 1, "exact legacy preview texture conversion");
                node.methods.add(location.get(0));
                node.fields.removeIf(field -> !Set.of("previewSkin", "previewCape", "previewElytra", "previewModel").contains(field.name));
                MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                constructor.visitVarInsn(Opcodes.ALOAD, 0);
                constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                constructor.visitInsn(Opcodes.RETURN);
                constructor.visitMaxs(1, 1);
                node.methods.add(constructor);
            } else node.fields.clear();
            node.superName = "java/lang/Object";
            node.interfaces.clear();
            node.innerClasses.clear();
            node.nestHostClass = null;
            node.nestMembers = null;
            node.access = Opcodes.ACC_PUBLIC;
            Map<String, String> types = new HashMap<>();
            types.put(owner, generated);
            types.put(owner + "$PreviewPlayer", generated);
            map(types, "net/minecraft/resources/ResourceLocation", Location.class);
            map(types, "net/minecraft/resources/Identifier", Location.class);
            map(types, "net/minecraft/client/resources/PlayerSkin", Skin.class);
            map(types, "net/minecraft/client/resources/PlayerSkin$Model", Model.class);
            map(types, "net/minecraft/world/entity/player/PlayerSkin", AssetSkin.class);
            map(types, "net/minecraft/world/entity/player/PlayerModelType", Model.class);
            map(types, "net/minecraft/core/ClientAsset$Texture", Texture.class);
            map(types, "net/minecraft/core/ClientAsset$ResourceTexture", ResourceTexture.class);
            ClassWriter writer = new ClassWriter(0);
            node.accept(new ClassRemapper(writer, new Remapper(Opcodes.ASM9) {
                @Override public String map(String name) { return types.getOrDefault(name, name); }
            }));
            previewAdapter = new ClassLoader(MinecraftCapeFallbackProbe.class.getClassLoader()) {
                Class<?> link() { byte[] bytes = writer.toByteArray(); return defineClass(generated.replace('/', '.'), bytes, 0, bytes.length); }
            }.link();
        }
        Location body, cape, wings;
        if (legacyPreview) {
            Object receiver = previewAdapter.getConstructor().newInstance();
            invoke(previewAdapter, receiver, "setAppearance", appearance.getClass(), appearance);
            body = previewLocation(receiver, "previewSkin");
            cape = previewLocation(receiver, "previewCape");
            wings = previewLocation(receiver, "previewElytra");
        } else {
            Object selected = invoke(previewAdapter, null, "playerSkin", appearance.getClass(), appearance);
            if (selected instanceof AssetSkin skin) {
                body = skin.body().texturePath();
                cape = skin.cape() == null ? null : skin.cape().texturePath();
                wings = nativeElytra(skin);
            } else {
                Skin skin = (Skin) selected;
                body = skin.texture();
                cape = skin.capeTexture();
                wings = skin.elytraTexture();
            }
        }
        var mode = appearance.capeMode();
        var selected = new Location(appearance.cape().orElseThrow().location());
        check(body.equals(new Location(appearance.skin().location())), "actual ordinary adapter retains confirmed CPM body texture");
        check(java.util.Objects.equals(cape, mode == com.naocraftlab.skins.client.PreviewRenderer.CapeMode.CAPE ? selected : null),
                "actual ordinary adapter installs only prepared Sneaky cape");
        if (mode == com.naocraftlab.skins.client.PreviewRenderer.CapeMode.ELYTRA && appearance.capeHasElytra()) {
            check(selected.equals(wings), "actual ordinary adapter installs prepared Sneaky wings");
        } else if (mode == com.naocraftlab.skins.client.PreviewRenderer.CapeMode.ELYTRA) {
            check(wings == null || wings.toString().equals("minecraft:textures/entity/elytra.png")
                    || wings.toString().equals("minecraft:textures/entity/equipment/wings/elytra.png"),
                    "actual adapter/native selector uses default elytra, never lower CPM sheet");
            check(!selected.equals(wings), "cape-only Sneaky atlas is not an elytra texture");
        }
    }

    private static ClassNode readClass(String owner) throws Exception {
        ClassNode node = new ClassNode();
        var compiled = java.util.Collections.list(MinecraftCapeFallbackProbe.class.getClassLoader().getResources(owner + ".class"))
                .stream().filter(resource -> resource.getProtocol().equals("file")).toList();
        check(compiled.size() == 1, "exact compiled preview adapter before distributable remapping " + owner);
        try (var input = compiled.get(0).openStream()) {
            new ClassReader(input).accept(node, 0);
        }
        return node;
    }

    private static Location previewLocation(Object receiver, String name) throws Exception {
        var field = previewAdapter.getDeclaredField(name);
        field.setAccessible(true);
        return (Location) field.get(receiver);
    }

    private static Location nativeElytra(AssetSkin skin) throws Exception {
        if (nativeWings == null) {
            String owner = "net/minecraft/client/renderer/entity/layers/WingsLayer";
            String generated = "com/naocraftlab/skins/buildlogic/probe/LinkedNativeWingsSelector";
            ClassNode node = new ClassNode();
            try (var input = MinecraftCapeFallbackProbe.class.getClassLoader().getResourceAsStream(owner + ".class")) {
                if (input == null) throw new AssertionError("missing native WingsLayer dependency");
                new ClassReader(input).accept(node, 0);
            }
            node.methods.removeIf(method -> !method.name.equals("getPlayerElytraTexture"));
            check(node.methods.size() == 1, "exact native wings selector");
            node.fields.clear();
            node.interfaces.clear();
            node.superName = "java/lang/Object";
            node.access = Opcodes.ACC_PUBLIC;
            Map<String, String> types = new HashMap<>();
            types.put(owner, generated);
            map(types, "net/minecraft/client/renderer/entity/state/HumanoidRenderState", Humanoid.class);
            map(types, "net/minecraft/client/renderer/entity/state/AvatarRenderState", Avatar.class);
            map(types, "net/minecraft/world/entity/player/PlayerSkin", AssetSkin.class);
            map(types, "net/minecraft/core/ClientAsset$Texture", Texture.class);
            map(types, "net/minecraft/resources/Identifier", Location.class);
            ClassWriter writer = new ClassWriter(0);
            node.accept(new ClassRemapper(writer, new Remapper(Opcodes.ASM9) {
                @Override public String map(String name) { return types.getOrDefault(name, name); }
            }));
            Class<?> linked = new ClassLoader(MinecraftCapeFallbackProbe.class.getClassLoader()) {
                Class<?> link() { byte[] bytes = writer.toByteArray(); return defineClass(generated.replace('/', '.'), bytes, 0, bytes.length); }
            }.link();
            nativeWings = linked.getDeclaredMethod("getPlayerElytraTexture", Humanoid.class);
            nativeWings.setAccessible(true);
        }
        Avatar avatar = new Avatar();
        avatar.skin = skin;
        avatar.showCape = true;
        return (Location) nativeWings.invoke(null, avatar);
    }

    private static Class<?> link(String family) throws Exception {
        String base = "com/naocraftlab/skins/compat/client/" +
                (family.equals("identifier") ? family : "resourcelocation/" + family);
        String owner = base + "/mixin/PlayerInfoProviderMixin";
        String generated = "com/naocraftlab/skins/buildlogic/probe/LinkedMinecraftCapeAdapter";
        Map<String, String> types = new HashMap<>();
        types.put(owner, generated);
        map(types, "com/mojang/authlib/GameProfile", Profile.class);
        map(types, "com/mojang/authlib/minecraft/MinecraftProfileTexture$Type", TextureType.class);
        map(types, "net/minecraft/client/Minecraft", Client.class);
        map(types, "net/minecraft/client/User", User.class);
        map(types, "net/minecraft/client/multiplayer/PlayerInfo", Info.class);
        map(types, "net/minecraft/resources/ResourceLocation", Location.class);
        map(types, "net/minecraft/resources/Identifier", Location.class);
        map(types, "net/minecraft/client/resources/PlayerSkin", Skin.class);
        map(types, "net/minecraft/client/resources/PlayerSkin$Model", Model.class);
        map(types, "net/minecraft/world/entity/player/PlayerSkin", AssetSkin.class);
        map(types, "net/minecraft/world/entity/player/PlayerModelType", Model.class);
        map(types, "net/minecraft/client/resources/DefaultPlayerSkin", Defaults.class);
        map(types, "net/minecraft/core/ClientAsset$Texture", Texture.class);
        map(types, "net/minecraft/core/ClientAsset$ResourceTexture", ResourceTexture.class);
        map(types, base + "/MinecraftProviderVisibility", Visibility.class);
        ClassNode node = new ClassNode();
        var compiled = java.util.Collections.list(MinecraftCapeFallbackProbe.class.getClassLoader().getResources(owner + ".class"))
                .stream().filter(resource -> resource.getProtocol().equals("file")).toList();
        check(compiled.size() == 1, "exact compiled appearance adapter before distributable remapping");
        try (var input = compiled.get(0).openStream()) {
            new ClassReader(input).accept(node, 0);
        }
        Set<String> methods = Set.of("nclskins$visible", "nclskins$location", "nclskins$texture",
                "nclskins$officialCape", "nclskins$resolvedCape", "nclskins$local",
                "nclskins$getCapeLocation", "nclskins$getElytraLocation", "nclskins$getSkinLocation");
        node.methods.removeIf(method -> !methods.contains(method.name));
        node.fields.removeIf(field -> !field.name.equals("textureLocations"));
        node.visibleAnnotations = null;
        node.invisibleAnnotations = null;
        node.interfaces.clear();
        node.superName = Info.class.getName().replace('.', '/');
        node.access = Opcodes.ACC_PUBLIC;
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, node.superName, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        node.methods.add(constructor);
        ClassWriter writer = new ClassWriter(0);
        node.accept(new ClassRemapper(writer, new Remapper(Opcodes.ASM9) {
            @Override public String map(String name) { return types.getOrDefault(name, name); }
        }));
        Class<?> adapter = new ClassLoader(MinecraftCapeFallbackProbe.class.getClassLoader()) {
            Class<?> link() { byte[] bytes = writer.toByteArray(); return defineClass(generated.replace('/', '.'), bytes, 0, bytes.length); }
        }.link();
        return adapter;
    }

    public static void main(String[] args) throws Exception {
        String family = args[0];
        Class<?> adapter = link(family);
        Info info = (Info) adapter.getConstructor().newInstance();
        try (var registration = CapeProjection.installEvents(new Events())) {
            registration.publish(CapeProjection.Snapshot.empty());
            for (Location elytra : new Location[]{null, ELYTRA}) {
                Location actual = project(adapter, info, family, CAPE, elytra);
                check((elytra == null ? CAPE : ELYTRA).equals(actual),
                        "remote Minecraft CAPE-only must retain official texture for ELYTRA, not default material");
                var material = CapeProjection.material(REMOTE, "Fixture", false);
                check(material.provider() == BuiltinProvider.MINECRAFT && material.hasElytra(), "authoritative Minecraft bundle has elytra fallback");
                check(material.capeLocation().equals(CAPE.toString()), "cape material unchanged");
                check(material.elytraLocation().equals(actual.toString()), "auxiliary material reads authoritative elytra");
                check(actual.equals(project(adapter, info, family, CAPE, elytra)), "repeat native lookup retains texture");
            }
            check(project(adapter, info, family, null, null) == null, "authoritative empty clears Minecraft texture");
            var lower = new CapeProjection.Candidate("fixture:lower", null, false);
            var identity = new CapeProjection.Identity(REMOTE, "Fixture");
            registration.publish(new CapeProjection.Snapshot(List.of(BuiltinProvider.MINECRAFT, BuiltinProvider.OPTIFINE),
                    Map.of(identity, lower), Map.of(), null, null, null));
            Location fallback = project(adapter, info, family, null, null);
            check(fallback != null && fallback.toString().endsWith("elytra.png"), "ordinary cape without elytra layout retains default wings");
            check(CAPE.equals(project(adapter, info, family, CAPE, null)), "Minecraft returns over lower fallback");
            info.profile = new Profile(SELF, "self");
            var own = new CapeProjection.Candidate("fixture:self-cape", "fixture:self-elytra", true);
            registration.publish(new CapeProjection.Snapshot(List.of(BuiltinProvider.MINECRAFT), Map.of(), Map.of(),
                    new CapeProjection.Identity(SELF, "self"), null, own));
            check(new Location("fixture:self-elytra").equals(project(adapter, info, family, CAPE, ELYTRA)), "self remains snapshot-owned");
        }
        System.out.println("Compiled production Minecraft cape adapter passed: " + family + "; synthetic host ABI, not native rendering");
    }

    static void lifecycle(com.tom.cpm.shared.definition.ModelDefinitionLoader<UUID> loader, Runnable drain, java.io.File fixtures) throws Exception {
        String family = "identifier";
        for (String candidate : List.of("playerinfo", "skinlookup")) {
            if (MinecraftCapeFallbackProbe.class.getClassLoader().getResource("com/naocraftlab/skins/compat/client/resourcelocation/" + candidate + "/mixin/PlayerInfoProviderMixin.class") != null) family = candidate;
        }
        String selectedFamily = family;
        Class<?> adapter = link(family);
        Info info = (Info) adapter.getConstructor().newInstance();
        CapeProjection.skinLookup(id -> {});
        try (var registration = CapeProjection.installEvents(new Events())) {
            drain.run();
            ((com.naocraftlab.skins.compat.cpm.CpmModelCacheAccess) loader).nclskins$refresh(REMOTE, null);
            var before = loader.loadPlayer(REMOTE, com.tom.cpm.shared.definition.ModelDefinitionLoader.PLAYER_UNIQUE);
            check(com.naocraftlab.skins.compat.cpm.CpmIntegration.source(before) == null, "loader begins before native source registration");
            before.getDefinitionFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            drain.run();
            CapeProjection.skinLookup(id -> {
                if (id.equals(REMOTE)) {
                    try { project(adapter, info, selectedFamily, CAPE, null); }
                    catch (Exception failure) { throw new AssertionError("native lookup failed", failure); }
                }
            });
            check(CAPE.equals(project(adapter, info, family, CAPE, null)), "native registration extracts Minecraft fallback");
            var source = CapeProjection.skinSource(REMOTE);
            check(source != null, "production native lookup registers source");
            drain.run();
            var player = loader.loadPlayer(REMOTE, com.tom.cpm.shared.definition.ModelDefinitionLoader.PLAYER_UNIQUE);
            check(player != before && com.naocraftlab.skins.compat.cpm.CpmIntegration.source(player) == source,
                    "actual cache refresh evicts source-less player and repeated native lookup tags new player");
            player.getDefinitionFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            drain.run();
            com.tom.cpm.shared.definition.ModelDefinition definition;
            try (var input = new java.io.FileInputStream(new java.io.File(fixtures, "accessory-geometry-sheet.png"))) {
                definition = loader.loadModel(com.tom.cpl.util.Image.loadFrom(input), player);
            }
            definition.resolveAll();
            player.setModelDefinition(java.util.concurrent.CompletableFuture.completedFuture(definition), false);
            player.getModelDefinition();
            Object body = new Object(), accessory = new Object();
            var manager = new com.tom.cpm.shared.model.render.ModelRenderManager<Object, Object, Object, Object>() {
                { for (Object model : List.of(body, accessory)) holders.put(model, new RedirectHolder<>(this, model) {
                    protected void swapIn0() {}
                    protected void swapOut0() {}
                    protected void bindTexture(Object input, com.tom.cpm.shared.skin.TextureProvider sheet) {
                        ((Location[]) input)[0] = new Location("fixture:cpm-sheet");
                    }
                }); }
            };
            manager.bindModel(body, null, null, definition, player, new com.tom.cpm.shared.animation.AnimationState());
            manager.bindSubModel(body, accessory, null);
            for (var order : List.of(List.of(BuiltinProvider.CPM, BuiltinProvider.MINECRAFT),
                    List.of(BuiltinProvider.MINECRAFT, BuiltinProvider.CPM), List.of(BuiltinProvider.MINECRAFT),
                    List.of(BuiltinProvider.CPM, BuiltinProvider.MINECRAFT))) {
                registration.publish(new CapeProjection.Snapshot(order, Map.of(), Map.of(), Map.of(), Map.of(),
                        registration.cpmCandidates(), null, null, null));
                Location material = project(adapter, info, family, CAPE, null);
                boolean cpm = order.get(0) == BuiltinProvider.CPM;
                check(cpm ? material == null : CAPE.equals(material), "native material follows observer order after actual refresh");
                check(com.naocraftlab.skins.compat.cpm.CpmIntegration.accessories(player, definition) == cpm,
                        "actual CPM gate agrees with native material selection");
                check(CapeProjection.cpmWins(source) == cpm, "read-only material lookup preserves authoritative inputs");
                for (var sheet : List.of(com.tom.cpm.shared.model.TextureSheetType.CAPE, com.tom.cpm.shared.model.TextureSheetType.ELYTRA)) {
                    Location[] binding = {cpm ? new Location("fixture:default") : material};
                    manager.bindSkin(accessory, binding, sheet);
                    check((cpm ? new Location("fixture:cpm-sheet") : CAPE).equals(binding[0]),
                            "actual transformed CPM sheet binding preserves selected Minecraft material when suppressed");
                }
                check(manager.getBoundPlayer(body, null).definition == definition, "main body binding remains intact");
                drain.run();
                check(loader.loadPlayer(REMOTE, com.tom.cpm.shared.definition.ModelDefinitionLoader.PLAYER_UNIQUE) == player,
                        "unchanged native source retains current cached player");
                check(player.getModelDefinition0() == definition, "provider order preserves body definition");
            }
            registration.publish(new CapeProjection.Snapshot(List.of(BuiltinProvider.MINECRAFT, BuiltinProvider.CPM),
                    Map.of(), Map.of(), Map.of(), Map.of(), registration.cpmCandidates(), null, null, null));
            check(project(adapter, info, family, null, null) == null && CapeProjection.cpmWins(source),
                    "authoritative empty permits lower CPM fallback without stale Minecraft texture");
            manager.unbindModel(accessory);
            manager.unbindModel(body);
            definition.cleanup();
        } finally { CapeProjection.skinLookup(id -> {}); drain.run(); }
        System.out.println("Minecraft fallback lifecycle passed: compiled native producer, actual CPM cache refresh/relookup, observer transitions and body preservation");
    }

    private static Location project(Class<?> adapter, Info info, String family, Location cape, Location elytra) throws Exception {
        if (family.equals("playerinfo")) {
            var field = adapter.getDeclaredField("textureLocations");
            field.setAccessible(true);
            Map<TextureType, Location> values = new HashMap<>();
            if (cape != null) values.put(TextureType.CAPE, cape);
            if (elytra != null) values.put(TextureType.ELYTRA, elytra);
            field.set(info, values);
            check(BODY.equals(invoke(adapter, info, "nclskins$getSkinLocation", Location.class, BODY)), "skin remains unchanged");
            Location selectedCape = (Location) invoke(adapter, info, "nclskins$getCapeLocation", Location.class, cape);
            check(java.util.Objects.equals(capeFor(info), selectedCape), "cape follows selected bundle");
            return (Location) invoke(adapter, info, "nclskins$getElytraLocation", Location.class, elytra);
        }
        if (family.equals("skinlookup")) {
            Skin original = new Skin(BODY, null, cape, elytra, Model.WIDE, true);
            Skin selected = (Skin) invoke(adapter, info, "nclskins$visible", Skin.class, original);
            check(selected.texture().equals(BODY) && selected.model() == Model.WIDE && selected.secure(), "body model and security unchanged");
            check(java.util.Objects.equals(capeFor(info), selected.capeTexture()), "cape follows selected bundle");
            return selected.elytraTexture();
        }
        AssetSkin original = new AssetSkin(texture(BODY), texture(cape), texture(elytra), Model.WIDE, true);
        AssetSkin result = (AssetSkin) invoke(adapter, info, "nclskins$visible", AssetSkin.class, original);
        check(result.body().texturePath().equals(BODY) && result.model() == Model.WIDE && result.secure(), "body model and security unchanged");
        check(java.util.Objects.equals(capeFor(info), result.cape() == null ? null : result.cape().texturePath()), "cape follows selected bundle");
        check(java.util.Objects.equals(result.elytra() == null ? null : result.elytra().texturePath(), nativeElytra(result)), "actual native wings selector retains projected texture");
        return nativeElytra(result);
    }

    private static Location capeFor(Info info) {
        return Location.tryParse(CapeProjection.material(info.profile.id(), info.profile.name(), SELF.equals(info.profile.id())).capeLocation());
    }

    private static Object invoke(Class<?> owner, Object instance, String name, Class<?> parameter, Object value) throws Exception {
        Method method = owner.getDeclaredMethod(name, parameter);
        method.setAccessible(true);
        return method.invoke(instance, value);
    }
    private static Texture texture(Location location) { return location == null ? null : new ResourceTexture(location, location); }
    private static void map(Map<String, String> types, String from, Class<?> to) { types.put(from, to.getName().replace('.', '/')); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public record Location(String value) {
        public Location(String namespace, String path) { this(namespace + ":" + path); }
        public static Location tryParse(String value) { return value == null ? null : new Location(value); }
        public static Location parse(String value) { return tryParse(value); }
        @Override public String toString() { return value; }
    }
    public record Profile(UUID id, String name) {
        public UUID getId() { return id; }
        public String getName() { return name; }
    }
    public static class Info {
        public Profile profile = new Profile(REMOTE, "Fixture");
        public Profile getProfile() { return profile; }
        public String getModelName() { return "default"; }
    }
    public static final class Client {
        public static Client getInstance() { return new Client(); }
        public boolean isLocalPlayer(UUID id) { return SELF.equals(id); }
        public User getUser() { return new User(); }
    }
    public static final class User { public UUID getProfileId() { return SELF; } }
    public enum TextureType { SKIN, CAPE, ELYTRA }
    public enum Model { WIDE, SLIM }
    public record Skin(Location texture, String textureUrl, Location capeTexture, Location elytraTexture, Model model, boolean secure) {}
    public interface Texture { Location texturePath(); }
    public record ResourceTexture(Location id, Location texturePath) implements Texture {}
    public record AssetSkin(Texture body, Texture cape, Texture elytra, Model model, boolean secure) {
        public static AssetSkin insecure(Texture body, Texture cape, Texture elytra, Model model) {
            return new AssetSkin(body, cape, elytra, model, false);
        }
    }
    public static final class Visibility {
        public static com.naocraftlab.skins.client.ProviderVisibility current() {
            return com.naocraftlab.skins.client.ProviderVisibility.ALL;
        }
    }
    public static class Humanoid {}
    public static final class Avatar extends Humanoid {
        public AssetSkin skin;
        public boolean showCape;
    }
    public static final class Defaults {}
    private static final class Events implements CapeProjection.Events {
        public void trackedPlayer(UUID id, String name) {}
        public void playerInfoUpdated(UUID id, String name) {}
        public void untrackedPlayer(UUID id) {}
        public void worldChanged() {}
        public void worldEntered() {}
    }
}

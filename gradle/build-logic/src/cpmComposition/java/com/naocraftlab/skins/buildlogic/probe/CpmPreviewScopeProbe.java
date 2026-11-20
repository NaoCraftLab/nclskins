package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

public final class CpmPreviewScopeProbe {
    private static boolean imageJoin;
    static boolean loadingCanonical;
    static void cpmImage(PreviewRenderer.PreviewAppearance appearance,com.naocraftlab.skins.runtime.GalleryNativePassFixture gallery) throws Exception {
        imageJoin=true;
        try { bodyProducer(appearance,() -> {},() -> {},gallery); }
        finally { imageJoin=false; }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("provider-retention")) {
            var pixels = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var bytes = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(pixels, "PNG", bytes);
            var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:policy", 64, 64),
                    SkinModel.CLASSIC, Optional.empty(), PreviewRenderer.CapeMode.OFF, OuterLayerVisibility.allVisible());
            try (var fixture = new com.naocraftlab.skins.runtime.GalleryNativePassFixture(appearance, bytes.toByteArray())) {
                providerRetention = true;
                try { bodyProducer(appearance, () -> {}, () -> {}, fixture); }
                finally { providerRetention = false; }
            }
            return;
        }
        String owner = "com.naocraftlab.skins.compat.client.resourcelocation." + args[0] + ".PreviewScope";
        Map<String, Class<?>> types = Map.ofEntries(
                Map.entry("net/minecraft/client/Minecraft", Client.class),
                Map.entry("net/minecraft/client/multiplayer/ClientLevel", Level.class),
                Map.entry("net/minecraft/world/level/Level", Level.class),
                Map.entry("net/minecraft/client/player/AbstractClientPlayer", Avatar.class),
                Map.entry("net/minecraft/world/entity/player/Player", Player.class),
                Map.entry("net/minecraft/world/entity/LivingEntity", Entity.class),
                Map.entry("net/minecraft/world/entity/EquipmentSlot", Slot.class),
                Map.entry("net/minecraft/world/entity/player/PlayerModelPart", Part.class),
                Map.entry("net/minecraft/world/item/ItemStack", Stack.class),
                Map.entry("net/minecraft/world/item/Item", Item.class),
                Map.entry("net/minecraft/world/level/ItemLike", ItemLike.class),
                Map.entry("net/minecraft/world/item/Items", Items.class),
                Map.entry("net/minecraft/resources/ResourceLocation", Location.class),
                Map.entry("net/minecraft/client/resources/PlayerSkin", Skin.class),
                Map.entry("net/minecraft/client/resources/PlayerSkin$Model", Skin.Model.class));
        ClassLoader loader = new ClassLoader(CpmPreviewScopeProbe.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(owner) && !name.startsWith(owner + "$")) return super.loadClass(name, resolve);
                Class<?> result = findLoadedClass(name);
                if (result == null) {
                    try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        var writer = new ClassWriter(0);
                        new ClassReader(input).accept(new ClassRemapper(writer, terminalNames(types)), 0);
                        byte[] bytes = writer.toByteArray();
                        result = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
                }
                if (resolve) resolveClass(result);
                return result;
            }
        };
        Class<?> scope = loader.loadClass(owner);
        var open = scope.getMethod("open", Client.class, Avatar.class, PreviewRenderer.PreviewAppearance.class);
        var part = scope.getMethod("modelPart", Player.class, Part.class);
        var equipment = scope.getMethod("equipment", Entity.class, Slot.class, Stack.class);
        Client client = new Client();
        Avatar player = new Avatar(client.level);
        Avatar other = new Avatar(client.level);
        var skin = new TextureRegistry.TextureHandle("fixture:preview", 64, 64);
        for (boolean nativeBundle : new boolean[]{true, false}) {
            for (var mode : PreviewRenderer.CapeMode.values()) {
                var appearance = new PreviewRenderer.PreviewAppearance(skin, SkinModel.CLASSIC,
                        nativeBundle || mode == PreviewRenderer.CapeMode.OFF ? Optional.empty() : Optional.of(skin),
                        mode, OuterLayerVisibility.allVisible(), true, false, nativeBundle);
                try (var ignored = (AutoCloseable) open.invoke(null, client, player, appearance)) {
                    check(Boolean.valueOf(mode == PreviewRenderer.CapeMode.CAPE)
                            .equals(part.invoke(null, player, Part.CAPE)), "production scope preserves native CAPE visibility without PNG");
                    Stack chest = (Stack) equipment.invoke(null, player, Slot.CHEST, Stack.EMPTY);
                    check((chest.item == Items.ELYTRA) == (mode == PreviewRenderer.CapeMode.ELYTRA),
                            "production scope preserves native ELYTRA equipment without PNG");
                    check(part.invoke(null, other, Part.CAPE) == null, "scope does not affect another player");
                    check(equipment.invoke(null, other, Slot.CHEST, Stack.EMPTY) == Stack.EMPTY, "other equipment unchanged");
                }
                check(part.invoke(null, player, Part.CAPE) == null, "scope closes");
            }
        }
        for (boolean wings : new boolean[]{true, false}) {
            var prepared = CpmMaterialBoundaryProbe.preparedSneaky(wings);
            var atlas = new TextureRegistry.TextureHandle("fixture:sneaky_" + prepared.renderSha256(), 64, 32);
            for (var mode : new PreviewRenderer.CapeMode[]{PreviewRenderer.CapeMode.CAPE, PreviewRenderer.CapeMode.ELYTRA}) {
                var appearance = new PreviewRenderer.PreviewAppearance(skin, SkinModel.CLASSIC,
                        Optional.of(atlas), mode, OuterLayerVisibility.allVisible(), prepared.hasElytra(), false, false);
                try (var ignored = (AutoCloseable) open.invoke(null, client, player, appearance)) {
                    Location cape, elytra;
                    if (args[0].equals("skinlookup")) {
                        var lookup = scope.getMethod("skin", Avatar.class, Skin.class);
                        var original = new Skin(new Location("fixture:old-body"), "",
                                new Location("fixture:old-cpm-cape"), new Location("fixture:old-cpm-wings"), Skin.Model.WIDE, false);
                        Skin selected = (Skin) lookup.invoke(null, player, original);
                        check(selected.texture().equals(new Location(skin.location())), "Sneaky scope preserves selected BODY");
                        cape = selected.capeTexture();
                        elytra = selected.elytraTexture();
                        check(lookup.invoke(null, other, original) == original, "Sneaky texture scope isolates another player");
                    } else {
                        cape = (Location) scope.getMethod("cape", Avatar.class, Location.class)
                                .invoke(null, player, new Location("fixture:old-cpm-cape"));
                        elytra = (Location) scope.getMethod("elytra", Avatar.class, Location.class)
                                .invoke(null, player, new Location("fixture:old-cpm-wings"));
                    }
                    check(java.util.Objects.equals(cape, mode == PreviewRenderer.CapeMode.CAPE
                            ? new Location(atlas.location()) : null), "Sneaky scope never leaks previous CPM cape");
                    Location expectedWings = mode != PreviewRenderer.CapeMode.ELYTRA ? null
                            : wings ? new Location(atlas.location()) : args[0].equals("skinlookup") ? null
                            : new Location("minecraft:textures/entity/elytra.png");
                    check(java.util.Objects.equals(elytra, expectedWings),
                            "cape-only Sneaky must use native default elytra in actual PreviewScope, not atlas or previous CPM sheet: "
                                    + args[0] + " expected=" + expectedWings + " actual=" + elytra);
                    Stack chest = (Stack) equipment.invoke(null, player, Slot.CHEST, Stack.EMPTY);
                    check((chest.item == Items.ELYTRA) == (mode == PreviewRenderer.CapeMode.ELYTRA),
                            "cape-only Sneaky keeps default elytra equipment visible");
                }
                check(part.invoke(null, player, Part.CAPE) == null, "Sneaky scope closes before next selection");
            }
        }
        CpmBakedDisplayProbe.immediateMenu(args[0]);
        System.out.println("Actual production PreviewScope methods passed: native/Sneaky bundle, exact atlas, default elytra, isolation and cleanup; synthetic host ABI, not native game rendering");
    }

    private static boolean providerRetention;

    static void bodyProducer(PreviewRenderer.PreviewAppearance appearance, Runnable binding, Runnable cleanupFailure,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture gallery) throws Exception {
        String family = CpmPreviewScopeProbe.class.getClassLoader().getResource(
                "com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/RemotePlayerPreviewRenderer.class") != null
                ? "playerinfo" : "skinlookup";
        String prefix = "com.naocraftlab.skins.compat.client.resourcelocation." + family + ".";
        String owner = prefix + (family.equals("playerinfo") ? "RemotePlayerPreviewRenderer" : "VanillaAppearancePreviewRenderer");
        if (CpmPreviewScopeProbe.class.getClassLoader().getResource(owner.replace('.', '/') + ".class") == null) return;
        var types = new java.util.HashMap<String, Class<?>>();
        types.put("net/minecraft/client/Minecraft", Client.class);
        types.put("net/minecraft/client/multiplayer/ClientLevel", Level.class);
        types.put("net/minecraft/world/level/Level", Level.class);
        types.put("net/minecraft/client/player/AbstractClientPlayer", Avatar.class);
        types.put("net/minecraft/client/player/RemotePlayer", Avatar.class);
        types.put("net/minecraft/client/player/LocalPlayer", Avatar.class);
        types.put(owner.replace('.', '/') + "$PreviewPlayer", Avatar.class);
        types.put("net/minecraft/world/entity/player/Player", Player.class);
        types.put("net/minecraft/world/entity/LivingEntity", Entity.class);
        types.put("net/minecraft/world/entity/EquipmentSlot", Slot.class);
        types.put("net/minecraft/world/entity/player/PlayerModelPart", Part.class);
        types.put("net/minecraft/world/item/ItemStack", Stack.class);
        types.put("net/minecraft/world/item/Item", Item.class);
        types.put("net/minecraft/world/level/ItemLike", ItemLike.class);
        types.put("net/minecraft/world/item/Items", Items.class);
        types.put("net/minecraft/resources/ResourceLocation", Location.class);
        types.put("net/minecraft/client/resources/PlayerSkin", Skin.class);
        types.put("net/minecraft/client/resources/PlayerSkin$Model", Skin.Model.class);
        types.put("net/minecraft/client/gui/GuiGraphics", Graphics.class);
        types.put("com/mojang/blaze3d/vertex/PoseStack", Pose.class);
        types.put("com/mojang/blaze3d/vertex/PoseStack$Pose", Pose.Entry.class);
        types.put("net/minecraft/client/gui/screens/inventory/InventoryScreen", Inventory.class);
        types.put("net/minecraft/client/model/PlayerModel", CpmBakedDisplayProbe.Model.class);
        types.put("net/minecraft/client/model/ElytraModel", CpmBakedDisplayProbe.Model.class);
        types.put("net/minecraft/client/model/geom/ModelPart", CpmBakedDisplayProbe.Part.class);
        types.put("net/minecraft/client/renderer/RenderType", CpmBakedDisplayProbe.RenderType.class);
        types.put("net/minecraft/client/renderer/MultiBufferSource$BufferSource", CpmBakedDisplayProbe.Buffers.class);
        types.put("net/minecraft/client/renderer/texture/OverlayTexture", CpmBakedDisplayProbe.Overlay.class);
        types.put("com/mojang/blaze3d/vertex/VertexConsumer", CpmBakedDisplayProbe.Vertex.class);
        types.put("com/mojang/blaze3d/platform/Lighting", CpmBakedDisplayProbe.ImmediateLighting.class);
        types.put("com/mojang/blaze3d/systems/RenderSystem", CpmBakedDisplayProbe.ImmediateLighting.class);
        var loader = new ClassLoader(CpmPreviewScopeProbe.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(owner) && !name.equals(owner + "$NativeSlot") && !name.startsWith(prefix + "PreviewScope")
                        && !name.startsWith(prefix + "BakedPlayerPose") && !name.startsWith(prefix + "BakedPreviewLighting")) return super.loadClass(name, resolve);
                Class<?> result = findLoadedClass(name);
                if (result == null) {
                    try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        var node = new org.objectweb.asm.tree.ClassNode();
                        new ClassReader(input).accept(node, 0);
                        if (name.equals(owner)) {
                            node.methods.removeIf(method -> !java.util.Set.of("render", "renderBody", "body", "prepareBody", "renderPrepared", "prepareImage", "prepareGalleryImage", "observeBody", "close",
                                    "renderFallback", "location", "parseTexture", "configureLayers", "saveGallerySlot", "selectGallerySlot", "selectGallerySession",
                                    "retireGallerySlots", "prepareGalleryBody", "confirmGalleryBody", "renderGallery", "renderRetained", "retainedTextures", "galleryAccessories", "observeAccessories", "clearAccessoryInspection", "accessoryObserverFor").contains(method.name)
                                    && !method.name.startsWith("lambda$"));
                            var init = new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                            init.visitVarInsn(Opcodes.ALOAD, 0);
                            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                            init.visitInsn(Opcodes.RETURN);
                            init.visitMaxs(1, 1);
                            node.methods.add(init);
                            String self = owner.replace('.', '/');
                            String avatar = "L" + self + "$PreviewPlayer;";
                            for (String helper : java.util.List.of("configureEntity", "renderBackEquipment", "onLiveLayerFailure")) {
                                String descriptor = helper.equals("configureEntity") ? "(" + avatar + "Lcom/naocraftlab/skins/client/PreviewRenderer$PreviewRequest;)V"
                                        : helper.equals("renderBackEquipment") ? "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"
                                                + (family.equals("playerinfo") ? "Lnet/minecraft/client/model/PlayerModel;" : "")
                                                + "Lnet/minecraft/client/model/geom/ModelPart;Lcom/naocraftlab/skins/client/PreviewRenderer$CapeMode;ZLnet/minecraft/resources/ResourceLocation;)V"
                                        : helper.equals("onLiveLayerFailure") ? "(Ljava/lang/RuntimeException;)V" : "()V";
                                var stub = new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PRIVATE, helper, descriptor, null, null);
                                stub.visitInsn(Opcodes.RETURN); stub.visitMaxs(0, 8); node.methods.add(stub);
                            }
                            var proxy = new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PRIVATE, "previewPlayer",
                                    "(Lcom/naocraftlab/skins/client/PreviewRenderer$PreviewAppearance;)" + avatar, null, null);
                            proxy.visitVarInsn(Opcodes.ALOAD, 0); proxy.visitVarInsn(Opcodes.ALOAD, 0);
                            proxy.visitFieldInsn(Opcodes.GETFIELD, self, "minecraft", "Lnet/minecraft/client/Minecraft;");
                            proxy.visitVarInsn(Opcodes.ALOAD, 1);
                            proxy.visitMethodInsn(Opcodes.INVOKESTATIC, CpmPreviewScopeProbe.class.getName().replace('.', '/'), "nativePreviewPlayer",
                                    "(Ljava/lang/Object;L" + Client.class.getName().replace('.', '/') + ";Lcom/naocraftlab/skins/client/PreviewRenderer$PreviewAppearance;)L" + Avatar.class.getName().replace('.', '/') + ";", false);
                            proxy.visitInsn(Opcodes.ARETURN); proxy.visitMaxs(3, 2); node.methods.add(proxy);
                            var accessories = new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC, "accessories",
                                    "(Lcom/naocraftlab/skins/client/PreviewRenderer$PreviewRequest;)Lcom/naocraftlab/skins/client/PreviewAccessories;", null, null);
                            accessories.visitFieldInsn(Opcodes.GETSTATIC, "com/naocraftlab/skins/client/PreviewRenderer$LiveReason", "LIVE_UNAVAILABLE",
                                    "Lcom/naocraftlab/skins/client/PreviewRenderer$LiveReason;");
                            accessories.visitMethodInsn(Opcodes.INVOKESTATIC, "com/naocraftlab/skins/client/PreviewAccessories", "unavailable",
                                    "(Lcom/naocraftlab/skins/client/PreviewRenderer$LiveReason;)Lcom/naocraftlab/skins/client/PreviewAccessories;", false);
                            accessories.visitInsn(Opcodes.ARETURN); accessories.visitMaxs(1, 2); node.methods.add(accessories);
                            node.fields.removeIf(field -> (field.access & Opcodes.ACC_STATIC) != 0 && !field.name.equals("POSE_OPERATIONS"));
                            for (var field : node.fields) if (field.name.equals("POSE_OPERATIONS")) field.access &= ~Opcodes.ACC_FINAL;
                            node.innerClasses.clear();
                            if (node.nestMembers != null) node.nestMembers.removeIf(member -> !member.endsWith("$NativeSlot"));
                        }
                        var writer = new ClassWriter(0);
                        node.accept(new ClassRemapper(writer, terminalNames(types)));
                        byte[] bytes = writer.toByteArray();
                        result = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
                }
                if (resolve) resolveClass(result);
                return result;
            }
        };
        Class<?> renderer = loader.loadClass(owner);
        Object target = renderer.getConstructor().newInstance();
        Client client = new Client();
        client.player = new Avatar(client.level);
        var session = new com.naocraftlab.skins.client.EditorPreviewSession();
        for (var field : renderer.getDeclaredFields()) {
            field.setAccessible(true);
            Object value = switch (field.getName()) {
                case "session" -> session;
                case "gallerySlots" -> new java.util.LinkedHashMap<>();
                case "previewClock" -> new com.naocraftlab.skins.client.EditorPreviewClock();
                case "previewTickGate" -> new com.naocraftlab.skins.client.EditorPreviewTickGate();
                case "bodyObserver" -> (java.util.function.Consumer<PreviewRenderer.BodyStatus>) ignored -> {};
                case "previewProfile" -> Class.forName("com.mojang.authlib.GameProfile").getConstructor(java.util.UUID.class, String.class)
                        .newInstance(java.util.UUID.randomUUID(), "Fixture");
                case "minecraft" -> client;
                case "previewPlayer" -> client.player;
                case "previewLevel" -> client.level;
                case "diagnostics" -> com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding();
                case "accessoryObserver" -> (java.util.function.Consumer<com.naocraftlab.skins.client.PreviewAccessories>) ignored -> {};
                case "classicModel", "slimModel", "classic", "slim" -> new CpmBakedDisplayProbe.Model();
                case "classicCloak", "slimCloak" -> new CpmBakedDisplayProbe.Part();
                case "POSE_OPERATIONS" -> CpmBakedDisplayProbe.immediateOperations();
                default -> null;
            };
            if (value != null) field.set(target, value);
        }
        Inventory.draw = binding;
        try {
            if (providerRetention) {
                try { immediatePolicyHost(renderer, target, client, gallery); }
                finally { ((AutoCloseable)target).close(); }
                return;
            }
            if (Boolean.getBoolean("nclskins.sourceReplacement")) {
                Client.current = client;
                CpmSourceReplacementProbe.Material[] consumed = {null};
                var sourceProfile = nativeProfile(target);
                Inventory.draw = () -> {
                    var source = com.naocraftlab.skins.runtime.CapeProjection.skinSource(sourceProfile);
                    check(source != null, "SOURCE1 immediate consumer has actual selected source");
                    consumed[0] = new CpmSourceReplacementProbe.Material(source.location(), source.model());
                };
                try {
                    CpmSourceReplacementProbe.run(target, new Graphics(), gallery, () -> consumed[0] = null, () -> {},
                            () -> Inventory.x < 0, () -> consumed[0],
                            () -> { client.level = new Level(); client.player = new Avatar(client.level); },
                            () -> client.player = new Avatar(client.level));
                } finally { ((AutoCloseable) target).close(); Client.current = null; }
                return;
            }
            if(loadingCanonical) { try(var owned=gallery.freshOwned()) { immediateRootFrame(renderer,target,owned); } return; }
            if(imageJoin) { immediateCpmImage(renderer,target,gallery);return; }
            var render = renderer.getMethod("render", Graphics.class, PreviewRenderer.PreviewRequest.class);
            var request = new PreviewRenderer.PreviewRequest(appearance, 0, 0, 160, 180, 0, 0, 1, PreviewRenderer.PreviewIntent.EDITOR_DRAFT);
            render.invoke(target, new Graphics(), request);
            var body = renderer.getMethod("body");
            check(((com.naocraftlab.skins.client.PreviewModelFrames.Body) body.invoke(target)).status() == PreviewRenderer.BodyStatus.READY,
                    "actual immediate render -> genuine bind -> scope readback -> post-cleanup session display");
            var texture = com.naocraftlab.skins.client.NativePlayerSkinLifecycle.pending(appearance.skin().location());
            texture.ready();
            render.invoke(target, new Graphics(), request);
            var replacement = com.naocraftlab.skins.client.NativePlayerSkinLifecycle.pending(appearance.skin().location());
            replacement.ready();
            check(((PreviewRenderer<?>) target).displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED,
                    "READY-to-READY resource replacement must revoke display before another render");
            render.invoke(target, new Graphics(), request);
            replacement.retire();
            texture.retire();
            check(((com.naocraftlab.skins.client.PreviewModelFrames.Body) body.invoke(target)).status() == PreviewRenderer.BodyStatus.ORDINARY,
                    "resource registration retirement invalidates displayed witness without render callback");
            check(((PreviewRenderer<?>) target).displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED,
                    "Resource retirement is not positive ordinary display");
            Inventory.draw = () -> {};
            render.invoke(target, new Graphics(), request);
            check(((com.naocraftlab.skins.client.PreviewModelFrames.Body) body.invoke(target)).status() == PreviewRenderer.BodyStatus.ORDINARY,
                    "actual ordinary pass clears previously displayed CPM body");
            check(((PreviewRenderer<?>) target).displayKind() == PreviewRenderer.DisplayKind.ORDINARY,
                    "Actual successful ordinary live display must be retained");
            Inventory.draw = binding;
            render.invoke(target, new Graphics(), request);
            var level = client.level;
            client.level = null;
            check(((com.naocraftlab.skins.client.PreviewModelFrames.Body) body.invoke(target)).status() == PreviewRenderer.BodyStatus.ORDINARY,
                    "world loss invalidates actual immediate body without callback");
            check(((PreviewRenderer<?>) target).displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED,
                    "World loss is not positive ordinary display");
            client.level = level;
            render.invoke(target, new Graphics(), request);
            var closed = renderer.getDeclaredField("closed"); closed.setAccessible(true); closed.setBoolean(target, true);
            check(((com.naocraftlab.skins.client.PreviewModelFrames.Body) body.invoke(target)).status() == PreviewRenderer.BodyStatus.ORDINARY,
                    "closed/replaced renderer cannot retain current display");
            check(((PreviewRenderer<?>) target).displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED,
                    "Closed renderer is not positive ordinary display");
            closed.setBoolean(target, false);
            Inventory.draw = () -> {
                binding.run();
                check(com.naocraftlab.skins.client.EditorPreviewLayerGuard.handle(new IllegalStateException("synthetic handled layer failure")),
                        "actual layer failure guard is installed");
            };
            render.invoke(target, new Graphics(), request);
            check(((com.naocraftlab.skins.client.PreviewModelFrames.Body) body.invoke(target)).status() == PreviewRenderer.BodyStatus.ORDINARY,
                    "handled layer failure cannot confirm display");
            check(((PreviewRenderer<?>) target).displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED,
                    "Handled failure must not become ordinary success");
            var sessionField = renderer.getDeclaredField("session"); sessionField.setAccessible(true);
            sessionField.set(target, new com.naocraftlab.skins.client.EditorPreviewSession());
            boolean[] cleanupEntered = {false};
            Inventory.draw = () -> { cleanupEntered[0] = true; cleanupFailure.run(); };
            render.invoke(target, new Graphics(), request);
            check(cleanupEntered[0], "Cleanup negative executes live primary, not circuit-disabled ordinary fallback");
            check(((com.naocraftlab.skins.client.PreviewModelFrames.Body) body.invoke(target)).status() == PreviewRenderer.BodyStatus.ORDINARY,
                    "own native scope cleanup failure cannot confirm display");
            check(((PreviewRenderer<?>) target).displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED,
                    "Failed native cleanup is not positive ordinary display");
            sessionField.set(target, new com.naocraftlab.skins.client.EditorPreviewSession());
            Inventory.draw = () -> { binding.run(); throw new IllegalStateException("synthetic native failure"); };
            render.invoke(target, new Graphics(), request);
            check(((com.naocraftlab.skins.client.PreviewModelFrames.Body) body.invoke(target)).status() == PreviewRenderer.BodyStatus.ORDINARY,
                    "failed actual pass never commits captured binding");
            check(((PreviewRenderer<?>) target).displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED,
                    "Failed actual pass is not positive ordinary display");
            CpmBakedDisplayProbe.immediateFallback(renderer, target, client, appearance);
            closed.setBoolean(target, false);
            check(((PreviewRenderer<?>) target).prepareGalleryBody("fixture:gallery-A", false, () -> true),
                    "actual immediate Gallery opt-in must retain source-specific displayed/candidate bodies");
            immediateGallery(renderer, target, client, appearance, binding, gallery);
        } finally { Inventory.draw = () -> {}; }
        System.out.println("Actual immediate body producer passed: " + family + "; remapped host/GPU terminals, genuine CPM bind and native scope/session path");
    }

    private static Object nativeField(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }

    private static void nativeField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target,value);
    }

    public static Avatar nativePreviewPlayer(Object renderer, Client client, PreviewRenderer.PreviewAppearance appearance) {
        try {
            var profile = nativeField(renderer, "previewProfile");
            java.util.UUID id;
            try { id = (java.util.UUID) profile.getClass().getMethod("getId").invoke(profile); }
            catch (NoSuchMethodException modern) { id = (java.util.UUID) profile.getClass().getMethod("id").invoke(profile); }
            com.naocraftlab.skins.runtime.CapeProjection.effectiveSkin(id, new java.util.UUID(0, imageJoin ? 1 : 801),
                    appearance.skin().location(), appearance.model(), true);
            var previous = (Avatar) nativeField(renderer, "previewPlayer");
            var player = previous != null && previous.level() == client.level ? previous : new Avatar(client.level);
            nativeField(renderer, "previewPlayer", player);
            nativeField(renderer, "previewLevel", client.level);
            return player;
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static void immediateGallery(Class<?> type, Object target, Client client,
            PreviewRenderer.PreviewAppearance appearance, Runnable binding,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture root) throws Exception {
        @SuppressWarnings("unchecked") var renderer = (PreviewRenderer<Graphics>) target;
        client.level = new Level(); client.player = new Avatar(client.level); Client.current = client;
        try (var fixture = root.fresh()) {
            var selected = fixture.preview();
            String a = fixture.runtime.previewBodySource(selected);
            var observer = fixture.observeBody(selected, renderer);
            renderer.observeBody(observer);
            check(renderer.prepareGalleryBody(a,false, fixture.runtime.galleryPreviewAdmission(selected)), "immediate source A admission");
            Inventory.draw = () -> {};
            renderer.renderGallery(new Graphics(), a, fixture.request());
            check(fixture.displayed() == PreviewRenderer.DisplayKind.ORDINARY, "actual immediate ordinary -> Gallery callback");
            fixture.layersVisible(true);
            var before = renderer.body();
            var slots = (Map<?,?>) nativeField(target,"gallerySlots");
            Object aSession = nativeField(target,"session");
            Object aSlot = slots.get(aSession);
            fixture.selectC();
            var desired = fixture.runtime.galleryPreviewCandidate().orElseThrow();
            String b = fixture.runtime.previewBodySource(desired);
            var next = fixture.observeBody(desired, renderer);
            renderer.observeBody(next);
            check(renderer.prepareGalleryBody(b,false, fixture.runtime.galleryPreviewAdmission(desired)), "immediate B preparation");
            check(before.current().getAsBoolean(), "A witness retained while B prepared");
            check(slots.size() == 2 && slots.get(aSession).equals(aSlot), "A native slot not mutated by B preparation");
            check(!renderer.body().current().getAsBoolean(), "prepared B is not its completed pass");
            Inventory.draw = binding;
            renderer.renderGallery(new Graphics(), b, fixture.request());
            check(fixture.displayed() == PreviewRenderer.DisplayKind.CPM, "actual immediate CPM -> selected B callback");
            check(!before.current().getAsBoolean() && slots.size() == 1 && !slots.containsKey(aSession), "completed B retires A");
            fixture.runtime.invalidatePreviewBody(observer);
            check(fixture.displayed() == PreviewRenderer.DisplayKind.CPM, "stale A cleanup cannot revoke B");
            Inventory.draw = () -> {};
            renderer.renderGallery(new Graphics(), b, fixture.request());
            fixture.layersVisible(true);
            check(fixture.displayed() == PreviewRenderer.DisplayKind.ORDINARY, "CPM to ordinary actual own pass");
            fixture.fallback();
            var fallback = fixture.runtime.galleryPreviewCandidate().orElseThrow();
            String worldless = fixture.runtime.previewBodySource(fallback);
            renderer.observeBody(fixture.observeBody(fallback, renderer));
            client.level = null; client.player = null;
            check(renderer.prepareGalleryBody(worldless,false, fixture.runtime.galleryPreviewAdmission(fallback)),"worldless Gallery admission");
            CpmBakedDisplayProbe.flushed = () -> check(renderer.displayKind() != PreviewRenderer.DisplayKind.ORDINARY,
                    "flush cannot publish worldless display early");
            renderer.renderGallery(new Graphics(), worldless, fixture.request());
            CpmBakedDisplayProbe.flushed = () -> {};
            check(fixture.displayed() == PreviewRenderer.DisplayKind.ORDINARY,"worldless actual baked flush -> Gallery observer");
            fixture.layersVisible(true);
            immediateCaller(type, target, fixture);

        } finally { Client.current = null; Inventory.draw = () -> {}; CpmBakedDisplayProbe.flushed = () -> {}; }
        try (var owned = root.freshOwned()) { immediateRootFrame(type,target,owned); }
        try (var subject = root.fresh()) {
            Object owner = reopenedNative(type, target);
            @SuppressWarnings("unchecked") var rebound = (PreviewRenderer<Graphics>) owner;
            Client.current = client; client.level = new Level(); client.player = new Avatar(client.level);
            try {
                var a = subject.runtime.galleryPreviewCandidate().orElseThrow();
                String sourceA = subject.runtime.previewBodySource(a);
                var requestA = subject.request();
                rebound.observeBody(subject.observeBody(a, rebound));
                check(rebound.prepareGalleryBody(sourceA,false,subject.runtime.galleryPreviewAdmission(a)), "N2 immediate A admitted");
                Inventory.draw = () -> {};
                rebound.renderGallery(new Graphics(), sourceA, requestA);
                check(rebound.body().current().getAsBoolean(), "N2 immediate own A native body");
                subject.selectC();
                var b = subject.runtime.galleryPreviewCandidate().orElseThrow();
                String sourceB = subject.runtime.previewBodySource(b);
                var requestB = subject.request();
                rebound.observeBody(subject.observeBody(b, rebound));
                check(rebound.prepareGalleryBody(sourceB,false,subject.runtime.galleryPreviewAdmission(b)), "N2 immediate B admitted");
                int[] publications = {0};
                var inputB = subject.runtime.galleryAccessoryAdmission(b);
                var common = subject.runtime.observePreviewAccessories(b, () -> rebound.galleryAccessories(sourceB,requestB,inputB));
                rebound.observeAccessories(value -> { publications[0]++; common.accept(value); });
                Inventory.draw = () -> { throw new IllegalStateException("synthetic retained immediate A failure"); };
                rebound.renderGallery(new Graphics(), sourceA, requestA);
                check(publications[0] == 0, "N2 immediate retained A cannot notify registered successor B");
                Inventory.draw = () -> {};
                rebound.renderGallery(new Graphics(), sourceB, requestB);
                check(rebound.body().current().getAsBoolean() && subject.displayed() == PreviewRenderer.DisplayKind.ORDINARY,
                        "N2 immediate B owns successful native body after A failure");
                Inventory.draw = () -> { throw new IllegalStateException("synthetic own immediate B failure"); };
                rebound.renderGallery(new Graphics(), sourceB, requestB);
                check(publications[0] > 0, "N2 immediate own B notifies registered B observer");
                System.out.println("PASS N2 immediate prepare/register B -> actual retained A failure -> zero B publications -> own B native body and observer positive");
            } finally { Inventory.draw = () -> {}; ((AutoCloseable) owner).close(); Client.current = null; }
        }
        System.out.println("PASS immediate Gallery actual ordinary/CPM/worldless source A/B isolation, flush, retirement and stale callback");
    }

    private static void immediateCpmImage(Class<?> rendererType,Object target,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture original) throws Exception {
        Client previousClient=Client.current;Client.current=(Client)nativeField(target,"minecraft");
        try(var fixture=original.freshOwnedCpm();var profiles=CpmBehaviorProbe.nativeProfileScope()) {
            var screenType=CpmPreviewScreenSlice.loadNative(CpmPreviewScreenSlice.availableOwner(),rendererType);
            Object screen=screenType.getConstructor().newInstance();
            var registry=new CpmOwnedPreviewDispatchProbe.Registry();
            try(var skins=new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.PLAYER_SKIN,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
                var capes=new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.IMAGE,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
                for(var field:screenType.getDeclaredFields()) {
                    Object value=switch(field.getName()) {
                        case "runtime" -> fixture.runtime;case "skinTextures" -> skins;case "capeTextures" -> capes;
                        case "width" -> 854;case "height" -> 480;
                        case "capabilities" -> new CpmOwnedPreviewDispatchProbe.Capabilities() {
                            @SuppressWarnings({"unchecked","rawtypes"}) public PreviewRenderer<CpmOwnedPreviewDispatchProbe.Graphics> createEditorPreviewRenderer(){return (PreviewRenderer)target;}
                            public PreviewRenderer<CpmOwnedPreviewDispatchProbe.Graphics> createSimplePreviewRenderer(){return new CpmOwnedPreviewDispatchProbe.RenderTerminal();}
                        };
                        default -> field.getType()==Map.class?new java.util.HashMap<>():null;
                    };if(value!=null)field.set(screen,value);
                }
                var sync=screenType.getDeclaredMethod("synchronizePreviews",com.naocraftlab.skins.runtime.ViewSpec.class);sync.setAccessible(true);
                sync.invoke(screen,fixture.runtime.view(854,480,0,0));fixture.drainWorkers();sync.invoke(screen,fixture.runtime.view(854,480,0,0));fixture.drainWorkers();
                var desired=fixture.runtime.galleryPreviewCandidate().orElseThrow();String source=fixture.runtime.previewBodySource(desired);
                check(desired.bodySource().orElseThrow().cpmExpected(),"IMMEDIATE_CPM actual imported carrier requires own CPM body");
                @SuppressWarnings("unchecked") var production=(PreviewRenderer<Graphics>)target;
                var registration=fixture.runtime.observePreviewBody(desired,production::displayKind,() -> production.confirmGalleryBody(source));
                production.observeBody(registration);
                check(production.prepareGalleryBody(source,true,fixture.runtime.galleryPreviewAdmission(desired)),"IMMEDIATE_CPM real common registration precedes native source preparation");
                var requestMethod=screenType.getDeclaredMethod("galleryRequest",com.naocraftlab.skins.runtime.ViewSpec.Preview.class);requestMethod.setAccessible(true);
                @SuppressWarnings("unchecked") var request=(Optional<PreviewRenderer.PreviewRequest>)requestMethod.invoke(screen,desired);
                var own=request.orElseThrow();
                check(java.util.Arrays.equals(registry.registered.get(own.appearance().skin()),fixture.ownedBytes(desired)),"IMMEDIATE_CPM own imported pixels reach cache/relookup");
                try(var definition=CpmBehaviorProbe.nativeOwnedBody(fixture.ownedBytes(desired),nativeProfile(target),own.appearance())) {
                    int[] draws={0};
                    Inventory.draw=() -> {
                        check(fixture.runtime.previewBodySource(fixture.runtime.galleryPreviewCandidate().orElseThrow()).equals(source),"IMMEDIATE_CPM actual consumer retains current own source");
                        check(nativeSourceLocation(target).equals(own.appearance().skin().location()),"IMMEDIATE_CPM actual Inventory consumer receives own registered source");
                        CpmExtractionBodyProbe.bindAppearance(own.appearance());draws[0]++;
                    };
                    var render=screenType.getDeclaredMethod("renderPreview",Graphics.class,com.naocraftlab.skins.runtime.ViewSpec.Preview.class);render.setAccessible(true);
                    render.invoke(screen,new Graphics(),fixture.preview());
                    var session=(com.naocraftlab.skins.client.EditorPreviewSession)nativeField(target,"session");
                    check(draws[0]==1 && Inventory.x<0 && session.imageVisible() && fixture.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"IMMEDIATE_CPM hidden own consumer completes preparation without visible common ACK draws="+draws[0]+" image="+session.imageVisible()+" display="+fixture.displayed()+" native="+production.displayKind()+" status="+session.bodyStatus());
                    render.invoke(screen,new Graphics(),fixture.preview());
                    check(draws[0]==2 && Inventory.x>=0 && fixture.displayed()==PreviewRenderer.DisplayKind.CPM && fixture.preview().bodySource().equals(desired.bodySource()),"IMMEDIATE_CPM own visible native pass grants current common CPM ACK");
                    fixture.layersVisible(false);
                    System.out.println("PASS IMMEDIATE_CPM actual imported source/cache -> hidden own native primary binding -> visible own native consumer -> common CPM ACK");
                }
            } finally { ((AutoCloseable)target).close();Client.current=previousClient;Inventory.draw=() -> {};CpmExtractionBodyProbe.submissionBoundaries(() -> {},() -> {}); }
        }
    }

    private static String nativeSourceLocation(Object target) {
        try { var source=com.naocraftlab.skins.runtime.CapeProjection.skinSource(nativeProfile(target));return source==null?"":source.location(); }
        catch(Exception failure){throw new AssertionError(failure);}
    }

    private static void immediatePolicyHost(Class<?> rendererType, Object template, Client client,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture base) throws Exception {
        client.level = null; client.player = null; Client.current = client;
        for (boolean early : new boolean[]{false, true}) {
            Client.current = client;
            Object target = reopenedNative(rendererType, template);
            @SuppressWarnings("unchecked") var renderer = (PreviewRenderer<Graphics>)target;
            var hostType = CpmPreviewScreenSlice.loadNative(CpmPreviewScreenSlice.availableOwner(), rendererType);
            Object host = hostType.getConstructor().newInstance();
            var registry = new CpmOwnedPreviewDispatchProbe.Registry();
            try (var subject = base.freshOwned();
                 var skins = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry, TextureRegistry.TextureKind.PLAYER_SKIN,
                         com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding(), renderer::retainedTextures);
                 var capes = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry, TextureRegistry.TextureKind.IMAGE,
                         com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding(), renderer::retainedTextures)) {
                subject.localCapes(); subject.runtime.dispatchWidget("gallery.providers"); subject.drainWorkers();
                for (var field : hostType.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object value = switch (field.getName()) {
                        case "runtime" -> subject.runtime; case "width" -> 854; case "height" -> 480;
                        case "skinTextures" -> skins; case "capeTextures" -> capes;
                        case "capabilities" -> new CpmOwnedPreviewDispatchProbe.Capabilities() {
                            @SuppressWarnings({"unchecked", "rawtypes"}) public PreviewRenderer<CpmOwnedPreviewDispatchProbe.Graphics> createEditorPreviewRenderer() { return (PreviewRenderer)target; }
                            public PreviewRenderer<CpmOwnedPreviewDispatchProbe.Graphics> createSimplePreviewRenderer() { return new CpmOwnedPreviewDispatchProbe.RenderTerminal(); }
                        };
                        default -> field.getType() == Map.class ? new java.util.HashMap<>() : null;
                    };
                    if (value != null) field.set(host, value);
                }
                var sync = hostType.getDeclaredMethod("synchronizePreviews", com.naocraftlab.skins.runtime.ViewSpec.class); sync.setAccessible(true);
                var paint = hostType.getDeclaredMethod("renderPreview", Graphics.class, com.naocraftlab.skins.runtime.ViewSpec.Preview.class); paint.setAccessible(true);
                CpmBakedDisplayProbe.observeImmediateMenu = true;
                for (int pass = 0; pass < 8 && renderer.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED; pass++) {
                    var view = subject.runtime.view(854,480,0,0); sync.invoke(host, view); subject.drainWorkers();
                    view = subject.runtime.view(854,480,0,0); sync.invoke(host, view);
                    CpmBakedDisplayProbe.immediateMaterials.clear(); CpmBakedDisplayProbe.immediateTranslations.clear();
                    paint.invoke(host,new Graphics(),view.previews().get(0));
                    if (pass == 0) check(!CpmBakedDisplayProbe.immediateTranslations.isEmpty()
                            && CpmBakedDisplayProbe.immediateTranslations.get(0) < 0, "PROVIDERS immediate first-open prepares only hidden own material");
                }
                check(renderer.displayKind() == PreviewRenderer.DisplayKind.ORDINARY && !CpmBakedDisplayProbe.immediateMaterials.isEmpty(), "PROVIDERS immediate actual A material and ACK");
                var materialA = CpmBakedDisplayProbe.immediateMaterials.get(0);
                var a = subject.runtime.view(854,480,0,0).previews().get(0);
                subject.runtime.dispatchWidget("providers.row.MINECRAFT"); subject.drainWorkers();
                var view = subject.runtime.view(854,480,0,0); var b = view.previews().get(0);
                check(!subject.runtime.previewBodySource(a).equals(subject.runtime.previewBodySource(b)), "PROVIDERS immediate admitted row changes actual source");
                sync.invoke(host, view); subject.drainWorkers(); view = subject.runtime.view(854,480,0,0); sync.invoke(host,view);
                var key = hostType.getDeclaredMethod("previewAssetKey",com.naocraftlab.skins.runtime.ViewSpec.Preview.class); key.setAccessible(true);
                var handle = b.requiresLoadedSkin() ? skins.handle(key.invoke(null,b)).orElseThrow() : subject.runtime.previewPlayerAppearance(b).orElseThrow().skin();
                var pending = com.naocraftlab.skins.client.NativePlayerSkinLifecycle.pending(handle.location());
                try {
                    paint.invoke(host,new Graphics(),b);
                    var session = (com.naocraftlab.skins.client.EditorPreviewSession)nativeField(target,"session");
                    long[] now = {0}; nativeField(session,"nanoTime",(java.util.function.LongSupplier)() -> now[0]); nativeField(session,"imageStarted",0L);
                    for (long tick : early ? new long[]{0} : new long[]{0,299_000_000L,300_000_000L,5_000_000_000L}) {
                        now[0] = tick; CpmBakedDisplayProbe.immediateMaterials.clear(); CpmBakedDisplayProbe.immediateTranslations.clear(); Graphics.loadingFrames.clear();
                        paint.invoke(host,new Graphics(),b);
                        check(CpmBakedDisplayProbe.immediateMaterials.equals(java.util.List.of(materialA, new Location(handle.location())))
                                && CpmBakedDisplayProbe.immediateTranslations.size() == 4
                                && CpmBakedDisplayProbe.immediateTranslations.get(0) > 0 && CpmBakedDisplayProbe.immediateTranslations.get(2) < 0
                                && !session.imageVisible() && Graphics.loadingFrames.isEmpty(), "PROVIDERS immediate visible A/hidden B material/no paint spinner at " + tick
                                + " expected=" + materialA + " actual=" + CpmBakedDisplayProbe.immediateMaterials
                                + " translations=" + CpmBakedDisplayProbe.immediateTranslations + " visible=" + session.imageVisible()
                                + " retained=" + ((com.naocraftlab.skins.client.EditorPreviewSession.Gallery)nativeField(target,"gallery")).retainedSource());
                        check(renderer.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "PROVIDERS immediate A cannot ACK B");
                    }
                    now[0] = early ? 25_000_000L : 5_000_000_000L; pending.ready();
                    CpmBakedDisplayProbe.immediateMaterials.clear(); CpmBakedDisplayProbe.immediateTranslations.clear(); paint.invoke(host,new Graphics(),b);
                    check(CpmBakedDisplayProbe.immediateMaterials.contains(new Location(handle.location())) && session.imageVisible()
                            && renderer.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "PROVIDERS immediate own hidden B flush is not visible ACK");
                    CpmBakedDisplayProbe.immediateMaterials.clear(); CpmBakedDisplayProbe.immediateTranslations.clear(); paint.invoke(host,new Graphics(),b);
                    check(CpmBakedDisplayProbe.immediateMaterials.equals(java.util.List.of(new Location(handle.location())))
                            && CpmBakedDisplayProbe.immediateTranslations.get(0) > 0 && renderer.displayKind() == PreviewRenderer.DisplayKind.ORDINARY,
                            "PROVIDERS immediate own B visible material and common ACK");
                    check(((Map<?,?>)nativeField(target,"gallerySlots")).size() == 1, "PROVIDERS immediate bounded slots retire A after own B");
                    subject.runtime.closeScreen(); check(renderer.retainedTextures().isEmpty(), "PROVIDERS immediate close revokes retention");
                } finally { pending.retire(); }
                System.out.println("PASS PROVIDERS immediate actual host source/cache -> whole A 0/299/300/5000 or early25 -> own hidden B flush -> visible B ACK; early=" + early);
            } finally { ((AutoCloseable)target).close(); CpmBakedDisplayProbe.observeImmediateMenu=false; Client.current=null; }
        }
    }

    private static void immediateCaller(Class<?> rendererType, Object target,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture fixture) throws Exception {
        String owner = CpmPreviewScreenSlice.availableOwner();
        Class<?> screenType = CpmPreviewScreenSlice.loadNative(owner, rendererType);
        Object screen = screenType.getConstructor().newInstance();
        var registry = new CpmOwnedPreviewDispatchProbe.Registry();
        try (var skins = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,
                    TextureRegistry.TextureKind.PLAYER_SKIN, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
             var capes = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,
                    TextureRegistry.TextureKind.IMAGE, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
            for (var field : screenType.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = switch (field.getName()) {
                    case "runtime" -> fixture.runtime;
                    case "width" -> 854;
                    case "height" -> 480;
                    case "skinTextures" -> skins;
                    case "capeTextures" -> capes;
                    case "capabilities" -> new CpmOwnedPreviewDispatchProbe.Capabilities() {
                        @SuppressWarnings({"unchecked", "rawtypes"}) public PreviewRenderer<CpmOwnedPreviewDispatchProbe.Graphics> createEditorPreviewRenderer() {
                            return (PreviewRenderer) target;
                        }
                        public PreviewRenderer<CpmOwnedPreviewDispatchProbe.Graphics> createSimplePreviewRenderer() {
                            return new CpmOwnedPreviewDispatchProbe.RenderTerminal();
                        }
                    };
                    default -> field.getType() == Map.class ? new java.util.HashMap<>() : null;
                };
                if (value != null) field.set(screen,value);
            }
            var view = fixture.runtime.view(854,480,0,0);
            var sync = screenType.getDeclaredMethod("synchronizePreviews", com.naocraftlab.skins.runtime.ViewSpec.class);
            sync.setAccessible(true); sync.invoke(screen,view);
            var render = screenType.getDeclaredMethod("renderPreview", Graphics.class, com.naocraftlab.skins.runtime.ViewSpec.Preview.class);
            render.setAccessible(true); render.invoke(screen,new Graphics(),fixture.preview());
            check(nativeField(target,"gallery") != null,
                    "actual immediate GUI must dispatch Gallery candidate through frozen source-specific port, not legacy prepareBody");
            fixture.selectC();
            var callbackField = com.naocraftlab.skins.runtime.ClientRuntime.class.getDeclaredField("bodyCallback"); callbackField.setAccessible(true);
            @SuppressWarnings("unchecked") var registration = (java.util.function.Consumer<PreviewRenderer.BodyStatus>)callbackField.get(fixture.runtime);
            fixture.runtime.invalidatePreviewBody(registration);
            fixture.offlineCapeCapability(true);
            sync.invoke(screen,fixture.runtime.view(854,480,0,0));
            fixture.drainWorkers(); sync.invoke(screen,fixture.runtime.view(854,480,0,0)); fixture.drainWorkers();
            render.invoke(screen,new Graphics(),fixture.preview());
            var capabilityA = fixture.preview();
            check(capabilityA.capeHasElytra(),"COMMON-02 immediate real account predecessor capability");
            fixture.offlineCapeCapability(false);
            var capabilityB = fixture.runtime.galleryPreviewCandidate().orElseThrow();
            check(capabilityA.bodySource().equals(capabilityB.bodySource())
                    && !fixture.runtime.previewBodySource(capabilityA).equals(fixture.runtime.previewBodySource(capabilityB)),
                    "COMMON-02 immediate equal BodySource distinct exact identity");
            sync.invoke(screen,fixture.runtime.view(854,480,0,0));
            render.invoke(screen,new Graphics(),fixture.preview());
            check(!fixture.preview().capeHasElytra(),"COMMON-02 immediate actual same-ID successor pass");
            System.out.println("PASS COMMON-02 immediate actual host same-ID real account capability replacement");
            Object reentrant = reopenedNative(rendererType,target);
            @SuppressWarnings("unchecked") var closing = (PreviewRenderer<Graphics>)reentrant;
            try {
                for (int i=0;i<2;i++) {
                    String gallerySource = "fixture:live-gallery-return-" + i;
                    check(closing.prepareGalleryBody(gallerySource,false,() -> true),"N3 immediate live Gallery admission");
                    closing.renderGallery(new Graphics(),gallerySource,fixture.request());
                    check(closing.body().current().getAsBoolean(),"N3 immediate own Gallery material");
                    Object galleryProfile = nativeField(reentrant,"previewProfile");
                    closing.prepareBody("fixture:ordinary-before-entry-close-" + i,false);
                    closing.render(new Graphics(),fixture.request());
                    check(closing.body().current().getAsBoolean() && nativeField(reentrant,"previewProfile") != galleryProfile,
                            "N3 immediate Gallery to fresh Providers ordinary actual material");
                }
                check(closing.body().current().getAsBoolean(),"N3 immediate starts from own actual ordinary pass");
                Object session = nativeField(reentrant,"session"), profile = nativeField(reentrant,"previewProfile");
                boolean[] called = {false};
                closing.observeBody(status -> {
                    called[0] = true;
                    try { ((AutoCloseable)reentrant).close(); } catch (Exception failure) { throw new AssertionError(failure); }
                });
                check(!closing.prepareGalleryBody("fixture:reentrant-entry-close",false,() -> true) && called[0],
                        "N3 immediate reentrant invalidation close rejects Gallery recreation");
                closing.prepareBody("fixture:after-reentrant-close",false);
                check(nativeField(reentrant,"gallery") == null && nativeField(reentrant,"session") == session
                        && nativeField(reentrant,"previewProfile") == profile,"N3 immediate terminal identities unchanged");
                System.out.println("PASS N3 immediate own ordinary native pass -> entry invalidation close -> no Gallery/ordinary recreation");
            } finally { ((AutoCloseable)reentrant).close(); }
        }
        System.out.println("PASS actual immediate GUI -> Gallery native renderer port");
    }

    private static final java.util.List<String> framePainted = new java.util.ArrayList<>();
    private static final java.util.List<String> frameBodies = new java.util.ArrayList<>();

    public static class FrameWidget extends CpmOwnedPreviewDispatchProbe.WidgetTerminal {
        public String id;
        public void update(com.naocraftlab.skins.runtime.ViewSpec.Widget widget) {}
        public void setSelected(boolean value) {}
        public void setTrailingInfo(boolean value) {}
        public void setIcon(com.naocraftlab.skins.runtime.WidgetIcon icon) {}
        public void setIcon(com.naocraftlab.skins.runtime.GuiIcon icon) {}
        public void setIcon(CpmOwnedPreviewDispatchProbe.Text icon) {}
        public void setNarrationDescription(Optional<CpmOwnedPreviewDispatchProbe.TextValue> text) {}
    }

    public static CpmOwnedPreviewDispatchProbe.WidgetTerminal frameWidget(com.naocraftlab.skins.runtime.ViewSpec.Widget widget) {
        var value = new FrameWidget(); value.id = widget.id(); return value;
    }

    public static void framePaint(CpmOwnedPreviewDispatchProbe.WidgetTerminal widget) {
        var value = (FrameWidget) widget;
        framePainted.add(value.id + ":" + value.x + ":" + value.y + ":" + value.width + ":" + value.message.getString());
    }

    public record FrameTab(FrameWidget navigation) {}
    public static final class FrameTooltip {
        public static FrameTooltip create(CpmOwnedPreviewDispatchProbe.TextValue value) { return new FrameTooltip(); }
    }

    private static java.util.List<String> frameControls(com.naocraftlab.skins.runtime.ViewSpec view) {
        return view.widgets().stream().map(w -> w.id() + ":" + w.bounds().x() + ":" + w.bounds().y() + ":" + w.bounds().width()
                + ":" + w.label().key()).toList();
    }

    private static java.util.UUID nativeProfile(Object target) throws Exception {
        var profile = nativeField(target,"previewProfile");
        try { return (java.util.UUID) profile.getClass().getMethod("getId").invoke(profile); }
        catch (NoSuchMethodException modern) { return (java.util.UUID) profile.getClass().getMethod("id").invoke(profile); }
    }

    private static Object reopenedNative(Class<?> type, Object previous) throws Exception {
        Object target = type.getConstructor().newInstance();
        for (var field : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = switch (field.getName()) {
                case "session" -> new com.naocraftlab.skins.client.EditorPreviewSession();
                case "gallery", "activeGallerySource", "previewPlayer", "previewLevel" -> null;
                case "gallerySlots" -> new java.util.LinkedHashMap<>();
                case "closed" -> false;
                case "renderDepth" -> 0;
                case "previewClock" -> new com.naocraftlab.skins.client.EditorPreviewClock();
                case "previewTickGate" -> new com.naocraftlab.skins.client.EditorPreviewTickGate();
                case "bodyObserver" -> (java.util.function.Consumer<PreviewRenderer.BodyStatus>) ignored -> {};
                case "accessoryObserver" -> (java.util.function.Consumer<com.naocraftlab.skins.client.PreviewAccessories>) ignored -> {};
                case "previewProfile" -> Class.forName("com.mojang.authlib.GameProfile").getConstructor(java.util.UUID.class,String.class).newInstance(java.util.UUID.randomUUID(),"Fixture");
                default -> field.get(previous);
            };
            field.set(target,value);
        }
        return target;
    }

    private static void immediateRootFrame(Class<?> rendererType, Object target,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture fixture) throws Exception {
        fixture.enableCpmCape();
        var screenType = CpmPreviewScreenSlice.loadNativeLifecycle(CpmPreviewScreenSlice.availableOwner(), rendererType);
        var screen = screenType.getConstructor().newInstance();
        var terminal = (CpmOwnedPreviewDispatchProbe.ScreenTerminal) screen;
        terminal.width = 854; terminal.height = 480;
        terminal.minecraft = new CpmOwnedPreviewDispatchProbe.ClientTerminal(); terminal.minecraft.screen = terminal;
        var registry = new CpmOwnedPreviewDispatchProbe.Registry();
        try (var skins = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,
                TextureRegistry.TextureKind.PLAYER_SKIN, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
             var capes = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,
                TextureRegistry.TextureKind.IMAGE, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
            for (var field : screenType.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = switch (field.getName()) {
                    case "runtime" -> fixture.runtime;
                    case "skinTextures" -> skins;
                    case "capeTextures" -> capes;
                    case "initialized" -> true;
                    case "widgetShapes", "tabGroupShapes" -> java.util.List.of();
                    case "scrollController" -> new CpmOwnedPreviewDispatchProbe.ScrollTerminal();
                    case "capabilities" -> new CpmOwnedPreviewDispatchProbe.Capabilities() {
                        @SuppressWarnings({"unchecked", "rawtypes"}) public PreviewRenderer<CpmOwnedPreviewDispatchProbe.Graphics> createEditorPreviewRenderer() { return (PreviewRenderer) target; }
                        public PreviewRenderer<CpmOwnedPreviewDispatchProbe.Graphics> createSimplePreviewRenderer() { return new CpmOwnedPreviewDispatchProbe.RenderTerminal(); }
                    };
                    default -> field.getType() == Map.class ? new java.util.LinkedHashMap<>() : null;
                };
                if (value != null) field.set(screen,value);
            }
            var changed = screenType.getDeclaredMethod("snapshotChanged", com.naocraftlab.skins.runtime.ClientSnapshot.class); changed.setAccessible(true);
            var render = screenType.getMethod("render", Graphics.class, int.class, int.class, float.class);
            try (var subscription = fixture.runtime.subscribe(snapshot -> {
                try { changed.invoke(screen,snapshot); }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            })) {
                Client client = (Client) nativeField(target,"minecraft");
                client.level = new Level(); client.player = new Avatar(client.level); Client.current = client;
                var frameIdentities = new java.util.ArrayList<Map<?,?>>();
                Inventory.draw = () -> {
                    try {
                        if (frameIdentities.isEmpty()) frameIdentities.add(new java.util.LinkedHashMap<>((Map<?,?>) nativeField(screen,"nativeWidgets")));
                        frameBodies.add((String)nativeField(nativeField(target,"activeGallerySource"),"bodySource")); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                };
                var sync = screenType.getDeclaredMethod("synchronizePreviews", com.naocraftlab.skins.runtime.ViewSpec.class); sync.setAccessible(true);
                var assetKey = screenType.getDeclaredMethod("previewAssetKey", com.naocraftlab.skins.runtime.ViewSpec.Preview.class); assetKey.setAccessible(true);
                var loadingA = fixture.preview(); Object loadingAKey = assetKey.invoke(screen,loadingA);
                sync.invoke(screen,fixture.runtime.view(854,480,0,0));
                while (skins.handle(loadingAKey).isEmpty() && fixture.runNextWorker()) sync.invoke(screen,fixture.runtime.view(854,480,0,0));
                render.invoke(screen,new Graphics(),0,0,0f);
                check(fixture.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"initial immediate hidden preparation is not common ACK");
                render.invoke(screen,new Graphics(),0,0,0f);
                var initialA = fixture.preview();
                var initialInput = fixture.runtime.galleryAccessoryAdmission(initialA);
                Object keyA = assetKey.invoke(screen,initialA);
                var handleA = skins.handle(keyA).orElseThrow();
                check(java.util.Arrays.equals(fixture.ownedBytes(initialA),registry.registered.get(handleA)), "actual A import/storage/load/cache bytes");
                String initialSource = fixture.runtime.previewBodySource(initialA);
                var requestMethodA = screenType.getDeclaredMethod("galleryRequest",com.naocraftlab.skins.runtime.ViewSpec.Preview.class); requestMethodA.setAccessible(true);
                @SuppressWarnings("unchecked") var initialRequest = ((Optional<PreviewRenderer.PreviewRequest>) requestMethodA.invoke(screen,initialA)).orElseThrow();
                @SuppressWarnings("unchecked") var rendererA = (PreviewRenderer<Graphics>) target;
                try (var decodedA = CpmBehaviorProbe.nativeAccessoryObservation(com.naocraftlab.skins.runtime.CapeProjection.skinSource(nativeProfile(target)))) {
                    var accessories=rendererA.galleryAccessories(initialSource,initialRequest,initialInput);
                    check(accessories.canInspect() && accessories.present(),"actual A matching profile/appearance accessories positive");
                }
                render.invoke(screen,new Graphics(),0,0,0f);
                render.invoke(screen,new Graphics(),0,0,0f);
                var ownedA=fixture.preview();String a=fixture.runtime.previewBodySource(ownedA);
                var inputA=fixture.runtime.galleryAccessoryAdmission(ownedA);
                @SuppressWarnings("unchecked") var requestA=((Optional<PreviewRenderer.PreviewRequest>)requestMethodA.invoke(screen,ownedA)).orElseThrow();
                var sourceA=com.naocraftlab.skins.runtime.CapeProjection.skinSource(nativeProfile(target));
                com.naocraftlab.skins.client.PreviewAccessories accessoryA;
                try(var decodedA=CpmBehaviorProbe.nativeAccessoryObservation(sourceA)) {
                    accessoryA=rendererA.galleryAccessories(a,requestA,inputA);
                    check(accessoryA.canInspect() && accessoryA.present(),"stable actual A has its own decoded accessory scope");
                }
                int[] staleReadsA = {0};
                var registrationA = fixture.runtime.observePreviewAccessories(ownedA,() -> { staleReadsA[0]++; return rendererA.galleryAccessories(a,requestA,inputA); });
                fixture.selectC();
                var before = fixture.runtime.view(854,480,0,0);
                String b = fixture.runtime.previewBodySource(fixture.runtime.galleryPreviewCandidate().orElseThrow());
                check(!a.equals(b) && before.previews().stream().anyMatch(p -> fixture.runtime.previewBodySource(p).equals(a)),
                        "root fixture must retain actual A while distinct B is candidate");
                var hidden = fixture.runtime.galleryPreviewCandidate().orElseThrow();
                var inputB = fixture.runtime.galleryAccessoryAdmission(hidden);
                check(!before.previews().contains(hidden) && hidden.requiresLoadedSkin(),"distinct owned B must be hidden outside view.previews");
                Object keyB = assetKey.invoke(screen,hidden);
                sync.invoke(screen,before);
                check(!keyA.equals(keyB), "hidden candidate uses its own real asset cache key");
                check(skins.handle(keyB).isEmpty(), "distinct owned hidden candidate is cold/queued while A remains displayed");
                fixture.drainWorkers(); sync.invoke(screen,before);
                var handleB = skins.handle(keyB).orElseThrow();
                check(!handleA.equals(handleB) && hidden.variant() == com.naocraftlab.skins.core.model.SkinVariant.SLIM
                        && java.util.Arrays.equals(fixture.ownedBytes(hidden),registry.registered.get(handleB)), "distinct imported B bytes/model reached real hidden cache/relookup");
                check(fixture.preview().bodySource().equals(ownedA.bodySource()) && fixture.preview().presetId().equals(ownedA.presetId()), "loaded hidden B cannot publish before its actual pass");
                if(loadingCanonical) {
                    var held=com.naocraftlab.skins.client.NativePlayerSkinLifecycle.pending(handleB.location());
                    try {
                        frameBodies.clear();render.invoke(screen,new Graphics(),0,0,0f);
                        var pendingSession=(com.naocraftlab.skins.client.EditorPreviewSession)nativeField(target,"session");
                        long[] now={0};var clock=pendingSession.getClass().getDeclaredField("nanoTime");clock.setAccessible(true);clock.set(pendingSession,(java.util.function.LongSupplier)()->now[0]);
                        var start=pendingSession.getClass().getDeclaredField("imageStarted");start.setAccessible(true);start.setLong(pendingSession,0);
                        for(long tick:new long[]{0,299_000_000L,300_000_000L}) {
                            now[0]=tick;frameBodies.clear();render.invoke(screen,new Graphics(),0,0,0f);
                            check(frameBodies.equals(tick<300_000_000L?java.util.List.of(a):java.util.List.of()),"IMMEDIATE RETENTION actual root holds whole A until threshold, never pending B at "+tick+" actual="+frameBodies);
                            check(pendingSession.imageSpinner()==(tick>=300_000_000L) && fixture.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"IMMEDIATE RETENTION own pending clock/spinner never grants B authority");
                        }
                        held.ready();
                    } finally {held.retire();}
                }
                frameIdentities.clear();
                framePainted.clear(); frameBodies.clear();
                render.invoke(screen,new Graphics(),0,0,0f);
                check(frameBodies.equals(loadingCanonical?java.util.List.of(b):java.util.List.of(a,b)), "root retains A before threshold and separately executes own B hidden preparation: " + frameBodies);
                check(frameIdentities.size() == 1 && frameIdentities.get(0).equals(nativeField(screen,"nativeWidgets")), "root snapshot must not rebuild native collections during paint");
                check(fixture.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED && fixture.runtime.previewBodySource(fixture.preview()).equals(a),"hidden B preparation preserves retained common A without display ACK");
                framePainted.clear();frameBodies.clear();frameIdentities.clear();
                render.invoke(screen,new Graphics(),0,0,0f);
                check(frameBodies.equals(java.util.List.of(b)) && fixture.displayed()==PreviewRenderer.DisplayKind.ORDINARY,"own B visible immediate consumer obtains common ACK");
                var sourceB = com.naocraftlab.skins.runtime.CapeProjection.skinSource(nativeProfile(target));
                @SuppressWarnings("unchecked") var requestB = ((Optional<PreviewRenderer.PreviewRequest>) requestMethodA.invoke(screen,hidden)).orElseThrow();
                try (var decodedB = CpmBehaviorProbe.nativeAccessoryObservation(sourceB)) {
                    var accessoryB = rendererA.galleryAccessories(b,requestB,inputB);
                    check(accessoryB.canInspect() && accessoryB.present() && !accessoryA.scope().equals(accessoryB.scope()),"actual B profile has distinct matching positive accessory scope");
                    int readsBeforeStale = staleReadsA[0]; registrationA.accept(accessoryA);
                    check(staleReadsA[0] == readsBeforeStale && !sourceA.active(),"stale A callback after B never reads retired source supplier");
                }
                var after = fixture.runtime.view(854,480,0,0);
                check(!frameControls(before).equals(frameControls(after)), "root producer must change real widget labels/geometry");
                check(framePainted.equals(frameControls(before)), "ROOT_FRAME_MIXED_A_BODY_B_CONTROLS expected=" + frameControls(before) + " actual=" + framePainted);
                var metadataCandidate = fixture.runtime.galleryPreviewCandidate().orElseThrow();
                String metadataSource = fixture.runtime.previewBodySource(metadataCandidate);
                check(!metadataSource.equals(b) && metadataCandidate.nativeAccessories(), "decoded B metadata produces a distinct full-key successor");
                check(fixture.runtime.previewBodySource(fixture.preview()).equals(b), "metadata alone retains B0 until B1 own native pass");
                framePainted.clear(); frameBodies.clear();
                render.invoke(screen,new Graphics(),0,0,0f);
                check(frameBodies.equals(java.util.List.of(b,metadataSource)), "next root retains B0 while separately preparing metadata-derived B1 image");
                check(framePainted.equals(frameControls(after)), "root keeps B0 controls throughout B1 native completion");
                check(fixture.runtime.previewBodySource(fixture.preview()).equals(b),"hidden B1 cannot common ACK metadata alone");
                framePainted.clear();frameBodies.clear();render.invoke(screen,new Graphics(),0,0,0f);
                check(fixture.runtime.previewBodySource(fixture.preview()).equals(metadataSource), "only B1 actual pass commits its own projection");
                var metadataView = fixture.runtime.view(854,480,0,0);
                framePainted.clear(); frameBodies.clear();
                render.invoke(screen,new Graphics(),0,0,0f);
                check(frameBodies.equals(java.util.List.of(metadataSource)), "stable successor draws only its own native body");
                check(framePainted.equals(frameControls(metadataView)), "stable successor paints its own coherent controls");
                check(skins.handle(keyA).isEmpty(), "old Gallery A cache key retired after B publication");
                fixture.selectB();
                sync.invoke(screen,fixture.runtime.view(854,480,0,0)); fixture.drainWorkers();
                sync.invoke(screen,fixture.runtime.view(854,480,0,0));
                frameBodies.clear(); render.invoke(screen,new Graphics(),0,0,0f);
                check(frameBodies.size()==2 && frameBodies.get(0).equals(metadataSource) && !frameBodies.get(1).equals(metadataSource),"B-C-B return retains predecessor while separately preparing new source authority");
                check(fixture.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"returned hidden preparation does not acknowledge retained image");
                frameBodies.clear();render.invoke(screen,new Graphics(),0,0,0f);
                check(fixture.preview().presetId().equals(ownedA.presetId()), "B-C-B actual pass returns original selected preset");
                var requestMethod = screenType.getDeclaredMethod("galleryRequest",com.naocraftlab.skins.runtime.ViewSpec.Preview.class); requestMethod.setAccessible(true);
                var current = fixture.preview(); String currentSource = fixture.runtime.previewBodySource(current);
                @SuppressWarnings("unchecked") var projected = (Optional<PreviewRenderer.PreviewRequest>) requestMethod.invoke(screen,current);
                var request = projected.orElseThrow();
                @SuppressWarnings("unchecked") var nativeRenderer = (PreviewRenderer<Graphics>) target;
                var inputCurrent = fixture.runtime.galleryAccessoryAdmission(current);
                var profile = nativeField(target,"previewProfile");
                java.util.UUID profileId;
                try { profileId = (java.util.UUID) profile.getClass().getMethod("getId").invoke(profile); }
                catch (NoSuchMethodException modern) { profileId = (java.util.UUID) profile.getClass().getMethod("id").invoke(profile); }
                var source = com.naocraftlab.skins.runtime.CapeProjection.skinSource(profileId);
                check(source != null && source.active() && source.location().equals(request.appearance().skin().location())
                        && source.model() == request.appearance().model(),"actual slot profile/appearance source prerequisite");
                try (var decoded = CpmBehaviorProbe.nativeAccessoryObservation(source)) {
                    var positive = nativeRenderer.galleryAccessories(currentSource,request,inputCurrent);
                    check(positive.canInspect() && positive.cape() && positive.elytra() && positive.scope() != null,
                            "actual source-specific Gallery accessories positive from decoded model");
                    check(!nativeRenderer.galleryAccessories(a,request,inputA).canInspect() && !nativeRenderer.galleryAccessories(b,request,inputB).canInspect(),
                            "stale A and C cannot expose current source accessories after B return");
                    check(!nativeRenderer.galleryAccessories(currentSource,new PreviewRenderer.PreviewRequest(
                            new PreviewRenderer.PreviewAppearance(request.appearance().skin(),SkinModel.SLIM,Optional.empty(),PreviewRenderer.CapeMode.OFF,true),
                            0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.EDITOR_DRAFT),inputCurrent).canInspect(),"mismatched appearance cannot borrow current profile accessories");
                    int[] reads = {0,0};
                    var stale = fixture.runtime.observePreviewAccessories(current,() -> { reads[0]++; return nativeRenderer.galleryAccessories(currentSource,request,inputCurrent); });
                    var live = fixture.runtime.observePreviewAccessories(current,() -> { reads[1]++; return nativeRenderer.galleryAccessories(currentSource,request,inputCurrent); });
                    reads[0] = 0; reads[1] = 0; stale.accept(positive); live.accept(positive);
                    check(reads[0] == 0 && reads[1] > 0,"replaced registration rejects stale supplier before reading; positive current supplier executes");
                    framePainted.clear();
                    Inventory.draw = () -> { throw new AssertionError("frame-terminal-failure"); };
                    boolean failed = false;
                    try { render.invoke(screen,new Graphics(),0,0,0f); }
                    catch (java.lang.reflect.InvocationTargetException expected) { failed = expected.getCause() instanceof AssertionError && "frame-terminal-failure".equals(expected.getCause().getMessage()); }
                    check(failed && ((Integer) nativeField(screen,"galleryFrameDepth")) == 0,"root exception settles frame guard");
                    Inventory.draw=() -> {};
                    fixture.selectC();
                    sync.invoke(screen,fixture.runtime.view(854,480,0,0));fixture.drainWorkers();sync.invoke(screen,fixture.runtime.view(854,480,0,0));
                    render.invoke(screen,new Graphics(),0,0,0f);render.invoke(screen,new Graphics(),0,0,0f);
                    check(fixture.displayed()==PreviewRenderer.DisplayKind.ORDINARY,"explicit new selected source recovers after terminal image failure");
                    var closingSource=com.naocraftlab.skins.runtime.CapeProjection.skinSource(nativeProfile(target));
                    framePainted.clear();
                    Inventory.draw = () -> {
                        check(closingSource.active(),"native reentrant close starts with actual current source");
                        try { ((AutoCloseable) target).close(); } catch (Exception failure) { throw new AssertionError(failure); }
                        check(closingSource.active(),"source resource retained until immediate native call really returns");
                        fixture.runtime.closeScreen();
                    };
                    render.invoke(screen,new Graphics(),0,0,0f);
                    check(!closingSource.active() && ((Map<?,?>) nativeField(target,"gallerySlots")).isEmpty(),"native return retires closed source/profile slots");
                    check(framePainted.isEmpty() && ((Integer) nativeField(screen,"galleryFrameDepth")) == 0,"reentrant screen close has no after-close widgets and settles frame guard");
                    int oldReads = reads[1]; live.accept(positive);
                    check(reads[1] == oldReads,"closed screen rejects accessory supplier before read");
                    render.invoke(screen,new Graphics(),0,0,0f);
                    check(framePainted.isEmpty(),"closed root cannot revive widgets");
                    fixture.runtime.reopen(); fixture.drainWorkers();
                    check(fixture.runtime.snapshot().lifecycle() == com.naocraftlab.skins.runtime.ClientSnapshot.Lifecycle.READY,"real same runtime reopen reaches READY");
                    live.accept(positive); check(reads[1] == oldReads,"pre-close accessory callback stays retired after reopen");
                    check(!nativeRenderer.prepareGalleryBody(currentSource,false, () -> true),"closed native owner cannot be resurrected by reopen");
                    Object terminalSession = nativeField(target,"session");
                    nativeRenderer.prepareBody("fixture:ordinary-after-terminal-close",false);
                    check(nativeField(target,"session") == terminalSession,"N3 closed immediate owner cannot recreate ordinary session");
                    Object reopened = reopenedNative(rendererType,target);
                    @SuppressWarnings("unchecked") var nextRenderer = (PreviewRenderer<Graphics>) reopened;
                    var nextPreview = fixture.preview();
                    var inputNext = fixture.runtime.galleryAccessoryAdmission(nextPreview);
                    String nextSource = fixture.runtime.previewBodySource(nextPreview);
                    check(!nextSource.equals(currentSource),"reopen creates new source generation");
                    nextRenderer.observeBody(fixture.observeBody(nextPreview, nextRenderer));
                    check(nextRenderer.prepareGalleryBody(nextSource,false, fixture.runtime.galleryPreviewAdmission(nextPreview)),"fresh native owner admits reopened source");
                    Inventory.draw = () -> {};
                    nextRenderer.renderGallery(new Graphics(),nextSource,requestA);
                    check(fixture.displayed() == PreviewRenderer.DisplayKind.ORDINARY,"reopened actual native pass positively publishes new source");
                    var nextSkin = com.naocraftlab.skins.runtime.CapeProjection.skinSource(nativeProfile(reopened));
                    try (var nextDecoded = CpmBehaviorProbe.nativeAccessoryObservation(nextSkin)) {
                        var nextAccessory = nextRenderer.galleryAccessories(nextSource,requestA,inputNext);
                        check(nextAccessory.canInspect() && !nextAccessory.scope().equals(positive.scope()),"reopened actual owner has new positive accessory scope");
                        int[] accountReads = {0};
                        var accountCallback = fixture.runtime.observePreviewAccessories(nextPreview,() -> { accountReads[0]++; return nextRenderer.galleryAccessories(nextSource,requestA,inputNext); });
                        int beforeAccount = accountReads[0]; fixture.sessionAccount = new java.util.UUID(0,899);
                        accountCallback.accept(nextAccessory); check(accountReads[0] == beforeAccount,"account scope rejects supplier before reading");
                        fixture.sessionAccount = new java.util.UUID(0,801);
                        var lease = nextSkin;
                        client.level = null; client.player = null;
                        CpmBakedDisplayProbe.flushed = () -> {
                            check(lease.active(),"source remains retained inside actual endBatch/flush terminal");
                            try { ((AutoCloseable) reopened).close(); } catch (Exception failure) { throw new AssertionError(failure); }
                            check(lease.active(),"reentrant baked close cannot retire source before actual native flush returns");
                        };
                        try { nextRenderer.renderGallery(new Graphics(),nextSource,requestA); }
                        finally { CpmBakedDisplayProbe.flushed = () -> {}; }
                        check(!lease.active() && ((Map<?,?>) nativeField(reopened,"gallerySlots")).isEmpty(),"actual endBatch/flush return settles closed resources");
                    }
                    System.out.println("PASS REOPEN_ACCOUNT fresh native owner/same runtime positive, old callback retirement, account scope, actual endBatch/flush reentrant retention");
                }
                System.out.println("PASS SOURCE_ACCESSORIES decoded matching profile/appearance, stale A/C, replaced registration, native reentrant close retains resource until return, root exception/close guards");
                System.out.println("PASS DISTINCT_OWNED hidden candidate actual import/storage/load/cache/relookup, model/bytes/no-borrow, old-key retirement and B-C-B");
            }
        }
        System.out.println("PASS ROOT_FRAME retained common A/controls, hidden own B preparation -> visible own B consumer/common ACK -> next coherent B widgets");
    }

    private static Remapper terminalNames(Map<String, Class<?>> types) {
        return new Remapper(Opcodes.ASM9) {
            @Override public String map(String internalName) {
                Class<?> mapped = types.get(internalName);
                return mapped == null ? internalName : mapped.getName().replace('.', '/');
            }

            @Override public String mapFieldName(String owner, String name, String descriptor) {
                if (!types.containsKey(owner)) return name;
                return switch (owner + "." + name + ":" + descriptor) {
                    case "net/minecraft/client/Minecraft.f_91074_:Lnet/minecraft/client/player/LocalPlayer;" -> "player";
                    case "net/minecraft/client/Minecraft.f_91073_:Lnet/minecraft/client/multiplayer/ClientLevel;" -> "level";
                    case "net/minecraft/world/item/ItemStack.f_41583_:Lnet/minecraft/world/item/ItemStack;" -> "EMPTY";
                    case "net/minecraft/world/item/Items.f_42741_:Lnet/minecraft/world/item/Item;" -> "ELYTRA";
                    case "net/minecraft/client/model/PlayerModel.f_102608_:F" -> "attackTime";
                    case "net/minecraft/client/model/PlayerModel.f_102609_:Z" -> "riding";
                    case "net/minecraft/client/model/PlayerModel.f_102610_:Z" -> "young";
                    case "net/minecraft/client/model/PlayerModel.f_102817_:Z" -> "crouching";
                    case "net/minecraft/client/model/PlayerModel.f_102808_:Lnet/minecraft/client/model/geom/ModelPart;" -> "head";
                    case "net/minecraft/client/model/PlayerModel.f_102809_:Lnet/minecraft/client/model/geom/ModelPart;" -> "hat";
                    case "net/minecraft/client/model/PlayerModel.f_102810_:Lnet/minecraft/client/model/geom/ModelPart;" -> "body";
                    case "net/minecraft/client/model/PlayerModel.f_102811_:Lnet/minecraft/client/model/geom/ModelPart;" -> "rightArm";
                    case "net/minecraft/client/model/PlayerModel.f_102812_:Lnet/minecraft/client/model/geom/ModelPart;" -> "leftArm";
                    case "net/minecraft/client/model/PlayerModel.f_102813_:Lnet/minecraft/client/model/geom/ModelPart;" -> "rightLeg";
                    case "net/minecraft/client/model/PlayerModel.f_102814_:Lnet/minecraft/client/model/geom/ModelPart;" -> "leftLeg";
                    case "net/minecraft/client/model/PlayerModel.f_103374_:Lnet/minecraft/client/model/geom/ModelPart;" -> "leftSleeve";
                    case "net/minecraft/client/model/PlayerModel.f_103375_:Lnet/minecraft/client/model/geom/ModelPart;" -> "rightSleeve";
                    case "net/minecraft/client/model/PlayerModel.f_103376_:Lnet/minecraft/client/model/geom/ModelPart;" -> "leftPants";
                    case "net/minecraft/client/model/PlayerModel.f_103377_:Lnet/minecraft/client/model/geom/ModelPart;" -> "rightPants";
                    case "net/minecraft/client/model/PlayerModel.f_103378_:Lnet/minecraft/client/model/geom/ModelPart;" -> "jacket";
                    case "net/minecraft/client/model/geom/ModelPart.f_104207_:Z" -> "visible";
                    case "net/minecraft/client/renderer/texture/OverlayTexture.f_118083_:I" -> "NO_OVERLAY";
                    default -> name;
                };
            }

            @Override public String mapMethodName(String owner, String name, String descriptor) {
                if (!types.containsKey(owner)) return name;
                return switch (owner + "." + name + descriptor) {
                    case "net/minecraft/client/Minecraft.m_18695_()Z" -> "isSameThread";
                    case "net/minecraft/client/player/AbstractClientPlayer.m_9236_()Lnet/minecraft/world/level/Level;" -> "level";
                    case "com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/RemotePlayerPreviewRenderer$PreviewPlayer.m_6134_()F" -> "getScale";
                    case "com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/RemotePlayerPreviewRenderer$PreviewPlayer.m_20206_()F" -> "getBbHeight";
                    case "net/minecraft/client/gui/GuiGraphics.m_280168_()Lcom/mojang/blaze3d/vertex/PoseStack;" -> "pose";
                    case "com/mojang/blaze3d/vertex/PoseStack.m_85836_()V" -> "pushPose";
                    case "com/mojang/blaze3d/vertex/PoseStack.m_85849_()V" -> "popPose";
                    case "com/mojang/blaze3d/vertex/PoseStack.m_252880_(FFF)V" -> "translate";
                    case "net/minecraft/client/gui/screens/inventory/InventoryScreen.m_280432_(Lnet/minecraft/client/gui/GuiGraphics;IIILorg/joml/Quaternionf;Lorg/joml/Quaternionf;Lnet/minecraft/world/entity/LivingEntity;)V" -> "renderEntityInInventory";
                    case "net/minecraft/resources/ResourceLocation.m_135820_(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;" -> "tryParse";
                    case "com/mojang/blaze3d/platform/Lighting.m_84931_()V" -> "setupFor3DItems";
                    case "net/minecraft/client/gui/GuiGraphics.m_280091_()Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;" -> "bufferSource";
                    case "net/minecraft/client/model/PlayerModel.m_103119_(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;" -> "renderType";
                    case "net/minecraft/client/model/PlayerModel.m_7695_(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V" -> "renderToBuffer";
                    case "net/minecraft/client/model/PlayerModel.m_8009_(Z)V" -> "setAllVisible";
                    case "net/minecraft/client/model/geom/ModelPart.m_233569_()V" -> "resetPose";
                    case "net/minecraft/client/renderer/MultiBufferSource$BufferSource.m_109911_()V" -> "endBatch";
                    case "net/minecraft/client/renderer/MultiBufferSource$BufferSource.m_6299_(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;" -> "getBuffer";
                    default -> name;
                };
            }
        };
    }

    public static final class Graphics {
        static final java.util.List<CpmBakedDisplayProbe.LoadingFrame> loadingFrames = new java.util.ArrayList<>();
        final java.util.List<int[]> fills = new java.util.ArrayList<>();
        final java.util.List<String> drawn = new java.util.ArrayList<>();
        public void fill(int x, int y, int right, int bottom, int color) {
            fills.add(new int[]{x, y, right, bottom, color});
        }
        public int drawString(CpmOwnedPreviewDispatchProbe.FontTerminal font, CpmOwnedPreviewDispatchProbe.TextValue text,
                int x, int y, int color, boolean shadow) {
            drawn.add(text.getString());
            return x;
        }
        public void blit(Location texture, int x, int y, int width, int height, float u, float v,
                int sourceWidth, int sourceHeight, int textureWidth, int textureHeight) {
            loadingFrames.add(new CpmBakedDisplayProbe.LoadingFrame(texture.value(), x, y, u, v, width, height, sourceWidth, sourceHeight, textureWidth, textureHeight));
        }
        public void blit(CpmOwnedPreviewDispatchProbe.Text texture, int x, int y, int width, int height, float u, float v,
                int sourceWidth, int sourceHeight, int textureWidth, int textureHeight) {
            loadingFrames.add(new CpmBakedDisplayProbe.LoadingFrame(texture.toString(), x, y, u, v, width, height, sourceWidth, sourceHeight, textureWidth, textureHeight));
        }
        private final Pose pose = new Pose();
        public void enableScissor(int x, int y, int right, int bottom) {}
        public void disableScissor() {}
        public Pose pose() { return pose; }
        public CpmBakedDisplayProbe.Buffers bufferSource() { return new CpmBakedDisplayProbe.Buffers(); }
        public void flush() { CpmBakedDisplayProbe.flush(); }
    }
    public static final class Pose {
        private final java.util.ArrayDeque<Entry> entries = new java.util.ArrayDeque<>(java.util.List.of(new Entry()));
        public Entry last() { return entries.getLast(); }
        public void pushPose() { CpmBakedDisplayProbe.push(); entries.addLast(new Entry()); }
        public void popPose() {
            if (entries.size() == 1) throw new IllegalStateException("Cannot pop the incoming pose");
            entries.removeLast();
            CpmBakedDisplayProbe.pop();
        }
        public static final class Entry {
            public org.joml.Matrix4f pose() { return new ScaleMatrix(); }
            public org.joml.Matrix3f normal() { return new org.joml.Matrix3f(); }
        }
        private static final class ScaleMatrix extends org.joml.Matrix4f {
            @Override public org.joml.Matrix4f scale(float x,float y,float z) {
                CpmBakedDisplayProbe.scaled(x,y,z);
                return super.scale(x,y,z);
            }
        }
        public void scale(float x, float y, float z) { CpmBakedDisplayProbe.scaled(x,y,z); }
        public void translate(float x, float y, float z) { CpmBakedDisplayProbe.translated(x, y); }
        public void mulPose(org.joml.Quaternionfc rotation) {}
        public void mulPose(org.joml.Quaternionf rotation) {}
    }
    public static final class Inventory {
        static Runnable draw = () -> {};
        static float x;
        public static void renderEntityInInventory(Graphics graphics, int x, int y, int scale,
                org.joml.Quaternionf rotation, org.joml.Quaternionf camera, Entity entity) { Inventory.x=x;draw.run(); }
        public static void renderEntityInInventory(Graphics graphics, float x, float y, float scale, org.joml.Vector3f translation,
                org.joml.Quaternionf rotation, org.joml.Quaternionf camera, Entity entity) { Inventory.x=x;draw.run(); }
    }

    static void submissionBodyProducer(PreviewRenderer.PreviewAppearance appearance, Runnable binding, Runnable cleanupFailure,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture gallery) throws Exception {
        if (CpmPreviewScopeProbe.class.getClassLoader().getResource("com/naocraftlab/skins/compat/client/identifier/submission/LivePreviewRenderer.class") == null) return;
        CpmBakedDisplayProbe.submissionBodyProducer(appearance, binding, cleanupFailure, gallery);
    }

    public static final class AvatarState { public float ageInTicks; }
    public static final class Physics { public void tick(Vector position, Vector motion) {} }
    public static final class Vector { public static final Vector ZERO = new Vector(); }
    public interface Delta { float getGameTimeDeltaPartialTick(boolean paused); }
    public static final class Dispatcher {
        public AvatarState extractEntity(Entity player, float partialTick) { return new AvatarState(); }
        public void submit(AvatarState state, Camera camera, double x, double y, double z, Pose pose, Collector collector) {}
    }
    public static final class GameRenderer {
        public Light getLighting() { return new Light(); }
        public Features getFeatureRenderDispatcher() { return new Features(); }
    }
    public static final class Light { public enum Entry { ENTITY_IN_UI, PLAYER_SKIN } public void setupFor(Entry entry) {} }
    public static final class Camera { public org.joml.Quaternionf orientation; }
    public interface Collector {}
    public static final class Storage implements Collector {}
    public static final class Features {
        public Storage getSubmitNodeStorage() { return new Storage(); }
        public void renderAllFeatures() { Inventory.draw.run(); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class Level {}
    public static final class Client {
        public static final boolean ON_OSX = false;
        static Client current;
        public static Client getInstance() { return current; }
        public final GameRenderer gameRenderer = new GameRenderer();
        public Delta getDeltaTracker() { return ignored -> 0; }
        public Dispatcher getEntityRenderDispatcher() { return new Dispatcher(); }
        public Level level = new Level();
        public Avatar player;
        public boolean isSameThread() { return true; }
    }
    public static class Entity {
        private final Level level;
        Entity(Level level) { this.level = level; }
        public Level level() { return level; }
        public float getScale() { return 1; }
        public float getBbHeight() { return 1.8F; }
    }
    public static class Player extends Entity { Player(Level level) { super(level); } }
    public static final class Avatar extends Player {
        public int tickCount;
        Avatar(Level level) { super(level); }
        public Physics avatarState() { return new Physics(); }
        public Vector position() { return Vector.ZERO; }
    }
    public enum Slot { HEAD, CHEST, LEGS, FEET, MAINHAND, OFFHAND }
    public enum Part { CAPE, HAT, JACKET, LEFT_SLEEVE, RIGHT_SLEEVE, LEFT_PANTS_LEG, RIGHT_PANTS_LEG }
    public interface ItemLike {}
    public static final class Item implements ItemLike {}
    public static final class Items { public static final Item ELYTRA = new Item(); }
    public static final class Stack {
        public static final Stack EMPTY = new Stack(null);
        final ItemLike item;
        public Stack(ItemLike item) { this.item = item; }
        public Stack copy() { return new Stack(item); }
    }
    public record Location(String value) {
        public Location(String namespace, String path) { this(namespace + ":" + path); }
        public static Location tryParse(String value) { return new Location(value); }
    }
    public record Skin(Location texture, String textureUrl, Location capeTexture, Location elytraTexture,
            Model model, boolean secure) {
        public enum Model { SLIM, WIDE }
    }
}

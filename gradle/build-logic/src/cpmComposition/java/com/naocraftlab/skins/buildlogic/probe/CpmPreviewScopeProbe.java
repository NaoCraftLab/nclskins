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
    public static void main(String[] args) throws Exception {
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
        System.out.println("Actual production PreviewScope methods passed: native/Sneaky bundle, exact atlas, default elytra, isolation and cleanup; synthetic host ABI, not native game rendering");
    }

    static void bodyProducer(PreviewRenderer.PreviewAppearance appearance, Runnable binding, Runnable cleanupFailure) throws Exception {
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
                if (!name.equals(owner) && !name.startsWith(prefix + "PreviewScope")
                        && !name.startsWith(prefix + "BakedPlayerPose") && !name.startsWith(prefix + "BakedPreviewLighting")) return super.loadClass(name, resolve);
                Class<?> result = findLoadedClass(name);
                if (result == null) {
                    try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        var node = new org.objectweb.asm.tree.ClassNode();
                        new ClassReader(input).accept(node, 0);
                        if (name.equals(owner)) {
                            node.methods.removeIf(method -> !method.name.equals("render") && !method.name.equals("body")
                                    && !java.util.Set.of("renderFallback", "location", "parseTexture", "configureLayers").contains(method.name)
                                    && !method.name.startsWith("lambda$render"));
                            var init = new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                            init.visitVarInsn(Opcodes.ALOAD, 0);
                            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                            init.visitInsn(Opcodes.RETURN);
                            init.visitMaxs(1, 1);
                            node.methods.add(init);
                            String self = owner.replace('.', '/');
                            String avatar = "L" + self + "$PreviewPlayer;";
                            for (String helper : java.util.List.of("configureEntity", "renderBackEquipment", "onLiveLayerFailure", "clearAccessoryInspection")) {
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
                            proxy.visitVarInsn(Opcodes.ALOAD, 0); proxy.visitFieldInsn(Opcodes.GETFIELD, self, "previewPlayer", avatar);
                            proxy.visitInsn(Opcodes.ARETURN); proxy.visitMaxs(1, 2); node.methods.add(proxy);
                            var accessories = new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC, "accessories",
                                    "(Lcom/naocraftlab/skins/client/PreviewRenderer$PreviewRequest;)Lcom/naocraftlab/skins/client/PreviewAccessories;", null, null);
                            accessories.visitFieldInsn(Opcodes.GETSTATIC, "com/naocraftlab/skins/client/PreviewRenderer$LiveReason", "LIVE_UNAVAILABLE",
                                    "Lcom/naocraftlab/skins/client/PreviewRenderer$LiveReason;");
                            accessories.visitMethodInsn(Opcodes.INVOKESTATIC, "com/naocraftlab/skins/client/PreviewAccessories", "unavailable",
                                    "(Lcom/naocraftlab/skins/client/PreviewRenderer$LiveReason;)Lcom/naocraftlab/skins/client/PreviewAccessories;", false);
                            accessories.visitInsn(Opcodes.ARETURN); accessories.visitMaxs(1, 2); node.methods.add(accessories);
                            node.fields.removeIf(field -> !java.util.Set.of("session", "minecraft", "closed", "previewPlayer", "previewLevel",
                                    "diagnostics", "accessoryObserver", "previewProfile", "classicModel", "slimModel", "classic", "slim",
                                    "classicCloak", "slimCloak", "POSE_OPERATIONS").contains(field.name));
                            for (var field : node.fields) if (field.name.equals("POSE_OPERATIONS")) field.access &= ~Opcodes.ACC_FINAL;
                            node.innerClasses.clear(); node.nestMembers = null;
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
        } finally { Inventory.draw = () -> {}; }
        System.out.println("Actual immediate body producer passed: " + family + "; remapped host/GPU terminals, genuine CPM bind and native scope/session path");
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
        public Pose pose() { return new Pose(); }
        public CpmBakedDisplayProbe.Buffers bufferSource() { return new CpmBakedDisplayProbe.Buffers(); }
        public void flush() { CpmBakedDisplayProbe.flush(); }
    }
    public static final class Pose {
        public void pushPose() { CpmBakedDisplayProbe.push(); }
        public void popPose() { CpmBakedDisplayProbe.pop(); }
        public void scale(float x, float y, float z) {}
        public void translate(float x, float y, float z) {}
        public void mulPose(org.joml.Quaternionfc rotation) {}
    }
    public static final class Inventory {
        static Runnable draw = () -> {};
        public static void renderEntityInInventory(Graphics graphics, int x, int y, int scale,
                org.joml.Quaternionf rotation, org.joml.Quaternionf camera, Entity entity) { draw.run(); }
        public static void renderEntityInInventory(Graphics graphics, float x, float y, float scale, org.joml.Vector3f translation,
                org.joml.Quaternionf rotation, org.joml.Quaternionf camera, Entity entity) { draw.run(); }
    }

    static void submissionBodyProducer(PreviewRenderer.PreviewAppearance appearance, Runnable binding, Runnable cleanupFailure) throws Exception {
        String prefix = "com.naocraftlab.skins.compat.client.identifier.submission.";
        if (CpmPreviewScopeProbe.class.getClassLoader().getResource(prefix.replace('.', '/') + "LivePreviewRenderer.class") == null) return;
        var types = new java.util.HashMap<String, Class<?>>();
        types.put("net/minecraft/client/Minecraft", Client.class);
        types.put("net/minecraft/client/player/AbstractClientPlayer", Avatar.class);
        types.put("net/minecraft/client/player/LocalPlayer", Avatar.class);
        types.put("net/minecraft/world/entity/Entity", Entity.class);
        types.put("net/minecraft/world/entity/Avatar", Player.class);
        types.put("net/minecraft/world/entity/LivingEntity", Entity.class);
        types.put("net/minecraft/world/entity/EquipmentSlot", Slot.class);
        types.put("net/minecraft/world/entity/player/PlayerModelPart", Part.class);
        types.put("net/minecraft/world/entity/player/PlayerSkin", SubmissionSkin.class);
        types.put("net/minecraft/world/item/ItemStack", Stack.class);
        types.put("net/minecraft/client/gui/render/pip/PictureInPictureRenderer", Pip.class);
        types.put("net/minecraft/client/renderer/MultiBufferSource$BufferSource", Buffers.class);
        types.put("net/minecraft/client/renderer/entity/state/EntityRenderState", AvatarState.class);
        types.put("net/minecraft/client/renderer/entity/state/AvatarRenderState", AvatarState.class);
        types.put("net/minecraft/client/entity/ClientAvatarState", Physics.class);
        types.put("net/minecraft/world/phys/Vec3", Vector.class);
        types.put("net/minecraft/client/DeltaTracker", Delta.class);
        types.put("net/minecraft/client/renderer/entity/EntityRenderDispatcher", Dispatcher.class);
        types.put("net/minecraft/client/renderer/GameRenderer", GameRenderer.class);
        types.put("com/mojang/blaze3d/platform/Lighting", Light.class);
        types.put("com/mojang/blaze3d/platform/Lighting$Entry", Light.Entry.class);
        types.put("com/mojang/blaze3d/vertex/PoseStack", Pose.class);
        types.put("net/minecraft/client/renderer/state/CameraRenderState", Camera.class);
        types.put("net/minecraft/client/renderer/feature/FeatureRenderDispatcher", Features.class);
        types.put("net/minecraft/client/renderer/SubmitNodeStorage", Storage.class);
        types.put("net/minecraft/client/renderer/SubmitNodeCollector", Collector.class);
        types.put((prefix + "SubmissionPreviewRenderer").replace('.', '/'), Submission.class);
        types.put((prefix + "SubmissionPreviewRenderer$PreviewPlayer").replace('.', '/'), Avatar.class);
        var loader = new ClassLoader(CpmPreviewScopeProbe.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith(prefix) || name.endsWith("SubmissionPreviewRenderer")) return super.loadClass(name, resolve);
                Class<?> result = findLoadedClass(name);
                if (result == null) {
                    try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        var node = new org.objectweb.asm.tree.ClassNode();
                        new ClassReader(input).accept(node, 0);
                        if (name.endsWith("LivePreviewRenderState")) {
                            node.interfaces.clear(); node.innerClasses.clear(); node.nestMembers = null;
                            node.methods.removeIf(method -> !java.util.Set.of("previewPlayer", "previewContext", "request", "previewAge",
                                    "translation", "rotation", "overrideCameraAngle", "failureSink", "layerFailureSink").contains(method.name));
                            node.fields.removeIf(field -> field.name.equals("bounds") || field.name.equals("scissorArea"));
                            var init = new org.objectweb.asm.tree.MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                            init.visitVarInsn(Opcodes.ALOAD, 0); init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                            init.visitInsn(Opcodes.RETURN); init.visitMaxs(1, 1); node.methods.add(init);
                        }
                        if (name.endsWith("LivePreviewRenderer")) node.methods.removeIf(method ->
                                method.name.equals("renderToTexture") && (method.access & Opcodes.ACC_BRIDGE) != 0);
                        var writer = new ClassWriter(0);
                        node.accept(new ClassRemapper(writer, new Remapper(Opcodes.ASM9) {
                            @Override public String map(String internalName) {
                                Class<?> mapped = types.get(internalName);
                                return mapped == null ? internalName : mapped.getName().replace('.', '/');
                            }
                        }));
                        byte[] bytes = writer.toByteArray(); result = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
                }
                if (resolve) resolveClass(result);
                return result;
            }
        };
        Client client = new Client(); client.player = new Avatar(client.level); Client.current = client;
        Class<?> contextType = loader.loadClass(prefix + "PreviewContext");
        var constructor = contextType.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        var session = new com.naocraftlab.skins.client.EditorPreviewSession();
        boolean[] current = {true};
        Object context = constructor.newInstance(client.player, appearance, new SubmissionSkin(), Stack.EMPTY,
                (java.util.function.BooleanSupplier) () -> current[0], session.displayBody(() -> current[0]));
        Class<?> stateType = loader.loadClass(prefix + "LivePreviewRenderState");
        Object state = stateType.getConstructor().newInstance();
        Object failureSink = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{loader.loadClass(prefix + "PreviewFailureSink")},
                (proxy, method, args) -> null);
        for (var field : stateType.getDeclaredFields()) {
            field.setAccessible(true);
            Object value = switch (field.getName()) {
                case "previewPlayer" -> client.player;
                case "previewContext" -> context;
                case "request" -> new PreviewRenderer.PreviewRequest(appearance, 0, 0, 160, 180, 0, 0, 1, PreviewRenderer.PreviewIntent.EDITOR_DRAFT);
                case "translation" -> new org.joml.Vector3f();
                case "rotation", "overrideCameraAngle" -> new org.joml.Quaternionf();
                case "failureSink", "layerFailureSink" -> failureSink;
                default -> null;
            };
            if (value != null) field.set(state, value);
        }
        Class<?> rendererType = loader.loadClass(prefix + "LivePreviewRenderer");
        Object renderer = rendererType.getConstructor(Buffers.class).newInstance(new Buffers());
        var render = rendererType.getDeclaredMethod("renderToTexture", stateType, Pose.class); render.setAccessible(true);
        Inventory.draw = binding;
        try {
            check(session.body().status() == PreviewRenderer.BodyStatus.ORDINARY, "queued submission/context is not completed display");
            render.invoke(renderer, state, new Pose());
            check(session.body().status() == PreviewRenderer.BodyStatus.READY,
                    "actual submission features -> genuine bind -> captured context -> post-cleanup body commit");
            Inventory.draw = () -> {};
            render.invoke(renderer, state, new Pose());
            check(session.body().status() == PreviewRenderer.BodyStatus.ORDINARY && session.body().current().getAsBoolean(),
                    "actual submission ordinary feature execution and cleanup retain positive ordinary proof");
            Inventory.draw = () -> {
                binding.run();
                check(com.naocraftlab.skins.client.EditorPreviewLayerGuard.handle(new IllegalStateException("synthetic handled feature failure")),
                        "submission layer guard is installed");
            };
            render.invoke(renderer, state, new Pose());
            check(session.body().status() == PreviewRenderer.BodyStatus.ORDINARY, "handled layer failure is not successful submission display");
            check(!session.body().current().getAsBoolean(), "handled submission failure is unconfirmed, not ordinary");
            Inventory.draw = cleanupFailure;
            render.invoke(renderer, state, new Pose());
            check(session.body().status() == PreviewRenderer.BodyStatus.ORDINARY, "owned submission cleanup failure never commits candidate");
            Inventory.draw = () -> { binding.run(); throw new IllegalStateException("synthetic feature failure"); };
            render.invoke(renderer, state, new Pose());
            check(session.body().status() == PreviewRenderer.BodyStatus.ORDINARY, "handled actual feature failure clears body");
            Inventory.draw = () -> { binding.run(); current[0] = false; };
            render.invoke(renderer, state, new Pose());
            check(session.body().status() == PreviewRenderer.BodyStatus.ORDINARY, "retired deferred context cannot publish body");
        } finally { Inventory.draw = () -> {}; Client.current = null; }
        System.out.println("Actual submission body producer passed: remapped host/GPU terminals, genuine CPM bind and actual context/scope/session path");
    }

    public static class Pip { public Pip(Buffers buffers) {} }
    public static final class Buffers {}
    public static final class SubmissionSkin { public Object cape() { return null; } }
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
    public static final class Submission {
        public static void configure(AvatarState state, PreviewRenderer.PreviewRequest request) {}
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class Level {}
    public static final class Client {
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

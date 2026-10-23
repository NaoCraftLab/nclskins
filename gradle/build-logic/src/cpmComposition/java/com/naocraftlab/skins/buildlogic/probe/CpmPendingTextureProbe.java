package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.EditorPreviewSession;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class CpmPendingTextureProbe {
    private static int fallbacks;
    private static int liveEntries;
    private static boolean textureReady;

    public static void run() throws Exception {
        String owner = "com/naocraftlab/skins/compat/client/resourcelocation/skinlookup/VanillaAppearancePreviewRenderer";
        var resources = java.util.Collections.list(CpmPendingTextureProbe.class.getClassLoader().getResources(owner + ".class"))
                .stream().filter(resource -> resource.getProtocol().equals("file")).toList();
        if (resources.isEmpty()) return;
        check(resources.size() == 1, "one compiled player-skin renderer before remapping");
        var node = new ClassNode(Opcodes.ASM9);
        try (var input = resources.get(0).openStream()) { new ClassReader(input).accept(node, 0); }
        var renders = node.methods.stream().filter(method -> method.name.equals("render")
                && method.desc.startsWith("(Lnet/minecraft/client/gui/GuiGraphics;")).toList();
        check(renders.size() == 1, "exact typed render entrypoint");
        MethodNode render = renders.get(0);
        boolean liveTerminal = false;
        for (var instruction : render.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals("previewPlayer")) {
                for (var next = instruction.getNext(); next != null;) {
                    var after = next.getNext(); render.instructions.remove(next); next = after;
                }
                render.instructions.set(call, new InsnNode(Opcodes.POP2));
                render.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, internal(CpmPendingTextureProbe.class), "live", "()V", false));
                render.instructions.add(new InsnNode(Opcodes.RETURN));
                liveTerminal = true;
                break;
            }
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && (call.name.equals("accessories") || call.name.equals("renderFallback"))) {
                var replacement = new org.objectweb.asm.tree.InsnList();
                replacement.add(new InsnNode(Opcodes.POP2));
                if (call.name.equals("accessories")) replacement.add(new InsnNode(Opcodes.ACONST_NULL));
                else {
                    replacement.add(new InsnNode(Opcodes.POP));
                    replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, internal(CpmPendingTextureProbe.class), "fallback", "()V", false));
                }
                render.instructions.insertBefore(call, replacement);
                render.instructions.remove(call);
            }
        }
        check(liveTerminal, "live boundary retained after original texture/path branch");
        render.tryCatchBlocks.clear();
        render.localVariables = null;
        render.visibleLocalVariableAnnotations = null;
        render.invisibleLocalVariableAnnotations = null;
        Map<String, MethodNode> ownMethods = new java.util.HashMap<>();
        node.methods.forEach(method -> ownMethods.put(method.name + method.desc, method));
        Set<String> retainedMethods = new HashSet<>();
        var pendingMethods = new java.util.ArrayDeque<String>();
        pendingMethods.add(render.name + render.desc);
        Set<List<String>> retainedFields = new HashSet<>();
        Set<String> lifecycleCalls = new HashSet<>();
        while (!pendingMethods.isEmpty()) {
            String identity = pendingMethods.remove();
            if (!retainedMethods.add(identity)) continue;
            MethodNode method = ownMethods.get(identity);
            check(method != null, "retained exact own method exists: " + identity);
            for (var instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field && field.owner.equals(owner)) {
                    retainedFields.add(List.of(field.owner, field.name, field.desc));
                } else if (instruction instanceof MethodInsnNode call) {
                    if (call.owner.equals(owner)) pendingMethods.add(call.name + call.desc);
                    if (call.owner.equals("com/naocraftlab/skins/client/NativePlayerSkinLifecycle")) lifecycleCalls.add(call.name + call.desc);
                } else if (instruction instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode dynamic) {
                    for (Object argument : dynamic.bsmArgs) {
                        if (argument instanceof org.objectweb.asm.Handle handle && handle.getOwner().equals(owner)) {
                            pendingMethods.add(handle.getName() + handle.getDesc());
                        }
                    }
                }
            }
        }
        check(lifecycleCalls.equals(Set.of("isReady(Ljava/lang/String;)Z", "registration(Ljava/lang/String;)Ljava/util/Optional;")),
                "complete retained lifecycle terminal ABI is audited");
        node.methods.removeIf(method -> !retainedMethods.contains(method.name + method.desc));
        check(retainedFields.stream().allMatch(identity -> node.fields.stream().anyMatch(field ->
                identity.equals(List.of(owner, field.name, field.desc)))), "retained own field references have exact declarations");
        node.fields.removeIf(field -> !retainedFields.contains(List.of(owner, field.name, field.desc)));
        node.fields.forEach(field -> field.access = Opcodes.ACC_PUBLIC);
        node.superName = "java/lang/Object";
        node.interfaces.clear();
        node.innerClasses.clear();
        node.nestHostClass = null;
        node.nestMembers = null;
        node.access = Opcodes.ACC_PUBLIC;
        var constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(constructor);
        String generated = internal(CpmPendingTextureProbe.class) + "$LinkedRenderer";
        Map<String, String> names = Map.of(owner, generated,
                "net/minecraft/client/Minecraft", internal(Client.class),
                "net/minecraft/client/player/LocalPlayer", "java/lang/Object",
                "net/minecraft/client/multiplayer/ClientLevel", "java/lang/Object",
                "net/minecraft/client/gui/GuiGraphics", "java/lang/Object",
                "com/naocraftlab/skins/client/NativePlayerSkinLifecycle", internal(SkinLifecycle.class));
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(new ClassRemapper(writer, new Remapper(Opcodes.ASM9) {
            @Override public String map(String name) { return names.getOrDefault(name, name); }
        }));
        Class<?> linked = new ClassLoader(CpmPendingTextureProbe.class.getClassLoader()) {
            Class<?> link() { byte[] bytes = writer.toByteArray(); return defineClass(generated.replace('/', '.'), bytes, 0, bytes.length); }
        }.link();
        for (boolean world : new boolean[]{true, false}) {
            for (boolean cpm : new boolean[]{true, false}) {
                long[] nanos = {0};
                var session = new EditorPreviewSession(() -> nanos[0]);
                session.beginBody("fixture:pending", cpm, world);
                Object receiver = linked.getConstructor().newInstance();
                var client = new Client();
                client.level = world ? new Object() : null;
                client.player = world ? new Object() : null;
                linked.getField("minecraft").set(receiver, client);
                linked.getField("session").set(receiver, session);
                linked.getField("accessoryObserver").set(receiver, (Consumer<Object>) value -> {});
                var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:pending", 64, 64),
                        SkinModel.CLASSIC, Optional.empty(), PreviewRenderer.CapeMode.OFF, true);
                var request = new PreviewRenderer.PreviewRequest(appearance, 0, 0, 100, 100, 0, 0, 1,
                        PreviewRenderer.PreviewIntent.EDITOR_DRAFT);
                textureReady = false;
                fallbacks = liveEntries = 0;
                linked.getMethod("render", Object.class, PreviewRenderer.PreviewRequest.class).invoke(receiver, new Object(), request);
                check(liveEntries == 0, "pending texture never enters live drawing");
                check(fallbacks == (world && cpm ? 0 : 1), "CPM first texture-loading frame must hide baked body and accessories");
                textureReady = true;
                fallbacks = liveEntries = 0;
                linked.getMethod("render", Object.class, PreviewRenderer.PreviewRequest.class).invoke(receiver, new Object(), request);
                check(world ? liveEntries == 1 : fallbacks == 1, "ready texture reaches non-drawing model preparation; worldless stays baked");
                textureReady = false;
                nanos[0] = java.time.Duration.ofSeconds(60).toNanos();
                fallbacks = liveEntries = 0;
                linked.getMethod("render", Object.class, PreviewRenderer.PreviewRequest.class).invoke(receiver, new Object(), request);
                check(fallbacks == 1, "expired/worldless/ordinary texture wait retains baked fallback");
                textureReady = true;
                fallbacks = liveEntries = 0;
                linked.getMethod("render", Object.class, PreviewRenderer.PreviewRequest.class).invoke(receiver, new Object(), request);
                check(world && !cpm ? liveEntries == 1 : fallbacks == 1, "ordinary in-world retains live entry; expired CPM/worldless stays baked");
            }
        }
        var closedSession = new EditorPreviewSession();
        closedSession.displayBody(() -> true).accept(new com.naocraftlab.skins.client.PreviewModelFrames.Body(
                PreviewRenderer.BodyStatus.READY, () -> true));
        check(closedSession.body().status() == PreviewRenderer.BodyStatus.READY, "closed control starts with displayed body");
        Object closedReceiver = linked.getConstructor().newInstance();
        linked.getField("session").set(closedReceiver, closedSession);
        linked.getField("closed").setBoolean(closedReceiver, true);
        fallbacks = liveEntries = 0;
        linked.getMethod("render", Object.class, PreviewRenderer.PreviewRequest.class).invoke(closedReceiver, null, null);
        check(closedSession.body().status() == PreviewRenderer.BodyStatus.ORDINARY && fallbacks == 0 && liveEntries == 0,
                "closed render invalidates displayed body before texture/path lookup or drawing");
        System.out.println("PASS compiled player-skin first-frame texture branch: CPM hidden pending, finite fallback, ordinary/worldless and closed controls; synthetic GPU terminals");
    }

    public static void fallback() { fallbacks++; }
    public static void live() { liveEntries++; }
    public static final class Client { public Object level; public Object player; }
    public static final class SkinLifecycle {
        public static boolean isReady(String location) { return textureReady; }
        public static Optional<Object> registration(String location) { return Optional.empty(); }
    }
    private static String internal(Class<?> type) { return type.getName().replace('.', '/'); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

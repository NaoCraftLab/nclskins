package com.naocraftlab.skins.buildlogic.probe;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

final class CpmPreviewScreenSlice {
    private static final List<String> OWNERS = List.of(
            "com.naocraftlab.skins.compat.gui.immediate.NclSkinsImmediateScreen",
            "com.naocraftlab.skins.compat.client.identifier.submission.NclSkinsScreen",
            "com.naocraftlab.skins.compat.client.identifier.extraction.NclSkinsScreen");

    static String availableOwner() {
        var present = OWNERS.stream().filter(name -> CpmPreviewScreenSlice.class.getClassLoader()
                .getResource(name.replace('.', '/') + ".class") != null).toList();
        if (present.size() != 1) throw new AssertionError("Expected exactly one target-compiled screen family: " + present);
        return present.get(0);
    }

    static Class<?> load(String owner) throws Exception {
        return load(owner, false);
    }

    static Class<?> load(String owner, boolean textConsumers) throws Exception {
        String internalOwner = owner.replace('.', '/');
        String namespace = internalOwner.substring(0, internalOwner.lastIndexOf('/') + 1);
        Map<String, String> names = new HashMap<>();
        names.put("net/minecraft/client/gui/GuiGraphics", internal(CpmOwnedPreviewDispatchProbe.Graphics.class));
        names.put("net/minecraft/client/gui/GuiGraphicsExtractor", internal(CpmOwnedPreviewDispatchProbe.Graphics.class));
        if (textConsumers) {
            String screen = internal(CpmOwnedPreviewDispatchProbe.ScreenTerminal.class);
            names.put("net/minecraft/client/gui/screens/Screen", screen);
            if (internalOwner.contains("/extraction/")) names.put(namespace + "GuiExtraction$ScreenHost", screen);
            names.put("net/minecraft/client/Minecraft", internal(CpmOwnedPreviewDispatchProbe.ClientTerminal.class));
            names.put("net/minecraft/client/gui/Font", internal(CpmOwnedPreviewDispatchProbe.FontTerminal.class));
            for (String type : List.of("net/minecraft/network/chat/Component", "net/minecraft/network/chat/FormattedText", "net/minecraft/util/FormattedCharSequence")) {
                names.put(type, internal(CpmOwnedPreviewDispatchProbe.TextValue.class));
            }
            names.put("net/minecraft/network/chat/MutableComponent", internal(CpmOwnedPreviewDispatchProbe.Text.class));
            names.put(namespace + "ExtractionGuiApi", internal(CpmOwnedPreviewDispatchProbe.GuiTerminal.class));
        }
        names.put("com/naocraftlab/skins/runtime/ClientRuntime", internal(CpmOwnedPreviewDispatchProbe.RuntimeBoundary.class));
        names.put("com/naocraftlab/skins/compat/gui/immediate/ImmediateScreenCapabilities", internal(CpmOwnedPreviewDispatchProbe.Capabilities.class));
        names.put("com/naocraftlab/skins/compat/client/identifier/IdentifierTextureRegistry", internal(CpmOwnedPreviewDispatchProbe.Registry.class));
        for (String renderer : List.of("SubmissionPreviewRenderer", "SimplePreviewRenderer", "AvatarRenderStatePreviewRenderer", "AvatarPipPreviewRenderer")) {
            names.put(namespace + renderer, internal(CpmOwnedPreviewDispatchProbe.RenderTerminal.class));
        }
        ClassLoader loader = new ClassLoader(CpmPreviewScreenSlice.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                boolean components = textConsumers && (name.equals(namespace.replace('/', '.') + "SubmissionComponents")
                        || name.equals(namespace.replace('/', '.') + "MinecraftClientComponents"));
                if (!name.equals(owner) && !name.startsWith(owner + "$") && !components) return super.loadClass(name, resolve);
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        var node = new ClassNode(Opcodes.ASM9);
                        new ClassReader(input).accept(node, 0);
                        if (name.equals(owner)) {
                            verifyRetirementWiring(node);
                            verifyPaintOrdering(node);
                            isolate(node, textConsumers);
                        }
                        var writer = new ClassWriter(0);
                        node.accept(new ClassRemapper(writer, new Remapper(Opcodes.ASM9) {
                            @Override public String map(String value) { return names.getOrDefault(value, value); }
                            @Override public String mapMethodName(String owner, String name, String descriptor) {
                                return textConsumers && owner.equals("net/minecraft/client/gui/GuiGraphics")
                                        && name.equals("drawString") && descriptor.endsWith(")I") ? "drawStringResult" : name;
                            }
                        }));
                        byte[] bytes = writer.toByteArray();
                        type = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
                }
                if (resolve) resolveClass(type);
                return type;
            }
        };
        return loader.loadClass(owner);
    }

    private static void isolate(ClassNode node, boolean textConsumers) {
        boolean immediate = node.name.endsWith("NclSkinsImmediateScreen");
        List<String> roots = textConsumers ? List.of("getNarrationMessage", immediate || node.name.contains("submission") ? "renderText" : "drawText")
                : immediate ? List.of("synchronizePreviews", "renderPreview", "releasePreviews")
                : node.name.contains("submission") ? List.of("synchronizePreviewAssets", "renderPreviews", "releasePreviewBody", "releasePreviewAccessories")
                : List.of("syncPreviewAssets", "drawPreviews", "releasePreviewBody", "releasePreviewAccessories");
        Map<String, MethodNode> methods = new HashMap<>();
        for (MethodNode method : node.methods) methods.put(method.name + method.desc, method);
        Set<String> retained = new HashSet<>(), fields = new HashSet<>();
        var pending = new ArrayDeque<String>();
        for (String root : roots) {
            var matches = node.methods.stream().filter(method -> method.name.equals(root)).toList();
            if (matches.size() != 1) throw new AssertionError("Exact screen seam absent/ambiguous: " + root);
            pending.add(matches.get(0).name + matches.get(0).desc);
        }
        while (!pending.isEmpty()) {
            String identity = pending.remove();
            if (!retained.add(identity)) continue;
            MethodNode method = methods.get(identity);
            if (method == null) throw new AssertionError("Missing own compiled helper: " + identity);
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                    pending.add(call.name + call.desc);
                } else if (instruction instanceof FieldInsnNode field && field.owner.equals(node.name)) {
                    fields.add(field.name);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    for (Object argument : dynamic.bsmArgs) {
                        if (argument instanceof Handle handle && handle.getOwner().equals(node.name)) {
                            pending.add(handle.getName() + handle.getDesc());
                        }
                    }
                }
            }
        }
        if (immediate) fields.add("capabilities");
        node.methods.removeIf(method -> !retained.contains(method.name + method.desc));
        node.fields.removeIf(field -> !fields.contains(field.name));
        node.fields.forEach(field -> field.access = (field.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL)) | Opcodes.ACC_PUBLIC);
        node.superName = textConsumers ? "net/minecraft/client/gui/screens/Screen" : "java/lang/Object";
        node.interfaces.clear();
        node.signature = null;
        node.access = (node.access & ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_FINAL)) | Opcodes.ACC_PUBLIC;
        var constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        constructor.maxStack = 1;
        constructor.maxLocals = 1;
        node.methods.add(constructor);
        long branches = node.methods.stream().flatMap(method -> java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false))
                .filter(instruction -> instruction instanceof MethodInsnNode call && call.owner.equals("com/naocraftlab/skins/runtime/ViewSpec$Preview") && call.name.equals("requiresLoadedSkin")).count();
        if (!textConsumers && branches != 2) throw new AssertionError("Both production loaded/borrowed predicates must remain executable: " + branches);
    }

    private static void verifyPaintOrdering(ClassNode node) throws IOException {
        boolean immediate = node.name.endsWith("NclSkinsImmediateScreen");
        boolean submission = node.name.contains("submission");
        String entry = immediate || submission ? "render" : "extractFrame";
        String preview = immediate || submission ? "renderPreviews" : "drawPreviews";
        String reconcile = immediate ? "synchronizeWidgets" : submission ? "refresh" : "snapshotChanged";
        String controls = immediate ? "pointerOwnerAt" : submission ? "renderWidgetsClipped" : "extractRenderablesClipped";
        var matches = node.methods.stream().filter(candidate -> candidate.name.equals(entry)).toList();
        if (matches.size() != 1) throw new AssertionError("Exact compiled paint body is absent or ambiguous: " + node.name + "." + entry);
        var method = matches.get(0);
        if (!immediate && !submission) verifyExtractionCallback(node, method);
        int previewAt = -1, reconcileAt = -1, controlsAt = -1, index = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                if (call.name.equals(preview) && previewAt < 0) previewAt = index;
                if (previewAt >= 0 && call.name.equals(reconcile) && reconcileAt < 0) reconcileAt = index;
                if (call.name.equals(controls) && controlsAt < 0) controlsAt = index;
            }
            index++;
        }
        if (!(previewAt >= 0 && reconcileAt > previewAt && controlsAt > reconcileAt)) {
            throw new AssertionError("Actual compiled paint must reconcile native widgets after preview and before controls: " + node.name);
        }
    }

    private static void verifyExtractionCallback(ClassNode node, MethodNode frame) throws IOException {
        String graphics = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;";
        String frameDescriptor = "(" + graphics + "IIF)V";
        String hostName = node.name.substring(0, node.name.lastIndexOf('/') + 1) + "GuiExtraction$ScreenHost";
        if (!node.superName.equals(hostName) || !frame.desc.equals(frameDescriptor)) {
            throw new AssertionError("Extraction paint must remain behind the exact native screen adapter");
        }
        var host = new ClassNode(Opcodes.ASM9);
        try (var input = CpmPreviewScreenSlice.class.getClassLoader().getResourceAsStream(hostName + ".class")) {
            if (input == null) throw new AssertionError("Missing compiled extraction host: " + hostName);
            new ClassReader(input).accept(host, 0);
        }
        var callbacks = host.methods.stream().filter(method -> method.name.equals("extractRenderState")
                && (method.desc.equals(frameDescriptor) || method.desc.equals("(" + graphics + ")V"))).toList();
        if (callbacks.size() != 1) throw new AssertionError("Extraction host must expose one exact native callback");
        long dispatches = java.util.stream.StreamSupport.stream(callbacks.get(0).instructions.spliterator(), false)
                .filter(instruction -> instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals(hostName) && call.name.equals(frame.name) && call.desc.equals(frameDescriptor)).count();
        if (dispatches != 1) throw new AssertionError("Native extraction callback must dispatch to the shared frame exactly once");
    }

    private static void verifyRetirementWiring(ClassNode node) {
        boolean immediate = node.name.endsWith("NclSkinsImmediateScreen");
        String release = immediate ? "releasePreviews" : "releasePreviewBody";
        var removed = node.methods.stream().filter(method -> method.name.equals("removed") && method.desc.equals("()V")).findFirst().orElseThrow();
        int releaseAt = -1, accessoryAt = -1, closeAt = -1, clearAt = -1;
        int index = 0;
        for (var instruction : removed.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals(node.name) && call.name.equals(release)) releaseAt = index;
                if (call.owner.equals(node.name) && call.name.equals("releasePreviewAccessories")) accessoryAt = index;
                if (call.name.equals("close") && call.owner.endsWith("PreviewRenderer")) closeAt = index;
            }
            if (instruction instanceof FieldInsnNode field && field.name.equals("bodyCapability") && field.getOpcode() == Opcodes.PUTFIELD) clearAt = index;
            index++;
        }
        if (releaseAt < 0 || !immediate && !(clearAt > releaseAt && closeAt > clearAt))
            throw new AssertionError("Compiled removed must retire input and clear the renderer query before owned close");
        if (!immediate && !(accessoryAt >= 0 && accessoryAt < releaseAt))
            throw new AssertionError("Compiled removed must release owned accessories before body cleanup can publish");
    }

    private static String internal(Class<?> type) { return type.getName().replace('.', '/'); }
}

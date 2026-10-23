package com.naocraftlab.skins.buildlogic.contract;

import com.naocraftlab.skins.buildlogic.terminal.ImmediatePreviewBindings;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class ImmediatePreviewContract {
    private static ClassNode read(String owner, List<Path> inputs) throws Exception {
        for (Path input : inputs) {
            byte[] bytes = null;
            if (Files.isDirectory(input) && Files.isRegularFile(input.resolve(owner + ".class"))) bytes = Files.readAllBytes(input.resolve(owner + ".class"));
            else if (Files.isRegularFile(input) && input.toString().endsWith(".jar")) try (ZipFile zip = new ZipFile(input.toFile())) {
                var entry = zip.getEntry(owner + ".class"); if (entry != null) bytes = zip.getInputStream(entry).readAllBytes();
            }
            if (bytes != null) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
        }
        throw new AssertionError("IMMEDIATE_PREVIEW_PINNED_MISSING " + owner);
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static byte[] body(ClassNode owner, MethodNode method) {
        ClassNode single = new ClassNode(); single.version = owner.version; single.access = owner.access; single.name = owner.name; single.superName = owner.superName;
        single.methods.add(method); ClassWriter writer = new ClassWriter(0); single.accept(writer); return writer.toByteArray();
    }
    private static boolean selected(String owner, MethodNode method) {
        return owner.endsWith("Lighting") ? method.desc.equals("()V") && Set.of("setupForEntityInInventory", "setupFor3DItems").contains(method.name)
                : owner.endsWith("GuiGraphics") ? method.name.equals("flush") && method.desc.equals("()V")
                : owner.equals(ImmediatePreviewBindings.INVENTORY) ? method.name.equals("renderEntityInInventory")
                        && (method.desc.equals(ImmediatePreviewBindings.inventoryDescriptor("mesh")) || method.desc.equals(ImmediatePreviewBindings.inventoryDescriptor("rendered-buffer")))
                : method.name.equals("configureEntity");
    }
    private static void rejected(ClassNode node, String mode) {
        try { ImmediatePreviewBindings.apply(node, mode); throw new IllegalStateException("IMMEDIATE_PREVIEW_BAD_BINDING_ACCEPTED"); }
        catch (AssertionError expected) { check(expected.getMessage().startsWith("IMMEDIATE_PREVIEW_"), "UNEXPECTED_REJECTION"); }
    }
    private static void fullVertex(ClassNode builder, String name, String descriptor) {
        check(builder.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).count() == 1,
                "IMMEDIATE_VERTEX_SINK_EXACT_OVERRIDE");
    }
    private static void rejectedFullVertex(ClassNode builder, String name, String descriptor) {
        try { fullVertex(builder, name, descriptor); throw new IllegalStateException("IMMEDIATE_VERTEX_SINK_BAD_OVERRIDE_ACCEPTED"); }
        catch (AssertionError expected) { check("IMMEDIATE_VERTEX_SINK_EXACT_OVERRIDE".equals(expected.getMessage()), "UNEXPECTED_REJECTION"); }
    }
    public static void verifyVertexSink(String mode, List<Path> inputs) throws Exception {
        String name = mode.equals("mesh") ? "addVertex" : "vertex";
        String descriptor = mode.equals("mesh") ? "(FFFIFFIIFFF)V" : "(FFFFFFFFFIIFFF)V";
        String owner = "com/naocraftlab/skins/buildlogic/probe/wavey/ImmediateBuffers$Builder";
        ClassNode builder = read(owner, inputs);
        fullVertex(builder, name, descriptor);
        ClassNode wrong = read(owner, inputs), duplicate = read(owner, inputs);
        wrong.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst().orElseThrow().desc = "()V";
        duplicate.methods.add(duplicate.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst().orElseThrow());
        rejectedFullVertex(wrong, name, descriptor); rejectedFullVertex(duplicate, name, descriptor);
        ClassNode nativeBuilder = read("com/mojang/blaze3d/vertex/BufferBuilder", inputs);
        fullVertex(nativeBuilder, name, descriptor);
        System.out.println("IMMEDIATE_VERTEX_SINK_CONTRACT_PASS " + mode + " " + name + descriptor + "; own/native exact1 for baked fallback; wrong/duplicate rejected; ZERO native execution");
    }
    public static void verify(String mode, List<Path> inputs) throws Exception {
        check(ImmediatePreviewBindings.owners(mode).contains("net/minecraft/client/gui/screens/inventory/InventoryScreen"), "LEGACY_FINAL_GUI_TERMINAL_MISSING");
        verifyVertexSink(mode, inputs);
        int total = 0;
        for (String owner : ImmediatePreviewBindings.owners(mode)) {
            ClassNode raw = read(owner, inputs), node = read(owner, inputs);
            Map<String, byte[]> kept = new HashMap<>();
            for (MethodNode method : raw.methods) if (!selected(owner, method)) kept.put(method.name + method.desc, body(raw, method));
            total += ImmediatePreviewBindings.apply(node, mode);
            for (MethodNode method : node.methods) if (!selected(owner, method))
                check(Arrays.equals(kept.get(method.name + method.desc), body(node, method)), "IMMEDIATE_PREVIEW_UNRELATED_BODY_CHANGED");
            for (MethodNode method : raw.methods) if (selected(owner, method)) {
                ClassNode wrong = read(owner, inputs), duplicate = read(owner, inputs);
                wrong.methods.stream().filter(m -> m.name.equals(method.name) && m.desc.equals(method.desc)).findFirst().orElseThrow().desc = "()Ljava/lang/Void;";
                duplicate.methods.add(duplicate.methods.stream().filter(m -> m.name.equals(method.name) && m.desc.equals(method.desc)).findFirst().orElseThrow());
                rejected(wrong, mode); rejected(duplicate, mode);
            }
        }
        check(total == 6, "IMMEDIATE_PREVIEW_EXACT_TOTAL");
        ClassNode unknown = new ClassNode(); unknown.name = "net/minecraft/client/renderer/entity/LivingEntityRenderer";
        check(ImmediatePreviewBindings.apply(unknown, mode) == 0, "IMMEDIATE_PREVIEW_CONSUMER_LOOP_REPLACED");
        String inventory = "net/minecraft/client/gui/screens/inventory/InventoryScreen";
        String desc = "(Lnet/minecraft/client/gui/GuiGraphics;" + (mode.equals("mesh") ? "FFFLorg/joml/Vector3f;" : "III")
                + "Lorg/joml/Quaternionf;Lorg/joml/Quaternionf;Lnet/minecraft/world/entity/LivingEntity;)V";
        ClassNode renderer = read(ImmediatePreviewBindings.preview(mode), inputs), nativeInventory = read(inventory, inputs);
        check(nativeInventory.methods.stream().filter(m -> m.name.equals("renderEntityInInventory") && m.desc.equals(desc)).count() == 1, "LEGACY_INVENTORY_OVERLOAD");
        check(renderer.methods.stream().filter(m -> m.name.equals("render")).flatMap(m -> Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof MethodInsnNode call && call.owner.equals(inventory) && call.name.equals("renderEntityInInventory") && call.desc.equals(desc)).count() == 1, "LEGACY_PUBLIC_INVENTORY_BINDING");
        var fields = Map.of("net/minecraft/world/entity/Entity", Map.of("firstTick", "Z"),
                "net/minecraft/client/gui/GuiGraphics", Map.of("pose", "Lcom/mojang/blaze3d/vertex/PoseStack;", "bufferSource", "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"));
        for (var entry : fields.entrySet()) for (var field : entry.getValue().entrySet())
            check(read(entry.getKey(), inputs).fields.stream().filter(f -> f.name.equals(field.getKey()) && f.desc.equals(field.getValue())).count() == 1, "LEGACY_RAW_FIELD " + entry.getKey() + "." + field.getKey());
        ClassNode batch = read("com/naocraftlab/skins/buildlogic/probe/wavey/LegacyCapeBatch", inputs);
        check(batch.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray())).filter(i -> i instanceof MethodInsnNode call
                && call.owner.equals("com/naocraftlab/skins/buildlogic/probe/wavey/ImmediatePublicPreviewProbe") && call.name.equals("runCase")).count() == 1,
                "LEGACY_PUBLIC_NOT_CANONICALLY_REGISTERED");
        MethodNode terminal = read(inventory, inputs).methods.stream().filter(m -> m.name.equals("renderEntityInInventory") && m.desc.equals(desc)).findFirst().orElseThrow();
        ClassNode bound = read(inventory, inputs); ImmediatePreviewBindings.apply(bound, mode);
        MethodNode replacement = bound.methods.stream().filter(m -> m.name.equals(terminal.name) && m.desc.equals(terminal.desc)).findFirst().orElseThrow();
        check(replacement.instructions.size() == 4 && replacement.instructions.getFirst() instanceof VarInsnNode graphics && graphics.var == 0
                && replacement.instructions.get(1) instanceof VarInsnNode actor && actor.var == (mode.equals("mesh") ? 7 : 6)
                && replacement.instructions.get(2) instanceof MethodInsnNode call && call.name.equals("guiTerminal")
                && call.owner.equals("com/naocraftlab/skins/buildlogic/probe/wavey/ImmediatePublicPreviewProbe")
                && call.desc.equals("(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/entity/LivingEntity;)V"), "LEGACY_FINAL_GUI_ACTUAL_ARGUMENTS");
        System.out.println("IMMEDIATE_PUBLIC_CONTRACT_PASS exact6 raw-tick/lighting/depth/final-GUI; wrong/duplicate/unknown/unrelated bodies; actual graphics+actor slots; final InventoryScreen GUI SUBSTITUTED; full native body/nameplate/layer UI NOT_EXECUTED; ZERO native execution");
    }
}

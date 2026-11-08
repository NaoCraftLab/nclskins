package com.naocraftlab.skins.buildlogic.contract;

import com.naocraftlab.skins.buildlogic.terminal.SubmissionPreviewBindings;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class SubmissionPreviewContract {
    private static final String PROBE = "com/naocraftlab/skins/buildlogic/probe/wavey/";
    private static ClassNode read(String owner, List<Path> inputs) throws Exception {
        for (Path path : inputs) {
            byte[] value = null;
            if (Files.isDirectory(path) && Files.isRegularFile(path.resolve(owner + ".class"))) value = Files.readAllBytes(path.resolve(owner + ".class"));
            else if (Files.isRegularFile(path)) try (var zip = new java.util.zip.ZipFile(path.toFile())) {
                var entry = zip.getEntry(owner + ".class");
                if (entry != null) value = zip.getInputStream(entry).readAllBytes();
            }
            if (value != null) { var node = new ClassNode(); new ClassReader(value).accept(node, 0); return node; }
        }
        throw new AssertionError("SUBMISSION_PINNED_INPUT_MISSING " + owner);
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static byte[] bytes(ClassNode owner, MethodNode method) {
        ClassNode single = new ClassNode(); single.version = owner.version; single.access = owner.access;
        single.name = owner.name; single.superName = "java/lang/Object"; single.methods.add(method);
        ClassWriter writer = new ClassWriter(0); single.accept(writer); return writer.toByteArray();
    }
    private static boolean selected(String owner, MethodNode method) {
        if (owner.equals(SubmissionPreviewBindings.P)) return Set.of("previewPlayer", "applySettling").contains(method.name);
        if (owner.equals(SubmissionPreviewBindings.C)) return method.name.equals("setModelParts");
        if (owner.equals(SubmissionPreviewBindings.TARGET)) return method.name.equals("renderToTexture") && method.desc.contains("LivePreviewRenderState;");
        return method.name.equals("finalizeRenderState");
    }
    private static void rejected(ClassNode node) {
        try { SubmissionPreviewBindings.apply(node); throw new IllegalStateException("SUBMISSION_MUTANT_ACCEPTED"); }
        catch (AssertionError expected) { check(expected.getMessage().startsWith("SUBMISSION_DESCRIPTOR_COUNT") || expected.getMessage().startsWith("EXTRACTION_DESCRIPTOR_COUNT"), "SUBMISSION_WRONG_REJECTION"); }
    }
    public static void verify(List<Path> inputs) throws Exception {
        ClassNode entry = read(PROBE + "BufferedPreviewCapeProbe", inputs);
        check(entry.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray())).anyMatch(i -> i instanceof LdcInsnNode literal
                && (PROBE + "LivePublicPreviewProbe").replace('/', '.').equals(literal.cst)), "PUBLIC_LIVE_NOT_CANONICALLY_REGISTERED");
        boolean loader = ClassLoader.getSystemResource(PROBE + "ClientCapeBatch.class") != null;
        ClassNode batch = read(PROBE + (loader ? "ClientCapeBatch" : "NativeEnchantedElytra"), inputs);
        check(batch.methods.stream().filter(m -> !loader || m.name.equals("nativeEnchantment")).flatMap(m -> Arrays.stream(m.instructions.toArray()))
                .anyMatch(i -> loader ? i instanceof LdcInsnNode literal && (PROBE + "NativeElytraConsumptionProbe").replace('/', '.').equals(literal.cst)
                        : i instanceof MethodInsnNode call && call.owner.equals(PROBE + "NativeElytraConsumptionProbe") && call.name.equals("consume")), "NATIVE_ENCHANTMENT_NOT_CONSUMED");
        int total = 0;
        for (String name : SubmissionPreviewBindings.OWNERS) {
            ClassNode node = read(name, inputs);
            Map<String, byte[]> preserved = new HashMap<>();
            for (MethodNode method : node.methods) if (!selected(name, method)) preserved.put(method.name + method.desc, bytes(node, method));
            int count = SubmissionPreviewBindings.apply(node); check(count > 0, "SUBMISSION_TERMINAL_MISSING"); total += count;
            for (MethodNode method : node.methods) {
                if (!selected(name, method)) check(Arrays.equals(preserved.get(method.name + method.desc), bytes(node, method)), "SUBMISSION_UNRELATED_BODY_CHANGED");
                for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(PROBE + "SubmissionRawInputs")) {
                    ClassNode helper = read(call.owner, inputs);
                    check(helper.methods.stream().filter(m -> m.name.equals(call.name) && m.desc.equals(call.desc)).count() == 1, "SUBMISSION_RAW_HELPER_ABI " + call.name + call.desc);
                }
            }
            ClassNode wrong = read(name, inputs);
            wrong.methods.stream().filter(m -> selected(name, m)).findFirst().orElseThrow().desc = "()Ljava/lang/Object;"; rejected(wrong);
            ClassNode duplicate = read(name, inputs);
            duplicate.methods.add(duplicate.methods.stream().filter(m -> selected(name, m)).findFirst().orElseThrow()); rejected(duplicate);
        }
        ClassNode unknown = new ClassNode(); unknown.name = "net/minecraft/client/Minecraft";
        check(SubmissionPreviewBindings.apply(unknown) == 0, "SUBMISSION_UNKNOWN_OWNER_CHANGED");
        ClassNode dispatcher = read(SubmissionPreviewBindings.LIVE, inputs);
        var originalDispatcher = new ClassWriter(0); dispatcher.accept(originalDispatcher);
        check(SubmissionPreviewBindings.apply(dispatcher) == 0, "SUBMISSION_DISPATCHER_WAS_RAW_BOUND");
        var unchangedDispatcher = new ClassWriter(0); dispatcher.accept(unchangedDispatcher);
        check(Arrays.equals(originalDispatcher.toByteArray(), unchangedDispatcher.toByteArray()), "SUBMISSION_DISPATCHER_BYTES_CHANGED");
        var prepare = dispatcher.methods.stream().filter(m -> m.name.equals("prepare") && m.desc.startsWith("(Lcom/naocraftlab/skins/compat/client/identifier/submission/LivePreviewRenderState;")).findFirst().orElseThrow();
        for (String[] expected : List.of(new String[]{SubmissionPreviewBindings.TARGET, "prepare", "(Lnet/minecraft/client/gui/render/state/pip/PictureInPictureRenderState;Lnet/minecraft/client/gui/render/state/GuiRenderState;I)V"},
                new String[]{SubmissionPreviewBindings.FRAME, "retain", "(Lcom/naocraftlab/skins/client/ScreenOwnedRenderTarget;)V"}))
            check(Arrays.stream(prepare.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call && call.owner.equals(expected[0]) && call.name.equals(expected[1]) && call.desc.equals(expected[2])).count() == 1, "LIVE_FULL_PREPARE_DELEGATION " + Arrays.toString(expected));
        check(dispatcher.nestMembers.contains(SubmissionPreviewBindings.TARGET), "LIVE_TARGET_NEST_MISSING");
        ClassNode consumer = read(SubmissionPreviewBindings.TARGET, inputs);
        check(consumer.nestHostClass.equals(SubmissionPreviewBindings.LIVE), "LIVE_TARGET_NEST_HOST_MISSING");
        var render = consumer.methods.stream().filter(m -> selected(consumer.name, m)).findFirst().orElseThrow();
        for (String callName : List.of("extractEntity", "configure", "submit", "renderAllFeatures"))
            check(Arrays.stream(render.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call && call.name.equals(callName)).count() == 1, "LIVE_NATIVE_BOUNDARY_MISSING " + callName);
        for (String owner : SubmissionPreviewBindings.NATIVE_OWNERS) {
            ClassNode nativeOwner = read(owner, inputs);
            var beforeRaw = new ClassWriter(0); nativeOwner.accept(beforeRaw);
            check(SubmissionPreviewBindings.applyRaw(nativeOwner) == 0, "NATIVE_OWNER_RAW_PHASE_SELECTED");
            var afterRaw = new ClassWriter(0); nativeOwner.accept(afterRaw);
            check(Arrays.equals(beforeRaw.toByteArray(), afterRaw.toByteArray()), "NATIVE_OWNER_CHANGED_BEFORE_MIXIN");
            if (owner.equals(SubmissionPreviewBindings.PIP)) {
                SubmissionPreviewBindings.verifyProjectionAnchor(nativeOwner, false);
                try { SubmissionPreviewBindings.verifyInjectedDepth(nativeOwner); throw new IllegalStateException("UNWOVEN_DEPTH_ACCEPTED"); }
                catch (AssertionError expected) { check(expected.getMessage().startsWith("SUBMISSION_INJECTED_DEPTH_HANDLER_COUNT"), "UNWOVEN_DEPTH_WRONG_REJECTION"); }
                ClassNode premature = read(owner, inputs);
                SubmissionPreviewBindings.applyNative(premature);
                try { SubmissionPreviewBindings.verifyProjectionAnchor(premature, false); throw new IllegalStateException("PRE_MIXIN_PROJECTION_ACCEPTED"); }
                catch (AssertionError expected) { check(expected.getMessage().startsWith("SUBMISSION_PROJECTION_ANCHOR_COUNT"), "PRE_MIXIN_PROJECTION_WRONG_REJECTION"); }
            }
            Map<String, byte[]> retained = new HashMap<>();
            for (MethodNode method : nativeOwner.methods) if (owner.equals(SubmissionPreviewBindings.FRAME) ? !method.name.equals("fence") : Set.of("prepare", "renderToTexture", "textureIsReadyToBlit", "getTranslateY", "getRenderStateClass", "getTextureLabel").contains(method.name))
                retained.put(method.name + method.desc, bytes(nativeOwner, method));
            check(SubmissionPreviewBindings.apply(nativeOwner) == (owner.equals(SubmissionPreviewBindings.FRAME) ? 1 : 3), "LIVE_NATIVE_TERMINAL_COUNT");
            if (owner.equals(SubmissionPreviewBindings.PIP)) SubmissionPreviewBindings.verifyProjectionAnchor(nativeOwner, true);
            for (MethodNode method : nativeOwner.methods) {
                if (retained.containsKey(method.name + method.desc)) check(Arrays.equals(retained.get(method.name + method.desc), bytes(nativeOwner, method)), "LIVE_NATIVE_BODY_CHANGED " + method.name);
                for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                        && (call.owner.equals(PROBE + "SubmissionRawInputs") || call.owner.equals(PROBE + "SubmissionRawInputs$Projection")))
                    check(read(call.owner, inputs).methods.stream().filter(m -> m.name.equals(call.name) && m.desc.equals(call.desc)).count() == 1, "SUBMISSION_RAW_HELPER_ABI " + call.name + call.desc);
            }
            ClassNode normalized = new ClassNode();
            nativeOwner.accept(new org.objectweb.asm.commons.ClassRemapper(normalized, new org.objectweb.asm.commons.Remapper() {
                @Override public String map(String name) { return name.equals(PROBE + "SubmissionRawInputs$Projection") ? "net/minecraft/client/renderer/CachedOrthoProjectionMatrixBuffer" : name; }
            }));
            ClassNode original = read(owner, inputs);
            for (MethodNode method : normalized.methods) {
                for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(PROBE + "SubmissionRawInputs")) call.owner = "com/mojang/blaze3d/systems/RenderSystem";
                MethodNode before = original.methods.stream().filter(m -> m.name.equals(method.name) && m.desc.equals(method.desc)).findFirst().orElseThrow();
                check(Arrays.equals(bytes(original, before), bytes(normalized, method)), "LIVE_NATIVE_NON_TERMINAL_BYTES_CHANGED " + method.name);
            }
            String name = owner.equals(SubmissionPreviewBindings.FRAME) ? "fence" : "prepare";
            ClassNode wrong = read(owner, inputs); wrong.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow().desc = "()Ljava/lang/Object;"; rejected(wrong);
            ClassNode duplicate = read(owner, inputs); duplicate.methods.add(duplicate.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow()); rejected(duplicate);
        }
        check(SubmissionPreviewBindings.applyNative(unknown) == 0, "SUBMISSION_UNKNOWN_DEVICE_OWNER_CHANGED");
        ClassNode depthInput = read(PROBE + "SubmissionRawInputs$DepthTarget", inputs);
        check(depthInput.superName.equals("com/mojang/blaze3d/pipeline/RenderTarget"), "MAIN_DEPTH_NATIVE_TARGET_SUPER");
        var depthCalls = depthInput.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i).toList();
        check(depthCalls.size() == 1 && depthCalls.getFirst().owner.equals(depthInput.superName)
                && depthCalls.getFirst().name.equals("<init>") && depthCalls.getFirst().desc.equals("(Ljava/lang/String;Z)V"), "MAIN_DEPTH_CPU_CONSTRUCTOR_ONLY");
        ClassNode scope = read(PROBE + "SubmissionRawInputs$MainTargetInput", inputs);
        check(scope.interfaces.contains("java/lang/AutoCloseable"), "MAIN_DEPTH_SCOPE_NOT_CLOSEABLE");
        check(scope.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray())).noneMatch(i -> i instanceof MethodInsnNode call
                && (call.owner.equals("com/mojang/blaze3d/systems/RenderSystem") || call.name.equals("createBuffers") || call.name.equals("getWindow"))), "MAIN_DEPTH_EAGER_DEVICE_OR_WINDOW");
        ClassNode driver = read(PROBE + "LivePublicPreviewProbe", inputs);
        var instructions = driver.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray())).toList();
        for (String[] expected : List.of(new String[]{SubmissionPreviewBindings.LIVE, "<init>", "(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)V"},
                new String[]{SubmissionPreviewBindings.LIVE, "prepare", "(Lcom/naocraftlab/skins/compat/client/identifier/submission/LivePreviewRenderState;Lnet/minecraft/client/gui/render/state/GuiRenderState;I)V"},
                new String[]{SubmissionPreviewBindings.FRAME, "open", "()L" + SubmissionPreviewBindings.FRAME + ";"},
                new String[]{SubmissionPreviewBindings.FRAME, "submitted", "()V"}))
            check(instructions.stream().filter(i -> i instanceof MethodInsnNode call && call.owner.equals(expected[0]) && call.name.equals(expected[1]) && call.desc.equals(expected[2])).count() == 1, "LIVE_DRIVER_FULL_PREPARE_MISSING " + Arrays.toString(expected));
        check(instructions.stream().noneMatch(i -> i instanceof LdcInsnNode literal && "renderToTexture".equals(literal.cst)), "LIVE_DRIVER_DIRECT_TEXTURE_BYPASS");
        System.out.println("PUBLIC_LIVE_EXACT_CONTRACT_PASS raw-operations=" + total + "; wrong/duplicate/unknown/helper ABI/full unrelated method preservation; full native prepare/blit/shared-frame delegation; public live and native enchanted consumer registered; ZERO native body/FML execution");
    }
    public static void main(String[] args) throws Exception { verify(Arrays.stream(args).map(Path::of).toList()); }
}

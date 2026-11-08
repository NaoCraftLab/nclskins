package com.naocraftlab.skins.buildlogic.terminal;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class SubmissionPreviewBindings {
    public static final String P = "com/naocraftlab/skins/compat/client/identifier/submission/SubmissionPreviewRenderer";
    public static final String C = P + "$PreviewPlayer";
    public static final String LIVE = P.replace("SubmissionPreviewRenderer", "LivePreviewRenderer");
    public static final String TARGET = LIVE + "$TargetRenderer";
    public static final String PIP = "net/minecraft/client/gui/render/pip/PictureInPictureRenderer";
    public static final String FRAME = "com/naocraftlab/skins/compat/client/identifier/preview/PreviewConsumerFrame";
    public static final Set<String> NATIVE_OWNERS = Set.of(PIP, FRAME);
    private static final String N = "net/minecraft/";
    private static final String RAW = "com/naocraftlab/skins/buildlogic/probe/wavey/SubmissionRawInputs";
    public static final Set<String> OWNERS = Set.of(P, C, TARGET, N + "client/renderer/entity/EntityRenderer");
    private static MethodNode exact(ClassNode node, String name, String desc) {
        var found = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).toList();
        if (found.size() != 1) throw new AssertionError("SUBMISSION_DESCRIPTOR_COUNT " + node.name + "." + name + desc + "=" + found.size());
        return found.getFirst();
    }
    private static String key(MethodInsnNode call) { return call.owner + "." + call.name + call.desc; }
    private static int raw(MethodNode method) {
        Map<String, Integer> expected;
        if (method.name.equals("renderToTexture")) {
            expected = Map.of(C + ".avatarState()L" + N + "client/entity/ClientAvatarState;", 1,
                    C + ".position()L" + N + "world/phys/Vec3;", 1,
                    N + "client/entity/ClientAvatarState.tick(L" + N + "world/phys/Vec3;L" + N + "world/phys/Vec3;)V", 1);
        } else {
            expected = ExtractionTerminalBindings.rawInventory(method.name, C);
            if (method.name.equals("previewPlayer")) {
                expected.remove(C + ".avatarState()L" + N + "client/entity/ClientAvatarState;");
                expected.remove(C + ".position()L" + N + "world/phys/Vec3;");
                expected.remove(N + "client/entity/ClientAvatarState.tick(L" + N + "world/phys/Vec3;L" + N + "world/phys/Vec3;)V");
            }
        }
        Map<String, Integer> found = new HashMap<>();
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && expected.containsKey(key(call))) found.merge(key(call), 1, Integer::sum);
        if (!expected.equals(found)) throw new AssertionError("SUBMISSION_OPERATION_COUNT " + method.name + " " + found);
        int constructors = 0;
        for (var instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.equals(C)) {
                if (type.getNext().getOpcode() != Opcodes.DUP) throw new AssertionError("SUBMISSION_CONSTRUCTOR_PATTERN");
                method.instructions.remove(type.getNext()); method.instructions.remove(type);
            }
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (call.owner.equals(C) && call.name.equals("<init>") && call.desc.equals("(L" + N + "client/multiplayer/ClientLevel;Lcom/mojang/authlib/GameProfile;)V")) {
                var next = call.getNext();
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, RAW, "rawPreview", call.desc.replace(")V", ")Ljava/lang/Object;"), false));
                method.instructions.insertBefore(next, new TypeInsnNode(Opcodes.CHECKCAST, C)); constructors++; continue;
            }
            if (!expected.containsKey(key(call))) continue;
            method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, RAW, call.name, "(Ljava/lang/Object;" + call.desc.substring(1), false));
        }
        if (constructors != (method.name.equals("previewPlayer") ? 1 : 0)) throw new AssertionError("SUBMISSION_CONSTRUCTOR_COUNT");
        return expected.values().stream().mapToInt(Integer::intValue).sum() + constructors;
    }
    public static int apply(ClassNode node) {
        return NATIVE_OWNERS.contains(node.name) ? applyNative(node) : applyRaw(node);
    }
    public static int applyRaw(ClassNode node) {
        if (!OWNERS.contains(node.name)) return 0;
        if (node.name.equals(P)) return raw(exact(node, "previewPlayer", "(L" + N + "client/Minecraft;Lcom/naocraftlab/skins/client/PreviewRenderer$PreviewAppearance;L" + N + "world/entity/player/PlayerSkin;L" + N + "world/item/ItemStack;)L" + C + ";"))
                + raw(exact(node, "applySettling", "(L" + C + ";FLcom/naocraftlab/skins/client/EditorPreviewSession$SettlingMotion;)V"));
        if (node.name.equals(C)) return raw(exact(node, "setModelParts", "(B)V"));
        if (node.name.equals(TARGET)) return raw(exact(node, "renderToTexture", "(Lcom/naocraftlab/skins/compat/client/identifier/submission/LivePreviewRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"));
        return ExtractionTerminalBindings.apply(node, null);
    }

    public static void verifyProjectionAnchor(ClassNode node, boolean substituted) {
        String owner = substituted ? RAW + "$Projection" : "net/minecraft/client/renderer/CachedOrthoProjectionMatrixBuffer";
        var constructor = exact(node, "<init>", "(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)V");
        long count = Arrays.stream(constructor.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
                && call.owner.equals(owner) && call.name.equals("<init>") && call.desc.equals("(Ljava/lang/String;FFZ)V")).count();
        if (count != 1) throw new AssertionError("SUBMISSION_PROJECTION_ANCHOR_COUNT " + substituted + "=" + count);
    }
    public static void verifyInjectedDepth(ClassNode node) {
        var handlers = node.methods.stream().filter(m -> m.name.contains("nclskins$expandPipDepth")
                && m.desc.equals("(Lorg/spongepowered/asm/mixin/injection/invoke/arg/Args;)V")).toList();
        if (handlers.size() != 1) throw new AssertionError("SUBMISSION_INJECTED_DEPTH_HANDLER_COUNT " + handlers.size());
        var handler = handlers.getFirst();
        long writes = Arrays.stream(handler.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
                && call.owner.equals("org/spongepowered/asm/mixin/injection/invoke/arg/Args")
                && call.name.equals("set") && call.desc.equals("(ILjava/lang/Object;)V")).count();
        var constructor = exact(node, "<init>", "(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)V");
        long calls = Arrays.stream(constructor.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
                && call.owner.equals(node.name) && call.name.equals(handler.name) && call.desc.equals(handler.desc)).count();
        if (writes != 2 || calls != 1) throw new AssertionError("SUBMISSION_INJECTED_DEPTH_CALL_ARGS " + calls + "/" + writes);
    }
    public static int applyNative(ClassNode node) {
        if (!NATIVE_OWNERS.contains(node.name)) return 0;
        if (node.name.equals(FRAME)) {
            var method = exact(node, "fence", "()V");
            int count = 0;
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("com/mojang/blaze3d/systems/RenderSystem") && call.name.equals("queueFencedTask") && call.desc.equals("(Ljava/lang/Runnable;)V")) {
                call.owner = RAW; count++;
            }
            if (count != 1) throw new AssertionError("SUBMISSION_FENCE_COUNT " + count);
            return count;
        }
        exact(node, "prepare", "(Lnet/minecraft/client/gui/render/state/pip/PictureInPictureRenderState;Lnet/minecraft/client/gui/render/state/GuiRenderState;I)V");
        Map<String, Integer> expected = Map.of("getDevice()Lcom/mojang/blaze3d/systems/GpuDevice;", 1,
                "getSamplerCache()Lcom/mojang/blaze3d/systems/SamplerCache;", 1,
                "setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V", 1);
        Map<String, Integer> found = new HashMap<>();
        for (var method : node.methods) for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                && call.owner.equals("com/mojang/blaze3d/systems/RenderSystem") && expected.containsKey(call.name + call.desc)) {
            found.merge(call.name + call.desc, 1, Integer::sum); call.owner = RAW;
        }
        if (!expected.equals(found)) throw new AssertionError("SUBMISSION_NATIVE_TERMINAL_COUNT " + found);
        var remapped = new ClassNode();
        node.accept(new org.objectweb.asm.commons.ClassRemapper(remapped, new org.objectweb.asm.commons.Remapper() {
            @Override public String map(String name) {
                return name.equals("net/minecraft/client/renderer/CachedOrthoProjectionMatrixBuffer") ? RAW + "$Projection" : name;
            }
        }));
        node.fields = remapped.fields; node.methods = remapped.methods;
        return 3;
    }
}

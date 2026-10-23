package com.naocraftlab.skins.buildlogic.terminal;

import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class ImmediateTerminalBindings {
    private static final String V = "com/mojang/blaze3d/vertex/";
    private static final String R = "net/minecraft/client/renderer/RenderType";
    private static final String S = "net/minecraft/client/renderer/MultiBufferSource$BufferSource";
    private static final String W = "dev/tr7zw/waveycapes/render/CapeNodeCollector";
    private static final String H = "com/naocraftlab/skins/buildlogic/probe/wavey/ImmediateCapeHost";
    private static final String B = "com/naocraftlab/skins/buildlogic/probe/wavey/ImmediateBuffers";
    public static Set<String> owners(String mode) {
        if (!Set.of("mesh", "rendered-buffer").contains(mode)) throw new IllegalArgumentException("IMMEDIATE_MODE");
        return mode.equals("mesh") ? Set.of(R, S, W) : Set.of(R, S, W, V + "BufferBuilder$RenderedBuffer");
    }
    private static MethodNode exact(ClassNode node, String name, String descriptor) {
        var methods = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).toList();
        if (methods.size() != 1) throw new AssertionError("IMMEDIATE_DESCRIPTOR_COUNT " + node.name + "." + name + descriptor);
        return methods.get(0);
    }
    private static void count(int actual, int expected) {
        if (actual != expected) throw new AssertionError("IMMEDIATE_TERMINAL_COUNT " + actual + " != " + expected);
    }
    public static int apply(ClassNode node, String mode) {
        if (!owners(mode).contains(node.name)) return 0;
        boolean mesh = mode.equals("mesh");
        if (node.name.equals(W)) {
            var method = exact(node, "submitCape", "(Lnet/minecraft/client/renderer/MultiBufferSource;Ldev/tr7zw/transition/mc/entitywrapper/PlayerWrapper;L" + V + "PoseStack;IF)V");
            int fields = 0, calls = 0;
            for (var instruction : method.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(W)
                        && field.name.equals("customCapeRenderer") && field.desc.equals("Ldev/tr7zw/waveycapes/render/CustomCapeRenderer;")) {
                    method.instructions.set(field, new InsnNode(Opcodes.POP)); fields++;
                } else if (instruction instanceof MethodInsnNode call && call.owner.equals("dev/tr7zw/waveycapes/render/CustomCapeRenderer")
                        && call.name.equals("render") && call.desc.equals("(Ldev/tr7zw/transition/mc/entitywrapper/PlayerWrapper;Ldev/tr7zw/waveycapes/render/CapeRenderer;L" + V + "VertexConsumer;L" + V + "PoseStack;IF)V")) {
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, H, "geometry", call.desc, false)); calls++;
                }
            }
            count(fields, 2); count(calls, 2); return 2;
        }
        if (node.name.equals(R)) {
            var method = exact(node, mesh ? "draw" : "end", mesh ? "(L" + V + "MeshData;)V" : "(L" + V + "BufferBuilder;L" + V + "VertexSorting;)V");
            int setup = 0, clear = 0, draw = 0;
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (call.owner.equals(R) && call.desc.equals("()V") && (call.name.equals("setupRenderState") || call.name.equals("clearRenderState"))) {
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, H, call.name.equals("setupRenderState") ? "setup" : "clear", "(L" + R + ";)V", false));
                    if (call.name.equals("setupRenderState")) setup++; else clear++;
                } else if (call.owner.equals(V + "BufferUploader") && call.name.equals("drawWithShader")
                        && call.desc.equals("(L" + V + (mesh ? "MeshData" : "BufferBuilder$RenderedBuffer") + ";)V")) {
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, B, "draw", call.desc, false)); draw++;
                }
            }
            count(setup, 1); count(clear, 1); count(draw, 1); return 3;
        }
        if (node.name.equals(S)) {
            var end = exact(node, "endBatch", mesh ? "(L" + R + ";L" + V + "BufferBuilder;)V" : "(L" + R + ";)V");
            int sorting = 0;
            for (var instruction : end.instructions.toArray()) if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("com/mojang/blaze3d/systems/RenderSystem") && call.name.equals("getVertexSorting") && call.desc.equals("()L" + V + "VertexSorting;")) {
                call.owner = H; call.name = "vertexSorting"; sorting++;
            }
            count(sorting, 1);
            if (!mesh) return 1;
            var get = exact(node, "getBuffer", "(L" + R + ";)L" + V + "VertexConsumer;");
            int allocations = 0, constructors = 0;
            for (var instruction : get.instructions.toArray()) {
                if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.equals(V + "BufferBuilder")) {
                    type.desc = B + "$Builder"; allocations++;
                } else if (instruction instanceof MethodInsnNode call && call.owner.equals(V + "BufferBuilder") && call.name.equals("<init>")
                        && call.desc.equals("(L" + V + "ByteBufferBuilder;L" + V + "VertexFormat$Mode;L" + V + "VertexFormat;)V")) {
                    call.owner = B + "$Builder"; constructors++;
                }
            }
            count(allocations, 2); count(constructors, 2); return 5;
        }
        var release = exact(node, "release", "()V");
        release.instructions.clear(); release.tryCatchBlocks.clear(); release.localVariables = null;
        release.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        release.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, B, "release", "(L" + node.name + ";)V", false));
        release.instructions.add(new InsnNode(Opcodes.RETURN)); release.maxStack = 1; release.maxLocals = 1;
        return 1;
    }
}

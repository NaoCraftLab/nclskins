package com.naocraftlab.skins.buildlogic.terminal;

import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class CapeTerminalBindings {
    private static final String V = "com/mojang/blaze3d/vertex/";
    private static final String R = "net/minecraft/client/renderer/rendertype/RenderType";
    private static final String PR = "net/minecraft/client/renderer/rendertype/PreparedRenderType";
    private static final String W = "dev/tr7zw/waveycapes/render/CapeNodeCollector";
    private static final String A = "net/minecraft/client/renderer/entity/player/AvatarRenderer";
    private static final String H = "com/naocraftlab/skins/buildlogic/probe/wavey/BufferedCapeHost";
    private static final String P = "com/naocraftlab/skins/buildlogic/probe/wavey/CapePublicationProbe";
    public static final Set<String> OWNERS = Set.of(V + "BufferBuilder", V + "MeshData", R, W, A);
    public static Set<String> owners(String mode) {
        if (!Set.of("buffered", "prepared", "combined").contains(mode)) throw new IllegalArgumentException("UNIMPLEMENTED_TERMINAL_MODE " + mode);
        return mode.equals("buffered") ? OWNERS : Set.of(R, PR, W, A);
    }
    private static MethodNode exact(ClassNode type, String name, String descriptor) {
        var matches = type.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).toList();
        if (matches.size() != 1) throw new AssertionError("TERMINAL_DESCRIPTOR_COUNT " + type.name + "." + name + descriptor + "=" + matches.size());
        return matches.getFirst();
    }
    private static void replace(MethodNode method, InsnList body) {
        method.instructions = body;
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.maxStack = 8;
    }
    private static InsnList call(String owner, String name, String desc, int result, int... loads) {
        InsnList body = new InsnList();
        for (int i = 0; i < loads.length; i += 2) body.add(new VarInsnNode(loads[i], loads[i + 1]));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, desc, false));
        body.add(new InsnNode(result));
        return body;
    }
    public static int apply(ClassNode type) {
        return apply(type, System.getProperty("nclskins.wavey.mode", "buffered"));
    }
    public static int apply(ClassNode type, String mode) {
        if (!owners(mode).contains(type.name)) return 0;
        String host = mode.equals("combined") ? H.replace("BufferedCapeHost", "CombinedCapeHost")
                : mode.equals("prepared") ? H.replace("BufferedCapeHost", "CapeHost") : H;
        if (mode.equals("combined") && type.name.equals(PR)) {
            String info = "Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;";
            String stage = "Lnet/minecraft/client/renderer/oit/OitStage;";
            String pass = "Lcom/mojang/renderpearl/api/commands/RenderPass;";
            MethodNode normal = exact(type, "drawFromBuffer", "(" + info + pass + ")V");
            MethodNode oit = exact(type, "drawFromBufferOit", "(" + info + stage + pass + ")V");
            InsnList body = new InsnList();
            body.add(new VarInsnNode(Opcodes.ALOAD, 0)); body.add(new VarInsnNode(Opcodes.ALOAD, 1));
            body.add(new InsnNode(Opcodes.ACONST_NULL)); body.add(new VarInsnNode(Opcodes.ALOAD, 2));
            body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, host, "draw", "(L" + PR + ";" + info + stage + pass + ")V", false));
            body.add(new InsnNode(Opcodes.RETURN)); replace(normal, body);
            replace(oit, call(host, "draw", "(L" + PR + ";" + info + stage + pass + ")V", Opcodes.RETURN,
                    Opcodes.ALOAD, 0, Opcodes.ALOAD, 1, Opcodes.ALOAD, 2, Opcodes.ALOAD, 3));
            return 2;
        }
        if (mode.equals("prepared") && type.name.equals(PR)) {
            String info = "Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;";
            replace(exact(type, "drawFromBuffer", "(" + info + ")V"), call(host, "draw", "(L" + PR + ";" + info + ")V",
                    Opcodes.RETURN, Opcodes.ALOAD, 0, Opcodes.ALOAD, 1));
            return 1;
        }
        if (type.name.equals(V + "BufferBuilder")) {
            MethodNode init = exact(type, "<init>", "(L" + V + "ByteBufferBuilder;L" + V + "VertexFormat$Mode;L" + V + "VertexFormat;)V");
            InsnList body = new InsnList(); body.add(new VarInsnNode(Opcodes.ALOAD, 0));
            body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
            body.add(new InsnNode(Opcodes.RETURN)); replace(init, body);
            replace(exact(type, "addVertex", "(FFF)L" + V + "VertexConsumer;"), call(H, "vertex", "(L" + V + "BufferBuilder;FFF)L" + V + "VertexConsumer;", Opcodes.ARETURN,
                    Opcodes.ALOAD, 0, Opcodes.FLOAD, 1, Opcodes.FLOAD, 2, Opcodes.FLOAD, 3));
            replace(exact(type, "build", "()L" + V + "MeshData;"), call(H, "build", "(L" + V + "BufferBuilder;)L" + V + "MeshData;", Opcodes.ARETURN, Opcodes.ALOAD, 0));
            return 3;
        }
        if (type.name.equals(V + "MeshData")) {
            InsnList empty = new InsnList(); empty.add(new InsnNode(Opcodes.ACONST_NULL)); empty.add(new InsnNode(Opcodes.ARETURN));
            replace(exact(type, "sortQuads", "(L" + V + "ByteBufferBuilder;L" + V + "VertexSorting;)L" + V + "MeshData$SortState;"), empty);
            replace(exact(type, "close", "()V"), call(H, "close", "(L" + V + "MeshData;)V", Opcodes.RETURN, Opcodes.ALOAD, 0));
            return 2;
        }
        if (type.name.equals(R)) {
            exact(type, "create", "(Ljava/lang/String;Lnet/minecraft/client/renderer/rendertype/RenderSetup;)L" + R + ";").access |= Opcodes.ACC_PUBLIC;
            if (mode.equals("prepared") || mode.equals("combined")) {
                replace(exact(type, "prepare", "()L" + PR + ";"), call(host, "prepare", "(L" + R + ";)L" + PR + ";",
                        Opcodes.ARETURN, Opcodes.ALOAD, 0));
                return 1;
            }
            replace(exact(type, "draw", "(L" + V + "MeshData;)V"), call(H, "draw", "(L" + R + ";L" + V + "MeshData;)V", Opcodes.RETURN, Opcodes.ALOAD, 0, Opcodes.ALOAD, 1));
            return 1;
        }
        if (type.name.equals(A)) {
            String desc = "(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V";
            replace(exact(type, "extractRenderState", desc), call(P, "captureRaw", desc, Opcodes.RETURN, Opcodes.ALOAD, 1, Opcodes.ALOAD, 2, Opcodes.FLOAD, 3));
            return 1;
        }
        String args = "Ldev/tr7zw/transition/mc/entitywrapper/PlayerWrapper;Ldev/tr7zw/waveycapes/render/CapeRenderer;";
        String tail = "IFL" + V + "PoseStack$Pose;L" + V + "VertexConsumer;)V";
        for (int i = 0; i < 3; i++) {
            MethodNode method = exact(type, "lambda$submitCape$" + i, "(" + args + (i == 0 ? "Ldev/tr7zw/waveycapes/render/CapePass;" : "") + tail);
            int fields = 0, calls = 0;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(W)
                        && field.name.equals("customCapeRenderer") && field.desc.equals("Ldev/tr7zw/waveycapes/render/CustomCapeRenderer;")) {
                    method.instructions.set(field, new InsnNode(Opcodes.POP)); fields++;
                } else if (instruction instanceof MethodInsnNode invoke && invoke.owner.equals("dev/tr7zw/waveycapes/render/CustomCapeRenderer")
                        && invoke.name.equals("render") && invoke.desc.equals("(" + args + "L" + V + "VertexConsumer;L" + V + "PoseStack;IF)V")) {
                    method.instructions.set(invoke, new MethodInsnNode(Opcodes.INVOKESTATIC, host, "hostGeometry", invoke.desc, false)); calls++;
                }
            }
            if (fields != 1 || calls != 1) throw new AssertionError("GEOMETRY_TERMINAL_COUNT " + method.name + " fields=" + fields + " calls=" + calls);
        }
        return 3;
    }
}

package com.naocraftlab.skins.buildlogic.terminal;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class ExtractionTerminalBindings {
    private static final String P = "com/naocraftlab/skins/compat/client/identifier/extraction/AvatarRenderStatePreviewRenderer";
    private static final String C = P + "$PreviewPlayer";
    private static final String N = "net/minecraft/";
    private static final String S = N + "client/renderer/entity/state/EntityRenderState";
    private static final String PROBE = "com/naocraftlab/skins/buildlogic/probe/wavey/ExtractionPublicationProbe";
    public static final String TERMINALS = "com/naocraftlab/skins/buildlogic/probe/wavey/RawPublicationTerminals";
    public static final Set<String> OWNERS = Set.of(P, C, N + "client/renderer/entity/EntityRenderer", N + "client/gui/GuiGraphicsExtractor");
    private static MethodNode exact(ClassNode node, String name, String desc) {
        var found = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).toList();
        if (found.size() != 1) throw new AssertionError("EXTRACTION_DESCRIPTOR_COUNT " + node.name + "." + name + desc + "=" + found.size());
        return found.getFirst();
    }
    public static ClassNode helper() {
        ClassNode result = new ClassNode(); result.version = Opcodes.V17; result.access = Opcodes.ACC_PUBLIC;
        result.name = TERMINALS; result.superName = "java/lang/Object"; return result;
    }
    private static void empty(MethodNode method) {
        method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null; method.maxStack = 8;
    }
    private static String key(String owner, String name, String desc) { return owner + "." + name + desc; }
    private static void add(Map<String, Integer> map, String owner, String name, String desc, int count) { map.put(key(owner, name, desc), count); }
    private static Map<String, Integer> inventory(String method) { return rawInventory(method, C); }
    public static Map<String, Integer> rawInventory(String method, String C) {
        Map<String, Integer> result = new HashMap<>();
        String vec = "L" + N + "world/phys/Vec3;", avatar = "L" + N + "client/entity/ClientAvatarState;";
        if (method.equals("previewPlayer")) {
            add(result, C, "setId", "(I)V", 1);
            add(result, C, "setItemSlot", "(L" + N + "world/entity/EquipmentSlot;L" + N + "world/item/ItemStack;)V", 2);
            add(result, N + "client/player/LocalPlayer", "position", "()" + vec, 1);
            add(result, C, "setPos", "(" + vec + ")V", 1);
            add(result, C, "setYRot", "(F)V", 1); add(result, C, "setXRot", "(F)V", 1);
            add(result, C, "setOldPosAndRot", "()V", 1); add(result, C, "setOnGround", "(Z)V", 1);
            add(result, C, "setPose", "(L" + N + "world/entity/Pose;)V", 1); add(result, C, "setInvisible", "(Z)V", 1);
        } else if (method.equals("applySettling")) {
            for (String axis : List.of("X", "Y", "Z")) add(result, C, "get" + axis, "()D", 1);
            add(result, C, "setPos", "(DDD)V", 1); add(result, C, "tick", "()V", 1);
        } else {
            add(result, C, "getEntityData", "()L" + N + "network/syncher/SynchedEntityData;", 1);
            add(result, N + "network/syncher/SynchedEntityData", "set", "(L" + N + "network/syncher/EntityDataAccessor;Ljava/lang/Object;)V", 1);
            return result;
        }
        add(result, C, "avatarState", "()" + avatar, 1); add(result, C, "position", "()" + vec, 1);
        add(result, N + "client/entity/ClientAvatarState", "tick", "(" + vec + vec + ")V", 1);
        return result;
    }
    private static int raw(ClassNode node, MethodNode method, ClassNode helper) {
        Map<String, Integer> expected = inventory(method.name), found = new HashMap<>();
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && expected.containsKey(key(call.owner, call.name, call.desc)))
            found.merge(key(call.owner, call.name, call.desc), 1, Integer::sum);
        if (!found.equals(expected)) throw new AssertionError("EXTRACTION_OPERATION_COUNT " + method.name + " " + found);
        int index = 0, constructors = 0;
        for (var instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.equals(C)) {
                if (type.getNext().getOpcode() != Opcodes.DUP) throw new AssertionError("EXTRACTION_CONSTRUCTOR_PATTERN");
                method.instructions.remove(type.getNext()); method.instructions.remove(type);
            }
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (call.owner.equals(C) && call.name.equals("<init>") && call.desc.equals("(L" + N + "client/multiplayer/ClientLevel;Lcom/mojang/authlib/GameProfile;)V")) {
                var next = call.getNext();
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, PROBE, "rawPreview", "(L" + N + "client/multiplayer/ClientLevel;Lcom/mojang/authlib/GameProfile;)Ljava/lang/Object;", false));
                method.instructions.insertBefore(next, new TypeInsnNode(Opcodes.CHECKCAST, C));
                constructors++; continue;
            }
            if (!expected.containsKey(key(call.owner, call.name, call.desc))) continue;
            String desc = "(L" + call.owner + ";" + call.desc.substring(1);
            String name = (node.name.equals(C) ? "player_" : "preview_") + method.name + index++;
            if (helper != null) {
                MethodNode terminal = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
                Type result = Type.getReturnType(desc);
                int opcode = switch (result.getSort()) {
                    case Type.VOID -> Opcodes.RETURN;
                    case Type.DOUBLE -> { terminal.instructions.add(new InsnNode(Opcodes.DCONST_0)); yield Opcodes.DRETURN; }
                    case Type.OBJECT, Type.ARRAY -> { terminal.instructions.add(new InsnNode(Opcodes.ACONST_NULL)); yield Opcodes.ARETURN; }
                    default -> throw new AssertionError("EXTRACTION_RAW_RESULT " + desc);
                };
                terminal.instructions.add(new InsnNode(opcode)); helper.methods.add(terminal);
            }
            method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, TERMINALS, name, desc, false));
        }
        if (constructors != (method.name.equals("previewPlayer") ? 1 : 0)) throw new AssertionError("EXTRACTION_CONSTRUCTOR_COUNT");
        return index + constructors;
    }
    public static int apply(ClassNode node, ClassNode helper) {
        if (!OWNERS.contains(node.name)) return 0;
        if (node.name.equals(P)) {
            MethodNode player = exact(node, "previewPlayer", "(L" + N + "client/Minecraft;Lcom/naocraftlab/skins/client/PreviewRenderer$PreviewAppearance;L" + N + "world/entity/player/PlayerSkin;L" + N + "world/item/ItemStack;)L" + C + ";");
            MethodNode settling = exact(node, "applySettling", "(L" + C + ";FLcom/naocraftlab/skins/client/EditorPreviewSession$SettlingMotion;)V");
            return raw(node, player, helper) + raw(node, settling, helper);
        }
        if (node.name.equals(C)) return raw(node, exact(node, "setPreviewModelParts", "(B)V"), helper);
        if (node.name.equals(N + "client/renderer/entity/EntityRenderer")) {
            MethodNode method = exact(node, "finalizeRenderState", "(L" + N + "world/entity/Entity;L" + S + ";)V");
            empty(method); method.instructions.add(new InsnNode(Opcodes.RETURN)); return 1;
        }
        String tail = ";FLorg/joml/Vector3f;Lorg/joml/Quaternionf;Lorg/joml/Quaternionf;IIII)V";
        String interfaces = tail.replace("Vector3f;", "Vector3fc;").replace("Quaternionf;", "Quaternionfc;");
        var candidates = node.methods.stream().filter(m -> m.name.equals("entity") && (m.desc.equals("(L" + S + tail) || m.desc.equals("(L" + S + interfaces))).toList();
        if (candidates.size() != 1) throw new AssertionError("EXTRACTION_DESCRIPTOR_COUNT GUI entity=" + candidates.size());
        MethodNode method = candidates.getFirst(); empty(method); method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PROBE, "published", "(L" + S + ";)V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN)); return 1;
    }
}

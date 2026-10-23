package com.naocraftlab.skins.buildlogic.terminal;

import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class BakedPreviewBindings {
    public static final Set<String> OWNERS = Set.of("com/mojang/blaze3d/platform/Lighting", "net/minecraft/client/model/Model");
    public static int apply(ClassNode node) {
        if (!OWNERS.contains(node.name)) return 0;
        boolean lighting = node.name.endsWith("Lighting");
        String name = lighting ? "setupFor" : "renderToBuffer";
        String desc = lighting ? "(Lcom/mojang/blaze3d/platform/Lighting$Entry;)V"
                : "(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V";
        var methods = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).toList();
        if (methods.size() != 1) throw new AssertionError("BAKED_DESCRIPTOR_COUNT " + node.name + "." + name + desc + "=" + methods.size());
        MethodNode method = methods.get(0);
        method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
        if (!lighting) {
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            for (int i = 0; i < 3; i++) method.instructions.add(new InsnNode(Opcodes.FCONST_0));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "com/mojang/blaze3d/vertex/VertexConsumer", "addVertex", "(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;", true));
            method.instructions.add(new InsnNode(Opcodes.POP));
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN)); method.maxStack = 4;
        return 1;
    }
}

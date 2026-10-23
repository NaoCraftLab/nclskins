package com.naocraftlab.skins.buildlogic.terminal;

import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class ImmediatePreviewBindings {
    private static final String N = "net/minecraft/";
    public static final String INVENTORY = N + "client/gui/screens/inventory/InventoryScreen";
    public static String inventoryDescriptor(String mode) {
        return "(Lnet/minecraft/client/gui/GuiGraphics;" + (mode.equals("mesh") ? "FFFLorg/joml/Vector3f;" : "III")
                + "Lorg/joml/Quaternionf;Lorg/joml/Quaternionf;Lnet/minecraft/world/entity/LivingEntity;)V";
    }
    public static String preview(String mode) {
        return "com/naocraftlab/skins/compat/client/resourcelocation/" + (mode.equals("mesh")
                ? "skinlookup/VanillaAppearancePreviewRenderer" : "playerinfo/RemotePlayerPreviewRenderer");
    }
    public static Set<String> owners(String mode) {
        return Set.of(preview(mode), INVENTORY, N + "client/gui/GuiGraphics", "com/mojang/blaze3d/platform/Lighting");
    }
    private static MethodNode exact(ClassNode node, String name, String desc) {
        var methods = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).toList();
        if (methods.size() != 1) throw new AssertionError("IMMEDIATE_PREVIEW_DESCRIPTOR_COUNT " + node.name + "." + name + desc);
        return methods.get(0);
    }
    private static void count(int value, int expected) {
        if (value != expected) throw new AssertionError("IMMEDIATE_PREVIEW_OPERATION_COUNT " + value + " != " + expected);
    }
    public static int apply(ClassNode node, String mode) {
        if (!owners(mode).contains(node.name)) return 0;
        if (node.name.equals(INVENTORY)) {
            MethodNode method = exact(node, "renderEntityInInventory", inventoryDescriptor(mode));
            method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, mode.equals("mesh") ? 7 : 6));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/naocraftlab/skins/buildlogic/probe/wavey/ImmediatePublicPreviewProbe", "guiTerminal",
                    "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/entity/LivingEntity;)V", false));
            method.instructions.add(new InsnNode(Opcodes.RETURN));
            return 1;
        }
        if (node.name.equals(preview(mode))) {
            MethodNode method = exact(node, "configureEntity", "(L" + node.name + "$PreviewPlayer;Lcom/naocraftlab/skins/client/PreviewRenderer$PreviewRequest;)V");
            int ticks = 0;
            for (var instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(node.name + "$PreviewPlayer") && call.name.equals("tick") && call.desc.equals("()V")) {
                method.instructions.set(call, new InsnNode(Opcodes.POP)); ticks++;
            }
            count(ticks, 1); return ticks;
        }
        if (node.name.endsWith("GuiGraphics")) {
            MethodNode method = exact(node, "flush", "()V");
            int disable = 0, enable = 0;
            for (var instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("com/mojang/blaze3d/systems/RenderSystem") && call.desc.equals("()V")) {
                if (call.name.equals("disableDepthTest")) { method.instructions.remove(call); disable++; }
                else if (call.name.equals("enableDepthTest")) { method.instructions.remove(call); enable++; }
            }
            count(disable, 1); count(enable, 1); return 2;
        }
        for (String name : List.of("setupForEntityInInventory", "setupFor3DItems")) {
            MethodNode method = exact(node, name, "()V");
            method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
            method.instructions.add(new InsnNode(Opcodes.RETURN));
        }
        return 2;
    }
}

package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class BindOitDrawTerminals {
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0], "net/minecraft/client/renderer/rendertype/PreparedRenderType.class");
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(input)).accept(node, 0);
        int edits = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("drawFromBuffer") && !method.name.equals("drawFromBufferOit")) continue;
            method.instructions.clear();
            method.tryCatchBlocks.clear();
            method.localVariables = null;
            int arguments = Type.getArgumentTypes(method.desc).length;
            for (int slot = 0; slot <= arguments; slot++) method.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/naocraftlab/skins/buildlogic/probe/wavey/OitDrawProbe", method.name,
                    "(L" + node.name + ";" + method.desc.substring(1), false));
            method.instructions.add(new InsnNode(Opcodes.RETURN));
            method.maxLocals = arguments + 1;
            method.maxStack = arguments + 1;
            edits++;
        }
        if (edits != 2) throw new AssertionError("Expected exactly two GPU draw terminals: " + edits);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = Path.of(args[1]).resolve("net/minecraft/client/renderer/rendertype/PreparedRenderType.class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
        System.out.println("TWO_DRAW_GPU_TERMINALS_BOUND; executeGroup branch, loop and woven validity checks unchanged");
    }
}

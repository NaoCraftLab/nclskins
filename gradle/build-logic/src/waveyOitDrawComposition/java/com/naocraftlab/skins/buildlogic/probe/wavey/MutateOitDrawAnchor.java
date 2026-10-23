package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class MutateOitDrawAnchor {
    public static void main(String[] args) throws Exception {
        String owner = "net/minecraft/client/renderer/feature/RenderTypeFeatureRenderer";
        ClassNode node = new ClassNode();
        try (var input = ClassLoader.getSystemResourceAsStream(owner + ".class")) {
            new ClassReader(input.readAllBytes()).accept(node, 0);
        }
        int changed = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("executeGroup")) continue;
            if (args[1].equals("selector")) { method.name = "nclskins$missingSelector"; changed++; }
            else for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.endsWith("/PreparedRenderType")
                        && call.name.equals(args[1])) {
                    call.name = "nclskins$missingDrawOperation";
                    changed++;
                }
            }
        }
        if (changed != 1) throw new AssertionError("Mutation must remove one exact selector/anchor: " + changed);
        ClassWriter writer = new ClassWriter(0); node.accept(writer);
        Path output = Path.of(args[0]).resolve(owner + ".class");
        Files.createDirectories(output.getParent()); Files.write(output, writer.toByteArray());
        System.out.println("ONE_NATIVE_DRAW_ANCHOR_MUTATED " + args[1]);
    }
}

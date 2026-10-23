package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.nio.file.*;
import com.naocraftlab.skins.buildlogic.terminal.ExtractionTerminalBindings;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class BindExtractionPublicationInputs {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        ClassNode helper = ExtractionTerminalBindings.helper();
        for (String owner : ExtractionTerminalBindings.OWNERS.stream().sorted().toList()) {
            ClassNode node = new ClassNode();
            try (var in = ClassLoader.getSystemResourceAsStream(owner + ".class")) { new ClassReader(in.readAllBytes()).accept(node, 0); }
            int count = ExtractionTerminalBindings.apply(node, helper);
            if (args.length > 1 && args[1].equals("missing") && owner.endsWith("/AvatarRenderStatePreviewRenderer")) {
                int removed = 0;
                for (MethodNode method : node.methods) for (var instruction : method.instructions.toArray()) {
                    if (instruction instanceof MethodInsnNode call && call.owner.equals("com/naocraftlab/skins/compat/client/identifier/CapeMaterialSnapshot$Carrier")
                            && call.name.equals("nclskins$setCapeMaterial") && call.desc.equals("(Lcom/naocraftlab/skins/compat/client/identifier/CapeMaterialSnapshot;)V")) {
                        method.instructions.set(call, new InsnNode(Opcodes.POP2)); removed++;
                    }
                }
                if (removed != 1) throw new AssertionError("Exact missing-publication mutation count " + removed);
            }
            save(root, node);
            System.out.println("EXTRACTION_EXACT_TERMINALS " + owner + "=" + count);
        }
        save(root, helper);
    }
    private static void save(Path root, ClassNode node) throws Exception {
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(out);
        Path dest = root.resolve(node.name + ".class"); Files.createDirectories(dest.getParent()); Files.write(dest, out.toByteArray());
    }
}
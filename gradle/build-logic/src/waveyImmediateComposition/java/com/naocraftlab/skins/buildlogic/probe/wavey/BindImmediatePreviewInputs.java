package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.naocraftlab.skins.buildlogic.terminal.ImmediatePreviewBindings;
import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;

public final class BindImmediatePreviewInputs {
    public static void main(String[] args) throws Exception {
        int changed = 0;
        for (String owner : ImmediatePreviewBindings.owners(args[1])) {
            ClassNode node = new ClassNode();
            try (var input = ClassLoader.getSystemResourceAsStream(owner + ".class")) { new ClassReader(input.readAllBytes()).accept(node, 0); }
            changed += ImmediatePreviewBindings.apply(node, args[1]);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer);
            Path output = Path.of(args[0]).resolve(owner + ".class"); Files.createDirectories(output.getParent()); Files.write(output, writer.toByteArray());
        }
        if (changed != 6) throw new AssertionError("IMMEDIATE_PUBLIC_BINDER_COUNT");
        System.out.println("IMMEDIATE_PUBLIC_EXACT_TERMINALS=6; same pure binder as canonical CLIENT SPI; final InventoryScreen GUI SUBSTITUTED; full native body/nameplate/layer UI NOT_EXECUTED");
    }
}

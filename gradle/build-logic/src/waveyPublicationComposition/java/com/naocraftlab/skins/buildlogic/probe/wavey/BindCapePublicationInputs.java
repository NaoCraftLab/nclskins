package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class BindCapePublicationInputs {
    public static void main(String[] args) throws Exception {
        String name = "net/minecraft/client/renderer/entity/player/AvatarRenderer";
        ClassNode node = new ClassNode();
        try (var in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
            new ClassReader(in.readAllBytes()).accept(node, 0);
        }
        int changed = com.naocraftlab.skins.buildlogic.terminal.CapeTerminalBindings.apply(node);
        if (changed != 1) throw new AssertionError("Exact raw extraction ABI: " + changed);
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(out);
        Path dest = Path.of(args[0]).resolve(name + ".class");
        Files.createDirectories(dest.getParent());
        Files.write(dest, out.toByteArray());
        System.out.println("RAW_INPUT_ONLY AvatarRenderer.extractRenderState(Avatar,AvatarRenderState,float); no NCL material; normal Mixin follows");
    }
}

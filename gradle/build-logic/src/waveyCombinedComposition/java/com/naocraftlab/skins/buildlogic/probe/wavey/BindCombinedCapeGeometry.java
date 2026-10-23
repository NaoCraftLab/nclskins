package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class BindCombinedCapeGeometry {
    public static void main(String[] args) throws Exception {
        String owner = "dev/tr7zw/waveycapes/render/CapeNodeCollector";
        ClassNode node = new ClassNode();
        try (var in = ClassLoader.getSystemResourceAsStream(owner + ".class")) { new ClassReader(in.readAllBytes()).accept(node, 0); }
        int count = com.naocraftlab.skins.buildlogic.terminal.CapeTerminalBindings.apply(node, "combined");
        if (count != 3) throw new AssertionError("Geometry sink count=" + count);
        ClassWriter writer = new ClassWriter(0); node.accept(writer);
        Path path = Path.of(args[0]).resolve(owner + ".class"); Files.createDirectories(path.getParent()); Files.write(path, writer.toByteArray());
        System.out.println("COMBINED_GEOMETRY_TERMINALS=3; support selection/public pass loop/legacy return/wrappers unchanged");
    }
}

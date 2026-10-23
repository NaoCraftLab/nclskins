package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class BindCapeGeometry {
    public static void main(String[] arguments) throws Exception {
        String owner = "dev/tr7zw/waveycapes/render/CapeNodeCollector";
        byte[] original;
        try (ZipFile jar = new ZipFile(arguments[0])) {
            original = jar.getInputStream(jar.getEntry(owner + ".class")).readAllBytes();
        }
        var node = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(original).accept(node, 0);
        int edits = com.naocraftlab.skins.buildlogic.terminal.CapeTerminalBindings.apply(node, "prepared");
        if (edits != 3) throw new AssertionError("geometry host binding count=" + edits);
        ClassWriter writer = new ClassWriter(0); node.accept(writer);
        Path target = Path.of(arguments[1]).resolve(owner + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, writer.toByteArray());
        System.out.println("BOUND_GEOMETRY_SINK=3; registry/pass-list/submission/wrapper/early-return unchanged");
    }
}

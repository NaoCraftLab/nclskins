package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class BindBufferedCapeTerminals {
    private static final String HOST = "com/naocraftlab/skins/buildlogic/probe/wavey/BufferedCapeHost";
    public static void main(String[] args) throws Exception {
        int changed = 0;
        try (ZipFile jar = new ZipFile(args[0])) {
            for (String owner : List.of("com/mojang/blaze3d/vertex/BufferBuilder", "com/mojang/blaze3d/vertex/MeshData",
                    "net/minecraft/client/renderer/rendertype/RenderType")) {
                ClassNode type = new ClassNode();
                new ClassReader(jar.getInputStream(jar.getEntry(owner + ".class")).readAllBytes()).accept(type, 0);
                changed += com.naocraftlab.skins.buildlogic.terminal.CapeTerminalBindings.apply(type);
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); type.accept(writer);
                Path output = Path.of(args[1]).resolve(owner + ".class");
                Files.createDirectories(output.getParent()); Files.write(output, writer.toByteArray());
            }
        }
        if (changed != 6) throw new AssertionError("Buffered host terminal count=" + changed);
        if (ClassLoader.getSystemResource("com/naocraftlab/skins/buildlogic/probe/wavey/BakedPublicPreviewProbe.class") != null) {
            for (String owner : com.naocraftlab.skins.buildlogic.terminal.BakedPreviewBindings.OWNERS) {
                ClassNode type = new ClassNode();
                try (var in = ClassLoader.getSystemResourceAsStream(owner + ".class")) { new ClassReader(in.readAllBytes()).accept(type, 0); }
                if (com.naocraftlab.skins.buildlogic.terminal.BakedPreviewBindings.apply(type) != 1) throw new AssertionError("BAKED_BINDER_COUNT");
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); type.accept(writer);
                Path output = Path.of(args[1]).resolve(owner + ".class");
                Files.createDirectories(output.getParent()); Files.write(output, writer.toByteArray());
            }
            System.out.println("BAKED_PRE_MIXIN_TERMINALS=2");
            for (String owner : com.naocraftlab.skins.buildlogic.terminal.SubmissionPreviewBindings.OWNERS) {
                ClassNode type = new ClassNode();
                try (var in = ClassLoader.getSystemResourceAsStream(owner + ".class")) { new ClassReader(in.readAllBytes()).accept(type, 0); }
                if (com.naocraftlab.skins.buildlogic.terminal.SubmissionPreviewBindings.apply(type) == 0) throw new AssertionError("LIVE_BINDER_COUNT");
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); type.accept(writer);
                Path output = Path.of(args[1]).resolve(owner + ".class");
                Files.createDirectories(output.getParent()); Files.write(output, writer.toByteArray());
            }
        }
        System.out.println("BUFFERED_TERMINALS=6; native getBuffer/endBatch/custom dispatch/storage unchanged");
    }
}

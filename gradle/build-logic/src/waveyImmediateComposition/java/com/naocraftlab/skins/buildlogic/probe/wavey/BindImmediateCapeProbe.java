package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.naocraftlab.skins.buildlogic.terminal.ImmediateTerminalBindings;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class BindImmediateCapeProbe {
    private static final String HOST = "com/naocraftlab/skins/buildlogic/probe/wavey/ImmediateCapeHost";
    private static final String BUFFERS = "com/naocraftlab/skins/buildlogic/probe/wavey/ImmediateBuffers";
    private static final String BUILDER = "com/mojang/blaze3d/vertex/BufferBuilder";
    private static final String TYPE = "net/minecraft/client/renderer/RenderType";

    private static void save(Path output, String owner, ClassNode node) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path path = output.resolve(owner + ".class");
        Files.createDirectories(path.getParent());
        Files.write(path, writer.toByteArray());
    }

    public static void main(String[] args) throws Exception {
        boolean mesh = args[3].equals("mesh");
        Path output = Path.of(args[2]);
        int bindings = 0;
        try (ZipFile wavey = new ZipFile(args[0]); ZipFile minecraft = new ZipFile(args[1])) {
            for (String owner : ImmediateTerminalBindings.owners(args[3])) {
                ZipFile archive = owner.startsWith("dev/") ? wavey : minecraft;
                ClassNode node = new ClassNode();
                new ClassReader(archive.getInputStream(archive.getEntry(owner + ".class")).readAllBytes()).accept(node, 0);
                bindings += ImmediateTerminalBindings.apply(node, args[3]);
                save(output, owner, node);
            }
        }
        if (bindings != (mesh ? 10 : 7)) throw new AssertionError("IMMEDIATE_TOTAL_BINDINGS " + bindings);
        System.out.println("IMMEDIATE_TERMINALS_BOUND geometry=2 gpu=3; real collector decision/pass loop/wrappers/legacy branch and native BufferSource getBuffer/endBatch and RenderType end/draw control flow unchanged; CPU vertex storage substituted");
    }
}

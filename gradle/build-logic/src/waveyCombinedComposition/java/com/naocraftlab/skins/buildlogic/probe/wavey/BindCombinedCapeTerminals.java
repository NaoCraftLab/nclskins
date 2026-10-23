package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class BindCombinedCapeTerminals {
    public static void main(String[] args) throws Exception {
        for (String owner : java.util.List.of("net/minecraft/client/renderer/rendertype/RenderType", "net/minecraft/client/renderer/rendertype/PreparedRenderType")) {
            ClassNode type = read(owner);
            com.naocraftlab.skins.buildlogic.terminal.CapeTerminalBindings.apply(type, "combined");
            save(type, args[0]);
        }
        System.out.println("COMBINED_GPU_TERMINALS=3; material policy, collector, storage, build, prepareGroup, consolidation, executeGroup unchanged; prepare RETURN woven afterwards");
    }
    private static ClassNode read(String owner) throws Exception {
        ClassNode node = new ClassNode();
        try (var in = ClassLoader.getSystemResourceAsStream(owner + ".class")) { new ClassReader(in.readAllBytes()).accept(node, 0); }
        return node;
    }
    private static void save(ClassNode node, String directory) throws Exception {
        ClassWriter writer = new ClassWriter(0); node.accept(writer);
        Path path = Path.of(directory).resolve(node.name + ".class");
        Files.createDirectories(path.getParent()); Files.write(path, writer.toByteArray());
    }
}

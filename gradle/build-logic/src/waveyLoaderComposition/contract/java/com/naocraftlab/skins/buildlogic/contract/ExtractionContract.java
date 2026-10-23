package com.naocraftlab.skins.buildlogic.contract;

import com.naocraftlab.skins.buildlogic.terminal.ExtractionTerminalBindings;
import java.nio.file.Path;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class ExtractionContract {
    private static byte[] bytes(ClassNode node) { ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(out); return out.toByteArray(); }
    public static void verify(List<Path> inputs) throws Exception {
        ClassNode helper = ExtractionTerminalBindings.helper();
        for (String owner : ExtractionTerminalBindings.OWNERS.stream().sorted().toList()) {
            ClassNode node = TerminalContractProbe.read(owner, inputs);
            String target = owner.endsWith("$PreviewPlayer") ? "setPreviewModelParts" : owner.endsWith("/AvatarRenderStatePreviewRenderer")
                    ? "previewPlayer" : owner.endsWith("/EntityRenderer") ? "finalizeRenderState" : "entity";
            var methods = node.methods.stream().filter(m -> m.name.equals(target) && (!target.equals("entity") || m.desc.startsWith("(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))).toList();
            if (methods.size() != 1) throw new AssertionError("EXTRACTION_TEST_TARGET");
            String desc = methods.getFirst().desc;
            int count = ExtractionTerminalBindings.apply(node, helper);
            if (count <= 0) throw new AssertionError("EXTRACTION_NOT_REACHED");
            ClassNode spi = TerminalContractProbe.read(owner, inputs); ExtractionTerminalBindings.apply(spi, null);
            if (!Arrays.equals(bytes(node), bytes(spi))) throw new AssertionError("CLI_SPI_TERMINAL_DIVERGENCE");
            ClassNode wrong = TerminalContractProbe.read(owner, inputs);
            wrong.methods.stream().filter(m -> m.name.equals(target) && m.desc.equals(desc)).findFirst().orElseThrow().desc = "()Ljava/lang/Object;";
            try { ExtractionTerminalBindings.apply(wrong, null); throw new IllegalStateException("EXTRACTION_WRONG_DESCRIPTOR_ACCEPTED"); }
            catch (AssertionError expected) { if (!expected.getMessage().startsWith("EXTRACTION_DESCRIPTOR_COUNT")) throw expected; }
            ClassNode duplicate = TerminalContractProbe.read(owner, inputs);
            duplicate.methods.add(duplicate.methods.stream().filter(m -> m.name.equals(target) && m.desc.equals(desc)).findFirst().orElseThrow());
            try { ExtractionTerminalBindings.apply(duplicate, null); throw new IllegalStateException("EXTRACTION_DUPLICATE_ACCEPTED"); }
            catch (AssertionError expected) { if (!expected.getMessage().startsWith("EXTRACTION_DESCRIPTOR_COUNT")) throw expected; }
            if (target.equals("previewPlayer")) {
                ClassNode badCall = TerminalContractProbe.read(owner, inputs);
                MethodNode player = badCall.methods.stream().filter(m -> m.name.equals(target)).findFirst().orElseThrow();
                for (var instruction : player.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals("setId")) { call.desc = "(J)V"; break; }
                try { ExtractionTerminalBindings.apply(badCall, null); throw new IllegalStateException("EXTRACTION_WRONG_OPERATION_ACCEPTED"); }
                catch (AssertionError expected) { if (!expected.getMessage().startsWith("EXTRACTION_OPERATION_COUNT")) throw expected; }
                for (ClassNode observed : List.of(node, spi)) {
                    long publications = observed.methods.stream().filter(m -> m.name.equals("render")).flatMap(m -> Arrays.stream(m.instructions.toArray()))
                            .filter(i -> i instanceof MethodInsnNode call && call.name.equals("nclskins$setCapeMaterial")).count();
                    if (publications != 1) throw new AssertionError("PRODUCTION_PUBLICATION_CHANGED");
                }
            }
        }
        if (helper.methods.size() != 24) throw new AssertionError("RAW_HELPER_INVENTORY=" + helper.methods.size());
        System.out.println("EXTRACTION_EXACT_CONTRACT_PASS owners=4; CLI/SPI identical; full descriptors/counts, duplicate/wrong-operation rejected; production publication retained; ZERO FML");
    }
}

package com.naocraftlab.skins.buildlogic.contract;

import com.naocraftlab.skins.buildlogic.processor.CapeTerminalProvider.TerminalProcessor;
import com.naocraftlab.skins.buildlogic.terminal.CapeTerminalBindings;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import net.neoforged.fml.classloading.transformation.ClassProcessorSet;
import net.neoforged.neoforgespi.transformation.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class TerminalContractProbe {
    public static ClassNode read(String owner, List<Path> inputs) throws Exception {
        for (Path input : inputs) if (Files.isDirectory(input) && Files.isRegularFile(input.resolve(owner + ".class"))) {
            ClassNode node = new ClassNode(); new ClassReader(Files.readAllBytes(input.resolve(owner + ".class"))).accept(node, 0); return node;
        }
        for (Path input : inputs) if (Files.isRegularFile(input)) try (ZipFile zip = new ZipFile(input.toFile())) {
            var entry = zip.getEntry(owner + ".class");
            if (entry != null) { ClassNode node = new ClassNode(); new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, 0); return node; }
        }
        throw new AssertionError("MISSING_NATIVE " + owner);
    }
    public static void main(String[] args) throws Exception {
        ProcessorOrderContract.main(new String[0]);
        var terminal = new TerminalProcessor();
        ClassProcessor mixin = new ClassProcessor() {
            public ProcessorName name() { return ClassProcessorIds.MIXIN; }
            public boolean handlesClass(SelectionContext context) { return false; }
            public ComputeFlags processClass(TransformationContext context) { return ComputeFlags.NO_REWRITE; }
        };
        ClassProcessorSet.of(mixin, terminal).link(name -> null);
        var reversed = new LinkedHashMap<ProcessorName, ClassProcessor>();
        reversed.put(ClassProcessorIds.MIXIN, mixin); reversed.put(TerminalProcessor.NAME, terminal);
        try { terminal.link(new ClassProcessor.LinkContext(reversed, null)); throw new IllegalStateException("REVERSED_ORDER_ACCEPTED"); }
        catch (AssertionError expected) { if (!expected.getMessage().startsWith("TERMINAL_ORDER")) throw expected; }
        List<Path> inputs = Arrays.stream(args).map(Path::of).toList();
        ClassNode thread = read(com.naocraftlab.skins.buildlogic.probe.wavey.CapeUnsupportedAbiProbe.THREAD, inputs);
        ClassWriter threadWriter = new ClassWriter(0); thread.accept(threadWriter);
        com.naocraftlab.skins.buildlogic.probe.wavey.CapeUnsupportedAbiProbe.selectorControls(threadWriter.toByteArray());
        for (String[] selector : List.of(new String[]{com.naocraftlab.skins.buildlogic.probe.wavey.CapeUnsupportedAbiProbe.RENDERER,
                "getCapePasses", com.naocraftlab.skins.buildlogic.probe.wavey.CapeUnsupportedAbiProbe.PASSES},
                new String[]{com.naocraftlab.skins.buildlogic.probe.wavey.CapeUnsupportedAbiProbe.PASS,
                        "wrapper", com.naocraftlab.skins.buildlogic.probe.wavey.CapeUnsupportedAbiProbe.WRAPPER})) {
            ClassNode sdk = read(selector[0], inputs);
            com.naocraftlab.skins.buildlogic.probe.wavey.CapeUnsupportedAbiProbe.select(sdk, selector[0], selector[1], selector[2], false);
            sdk.methods.removeIf(method -> method.name.equals(selector[1]) && method.desc.equals(selector[2]));
            try {
                com.naocraftlab.skins.buildlogic.probe.wavey.CapeUnsupportedAbiProbe.select(sdk, selector[0], selector[1], selector[2], false);
                throw new AssertionError("ABI_MISSING_SELECTOR_ACCEPTED");
            } catch (IllegalArgumentException expected) { }
        }
        System.out.println("CANONICAL_ABI_SELECTORS_PASS");
        if (Boolean.getBoolean("nclskins.wavey.extraction")) { ExtractionContract.verify(inputs); BakedPreviewContract.verify(inputs); }
        else { BakedPreviewContract.verify(inputs); SubmissionPreviewContract.verify(inputs); }
        for (String owner : List.of("com/naocraftlab/skins/buildlogic/boot/ClientCapeLauncher", "com/naocraftlab/skins/buildlogic/boot/CompositionAgent")) {
            ClassNode driver = read(owner, inputs);
            for (MethodNode method : driver.methods) {
                if (method.desc.contains("Lnet/minecraft/") || method.desc.contains("Lnet/neoforged/neoforge/")) throw new AssertionError("BOOT_NATIVE_SIGNATURE");
                for (var instruction : method.instructions) {
                    String referenced = instruction instanceof MethodInsnNode call ? call.owner
                            : instruction instanceof FieldInsnNode field ? field.owner : instruction instanceof TypeInsnNode type ? type.desc : "";
                    if (referenced.startsWith("net/minecraft/") || referenced.startsWith("net/neoforged/neoforge/")
                            || referenced.startsWith("com/naocraftlab/skins/runtime/")) throw new AssertionError("BOOT_NATIVE_REFERENCE");
                }
            }
        }
        int count = 0;
        String mode = System.getProperty("nclskins.wavey.mode", "buffered");
        boolean combinedMode = mode.equals("combined");
        boolean preparedMode = mode.equals("prepared") || combinedMode;
        if (combinedMode) CombinedContract.main(new String[0]);
        for (String owner : CapeTerminalBindings.owners(System.getProperty("nclskins.wavey.mode", "buffered"))) {
            ClassNode type = read(owner, inputs);
            if (combinedMode) CombinedContract.verifyPinned(type);
            int methods = type.methods.size();
            count += CapeTerminalBindings.apply(type);
            if (type.methods.size() != methods) throw new AssertionError("METHOD_INVENTORY");
            ClassNode mutant = read(owner, inputs);
            MethodNode targeted = mutant.methods.stream().filter(m -> switch(owner) {
                case "com/mojang/blaze3d/vertex/BufferBuilder" -> m.name.equals("build");
                case "com/mojang/blaze3d/vertex/MeshData" -> m.name.equals("close");
                case "net/minecraft/client/renderer/rendertype/RenderType" -> m.name.equals(preparedMode ? "prepare" : "draw");
                case "net/minecraft/client/renderer/rendertype/PreparedRenderType" -> m.name.equals("drawFromBuffer");
                case "dev/tr7zw/waveycapes/render/CapeNodeCollector" -> m.name.equals("lambda$submitCape$0");
                default -> m.name.equals("extractRenderState") && m.desc.startsWith("(Lnet/minecraft/world/entity/Avatar;");
            }).findFirst().orElseThrow();
            targeted.desc = "()Ljava/lang/Object;";
            try { CapeTerminalBindings.apply(mutant); throw new IllegalStateException("WRONG_DESCRIPTOR_ACCEPTED"); }
            catch (AssertionError expected) { if (!expected.getMessage().startsWith("TERMINAL_DESCRIPTOR_COUNT")) throw expected; }
            ClassNode duplicate = read(owner, inputs);
            MethodNode original = duplicate.methods.stream().filter(m -> m.name.equals(targeted.name)).findFirst().orElseThrow();
            duplicate.methods.add(original);
            try { CapeTerminalBindings.apply(duplicate); throw new IllegalStateException("DUPLICATE_ACCEPTED"); }
            catch (AssertionError expected) { if (!expected.getMessage().startsWith("TERMINAL_DESCRIPTOR_COUNT")) throw expected; }
        }
        if (count != (combinedMode ? 7 : preparedMode ? 6 : 10)) throw new AssertionError("TERMINAL_TOTAL=" + count);
        var unknown = new ClassNode(); unknown.name = "net/minecraft/client/Minecraft";
        if (CapeTerminalBindings.apply(unknown) != 0) throw new AssertionError("UNLISTED_OWNER");
        var events = new ArrayList<String>();
        var primary = new IllegalStateException("synthetic abort");
        try {
            com.naocraftlab.skins.buildlogic.probe.wavey.CaseLifecycle.execute(() -> { events.add("abort"); throw primary; },
                    () -> { events.add("cleanup"); throw new IllegalArgumentException("synthetic cleanup"); });
            throw new AssertionError("ABORT_SWALLOWED");
        } catch (IllegalStateException actual) {
            if (actual != primary || actual.getSuppressed().length != 1) throw new AssertionError("PRIMARY_FAILURE_LOST");
        }
        com.naocraftlab.skins.buildlogic.probe.wavey.CaseLifecycle.execute(() -> events.add("fresh-normal"), () -> events.add("fresh-cleanup"));
        if (!events.equals(List.of("abort", "cleanup", "fresh-normal", "fresh-cleanup"))) throw new AssertionError("CASE_LIFECYCLE");
        String fixture = "com/naocraftlab/skins/buildlogic/probe/wavey/";
        ClassNode host = read(fixture + (combinedMode ? "CombinedCapeHost" : preparedMode ? "CapeHost" : "BufferedCapeHost"), inputs);
        var reset = host.methods.stream().filter(m -> m.name.equals("reset") && m.desc.equals("()V")).findFirst().orElseThrow();
        Set<String> writes = new HashSet<>(); int clears = 0;
        for (var instruction : reset.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC) writes.add(field.name);
            if (instruction instanceof MethodInsnNode call && call.name.equals("clear") && call.desc.equals("()V")) clears++;
        }
        Set<String> expectedWrites = combinedMode
                ? Set.of("geometry", "geometryFailure", "drawFailure", "retireAtDraw", "expectedActor", "expectedRenderer", "currentConsumer", "expectedStage", "expectedPass", "expectedLight", "expectedDelta")
                : preparedMode ? Set.of("prepares", "wrappers", "draws", "geometry", "foreignVertices", "ownVertices", "abort")
                : Set.of("draws", "geometry", "foreignVertices", "ownVertices", "closes", "abortGeometry", "drawBoundary", "geometrySink");
        if (clears != (combinedMode ? 6 : preparedMode ? 2 : 3) || !writes.equals(expectedWrites))
            throw new AssertionError("HOST_REFERENCE_COUNTER_RESET");
        ClassNode publication = read(fixture + "CapePublicationProbe", inputs);
        var publicationReset = publication.methods.stream().filter(m -> m.name.equals("reset") && m.desc.equals("()V")).findFirst().orElseThrow();
        Set<String> publicationWrites = new HashSet<>();
        for (var instruction : publicationReset.instructions) if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC)
            publicationWrites.add(field.name);
        if (!publicationWrites.equals(Set.of("lastActor", "lastState", "rawCalls"))) throw new AssertionError("PUBLICATION_REFERENCE_RESET");
        System.out.println("CANONICAL_CHEAP_ABORT_CLEANUP_PASS primary preserved; normal next case; no FML startup");
        System.out.println("CANONICAL_TERMINAL_CONTRACT_PASS terminals=" + count + "; wrong-descriptor/duplicate/reversed-order rejected; no FML startup");
    }
}

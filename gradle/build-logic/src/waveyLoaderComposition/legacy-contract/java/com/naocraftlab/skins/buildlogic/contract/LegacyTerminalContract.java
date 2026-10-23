package com.naocraftlab.skins.buildlogic.contract;

import com.naocraftlab.skins.buildlogic.terminal.ImmediateTerminalBindings;
import com.naocraftlab.skins.buildlogic.processor.LegacyCapeTerminalPlugin;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService.Phase;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class LegacyTerminalContract {
    private static byte[] find(String owner, String[] jars) throws Exception {
        for (String jar : jars) {
            if (Files.isDirectory(Path.of(jar))) {
                Path path = Path.of(jar).resolve(owner + ".class");
                if (Files.isRegularFile(path)) return Files.readAllBytes(path);
                continue;
            }
            try (ZipFile zip = new ZipFile(jar)) {
            var entry = zip.getEntry(owner + ".class");
            if (entry != null) return zip.getInputStream(entry).readAllBytes();
        }
        }
        throw new AssertionError("PINNED_CLASS_MISSING " + owner);
    }
    private static ClassNode node(byte[] bytes) { var node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static void refused(Runnable action) {
        try { action.run(); } catch (AssertionError expected) { return; }
        throw new IllegalStateException("INVALID_TERMINAL_ACCEPTED");
    }
    private static final String PROBE = "com/naocraftlab/skins/buildlogic/probe/wavey/";
    private static final String INTEGRATION = "com/naocraftlab/skins/compat/waveycapes/WaveyCapesIntegration";
    private static List<MethodNode> reachable(Map<String, ClassNode> classes, MethodNode entry) {
        List<MethodNode> result = new ArrayList<>();
        Deque<MethodNode> pending = new ArrayDeque<>(); pending.add(entry);
        while (!pending.isEmpty()) {
            MethodNode method = pending.removeFirst();
            if (result.contains(method)) continue;
            result.add(method);
            for (var insn : method.instructions.toArray()) {
                List<Handle> targets = new ArrayList<>();
                if (insn instanceof MethodInsnNode call) targets.add(new Handle(call.getOpcode(), call.owner, call.name, call.desc, call.itf));
                if (insn instanceof InvokeDynamicInsnNode dynamic) for (Object arg : dynamic.bsmArgs) if (arg instanceof Handle handle) targets.add(handle);
                for (Handle target : targets) {
                    ClassNode owner = classes.get(target.getOwner());
                    if (owner != null) for (MethodNode candidate : owner.methods)
                        if (candidate.name.equals(target.getName()) && candidate.desc.equals(target.getDesc())) pending.add(candidate);
                }
            }
        }
        return result;
    }
    private static List<MethodInsnNode> calls(List<MethodNode> methods, String owner, String name) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (MethodNode method : methods) for (var insn : method.instructions.toArray())
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) result.add(call);
        return result;
    }
    private static void assertion(List<MethodNode> methods, String reason, String... requiredCalls) {
        for (MethodNode method : methods) {
            Set<String> calls = new HashSet<>(); boolean branch = false;
            for (var insn : method.instructions.toArray()) {
                if (insn instanceof JumpInsnNode) branch = true;
                if (insn instanceof MethodInsnNode call) {
                    if (call.name.equals("check")) { calls.clear(); branch = false; }
                    else calls.add(call.name);
                }
                if (insn instanceof LdcInsnNode literal && reason.equals(literal.cst)) {
                    var next = insn.getNext(); while (next != null && next.getOpcode() < 0) next = next.getNext();
                    check(next instanceof MethodInsnNode call && call.name.equals("check") && call.desc.equals("(ZLjava/lang/String;)V")
                            && (branch || requiredCalls.length > 0) && calls.containsAll(List.of(requiredCalls)), "BRIDGE_ASSERTION_MISSING " + reason);
                    return;
                }
            }
        }
        throw new AssertionError("BRIDGE_ASSERTION_MISSING " + reason);
    }
    private static void bridgeContract(Map<String, ClassNode> classes) {
        ClassNode batch = classes.get(PROBE + "LegacyCapeBatch");
        MethodNode run = batch.methods.stream().filter(method -> method.name.equals("run")).findFirst().orElseThrow();
        String marker = null, last = null; Handle bridge = null; boolean sensitivity = false;
        for (var insn : run.instructions.toArray()) {
            if (insn instanceof LdcInsnNode literal && literal.cst instanceof String text && text.startsWith("guard-")) marker = text;
            if (insn instanceof InvokeDynamicInsnNode dynamic && "guard-bridge-contract".equals(marker))
                for (Object arg : dynamic.bsmArgs) if (arg instanceof Handle handle && handle.getOwner().equals(batch.name)) bridge = handle;
            if (insn instanceof MethodInsnNode call && call.owner.equals(batch.name) && call.name.equals("runCase")) {
                if ("guard-no-support-sensitivity".equals(marker)) sensitivity = true;
                last = marker;
            }
        }
        check(bridge != null, "BRIDGE_ENTRY_MISSING");
        Handle target = bridge;
        MethodNode entry = batch.methods.stream().filter(method -> method.name.equals(target.getName()) && method.desc.equals(target.getDesc())).findFirst().orElseThrow();
        List<MethodNode> guard = reachable(classes, entry);
        check(calls(guard, PROBE + "ImmediateGuardProbe", "bridge").size() == 1, "BRIDGE_ENTRY_BYPASSES_SUCCESSFUL_INTEGRATION");
        check(sensitivity && "guard-bridge-contract".equals(last), "BRIDGE_NOT_TERMINAL_CASE");
        List<MethodNode> lifecycle = classes.get(PROBE + "ImmediateGuardProbe").methods.stream().filter(guard::contains).toList();
        check(calls(lifecycle, INTEGRATION, "install").size() == 3 && calls(lifecycle, INTEGRATION, "close").size() == 2, "BRIDGE_INSTALL_CLOSE_LATE_COUNTS");
        check(calls(guard, PROBE + "ImmediateCapeProbe", "runCase").stream().anyMatch(call -> call.desc.contains("WaveyCapesSupport;")), "BRIDGE_NATIVE_CASE_NOT_CONNECTED");
        check(calls(guard, PROBE + "ImmediateGuardProbe", "policy").size() == 1, "BRIDGE_POLICY_NOT_CONNECTED");
        assertion(guard, "REPEATED_INSTALL_OWN_COUNT", "size");
        assertion(guard, "INSTALLED_SUPPORT_IDENTITY");
        assertion(guard, "PRIORITY_FEATURE_POLICY", "priority", "blockFeatureRenderer");
        assertion(guard, "LOGICAL_UV_DELEGATION", "vanillaUvValues", "uvSheetWidth", "uvSheetHeight");
        assertion(guard, "PUBLIC_WRAPPER_IDENTITY", "wrapper", "apply");
        assertion(guard, "FOREIGN_CHANGED_OR_LATE_INSTALL_RECREATED", "equals", "contains");
        assertion(guard, "GUARD_REGISTRY_NOT_RESTORED", "equals");
        assertion(guard, "Native plan/legacy foil policy", "size", "getCapeInfo", "isGlint");
        assertion(guard, "Genuine renderer selection/legacy early return changed", "allMatch");
        assertion(guard, "Own CPU geometry terminal retained resource", "pending");
        check(guard.stream().anyMatch(method -> method.tryCatchBlocks.stream().anyMatch(block -> "java/lang/UnsupportedOperationException".equals(block.type))), "BRIDGE_IMMUTABILITY_NOT_CONNECTED");
        check(classes.values().stream().filter(type -> type.name.startsWith(PROBE + "ImmediateGuardProbe$"))
                .flatMap(type -> type.methods.stream()).anyMatch(method -> method.name.equals("priority")
                        && Arrays.stream(method.instructions.toArray()).anyMatch(insn -> insn.getOpcode() == Opcodes.ICONST_1)), "BRIDGE_FOREIGN_PRIORITY_COLLISION");
    }
    private static void bridgeContract(Path game) throws Exception {
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        try (var paths = Files.list(game.resolve(PROBE))) {
            for (Path path : paths.filter(path -> path.getFileName().toString().matches("(?:LegacyCapeBatch|ImmediateCapeProbe|ImmediateGuardProbe(?:\\$.*)?)\\.class")).toList()) {
                ClassNode type = node(Files.readAllBytes(path)); classes.put(type.name, type);
            }
        }
        bridgeContract(classes);
        for (String mutation : List.of("install", "close", "PRIORITY_FEATURE_POLICY", "PUBLIC_WRAPPER_IDENTITY")) {
            Map<String, ClassNode> mutant = new LinkedHashMap<>();
            for (ClassNode type : classes.values()) { ClassWriter writer = new ClassWriter(0); type.accept(writer); mutant.put(type.name, node(writer.toByteArray())); }
            boolean changed = false;
            for (MethodNode method : mutant.get(PROBE + "ImmediateGuardProbe").methods) {
                for (var insn : method.instructions.toArray()) {
                    if (!changed && insn instanceof MethodInsnNode call && call.owner.equals(INTEGRATION) && call.name.equals(mutation)) {
                        method.instructions.set(call, new InsnNode(mutation.equals("install") ? Opcodes.POP : Opcodes.NOP)); changed = true;
                    }
                    if (!changed && insn instanceof LdcInsnNode literal && mutation.equals(literal.cst)) {
                        var call = insn.getNext(); while (call.getOpcode() < 0) call = call.getNext();
                        method.instructions.set(call, new InsnNode(Opcodes.POP2)); changed = true;
                    }
                }
            }
            check(changed, "BRIDGE_MUTATION_NOT_APPLIED " + mutation);
            mutant.replaceAll((name, type) -> { ClassWriter writer = new ClassWriter(0); type.accept(writer); return node(writer.toByteArray()); });
            refused(() -> bridgeContract(mutant));
            System.out.println("CANONICAL_LEGACY_BRIDGE_MUTATION_REJECTED " + mutation);
        }
        System.out.println("CANONICAL_LEGACY_BRIDGE_CONTRACT_PASS mutations=4; compiled callgraph/assertion inventory only; native bodies NOT_EXECUTED");
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--bridge-contract")) { bridgeContract(Path.of(args[1])); return; }
        String mode = System.getProperty("nclskins.wavey.mode");
        var plugin = new LegacyCapeTerminalPlugin();
        int transformed = 0;
        for (String owner : ImmediateTerminalBindings.owners(mode)) {
            byte[] bytes = find(owner, args);
            ClassNode raw = node(bytes), positive = node(bytes);
            check(plugin.handlesClass(Type.getObjectType(owner), false).equals(EnumSet.of(Phase.BEFORE)), "ONLY_BEFORE");
            check(plugin.handlesClass(Type.getObjectType(owner), true).isEmpty(), "EMPTY_DEFINITION");
            check(plugin.processClass(Phase.BEFORE, positive, Type.getObjectType(owner)), "TERMINAL_NOT_APPLIED");
            transformed++;
            int negatives = 0;
            for (int i = 0; i < raw.methods.size(); i++) {
                ClassNode left = node(bytes), right = node(bytes);
                MethodNode method = left.methods.get(i);
                String descriptor = method.desc; method.desc = "()Ljava/lang/Void;";
                try { ImmediateTerminalBindings.apply(left, mode); } catch (AssertionError expected) {
                    var duplicate = new MethodNode(method.access, method.name, descriptor, null, null);
                    right.methods.get(i).accept(duplicate); right.methods.add(duplicate);
                    refused(() -> ImmediateTerminalBindings.apply(right, mode)); negatives++;
                }
            }
            check(negatives > 0, "MISSING_DESCRIPTOR_NEGATIVE " + owner);
            refused(() -> plugin.processClass(Phase.AFTER, node(bytes), Type.getObjectType(owner)));
        }
        ClassNode unknown = new ClassNode(); unknown.name = "com/naocraftlab/skins/fixture/Unknown";
        check(ImmediateTerminalBindings.apply(unknown, mode) == 0, "WHITELIST_ESCAPE");
        check(plugin.handlesClass(Type.getObjectType(unknown.name), false).isEmpty(), "UNKNOWN_PLUGIN_OWNER");
        ClassNode transformer = node(find("cpw/mods/modlauncher/ClassTransformer", args));
        boolean beforeAfter = false;
        for (MethodNode method : transformer.methods) {
            List<String> phases = new ArrayList<>();
            for (var insn : method.instructions.toArray()) if (insn instanceof FieldInsnNode field && field.owner.endsWith("ILaunchPluginService$Phase")) phases.add(field.name);
            if (phases.contains("BEFORE") && phases.contains("AFTER")) beforeAfter |= phases.indexOf("BEFORE") < phases.indexOf("AFTER");
        }
        check(beforeAfter, "PINNED_MODLAUNCHER_PHASE_ORDER");
        var mixin = new org.spongepowered.asm.service.modlauncher.MixinTransformationHandler();
        check(mixin.handlesClass(Type.getObjectType("net/minecraft/client/renderer/RenderType"), false, "classloading")
                .equals(EnumSet.of(Phase.AFTER)), "PINNED_MIXIN_NOT_AFTER");
        check(!mixin.processClass(Phase.BEFORE, unknown, Type.getObjectType(unknown.name), "classloading"), "MIXIN_BEFORE_TRANSFORM");
        bridgeContract(Path.of(LegacyTerminalContract.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolveSibling("game"));
        List<Path> previewInputs = new ArrayList<>(); previewInputs.add(Path.of(LegacyTerminalContract.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolveSibling("game"));
        previewInputs.addAll(Arrays.stream(args).map(Path::of).toList());
        ImmediatePreviewContract.verify(mode, previewInputs);
        System.out.println("CANONICAL_LEGACY_CONTRACT_PASS owners=" + transformed + "; exact descriptor/count negatives; public ModLauncher BEFORE precedes pinned Mixin AFTER; no loader creation");
    }
}

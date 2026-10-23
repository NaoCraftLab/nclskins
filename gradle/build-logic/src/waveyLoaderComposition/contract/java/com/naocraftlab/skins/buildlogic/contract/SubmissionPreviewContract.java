package com.naocraftlab.skins.buildlogic.contract;

import com.naocraftlab.skins.buildlogic.terminal.SubmissionPreviewBindings;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class SubmissionPreviewContract {
    private static final String PROBE = "com/naocraftlab/skins/buildlogic/probe/wavey/";
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static byte[] bytes(ClassNode owner, MethodNode method) {
        ClassNode single = new ClassNode(); single.version = owner.version; single.access = owner.access;
        single.name = owner.name; single.superName = "java/lang/Object"; single.methods.add(method);
        ClassWriter writer = new ClassWriter(0); single.accept(writer); return writer.toByteArray();
    }
    private static boolean selected(String owner, MethodNode method) {
        if (owner.equals(SubmissionPreviewBindings.P)) return Set.of("previewPlayer", "applySettling").contains(method.name);
        if (owner.equals(SubmissionPreviewBindings.C)) return method.name.equals("setModelParts");
        if (owner.equals(SubmissionPreviewBindings.LIVE)) return method.name.equals("renderToTexture") && method.desc.contains("LivePreviewRenderState;");
        return method.name.equals("finalizeRenderState");
    }
    private static void rejected(ClassNode node) {
        try { SubmissionPreviewBindings.apply(node); throw new IllegalStateException("SUBMISSION_MUTANT_ACCEPTED"); }
        catch (AssertionError expected) { check(expected.getMessage().startsWith("SUBMISSION_DESCRIPTOR_COUNT") || expected.getMessage().startsWith("EXTRACTION_DESCRIPTOR_COUNT"), "SUBMISSION_WRONG_REJECTION"); }
    }
    public static void verify(List<Path> inputs) throws Exception {
        ClassNode entry = TerminalContractProbe.read(PROBE + "BufferedPreviewCapeProbe", inputs);
        check(entry.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray())).anyMatch(i -> i instanceof LdcInsnNode literal
                && (PROBE + "LivePublicPreviewProbe").replace('/', '.').equals(literal.cst)), "PUBLIC_LIVE_NOT_CANONICALLY_REGISTERED");
        ClassNode batch = TerminalContractProbe.read(PROBE + "ClientCapeBatch", inputs);
        check(batch.methods.stream().filter(m -> m.name.equals("nativeEnchantment")).flatMap(m -> Arrays.stream(m.instructions.toArray()))
                .anyMatch(i -> i instanceof LdcInsnNode literal && (PROBE + "NativeElytraConsumptionProbe").replace('/', '.').equals(literal.cst)), "NATIVE_ENCHANTMENT_NOT_CONSUMED");
        int total = 0;
        for (String name : SubmissionPreviewBindings.OWNERS) {
            ClassNode node = TerminalContractProbe.read(name, inputs);
            Map<String, byte[]> preserved = new HashMap<>();
            for (MethodNode method : node.methods) if (!selected(name, method)) preserved.put(method.name + method.desc, bytes(node, method));
            int count = SubmissionPreviewBindings.apply(node); check(count > 0, "SUBMISSION_TERMINAL_MISSING"); total += count;
            for (MethodNode method : node.methods) {
                if (!selected(name, method)) check(Arrays.equals(preserved.get(method.name + method.desc), bytes(node, method)), "SUBMISSION_UNRELATED_BODY_CHANGED");
                for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(PROBE + "SubmissionRawInputs")) {
                    ClassNode helper = TerminalContractProbe.read(call.owner, inputs);
                    check(helper.methods.stream().filter(m -> m.name.equals(call.name) && m.desc.equals(call.desc)).count() == 1, "SUBMISSION_RAW_HELPER_ABI " + call.name + call.desc);
                }
            }
            ClassNode wrong = TerminalContractProbe.read(name, inputs);
            wrong.methods.stream().filter(m -> selected(name, m)).findFirst().orElseThrow().desc = "()Ljava/lang/Object;"; rejected(wrong);
            ClassNode duplicate = TerminalContractProbe.read(name, inputs);
            duplicate.methods.add(duplicate.methods.stream().filter(m -> selected(name, m)).findFirst().orElseThrow()); rejected(duplicate);
        }
        ClassNode unknown = new ClassNode(); unknown.name = "net/minecraft/client/Minecraft";
        check(SubmissionPreviewBindings.apply(unknown) == 0, "SUBMISSION_UNKNOWN_OWNER_CHANGED");
        ClassNode consumer = TerminalContractProbe.read(SubmissionPreviewBindings.LIVE, inputs);
        var render = consumer.methods.stream().filter(m -> selected(consumer.name, m)).findFirst().orElseThrow();
        for (String callName : List.of("extractEntity", "configure", "submit", "renderAllFeatures"))
            check(Arrays.stream(render.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call && call.name.equals(callName)).count() == 1, "LIVE_NATIVE_BOUNDARY_MISSING " + callName);
        System.out.println("PUBLIC_LIVE_EXACT_CONTRACT_PASS raw-operations=" + total + "; wrong/duplicate/unknown/helper ABI/full unrelated method preservation; public live and native enchanted consumer registered; ZERO native body/FML execution");
    }
    public static void main(String[] args) throws Exception { verify(Arrays.stream(args).map(Path::of).toList()); }
}

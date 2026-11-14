package com.naocraftlab.skins.buildlogic;

import com.naocraftlab.skins.buildlogic.terminal.SubmissionPreviewBindings;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class SubmissionFenceFixture {
    private static final String FRAME = SubmissionPreviewBindings.FRAME;
    private static final String LIST = "(Ljava/util/List;)V";
    private static final String SYSTEM = "com/mojang/blaze3d/systems/RenderSystem";
    private static final String RAW = "com/naocraftlab/skins/buildlogic/probe/wavey/SubmissionRawInputs";

    private static MethodNode method(String name, String desc) {
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, name, desc, null, null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }
    private static ClassNode frame() {
        ClassNode node = new ClassNode(); node.version = Opcodes.V21; node.access = Opcodes.ACC_PUBLIC;
        node.name = FRAME; node.superName = "java/lang/Object";
        MethodNode bridge = method("fence", "()V");
        bridge.instructions.insertBefore(bridge.instructions.getLast(), new VarInsnNode(Opcodes.ALOAD, 0));
        bridge.instructions.insertBefore(bridge.instructions.getLast(), new InsnNode(Opcodes.ACONST_NULL));
        bridge.instructions.insertBefore(bridge.instructions.getLast(), new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FRAME, "fence", LIST, false));
        node.methods.add(bridge);
        MethodNode fence = method("fence", LIST);
        fence.instructions.insertBefore(fence.instructions.getLast(), new InsnNode(Opcodes.ACONST_NULL));
        fence.instructions.insertBefore(fence.instructions.getLast(), new MethodInsnNode(Opcodes.INVOKESTATIC, SYSTEM, "queueFencedTask", "(Ljava/lang/Runnable;)V", false));
        node.methods.add(fence);
        node.methods.add(method("close", "()V"));
        node.methods.add(method("fence", "(I)V"));
        return node;
    }
    private static MethodNode fence(ClassNode node) {
        return node.methods.stream().filter(m -> m.name.equals("fence") && m.desc.equals(LIST)).findFirst().orElseThrow();
    }
    private static ClassNode copy(ClassNode node) {
        ClassNode result = new ClassNode(); node.accept(result); return result;
    }
    private static void rejected(String label, String reason, Runnable operation) {
        try { operation.run(); }
        catch (AssertionError failure) {
            if (!failure.getMessage().startsWith(reason)) throw failure;
            System.out.println("REJECTED " + label + " " + failure.getMessage()); return;
        }
        throw new AssertionError("MUTANT_ACCEPTED " + label);
    }
    private static void contract(ClassNode original, ClassNode bound) {
        com.naocraftlab.skins.buildlogic.contract.SubmissionPreviewContract.verifyConsumerFrame(original, bound);
    }
    public static void main(String[] args) throws Exception {
        ClassNode original = frame();
        ClassNode node = copy(original);
        if (SubmissionPreviewBindings.applyNative(node) != 1) throw new AssertionError("EXACT_LIST_BINDING_COUNT");
        long count = java.util.Arrays.stream(fence(node).instructions.toArray()).filter(i -> i instanceof MethodInsnNode call && call.owner.equals(RAW)).count();
        if (count != 1) throw new AssertionError("EXACT_LIST_RAW_COUNT");
        contract(original, node);
        System.out.println("EXACT_LIST_BINDING_AND_REAL_CONTRACT_PASS");
        for (String mutation : java.util.List.of("wrong-descriptor", "duplicate", "missing", "wrong-owner", "wrong-call-descriptor", "wrong-opcode", "wrong-interface")) {
            ClassNode mutant = copy(original);
            MethodNode target = fence(mutant);
            MethodInsnNode hook = (MethodInsnNode) target.instructions.get(target.instructions.size() - 2);
            switch (mutation) {
                case "wrong-descriptor" -> target.desc = "(Ljava/util/Collection;)V";
                case "duplicate" -> mutant.methods.add(target);
                case "missing" -> target.instructions.remove(hook);
                case "wrong-owner" -> hook.owner = RAW;
                case "wrong-call-descriptor" -> hook.desc = "(Ljava/lang/Object;)V";
                case "wrong-opcode" -> hook.setOpcode(Opcodes.INVOKEVIRTUAL);
                case "wrong-interface" -> hook.itf = true;
            }
            rejected("binding-" + mutation, mutation.equals("wrong-descriptor") || mutation.equals("duplicate")
                    ? "SUBMISSION_DESCRIPTOR_COUNT" : "SUBMISSION_FENCE_COUNT", () -> SubmissionPreviewBindings.applyNative(mutant));
        }
        for (String mutation : java.util.List.of("wrong-descriptor", "duplicate", "missing", "duplicate-hook", "wrong-owner", "wrong-call-descriptor", "wrong-opcode", "wrong-interface", "noarg-body", "other-overload", "unrelated-body", "deleted-method", "unexpected-hook", "phantom-old-overload")) {
            ClassNode mutant = copy(node);
            MethodNode target = fence(mutant);
            MethodInsnNode hook = (MethodInsnNode) target.instructions.get(target.instructions.size() - 2);
            String reason = "SUBMISSION_FENCE_UNEXPECTED_HOOK";
            switch (mutation) {
                case "wrong-descriptor" -> { target.desc = "(Ljava/util/Collection;)V"; reason = "SUBMISSION_DESCRIPTOR_COUNT"; }
                case "duplicate" -> { mutant.methods.add(target); reason = "SUBMISSION_DESCRIPTOR_COUNT"; }
                case "missing" -> { target.instructions.remove(hook); reason = "SUBMISSION_FENCE_COUNT"; }
                case "duplicate-hook" -> { target.instructions.insertBefore(hook, new MethodInsnNode(hook.getOpcode(), hook.owner, hook.name, hook.desc, hook.itf)); reason = "SUBMISSION_FENCE_COUNT"; }
                case "wrong-owner" -> hook.owner = SYSTEM;
                case "wrong-call-descriptor" -> hook.desc = "(Ljava/lang/Object;)V";
                case "wrong-opcode" -> hook.setOpcode(Opcodes.INVOKEVIRTUAL);
                case "wrong-interface" -> hook.itf = true;
                case "noarg-body" -> { mutant.methods.getFirst().instructions.insert(new InsnNode(Opcodes.NOP)); reason = "SUBMISSION_FRAME_BODY_CHANGED"; }
                case "other-overload" -> { mutant.methods.getLast().instructions.insert(new InsnNode(Opcodes.NOP)); reason = "SUBMISSION_FRAME_BODY_CHANGED"; }
                case "unrelated-body" -> { mutant.methods.get(2).instructions.insert(new InsnNode(Opcodes.NOP)); reason = "SUBMISSION_FRAME_BODY_CHANGED"; }
                case "deleted-method" -> { mutant.methods.remove(2); reason = "SUBMISSION_FRAME_METHOD_COUNT"; }
                case "unexpected-hook" -> mutant.methods.get(2).instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, RAW, "queueFencedTask", "(Ljava/lang/Runnable;)V", false));
                case "phantom-old-overload" -> { target.instructions.remove(hook); mutant.methods.getFirst().instructions.insert(hook); }
            }
            rejected("contract-" + mutation, reason, () -> contract(original, mutant));
        }
        ClassNode noBridge = copy(node); noBridge.methods.getFirst().instructions.clear();
        rejected("bridge-missing", "SUBMISSION_FENCE_BRIDGE_COUNT", () -> contract(original, noBridge));
        ClassNode unknown = copy(original); unknown.name = "com/naocraftlab/skins/Unknown";
        ClassWriter before = new ClassWriter(0); unknown.accept(before);
        if (SubmissionPreviewBindings.applyNative(unknown) != 0) throw new AssertionError("UNKNOWN_OWNER_BOUND");
        ClassWriter after = new ClassWriter(0); unknown.accept(after);
        if (!java.util.Arrays.equals(before.toByteArray(), after.toByteArray())) throw new AssertionError("UNKNOWN_OWNER_CHANGED");
        System.out.println("SUBMISSION_FENCE_MUTANTS_PASS binding=7 contract=15 unknown-owner=preserved");
    }
}

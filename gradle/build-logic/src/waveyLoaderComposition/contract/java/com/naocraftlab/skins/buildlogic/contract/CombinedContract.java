package com.naocraftlab.skins.buildlogic.contract;

import com.naocraftlab.skins.buildlogic.terminal.CapeTerminalBindings;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class CombinedContract {
    private static final String R = "net/minecraft/client/renderer/rendertype/RenderType";
    private static final String P = "net/minecraft/client/renderer/rendertype/PreparedRenderType";
    private static final String I = "Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;";
    private static final String S = "Lnet/minecraft/client/renderer/oit/OitStage;";
    private static final String PASS = "Lcom/mojang/renderpearl/api/commands/RenderPass;";
    private static ClassNode fixture(String owner) {
        ClassNode node = new ClassNode(); node.name = owner;
        if (owner.equals(R)) {
            node.methods.add(new MethodNode(0, "create", "(Ljava/lang/String;Lnet/minecraft/client/renderer/rendertype/RenderSetup;)L" + R + ";", null, null));
            node.methods.add(new MethodNode(0, "prepare", "()L" + P + ";", null, null));
        } else {
            node.methods.add(new MethodNode(0, "drawFromBuffer", "(" + I + PASS + ")V", null, null));
            node.methods.add(new MethodNode(0, "drawFromBufferOit", "(" + I + S + PASS + ")V", null, null));
        }
        return node;
    }
    private static void rejected(ClassNode node) {
        try { CapeTerminalBindings.apply(node, "combined"); throw new IllegalStateException("COMBINED_MUTANT_ACCEPTED"); }
        catch (AssertionError expected) { if (!expected.getMessage().startsWith("TERMINAL_DESCRIPTOR_COUNT")) throw expected; }
    }
    private static ClassNode copy(ClassNode original) {
        ClassNode node = new ClassNode(); original.accept(node); return node;
    }
    public static void verifyPinned(ClassNode original) {
        if (original.name.equals("dev/tr7zw/waveycapes/render/CapeNodeCollector")) {
            for (boolean duplicate : List.of(false, true)) {
                ClassNode bad = copy(original);
                MethodNode lambda = bad.methods.stream().filter(m -> m.name.equals("lambda$submitCape$0")).findFirst().orElseThrow();
                MethodInsnNode call = Arrays.stream(lambda.instructions.toArray()).filter(i -> i instanceof MethodInsnNode c
                        && c.owner.equals("dev/tr7zw/waveycapes/render/CustomCapeRenderer") && c.name.equals("render"))
                        .map(i -> (MethodInsnNode) i).findFirst().orElseThrow();
                if (duplicate) lambda.instructions.insert(call, new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf));
                else call.desc = "()V";
                try { CapeTerminalBindings.apply(bad, "combined"); throw new IllegalStateException("COMBINED_GEOMETRY_MUTANT_ACCEPTED"); }
                catch (AssertionError expected) { if (!expected.getMessage().startsWith("GEOMETRY_TERMINAL_COUNT")) throw expected; }
            }
            return;
        }
        if (!Set.of(R, P).contains(original.name)) return;
        for (MethodNode method : original.methods) if (Set.of("prepare", "drawFromBuffer", "drawFromBufferOit").contains(method.name)) {
            ClassNode wrong = copy(original);
            wrong.methods.stream().filter(m -> m.name.equals(method.name) && m.desc.equals(method.desc)).findFirst().orElseThrow().desc = "()Ljava/lang/Object;";
            rejected(wrong);
            ClassNode duplicate = copy(original);
            duplicate.methods.add(duplicate.methods.stream().filter(m -> m.name.equals(method.name) && m.desc.equals(method.desc)).findFirst().orElseThrow());
            rejected(duplicate);
        }
    }
    public static void main(String[] args) {
        for (String owner : List.of(R, P)) {
            ClassNode positive = fixture(owner);
            int changed = CapeTerminalBindings.apply(positive, "combined");
            if (changed != (owner.equals(R) ? 1 : 2)) throw new AssertionError("COMBINED_TERMINAL_TOTAL");
            for (MethodNode method : fixture(owner).methods) {
                ClassNode wrong = fixture(owner);
                wrong.methods.stream().filter(m -> m.name.equals(method.name)).findFirst().orElseThrow().desc = "()V";
                rejected(wrong);
                ClassNode duplicate = fixture(owner); duplicate.methods.add(method); rejected(duplicate);
            }
            for (MethodNode method : positive.methods) if (!method.name.equals("create")) {
                var calls = Arrays.stream(method.instructions.toArray()).filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i).toList();
                if (calls.size() != 1 || !calls.getFirst().owner.equals("com/naocraftlab/skins/buildlogic/probe/wavey/CombinedCapeHost"))
                    throw new AssertionError("COMBINED_HOST_TERMINAL");
            }
        }
        ClassNode unknown = new ClassNode(); unknown.name = "net/minecraft/client/Minecraft";
        if (CapeTerminalBindings.apply(unknown, "combined") != 0 || !unknown.methods.isEmpty()) throw new AssertionError("COMBINED_UNKNOWN_CHANGED");
        System.out.println("COMBINED_EXACT_CONTRACT_PASS normal/OIT full descriptors; wrong/duplicate/unknown rejected; ZERO FML");
    }
}

package com.naocraftlab.skins.buildlogic.contract;

import java.util.*;
import java.util.function.Consumer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class PreparedPreviewContract {
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError("PREPARED_PREVIEW_" + reason);
    }
    private static MethodNode exact(ClassNode node, String name, String descriptor) {
        var found = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).toList();
        check(found.size() == 1, "METHOD_COUNT " + name);
        return found.get(0);
    }
    private static List<MethodInsnNode> calls(MethodNode method, String name) {
        return Arrays.stream(method.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call && call.name.equals(name))
                .map(i -> (MethodInsnNode) i).toList();
    }
    private static Set<AbstractInsnNode> reachable(MethodNode method) {
        Set<AbstractInsnNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayDeque<AbstractInsnNode>();
        if (method.instructions.getFirst() != null) pending.add(method.instructions.getFirst());
        while (!pending.isEmpty()) {
            var instruction = pending.remove();
            if (!seen.add(instruction)) continue;
            int opcode = instruction.getOpcode();
            if (instruction instanceof JumpInsnNode jump) {
                check(opcode != Opcodes.JSR, "SUBROUTINE");
                pending.add(jump.label);
            }
            if (instruction instanceof TableSwitchInsnNode table) { pending.add(table.dflt); pending.addAll(table.labels); }
            else if (instruction instanceof LookupSwitchInsnNode lookup) { pending.add(lookup.dflt); pending.addAll(lookup.labels); }
            else if (opcode != Opcodes.GOTO && opcode != Opcodes.ATHROW
                    && !(opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) && instruction.getNext() != null)
                pending.add(instruction.getNext());
            for (var handler : method.tryCatchBlocks) {
                int index = method.instructions.indexOf(instruction);
                if (index >= method.instructions.indexOf(handler.start) && index < method.instructions.indexOf(handler.end)) pending.add(handler.handler);
            }
        }
        return seen;
    }
    public static void verify(ClassNode renderer, String graphics, String terminalOwner, String terminalName,
                              String terminalDescriptor, int terminalOpcode, boolean terminalInterface) {
        String descriptor = "(Lnet/minecraft/client/gui/" + graphics + ";Lcom/naocraftlab/skins/client/PreviewRenderer$PreviewRequest;)V";
        MethodNode entry = exact(renderer, "render", descriptor), prepared = exact(renderer, "renderPrepared", descriptor);
        check((entry.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC)) == Opcodes.ACC_PUBLIC,
                "PUBLIC_ENTRY");
        check((prepared.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC)) == Opcodes.ACC_PRIVATE,
                "PRIVATE_HELPER");
        var bridges = calls(entry, "renderPrepared");
        check(bridges.size() == 2, "BRIDGE_COUNT");
        var entryReachable = reachable(entry);
        for (var call : bridges) check(call.owner.equals(renderer.name) && call.desc.equals(descriptor)
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL && !call.itf && entryReachable.contains(call), "EXACT_REACHABLE_BRIDGE");
        var terminals = calls(prepared, terminalName);
        check(terminals.size() == 1, "TERMINAL_COUNT");
        var terminal = terminals.get(0);
        check(terminal.owner.equals(terminalOwner) && terminal.desc.equals(terminalDescriptor)
                && terminal.getOpcode() == terminalOpcode && terminal.itf == terminalInterface
                && reachable(prepared).contains(terminal), "EXACT_REACHABLE_TERMINAL");
        check(renderer.methods.stream().mapToLong(m -> calls(m, terminalName).size()).sum() == 1, "TERMINAL_ROSTER");
    }
    private static ClassNode copy(ClassNode original) {
        ClassNode copy = new ClassNode(); original.accept(copy); return copy;
    }
    public static void verifyMutations(ClassNode original, String graphics, String owner, String name,
                                       String descriptor, int opcode, boolean itf) {
        verify(original, graphics, owner, name, descriptor, opcode, itf);
        String renderDescriptor = "(Lnet/minecraft/client/gui/" + graphics + ";Lcom/naocraftlab/skins/client/PreviewRenderer$PreviewRequest;)V";
        Map<String, Consumer<ClassNode>> mutations = new LinkedHashMap<>();
        mutations.put("entry-descriptor", n -> exact(n, "render", renderDescriptor).desc = "()V");
        mutations.put("entry-access", n -> exact(n, "render", renderDescriptor).access = Opcodes.ACC_PRIVATE);
        mutations.put("missing-helper", n -> n.methods.remove(exact(n, "renderPrepared", renderDescriptor)));
        mutations.put("duplicate-helper", n -> n.methods.add(exact(n, "renderPrepared", renderDescriptor)));
        mutations.put("helper-descriptor", n -> exact(n, "renderPrepared", renderDescriptor).desc = "()V");
        mutations.put("helper-access", n -> exact(n, "renderPrepared", renderDescriptor).access = Opcodes.ACC_PUBLIC);
        mutations.put("missing-bridge", n -> { var m = exact(n, "render", renderDescriptor); m.instructions.remove(calls(m, "renderPrepared").get(0)); });
        mutations.put("duplicate-bridge", n -> { var m = exact(n, "render", renderDescriptor); m.instructions.add(calls(m, "renderPrepared").get(0).clone(new HashMap<>())); });
        mutations.put("bridge-owner", n -> calls(exact(n, "render", renderDescriptor), "renderPrepared").get(0).owner = "com/naocraftlab/skins/fixture/Foreign");
        mutations.put("bridge-descriptor", n -> calls(exact(n, "render", renderDescriptor), "renderPrepared").get(0).desc = "()V");
        mutations.put("bridge-opcode", n -> calls(exact(n, "render", renderDescriptor), "renderPrepared").get(0).setOpcode(Opcodes.INVOKESTATIC));
        mutations.put("bridge-interface", n -> calls(exact(n, "render", renderDescriptor), "renderPrepared").get(0).itf = true);
        mutations.put("dead-helper", n -> exact(n, "render", renderDescriptor).instructions.insert(new InsnNode(Opcodes.RETURN)));
        mutations.put("missing-terminal", n -> { var m = exact(n, "renderPrepared", renderDescriptor); m.instructions.remove(calls(m, name).get(0)); });
        mutations.put("duplicate-terminal", n -> { var m = exact(n, "renderPrepared", renderDescriptor); m.instructions.add(calls(m, name).get(0).clone(new HashMap<>())); });
        mutations.put("terminal-owner", n -> calls(exact(n, "renderPrepared", renderDescriptor), name).get(0).owner = "com/naocraftlab/skins/fixture/Foreign");
        mutations.put("terminal-descriptor", n -> calls(exact(n, "renderPrepared", renderDescriptor), name).get(0).desc = "()V");
        mutations.put("terminal-opcode", n -> calls(exact(n, "renderPrepared", renderDescriptor), name).get(0).setOpcode(Opcodes.INVOKESPECIAL));
        mutations.put("terminal-interface", n -> calls(exact(n, "renderPrepared", renderDescriptor), name).get(0).itf = !itf);
        mutations.put("dead-terminal", n -> exact(n, "renderPrepared", renderDescriptor).instructions.insert(new InsnNode(Opcodes.RETURN)));
        mutations.put("unrelated-noarg-terminal", n -> {
            var m = exact(n, "renderPrepared", renderDescriptor);
            var call = calls(m, name).get(0); m.instructions.remove(call);
            var decoy = new MethodNode(Opcodes.ACC_PRIVATE, "render", "()V", null, null);
            decoy.instructions.add(call); decoy.instructions.add(new InsnNode(Opcodes.RETURN)); n.methods.add(decoy);
        });
        for (var mutation : mutations.entrySet()) {
            ClassNode mutant = copy(original); mutation.getValue().accept(mutant);
            try {
                verify(mutant, graphics, owner, name, descriptor, opcode, itf);
                throw new IllegalStateException("PREPARED_PREVIEW_MUTANT_ACCEPTED " + mutation.getKey());
            } catch (AssertionError expected) {
                if (!expected.getMessage().startsWith("PREPARED_PREVIEW_")) throw expected;
            }
        }
        System.out.println("PREPARED_PREVIEW_CONTRACT_PASS " + original.name + " terminal=" + name
                + "; exact public/private descriptors, reachable virtual bridges=2, terminal=1; mutants=" + mutations.size() + "; ZERO native execution");
    }
}

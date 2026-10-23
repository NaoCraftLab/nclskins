package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesPassAbiTest {
    @Test
    void nonemptyPassesBypassLegacyAndBothBranchOrientationsAreSupported() {
        WaveyCapesPassAbi.verifyShortCircuit(dispatch(false))
        WaveyCapesPassAbi.verifyShortCircuit(dispatch(true))
    }

    @Test
    void invertedEmptyGuardAndFallthroughCannotReachLegacyFoil() {
        MethodNode inverted = dispatch(false)
        JumpInsnNode branch = inverted.instructions.toArray().find { it instanceof JumpInsnNode }
        branch.opcode = Opcodes.IFEQ
        assertThrows(IllegalStateException) { WaveyCapesPassAbi.verifyShortCircuit(inverted) }
        MethodNode fallthrough = dispatch(false)
        AbstractInsnNode firstReturn = fallthrough.instructions.toArray().find { it.opcode == Opcodes.RETURN }
        fallthrough.instructions.remove(firstReturn)
        assertThrows(IllegalStateException) { WaveyCapesPassAbi.verifyShortCircuit(fallthrough) }
    }

    @Test
    void changedPublicDescriptorOrDuplicateLegacyDispatchIsRejected() {
        MethodNode descriptor = dispatch(false)
        MethodInsnNode pass = descriptor.instructions.toArray().find {
            it instanceof MethodInsnNode && it.name == 'getCapePasses'
        }
        pass.desc = '(Ljava/lang/Object;)Ljava/util/List;'
        assertThrows(IllegalStateException) { WaveyCapesPassAbi.verifyShortCircuit(descriptor) }
        MethodNode duplicate = dispatch(false)
        MethodInsnNode legacy = duplicate.instructions.toArray().find {
            it instanceof MethodInsnNode && it.name == 'getCapeInfo'
        }
        duplicate.instructions.insert(legacy, new MethodInsnNode(legacy.opcode, legacy.owner,
                legacy.name, legacy.desc, legacy.itf))
        assertThrows(IllegalStateException) { WaveyCapesPassAbi.verifyShortCircuit(duplicate) }
    }

    private static MethodNode dispatch(boolean opposite) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, 'submitCape',
                '(L' + WaveyCapesPassAbi.RENDERER + ';L' + WaveyCapesPassAbi.WRAPPER + ';)V', null, null)
        String actor = '(L' + WaveyCapesPassAbi.WRAPPER + ';)'
        LabelNode legacy = new LabelNode(), passes = new LabelNode()
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0))
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1))
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, WaveyCapesPassAbi.RENDERER,
                'getCapePasses', actor + 'Ljava/util/List;', true))
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, 'java/util/List', 'isEmpty', '()Z', true))
        method.instructions.add(new JumpInsnNode(opposite ? Opcodes.IFEQ : Opcodes.IFNE, opposite ? passes : legacy))
        if (!opposite) method.instructions.add(new InsnNode(Opcodes.RETURN))
        method.instructions.add(legacy)
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0))
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1))
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, WaveyCapesPassAbi.RENDERER,
                'getCapeInfo', actor + 'L' + WaveyCapesPassAbi.INFOS + ';', true))
        method.instructions.add(new InsnNode(Opcodes.POP))
        method.instructions.add(new InsnNode(Opcodes.RETURN))
        if (opposite) {
            method.instructions.add(passes)
            method.instructions.add(new InsnNode(Opcodes.RETURN))
        }
        method.maxStack = 2
        method.maxLocals = 2
        method
    }
}

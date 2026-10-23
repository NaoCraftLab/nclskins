package com.naocraftlab.skins.buildlogic

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

final class WaveyCapesPassAbi {
    static final String SUPPORT = 'dev/tr7zw/waveycapes/support/ModSupport'
    static final String RENDERER = 'dev/tr7zw/waveycapes/render/CapeRenderer'
    static final String PASS = 'dev/tr7zw/waveycapes/render/CapePass'
    static final String INFOS = 'dev/tr7zw/waveycapes/render/CapeInfos'
    static final String COLLECTOR = 'dev/tr7zw/waveycapes/render/CapeNodeCollector'
    static final String MANAGER = 'dev/tr7zw/waveycapes/support/SupportManager'
    static final String WRAPPER = 'dev/tr7zw/transition/mc/entitywrapper/PlayerWrapper'

    static void verify(Map<String, byte[]> classes, String renderType) {
        Map<String, ClassNode> nodes = classes.collectEntries { String name, byte[] bytes ->
            ClassNode node = new ClassNode()
            new ClassReader(bytes).accept(node, 0)
            [(name): node]
        }
        String actor = '(L' + WRAPPER + ';)'
        require(nodes, SUPPORT, 'shouldBeUsed', actor + 'Z')
        require(nodes, SUPPORT, 'getRenderer', '()L' + RENDERER + ';')
        require(nodes, SUPPORT, 'priority', '()I')
        require(nodes, SUPPORT, 'blockFeatureRenderer', '(Ljava/lang/Object;)Z')
        require(nodes, RENDERER, 'getCapePasses', actor + 'Ljava/util/List;')
        require(nodes, RENDERER, 'getCapeInfo', actor + 'L' + INFOS + ';')
        require(nodes, PASS, '<init>', '(L' + renderType + ';Ljava/util/function/UnaryOperator;)V')
        require(nodes, PASS, 'renderType', '()L' + renderType + ';')
        require(nodes, PASS, 'wrapper', '()Ljava/util/function/UnaryOperator;')
        require(nodes, INFOS, '<init>', '(L' + RENDERER + ';L' + renderType + ';Z)V')
        require(nodes, INFOS, 'isGlint', '()Z')
        require(nodes, MANAGER, 'getSupportedMods', '()Ljava/util/Set;')
        if (integerReturn(require(nodes, RENDERER, 'uvSheetWidth', '()I')) != 64
                || integerReturn(require(nodes, RENDERER, 'uvSheetHeight', '()I')) != 32) {
            fail('Logical cape sheet changed')
        }
        ClassNode wrapper = nodes[WRAPPER]
        if (wrapper == null || !wrapper.methods.any { it.name == 'getEntity' }
                || !wrapper.methods.any { it.name == 'getCapeTexture' && it.desc.startsWith('()Lnet/minecraft/') }) {
            fail('Typed wrapper actor/texture ABI changed')
        }
        MethodNode submit = nodes[COLLECTOR]?.methods?.find { it.name == 'submitCape' }
        if (submit == null) fail('Missing public cape collector')
        verifyShortCircuit(submit)
        List<MethodInsnNode> collectorCalls = nodes[COLLECTOR].methods.collectMany { method ->
            method.instructions.toArray().findAll { it instanceof MethodInsnNode } as List<MethodInsnNode>
        }
        if (!collectorCalls.any { it.owner == PASS && it.name == 'wrapper'
                && it.desc == '()Ljava/util/function/UnaryOperator;' }
                || !collectorCalls.any { it.owner == 'java/util/function/UnaryOperator' && it.name == 'apply'
                && it.desc == '(Ljava/lang/Object;)Ljava/lang/Object;' }) {
            fail('Host-owned public pass consumer wrapper changed')
        }
        MethodNode init = nodes[MANAGER]?.methods?.find { it.name == '<clinit>' }
        List<Handle> handles = init?.instructions?.toArray()?.findAll {
            it instanceof InvokeDynamicInsnNode
        }?.collectMany { ((InvokeDynamicInsnNode) it).bsmArgs.findAll { it instanceof Handle } as List<Handle> } ?: []
        List<String> comparatorCalls = init?.instructions?.toArray()?.findAll {
            it instanceof MethodInsnNode && it.owner == 'java/util/Comparator'
        }?.collect { ((MethodInsnNode) it).name } ?: []
        if (!handles.any { it.owner == SUPPORT && it.name == 'priority' }
                || !comparatorCalls.containsAll(['comparingInt', 'reversed', 'thenComparing', 'thenComparingInt'])) {
            fail('Scoped support priority ordering changed')
        }
    }

    static void verifyGeometryDispatch(byte[] bytes) {
        ClassNode node = new ClassNode()
        new ClassReader(bytes).accept(node, 0)
        MethodNode method = node.methods.find { it.name == 'render' }
        if (method == null) fail('Missing genuine cape geometry dispatcher')
        verifyGeometryDispatch(method)
    }

    static void verifyGeometryDispatch(MethodNode method) {
        List<AbstractInsnNode> code = method.instructions.toArray().toList()
        List<Integer> smooth = (0..<code.size()).findAll { int index ->
            def instruction = code[index]
            instruction instanceof MethodInsnNode && instruction.name == 'renderSmoothCape'
        }
        List<Integer> blocky = (0..<code.size()).findAll { int index ->
            def instruction = code[index]
            instruction instanceof MethodInsnNode && instruction.owner == RENDERER && instruction.name == 'render'
        }
        List<Integer> uv = indices(code, RENDERER, 'vanillaUvValues', '()Z')
        List<Integer> style = (0..<code.size()).findAll { int index ->
            def instruction = code[index]
            instruction instanceof FieldInsnNode && instruction.owner == 'dev/tr7zw/waveycapes/versionless/CapeStyle'
                    && instruction.name == 'SMOOTH' && instruction.opcode == Opcodes.GETSTATIC
        }
        if ([smooth, blocky, uv, style].any { it.size() != 1 }) fail('SMOOTH/BLOCKY dispatcher ABI changed')
        int branchIndex = nextInstruction(code, style.first() + 1)
        if (!(code[branchIndex] instanceof JumpInsnNode)
                || !(code[branchIndex].opcode in [Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE])) fail('Cape style discriminator changed')
        JumpInsnNode branch = (JumpInsnNode) code[branchIndex]
        int smoothPath = branch.opcode == Opcodes.IF_ACMPNE ? branchIndex + 1 : code.indexOf(branch.label)
        int blockyPath = branch.opcode == Opcodes.IF_ACMPNE ? code.indexOf(branch.label) : branchIndex + 1
        int uvBranchIndex = nextInstruction(code, uv.first() + 1)
        if (!(code[uvBranchIndex] instanceof JumpInsnNode)
                || !(code[uvBranchIndex].opcode in [Opcodes.IFEQ, Opcodes.IFNE])) fail('Logical UV geometry guard changed')
        JumpInsnNode uvBranch = (JumpInsnNode) code[uvBranchIndex]
        int uvTrue = uvBranch.opcode == Opcodes.IFEQ ? uvBranchIndex + 1 : code.indexOf(uvBranch.label)
        int uvFalse = uvBranch.opcode == Opcodes.IFEQ ? code.indexOf(uvBranch.label) : uvBranchIndex + 1
        if (!reachable(code, smoothPath, uv.first()) || reachable(code, blockyPath, smooth.first())
                || !reachable(code, blockyPath, blocky.first()) || !reachable(code, uvTrue, smooth.first())
                || reachable(code, uvTrue, blocky.first()) || reachable(code, uvFalse, smooth.first())
                || !reachable(code, uvFalse, blocky.first())) fail('SMOOTH/BLOCKY logical-UV branch semantics changed')
    }

    private static int nextInstruction(List<AbstractInsnNode> code, int index) {
        while (index < code.size() && code[index].opcode < 0) index++
        if (index >= code.size()) fail('Missing geometry branch')
        index
    }

    static void verifyShortCircuit(MethodNode method) {
        List<AbstractInsnNode> code = method.instructions.toArray().toList()
        List<Integer> pass = indices(code, RENDERER, 'getCapePasses', '(L' + WRAPPER + ';)Ljava/util/List;')
        List<Integer> empty = indices(code, 'java/util/List', 'isEmpty', '()Z')
        List<Integer> legacy = indices(code, RENDERER, 'getCapeInfo', '(L' + WRAPPER + ';)L' + INFOS + ';')
        if (pass.size() != 1 || empty.size() != 1 || legacy.size() != 1 || pass.first() >= empty.first()) {
            fail('Public/legacy collector dispatch changed')
        }
        int branchIndex = empty.first() + 1
        while (branchIndex < code.size() && code[branchIndex].opcode < 0) branchIndex++
        if (!(code[branchIndex] instanceof JumpInsnNode)
                || !(code[branchIndex].opcode in [Opcodes.IFEQ, Opcodes.IFNE])) {
            fail('Pass-list empty condition changed')
        }
        JumpInsnNode branch = (JumpInsnNode) code[branchIndex]
        int target = code.indexOf(branch.label)
        int nonempty = branch.opcode == Opcodes.IFEQ ? target : branchIndex + 1
        int emptyPath = branch.opcode == Opcodes.IFEQ ? branchIndex + 1 : target
        if (reachable(code, nonempty, legacy.first()) || !reachable(code, emptyPath, legacy.first())) {
            fail('Nonempty public passes no longer bypass legacy foil')
        }
    }

    private static boolean reachable(List<AbstractInsnNode> code, int start, int target) {
        Set<Integer> seen = [] as Set
        ArrayDeque<Integer> pending = new ArrayDeque<>()
        pending.add(start)
        while (!pending.empty) {
            int index = pending.removeFirst()
            if (index == target) return true
            if (index < 0 || index >= code.size() || !seen.add(index)) continue
            AbstractInsnNode node = code[index]
            if (node.opcode in [Opcodes.RETURN, Opcodes.ARETURN, Opcodes.IRETURN, Opcodes.ATHROW]) continue
            if (node instanceof JumpInsnNode) {
                pending.add(code.indexOf(((JumpInsnNode) node).label))
                if (node.opcode != Opcodes.GOTO) pending.add(index + 1)
            } else if (node instanceof TableSwitchInsnNode || node instanceof LookupSwitchInsnNode) {
                pending.add(code.indexOf(node.dflt))
                node.labels.each { pending.add(code.indexOf(it)) }
            } else pending.add(index + 1)
        }
        false
    }

    private static List<Integer> indices(List<AbstractInsnNode> code, String owner, String name, String desc) {
        (0..<code.size()).findAll { int index ->
            AbstractInsnNode node = code[index]
            node instanceof MethodInsnNode && node.owner == owner && node.name == name && node.desc == desc
        }
    }

    private static MethodNode require(Map<String, ClassNode> nodes, String owner, String name, String desc) {
        List<MethodNode> matches = nodes[owner]?.methods?.findAll { it.name == name && it.desc == desc } ?: []
        if (matches.size() != 1) fail('Public pass method ABI changed: ' + owner + '.' + name + desc)
        matches.first()
    }

    private static int integerReturn(MethodNode method) {
        List<AbstractInsnNode> code = method.instructions.toArray().findAll { it.opcode >= 0 }
        if (code.size() != 2 || code.last().opcode != Opcodes.IRETURN) return -1
        AbstractInsnNode first = code.first()
        first instanceof IntInsnNode ? first.operand : first.opcode - Opcodes.ICONST_0
    }

    private static void fail(String detail) { throw new IllegalStateException('WaveyCapes ' + detail) }
}

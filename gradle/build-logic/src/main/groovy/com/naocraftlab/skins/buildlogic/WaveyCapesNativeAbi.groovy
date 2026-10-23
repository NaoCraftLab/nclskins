package com.naocraftlab.skins.buildlogic

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.util.zip.ZipFile

final class WaveyCapesNativeAbi {
    static void verify(Collection<File> classpath, String implementation) {
        boolean immediate = implementation in ['player-info-cache', 'skin-lookup']
        boolean prepared = implementation in ['cape-foil-pipeline-bind-groups', 'cape-foil-combined']
        List<String> mixins
        String base = 'com/naocraftlab/skins/compat/client/'
        if (immediate) mixins = [base + 'cape/mixin/RenderTypeFlagsMixin']
        else {
            String family = prepared ? 'prepared' : 'buffered'
            mixins = ['CapturedRenderTypeMixin', 'CapturedGeometryMixin', 'CapturedDrawMixin'].collect {
                base + 'identifier/foil/' + family + '/' + it
            }
            if (prepared) mixins += ['CapturedPreparedTypeMixin', 'CapturedDrawGroupMixin'].collect {
                base + 'identifier/foil/prepared/' + it
            }
            if (implementation != 'cape-foil-combined') mixins += base + 'identifier/foil/pipeline/OrderedCapeFoilMixin'
        }
        if (!immediate) {
            if (prepared) verifyPreparedDraw(
                    read(classpath, 'net/minecraft/client/renderer/feature/RenderTypeFeatureRenderer'),
                    read(classpath, base + 'identifier/foil/prepared/CapturedDrawMixin'),
                    implementation == 'cape-foil-combined')
            String nativeName = 'net/minecraft/client/renderer/rendertype/RenderType'
            ClassNode nativeType = read(classpath, nativeName)
            MethodNode factory = nativeType.methods.find { it.name == 'create'
                    && it.desc == '(Ljava/lang/String;Lnet/minecraft/client/renderer/rendertype/RenderSetup;)L' + nativeName + ';' }
            if (factory == null || factory.instructions.toArray().count {
                it instanceof TypeInsnNode && it.opcode == Opcodes.NEW && it.desc == nativeName
            } != 1 || factory.instructions.toArray().count { it.opcode == Opcodes.ARETURN } != 1)
                fail('Captured native factory no longer creates a fresh instance')
            if (prepared) {
                MethodNode prepare = nativeType.methods.find { it.name == 'prepare'
                        && it.desc == '()Lnet/minecraft/client/renderer/rendertype/PreparedRenderType;' }
                if (prepare == null || prepare.instructions.toArray().count {
                    it instanceof TypeInsnNode && it.opcode == Opcodes.NEW
                            && it.desc == 'net/minecraft/client/renderer/rendertype/PreparedRenderType'
                } != 1) fail('Prepared native carrier could alias a cached/shared record')
            }
        }
        int anchors = 0, accessors = 0
        mixins.each { String name ->
            ClassNode mixin = read(classpath, name)
            AnnotationNode annotation = annotations(mixin).find { it.desc == 'Lorg/spongepowered/asm/mixin/Mixin;' }
            Map mixinValues = values(annotation)
            List<String> targets = []
            (mixinValues.value ?: []).each { targets.add(((Type) it).internalName) }
            (mixinValues.targets ?: []).each { targets.add(it.toString().replace('.', '/')) }
            if (targets.size() != 1) fail('Expected one exact native target for ' + name)
            ClassNode target = read(classpath, targets.first())
            mixin.methods.each { MethodNode method ->
                annotations(method).each { AnnotationNode hook ->
                    Map spec = values(hook)
                    if (hook.desc == 'Lorg/spongepowered/asm/mixin/gen/Accessor;') {
                        String fieldName = spec.value
                        String descriptor = Type.getReturnType(method.desc).descriptor
                        if (target.fields.count { it.name == fieldName && it.desc == descriptor } != 1)
                            fail('Exact carrier field changed: ' + target.name + '.' + fieldName)
                        accessors++
                    }
                    if (!(hook.desc in ['Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;',
                                        'Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;'])) return
                    if (spec.require != 1 || spec.expect != 1 || spec.allow != 1) fail('Non-strict cape anchor: ' + name)
                    List<String> methods = spec.method instanceof List ? spec.method : [spec.method]
                    if (methods.size() != 1) fail('Cape hook must select one exact method')
                    String selector = methods.first()
                    int descriptorStart = selector.indexOf('(')
                    if (descriptorStart < 1) fail('Non-exact cape method selector')
                    List<MethodNode> nativeMethods = target.methods.findAll {
                        it.name == selector.substring(0, descriptorStart) && it.desc == selector.substring(descriptorStart)
                    }
                    if (nativeMethods.size() != 1) fail('Native cape method changed: ' + target.name + '.' + selector)
                    Object rawAt = spec.at
                    List<AnnotationNode> at = rawAt instanceof List ? rawAt : [rawAt]
                    if (at.size() != 1) fail('Expected one cape operation anchor')
                    Map point = values(at.first())
                    int count
                    if (point.value == 'RETURN') {
                        count = nativeMethods.first().instructions.toArray().count { it.opcode == Opcodes.ARETURN }
                    } else {
                        String call = point.target
                        if (point.value != 'INVOKE' || !call?.startsWith('L')) fail('Unsupported cape anchor')
                        int separator = call.indexOf(';'), signature = call.indexOf('(', separator)
                        String owner = call.substring(1, separator), called = call.substring(separator + 1, signature)
                        String desc = call.substring(signature)
                        count = nativeMethods.first().instructions.toArray().count {
                            it instanceof MethodInsnNode && it.owner == owner && it.name == called && it.desc == desc
                        }
                    }
                    if (count != 1) fail('Cape operation count changed: ' + target.name + '.' + selector + ' count=' + count)
                    anchors++
                }
            }
        }
        if (immediate) {
            ClassNode nativeType = read(classpath, 'net/minecraft/client/renderer/RenderType')
            String method = implementation == 'player-info-cache' ? 'end' : 'draw'
            String desc = implementation == 'player-info-cache'
                    ? '(Lcom/mojang/blaze3d/vertex/BufferBuilder;Lcom/mojang/blaze3d/vertex/VertexSorting;)V'
                    : '(Lcom/mojang/blaze3d/vertex/MeshData;)V'
            if (nativeType.methods.count { it.name == method && it.desc == desc && !(it.access & Opcodes.ACC_FINAL) } != 1)
                fail('Immediate material forwarding ABI changed')
        } else if (prepared) {
            ClassNode staging = read(classpath, 'net/minecraft/client/renderer/StagedVertexBuffer')
            MethodNode acquire = staging.methods.find { it.name == 'getVertexBuilder' }
            List<AbstractInsnNode> code = acquire?.instructions?.toArray()?.toList() ?: []
            if (!code.any { it instanceof MethodInsnNode && it.owner == 'java/util/Objects' && it.name == 'requireNonNull' }
                    || !code.any { it instanceof TypeInsnNode && it.opcode == Opcodes.NEW
                    && it.desc == 'com/mojang/blaze3d/vertex/BufferBuilder' }
                    || code.count { it.opcode == Opcodes.ARETURN } != 2) {
                fail('Prepared native non-null acquisition invariant changed')
            }
            MethodNode build = read(classpath, 'net/minecraft/client/renderer/feature/CustomFeatureRenderer')
                    .methods.find { it.name == 'buildGroup' }
            List<AbstractInsnNode> instructions = build.instructions.toArray().toList()
            int acquireIndex = instructions.findIndexOf { it instanceof MethodInsnNode && it.name == 'getVertexBuilder' }
            int emitIndex = instructions.findIndexOf { it instanceof MethodInsnNode &&
                    it.owner == 'net/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer' && it.name == 'render' }
            if (acquireIndex < 0 || emitIndex <= acquireIndex || instructions.subList(acquireIndex + 1, emitIndex).any {
                it instanceof MethodInsnNode && it.owner == 'com/mojang/blaze3d/vertex/VertexConsumer'
            }) fail('Skipped acquisition reaches intermediate native consumer use')
        } else {
            MethodNode acquire = read(classpath, 'net/minecraft/client/renderer/MultiBufferSource$BufferSource')
                    .methods.find { it.name == 'getBuffer' }
            List<AbstractInsnNode> code = acquire.instructions.toArray().toList()
            if (code.count { it instanceof TypeInsnNode && it.opcode == Opcodes.NEW
                    && it.desc == 'com/mojang/blaze3d/vertex/BufferBuilder' } != 2
                    || code.count { it.opcode == Opcodes.ARETURN } != 2
                    || code.count { it.opcode in [Opcodes.IFNULL, Opcodes.IFNONNULL] } < 2) {
                fail('Buffered native non-null acquisition invariant changed')
            }
            ClassNode renderer = read(classpath, 'net/minecraft/client/renderer/feature/CustomFeatureRenderer')
            List<String> geometryMethods = implementation == 'cape-foil-pipeline-samplers'
                    ? ['render'] : ['renderSolid', 'renderTranslucent']
            geometryMethods.each { String name ->
                MethodNode method = renderer.methods.find { it.name == name && it.instructions.toArray().any {
                    it instanceof MethodInsnNode && it.name == 'getBuffer'
                } }
                List<AbstractInsnNode> operations = method?.instructions?.toArray()?.toList() ?: []
                int start = operations.findIndexOf { it instanceof MethodInsnNode && it.name == 'getBuffer' }
                int end = operations.findIndexOf { it instanceof MethodInsnNode &&
                        it.owner == 'net/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer' && it.name == 'render' }
                if (start < 0 || end <= start || operations.subList(start + 1, end).any {
                    it instanceof MethodInsnNode && it.owner == 'com/mojang/blaze3d/vertex/VertexConsumer'
                }) fail('Buffered skipped acquisition reaches an intermediate consumer operation')
            }
        }
        println 'WaveyCapes native exact bindings passed: ' + implementation + ' anchors=' + anchors + ' accessors=' + accessors
    }

    static void verifyPreparedDraw(ClassNode renderer, ClassNode mixin, boolean renderPass) {
        String info = 'Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;'
        String stage = 'Lnet/minecraft/client/renderer/oit/OitStage;'
        String pass = 'Lcom/mojang/renderpearl/api/commands/RenderPass;'
        String selector = 'executeGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;' +
                (renderPass ? stage + pass : '') + 'ILjava/util/List;Z)V'
        Map<String, String> expected = ['drawFromBuffer': '(' + info + (renderPass ? pass : '') + ')V']
        if (renderPass) expected.drawFromBufferOit = '(' + info + stage + pass + ')V'
        List<MethodNode> selected = renderer.methods.findAll { it.name + it.desc == selector }
        if (selected.size() != 1) fail('Prepared draw selector changed: ' + selector)
        String owner = 'net/minecraft/client/renderer/rendertype/PreparedRenderType'
        Set<String> required = expected.collect { name, desc -> 'L' + owner + ';' + name + desc } as Set
        List<Map> hooks = mixin.methods.collectMany { method ->
            annotations(method).findAll {
                it.desc == 'Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;'
            }.collect { values(it) }
        }
        Set<String> actual = [] as Set
        hooks.each { Map hook ->
            Object at = hook.at instanceof List && hook.at.size() == 1 ? hook.at.first() : hook.at
            Map point = at instanceof AnnotationNode ? values(at) : [:]
            if (hook.method != [selector] || hook.require != 1 || hook.expect != 1 || hook.allow != 1
                    || point.value != 'INVOKE' || point.containsKey('ordinal'))
                fail('Prepared draw hook is not an exact strict operation')
            actual.add(point.target?.toString())
        }
        if (hooks.size() != required.size() || actual != required)
            fail('Prepared draw branches are not completely guarded')
        expected.each { name, desc ->
            if (selected.first().instructions.toArray().count {
                it instanceof MethodInsnNode && it.owner == owner && it.name == name && it.desc == desc
            } != 1) fail('Prepared draw operation changed: ' + name + desc)
        }
    }

    static void verifyResourcePort(Collection<File> classpath, String implementation) {
        String leaf = implementation == 'player-info-cache'
                ? 'resourcelocation/playerinfo/PlayerInfoAppearanceCapability'
                : implementation == 'skin-lookup' ? 'resourcelocation/skinlookup/SkinLookupAppearanceCapability'
                : 'identifier/IdentifierAppearanceSink'
        ClassNode capability = read(classpath, 'com/naocraftlab/skins/compat/client/' + leaf)
        String registryName = Type.getType(capability.fields.find { it.name == 'textures' }.desc).internalName
        verifyCapturePort(capability, read(classpath, registryName),
                read(classpath, 'com/naocraftlab/skins/compat/waveycapes/CapeRenderThread'))
        println 'WaveyCapes exact current-handle/client-thread port passed: ' + capability.name
    }

    static void verifyCapturePort(ClassNode capability, ClassNode registry, ClassNode threadGuard) {
        String validity = 'Lcom/naocraftlab/skins/client/TextureUseValidity;'
        String source = 'Lcom/naocraftlab/skins/client/PlayerAppearanceSink$CapeSource;'
        MethodNode capture = capability.methods.find { it.name == 'captureCapeTexture'
                && it.desc == '(Ljava/util/UUID;' + source + 'Ljava/lang/String;)' + validity }
        List<AbstractInsnNode> code = capture?.instructions?.toArray()?.toList() ?: []
        List<MethodInsnNode> calls = code.findAll { it instanceof MethodInsnNode }
        String key = capability.name + '$ProviderCape'
        int thread = code.findIndexOf { it instanceof MethodInsnNode && it.name == 'checkClientThread' }
        int ownerMap = code.findIndexOf { it instanceof FieldInsnNode && it.owner == capability.name
                && it.name == 'providerCapes' && it.desc == 'Ljava/util/Map;' }
        if (thread < 0 || ownerMap <= thread
                || calls.count { it.owner == key && it.name == '<init>' && it.desc == '(Ljava/util/UUID;' + source + ')V' } != 1
                || calls.count { it.owner == 'java/util/Map' && it.name == 'get' } != 1
                || calls.count { it.owner == 'java/lang/String' && it.name == 'equals' } != 1
                || calls.count { it.owner == registry.name && it.name == 'capture'
                        && it.desc == '(Lcom/naocraftlab/skins/client/TextureRegistry$TextureHandle;)' + validity } != 1
                || !code.any { it instanceof FieldInsnNode && it.name == 'INVALID'
                        && it.owner == 'com/naocraftlab/skins/client/TextureUseValidity' }
                || calls.any { !(it.name in ['getInstance', 'checkClientThread', '<init>', 'get', 'location', 'equals', 'capture']) })
            fail('Native cape capture is not an exact read-only current-handle lookup')
        List<AbstractInsnNode> real = code.findAll { it.opcode >= 0 }
        int keyConstructor = real.findIndexOf { it instanceof MethodInsnNode && it.owner == key && it.name == '<init>' }
        int mapLookup = real.findIndexOf { it instanceof MethodInsnNode && it.owner == 'java/util/Map' && it.name == 'get' }
        int comparison = real.findIndexOf { it instanceof MethodInsnNode && it.owner == 'java/lang/String' && it.name == 'equals' }
        int use = real.findIndexOf { it instanceof MethodInsnNode && it.name == 'capture' }
        boolean scoped = keyConstructor >= 2 && mapLookup >= 0 && mapLookup + 2 < real.size()
                && comparison >= 3 && use >= 2
        int handle = scoped && real[mapLookup + 2] instanceof VarInsnNode ? real[mapLookup + 2].var : -1
        if (!scoped || !(real[mapLookup + 1] instanceof TypeInsnNode)
                || real[mapLookup + 1].opcode != Opcodes.CHECKCAST
                || real[mapLookup + 2].opcode != Opcodes.ASTORE
                || !load(real[keyConstructor - 2], 1) || !load(real[keyConstructor - 1], 2)
                || code.any { it instanceof VarInsnNode && it.opcode == Opcodes.ASTORE && it.var in [1, 2, 3] }
                || code.count { it instanceof VarInsnNode && it.opcode == Opcodes.ASTORE && it.var == handle } != 1
                || !load(real[comparison - 3], 3) || !load(real[comparison - 2], handle)
                || !(real[comparison - 1] instanceof MethodInsnNode) || real[comparison - 1].name != 'location'
                || !load(real[use - 1], handle) || !(real[use - 2] instanceof FieldInsnNode)
                || real[use - 2].name != 'textures' || real[use - 2].owner != capability.name)
            fail('Native capture overwrites/borrows actor, source, location or physical handle')
        if (registry.superName != 'com/naocraftlab/skins/client/AbstractTextureRegistry'
                || registry.methods.any { it.name in ['capture', 'release', 'close'] })
            fail('Native owner bypasses common incarnation invalidation before unload')
        [capability, registry].each { ClassNode owner ->
            MethodNode check = owner.methods.find { it.name == 'checkClientThread' }
            if (check == null || !check.instructions.toArray().any {
                it instanceof MethodInsnNode && it.owner == 'net/minecraft/client/Minecraft'
                        && it.name == 'isSameThread' && it.desc == '()Z'
            }) fail('Native resource owner is not client-thread confined')
        }
        MethodNode guard = threadGuard.methods.find { it.name == 'isClientThread' && it.desc == '()Z' }
        List<MethodInsnNode> checks = guard?.instructions?.toArray()?.findAll { it instanceof MethodInsnNode } ?: []
        if (checks.count { it.owner == 'net/minecraft/client/Minecraft' && it.name == 'getInstance' } != 1
                || checks.count { it.owner == 'net/minecraft/client/Minecraft' && it.name == 'isSameThread' && it.desc == '()Z' } != 1)
            fail('Own draw guard does not use the same native client-thread predicate')
    }

    private static boolean load(AbstractInsnNode node, int slot) {
        node instanceof VarInsnNode && node.opcode == Opcodes.ALOAD && node.var == slot
    }

    private static List<AnnotationNode> annotations(Object node) {
        (node.visibleAnnotations ?: []) + (node.invisibleAnnotations ?: [])
    }
    private static Map values(AnnotationNode annotation) {
        Map result = [:]
        if (annotation == null) return result
        for (int index = 0; index < (annotation.values ?: []).size(); index += 2)
            result[annotation.values[index]] = annotation.values[index + 1]
        result
    }
    private static ClassNode read(Collection<File> classpath, String name) {
        String entry = name + '.class'
        for (File file : classpath) {
            byte[] bytes = null
            if (file.isDirectory()) {
                File candidate = new File(file, entry)
                if (candidate.isFile()) bytes = candidate.bytes
            } else if (file.isFile() && file.name.endsWith('.jar')) {
                new ZipFile(file).withCloseable { jar ->
                    def member = jar.getEntry(entry)
                    if (member != null) bytes = jar.getInputStream(member).withCloseable { it.readAllBytes() }
                }
            }
            if (bytes != null) {
                ClassNode node = new ClassNode()
                new ClassReader(bytes).accept(node, 0)
                return node
            }
        }
        fail('Missing exact native/cape class: ' + name)
        null
    }
    private static void fail(String message) { throw new IllegalStateException(message) }
}

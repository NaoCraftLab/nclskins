package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesDrawAbiTest {
    private static final String INFO = 'Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;'
    private static final String STAGE = 'Lnet/minecraft/client/renderer/oit/OitStage;'
    private static final String PASS = 'Lcom/mojang/renderpearl/api/commands/RenderPass;'
    private static final String OWNER = 'net/minecraft/client/renderer/rendertype/PreparedRenderType'

    @Test
    void preparedDrawFamiliesRejectSelectorAndEachMissingOrDuplicateOperation() {
        [false, true].each { boolean renderPass ->
            def baseline = fixture(renderPass)
            WaveyCapesNativeAbi.verifyPreparedDraw(baseline.renderer, baseline.mixin, renderPass)
            List<String> mutations = ['selector', 'hook-selector', 'normal-missing', 'normal-duplicate',
                    'hook-missing', 'non-strict']
            if (renderPass) mutations += ['oit-missing', 'oit-duplicate', 'oit-hook-missing']
            mutations.each { String mutation ->
                def broken = fixture(renderPass)
                MethodNode method = broken.renderer.methods.first()
                switch (mutation) {
                    case 'selector': method.desc = '()V'; break
                    case 'hook-selector': broken.mixin.methods.first().invisibleAnnotations.first().values[1] = ['executeGroup()V']; break
                    case 'normal-missing': method.instructions.remove(method.instructions.first); break
                    case 'normal-duplicate': method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, 'drawFromBuffer', '(' + INFO + (renderPass ? PASS : '') + ')V', false)); break
                    case 'oit-missing': method.instructions.remove(method.instructions.last); break
                    case 'oit-duplicate': method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, 'drawFromBufferOit', '(' + INFO + STAGE + PASS + ')V', false)); break
                    case 'hook-missing': broken.mixin.methods.remove(0); break
                    case 'oit-hook-missing': broken.mixin.methods.remove(1); break
                    case 'non-strict': broken.mixin.methods.first().invisibleAnnotations.first().values[3] = 0; break
                }
                assertThrows(IllegalStateException, {
                    WaveyCapesNativeAbi.verifyPreparedDraw(broken.renderer, broken.mixin, renderPass)
                }, mutation)
            }
        }
    }

    @Test
    void everyTargetSelectsExactlyItsDrawLeafAndExistingRole() {
        File repository = new File('../..').canonicalFile
        Map catalog = CatalogTools.loadCatalog(repository)
        catalog.targets.each { Map target ->
            def sources = CatalogTools.resolveTargetSources(repository, catalog, target)
            String appearance = target.capabilities.appearance
            List expected = appearance == 'cape-foil-combined' ? ['cape-draw-render-pass']
                    : appearance == 'cape-foil-pipeline-bind-groups' ? ['cape-draw-staged'] : []
            assertEquals(expected, sources.bundles.findAll { it in ['cape-draw-staged', 'cape-draw-render-pass'] }, target.id.toString())
            int draws = sources.bundles.sum { String bundle ->
                catalog.sourceBundles[bundle].java.sum { String root ->
                    new File(repository, root + '/com/naocraftlab/skins/compat/client/identifier/foil/prepared/CapturedDrawMixin.java').isFile() ? 1 : 0
                } ?: 0
            }
            assertEquals(expected.size(), draws, target.id.toString())
        }
        assertTrue(new File(repository, 'gradle/build-logic/src/main/resources/architecture-roles.tsv').text.contains(
                'com/naocraftlab/skins/compat/client/identifier/foil/prepared/CapturedDrawMixin\tinfrastructure\tclient\tnative\tinternal'))
    }

    private static Map fixture(boolean renderPass) {
        String desc = '(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;' + (renderPass ? STAGE + PASS : '') + 'ILjava/util/List;Z)V'
        ClassNode renderer = new ClassNode(), mixin = new ClassNode()
        MethodNode execute = new MethodNode(Opcodes.ACC_PUBLIC, 'executeGroup', desc, null, null)
        renderer.methods.add(execute)
        Map<String, String> calls = ['drawFromBuffer': '(' + INFO + (renderPass ? PASS : '') + ')V']
        if (renderPass) calls.drawFromBufferOit = '(' + INFO + STAGE + PASS + ')V'
        calls.each { name, signature ->
            execute.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, name, signature, false))
            MethodNode wrapper = new MethodNode(Opcodes.ACC_PRIVATE, name, '()V', null, null)
            AnnotationNode hook = new AnnotationNode('Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;')
            AnnotationNode at = new AnnotationNode('Lorg/spongepowered/asm/mixin/injection/At;')
            at.values = ['value', 'INVOKE', 'target', 'L' + OWNER + ';' + name + signature]
            hook.values = ['method', ['executeGroup' + desc], 'require', 1, 'expect', 1, 'allow', 1, 'at', [at]]
            wrapper.invisibleAnnotations = [hook]
            mixin.methods.add(wrapper)
        }
        [renderer: renderer, mixin: mixin]
    }
}

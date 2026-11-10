package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import static org.junit.jupiter.api.Assertions.*

class CapeEntityMaterialVerifierTest {
    private final Path repository = new File('../..').canonicalFile.toPath()
    private final Map catalog = CatalogTools.loadCatalog(repository.toFile())
    @TempDir Path temporary






    @Test
    void compiledImmediateInspectionPassesTheSameActorToScopeAndNativeDraw() {
        [
                ['1.20.1', 'playerinfo', 'RemotePlayerPreviewRenderer', 'main'],
                ['1.21.1', 'skinlookup', 'VanillaAppearancePreviewRenderer', 'client']
        ].each { List family ->
            Path compiled = repository.resolve("targets/${family[0]}/fabric/build/classes/java/${family[3]}/com/naocraftlab/skins/compat/client/resourcelocation/${family[1]}/${family[2]}.class")
            org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(compiled), 'native binding checks require compiled targets')
            List<Integer> loads = []
            Integer scopedActor = null
            Integer renderedActor = null
            new ClassReader(Files.readAllBytes(compiled)).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (name != 'render' || !descriptor.contains('PreviewRenderer$PreviewRequest;')) return null
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        void visitVarInsn(int opcode, int variable) {
                            if (opcode == Opcodes.ALOAD) loads.add(variable)
                        }
                        @Override
                        void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                            if (owner.endsWith('/PreviewScope') && method == 'open') scopedActor = loads[-2]
                            if (owner.endsWith('/InventoryScreen') && method == 'renderEntityInInventory') renderedActor = loads[-1]
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
            assertNotNull(scopedActor, family[0].toString())
            assertNotNull(renderedActor, family[0].toString())
            assertEquals(renderedActor, scopedActor, family[0].toString())
        }
    }

    @Test
    void compiledImmediatePreviewFoilUsesTheExistingGuiOwnerBehindBothScopeGuards() {
        verifyCompiledImmediatePreviewFoil(repository)
    }

    @Test
    void compiledImmediatePreviewFoilDoesNotRequirePrebuiltTargets() {
        Path clean = temporary.resolve('clean-checkout')
        String source = 'compat/capabilities/appearance/cape-layer-translucent/src/main/java/com/naocraftlab/skins/compat/client/cape/mixin/CapeLayerTransparencyMixin.java'
        Files.createDirectories(clean.resolve(source).parent)
        Files.copy(repository.resolve(source), clean.resolve(source))
        assertFalse(Files.exists(clean.resolve('targets')))
        verifyCompiledImmediatePreviewFoil(clean)
    }

    private void verifyCompiledImmediatePreviewFoil(Path root) {
        assertImmediatePreviewFoil(compileImmediatePreviewFoil(immediatePreviewFoilMethod(root)), 'shared production method')
        [['1.20.1', 'main'], ['1.21.1', 'client']].each { List family ->
            Path compiled = root.resolve("targets/${family[0]}/fabric/build/classes/java/${family[1]}/com/naocraftlab/skins/compat/client/cape/mixin/CapeLayerTransparencyMixin.class")
            if (Files.isRegularFile(compiled)) {
                assertImmediatePreviewFoil(Files.readAllBytes(compiled), family[0].toString())
            }
        }
    }

    @Test
    void compiledImmediatePreviewFoilRejectsScopeAndGuiOwnerMutations() {
        String method = immediatePreviewFoilMethod(repository)
        [
                method.replace('!nativeFoil.get()', 'false'),
                method.replace('directFoil.get()\n                ?', 'true\n                ?'),
                method.replace('Minecraft.getInstance().renderBuffers().bufferSource()', 'buffers')
        ].eachWithIndex { String mutant, int index ->
            assertNotEquals(method, mutant)
            byte[] compiled = compileImmediatePreviewFoil(mutant)
            assertThrows(org.opentest4j.AssertionFailedError) {
                assertImmediatePreviewFoil(compiled, 'mutant ' + index)
            }
        }
    }

    private static String immediatePreviewFoilMethod(Path root) {
        String source = Files.readString(root.resolve('compat/capabilities/appearance/cape-layer-translucent/src/main/java/com/naocraftlab/skins/compat/client/cape/mixin/CapeLayerTransparencyMixin.java'))
        String marker = '    private VertexConsumer nclskins$capeFoilBuffer('
        int start = source.indexOf(marker)
        assertTrue(start >= 0)
        assertEquals(-1, source.indexOf(marker, start + marker.length()))
        int end = source.indexOf('\n    }', start)
        assertTrue(end > start)
        source.substring(start, end + '\n    }'.length()).replaceAll(/@Share\("[^"]+"\)\s*/, '')
    }

    private byte[] compileImmediatePreviewFoil(String method) {
        Path output = Files.createTempDirectory(temporary, 'immediate-foil-')
        Path source = output.resolve('ImmediateCapeFoil.java')
        Files.writeString(source, '''package com.naocraftlab.skins.buildlogic.fixture;
final class ImmediateCapeFoil {
''' + method + '''
}
interface Operation<T> { T call(Object... arguments); }
interface LocalBooleanRef { boolean get(); }
final class VertexConsumer { }
final class RenderType { }
final class MultiBufferSource { }
final class Minecraft {
    static Minecraft getInstance() { return null; }
    RenderBuffers renderBuffers() { return null; }
}
final class RenderBuffers {
    MultiBufferSource bufferSource() { return null; }
}
final class CapeTranslucentFoilMaterial {
    static VertexConsumer withFoil(MultiBufferSource buffers,
            java.util.function.Supplier<VertexConsumer> base, boolean direct) { return base.get(); }
}
''')
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream()
        def compiler = javax.tools.ToolProvider.systemJavaCompiler
        assertNotNull(compiler)
        assertEquals(0, compiler.run(null, diagnostics, diagnostics,
                '--release', '17', '-g:none', '-d', output.toString(), source.toString()), diagnostics.toString())
        Files.readAllBytes(output.resolve('com/naocraftlab/skins/buildlogic/fixture/ImmediateCapeFoil.class'))
    }

    private static void assertImmediatePreviewFoil(byte[] compiled, String context) {
        List<String> calls = []
        List<Integer> branches = []
        new ClassReader(compiled).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (name != 'nclskins$capeFoilBuffer') return null
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        calls.add("${owner.substring(owner.lastIndexOf('/') + 1)}.${method}".toString())
                    }
                    @Override
                    void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                        if (opcode != Opcodes.GOTO) branches.add(opcode)
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        assertEquals([Opcodes.IFNE, Opcodes.IFEQ], branches, context)
        assertEquals(['LocalBooleanRef.get', 'Operation.call', 'LocalBooleanRef.get',
                      'Minecraft.getInstance', 'Minecraft.renderBuffers', 'RenderBuffers.bufferSource',
                      'LocalBooleanRef.get', 'CapeTranslucentFoilMaterial.withFoil'], calls, context)
    }

    @Test
    void compiledImmediateFoilAcquiresTheBaseLast() {
        Path compiled = repository.resolve('targets/1.20.1/fabric/build/classes/java/main/com/naocraftlab/skins/compat/client/cape/CapeTranslucentFoilMaterial.class')
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(compiled), 'native binding checks require compiled targets')
        List<String> acquisitions = []
        new ClassReader(Files.readAllBytes(compiled)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (name != 'withFoil') return null
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        if (owner == 'net/minecraft/client/renderer/MultiBufferSource' && method == 'getBuffer') acquisitions.add('foil')
                        if (owner == 'java/util/function/Supplier' && method == 'get') acquisitions.add('base')
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        assertEquals(['foil', 'base'], acquisitions)
    }

}

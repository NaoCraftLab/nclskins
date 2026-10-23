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
    void selectedProductionMaterialsPreserveWorldlessAlphaAndScopeNativeExperiment() {
        List<String> errors = []
        SemanticVerifier.verifyCapeEntityMaterials(repository, catalog, errors)
        assertTrue(errors.empty, errors.join('\n'))
    }

    @Test
    void productionMutationsDetectMissingAlphaAndUnscopedNativeTextureSelection() {
        Path source = repository.resolve('compat/capabilities/appearance')
        Files.walk(source).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { Path path ->
                Path copy = temporary.resolve(repository.relativize(path))
                Files.createDirectories(copy.parent)
                Files.copy(path, copy)
            }
        }
        String baked = 'compat/capabilities/preview/avatar-pip-submit-crumbling-overlay/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/NclBakedPlayerRenderer.java'
        Files.createDirectories(temporary.resolve(baked).parent)
        Files.copy(repository.resolve(baked), temporary.resolve(baked))
        String fragment = 'compat/capabilities/appearance/cape-foil-pipeline/src/main/resources/assets/nclskins/shaders/core/cape_entity_foil.fsh'
        String mixin = 'compat/capabilities/appearance/cape-foil-pipeline/src/main/java/com/naocraftlab/skins/compat/client/identifier/foil/pipeline/CapeLayerFoilMixin.java'
        mutate(fragment, 'TextureMat * vec4(texCoord0,', 'TextureMat * vec4(texCoord0 * 16.0,', 'native glint')
        mutate(fragment, 'color.rgb += foil * foil;', 'color.rgb += foil * 0.5;', 'native glint')
        mutate(fragment, 'GlintAlpha * texture(', 'texture(', 'native glint')
        mutate(fragment, '#moj_import <minecraft:globals.glsl>', '', 'native glint')
        mutate(fragment, 'texture(GlintSampler,', 'texture(Sampler0,', 'cape alpha')
        mutate('compat/capabilities/appearance/cape-entity-shader-position-fog/src/main/resources/assets/nclskins/shaders/core/cape_entity_foil.json', '"GlintAlpha"', '"MissingAlpha"', 'GlintAlpha')
        mutate(fragment, 'texture(Sampler0, texCoord0)', 'vec4(1.0)', 'cape alpha')
        mutate(fragment, 'color.rgb += foil * foil;', 'color.a = 1.0;', 'cape alpha')
        mutate('compat/capabilities/appearance/cape-foil-combined/src/main/resources/assets/nclskins/shaders/core/cape_entity_foil.fsh', 'color.a <= 0.0', 'color.a < 0.0', 'current frame alpha')
        mutate('compat/capabilities/appearance/cape-layer-translucent/src/main/java/com/naocraftlab/skins/compat/client/cape/CapeTranslucentFoilMaterial.java', 'new ShaderStateShard(() -> shader)', 'new ShaderStateShard(() -> null)', 'reload shader supplier')
        mutate(mixin, 'snapshot.textureLocation().equals(texture.toString())', 'true', 'exact extracted texture')
        mutate(baked, 'state.capeGlint() && CapeTranslucentFoilMaterial.inWorld()', 'state.capeGlint()', 'world/source gating')
        mutate('compat/capabilities/appearance/cape-layer-translucent/src/main/java/com/naocraftlab/skins/compat/client/cape/CapeTranslucentFoilMaterial.java', 'if (inWorld) return', 'if (true) return', 'worldless combined branch')
        mutate('compat/capabilities/appearance/cape-foil-combined/src/main/java/com/naocraftlab/skins/compat/client/identifier/foil/CapeTranslucentFoilMaterial.java', 'minecraft != null && minecraft.level != null', 'true', 'worldless combined branch')
        mutate('compat/capabilities/appearance/cape-layer-translucent/src/main/java/com/naocraftlab/skins/compat/client/cape/CapeTranslucentFoilMaterial.java', 'direct ? RenderType.entityGlintDirect() : RenderType.entityGlint()', 'RenderType.entityGlint()', 'direct target')
        mutate('compat/capabilities/appearance/cape-layer-translucent/src/main/java/com/naocraftlab/skins/compat/client/cape/CapeTranslucentFoilMaterial.java', 'withFoil(buffers, () -> buffers.getBuffer(type), true)', 'withFoil(buffers, () -> buffers.getBuffer(type), false)', 'direct target')
        mutate('compat/capabilities/appearance/cape-layer-translucent/src/main/java/com/naocraftlab/skins/compat/client/cape/CapeTranslucentFoilMaterial.java', 'return VertexMultiConsumer.create(foil, base.get());', 'return base.get();', 'base last')
        mutate('compat/capabilities/appearance/cape-layer-translucent/src/main/java/com/naocraftlab/skins/compat/client/cape/mixin/CapeLayerTransparencyMixin.java', 'withFoil(foilBuffers,\n                () -> original.call(buffers, type), directFoil.get())', 'withFoil(foilBuffers, () -> buffers.getBuffer(type), directFoil.get())', 'original base lazily')
        mutate('compat/capabilities/appearance/cape-layer-translucent/src/main/java/com/naocraftlab/skins/compat/client/cape/mixin/CapeLayerTransparencyMixin.java', '? Minecraft.getInstance().renderBuffers().bufferSource() : buffers', '? buffers : buffers', 'native GUI owner')
        mutate(mixin, 'if (nativeFoil.get())', 'if (true)', 'invocation-scoped')
        mutate(mixin, 'nativeFoil.set(CapeTranslucentFoilMaterial.inWorld())', 'nativeFoil.set(true)', 'invocation-scoped')
        mutate('compat/capabilities/appearance/cape-foil-buffer/src/main/java/com/naocraftlab/skins/compat/client/identifier/foil/CapeFoilBuffer.java', 'glint && CapeTranslucentFoilMaterial.inWorld()', 'glint', 'world/source gating')
    }

    @Test
    void productionFragmentsUseOneNativeTransformAndSquaredNativeStrength() {
        ['cape-layer-translucent', 'cape-foil-pipeline'].each { String bundle ->
            String body = Files.readString(repository.resolve("compat/capabilities/appearance/${bundle}/src/main/resources/assets/nclskins/shaders/core/cape_entity_foil.fsh"))
            assertTrue(body.contains('TextureMat * vec4(texCoord0, 0.0, 1.0)'), bundle)
            assertEquals(1, body.count('TextureMat *'), bundle)
            assertTrue(body.contains('vec3 foil = GlintAlpha * texture('), bundle)
            assertTrue(body.contains('color.rgb += foil * foil;'), bundle)
            assertFalse(body.contains('color.a ='), bundle)
            assertTrue(bundle == 'cape-layer-translucent'
                    ? body.contains('uniform float GlintAlpha;')
                    : body.contains('#moj_import <minecraft:globals.glsl>'), bundle)
        }
        ['cape-entity-shader-position-fog', 'cape-entity-shader-view-rotation'].each { String bundle ->
            Map shader = new groovy.json.JsonSlurper().parse(repository.resolve("compat/capabilities/appearance/${bundle}/src/main/resources/assets/nclskins/shaders/core/cape_entity_foil.json").toFile()) as Map
            assertEquals([name: 'GlintAlpha', type: 'float', count: 1, values: [1.0]],
                    shader.uniforms.find { it.name == 'GlintAlpha' }, bundle)
        }
    }

    @Test
    void actualCompiledSupplierAndMutationExposeReloadCaptureDrift() {
        Path helper = repository.resolve('targets/1.20.1/fabric/build/classes/java/main/com/naocraftlab/skins/compat/client/cape/CapeTranslucentFoilMaterial.class')
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(helper), "compiled supplier is checked by the artifact gate after target builds")
        byte[] production = Files.readAllBytes(helper)
        assertTrue(ArtifactVerifier.capeShaderSupplierReadsCurrentProgram(production))
        ClassWriter writer = new ClassWriter(0)
        new ClassReader(production).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (!name.startsWith('lambda$create$')) return original
                return new MethodVisitor(Opcodes.ASM9, original) {
                    @Override
                    void visitFieldInsn(int opcode, String owner, String field, String type) {
                        if (opcode == Opcodes.GETSTATIC && field == 'shader') super.visitInsn(Opcodes.ACONST_NULL)
                        else super.visitFieldInsn(opcode, owner, field, type)
                    }
                }
            }
        }, 0)
        assertFalse(ArtifactVerifier.capeShaderSupplierReadsCurrentProgram(writer.toByteArray()))
    }

    @Test
    void immediateInspectionScopesTheRenderedProxyAndUsesDirectFoil() {
        [
                ['remote-player', 'playerinfo', 'RemotePlayerPreviewRenderer'],
                ['player-skin', 'skinlookup', 'VanillaAppearancePreviewRenderer']
        ].each { List family ->
            Path sources = repository.resolve("compat/capabilities/preview/${family[0]}/src/main/java/com/naocraftlab/skins/compat/client/resourcelocation/${family[1]}")
            String renderer = Files.readString(sources.resolve("${family[2]}.java")).replaceAll('\\s+', ' ')
            String scope = Files.readString(sources.resolve('PreviewScope.java'))
            assertTrue(renderer.contains('PreviewScope.open( minecraft, player, request.appearance())'), family[0].toString())
            assertTrue(scope.contains('minecraft.level != player.level()'), family[0].toString())
            assertFalse(scope.contains('minecraft.player != player'), family[0].toString())
        }
        String material = Files.readString(repository.resolve('compat/capabilities/appearance/cape-layer-translucent/src/main/java/com/naocraftlab/skins/compat/client/cape/CapeTranslucentFoilMaterial.java'))
        assertTrue(material.contains('direct ? RenderType.entityGlintDirect() : RenderType.entityGlint()'))
        assertTrue(material.contains('if (glint && inWorld()) return withFoil(buffers, () -> buffers.getBuffer(type), true);'))
    }

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
        [['1.20.1', 'main'], ['1.21.1', 'client']].each { List family ->
            Path compiled = repository.resolve("targets/${family[0]}/fabric/build/classes/java/${family[1]}/com/naocraftlab/skins/compat/client/cape/mixin/CapeLayerTransparencyMixin.class")
            assertTrue(Files.isRegularFile(compiled), 'native owner regression requires compiled targets')
            List<String> calls = []
            List<Integer> branches = []
            new ClassReader(Files.readAllBytes(compiled)).accept(new ClassVisitor(Opcodes.ASM9) {
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
            assertEquals([Opcodes.IFNE, Opcodes.IFEQ], branches, family[0].toString())
            assertEquals(['LocalBooleanRef.get', 'Operation.call', 'LocalBooleanRef.get',
                          'Minecraft.getInstance', 'Minecraft.renderBuffers', 'RenderBuffers.bufferSource',
                          'LocalBooleanRef.get', 'CapeTranslucentFoilMaterial.withFoil'], calls, family[0].toString())
        }
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

    private void mutate(String file, String original, String replacement, String diagnostic) {
        Path path = temporary.resolve(file)
        String body = Files.readString(path)
        assertTrue(body.contains(original))
        Files.writeString(path, body.replace(original, replacement))
        List<String> errors = []
        SemanticVerifier.verifyCapeEntityMaterials(temporary, catalog, errors)
        assertTrue(errors.any { it.contains(diagnostic) }, errors.join('\n'))
        Files.writeString(path, body)
    }
}

package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

import static org.junit.jupiter.api.Assertions.*

final class ForgeCpmRefmapTest {
    @TempDir
    Path temporary

    private final File repository = new File('../..').canonicalFile
    private final List<String> importBindings = [
            'CpmModelDefinitionPartsAccessor', 'CpmModelSkinTextureAccessor',
            'CpmModelTextureAccessor', 'CpmProjectExporterInvoker',
            'CpmProjectExporterMixin', 'CpmProjectTemplateMixin', 'CpmProjectTextureMixin',
            'CpmAnimatedTextureMixin', 'CpmDefinitionResourceMixin'
    ]

    @Test
    void currentCpmDeclarationsAcceptExplicitlyUnmappedUpstreamBindings() {
        assertEquals([], verify())
    }

    @Test
    void everyImportBindingRejectsEnabledOrImplicitMinecraftRemapping() {
        importBindings.each { String binding ->
            [true, null].each { Boolean remap ->
                assertEquals([
                        "forge-1.20.1: CPM upstream binding ${binding} must explicitly disable Minecraft remapping".toString()
                ], verify([(binding): remap]), "${binding}: remap=${remap}".toString())
            }
        }
    }

    @Test
    void unknownCpmDeclarationStillRequiresReviewedRefmapOwnership() {
        assertEquals(['forge-1.20.1: Forge Mixin declarations differ from refmap baseline'],
                verify([:], true))
    }

    @Test
    void upstreamBindingsDoNotRelaxMinecraftMappingValidation() {
        assertEquals([
                'forge-1.20.1: Forge Mixin production mapping baseline differs',
                'forge-1.20.1: Forge Mixin searge mapping baseline differs'
        ], verify([:], false, true))
    }

    private List<String> verify(Map<String, Boolean> remapping = [:],
                                boolean unknown = false, boolean corruptMappings = false) {
        Map baseline = ArtifactVerifier.FORGE_REFMAPS['forge-1.20.1']
        Map cpm = new JsonSlurper().parse(new File(repository,
                'compat/cpm/src/main/resources/nclskins.cpm.mixins.json')) as Map
        assertTrue(cpm.client.containsAll(importBindings))
        cpm.refmap = baseline.path
        if (unknown) cpm.client.add('UnreviewedUpstreamMixin')
        Map mappings = corruptMappings ? [:] : baseline.mappings
        Map<String, byte[]> entries = [
                'nclskins.cpm.mixins.json': JsonOutput.toJson(cpm).getBytes('UTF-8'),
                (baseline.path.toString()): JsonOutput.toJson(
                        [mappings: mappings, data: [searge: mappings]]).getBytes('UTF-8')
        ]
        List<String> configs = ['nclskins.cpm.mixins.json']
        baseline.mappings.keySet().groupBy { it.substring(0, it.lastIndexOf('/')) }
                .each { String packageName, List<String> classes ->
                    String configName = "minecraft-${configs.size()}.mixins.json"
                    configs.add(configName)
                    entries[configName] = JsonOutput.toJson([
                            package: packageName.replace('/', '.'), refmap: baseline.path,
                            client: classes.collect { it.substring(it.lastIndexOf('/') + 1) }
                    ]).getBytes('UTF-8')
                }
        cpm.client.each { String name ->
            String binaryName = cpm.package.replace('.', '/') + '/' + name
            def writer = new ClassWriter(0)
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, binaryName, null, 'java/lang/Object', null)
            def annotation = writer.visitAnnotation('Lorg/spongepowered/asm/mixin/Mixin;', false)
            Boolean remap = remapping.containsKey(name) ? remapping[name] : false
            if (remap != null) annotation.visit('remap', remap)
            annotation.visitEnd()
            writer.visitEnd()
            entries[binaryName + '.class'] = writer.toByteArray()
        }
        File jar = temporary.resolve('fixture.jar').toFile()
        new ZipOutputStream(new FileOutputStream(jar)).withCloseable { output ->
            entries.each { String name, byte[] bytes ->
                output.putNextEntry(new ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        List<String> errors = []
        new ZipFile(jar).withCloseable { archive ->
            ArtifactVerifier.verifyForgeRefmap(archive,
                    [id: 'forge-1.20.1', loader: [id: 'forge'],
                     metadata: [mixins: configs]],
                    entries.keySet().toList(), errors)
        }
        errors.collect { it.toString() }
    }
}

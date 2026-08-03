package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

final class BuildLogicTest {
    private final File repository = new File('../..').canonicalFile
    private final Map catalog = CatalogTools.loadCatalog(repository)
    private final Map abi = CatalogTools.loadJson(new File(repository, 'gradle/abi-fingerprints.json'))

    @Test
    void currentCatalogIsValid() {
        assertEquals(3, catalog.schemaVersion)
        CatalogTools.validate(repository, catalog)
    }

    @Test
    void everyTargetResolvesSourceBundles() {
        catalog.targets.each { Map target ->
            Map resolved = CatalogTools.resolveTargetSources(repository, catalog, target)
            assertFalse(resolved.java.isEmpty(), target.id.toString())
            assertFalse(resolved.resources.isEmpty(), target.id.toString())
        }
    }

    @Test
    void missingBundleAndCyclesAreRejected() {
        Map missing = cloneMap(catalog)
        missing.sourceBundles['portable-common'].requires = ['missing']
        assertThrows(IllegalArgumentException) { CatalogTools.resolveBundleOrder(missing, missing.baseBundles as List<String>) }
        Map cycle = cloneMap(catalog)
        cycle.sourceBundles['portable-common'].requires = ['canonical-resources']
        cycle.sourceBundles['canonical-resources'].requires = ['portable-common']
        assertThrows(IllegalArgumentException) { CatalogTools.resolveBundleOrder(cycle, cycle.baseBundles as List<String>) }
    }

    @Test
    void affectedClassificationUsesCatalogOwnership() {
        assertEquals(['forge-1.20.1'] as Set, selected('targets/forge/1.20.1/build.gradle'))
        assertEquals(['fabric-1.20.1', 'forge-1.20.1'] as Set, selected('compat/capabilities/gui/immediate-1.20/src/main/java/Example.java'))
        assertEquals(catalog.targets.collect { it.id } as Set, selected('core/src/main/java/com/naocraftlab/skins/core/Example.java'))
        assertEquals(catalog.targets.collect { it.id } as Set, selected('gradle/version.properties'))
    }

    @Test
    void unknownProductionPathIsRejected() {
        assertThrows(IllegalArgumentException) { CatalogTools.classifyAffected(repository, catalog, ['new-runtime/Main.java']) }
    }

    @Test
    void metadataMatchesEveryLoaderContract() {
        String version = CatalogTools.loadVersion(repository)
        catalog.targets.each { Map target ->
            Map<String, String> resources = MetadataRenderer.render(catalog, target, version)
            assertEquals(target.metadata.files as Set, resources.keySet() as Set, target.id.toString())
            assertTrue(resources.values().any { it.contains('GPL-3.0-only') }, target.id.toString())
            if (target.loader.id == 'fabric') {
                Map metadata = new JsonSlurper().parseText(resources['fabric.mod.json']) as Map
                assertEquals('*', metadata.environment)
                assertEquals([main: [target.metadata.serverEntrypoint], client: [target.metadata.entrypoint]], metadata.entrypoints)
            } else if (target.loader.id == 'forge') {
                assertTrue(resources['META-INF/mods.toml'].contains('displayTest="IGNORE_SERVER_VERSION"'))
                assertFalse(resources['META-INF/mods.toml'].contains('[[mixins]]'))
            } else {
                assertTrue(resources['META-INF/neoforge.mods.toml'].contains("javaVersion=\"[${target.java.release},)\""))
                assertEquals((target.metadata.serverMixins ?: []) + target.metadata.mixins, resources['META-INF/neoforge.mods.toml'].readLines().findAll { it.startsWith('config=') }.collect { it.substring('config="'.length(), it.length() - 1) })
            }
        }
    }

    @Test
    void ideaRunsUseOnlyRootGradleTasks() {
        Set<String> taskNames = [] as Set
        catalog.targets.each { Map target ->
            ['Client', 'Server'].each { String runKind ->
                String taskName = IdeaRunConfigurations.taskName(target, runKind)
                assertTrue(taskNames.add(taskName))
                String rendered = IdeaRunConfigurations.render(target, runKind)
                def configuration = new groovy.xml.XmlSlurper().parseText(rendered).configuration
                assertEquals('GradleRunConfiguration', configuration.@type.toString())
                assertEquals('$PROJECT_DIR$', configuration.ExternalSystemSettings.option.find { it.@name == 'externalProjectPath' }.@value.toString())
                assertEquals(taskName, configuration.ExternalSystemSettings.option.find { it.@name == 'taskNames' }.list.option.@value.toString())
                assertFalse(rendered.contains('python'))
                assertFalse(rendered.contains('do' + 'cs/'))
                assertFalse(rendered.contains('scr' + 'ipts/'))
            }
        }
        assertEquals(12, taskNames.size())
    }

    @Test
    void privateToolReferencesAreRejectedFromPublicCode() {
        PublicationTreeVerifier.PRIVATE_TOOL_REFERENCES.each { String marker ->
            assertTrue(PublicationTreeVerifier.containsPrivateToolReference("prefix ${marker} suffix"))
        }
        assertFalse(PublicationTreeVerifier.containsPrivateToolReference('gradle/build-logic'))
    }

    @Test
    void targetRunCommandsUseCatalogWrappersPortsAndNativeTasks() {
        catalog.targets.each { Map target ->
            List<String> client = TargetRunTask.command(repository, catalog, target, 'Client', true)
            assertEquals('runClient', client[-2])
            assertEquals('--dry-run', client[-1])
            assertEquals(TargetRuntime.wrapper(repository, catalog, target).absolutePath, client.first())
            List<String> server = TargetRunTask.command(repository, catalog, target, 'Server', true)
            assertTrue(server.contains('runServer'))
            int port = target.development.serverPort as int
            if (target.loader.id == 'neoforge') assertTrue(server.contains("-PnclskinsServerPort=${port}".toString()))
            else assertTrue(server.contains("--args=--port ${port}".toString()))
        }
    }

    @Test
    void committedAbiBaselinesMatchEachTargetSelection() {
        catalog.targets.each { Map target ->
            Map declarations = catalog.capabilityImplementations as Map
            Set<String> selected = (target.capabilities as Map).values().collect { declarations[it].abiImplementation.toString() } as Set
            Map actual = new TreeMap()
            selected.each { actual[it] = abi.resolvedByEpoch[target.minecraft.epoch][it] }
            AbiVerifier.verify(catalog, abi, target.id.toString(), actual)
        }
    }

    @Test
    void genericAbiNamesAreSplitAndErased() {
        assertEquals(['java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>', 'java.lang.Runnable'], AbiVerifier.splitGeneric('java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>, java.lang.Runnable'))
        assertEquals('java.util.Map', AbiVerifier.eraseGenerics('java.util.Map<java.lang.String, java.lang.Integer>'))
    }

    @Test
    void selectedAbiHashSortsMembersDeterministically() {
        Map surface = [access: 'public', finality: 'concrete']
        List<Map> members = [
                [kind: 'method', name: 'zeta', descriptor: '()V', access: 'public', finality: 'virtual'],
                [kind: 'method', name: 'alpha', descriptor: '()V', access: 'public', finality: 'virtual']
        ]
        assertEquals(AbiVerifier.selectedHash('Example', surface, members, []), AbiVerifier.selectedHash('Example', surface, members.reverse(), []))
    }

    @Test
    void changedAbiBaselineIsRejected() {
        Map target = catalog.targets.first() as Map
        Map declarations = catalog.capabilityImplementations as Map
        Set<String> selected = (target.capabilities as Map).values().collect { declarations[it].abiImplementation.toString() } as Set
        Map actual = new TreeMap()
        selected.each { actual[it] = cloneMap(abi.resolvedByEpoch[target.minecraft.epoch][it] as Map) }
        String implementation = selected.first()
        String className = (actual[implementation] as Map).keySet().first()
        actual[implementation][className] = '0' * 64
        assertThrows(IllegalStateException) { AbiVerifier.verify(catalog, abi, target.id.toString(), actual) }
    }

    @Test
    void semanticManifestMatchesCurrentSources() {
        Map coverage = CatalogTools.loadJson(new File(repository, 'gradle/capability-semantic-coverage.json'))
        assertEquals([], SemanticVerifier.verify(repository.toPath(), catalog, abi, coverage))
    }

    @Test
    void classfileVerifierRejectsWrongTargetMajor() {
        Path jar = Files.createTempFile('nclskins-artifact-', '.jar')
        try {
            byte[] classfile = [(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0, 0, 0, 61] as byte[]
            new ZipOutputStream(Files.newOutputStream(jar)).withCloseable { output ->
                output.putNextEntry(new ZipEntry('Example.class'))
                output.write(classfile)
                output.closeEntry()
            }
            ZipFile archive = new ZipFile(jar.toFile())
            archive.withCloseable {
                Map target = [id: 'fixture', java: [classfileMajor: 61]]
                List<String> errors = []
                ArtifactVerifier.verifyClassfiles(it, target, ['Example.class'], errors)
                assertEquals([], errors)
                target.java.classfileMajor = 65
                ArtifactVerifier.verifyClassfiles(it, target, ['Example.class'], errors)
                assertTrue(errors.any { message -> message.contains('expected classfile major 65') })
            }
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    @Test
    void commentScannerPreservesCommentLikeLiterals() {
        assertFalse(PublicationTreeVerifier.containsComment('String value = "https://example.invalid/a//b";\n', false))
        assertFalse(PublicationTreeVerifier.containsComment("def value = /a\\/\\/b/\n", true))
        assertTrue(PublicationTreeVerifier.containsComment('int value = 1; // private note\n', false))
        assertTrue(PublicationTreeVerifier.containsComment('int value = 1; /* private note */\n', false))
    }

    private Set<String> selected(String path) {
        CatalogTools.classifyAffected(repository, catalog, [path]).findAll { String id, Set reasons -> !reasons.isEmpty() }.keySet() as Set
    }

    private static Map cloneMap(Map value) {
        new JsonSlurper().parseText(JsonOutput.toJson(value)) as Map
    }
}

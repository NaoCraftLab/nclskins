package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*

class MetadataBaselineInputsTest {
    @TempDir File workspace
    private final File repository = new File('../..').canonicalFile

    File fixture() {
        File root = new File(workspace, 'project')
        new File(root, 'gradle').mkdirs()
        new File(root, 'gradle/targets.json').bytes = new File(repository, 'gradle/targets.json').bytes
        new File(root, 'gradle/version.properties').text = 'modVersion=1.2.2\n'
        new File(root, 'gradle/plugin-version.properties').text = 'pluginVersion=1.2.2\npluginBuild=1\n'
        new File(root, 'settings.gradle').text = "rootProject.name = 'metadata-baseline-fixture'\n"
        new File(root, 'build.gradle').text = '''
plugins { id 'com.naocraftlab.skins.build-tools' }
['fabric', 'neoforge'].each { loader ->
    tasks.register(loader + 'Metadata', nclskinsGenerateMetadataTaskType) {
        catalogFile.set(layout.projectDirectory.file('gradle/targets.json'))
        versionFile.set(layout.projectDirectory.file('gradle/version.properties'))
        targetId.set(loader + '-26.2')
        requiredServerPluginVersion.set(providers.environmentVariable('NCLSKINS_SERVER_PLUGIN_BASELINE')
                .orElse('').map { nclskinsMetadataRenderer.effectiveRequiredServerPluginVersion(
                        com.naocraftlab.skins.buildlogic.ServerPluginVersion.parts(
                                com.naocraftlab.skins.buildlogic.ServerPluginVersion.load(projectDir)).base.toString(), it) })
        outputDirectory.set(layout.buildDirectory.dir(loader))
    }
}
'''
        root
    }

    GradleRunner runner(File root, String baseline) {
        Map<String, String> environment = new HashMap<>(System.getenv())
        environment.remove('NCLSKINS_SERVER_PLUGIN_BASELINE')
        if (baseline != null) environment.NCLSKINS_SERVER_PLUGIN_BASELINE = baseline
        GradleRunner.create().withProjectDir(root).withPluginClasspath()
                .withTestKitDir(new File(workspace, 'test-kit'))
                .withEnvironment(environment)
                .withArguments('fabricMetadata', 'neoforgeMetadata', '--console=plain', '--stacktrace')
    }

    void assertMetadata(File root, String baseline, String version = '1.2.2') {
        ['fabric', 'neoforge'].each { String loader ->
            File compatibility = new File(root, "build/${loader}/nclskins-server-compatibility.json")
            Map actual = new JsonSlurper().parse(compatibility) as Map
            assertEquals([requiredServerPluginVersion: baseline,
                    protocolIds: ['appearance-refresh-v1', 'proxy-refresh-v1'], matrixId: 'official-v1'], actual)
            if (loader == 'fabric') {
                Map descriptor = new JsonSlurper().parse(new File(root, 'build/fabric/fabric.mod.json')) as Map
                assertEquals('nclskins', descriptor.id)
                assertEquals(version, descriptor.version)
                assertEquals('*', descriptor.environment)
            } else {
                String descriptor = new File(root, 'build/neoforge/META-INF/neoforge.mods.toml').text
                assertTrue(descriptor.readLines().contains('modId="nclskins"'))
                assertTrue(descriptor.readLines().contains('version="' + version + '"'))
            }
            println "${loader}: ${actual}; descriptor=${version}; sha256=${TestFiles.hash(compatibility)}"
        }
    }

    @Test void equivalentSpellingsFallbackAndPlanOnlyChangesKeepOutputsUpToDate() {
        File root = fixture()
        runner(root, '1.4.0').build()
        Map explicit = TestFiles.treeContents(new File(root, 'build/fabric'))
        ['  1.4.0  ', '1.4.0'].each { String baseline ->
            def result = runner(root, baseline).build()
            ['fabricMetadata', 'neoforgeMetadata'].each {
                assertEquals(TaskOutcome.UP_TO_DATE, result.task(':' + it).outcome)
            }
            assertEquals(explicit, TestFiles.treeContents(new File(root, 'build/fabric')))
            assertMetadata(root, '1.4.0')
        }
        new File(root, 'release-plan.json').text = '{"digest":"first","path":"one"}'
        assertEquals(TaskOutcome.UP_TO_DATE, runner(root, '1.4.0').build().task(':fabricMetadata').outcome)
        new File(root, 'release-plan.json').text = '{"digest":"second","path":"two"}'
        assertEquals(TaskOutcome.UP_TO_DATE, runner(root, '1.4.0').build().task(':fabricMetadata').outcome)
        Map fallback
        [null, '', '   ', '1.2.2', null].eachWithIndex { String baseline, int index ->
            def result = runner(root, baseline).build()
            ['fabricMetadata', 'neoforgeMetadata'].each {
                assertEquals(index == 0 ? TaskOutcome.SUCCESS : TaskOutcome.UP_TO_DATE,
                        result.task(':' + it).outcome)
            }
            assertMetadata(root, '1.2.2')
            Map hashes = TestFiles.treeContents(new File(root, 'build/fabric'))
            if (index == 0) fallback = hashes
            else assertEquals(fallback, hashes)
        }
        new File(root, 'gradle/version.properties').text = 'modVersion=1.2.3\n'
        def changed = runner(root, null).build()
        ['fabricMetadata', 'neoforgeMetadata'].each {
            assertEquals(TaskOutcome.SUCCESS, changed.task(':' + it).outcome)
        }
        assertMetadata(root, '1.2.2', '1.2.3')
    }

    @Test void explicitPropertyWinsOverAmbientBaseline() {
        File root = fixture()
        new File(root, 'build.gradle').append('''
['fabricMetadata', 'neoforgeMetadata'].each { name ->
    tasks.named(name) { requiredServerPluginVersion.set('1.4.0') }
}
''')
        runner(root, '1.5.0-beta.2').build()
        assertMetadata(root, '1.4.0')
    }

    @Test void invalidOverridesFailBeforeRemovingExistingMetadata() {
        File root = fixture()
        ['1.4.0-alpha.1', '1.5.0-beta.2'].each { String baseline ->
            runner(root, baseline).build()
            assertMetadata(root, baseline)
        }
        Map original = TestFiles.treeContents(new File(root, 'build'))
        ['v1.4.0', '1.4.0.1', '01.4.0', '1.04.0', '1.4.00', '1.4.0-beta.0',
         '1.4.0-rc.1', '1.4.0+build', 'arbitrary'].each { String invalid ->
            def rejected = runner(root, invalid).buildAndFail()
            assertTrue(rejected.output.contains('NCLSKINS_SERVER_PLUGIN_BASELINE must be an exact no-v SemVer'), rejected.output)
            assertEquals(original, TestFiles.treeContents(new File(root, 'build')))
        }
        new File(root, 'build.gradle').append('''
tasks.named('fabricMetadata') { requiredServerPluginVersion.set('v1.4.0') }
''')
        runner(root, '1.4.0').buildAndFail()
        assertEquals(original, TestFiles.treeContents(new File(root, 'build')))
    }


    @Test void changingBaselineRegeneratesWithoutCleanOrRerun() {
        File root = fixture()
        ['1.4.0', '1.4.0', '1.5.0-beta.2', '1.5.0-beta.2'].eachWithIndex { String baseline, int index ->
            def result = runner(root, baseline).build()
            ['fabricMetadata', 'neoforgeMetadata'].each { String task ->
                println "step=${index}; requested=${baseline}; ${task}=${result.task(':' + task).outcome}"
            }
            assertMetadata(root, baseline)
            ['fabricMetadata', 'neoforgeMetadata'].each { String task ->
                assertEquals(index % 2 == 0 ? TaskOutcome.SUCCESS : TaskOutcome.UP_TO_DATE,
                        result.task(':' + task).outcome, "step=${index}, baseline=${baseline}")
            }
        }
    }

    @Test void characterizesExplicitBaselineForFabricAndNeoForge262() {
        File root = fixture()
        def result = runner(root, '1.4.0').build()
        ['fabricMetadata', 'neoforgeMetadata'].each {
            assertEquals(TaskOutcome.SUCCESS, result.task(':' + it).outcome)
        }
        assertMetadata(root, '1.4.0')
    }

    @Test void characterizesConfiguredFunctionalBaselineForFabricAndNeoForge262() {
        File root = fixture()
        runner(root, null).build()
        assertMetadata(root, '1.2.2')
    }
}

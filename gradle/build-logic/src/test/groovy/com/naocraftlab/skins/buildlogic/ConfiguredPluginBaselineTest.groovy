package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.util.zip.ZipFile
import static org.junit.jupiter.api.Assertions.*

class ConfiguredPluginBaselineTest {
    @TempDir File workspace
    final File repository = new File('../..').canonicalFile

    File fixture() {
        File root = new File(workspace, 'project')
        new File(root, 'gradle').mkdirs()
        new File(root, 'gradle/targets.json').bytes = new File(repository, 'gradle/targets.json').bytes
        new File(root, 'gradle/version.properties').text = 'modVersion=1.4.0\n'
        new File(root, 'gradle/plugin-version.properties').text = 'pluginVersion=1.2.0-beta.2\npluginBuild=3\n'
        new File(root, 'settings.gradle').text = "rootProject.name = 'configured-baseline'\n"
        new File(root, 'build.gradle').text = '''
plugins { id 'java'; id 'com.naocraftlab.skins.build-tools' }
java { withSourcesJar() }
def metadata = tasks.register('metadata', nclskinsGenerateMetadataTaskType) {
    catalogFile.set(layout.projectDirectory.file('gradle/targets.json'))
    versionFile.set(layout.projectDirectory.file('gradle/version.properties'))
    targetId.set('forge-1.20.1')
    requiredServerPluginVersion.set(providers.environmentVariable('NCLSKINS_SERVER_PLUGIN_BASELINE').orElse('').map { it.trim() })
    outputDirectory.set(layout.buildDirectory.dir('generated/metadata'))
}
sourceSets.main.resources.srcDir(metadata)
'''
        String conventions = new File(repository, 'gradle/target-conventions.gradle').text
        String registration = conventions.substring(conventions.indexOf('def generateTargetMetadata ='),
                conventions.indexOf('def generateBlockbenchResources ='))
        new File(root, 'metadata.gradle').text = """
def repositoryRoot = layout.projectDirectory
def currentTargetId = 'forge-1.20.1'
def generatedMetadataDirectory = layout.buildDirectory.dir('generated/metadata')
""" + registration.replace("'generateTargetMetadata'", "'metadata'")
        new File(root, 'build.gradle').text = """
plugins { id 'java'; id 'com.naocraftlab.skins.build-tools' }
java { withSourcesJar() }
apply from: 'metadata.gradle'
sourceSets.main.resources.srcDir(tasks.named('metadata'))
"""
        root
    }

    def run(File root, String baseline = null, boolean fail = false) {
        Map env = new HashMap(System.getenv())
        env.remove('NCLSKINS_SERVER_PLUGIN_BASELINE')
        if (baseline != null) env.NCLSKINS_SERVER_PLUGIN_BASELINE = baseline
        def runner = GradleRunner.create().withProjectDir(root).withPluginClasspath()
                .withTestKitDir(new File(workspace, 'test-kit')).withEnvironment(env)
                .withArguments('jar', 'sourcesJar', '--console=plain', '--stacktrace')
        fail ? runner.buildAndFail() : runner.build()
    }

    void assertBaseline(File root, String expected) {
        String path = 'nclskins-server-compatibility.json'
        byte[] generated = new File(root, 'build/generated/metadata/' + path).bytes
        assertEquals(expected, new JsonSlurper().parse(generated).requiredServerPluginVersion)
        assertArrayEquals(generated, new File(root, 'build/resources/main/' + path).bytes)
        ['configured-baseline.jar', 'configured-baseline-sources.jar'].each { name ->
            new ZipFile(new File(root, 'build/libs/' + name)).withCloseable { zip ->
                assertArrayEquals(generated, zip.getInputStream(zip.getEntry(path)).bytes)
            }
        }
    }

    @Test void selectedPluginNotesDoNotRequireModNotesOrCurrentModIdentity() {
        File root = fixture()
        File notes = new File(root, 'PLUGIN_CHANGELOG.md')
        notes.text = '## 1.2.0.3-beta.2\n\nExact fix.\n\n## 1.2.0.2-beta.2\n\nOld.\n'
        Map metadata = ReleaseMetadata.validate(new File(root, 'gradle/version.properties'),
                new File(root, 'CHANGELOG.md'), '1.2.0.3-beta.2')
        assertEquals('plugin-only', metadata.componentKind)
        assertEquals('Exact fix.\n', metadata.serverPlugin.notes)
        assertNull(metadata.notes)
        ['', '## 1.2.0.3-beta.2\n', '## 1.2.0.2-beta.2\n\nOld.\n',
         '## 1.2.0.3-beta.2\n\nOne.\n\n## 1.2.0.3-beta.2\n\nTwo.\n'].each { invalid ->
            notes.text = invalid
            assertThrows(IllegalArgumentException) {
                ReleaseMetadata.validate(new File(root, 'gradle/version.properties'),
                        new File(root, 'CHANGELOG.md'), '1.2.0.3-beta.2')
            }
        }
        assertNull(ServerPluginChangelog.validate(new File(root, 'absent.md'), [publish: false]))
    }

    @Test void releaseProjectionNeedsOnlyConfiguredVersionsNotGitOrProductionHistory() {
        File root = fixture()
        Map catalog = CatalogTools.loadCatalog(root)
        Map mod = ServerPluginReleaseState.compute(root, catalog, '1.4.0')
        assertEquals('mod-only', mod.reason)
        assertFalse(mod.publish)
        assertEquals('1.2.0-beta.2', mod.activeVersion)
        assertEquals('1.2.0.3-beta.2', mod.pluginVersion)
        assertFalse(mod.containsKey('currentFingerprint'))
        Map plugin = ServerPluginReleaseState.compute(root, catalog, '1.2.0.3-beta.2')
        assertEquals('plugin-only', plugin.reason)
        assertTrue(plugin.publish)
        assertEquals('1.2.0-beta.2', plugin.currentVersion)
        assertThrows(IllegalArgumentException) { ServerPluginReleaseState.compute(root, catalog, '1.2.0.1-beta.2') }
        assertThrows(IllegalArgumentException) { ServerPluginReleaseState.compute(root, catalog, '1.2.0.4-beta.2') }
        new File(root, 'gradle/plugin-version.properties').text = 'pluginVersion=1.4.0\npluginBuild=1\n'
        Map joint = ServerPluginReleaseState.compute(root, catalog, '1.4.0')
        assertEquals('joint', joint.reason)
        assertTrue(joint.publish)
        assertEquals('1.4.0', joint.activeVersion)
        new File(root, 'gradle/plugin-version.properties').text = 'pluginVersion=1.4.0\npluginBuild=2\n'
        assertThrows(IllegalArgumentException) { ServerPluginReleaseState.compute(root, catalog, '1.4.0') }
    }

    @Test void overrideAndConfigurationInvalidationPreserveExactArchiveBytes() {
        File root = fixture()
        ['1.1.0', ' 1.1.0 ', '1.3.0-beta.1', '1.3.0-beta.1'].eachWithIndex { value, index ->
            assertEquals(index % 2 == 0 ? TaskOutcome.SUCCESS : TaskOutcome.UP_TO_DATE,
                    run(root, value).task(':metadata').outcome)
            assertBaseline(root, value.trim())
        }
        new File(root, 'release-plan.json').text = '{"digest":"not-an-input"}'
        assertEquals(TaskOutcome.UP_TO_DATE, run(root, '1.3.0-beta.1').task(':metadata').outcome)
        ['1.2.0-beta.2', '1.2.0-beta.2', '1.3.0', '1.3.0'].eachWithIndex { value, index ->
            new File(root, 'gradle/plugin-version.properties').text = "pluginVersion=${value}\npluginBuild=3\n"
            assertEquals(index % 2 == 0 ? TaskOutcome.SUCCESS : TaskOutcome.UP_TO_DATE,
                    run(root).task(':metadata').outcome)
            assertBaseline(root, value)
        }
        byte[] original = new File(root, 'build/generated/metadata/nclskins-server-compatibility.json').bytes
        new File(root, 'gradle/plugin-version.properties').text = 'pluginVersion=broken\npluginBuild=3\n'
        assertTrue(run(root, null, true).output.contains('Invalid plugin version'))
        assertArrayEquals(original, new File(root, 'build/generated/metadata/nclskins-server-compatibility.json').bytes)
        new File(root, 'gradle/plugin-version.properties').delete()
        run(root, null, true)
        assertArrayEquals(original, new File(root, 'build/generated/metadata/nclskins-server-compatibility.json').bytes)
    }

    @Test void selectedNotesAndReleaseOverrideAreStrictPerComponent() {
        File root = fixture()
        File version = new File(root, 'gradle/version.properties')
        File plugin = new File(root, 'gradle/plugin-version.properties')
        File modNotes = new File(root, 'CHANGELOG.md')
        File pluginNotes = new File(root, 'PLUGIN_CHANGELOG.md')
        modNotes.text = '## 1.4.0\n\nMod notes.\n'
        assertEquals('mod-only', ReleaseMetadata.prepare(version, plugin, modNotes, pluginNotes, '1.4.0').componentKind)
        assertThrows(IllegalArgumentException) {
            ReleaseMetadata.prepare(version, plugin, modNotes, pluginNotes, '1.4.0', '1.4.0')
        }
        plugin.text = 'pluginVersion=1.4.0\npluginBuild=1\n'
        assertThrows(IllegalArgumentException) { ReleaseMetadata.prepare(version, plugin, modNotes, pluginNotes, '1.4.0') }
        pluginNotes.text = '## 1.4.0.1\n\nPlugin notes.\n'
        Map joint = ReleaseMetadata.prepare(version, plugin, modNotes, pluginNotes, '1.4.0', ' 1.4.0 ')
        assertEquals('joint', joint.componentKind)
        assertEquals('Mod notes.\n', joint.notes)
        assertEquals('Plugin notes.\n', joint.serverPlugin.notes)
        ['## 1.4.0\n', '## 1.3.0\n\nOld.\n', '## 1.4.0\n\nOne.\n## 1.4.0\n\nTwo.\n'].each { invalid ->
            modNotes.text = invalid
            assertThrows(IllegalArgumentException) { ReleaseMetadata.prepare(version, plugin, modNotes, pluginNotes, '1.4.0') }
        }
        ['v1.4.0', '01.4.0', '1.4.0-beta.0', '1.4.0.0', '1.4.0-beta.1.2', '1.4.0.2-rc.1'].each { invalid ->
            assertThrows(IllegalArgumentException) { ServerPluginVersion.select(invalid, '1.4.0', '1.4.0.1') }
        }
    }

    @Test void configuredBaselineSurvivesModAndBuildChangesThroughBothArchives() {
        File root = fixture()
        assertEquals(TaskOutcome.SUCCESS, run(root).task(':metadata').outcome)
        assertBaseline(root, '1.2.0-beta.2')
        assertEquals(TaskOutcome.UP_TO_DATE, run(root).task(':metadata').outcome)
        new File(root, 'gradle/version.properties').text = 'modVersion=1.5.0\n'
        assertEquals(TaskOutcome.SUCCESS, run(root).task(':metadata').outcome)
        assertBaseline(root, '1.2.0-beta.2')
        assertEquals(TaskOutcome.UP_TO_DATE, run(root).task(':metadata').outcome)
        new File(root, 'gradle/plugin-version.properties').text = 'pluginVersion=1.2.0-beta.2\npluginBuild=4\n'
        assertEquals(TaskOutcome.UP_TO_DATE, run(root).task(':metadata').outcome)
        assertBaseline(root, '1.2.0-beta.2')
        assertEquals(TaskOutcome.UP_TO_DATE, run(root).task(':metadata').outcome)
    }
}

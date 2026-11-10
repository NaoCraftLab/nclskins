package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import java.nio.file.Files

import static org.junit.jupiter.api.Assertions.*

class ReleaseBaselineAlignmentTest {
    private final File workspace = Files.createTempDirectory(
            new File(System.getenv('TMPDIR') ?: System.getProperty('java.io.tmpdir')).toPath(),
            'release-baseline-').toFile()
    private final File repository = new File('../..').canonicalFile

    @AfterEach
    void cleanup() {
        workspace.deleteDir()
    }

    @Test
    void declarativeBaselineFlowsThroughGradleOutputsToMetadataWithoutCandidateWorkflow() {
        File root = fixture()
        runner(root, [:], 'computeServerPluginReleaseState').build()
        Map state = read(new File(root, 'build/release/1.4.0/server-plugin-release-state.json'))
        assertEquals(2, state.schemaVersion)
        assertEquals('1.2.0', state.activeVersion)
        assertFalse(state.containsKey('sourceCommit'))
        Map plan = [requestedVersion: '1.4.0', sourceCommit: ReleaseSelection.git(root, ['rev-parse', 'HEAD']).trim(),
                digest: 'd' * 64, components: [], serverState: state]
        File output = new File(root, 'ci-output')
        Map values = ReleaseProcess.ciOutputs(plan, plan.sourceCommit, false)
        ReleaseProcess.writeOutputs(output, values)
        assertTrue(output.readLines().contains('baseline=1.2.0'))
        runner(root, [NCLSKINS_SERVER_PLUGIN_BASELINE: values.baseline], 'generateMetadata').build()
        assertEquals('1.2.0', read(new File(root, 'build/metadata/nclskins-server-compatibility.json')).requiredServerPluginVersion)
        assertEquals('1.4.0', read(new File(root, 'build/metadata/fabric.mod.json')).version)
    }

    @Test
    void workflowOutputAdmissionRejectsMalformedIdentityWithoutPartialExport() {
        Map plan = [requestedVersion: '1.4.0', sourceCommit: 'a' * 40, digest: 'd' * 64,
                components: [], serverState: [activeVersion: '1.2.0']]
        [null, '', ' ', ' 1.2.0 ', 120, ['1.2.0'], '1.2.0\nINJECTED=value'].eachWithIndex { value, index ->
            File output = new File(workspace, 'output-' + index)
            assertThrows(IllegalStateException) {
                ReleaseProcess.writeOutputs(output, ReleaseProcess.ciOutputs(plan + [serverState: [activeVersion: value]], 'b' * 40, false))
            }
            assertFalse(output.exists())
        }
        assertThrows(IllegalStateException) { ReleaseProcess.ciOutputs(plan + [sourceCommit: 'invalid'], 'b' * 40, false) }
        assertThrows(IllegalStateException) { ReleaseProcess.ciOutputs(plan + [digest: 'invalid'], 'b' * 40, false) }
    }

    @Test
    void computeTaskRejectsInvalidMandatoryBaselineBeforeWritingState() {
        def project = ProjectBuilder.builder().withProjectDir(workspace).build()
        File catalog = new File(workspace, 'targets.json')
        catalog.text = '{}'
        def task = project.tasks.create('computeInvalidState', ComputeServerPluginReleaseStateTask)
        task.repositoryDirectory.set(workspace)
        task.catalogFile.set(catalog)
        task.releaseTag.set('1.4.0')
        File output = new File(workspace, 'state.json')
        task.stateFile.set(output)
        try {
            [null, '', ' ', ' 1.0.0 ', 100, 'v1.0.0', '1.0.0.2', '01.0.0',
             '1.0.0-beta.0', '1.0.0-rc.1', '1.0.0+build'].each { Object invalid ->
                ServerPluginReleaseState.metaClass.'static'.compute = { File root, Map data, String version ->
                    [activeVersion: invalid]
                }
                assertEquals('Invalid mandatory server plugin active baseline',
                        assertThrows(IllegalStateException) { task.compute() }.message)
                assertFalse(output.exists())
            }
        } finally {
            GroovySystem.metaClassRegistry.removeMetaClass(ServerPluginReleaseState)
        }
    }

    @Test
    void resumedOutputsKeepExactBaselineAndIdentityWithoutBuildMatrix() {
        Map plan = [requestedVersion: '1.2.0.2', sourceCommit: 'a' * 40, digest: 'd' * 64,
                components: [[id: 'server-plugin', build: true]], serverState: [activeVersion: '1.2.0']]
        Map values = ReleaseProcess.ciOutputs(plan, 'b' * 40, true)
        assertEquals('1.2.0.2', values.version)
        assertEquals('1.2.0', values.baseline)
        assertEquals('false', values.build)
        assertEquals('{"include":[]}', values.matrix)
        assertEquals('d' * 64, values.digest)
        assertEquals('b' * 40, values.catalog)
    }

    private GradleRunner runner(File root, Map<String, String> values, String task) {
        Map<String, String> environment = new HashMap<>(System.getenv())
        ['VERSION', 'NCLSKINS_SERVER_PLUGIN_BASELINE'].each { environment.remove(it) }
        environment.putAll(values)
        GradleRunner.create().withProjectDir(root).withPluginClasspath()
                .withTestKitDir(new File(workspace, 'test-kit')).withEnvironment(environment)
                .withArguments(task, '--console=plain', '--stacktrace')
    }

    private static Map read(File file) {
        new JsonSlurper().parse(file) as Map
    }

    private File fixture() {
        File root = new File(workspace, 'project')
        new File(root, 'gradle').mkdirs()
        new File(root, '.gitignore').text = '.gradle/\nbuild/\n'
        new File(root, 'gradle/targets.json').bytes = new File(repository, 'gradle/targets.json').bytes
        new File(root, 'gradle/version.properties').text = 'modVersion=1.0.0\n'
        new File(root, 'gradle/plugin-version.properties').text = 'pluginVersion=1.0.0\npluginBuild=1\n'
        new File(root, 'settings.gradle').text = "rootProject.name = 'release-baseline-fixture'\ninclude 'toolchains'\n"
        new File(root, 'toolchains').mkdirs()
        new File(root, 'toolchains/build.gradle').text = "plugins { id 'java' }\n"
        new File(root, 'build.gradle').text = '''
import com.naocraftlab.skins.buildlogic.*
plugins { id 'com.naocraftlab.skins.build-tools' }
tasks.register('computeServerPluginReleaseState', ComputeServerPluginReleaseStateTask) {
    repositoryDirectory.set(layout.projectDirectory)
    catalogFile.set(layout.projectDirectory.file('gradle/targets.json'))
    releaseTag.set('1.4.0')
    stateFile.set(layout.buildDirectory.file('release/1.4.0/server-plugin-release-state.json'))
    outputs.upToDateWhen { false }
}
tasks.register('planRelease') { doLast { throw new GradleException('REMOTE PLAN MUST NOT RUN') } }
tasks.register('generateMetadata', GenerateMetadataTask) {
    catalogFile.set(layout.projectDirectory.file('gradle/targets.json'))
    versionFile.set(layout.projectDirectory.file('gradle/version.properties'))
    targetId.set('fabric-26.2')
    requiredServerPluginVersion.set(providers.environmentVariable('NCLSKINS_SERVER_PLUGIN_BASELINE')
            .orElse('').map { MetadataRenderer.effectiveRequiredServerPluginVersion(
                CatalogTools.loadVersion(rootDir), it) })
    outputDirectory.set(layout.buildDirectory.dir('metadata'))
}
'''
        ReleaseSelection.git(root, ['init', '-q'])
        commit(root, 'Initial baseline')
        ReleaseSelection.git(root, ['tag', '1.0.0'])
        new File(root, 'gradle/version.properties').text = 'modVersion=1.4.0\n'
        new File(root, 'gradle/plugin-version.properties').text = 'pluginVersion=1.2.0\npluginBuild=2\n'
        commit(root, 'Pre-tag candidate')
        root
    }

    private static void commit(File root, String message) {
        ReleaseSelection.git(root, ['add', '.'])
        ReleaseSelection.git(root, ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                                  '-c', 'commit.gpgsign=false', 'commit', '-qm', message])
    }
}

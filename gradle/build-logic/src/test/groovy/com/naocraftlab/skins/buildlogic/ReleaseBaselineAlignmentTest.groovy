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
    void computedPreTagStateFlowsThroughActualCandidateStepToLaterGradleInvocations() {
        File root = fixture()
        def computed = runner(root, [:], 'computeServerPluginReleaseState').build()
        assertFalse(computed.output.contains('REMOTE PLAN MUST NOT RUN'))
        File stateFile = new File(root, 'build/release/1.4.0/server-plugin-release-state.json')
        Map state = read(stateFile)
        assertEquals('1.4.0', state.currentVersion)
        assertEquals('1.2.0.2', state.pluginVersion)
        assertEquals('1.0.0', state.activeVersion)
        assertEquals('unchanged', state.reason)
        assertEquals(ReleaseSelection.git(root, ['rev-parse', 'HEAD']).trim(), state.sourceCommit)
        assertEquals('', ReleaseSelection.git(root, ['tag', '--list', '1.4.0']).trim())
        String block = candidateBlock()
        File environmentFile = new File(root, 'github-env')
        File summary = new File(root, 'summary')
        Map<String, String> base = stepEnvironment(root, environmentFile, summary)
        assertFalse(base.containsKey('VERSION'))
        Map<String, String> step = new LinkedHashMap<>(base) + [VERSION: '1.4.0']
        assertEquals(0, shell(root, block, step).exit)
        assertFalse(base.containsKey('NCLSKINS_SERVER_PLUGIN_BASELINE'))
        assertEquals('NCLSKINS_SERVER_PLUGIN_BASELINE=1.0.0\n', environmentFile.text)
        assertTrue(summary.text.contains('Provisional'))
        assertTrue(summary.text.contains(state.sourceCommit))
        assertTrue(summary.text.contains('1.0.0'))
        Map<String, String> following = [:]
        environmentFile.readLines().each { String line ->
            int equals = line.indexOf('=')
            following[line.substring(0, equals)] = line.substring(equals + 1)
        }
        assertFalse(following.containsKey('VERSION'))
        ['captureBaseline', 'generateMetadata'].each { String task ->
            runner(root, following, task).build()
        }
        assertEquals('1.0.0', read(new File(root, 'build/capture.json')).requiredServerPluginVersion)
        assertEquals('1.0.0', read(new File(root, 'build/metadata/nclskins-server-compatibility.json')).requiredServerPluginVersion)
        assertEquals('1.4.0', read(new File(root, 'build/metadata/fabric.mod.json')).version)
        println "W1/W2 mod=1.4.0 functional=1.2.0 artifact=${state.pluginVersion} active=${state.activeVersion}; source=${state.sourceCommit}"
        println "W2 subsequent capture=${read(new File(root, 'build/capture.json')).requiredServerPluginVersion}; generated=1.0.0; descriptor=1.4.0"
        Map catalog = read(new File(root, 'gradle/targets.json'))
        catalog.serverPlugin.matrixId = 'changed-functional-input'
        new File(root, 'gradle/targets.json').text = CatalogTools.json(catalog)
        new File(root, 'gradle/plugin-version.properties').text = 'pluginVersion=1.3.0\npluginBuild=7\n'
        runner(root, [:], 'computeServerPluginReleaseState').build()
        assertEquals('1.3.0', read(stateFile).activeVersion)
        assertEquals('1.3.0.7', read(stateFile).pluginVersion)
        assertNotEquals(following.NCLSKINS_SERVER_PLUGIN_BASELINE, read(stateFile).activeVersion)
    }

    @Test
    void candidateIdentityExtractionRejectsMissingMalformedAndStaleState() {
        File root = fixture()
        runner(root, [:], 'computeServerPluginReleaseState').build()
        File file = new File(root, 'build/release/1.4.0/server-plugin-release-state.json')
        Map valid = read(file)
        String block = candidateBlock()
        List<Object> invalid = [null, '{broken', '[]', 'null', [:],
                valid + [schemaVersion: 2], valid + [sealed: false], valid + [sealed: 'true'],
                valid + [currentVersion: '1.4.1'], valid + [sourceCommit: '0' * 40],
                valid + [activeVersion: null], valid + [activeVersion: ''],
                valid + [activeVersion: '   '], valid + [activeVersion: ' 1.0.0 '],
                valid + [activeVersion: 100], valid + [activeVersion: ['1.0.0']],
                valid + [activeVersion: '1.0.0\nINJECTED=value']]
        invalid.eachWithIndex { Object value, int index ->
            if (value == null) assertTrue(file.delete())
            else file.text = value instanceof String ? value : CatalogTools.json(value)
            File env = new File(root, "invalid-${index}.env")
            File summary = new File(root, "invalid-${index}.summary")
            Map result = shell(root, block, stepEnvironment(root, env, summary) + [VERSION: '1.4.0'])
            assertNotEquals(0, result.exit, "invalid state ${index}: ${result.output}")
            assertFalse(env.exists() && env.length() > 0, "invalid state ${index} exported a baseline")
        }
        file.text = CatalogTools.json(valid)
        File env = new File(root, 'valid.env')
        assertEquals(0, shell(root, block, stepEnvironment(root, env, new File(root, 'valid.summary')) +
                [VERSION: '1.4.0']).exit)
        assertEquals('NCLSKINS_SERVER_PLUGIN_BASELINE=1.0.0\n', env.text)
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
    void pinnedWorkflowBaselineOutputRejectsAbsentOrNonStringValues() {
        File root = new File(workspace, 'pinned')
        File plan = new File(root, 'build/release-plan/release-plan.json')
        plan.parentFile.mkdirs()
        String workflow = new File(repository, '.github/workflows/release.yml').text
        int start = workflow.indexOf('            plan=build/release-plan/release-plan.json\n')
        assertTrue(start >= 0)
        int end = workflow.indexOf('          fi\n', start)
        String block = 'set -euo pipefail\n' + workflow.substring(start, end).readLines().collect {
            it.substring(12)
        }.join('\n') + '\n'
        Map valid = [digest: 'd' * 64, sourceCommit: 'a' * 40, components: [],
                     serverState: [activeVersion: '1.2.0']]
        [valid, valid + [serverState: [:]], valid + [serverState: [activeVersion: null]],
         valid + [serverState: [activeVersion: '']], valid + [serverState: [activeVersion: ' ']],
         valid + [serverState: [activeVersion: ' 1.2.0 ']],
         valid + [serverState: [activeVersion: 120]],
         valid + [serverState: [activeVersion: '1.2.0\nINJECTED=value']]].eachWithIndex { Map state, int index ->
            plan.text = CatalogTools.json(state)
            File output = new File(root, "output-${index}")
            Map result = shell(root, block, [VERSION: '1.4.0', GITHUB_OUTPUT: output.absolutePath])
            if (index == 0) {
                assertEquals(0, result.exit, result.output.toString())
                assertTrue(output.readLines().contains('baseline=1.2.0'))
            } else {
                assertNotEquals(0, result.exit, "invalid pinned baseline ${index}")
                assertFalse(output.exists() && output.readLines().any { it.startsWith('baseline=') })
            }
        }
    }

    @Test
    void planOnlyProvenanceChangesLeaveLocalReceiptReusableAtTheSameBaseline() {
        File root = fixture()
        runner(root, [NCLSKINS_SERVER_PLUGIN_BASELINE: '1.0.0'], 'captureBaseline').build()
        Map environment = read(new File(root, 'build/capture.json'))
        assertEquals('1.0.0', environment.requiredServerPluginVersion)
        Map catalog = [targets: [[id: 'example', path: 'fixture', artifact: [file: 'mod.jar']]]]
        File artifact = new File(root, 'fixture/build/libs/mod.jar')
        artifact.parentFile.mkdirs()
        artifact.text = 'fixture artifact'
        String source = ReleaseSelection.git(root, ['rev-parse', 'HEAD']).trim()
        String tag = ReleaseSelection.git(root, ['rev-parse', '1.0.0^{commit}']).trim()
        Map data = [schemaVersion: 1, version: '1.4.0', sourceCommit: source, tagCommit: tag,
                    components: [], buildTargetIds: [], serverState: [schemaVersion: 1, sealed: true,
                        currentVersion: '1.4.0', sourceCommit: tag, activeVersion: '1.0.0']]
        File plan = new File(root, 'build/release-plan/first.json')
        plan.parentFile.mkdirs()
        plan.text = CatalogTools.json(ReleasePlan.seal(data))
        String digest = ReleasePlan.load(plan).digest
        Map before = BuildReceipt.snapshot(root, catalog, environment)
        BuildReceipt.publish(root, catalog, before, before, 'incremental')
        String receiptHash = BuildReceipt.hash(BuildReceipt.location(root))
        File changed = new File(root, 'build/release-plan/second.json')
        changed.text = CatalogTools.json(ReleasePlan.seal(data + [catalogCommit: tag]))
        assertNotEquals(digest, ReleasePlan.load(changed).digest)
        Map after = BuildReceipt.snapshot(root, catalog, environment)
        assertEquals(before, after)
        assertTrue(BuildReceipt.status(root, catalog, after).reusable)
        assertEquals(receiptHash, BuildReceipt.hash(BuildReceipt.location(root)))
        assertFalse(BuildReceipt.status(root, catalog, after +
                [environment: environment + [requiredServerPluginVersion: '1.3.0']]).reusable)
    }

    private String candidateBlock() {
        String workflow = new File(repository, '.github/workflows/release-candidate.yml').text
        String name = '      - name: Align provisional server plugin baseline\n'
        int start = workflow.indexOf(name)
        assertTrue(start >= 0, 'Missing provisional baseline alignment step')
        String step = workflow.substring(start + name.length()).split('(?m)^      - ', 2)[0]
        int run = step.indexOf('        run: |\n')
        assertTrue(run >= 0)
        step.substring(run + '        run: |\n'.length()).readLines().collect {
            assertTrue(it.isEmpty() || it.startsWith('          '))
            it.isEmpty() ? '' : it.substring(10)
        }.join('\n') + '\n'
    }

    private Map<String, String> stepEnvironment(File root, File env, File summary) {
        [GITHUB_ENV: env.absolutePath, GITHUB_STEP_SUMMARY: summary.absolutePath,
         GITHUB_SHA: ReleaseSelection.git(root, ['rev-parse', 'HEAD']).trim()]
    }

    private Map shell(File root, String block, Map<String, String> values) {
        ProcessBuilder builder = new ProcessBuilder('bash', '-c', block).directory(root).redirectErrorStream(true)
        ['VERSION', 'NCLSKINS_SERVER_PLUGIN_BASELINE'].each { builder.environment().remove(it) }
        builder.environment().putAll(values)
        Process process = builder.start()
        String output = process.inputStream.getText('UTF-8')
        [exit: process.waitFor(), output: output]
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
tasks.register('captureBaseline') {
    doLast {
        file('build').mkdirs()
        file('build/capture.json').text = CatalogTools.json(ReceiptEnvironment.capture(project,
                CatalogTools.loadCatalog(rootDir)))
    }
}
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

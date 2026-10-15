package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*

class ReceiptEnvironmentTest {
    @TempDir File workspace

    File fixture() {
        File root = new File(workspace, 'project')
        new File(root, 'gradle').mkdirs()
        assertEquals(0, new ProcessBuilder('git', 'init', '-q').directory(root).start().waitFor())
        new File(root, '.gitignore').text = 'build/\n.gradle/\n'
        new File(root, 'settings.gradle').text = "rootProject.name = 'receipt-fixture'\ninclude 'toolchains'\n"
        new File(root, 'toolchains').mkdirs()
        new File(root, 'toolchains/build.gradle').text = "plugins { id 'java' }\n"
        new File(root, 'gradle/version.properties').text = 'modVersion=1.2.2\n'
        Map catalog = [targets: [[id: 'example', path: 'target', java: [buildJdk: 17],
                                  artifact: [file: 'mod.jar']]], serverPlugin: [packaging: [buildJdk: 17]]]
        new File(root, 'gradle/targets.json').text = JsonOutput.toJson(catalog)
        File artifact = new File(root, 'target/build/libs/mod.jar')
        artifact.parentFile.mkdirs()
        artifact.text = 'fixture artifact'
        new File(root, 'build.gradle').text = '''
import com.naocraftlab.skins.buildlogic.BuildReceipt
import com.naocraftlab.skins.buildlogic.ReceiptEnvironment
import groovy.json.JsonOutput
plugins { id 'com.naocraftlab.skins.build-tools' }
def catalog = nclskinsCatalogTools.loadJson(file('gradle/targets.json').toPath())
def snapshot = {
    BuildReceipt.snapshot(rootDir, catalog, ReceiptEnvironment.capture(project, catalog))
}
tasks.register('captureReceipt') {
    doLast {
        file('build').mkdirs()
        file('build/capture.json').text = JsonOutput.toJson(snapshot())
    }
}
tasks.register('downloadFixtureDependency') {
    doLast {
        File dependency = new File(gradle.gradleUserHomeDir,
                'caches/modules-2/files-2.1/fixture/downloaded.jar')
        dependency.parentFile.mkdirs()
        dependency.text = 'fixture dependency'
    }
}
tasks.register('publishReceipt') {
    doLast {
        Map current = snapshot()
        BuildReceipt.publish(rootDir, catalog, current, current, 'fullCheck')
    }
}
tasks.register('statusReceipt') {
    doLast {
        Map result
        try { result = BuildReceipt.status(rootDir, catalog, snapshot()) }
        catch (Exception ignored) { result = [reusable: false, reason: 'unsupported-build-settings-or-inputs'] }
        file('build').mkdirs()
        file('build/status.json').text = JsonOutput.toJson(result)
    }
}
'''
        root
    }

    GradleRunner runner(File root, String baseline, String task) {
        Map<String, String> environment = new HashMap<>(System.getenv())
        environment.remove('NCLSKINS_SERVER_PLUGIN_BASELINE')
        if (baseline != null) environment.NCLSKINS_SERVER_PLUGIN_BASELINE = baseline
        GradleRunner.create().withProjectDir(root).withPluginClasspath()
                .withTestKitDir(new File(workspace, 'test-kit'))
                .withEnvironment(environment).withArguments(task, '--console=plain', '--stacktrace')
    }

    Map read(File root, String path) {
        new JsonSlurper().parse(new File(root, path)) as Map
    }

    @Test void realCaptureSealsEffectiveBaselineAndStatusRemainsReadOnly() {
        File root = fixture()
        runner(root, '1.4.0', 'captureReceipt').build()
        assertEquals('1.4.0', read(root, 'build/capture.json').environment.requiredServerPluginVersion)
        runner(root, '1.4.0', 'publishReceipt').build()
        File receipt = BuildReceipt.location(root)
        String original = BuildReceipt.hash(receipt)
        ['1.4.0', '  1.4.0  '].each { String baseline ->
            runner(root, baseline, 'statusReceipt').build()
            Map status = read(root, 'build/status.json')
            assertTrue(status.reusable, status.toString())
            assertEquals('fullCheck', status.receipt.verificationLevel)
            assertEquals(original, BuildReceipt.hash(receipt))
        }
        runner(root, '1.5.0-beta.2', 'statusReceipt').build()
        assertFalse(read(root, 'build/status.json').reusable)
        assertEquals(original, BuildReceipt.hash(receipt))
        runner(root, 'v1.4.0', 'statusReceipt').build()
        assertFalse(read(root, 'build/status.json').reusable)
        assertEquals(original, BuildReceipt.hash(receipt))
        runner(root, '1.4.0.1', 'publishReceipt').buildAndFail()
        assertEquals(original, BuildReceipt.hash(receipt))
        [null, '', '   ', '1.2.2'].each { String baseline ->
            runner(root, baseline, 'captureReceipt').build()
            assertEquals('1.2.2', read(root, 'build/capture.json').environment.requiredServerPluginVersion)
        }
    }

    @Test void legacyAndMalformedRecordedBaselinesAreReadOnlyStale() {
        File root = fixture()
        runner(root, '1.4.0', 'publishReceipt').build()
        runner(root, '1.4.0', 'statusReceipt').build()
        assertTrue(read(root, 'build/status.json').reusable)
        File receipt = BuildReceipt.location(root)
        String original = receipt.text
        ['missing', null, '', '   ', ' 1.4.0 ', 'v1.4.0', '1.4.0.1', 140].each { Object value ->
            Map record = new JsonSlurper().parseText(original) as Map
            if (value == 'missing') record.environment.remove('requiredServerPluginVersion')
            else record.environment.requiredServerPluginVersion = value
            receipt.text = JsonOutput.toJson(record)
            String mutated = BuildReceipt.hash(receipt)
            runner(root, '1.4.0', 'statusReceipt').build()
            assertFalse(read(root, 'build/status.json').reusable, "recorded baseline=${value}")
            assertEquals(mutated, BuildReceipt.hash(receipt))
        }
    }

    @Test void unsupportedCaptureSettingsRemainRejected() {
        File root = fixture()
        runner(root, '1.4.0', 'publishReceipt').build()
        String original = BuildReceipt.hash(BuildReceipt.location(root))
        [['statusReceipt', '--offline'], ['statusReceipt', '-Punsupported=fixture']].each { List<String> arguments ->
            runner(root, '1.4.0', 'statusReceipt').withArguments(arguments).build()
            assertEquals([reusable: false, reason: 'unsupported-build-settings-or-inputs'],
                    read(root, 'build/status.json'))
            assertEquals(original, BuildReceipt.hash(BuildReceipt.location(root)))
        }
    }

    @Test void realCapturesRejectDriftEvenWithAdditiveCacheAndAcceptEquivalentSpelling() {
        File root = fixture()
        runner(root, '1.4.0', 'captureReceipt').build()
        Map before = read(root, 'build/capture.json')
        assertEquals('1.4.0', before.environment.requiredServerPluginVersion)
        println "capture toolchains=${before.environment.toolchains.collect { it.home }}"
        Map catalog = read(root, 'gradle/targets.json')
        runner(root, '1.5.0-beta.2', 'captureReceipt').build()
        Map baselineOnly = read(root, 'build/capture.json')
        assertEquals(before.environment.dependencyCache, baselineOnly.environment.dependencyCache)
        assertEquals('1.5.0-beta.2', baselineOnly.environment.requiredServerPluginVersion)
        IllegalStateException baselineDrift = assertThrows(IllegalStateException) {
            BuildReceipt.publish(root, catalog, before, baselineOnly, 'fullCheck')
        }
        assertEquals('Build inputs changed during verification', baselineDrift.message)
        assertFalse(BuildReceipt.location(root).exists())
        runner(root, '1.5.0-beta.2', 'captureReceipt')
                .withArguments('downloadFixtureDependency', 'captureReceipt', '--console=plain', '--stacktrace').build()
        Map changed = read(root, 'build/capture.json')
        assertTrue(changed.environment.dependencyCache.containsKey('fixture/downloaded.jar'))
        IllegalStateException combinedDrift = assertThrows(IllegalStateException) {
            BuildReceipt.publish(root, catalog, before, changed, 'fullCheck')
        }
        assertEquals('Build inputs changed during verification', combinedDrift.message)
        assertFalse(BuildReceipt.location(root).exists())
        runner(root, '  1.4.0  ', 'captureReceipt').build()
        Map equivalent = read(root, 'build/capture.json')
        assertEquals(changed.environment.dependencyCache, equivalent.environment.dependencyCache)
        BuildReceipt.publish(root, catalog, before, equivalent, 'fullCheck')
        assertTrue(BuildReceipt.status(root, catalog, equivalent).reusable)
        assertEquals(equivalent.environment.dependencyCache,
                read(root, 'build/verification/prism-build.json').environment.dependencyCache)
    }
}

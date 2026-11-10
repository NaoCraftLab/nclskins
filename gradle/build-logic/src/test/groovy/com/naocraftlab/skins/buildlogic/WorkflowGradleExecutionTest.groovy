package com.naocraftlab.skins.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import static org.junit.jupiter.api.Assertions.*

final class WorkflowGradleExecutionTest {
    @TempDir File root

    @Test void gradlePagesUsesOnlyPinnedSourceAndBoundedInventoryWithoutAssetPrerequisites() {
        new File(root, 'settings.gradle').text = "rootProject.name='pages-fixture'\n"
        File repository = new File(System.getProperty('repositoryRoot', '../..'))
        new File(root, 'gradle').mkdirs()
        new File(root, 'gradle/targets.json').text = new File(repository, 'gradle/targets.json').text
        String registration = new File(repository, 'build.gradle').text
        int start = registration.indexOf("tasks.register('generateUpdateCatalog', GenerateUpdateCatalogTask) {")
        int end = registration.indexOf('\n}\n', start) + 3
        assertTrue(start >= 0 && end > start)
        registration = registration.substring(start, end)
        new File(root, 'build.gradle').text = '''
import com.naocraftlab.skins.buildlogic.*
plugins { id 'com.naocraftlab.skins.build-tools' }
def requestedUpdateCatalogInventory = null
def requestedUpdateCatalogOutput = 'build/site'
''' + registration + '''
tasks.named('generateUpdateCatalog') {
    sourceCommit.set(providers.gradleProperty('source'))
    callerCatalogCommit.set(providers.gradleProperty('source'))
    eventName.set('push')
    eventRef.set('refs/tags/1.0.0')
    branchTransport = { String branch -> providers.gradleProperty('source').get() }
    inventoryTransport = { int page ->
        file('reads').append(page.toString() + '\\n')
        [status: 200, headers: [:], body: '[]'.bytes]
    }
}
tasks.register('test') { doLast { throw new GradleException('QUALITY MUST NOT RUN') } }
tasks.register('assemble') { doLast { throw new GradleException('ASSETS MUST NOT RUN') } }
'''
        ReleaseSelection.git(root, ['init', '-q', '-b', 'main'])
        ReleaseSelection.git(root, ['add', '.'])
        ReleaseSelection.git(root, ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                '-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture'])
        String sha = ReleaseSelection.git(root, ['rev-parse', 'HEAD']).trim()
        def result = GradleRunner.create().withProjectDir(root).withPluginClasspath()
                .withEnvironment(new LinkedHashMap<>(System.getenv()) + [NCLSKINS_CATALOG_WORKFLOW: 'true'])
                .withArguments('generateUpdateCatalog', '-Psource=' + sha, '--console=plain').build()
        assertEquals([':generateUpdateCatalog'], result.tasks*.path)
        assertFalse(new File(root, '.agents').exists())
        assertFalse(new File(root, 'openspec').exists())
        assertEquals('1\n', new File(root, 'reads').text)
        assertTrue(new File(root, 'build/site/updates/v1/catalog.json').isFile())
        assertFalse(new File(root, 'build/libs').exists())
    }
}

package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*

class ReleaseReadinessTest {
    @TempDir File workspace
    private final File repository = new File('../..').canonicalFile

    @Test
    void cpmOptionalRelationsReachBothMarketplacePayloadsForEveryEligibleTarget() {
        Map catalog = CatalogTools.loadCatalog(repository)
        CatalogTools.releaseTargets(catalog).each { Map target ->
            Map publication = AssembleReleaseTask.publicationTarget(catalog, target,
                    [version: '1.3.0', channel: 'release'],
                    [file: 'mod.jar', sha1: 'production'],
                    [file: 'mod-sources.jar', sha1: 'sources'])
            Map manifest = [platforms: catalog.mod.platforms, releaseNotes: [text: 'Release notes']]
            Map modrinth = PublicationSupport.modrinthMetadata(manifest, publication)
            assertEquals([[project_id: 'h1E7sQNL', dependency_type: 'optional']] as Set,
                    (modrinth.dependencies as List).findAll { it.project_id == 'h1E7sQNL' } as Set,
                    target.id.toString())
            String slug = target.loader.id == 'fabric' ? 'custom-player-models-fabric' : 'custom-player-models'
            int projectId = target.loader.id == 'fabric' ? 440597 : 439870
            assertEquals([[projectId: projectId, slug: slug, type: 'optional']],
                    publication.dependencies.curseforge.findAll { it.projectId in [440597, 439870] },
                    target.id.toString())
            Map curseforge = PublicationSupport.curseForgeMetadata(manifest, publication)
            assertEquals([[slug: slug, projectID: projectId, type: 'optionalDependency']],
                    curseforge.relations.projects.findAll {
                        it.slug in ['custom-player-models-fabric', 'custom-player-models']
                    }, target.id.toString())
        }
    }


    @Test
    void compiledTargetClassesInvalidateTheActualBuildLogicInputDeclaration() {
        File root = new File(workspace, 'compiled-input-fixture')
        File catalog = new File(root, 'gradle/targets.json')
        catalog.parentFile.mkdirs()
        catalog.text = JsonOutput.toJson([targets: [[id: 'fixture-a', path: 'targets/a']]])
        File buildLogic = new File(root, 'gradle/build-logic')
        buildLogic.mkdirs()
        new File(buildLogic, 'settings.gradle').text = "rootProject.name = 'compiled-input-fixture'\n"
        String source = new File(repository, 'gradle/build-logic/build.gradle').text
        int start = source.indexOf('tasks.withType(Test).configureEach {')
        int end = source.indexOf('\n}\n', start)
        assertTrue(start >= 0 && end > start)
        String declaration = source.substring(start, end + 3)
        new File(buildLogic, 'build.gradle').text = "plugins { id 'java' }\n" + declaration + '''
tasks.named('test', Test) {
    classpath = files()
    testClassesDirs = files()
}
tasks.register('releaseInputProbe') {
    inputs.files(tasks.named('test').map { it.inputs.files })
    outputs.file(layout.buildDirectory.file('probe.txt'))
    doLast { outputs.files.singleFile.text = 'verified' }
}
'''
        Closure result = {
            GradleRunner.create().withProjectDir(buildLogic)
                    .withTestKitDir(new File(workspace, 'compiled-test-kit'))
                    .withArguments('releaseInputProbe', '--console=plain').build()
                    .task(':releaseInputProbe').outcome
        }
        assertEquals(TaskOutcome.SUCCESS, result())
        assertEquals(TaskOutcome.UP_TO_DATE, result())
        File firstClasses = new File(root, 'targets/a/build/classes/java/main')
        firstClasses.mkdirs()
        File original = new File(firstClasses, 'Fixture.class')
        original.bytes = [0x01] as byte[]
        assertEquals(TaskOutcome.SUCCESS, result(), 'new catalog target class output')
        assertEquals(TaskOutcome.UP_TO_DATE, result(), 'unchanged catalog target class output')
        original.bytes = [0x02] as byte[]
        assertEquals(TaskOutcome.SUCCESS, result(), 'changed class bytes')
        original.delete()
        assertEquals(TaskOutcome.SUCCESS, result(), 'removed class output')
        File clientClass = new File(root, 'targets/a/build/classes/java/client/ClientFixture.class')
        clientClass.parentFile.mkdirs()
        clientClass.bytes = [0x04] as byte[]
        assertEquals(TaskOutcome.SUCCESS, result(), 'split client source-set class output')
        assertEquals(TaskOutcome.UP_TO_DATE, result(), 'unchanged split client class output')
        clientClass.bytes = [0x05] as byte[]
        assertEquals(TaskOutcome.SUCCESS, result(), 'changed split client class bytes')
        clientClass.delete()
        assertEquals(TaskOutcome.SUCCESS, result(), 'removed split client class output')
        catalog.text = JsonOutput.toJson([targets: [
                [id: 'fixture-a', path: 'targets/a'], [id: 'fixture-b', path: 'targets/b']]])
        File secondClasses = new File(root, 'targets/b/build/classes/java/main')
        secondClasses.mkdirs()
        new File(secondClasses, 'Another.class').bytes = [0x03] as byte[]
        assertEquals(TaskOutcome.SUCCESS, result(), 'additional catalog target class output')
        assertEquals(TaskOutcome.UP_TO_DATE, result(), 'unchanged expanded target roster')
        new File(root, 'targets/b/build/cache-marker').text = 'not a class input'
        assertEquals(TaskOutcome.UP_TO_DATE, result(), 'non-class build output')
    }

    @Test
    void releaseFilesInvalidateTheActualBuildLogicInputDeclaration() {
        List<String> required = ['gradle/version.properties', 'gradle/plugin-version.properties',
                'gradle/targets.json', 'build.gradle', 'CHANGELOG.md', 'PLUGIN_CHANGELOG.md',
                '.github/workflows/release.yml',
                '.github/workflows/publish-update-catalog.yml']
        File root = new File(workspace, 'input-fixture')
        required.each { String relative ->
            File file = new File(root, relative)
            file.parentFile.mkdirs()
            file.text = relative == 'gradle/targets.json'
                    ? JsonOutput.toJson([targets: [[id: 'fixture-target', path: 'targets/fixture']]])
                    : 'initial\n'
        }
        File buildLogic = new File(root, 'gradle/build-logic')
        buildLogic.mkdirs()
        new File(buildLogic, 'settings.gradle').text = "rootProject.name = 'release-input-fixture'\n"
        String source = new File(repository, 'gradle/build-logic/build.gradle').text
        int start = source.indexOf('tasks.withType(Test).configureEach {')
        int end = source.indexOf('\n}\n', start)
        assertTrue(start >= 0 && end > start)
        String declaration = source.substring(start, end + 3)
        new File(buildLogic, 'build.gradle').text = "plugins { id 'java' }\n" + declaration + '''
tasks.named('test', Test) {
    classpath = files()
    testClassesDirs = files()
}
tasks.register('releaseInputProbe') {
    inputs.files(tasks.named('test').map { it.inputs.files })
    outputs.file(layout.buildDirectory.file('probe.txt'))
    doLast { outputs.files.singleFile.text = 'verified' }
}
'''
        Closure result = {
            GradleRunner.create().withProjectDir(buildLogic)
                    .withTestKitDir(new File(workspace, 'test-kit'))
                    .withArguments('releaseInputProbe', '--console=plain').build()
                    .task(':releaseInputProbe').outcome
        }
        assertEquals(TaskOutcome.SUCCESS, result())
        assertEquals(TaskOutcome.UP_TO_DATE, result())
        required.each { String relative ->
            File changed = new File(root, relative)
            if (relative == 'gradle/targets.json') {
                changed.text = JsonOutput.toJson([targets: [[id: 'fixture-target', path: 'targets/fixture'],
                                                            [id: 'fixture-target-2', path: 'targets/fixture-2']]])
            } else {
                changed.append('changed\n')
            }
            assertEquals(TaskOutcome.SUCCESS, result(), relative)
            assertEquals(TaskOutcome.UP_TO_DATE, result(), relative)
        }
    }

    @Test
    void pagesInventoryRejectsAnUnobservedSecondPageBeforeGeneration() {
        assertEquals(0, snapshotReleases(99, 1).exit)
        assertEquals(0, snapshotReleases(100, 0).exit)
        Map overflow = snapshotReleases(100, 1)
        assertNotEquals(0, overflow.exit)
        assertTrue(overflow.output.contains('release inventory exceeds 100'))
    }

    private Map snapshotReleases(int firstCount, int secondCount) {
        List<Integer> pages = []
        try {
            List<Map> inventory = GithubReleaseInventory.snapshot { int page ->
                pages.add(page)
                int count = page == 1 ? firstCount : secondCount
                [status: 200, headers: [:], body: JsonOutput.toJson(
                        (0..<count).collect { [id: (page - 1) * 100 + it] }).getBytes('UTF-8')]
            }
            assertEquals(firstCount, inventory.size())
            assertEquals(firstCount == 100 ? [1, 2] : [1], pages)
            [exit: 0, output: '']
        } catch (IllegalArgumentException rejected) {
            assertEquals([1, 2], pages)
            [exit: 1, output: rejected.message]
        }
    }
}

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
    void cpmPublicationDeclarationsMustRemainOptionalAndLoaderSpecific() {
        Map catalog = CatalogTools.loadCatalog(repository)
        List<String> validErrors = []
        CatalogTools.validatePublicationDependencies(catalog, validErrors)
        assertEquals([], validErrors)
        ['cpm_fabric', 'cpm_forge'].each { String id ->
            assertTrue(catalog.publicationDependencies.containsKey(id), id)
            ['missing', 'required', 'wrong-loader'].each { String mutation ->
                Map changed = CatalogTools.materialize(catalog) as Map
                if (mutation == 'missing') changed.publicationDependencies.remove(id)
                else if (mutation == 'required') changed.publicationDependencies[id].type = 'required'
                else changed.publicationDependencies[id].loaders = ['fabric', 'forge', 'neoforge']
                List<String> errors = []
                CatalogTools.validatePublicationDependencies(changed, errors)
                assertFalse(errors.isEmpty(), id + ': ' + mutation)
            }
        }
    }

    @Test
    void releaseFilesInvalidateTheActualBuildLogicInputDeclaration() {
        List<String> required = ['gradle/version.properties', 'gradle/plugin-version.properties',
                'gradle/targets.json', 'build.gradle', 'CHANGELOG.md', 'PLUGIN_CHANGELOG.md',
                '.github/workflows/release.yml', '.github/workflows/release-candidate.yml',
                '.github/workflows/publish-update-catalog.yml']
        File root = new File(workspace, 'input-fixture')
        required.each { String relative ->
            File file = new File(root, relative)
            file.parentFile.mkdirs()
            file.text = 'initial\n'
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
            new File(root, relative).append('changed\n')
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
        File root = new File(workspace, 'pages-' + firstCount + '-' + secondCount)
        File bin = new File(root, 'bin')
        bin.mkdirs()
        File gh = new File(bin, 'gh')
        gh.text = '''#!/bin/bash
set -euo pipefail
case "${!#}" in
  *'&page=1') printf '%s' "$FIRST_PAGE" ;;
  *'&page=2') printf '%s' "$SECOND_PAGE" ;;
  *) exit 71 ;;
esac
'''
        assertTrue(gh.setExecutable(true))
        String workflow = new File(repository, '.github/workflows/publish-update-catalog.yml').text
        int section = workflow.indexOf('      - name: Snapshot final published GitHub Releases')
        int start = workflow.indexOf('        run: |\n', section) + '        run: |\n'.length()
        int end = workflow.indexOf('\n      - name:', start)
        assertTrue(section >= 0 && end > start)
        String script = workflow.substring(start, end).readLines()
                .collect { it.startsWith('          ') ? it.substring(10) : it }.join('\n')
        ProcessBuilder builder = new ProcessBuilder('bash', '-c', script)
                .directory(root).redirectErrorStream(true)
        builder.environment().putAll([
                PATH: bin.absolutePath + File.pathSeparator + System.getenv('PATH'),
                RUNNER_TEMP: root.absolutePath, GITHUB_REPOSITORY: 'fixture/repository',
                FIRST_PAGE: JsonOutput.toJson((0..<firstCount).collect { [id: it] }),
                SECOND_PAGE: JsonOutput.toJson((0..<secondCount).collect { [id: 100 + it] })])
        Process process = builder.start()
        String output = process.inputStream.getText('UTF-8')
        [exit: process.waitFor(), output: output]
    }
}

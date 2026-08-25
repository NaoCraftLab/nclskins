package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

import static org.junit.jupiter.api.Assertions.*

final class ReleaseLogicTest {
    private final File repository = new File('../..').canonicalFile

    @Test
    void currentReleaseMatchesVersionAndChangelog() {
        Map metadata = ReleaseMetadata.validate(
                new File(repository, 'gradle/version.properties'),
                new File(repository, 'CHANGELOG.md'),
                '1.0.0-beta.3')

        assertEquals('1.0.0-beta.3', metadata.version)
        assertEquals('beta', metadata.channel)
        assertTrue(metadata.prerelease)
        assertTrue(metadata.notes.startsWith(
                '### Added\n\n- **Cross-platform update notifications**'))
        assertTrue(metadata.notes.contains(
                '### Removed\n\n- Technical refresh commands'))
        assertFalse(metadata.notes.contains('## 1.0.0-beta.3'))

        File pluginChangelog = new File(repository, 'PLUGIN_CHANGELOG.md')
        List<String> pluginLines = pluginChangelog.readLines()
        assertTrue(pluginChangelog.isFile())
        assertFalse(new File(repository, 'SERVER_CHANGELOG.md').exists())
        assertEquals('## 1.0.0-beta.3', pluginLines.find { !it.isBlank() })
        assertFalse(pluginLines.any { it.startsWith('# ') })
        String pluginNotes = ServerPluginChangelog.validate(pluginChangelog, [
                currentVersion: '1.0.0-beta.3', publish: true, reason: 'server-change'
        ])
        assertTrue(pluginNotes.startsWith(
                '### Changed\n\n- Replaced technical refresh commands'))
        assertFalse(pluginNotes.contains('## 1.0.0-beta.3'))
    }

    @Test
    void validateReleaseConsumesSealedStateAndSeparateMarketplaceProjects() {
        Path fixture = Files.createTempDirectory('nclskins-release-validation-')
        try {
            Map catalog = CatalogTools.materialize(CatalogTools.loadCatalog(repository)) as Map
            catalog.serverPlugin.platforms.modrinth.projectId = 'AbCd1234'
            catalog.serverPlugin.platforms.curseforge.projectId = 1234567
            File catalogFile = fixture.resolve('targets.json').toFile()
            catalogFile.text = JsonOutput.toJson(catalog)
            Map state = ServerPluginReleaseState.compute(repository, catalog, '1.0.0-beta.3')
            File stateFile = ServerPluginReleaseState.write(
                    fixture.resolve('server-state.json').toFile(), state)

            ValidateReleaseTask task = ProjectBuilder.builder().build().tasks.create(
                    'validateFixtureRelease', ValidateReleaseTask)
            task.repositoryDirectory.set(repository)
            task.catalogFile.set(catalogFile)
            task.versionFile.set(new File(repository, 'gradle/version.properties'))
            task.changelogFile.set(new File(repository, 'CHANGELOG.md'))
            task.pluginChangelogFile.set(new File(repository, 'PLUGIN_CHANGELOG.md'))
            task.serverPluginStateFile.set(stateFile)
            task.releaseTag.set('1.0.0-beta.3')
            task.releaseRoot.set(fixture.resolve('release').toFile())

            task.validateRelease()

            Map metadata = new JsonSlurper().parse(
                    fixture.resolve('release/1.0.0-beta.3/release-metadata.json').toFile()) as Map
            assertEquals('server-change', metadata.serverPlugin.reason)
            assertTrue(metadata.serverPlugin.publish)
            assertEquals('1.0.0-beta.2', metadata.serverPlugin.previousActiveVersion)
        } finally {
            fixture.toFile().deleteDir()
        }
    }

    @Test
    void tagPublicationJobsBypassTheExpectedSkippedBackfillDependency() {
        String workflow = new File(repository, '.github/workflows/release.yml').text

        assertTrue(workflow.contains('''  platforms:
    name: Publish Modrinth and CurseForge
    needs: build
    if: >-
      always() &&
      !cancelled() &&
      needs.build.result == 'success'
'''))
        assertTrue(workflow.contains('''  github:
    name: Publish GitHub Release
    needs: [build, platforms]
    if: >-
      always() &&
      !cancelled() &&
      needs.build.result == 'success' &&
      needs.platforms.result == 'success'
'''))
    }

    @Test
    void releaseWorkflowKeepsDistinctSourceContractsForEveryMode() {
        String workflow = new File(repository, '.github/workflows/release.yml').text

        assertTrue(workflow.contains('git checkout --detach "refs/tags/${version}"'))
        assertTrue(workflow.contains("mode='reconcile-tag'"))
        assertTrue(workflow.contains(
                "Compatibility backfill must run from current origin/main."))
        assertTrue(workflow.contains(
                "Historical current-main build forbidden::Use reconcile-tag"))
        assertTrue(workflow.contains("mode='backfill'"))
        assertTrue(workflow.contains("mode='tag'"))
        assertTrue(workflow.contains(
                'catalog_source_commit="$(git rev-parse origin/main)"'))
        assertTrue(workflow.contains(
                'printf \'release_source_commit=%s\\n\' "$(git rev-parse HEAD)"'))
        assertTrue(workflow.contains(
                'printf \'catalog_source_commit=%s\\n\' "$catalog_source_commit"'))
        assertTrue(workflow.contains(
                'ref: ${{ needs.build.outputs.release_source_commit }}'))
        assertTrue(workflow.contains(
                'catalog_source_commit: ${{ needs.build.outputs.catalog_source_commit }}'))
        assertFalse(workflow.contains(
                'catalog_source_commit: ${{ needs.build.outputs.release_source_commit }}'))
    }

    @Test
    void pagesWorkflowIsPinnedBoundedCredentialIsolatedAndReusable() {
        String pages = new File(
                repository, '.github/workflows/publish-update-catalog.yml').text

        assertTrue(pages.contains('workflow_call:'))
        assertTrue(pages.contains('workflow_dispatch:'))
        assertTrue(pages.contains('group: github-pages-update-catalog'))
        assertTrue(pages.contains('cancel-in-progress: false'))
        assertTrue(pages.contains('name: github-pages'))
        assertTrue(pages.contains('contents: read'))
        assertTrue(pages.contains('pages: write'))
        assertTrue(pages.contains('id-token: write'))
        assertTrue(pages.contains('releases?per_page=100&page=1'))
        assertTrue(pages.contains('generateUpdateCatalog'))
        assertTrue(pages.contains('build/update-catalog/site'))
        assertTrue(pages.contains(
                'actions/configure-pages@45bfe0192ca1faeb007ade9deae92b16b8254a0d'))
        assertTrue(pages.contains(
                'actions/upload-pages-artifact@fc324d3547104276b827a68afc52ff2a11cc49c9'))
        assertTrue(pages.contains(
                'actions/deploy-pages@cd2ce8fcbc39b97be8ca5fce6e763baed58fa128'))
        pages.readLines().findAll { it.trim().startsWith('uses:') }.each { String line ->
            assertTrue(line ==~ /.*@[0-9a-f]{40}(?:\s+#.*)?/, line)
        }
        assertFalse(pages.contains('MODRINTH_TOKEN'))
        assertFalse(pages.contains('CURSEFORGE'))
        assertFalse(pages.contains('secrets: inherit'))
        assertFalse(pages.contains('openspec/'))
        assertFalse(pages.contains('.agents/'))
    }

    @Test
    void catalogPublicationUsesFinalRemoteAssetsAndPreservesReleaseFailureSemantics() {
        String releaseWorkflow = new File(
                repository, '.github/workflows/release.yml').text
        String pagesWorkflow = new File(
                repository, '.github/workflows/publish-update-catalog.yml').text

        assertTrue(releaseWorkflow.contains('''  update-catalog:
    name: Publish static update catalog
    needs: [build, github]
'''))
        assertTrue(releaseWorkflow.contains("needs.github.result == 'success'"))
        assertTrue(releaseWorkflow.contains(
                'catalog_source_commit: ${{ needs.build.outputs.catalog_source_commit }}'))
        assertFalse(releaseWorkflow.contains('secrets: inherit'))

        assertTrue(pagesWorkflow.contains('workflow_dispatch:'))
        assertTrue(pagesWorkflow.contains(
                '"repos/${GITHUB_REPOSITORY}/releases?per_page=100&page=1"'))
        assertFalse(pagesWorkflow.contains('RELEASE_MODE'))
        assertFalse(pagesWorkflow.contains('selectedTargetIds'))
        assertFalse(pagesWorkflow.contains('release-manifest.json'))
        assertTrue(pagesWorkflow.contains('verifyUpdateCatalogDeployment'))
        assertTrue(pagesWorkflow.indexOf('Deploy GitHub Pages') <
                pagesWorkflow.indexOf('Verify deployed endpoint bytes'))

        String publicationTests = new File(repository,
                'gradle/build-logic/src/test/groovy/com/naocraftlab/skins/' +
                'buildlogic/PublicationLogicTest.groovy').text
        assertTrue(publicationTests.contains(
                'githubPlanKeepsExactAssetsAddsMissingAndProtectsBackfillJars'))
        assertTrue(publicationTests.contains('conflict.conflicts.size()'))
        String catalogTests = new File(repository,
                'gradle/build-logic/src/test/groovy/com/naocraftlab/skins/' +
                'buildlogic/UpdateCatalogGeneratorTest.groovy').text
        assertTrue(catalogTests.contains(
                'currentCatalogWinsOverHistoricalTagCatalogAndTagMoveAloneChangesNothing'))
        assertTrue(catalogTests.contains(
                'backfillAddsOnlyExactAssociationToExistingRelease'))
        assertTrue(catalogTests.contains(
                'partialReleaseAssociatesOnlyExactPublishedTargetArtifact'))
    }

    @Test
    void githubReleaseBodyContainsOnlyChangelogSectionsForPublishedComponents() {
        Map manifest = [
                targets     : [[id: 'fabric-26.2']],
                releaseNotes: [text: 'Mod changes\n'],
                serverPlugin: [publish: true, publication: [releaseNotes: 'Plugin changes\n']]
        ]

        assertEquals('''## Mod Changelog

Mod changes

## Plugin Changelog

Plugin changes
''', PublishGithubReleaseTask.releaseBody(manifest))

        manifest.serverPlugin = [publish: false, activeVersion: '1.0.0-beta.2']
        assertEquals('''## Mod Changelog

Mod changes
''', PublishGithubReleaseTask.releaseBody(manifest))

        manifest.targets = []
        manifest.serverPlugin = [publish: true, publication: [releaseNotes: 'Plugin changes\n']]
        assertEquals('''## Plugin Changelog

Plugin changes
''', PublishGithubReleaseTask.releaseBody(manifest))
    }

    @Test
    void githubReleaseBodyRejectsEmptyOrMissingComponentNotes() {
        assertThrows(IllegalStateException) {
            PublishGithubReleaseTask.releaseBody([
                    targets: [], serverPlugin: [publish: false]
            ])
        }
        assertThrows(IllegalStateException) {
            PublishGithubReleaseTask.releaseBody([
                    targets: [[id: 'paper-26.2']], releaseNotes: [text: '  '],
                    serverPlugin: [publish: false]
            ])
        }
        assertThrows(IllegalStateException) {
            PublishGithubReleaseTask.releaseBody([
                    targets: [], serverPlugin: [publish: true, publication: [releaseNotes: '  ']]
            ])
        }
    }

    @Test
    void githubReleaseBodyUsesExtractedCanonicalComponentBodies() {
        withReleaseFixture(
                '1.0.0',
                '## 1.0.0\n\n### Added\n\n- Mod change\n') {
            File versionFile, File changelogFile ->
                Map mod = ReleaseMetadata.validate(versionFile, changelogFile, '1.0.0')
                withPluginChangelogFixture(
                        '## 1.0.0\n\n### Changed\n\n- Plugin change\n') {
                    File pluginChangelog ->
                        String plugin = ServerPluginChangelog.validate(pluginChangelog, [
                                currentVersion: '1.0.0', publish: true, reason: 'server-change'
                        ])
                        String body = PublishGithubReleaseTask.releaseBody([
                                targets: [[id: 'fabric-26.2']],
                                releaseNotes: [text: mod.notes],
                                serverPlugin: [publish: true, publication: [releaseNotes: plugin]]
                        ])

                        assertTrue(body.contains('## Mod Changelog\n\n### Added'))
                        assertTrue(body.contains('## Plugin Changelog\n\n### Changed'))
                        assertFalse(body.contains('## 1.0.0'))
                        assertFalse(body.contains('# NCL Skins Plugin changelog'))
                }
        }
    }

    @Test
    void stableAlphaAndBetaTagsAreClassifiedStrictly() {
        ['1.0.0': false, '1.0.0-alpha.1': true, '1.1.0-beta.2': true].each {
            String version, boolean prerelease ->
                withReleaseFixture(version, "## ${version}\n\nNotes for ${version}\n") {
                    File versionFile, File changelogFile ->
                        Map metadata = ReleaseMetadata.validate(versionFile, changelogFile, version)
                        assertEquals(prerelease, metadata.prerelease)
                        assertEquals(prerelease ? (version.contains('-alpha.') ? 'alpha' : 'beta') : 'release',
                                metadata.channel)
                        assertEquals("Notes for ${version}\n".toString(), metadata.notes)
                }
        }
    }

    @Test
    void serverPluginPublicationDecisionIsAutomaticAndStablePromotionOnly() {
        String fingerprint = 'a' * 64
        String changed = 'b' * 64

        assertEquals([publish: true, reason: 'initial'],
                ServerPluginReleaseState.decide('1.0.0-alpha.1', fingerprint, null))
        assertEquals([publish: false, reason: 'unchanged'],
                ServerPluginReleaseState.decide(
                        '1.0.0-beta.1', fingerprint,
                        [version: '1.0.0-alpha.1', fingerprint: fingerprint]))
        assertEquals([publish: true, reason: 'stable-promotion'],
                ServerPluginReleaseState.decide(
                        '1.0.0', fingerprint,
                        [version: '1.0.0-beta.1', fingerprint: fingerprint]))
        assertEquals([publish: false, reason: 'unchanged'],
                ServerPluginReleaseState.decide(
                        '1.0.1', fingerprint,
                        [version: '1.0.0', fingerprint: fingerprint]))
        assertEquals([publish: true, reason: 'server-change'],
                ServerPluginReleaseState.decide(
                        '1.0.1', changed,
                        [version: '1.0.0', fingerprint: fingerprint]))
    }

    @Test
    void serverFingerprintIgnoresEmbeddedVersionAndMarketplaceIdsButTracksBehavior() {
        Path fixture = Files.createTempDirectory('nclskins-server-fingerprint-')
        try {
            write(fixture, 'server-plugin/src/main/resources/plugin.yml',
                    "name: NCLSkinsPlugin\nversion: '1.0.0-alpha.1'\n")
            write(fixture, 'server-plugin/src/main/java/example/Server.java',
                    'final class Server { static final int VALUE = 1; }')
            write(fixture, 'server-plugin/build.gradle', 'plugins { id \'java\' }\n')
            write(fixture, 'LICENSE', 'license')
            Map catalog = [serverPlugin: [
                    protocols: ['command-v1'], compatibility: ['1.20.1': ['paper']],
                    matrixId: 'fixture-v1', javaRelease: 17,
                    packaging: [gson: '2.10'], excluded: [],
                    artifact: 'nclskins-plugin-{pluginVersion}.jar',
                    sourcesArtifact: 'nclskins-plugin-{pluginVersion}-sources.jar',
                    name: 'NCL Skins Plugin', slug: 'nclskins-plugin',
                    platforms: [modrinth: [projectId: null], curseforge: [projectId: null]],
                    productionInputs: [server: ['server-plugin/src/main'], shared: ['LICENSE']]
            ]]

            String baseline = ServerPluginFingerprint.current(fixture.toFile(), catalog)
            write(fixture, 'server-plugin/src/main/resources/plugin.yml',
                    "name: NCLSkinsPlugin\nversion: '9.9.9'\n")
            catalog.serverPlugin.platforms.modrinth.projectId = 'abcdefgh'
            assertEquals(baseline, ServerPluginFingerprint.current(fixture.toFile(), catalog))

            write(fixture, 'server-plugin/src/main/java/example/Server.java',
                    'final class Server { static final int VALUE = 2; }')
            assertNotEquals(baseline, ServerPluginFingerprint.current(fixture.toFile(), catalog))
        } finally {
            fixture.toFile().deleteDir()
        }
    }

    @Test
    void invalidTagsAreRejectedWithEnglishGuidance() {
        withReleaseFixture('1.0.0-alpha.1', '## 1.0.0-alpha.1\n\nNotes\n') {
            File versionFile, File changelogFile ->
                ['v1.0.0', '1.0.0-aplha.1', '01.0.0', '1.0.0-alpha.0'].each { String tag ->
                    IllegalArgumentException failure = assertThrows(IllegalArgumentException) {
                        ReleaseMetadata.validate(versionFile, changelogFile, tag)
                    }
                    assertTrue(failure.message.contains('Release tag'))
                    assertTrue(failure.message.contains('invalid'))
                }
        }
    }

    @Test
    void versionMismatchIsRejectedBeforeChangelogExtraction() {
        withReleaseFixture('1.0.0-alpha.1', '## 1.1.0-beta.2\n\nNotes\n') {
            File versionFile, File changelogFile ->
                IllegalArgumentException failure = assertThrows(IllegalArgumentException) {
                    ReleaseMetadata.validate(versionFile, changelogFile, '1.1.0-beta.2')
                }
                assertTrue(failure.message.contains("does not match modVersion '1.0.0-alpha.1'"))
        }
    }

    @Test
    void changelogSectionMustExistExactlyOnceAndContainNotes() {
        ['## 1.0.0\n\nFirst\n\n## 1.0.0\n\nSecond\n': 'found 2',
         '## 1.0.0\n\n\n'                           : 'must contain release notes'].each {
            String changelog, String expected ->
                withReleaseFixture('1.0.0', changelog) { File versionFile, File changelogFile ->
                    IllegalArgumentException failure = assertThrows(IllegalArgumentException) {
                        ReleaseMetadata.validate(versionFile, changelogFile, '1.0.0')
                    }
                    assertTrue(failure.message.contains(expected), failure.message)
                }
        }
    }

    @Test
    void changelogMustBeVersionFirstAndHaveNoLevelOneHeading() {
        ['# Changelog\n\n## 1.0.0\n\nNotes\n',
         'Preamble\n\n## 1.0.0\n\nNotes\n',
         '## 0.9.0\n\nOlder\n\n## 1.0.0\n\nNotes\n',
         '## 1.0.0\n\nNotes\n\n# Appendix\n'].each { String changelog ->
            withReleaseFixture('1.0.0', changelog) { File versionFile, File changelogFile ->
                IllegalArgumentException failure = assertThrows(IllegalArgumentException) {
                    ReleaseMetadata.validate(versionFile, changelogFile, '1.0.0')
                }
                assertTrue(failure.message.contains(
                        "CHANGELOG.md must start with '## 1.0.0'"), failure.message)
            }
        }
    }

    @Test
    void pluginChangelogRejectsPreambleDuplicatesEmptyAndLegacyOnlyPath() {
        Map state = [currentVersion: '1.0.0', publish: true, reason: 'server-change']
        ['# NCL Skins Plugin changelog\n\n## 1.0.0\n\nNotes\n': 'must start',
         'Preamble\n\n## 1.0.0\n\nNotes\n'                    : 'must start',
         '## 1.0.0\n\nFirst\n\n## 1.0.0\n\nSecond\n'       : 'exactly one',
         '## 1.0.0\n\n\n'                                      : 'is empty',
         '## 1.0.0\n\nNotes\n\n# Appendix\n'                  : 'must start'].each {
            String changelog, String expected ->
                withPluginChangelogFixture(changelog) { File pluginChangelog ->
                    IllegalArgumentException failure = assertThrows(IllegalArgumentException) {
                        ServerPluginChangelog.validate(pluginChangelog, state)
                    }
                    assertTrue(failure.message.contains(expected), failure.message)
                }
        }

        Path fixture = Files.createTempDirectory('nclskins-plugin-changelog-legacy-')
        try {
            Files.writeString(
                    fixture.resolve('SERVER_CHANGELOG.md'), '## 1.0.0\n\nLegacy notes\n')
            IllegalArgumentException failure = assertThrows(IllegalArgumentException) {
                ServerPluginChangelog.validate(
                        fixture.resolve('PLUGIN_CHANGELOG.md').toFile(), state)
            }
            assertEquals('PLUGIN_CHANGELOG.md is missing', failure.message)
        } finally {
            fixture.toFile().deleteDir()
        }
    }

    @Test
    void unchangedPluginRequiresVersionFirstHistoryWithoutCurrentSection() {
        Map state = [currentVersion: '1.0.1', publish: false, reason: 'unchanged']
        withPluginChangelogFixture('## 1.0.0\n\nPrevious notes\n') { File pluginChangelog ->
            assertNull(ServerPluginChangelog.validate(pluginChangelog, state))
        }
        withPluginChangelogFixture('## 1.0.1\n\nUnexpected notes\n') { File pluginChangelog ->
            IllegalArgumentException failure = assertThrows(IllegalArgumentException) {
                ServerPluginChangelog.validate(pluginChangelog, state)
            }
            assertTrue(failure.message.contains('must not contain'))
        }
    }

    @Test
    void changelogExtractionStopsAtNextVersionAndPreservesMarkdown() {
        String expected = '### Added\n\n- **Feature**\n    - Detail\n'
        withReleaseFixture(
                '1.1.0',
                "## 1.1.0\n\n${expected}\n## 1.0.0\n\nOlder notes\n") {
            File versionFile, File changelogFile ->
                Map metadata = ReleaseMetadata.validate(versionFile, changelogFile, '1.1.0')
                assertEquals(expected, metadata.notes)

                Path output = Files.createTempDirectory('nclskins-release-metadata-')
                try {
                    File versionDirectory = ReleaseMetadata.write(output.toFile(), metadata)
                    assertEquals(expected, new File(versionDirectory, 'release-notes.md').text)
                    Map json = new JsonSlurper().parse(
                            new File(versionDirectory, 'release-metadata.json')) as Map
                    assertEquals(
                            [schemaVersion: 3, version: '1.1.0', channel: 'release', prerelease: false,
                             releaseNotes : 'release-notes.md'],
                            json)
                } finally {
                    output.toFile().deleteDir()
                }
        }
    }

    @Test
    void allTargetSourcesJarIsDeterministicAndKeepsRepositoryPaths() {
        Path fixture = Files.createTempDirectory('nclskins-release-sources-')
        try {
            Files.writeString(fixture.resolve('LICENSE'), 'license')
            Files.writeString(fixture.resolve('NOTICE'), 'notice')
            write(fixture, 'bundle-a/src/main/java/example/Same.java', 'class SameA {}')
            write(fixture, 'bundle-b/src/main/java/example/Same.java', 'class SameB {}')
            write(fixture, 'bundle-b/src/main/resources/asset.txt', 'asset')
            write(fixture, 'bundle-b/src/main/resources/private.pixel.json', '{}')
            write(fixture, 'bundle-b/src/main/resources/build/generated.txt', 'generated')
            write(fixture, 'bundle-b/src/main/resources/generated/Binding.java', 'generated')
            Map catalog = [sourceBundles: [
                    first : [java: ['bundle-a/src/main/java'], resources: []],
                    second: [java     : ['bundle-b/src/main/java'],
                             resources: ['bundle-b/src/main/resources']]
            ]]
            File first = fixture.resolve('first.jar').toFile()
            File second = fixture.resolve('second.jar').toFile()

            ReleaseBundle.createSourcesJar(fixture.toFile(), catalog, '1.0.0', first)
            ReleaseBundle.createSourcesJar(fixture.toFile(), catalog, '1.0.0', second)

            assertArrayEquals(first.bytes, second.bytes)
            new ZipFile(first).withCloseable { archive ->
                List<String> names = archive.entries().collect { it.name }
                assertEquals(
                        ['META-INF/MANIFEST.MF', 'META-INF/LICENSE', 'META-INF/NOTICE'],
                        names.take(3))
                List<String> sourceNames = new ArrayList<>(names.drop(3))
                List<String> sortedSourceNames = new ArrayList<>(sourceNames)
                sortedSourceNames.sort()
                assertEquals(sortedSourceNames, sourceNames)
                assertTrue(names.contains('META-INF/LICENSE'))
                assertTrue(names.contains('META-INF/NOTICE'))
                assertTrue(names.contains('bundle-a/src/main/java/example/Same.java'))
                assertTrue(names.contains('bundle-b/src/main/java/example/Same.java'))
                assertTrue(names.contains('bundle-b/src/main/resources/asset.txt'))
                assertFalse(names.any { it.contains('generated.txt') })
                assertFalse(names.any { it.contains('Binding.java') })
                assertFalse(names.any { it.endsWith('.pixel.json') })
            }
        } finally {
            fixture.toFile().deleteDir()
        }
    }

    private static void withReleaseFixture(
            String version,
            String changelog,
            Closure<?> assertion) {
        Path fixture = Files.createTempDirectory('nclskins-release-metadata-')
        try {
            File versionFile = fixture.resolve('version.properties').toFile()
            File changelogFile = fixture.resolve('CHANGELOG.md').toFile()
            versionFile.text = "modVersion=${version}\n"
            changelogFile.text = changelog
            assertion.call(versionFile, changelogFile)
        } finally {
            fixture.toFile().deleteDir()
        }
    }

    private static void withPluginChangelogFixture(
            String changelog,
            Closure<?> assertion) {
        Path fixture = Files.createTempDirectory('nclskins-plugin-changelog-')
        try {
            File changelogFile = fixture.resolve('PLUGIN_CHANGELOG.md').toFile()
            changelogFile.text = changelog
            assertion.call(changelogFile)
        } finally {
            fixture.toFile().deleteDir()
        }
    }

    private static void write(Path root, String relative, String value) {
        Path destination = root.resolve(relative)
        Files.createDirectories(destination.parent)
        Files.writeString(destination, value)
    }
}

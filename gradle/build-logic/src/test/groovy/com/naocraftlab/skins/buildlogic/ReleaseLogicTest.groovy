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
                '1.0.0-beta.2')

        assertEquals('1.0.0-beta.2', metadata.version)
        assertEquals('beta', metadata.channel)
        assertTrue(metadata.prerelease)
        assertTrue(metadata.notes.startsWith(
                '### Added\n\n- **NCL Skins Plugin support**'))
        assertTrue(metadata.notes.contains(
                '### Changed\n\n- **Improved mod menu presentation**'))
        assertTrue(metadata.notes.contains(
                '### Fixed\n\n- **More reliable live skin updates**'))
        assertFalse(metadata.notes.contains('## 1.0.0-beta.2'))
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
            Map state = ServerPluginReleaseState.compute(repository, catalog, '1.0.0-beta.2')
            File stateFile = ServerPluginReleaseState.write(
                    fixture.resolve('server-state.json').toFile(), state)

            ValidateReleaseTask task = ProjectBuilder.builder().build().tasks.create(
                    'validateFixtureRelease', ValidateReleaseTask)
            task.repositoryDirectory.set(repository)
            task.catalogFile.set(catalogFile)
            task.versionFile.set(new File(repository, 'gradle/version.properties'))
            task.changelogFile.set(new File(repository, 'CHANGELOG.md'))
            task.serverChangelogFile.set(new File(repository, 'SERVER_CHANGELOG.md'))
            task.serverPluginStateFile.set(stateFile)
            task.releaseTag.set('1.0.0-beta.2')
            task.releaseRoot.set(fixture.resolve('release').toFile())

            task.validateRelease()

            Map metadata = new JsonSlurper().parse(
                    fixture.resolve('release/1.0.0-beta.2/release-metadata.json').toFile()) as Map
            assertEquals('initial', metadata.serverPlugin.reason)
            assertTrue(metadata.serverPlugin.publish)
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
        ['# Missing\n'                              : 'found 0',
         '## 1.0.0\n\nFirst\n\n## 1.0.0\n\nSecond\n': 'found 2',
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

    private static void write(Path root, String relative, String value) {
        Path destination = root.resolve(relative)
        Files.createDirectories(destination.parent)
        Files.writeString(destination, value)
    }
}

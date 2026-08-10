package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
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
                '1.0.0-beta.1')

        assertEquals('1.0.0-beta.1', metadata.version)
        assertEquals('beta', metadata.channel)
        assertTrue(metadata.prerelease)
        assertTrue(metadata.notes.startsWith(
                '### Changed\n\n- **Updated icons**'))
        assertFalse(metadata.notes.contains('## 1.0.0-beta.1'))
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
                            [schemaVersion: 2, version: '1.1.0', channel: 'release', prerelease: false,
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

package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput

import java.nio.charset.StandardCharsets
import java.nio.file.Files

final class ReleaseMetadata {
    static Map validate(File versionFile, File changelogFile, String releaseTag) {
        if (releaseTag == null || releaseTag.isBlank()) {
            throw new IllegalArgumentException(
                    'Release tag is required. Use -PreleaseTag=<major.minor.patch[-alpha.N|-beta.N]>')
        }
        if (!CatalogTools.VERSION_PATTERN.matcher(releaseTag).matches()) {
            throw new IllegalArgumentException(
                    "Release tag '${releaseTag}' is invalid. Expected major.minor.patch, " +
                            'major.minor.patch-alpha.N, or major.minor.patch-beta.N without a v prefix')
        }

        String configuredVersion = CatalogTools.loadVersion(versionFile.toPath())
        if (releaseTag != configuredVersion) {
            throw new IllegalArgumentException(
                    "Release tag '${releaseTag}' does not match modVersion '${configuredVersion}' " +
                            'in gradle/version.properties')
        }
        if (!changelogFile.isFile()) {
            throw new IllegalArgumentException('CHANGELOG.md is missing')
        }

        List<String> lines = Files.readAllLines(changelogFile.toPath(), StandardCharsets.UTF_8)
        String expectedHeading = "## ${releaseTag}"
        List<Integer> matches = []
        lines.eachWithIndex { String line, int index ->
            if (line == expectedHeading) matches.add(index)
        }
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                    "CHANGELOG.md must contain exactly one '${expectedHeading}' section; found ${matches.size()}")
        }

        int start = matches.first() + 1
        int end = lines.size()
        for (int index = start; index < lines.size(); index++) {
            if (lines[index].startsWith('## ')) {
                end = index
                break
            }
        }
        List<String> body = new ArrayList<>(lines.subList(start, end))
        while (!body.isEmpty() && body.first().isBlank()) body.remove(0)
        while (!body.isEmpty() && body.last().isBlank()) body.remove(body.size() - 1)
        if (body.isEmpty() || body.every { it.isBlank() }) {
            throw new IllegalArgumentException(
                    "CHANGELOG.md section '${expectedHeading}' must contain release notes")
        }

        [
                version   : releaseTag,
                prerelease: releaseTag.contains('-alpha.') || releaseTag.contains('-beta.'),
                notes     : body.join('\n') + '\n'
        ]
    }

    static File write(File releaseRoot, Map metadata) {
        File versionDirectory = new File(releaseRoot, metadata.version.toString())
        Files.createDirectories(versionDirectory.toPath())
        Files.writeString(
                new File(versionDirectory, 'release-notes.md').toPath(),
                metadata.notes.toString(),
                StandardCharsets.UTF_8)
        Map publicMetadata = [
                schemaVersion: 1,
                version      : metadata.version,
                prerelease   : metadata.prerelease,
                releaseNotes : 'release-notes.md'
        ]
        Files.writeString(
                new File(versionDirectory, 'release-metadata.json').toPath(),
                JsonOutput.prettyPrint(JsonOutput.toJson(publicMetadata)) + '\n',
                StandardCharsets.UTF_8)
        versionDirectory
    }

    private ReleaseMetadata() {}
}

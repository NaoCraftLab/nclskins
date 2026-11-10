package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput

import java.nio.charset.StandardCharsets
import java.nio.file.Files

final class ReleaseMetadata {
    static Map prepare(File versionFile, File pluginVersionFile, File changelogFile,
                       File pluginChangelogFile, String requestedVersion, String baselineOverride = '') {
        Map selection = ServerPluginVersion.select(requestedVersion,
                CatalogTools.loadVersion(versionFile.toPath()), ServerPluginVersion.parse(pluginVersionFile.text))
        String baseline = MetadataRenderer.effectiveRequiredServerPluginVersion(
                selection.functionalVersion.toString(), baselineOverride)
        if (baseline != selection.functionalVersion) {
            throw new IllegalArgumentException('Release baseline override must match configured plugin functional version')
        }
        Map metadata = selection.componentKind == 'plugin-only'
                ? [version: requestedVersion, channel: requestedVersion.contains('-alpha.') ? 'alpha' :
                    requestedVersion.contains('-beta.') ? 'beta' : 'release',
                   prerelease: requestedVersion.contains('-'), notes: null]
                : validate(versionFile, changelogFile, requestedVersion)
        Map server = selection + [publish: selection.componentKind != 'mod-only',
                reason: selection.componentKind, activeVersion: baseline]
        server.notes = ServerPluginChangelog.validate(pluginChangelogFile, server)
        metadata + selection + [serverPlugin: server]
    }

    static Map validate(File versionFile, File changelogFile, String releaseTag) {
        if (releaseTag != null && releaseTag ==~ /\d+\.\d+\.\d+\.[1-9][0-9]*(?:-(?:alpha|beta)\.[1-9][0-9]*)?/) {
            return prepare(versionFile, new File(versionFile.parentFile, 'plugin-version.properties'),
                    changelogFile, new File(changelogFile.parentFile, 'PLUGIN_CHANGELOG.md'), releaseTag)
        }
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
        requireVersionFirstFormat(lines, expectedHeading)
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

        String channel = releaseTag.contains('-alpha.')
                ? 'alpha' : releaseTag.contains('-beta.') ? 'beta' : 'release'
        [
                version   : releaseTag,
                channel   : channel,
                prerelease: channel != 'release',
                notes     : body.join('\n') + '\n'
        ]
    }

    private static void requireVersionFirstFormat(List<String> lines, String expectedHeading) {
        String first = lines.find { !it.isBlank() }
        if (first != expectedHeading || lines.any { it.startsWith('# ') }) {
            throw new IllegalArgumentException(
                    "CHANGELOG.md must start with '${expectedHeading}' and must not contain a level-one heading")
        }
    }

    static File write(File releaseRoot, Map metadata) {
        File versionDirectory = new File(releaseRoot, metadata.version.toString())
        Files.createDirectories(versionDirectory.toPath())
        File modNotes = new File(versionDirectory, 'release-notes.md')
        if (metadata.notes != null) {
            Files.writeString(modNotes.toPath(), metadata.notes.toString(), StandardCharsets.UTF_8)
        } else {
            Files.deleteIfExists(modNotes.toPath())
        }
        Map publicMetadata = [
                schemaVersion: 3,
                version      : metadata.version,
                channel      : metadata.channel,
                prerelease   : metadata.prerelease,
                releaseNotes : metadata.notes == null ? null : 'release-notes.md'
        ]
        if (metadata.serverPlugin instanceof Map) {
            Map server = new LinkedHashMap(metadata.serverPlugin as Map)
            String notes = server.remove('notes')
            if (server.publish == true) {
                File serverNotes = new File(versionDirectory, 'server-plugin-release-notes.md')
                Files.writeString(
                        serverNotes.toPath(), notes ?: '', StandardCharsets.UTF_8)
                server.releaseNotes = serverNotes.name
            } else {
                server.releaseNotes = null
            }
            publicMetadata.serverPlugin = server
        }
        Files.writeString(
                new File(versionDirectory, 'release-metadata.json').toPath(),
                JsonOutput.prettyPrint(JsonOutput.toJson(publicMetadata)) + '\n',
                StandardCharsets.UTF_8)
        versionDirectory
    }

    private ReleaseMetadata() {}
}

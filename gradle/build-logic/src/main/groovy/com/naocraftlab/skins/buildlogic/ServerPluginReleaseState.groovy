package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput

import java.nio.charset.StandardCharsets
import java.nio.file.Files


final class ServerPluginReleaseState {
    static Map compute(File repository, Map currentCatalog, String currentVersion) {
        if (!CatalogTools.VERSION_PATTERN.matcher(currentVersion).matches()) {
            throw new IllegalArgumentException("Invalid current plugin version ${currentVersion}")
        }
        String currentRef = refExists(repository, "refs/tags/${currentVersion}")
                ? currentVersion : 'HEAD'
        String currentCommit = ReleaseSelection.git(
                repository, ['rev-parse', "${currentRef}^{commit}"]).trim()
        List<Map> history = releaseHistory(repository, currentCommit)
        Map currentTagEntry = history.find { it.version == currentVersion }
        if (currentTagEntry != null && currentTagEntry.commit != currentCommit) {
            throw new IllegalStateException(
                    "Release tag ${currentVersion} does not point at the selected release commit")
        }

        Map active = null
        history.findAll { it.version != currentVersion }.each { Map release ->
            String fingerprint = ServerPluginFingerprint.atRef(
                    repository, release.version.toString())
            if (fingerprint == null) return
            Map decision = decide(release.version.toString(), fingerprint, active)
            if (decision.publish) {
                active = [version: release.version, fingerprint: fingerprint]
            }
        }
        String currentFingerprint = currentRef == currentVersion
                ? ServerPluginFingerprint.atRef(repository, currentRef)
                : ServerPluginFingerprint.current(repository, currentCatalog)
        if (currentFingerprint == null) {
            throw new IllegalStateException('Current release has no serverPlugin production graph')
        }
        Map decision = decide(currentVersion, currentFingerprint, active)
        String previousActive = active?.version
        [
                schemaVersion            : 1,
                sealed                   : true,
                currentVersion           : currentVersion,
                publish                  : decision.publish,
                reason                   : decision.reason,
                activeVersion            : decision.publish ? currentVersion : previousActive,
                previousActiveVersion    : previousActive,
                currentFingerprint       : currentFingerprint,
                activeFingerprint        : decision.publish
                        ? currentFingerprint : active?.fingerprint,
                previousActiveFingerprint: active?.fingerprint,
                protocolIds              : currentCatalog.serverPlugin.protocols,
                matrixId                 : currentCatalog.serverPlugin.matrixId,
                sourceCommit             : currentCommit
        ]
    }

    static Map decide(String currentVersion, String currentFingerprint, Map active) {
        if (active == null) return [publish: true, reason: 'initial']
        if (currentFingerprint != active.fingerprint) {
            return [publish: true, reason: 'server-change']
        }
        if (isStable(currentVersion) && !isStable(active.version.toString())) {
            return [publish: true, reason: 'stable-promotion']
        }
        [publish: false, reason: 'unchanged']
    }

    static File write(File destination, Map state) {
        Files.createDirectories(destination.toPath().parent)
        Files.writeString(
                destination.toPath(),
                JsonOutput.prettyPrint(JsonOutput.toJson(state)) + '\n',
                StandardCharsets.UTF_8)
        destination
    }

    static List<Map> releaseHistory(File repository, String commit) {
        List<String> commits = ReleaseSelection.git(
                repository, ['rev-list', '--first-parent', '--reverse', commit])
                .readLines().findAll { !it.isBlank() }
        List<Map> result = []
        commits.each { String candidate ->
            List<String> tags = ReleaseSelection.git(
                    repository, ['tag', '--points-at', candidate])
                    .readLines()
                    .findAll { CatalogTools.VERSION_PATTERN.matcher(it).matches() }
                    .sort(ServerPluginReleaseState::compareVersions)
            if (tags.size() > 1) {
                throw new IllegalStateException(
                        "Release commit ${candidate} has multiple SemVer tags: ${tags}")
            }
            if (tags.size() == 1) result.add([version: tags.first(), commit: candidate])
        }
        for (int index = 1; index < result.size(); index++) {
            if (compareVersions(result[index - 1].version.toString(),
                    result[index].version.toString()) >= 0) {
                throw new IllegalStateException(
                        "First-parent release versions are not strictly increasing: " +
                                "${result[index - 1].version}, ${result[index].version}")
            }
        }
        result
    }

    static int compareVersions(String left, String right) {
        List<Integer> leftParts = versionParts(left)
        List<Integer> rightParts = versionParts(right)
        for (int index = 0; index < leftParts.size(); index++) {
            int compared = leftParts[index] <=> rightParts[index]
            if (compared != 0) return compared
        }
        0
    }

    private static List<Integer> versionParts(String version) {
        def matcher = version =~ /^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta)\.(\d+))?$/
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid SemVer ${version}")
        int rank = matcher.group(4) == null ? 2 : matcher.group(4) == 'beta' ? 1 : 0
        [matcher.group(1).toInteger(), matcher.group(2).toInteger(),
         matcher.group(3).toInteger(), rank,
         matcher.group(5) == null ? 0 : matcher.group(5).toInteger()]
    }

    private static boolean isStable(String version) {
        !version.contains('-')
    }

    private static boolean refExists(File repository, String ref) {
        Process process = new ProcessBuilder('git', 'show-ref', '--verify', '--quiet', ref)
                .directory(repository)
                .start()
        process.inputStream.close()
        process.errorStream.close()
        process.waitFor() == 0
    }

    private ServerPluginReleaseState() {
    }
}

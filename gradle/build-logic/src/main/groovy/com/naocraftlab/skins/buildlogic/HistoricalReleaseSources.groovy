package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption

final class HistoricalReleaseSources {
    static final String INDEX_FILE = 'provenance.json'

    static void writeIndex(File directory, String version, String tagCommit, Collection<File> files) {
        requireVersionAndCommit(version, tagCommit)
        Files.createDirectories(directory.toPath())
        List<Map> assets = files.collect { File file ->
            requireSafeSourceFile(file)
            [
                    file  : file.name,
                    size  : file.length(),
                    sha256: ReleaseBundle.sha256(file)
            ]
        }.sort { Map left, Map right -> left.file <=> right.file }
        if (assets*.file.toSet().size() != assets.size()) {
            throw new IllegalStateException('Historical source provenance contains duplicate filenames')
        }
        Map index = [
                schemaVersion: 1,
                version      : version,
                tagCommit    : tagCommit,
                assets       : assets
        ]
        Files.writeString(
                new File(directory, INDEX_FILE).toPath(),
                JsonOutput.prettyPrint(JsonOutput.toJson(index)) + '\n',
                StandardCharsets.UTF_8)
    }

    static Map<String, File> verify(
            File directory, String version, String tagCommit, Collection<String> expectedNames) {
        requireVersionAndCommit(version, tagCommit)
        if (!directory.isDirectory() || Files.isSymbolicLink(directory.toPath())) {
            throw new IllegalStateException(
                    "Historical source directory is missing or unsafe: ${directory}")
        }
        File indexFile = new File(directory, INDEX_FILE)
        if (!indexFile.isFile() || Files.isSymbolicLink(indexFile.toPath())) {
            throw new IllegalStateException('Historical source provenance index is missing or unsafe')
        }
        Object parsed = new JsonSlurper().parse(indexFile)
        if (!(parsed instanceof Map) || parsed.schemaVersion != 1 ||
                parsed.version != version || parsed.tagCommit != tagCommit ||
                !(parsed.assets instanceof List)) {
            throw new IllegalStateException('Historical source provenance does not match exact release tag')
        }
        Set<String> expected = expectedNames.collect { it.toString() } as Set<String>
        if (expected.size() != expectedNames.size() || expected.any { !safeSourceName(it) }) {
            throw new IllegalArgumentException('Expected historical source filenames are invalid or duplicated')
        }
        Map<String, File> verified = [:]
        (parsed.assets as List).each { Object raw ->
            if (!(raw instanceof Map)) {
                throw new IllegalStateException('Historical source provenance asset is malformed')
            }
            Map asset = raw as Map
            String name = asset.file?.toString()
            if (!safeSourceName(name) || verified.containsKey(name) ||
                    !(asset.size instanceof Number) || !(asset.sha256 ==~ /[0-9a-f]{64}/)) {
                throw new IllegalStateException(
                        "Historical source provenance asset is invalid or duplicated: ${name}")
            }
            File file = new File(directory, name)
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    file.length() != (asset.size as Number).longValue() ||
                    ReleaseBundle.sha256(file) != asset.sha256) {
                throw new IllegalStateException(
                        "Historical source artifact is missing, unsafe, or tampered: ${name}")
            }
            verified[name] = file
        }
        if (verified.keySet() != expected) {
            throw new IllegalStateException(
                    "Historical source artifact set differs from expected: " +
                    "expected=${expected.sort()}, actual=${verified.keySet().sort()}")
        }
        Set<String> directoryNames = directory.listFiles().collect { File file -> file.name } as Set<String>
        if (directoryNames != expected + ([INDEX_FILE] as Set<String>)) {
            throw new IllegalStateException('Historical source directory contains unexpected entries')
        }
        verified
    }

    static String requireTaggedCheckout(File repository, File checkout, String releaseTag) {
        if (!CatalogTools.VERSION_PATTERN.matcher(releaseTag).matches()) {
            throw new IllegalArgumentException("Invalid historical release tag: ${releaseTag}")
        }
        String expected = ReleaseSelection.git(
                repository, ['rev-parse', "refs/tags/${releaseTag}^{commit}"]).trim()
        String actual = ReleaseSelection.git(checkout, ['rev-parse', 'HEAD^{commit}']).trim()
        if (expected != actual) {
            throw new IllegalStateException(
                    "Historical source checkout ${actual} differs from exact tag commit ${expected}")
        }
        String configured = CatalogTools.loadVersion(checkout)
        if (configured != releaseTag) {
            throw new IllegalStateException(
                    "Historical source checkout version ${configured} differs from tag ${releaseTag}")
        }
        expected
    }

    static String requireReachableTag(File repository, String releaseTag) {
        if (!CatalogTools.VERSION_PATTERN.matcher(releaseTag).matches()) {
            throw new IllegalArgumentException("Invalid historical release tag: ${releaseTag}")
        }
        String commit = ReleaseSelection.git(
                repository, ['rev-parse', "refs/tags/${releaseTag}^{commit}"]).trim()
        ReleaseSelection.git(repository, [
                'merge-base', '--is-ancestor', commit, 'HEAD^{commit}'])
        commit
    }

    static boolean safeSourceName(String name) {
        name != null && name ==~ /[A-Za-z0-9][A-Za-z0-9._+-]*-sources\.jar/
    }

    private static void requireSafeSourceFile(File file) {
        if (!safeSourceName(file.name) ||
                !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Historical source artifact is unsafe: ${file}")
        }
    }

    private static void requireVersionAndCommit(String version, String tagCommit) {
        if (!CatalogTools.VERSION_PATTERN.matcher(version).matches() ||
                !(tagCommit ==~ /[0-9a-f]{40}/)) {
            throw new IllegalArgumentException('Historical source provenance version or commit is invalid')
        }
    }

    private HistoricalReleaseSources() {}
}

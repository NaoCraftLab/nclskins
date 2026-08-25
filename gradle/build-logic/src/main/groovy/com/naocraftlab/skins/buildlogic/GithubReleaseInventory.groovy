package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets

final class GithubReleaseInventory {
    static final int MAX_BYTES = 2 * 1024 * 1024
    static final int MAX_RELEASES = 100
    static final int MAX_ASSETS = 256
    static final int MAX_STRING = 1024

    static List<Map> parse(Map catalog, byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) {
            throw invalid('inventory exceeds byte limit')
        }
        Object parsed
        try {
            parsed = new JsonSlurper().parse(bytes, StandardCharsets.UTF_8.name())
        } catch (RuntimeException failure) {
            throw invalid('inventory is not valid JSON')
        }
        if (!(parsed instanceof List) || (parsed as List).size() > MAX_RELEASES) {
            throw invalid('inventory must be a bounded array')
        }
        (catalog.targets as List).each { Map target ->
            String template = target.artifact.file.toString()
            if (!template.contains('{modVersion}')) {
                throw invalid("${target.id} artifact template has no version token")
            }
        }
        Set<String> versions = [] as Set
        List<Map> result = []
        (parsed as List).eachWithIndex { Object rawRelease, int index ->
            if (!(rawRelease instanceof Map)) {
                throw invalid("release ${index} is not an object")
            }
            Map release = rawRelease as Map
            if (!(release.draft instanceof Boolean)) {
                throw invalid("release ${index} draft flag is invalid")
            }
            if (release.draft == true) return
            String version = requiredString(release.tag_name, "release ${index} tag")
            if (!CatalogTools.VERSION_PATTERN.matcher(version).matches()) {
                throw invalid("release ${index} tag is not an exact NCL SemVer")
            }
            if (!versions.add(version)) {
                throw invalid("duplicate release version ${version}")
            }
            String releaseUrl = requiredString(release.html_url, "release ${version} URL")
            String expectedReleaseUrl =
                    "https://github.com/NaoCraftLab/nclskins/releases/tag/${version}"
            if (releaseUrl != expectedReleaseUrl) {
                throw invalid("release ${version} URL is not canonical")
            }
            if (!(release.assets instanceof List) ||
                    (release.assets as List).size() > MAX_ASSETS) {
                throw invalid("release ${version} assets are invalid")
            }
            Set<String> assetNames = [] as Set
            (release.assets as List).eachWithIndex { Object rawAsset, int assetIndex ->
                if (!(rawAsset instanceof Map)) {
                    throw invalid("release ${version} asset ${assetIndex} is not an object")
                }
                Map asset = rawAsset as Map
                String name = requiredString(
                        asset.name, "release ${version} asset ${assetIndex} name")
                if (!(name ==~ /[A-Za-z0-9._+-]+/) || !assetNames.add(name)) {
                    throw invalid("release ${version} has invalid or duplicate asset name")
                }
                String download = requiredString(
                        asset.browser_download_url,
                        "release ${version} asset ${assetIndex} URL")
                String expectedDownload =
                        "https://github.com/NaoCraftLab/nclskins/releases/download/${version}/" +
                                name.replace('+', '%2B')
                if (download != expectedDownload) {
                    throw invalid("release ${version} asset URL is not canonical")
                }
            }
            List<String> targetIds = (catalog.targets as List)
                    .findAll { Map target ->
                        target.releaseEligible == true && assetNames.contains(
                                target.artifact.file.toString()
                                        .replace('{modVersion}', version))
                    }
                    .collect { Map target -> target.id.toString() }
                    .sort()
            result.add([
                    version  : version,
                    url      : releaseUrl,
                    assets   : assetNames.toList().sort(),
                    targetIds: targetIds
            ])
        }
        result
    }

    private static String requiredString(Object raw, String subject) {
        if (!(raw instanceof String) || raw.isBlank() || raw.length() > MAX_STRING) {
            throw invalid("${subject} is invalid")
        }
        raw.toString()
    }

    private static IllegalArgumentException invalid(String reason) {
        new IllegalArgumentException("Invalid GitHub release inventory: ${reason}")
    }

    private GithubReleaseInventory() {}
}

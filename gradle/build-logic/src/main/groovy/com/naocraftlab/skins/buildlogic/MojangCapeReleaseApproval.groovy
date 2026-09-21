package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

final class MojangCapeReleaseApproval {
    private static final String MANIFEST = 'gradle/asset-provenance/mojang-capes.json'

    static void requireApproved(File repository) {
        File file = new File(repository, MANIFEST)
        if (!file.isFile()) {
            throw new IllegalStateException('Mojang cape provenance manifest is missing')
        }
        Map manifest = new JsonSlurper().parse(file) as Map
        List entries = manifest.entries instanceof List ? manifest.entries as List : []
        String fingerprint = assetSetSha256(entries)
        if (manifest.releaseApproved != true || entries.isEmpty() ||
                entries.any { !((it as Map).releaseApproved == true) } ||
                manifest.approvedAssetSetSha256 != fingerprint) {
            throw new IllegalStateException(
                    'Mojang cape redistribution is not approved for the exact asset set')
        }
    }

    static String assetSetSha256(List entries) {
        String canonical = entries.collect { Object raw ->
            Map entry = raw as Map
            [entry.collectionId, entry.capeId, entry.sha256].collect {
                it == null ? '' : it.toString()
            }.join('/')
        }.sort().join('\n')
        HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(
                canonical.getBytes(StandardCharsets.UTF_8)))
    }

    private MojangCapeReleaseApproval() {
    }
}

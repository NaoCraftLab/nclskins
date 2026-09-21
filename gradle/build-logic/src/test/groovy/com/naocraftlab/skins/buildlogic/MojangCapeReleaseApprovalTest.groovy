package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertThrows

class MojangCapeReleaseApprovalTest {
    @Test
    void publicationFailsClosedUntilTheWholeExactSetIsApproved(@TempDir Path temp) {
        Path manifest = temp.resolve(
                'gradle/asset-provenance/mojang-capes.json')
        Files.createDirectories(manifest.parent)
        Files.writeString(manifest, '{"releaseApproved":false,"entries":[' +
                '{"collectionId":"collection","capeId":"cape","sha256":"a","releaseApproved":false}]}')
        assertThrows(IllegalStateException) {
            MojangCapeReleaseApproval.requireApproved(temp.toFile())
        }

        List entries = [[collectionId: 'collection', capeId: 'cape', sha256: 'a',
                         releaseApproved: true]]
        String fingerprint = MojangCapeReleaseApproval.assetSetSha256(entries)
        Files.writeString(manifest, '{"releaseApproved":true,' +
                '"approvedAssetSetSha256":"' + fingerprint + '","entries":[' +
                '{"collectionId":"collection","capeId":"cape","sha256":"a","releaseApproved":true}]}')
        MojangCapeReleaseApproval.requireApproved(temp.toFile())

        Files.writeString(manifest, '{"releaseApproved":true,' +
                '"approvedAssetSetSha256":"' + fingerprint + '","entries":[' +
                '{"collectionId":"collection","capeId":"cape","sha256":"b","releaseApproved":true}]}')
        assertThrows(IllegalStateException) {
            MojangCapeReleaseApproval.requireApproved(temp.toFile())
        }
    }
}

package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.GradleException

import java.util.zip.ZipFile

final class ExternalArtifactIntegrity {
    private ExternalArtifactIntegrity() {
    }

    static void verify(File artifact, Map expected) {
        if (!artifact.isFile()) {
            throw new GradleException("Missing external development artifact: ${artifact}")
        }
        if (artifact.length() != (expected.size as long)) {
            throw new GradleException(
                    "External development artifact size mismatch: ${artifact.name}")
        }
        if (ReleaseBundle.sha1(artifact) != expected.sha1 ||
                ReleaseBundle.sha512(artifact) != expected.sha512) {
            throw new GradleException(
                    "External development artifact checksum mismatch: ${artifact.name}")
        }
        try {
            new ZipFile(artifact).withCloseable { ZipFile zip ->
                Set<String> names = [] as Set
                zip.entries().each { entry ->
                    if (!names.add(entry.name)) {
                        throw new GradleException(
                                "Duplicate external development artifact entry: ${entry.name}")
                    }
                }
                def metadataEntry = zip.getEntry('fabric.mod.json')
                if (metadataEntry == null) {
                    throw new GradleException(
                            'Minecraft SQLite JDBC artifact lacks fabric.mod.json')
                }
                Map metadata = zip.getInputStream(metadataEntry).withCloseable {
                    new JsonSlurper().parse(it) as Map
                }
                if (metadata.id != expected.fabricModId || metadata.version != expected.version) {
                    throw new GradleException(
                            'Minecraft SQLite JDBC artifact has unexpected metadata')
                }
                if (zip.getEntry('org/sqlite/JDBC.class') == null ||
                        zip.getEntry('META-INF/services/java.sql.Driver') == null) {
                    throw new GradleException(
                            'Minecraft SQLite JDBC artifact lacks the canonical JDBC driver')
                }
            }
        } catch (GradleException error) {
            throw error
        } catch (Exception error) {
            throw new GradleException(
                    "Invalid external development artifact archive: ${artifact.name}", error)
        }
    }
}

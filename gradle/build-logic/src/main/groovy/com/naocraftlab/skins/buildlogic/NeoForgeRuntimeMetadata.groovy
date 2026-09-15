package com.naocraftlab.skins.buildlogic

import org.gradle.api.GradleException

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

final class NeoForgeRuntimeMetadata {
    static final String METADATA = 'META-INF/neoforge.mods.toml'
    static final String LEGACY_ICON = 'logoFile = "yacl-128x.png"'
    static final String SQUARE_ICON = 'iconFile = "yacl-128x.png"'
    static final String SQLITE_LEGACY_LOGO = 'logoFile="sqlite-jdbc.png"'
    static final String SQLITE_NO_BANNER = 'bannerFile=false'

    static void patchYacl(Path input, Path output) {
        patch(input, output, 'YACL', LEGACY_ICON, SQUARE_ICON)
    }

    static void patchSqlite(Path input, Path output) {
        patch(input, output, 'SQLite JDBC', SQLITE_LEGACY_LOGO, SQLITE_NO_BANNER)
    }

    private static void patch(
            Path input, Path output, String artifactName, String legacy, String replacement) {
        if (!Files.isRegularFile(input)) {
            throw new GradleException("Missing ${artifactName} runtime artifact: ${input}")
        }
        Map entries = new TreeMap()
        ZipFile inputArchive = new ZipFile(input.toFile())
        try {
            def archiveEntries = inputArchive.entries()
            while (archiveEntries.hasMoreElements()) {
                ZipEntry entry = archiveEntries.nextElement()
                if (entries.containsKey(entry.name)) {
                    throw new GradleException("Duplicate ${artifactName} JAR entry: ${entry.name}")
                }
                if (entry.name.matches('META-INF/[^/]+\\.(SF|RSA|DSA)')) {
                    throw new GradleException(
                            "Refusing to rewrite signed ${artifactName} runtime artifact: ${entry.name}")
                }
                byte[] contents = new byte[0]
                if (!entry.directory) {
                    InputStream entryInput = inputArchive.getInputStream(entry)
                    try {
                        contents = entryInput.readAllBytes()
                    } finally {
                        entryInput.close()
                    }
                }
                entries[entry.name] = contents
            }
        } finally {
            inputArchive.close()
        }
        byte[] metadataBytes = entries[METADATA]
        if (metadataBytes == null) {
            throw new GradleException("${artifactName} runtime artifact lacks ${METADATA}")
        }
        String metadata = new String(metadataBytes, StandardCharsets.UTF_8)
        if (metadata.count(legacy) != 1 || metadata.contains(replacement)) {
            throw new GradleException(
                    "${artifactName} NeoForge metadata no longer matches the expected upstream baseline")
        }
        entries[METADATA] = metadata.replace(legacy, replacement)
                .getBytes(StandardCharsets.UTF_8)

        Files.createDirectories(output.parent)
        Path temporary = output.resolveSibling(output.fileName.toString() + '.tmp')
        try {
            ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))
            try {
                for (def mapping : entries.entrySet()) {
                    String name = mapping.key
                    byte[] bytes = mapping.value
                    ZipEntry entry = new ZipEntry(name)
                    entry.time = 0L
                    zip.putNextEntry(entry)
                    if (bytes.length > 0) zip.write(bytes)
                    zip.closeEntry()
                }
            } finally {
                zip.close()
            }
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        ZipFile outputArchive = new ZipFile(output.toFile())
        try {
            InputStream metadataInput = outputArchive.getInputStream(outputArchive.getEntry(METADATA))
            String actual
            try {
                actual = new String(metadataInput.readAllBytes(), StandardCharsets.UTF_8)
            } finally {
                metadataInput.close()
            }
            if (!actual.contains(replacement) || actual.contains(legacy)) {
                throw new GradleException(
                        "Patched ${artifactName} metadata verification failed: ${output}")
            }
        } finally {
            outputArchive.close()
        }
    }

    private NeoForgeRuntimeMetadata() {}
}

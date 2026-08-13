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

    static void patchYacl(Path input, Path output) {
        if (!Files.isRegularFile(input)) {
            throw new GradleException("Missing YACL runtime artifact: ${input}")
        }
        Map entries = new TreeMap()
        ZipFile inputArchive = new ZipFile(input.toFile())
        try {
            def archiveEntries = inputArchive.entries()
            while (archiveEntries.hasMoreElements()) {
                ZipEntry entry = archiveEntries.nextElement()
                if (entries.containsKey(entry.name)) {
                    throw new GradleException("Duplicate YACL JAR entry: ${entry.name}")
                }
                if (entry.name.matches('META-INF/[^/]+\\.(SF|RSA|DSA)')) {
                    throw new GradleException(
                            "Refusing to rewrite signed YACL runtime artifact: ${entry.name}")
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
            throw new GradleException("YACL runtime artifact lacks ${METADATA}")
        }
        String metadata = new String(metadataBytes, StandardCharsets.UTF_8)
        if (metadata.count(LEGACY_ICON) != 1 || metadata.contains(SQUARE_ICON)) {
            throw new GradleException('YACL NeoForge icon metadata no longer matches the expected upstream baseline')
        }
        entries[METADATA] = metadata.replace(LEGACY_ICON, SQUARE_ICON)
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
            if (!actual.contains(SQUARE_ICON) || actual.contains(LEGACY_ICON)) {
                throw new GradleException("Patched YACL metadata verification failed: ${output}")
            }
        } finally {
            outputArchive.close()
        }
    }

    private NeoForgeRuntimeMetadata() {}
}

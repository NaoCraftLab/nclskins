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

final class ForgeRuntimeMetadata {
    static final String MANIFEST = 'META-INF/MANIFEST.MF'
    static final String MULTI_RELEASE = 'Multi-Release: true'

    static void patchSqlite(Path input, Path output) {
        if (!Files.isRegularFile(input)) {
            throw new GradleException("Missing SQLite JDBC runtime artifact: ${input}")
        }
        Map<String, byte[]> entries = new TreeMap<>()
        new ZipFile(input.toFile()).withCloseable { ZipFile archive ->
            archive.entries().each { ZipEntry entry ->
                if (entries.containsKey(entry.name)) {
                    throw new GradleException("Duplicate SQLite JDBC JAR entry: ${entry.name}")
                }
                if (entry.name.matches('META-INF/[^/]+\\.(SF|RSA|DSA)')) {
                    throw new GradleException(
                            "Refusing to rewrite signed SQLite JDBC runtime artifact: ${entry.name}")
                }
                if (entry.name.startsWith('META-INF/versions/')) {
                    throw new GradleException(
                            'SQLite JDBC runtime artifact unexpectedly contains multi-release entries')
                }
                entries[entry.name] = entry.directory
                        ? new byte[0]
                        : archive.getInputStream(entry).withCloseable { it.readAllBytes() }
            }
        }
        byte[] manifestBytes = entries[MANIFEST]
        if (manifestBytes == null) {
            throw new GradleException("SQLite JDBC runtime artifact lacks ${MANIFEST}")
        }
        String manifest = new String(manifestBytes, StandardCharsets.UTF_8)
        if (manifest.readLines().count { it.equalsIgnoreCase(MULTI_RELEASE) } != 1) {
            throw new GradleException(
                    'SQLite JDBC Forge manifest no longer matches the expected upstream baseline')
        }
        entries[MANIFEST] = manifest.replaceAll('(?mi)^Multi-Release: true\\r?\\n', '')
                .getBytes(StandardCharsets.UTF_8)

        Files.createDirectories(output.parent)
        Path temporary = output.resolveSibling(output.fileName.toString() + '.tmp')
        try {
            new ZipOutputStream(Files.newOutputStream(temporary)).withCloseable { ZipOutputStream zip ->
                entries.each { String name, byte[] bytes ->
                    ZipEntry entry = new ZipEntry(name)
                    entry.time = 0L
                    zip.putNextEntry(entry)
                    if (bytes.length > 0) zip.write(bytes)
                    zip.closeEntry()
                }
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
        new ZipFile(output.toFile()).withCloseable { ZipFile archive ->
            String actual = archive.getInputStream(archive.getEntry(MANIFEST))
                    .withCloseable { new String(it.readAllBytes(), StandardCharsets.UTF_8) }
            if (actual.readLines().any { it.toLowerCase(Locale.ROOT).startsWith('multi-release:') }) {
                throw new GradleException("Patched SQLite JDBC Forge manifest verification failed: ${output}")
            }
        }
    }

    private ForgeRuntimeMetadata() {}
}

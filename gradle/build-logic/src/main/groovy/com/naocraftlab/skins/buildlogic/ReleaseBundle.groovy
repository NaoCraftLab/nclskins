package com.naocraftlab.skins.buildlogic

import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

final class ReleaseBundle {
    private static final Set<String> FORBIDDEN_SEGMENTS = [
            'build', 'generated', 'docs', '.gradle', '.codex', '.agents'
    ] as Set

    static File createSourcesJar(File repository, Map catalog, String version, File destination) {
        Path root = repository.toPath().toRealPath()
        TreeMap<String, Path> entries = new TreeMap<>()
        Set<String> declaredRoots = new TreeSet<>()
        (catalog.sourceBundles as Map).values().each { Object rawBundle ->
            Map bundle = rawBundle as Map
            ((bundle.java as List) + (bundle.resources as List)).each { Object rawPath ->
                declaredRoots.add(rawPath.toString())
            }
        }
        if (declaredRoots.isEmpty()) {
            throw new IllegalStateException('Target catalog contains no source roots for the release sources JAR')
        }

        declaredRoots.each { String declaredRoot ->
            Path relativeRoot = Path.of(declaredRoot)
            if (relativeRoot.isAbsolute() || relativeRoot.normalize().startsWith('..')) {
                throw new IllegalStateException("Source root escapes the repository: ${declaredRoot}")
            }
            Path sourceRoot = root.resolve(relativeRoot).normalize()
            if (!sourceRoot.startsWith(root) || !Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Missing catalog source root: ${declaredRoot}")
            }
            Files.walk(sourceRoot).withCloseable { stream ->
                stream.forEach { Path path ->
                    if (Files.isSymbolicLink(path)) {
                        throw new IllegalStateException(
                                "Symlinks are not allowed in release sources: ${root.relativize(path)}")
                    }
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return
                    String entry = root.relativize(path).toString()
                            .replace(File.separatorChar, '/' as char)
                    if (forbidden(entry)) return
                    Path previous = entries.put(entry, path)
                    if (previous != null) {
                        throw new IllegalStateException("Duplicate release source entry: ${entry}")
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException('Release sources JAR would be empty')
        }

        Files.createDirectories(destination.toPath().parent)
        Path temporary = Files.createTempFile(destination.toPath().parent, '.nclskins-sources-', '.jar')
        try {
            new ZipOutputStream(Files.newOutputStream(temporary)).withCloseable { ZipOutputStream output ->
                output.setLevel(9)
                writeEntry(
                        output,
                        'META-INF/MANIFEST.MF',
                        ('Manifest-Version: 1.0\r\n' +
                                'Implementation-Title: NCL Skins Sources\r\n' +
                                "Implementation-Version: ${version}\r\n\r\n")
                                .getBytes(StandardCharsets.UTF_8))
                writeEntry(output, 'META-INF/LICENSE', Files.readAllBytes(root.resolve('LICENSE')))
                writeEntry(output, 'META-INF/NOTICE', Files.readAllBytes(root.resolve('NOTICE')))
                entries.each { String entry, Path source ->
                    writeEntry(output, entry, source)
                }
            }
            moveReplacing(temporary, destination.toPath())
        } finally {
            Files.deleteIfExists(temporary)
        }
        destination
    }

    static String sha256(File file) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        file.withInputStream { input ->
            byte[] buffer = new byte[8192]
            int read
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read)
        }
        digest.digest().collect { String.format('%02x', it & 0xff) }.join()
    }

    private static boolean forbidden(String entry) {
        if (entry.endsWith('.pixel.json')) return true
        entry.split('/').any { FORBIDDEN_SEGMENTS.contains(it) }
    }

    private static void writeEntry(ZipOutputStream output, String name, Path source) {
        ZipEntry entry = new ZipEntry(name)
        entry.time = 0L
        output.putNextEntry(entry)
        Files.copy(source, output)
        output.closeEntry()
    }

    private static void writeEntry(ZipOutputStream output, String name, byte[] bytes) {
        ZipEntry entry = new ZipEntry(name)
        entry.time = 0L
        output.putNextEntry(entry)
        output.write(bytes)
        output.closeEntry()
    }

    private static void moveReplacing(Path source, Path destination) {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private ReleaseBundle() {}
}

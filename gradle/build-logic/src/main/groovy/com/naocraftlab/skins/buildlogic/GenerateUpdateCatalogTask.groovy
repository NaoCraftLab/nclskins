package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@CacheableTask
abstract class GenerateUpdateCatalogTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getCatalogFile()

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getInventoryFile()

    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @Internal
    abstract DirectoryProperty getAllowedOutputRoot()

    @TaskAction
    void generate() {
        Path catalogPath = validateInput(catalogFile.get().asFile.toPath(), 'catalog')
        Path inventoryPath = validateInput(inventoryFile.get().asFile.toPath(), 'inventory')
        Path allowedRoot = allowedOutputRoot.get().asFile.toPath().toAbsolutePath().normalize()
        Path output = outputDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        validateOutput(allowedRoot, output, [catalogPath, inventoryPath])

        Map catalog = CatalogTools.loadJson(catalogPath)
        List<Map> inventory = GithubReleaseInventory.parse(catalog, Files.readAllBytes(inventoryPath))
        Path temporary = output.resolveSibling("${output.fileName}.tmp")
        deleteTree(temporary)
        UpdateCatalogSite.files(catalog, inventory).each { String relative, String content ->
            Path destination = temporary.resolve(relative).normalize()
            if (!destination.startsWith(temporary)) {
                throw new IllegalArgumentException('generated update catalog path escapes output')
            }
            Files.createDirectories(destination.parent)
            GenerateUpdateCatalogTask.write(destination, content)
        }
        deleteTree(output)
        try {
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE)
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, output)
        }
    }

    static Path validateInput(Path raw, String label) {
        Path path = raw.toAbsolutePath().normalize()
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path) ||
                path.any { Path segment ->
                    segment.toString() == 'openspec' || segment.toString() == '.agents'
                }) {
            throw new IllegalArgumentException("invalid update catalog ${label} input")
        }
        path
    }

    static void validateOutput(
            Path allowedRoot, Path output, Collection<Path> inputs) {
        if (output == allowedRoot || !output.startsWith(allowedRoot) ||
                Files.isSymbolicLink(allowedRoot) ||
                inputs.any { Path input -> input.startsWith(output) || output == input }) {
            throw new IllegalArgumentException(
                    'update catalog output must be an isolated child of the build directory')
        }
        Path current = allowedRoot
        for (Path segment : allowedRoot.relativize(output)) {
            current = current.resolve(segment)
            if (Files.exists(current) && Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException('update catalog output cannot traverse symlinks')
            }
        }
    }

    private static void write(Path destination, String content) {
        Files.writeString(destination, content, StandardCharsets.UTF_8)
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return
        Files.walk(root).sorted(Comparator.reverseOrder()).withCloseable { stream ->
            stream.forEach { Files.delete(it) }
        }
    }
}

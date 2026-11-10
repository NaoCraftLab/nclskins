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
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getInventoryFile()

    @Input abstract org.gradle.api.provider.Property<Boolean> getWorkflowSnapshot()
    @Input @Optional abstract org.gradle.api.provider.Property<String> getSourceCommit()
    @Input @Optional abstract org.gradle.api.provider.Property<String> getCallerCatalogCommit()
    @Input @Optional abstract org.gradle.api.provider.Property<String> getEventName()
    @Input @Optional abstract org.gradle.api.provider.Property<String> getEventRef()
    @Internal abstract DirectoryProperty getRepositoryDirectory()
    @Internal Closure inventoryTransport
    @Internal Closure branchTransport

    GenerateUpdateCatalogTask() {
        workflowSnapshot.convention(project.providers.environmentVariable('NCLSKINS_CATALOG_WORKFLOW').map { it == 'true' }.orElse(false))
        outputs.doNotCacheIf('Live GitHub inventory snapshot') { workflowSnapshot.get() }
        repositoryDirectory.convention(project.layout.projectDirectory)
        sourceCommit.convention(project.providers.environmentVariable('SOURCE_COMMIT'))
        callerCatalogCommit.convention(project.providers.environmentVariable('CALLER_CATALOG_COMMIT').orElse(''))
        eventName.convention(project.providers.environmentVariable('EVENT_NAME'))
        eventRef.convention(project.providers.environmentVariable('EVENT_REF'))
        outputs.upToDateWhen { !workflowSnapshot.get() }
    }

    void admitSource() {
        File root = repositoryDirectory.get().asFile
        String source = sourceCommit.getOrElse('')
        String caller = callerCatalogCommit.getOrElse('')
        if (!(source ==~ /[0-9a-f]{40}/) ||
                (!caller && (eventName.getOrElse('') != 'workflow_dispatch' || eventRef.getOrElse('') != 'refs/heads/main')) ||
                (caller && caller != source) || ReleaseSelection.git(root, ['rev-parse', 'HEAD']).trim() != source) {
            throw new IllegalStateException('Invalid pinned current-main catalog source')
        }
        String main = branchTransport != null ? branchTransport.call('main') :
                new groovy.json.JsonSlurper().parse(ReleaseHttp.readGithub(
                        'https://api.github.com/repos/' + repositoryName() + '/git/ref/heads/main',
                        System.getenv('GITHUB_TOKEN')).body as byte[]).object?.sha
        if (!(main ==~ /[0-9a-f]{40}/)) throw new IllegalStateException('Invalid current-main snapshot')
        if (!caller && source != main) throw new IllegalStateException('Manual current-main source drifted')
        if (caller && source != main) ReleaseSelection.git(root, ['merge-base', '--is-ancestor', source, main])
    }

    String repositoryName() {
        String repository = System.getenv('GITHUB_REPOSITORY')
        if (repository != 'NaoCraftLab/nclskins') throw new IllegalStateException('Wrong update catalog repository')
        repository
    }

    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @Internal
    abstract DirectoryProperty getAllowedOutputRoot()

    @TaskAction
    void generate() {
        Path catalogPath = validateInput(catalogFile.get().asFile.toPath(), 'catalog')
        if (workflowSnapshot.get()) admitSource()
        Path inventoryPath = inventoryFile.present ? validateInput(inventoryFile.get().asFile.toPath(), 'inventory') : null
        if (!workflowSnapshot.get() && inventoryPath == null) throw new IllegalArgumentException('Missing release inventory')
        Path allowedRoot = allowedOutputRoot.get().asFile.toPath().toAbsolutePath().normalize()
        Path output = outputDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        validateOutput(allowedRoot, output, [catalogPath, inventoryPath].findAll { it != null })

        Map catalog = CatalogTools.loadJson(catalogPath)
        byte[] inventoryBytes = workflowSnapshot.get() ? groovy.json.JsonOutput.toJson(
                GithubReleaseInventory.snapshot(inventoryTransport ?: { int page ->
                    ReleaseHttp.readGithub('https://api.github.com/repos/' + repositoryName() +
                            "/releases?per_page=100&page=${page}", System.getenv('GITHUB_TOKEN'))
                })).getBytes('UTF-8') : Files.readAllBytes(inventoryPath)
        List<Map> inventory = GithubReleaseInventory.parse(catalog, inventoryBytes)
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

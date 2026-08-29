package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

abstract class TargetBuildTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @Input
    abstract ListProperty<String> getTargetIds()

    @Input
    abstract ListProperty<String> getTargetTasks()

    @Input
    abstract Property<Boolean> getVerifyArtifacts()

    @Input
    abstract Property<Boolean> getCollectArtifacts()

    @Input
    abstract Property<Boolean> getVerifyCompatibility()

    @Internal
    abstract Property<Integer> getMaximumWorkers()

    @TaskAction
    void buildTargets() {
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        List<Map> targets = selectedTargetIds(catalog).collect { CatalogTools.selectTarget(catalog, it) }
        int workers = Math.max(1, Math.min(maximumWorkers.get(), targets.size()))
        def executor = Executors.newFixedThreadPool(workers)
        try {
            List<Future<String>> futures = targets.collect { Map target ->
                executor.submit({ runTarget(catalog, target) } as Callable<String>)
            }
            futures.each { logger.lifecycle(it.get()) }
            if (verifyCompatibility.get()) {
                String modVersion = CatalogTools.loadVersion(repositoryDirectory.get().asFile)
                targets.findAll { it.compatibility instanceof Map }.each { Map target ->
                    CompatibilityHarness.verify(
                            repositoryDirectory.get().asFile, catalog, target, modVersion)
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    List<String> selectedTargetIds(Map catalog) {
        targetIds.get()
    }

    String runTarget(Map catalog, Map target) {
        File root = repositoryDirectory.get().asFile
        File targetDirectory = new File(root, target.path.toString())
        File wrapper = TargetRuntime.wrapper(root, catalog, target)
        String javaHome = TargetRuntime.resolveJavaHome(target.java.buildJdk as int)
        List<String> command = [
                wrapper.absolutePath,
                "-PnclskinsSourceGraph=${sourceGraphFingerprint(root)}".toString(),
                "-PnclskinsBuildLogicWorkspace=${target.id}".toString(),
                '-p',
                targetDirectory.absolutePath
        ] + targetTasks.get()
        ProcessBuilder builder = new ProcessBuilder(command).directory(root)
        builder.redirectErrorStream(true)
        TargetRuntime.configureEnvironment(builder, javaHome)
        Process process = builder.start()
        String output = process.inputStream.getText('UTF-8')
        int exit = process.waitFor()
        if (exit != 0) {
            throw new IllegalStateException("${target.id} failed (${exit})\n${output}")
        }
        if (verifyArtifacts.get()) {
            List<String> errors = []
            String version = CatalogTools.loadVersion(new File(root, 'gradle/version.properties').toPath())
            ArtifactVerifier.verify(root, catalog, target, version, errors)
            if (!errors.isEmpty()) throw new IllegalStateException("${target.id} artifact verification failed:\n- ${errors.join('\n- ')}")
            if (collectArtifacts.get()) collect(catalog, target, version)
        }
        "${target.id}: ${targetTasks.get().join(' ')} passed"
    }

    static String sourceGraphFingerprint(File root) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        ['core', 'client-contract', 'client-runtime', 'server-contract', 'server-runtime',
         'server-vanilla-publication', 'compat', 'loader', 'targets'].each { String topLevel ->
            File directory = new File(root, topLevel)
            if (!directory.isDirectory()) return
            List<String> paths = []
            Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
                    if (path != directory.toPath() && path.fileName.toString() in ['build', '.gradle']) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    FileVisitResult.CONTINUE
                }

                @Override
                FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        paths.add(root.toPath().relativize(path).toString()
                                .replace(File.separatorChar, '/' as char))
                    }
                    FileVisitResult.CONTINUE
                }
            })
            paths.sort().each { String path ->
                digest.update(path.getBytes(StandardCharsets.UTF_8))
                digest.update((byte) 0)
            }
        }
        digest.digest().encodeHex().toString()
    }

    void collect(Map catalog, Map target, String version) {
        File root = repositoryDirectory.get().asFile
        File distribution = new File(root, "build/distributions/${version}")
        distribution.mkdirs()
        String production = target.artifact.file.toString().replace('{modVersion}', version)
        String sources = production.replace('.jar', '-sources.jar')
        [production, sources].each { String name ->
            File source = new File(root, "${target.path}/build/libs/${name}")
            if (!source.isFile()) throw new IllegalStateException("${target.id}: missing distributable artifact ${source}")
            java.nio.file.Files.copy(source.toPath(), new File(distribution, name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

}

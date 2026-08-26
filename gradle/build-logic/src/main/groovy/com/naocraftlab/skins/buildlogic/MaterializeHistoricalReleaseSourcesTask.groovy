package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

abstract class MaterializeHistoricalReleaseSourcesTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @InputFile
    abstract RegularFileProperty getVersionFile()

    @Input
    abstract Property<String> getReleaseTag()

    @InputDirectory
    abstract DirectoryProperty getExistingAssetsDirectory()

    @OutputDirectory
    abstract DirectoryProperty getSourcesDirectory()

    @TaskAction
    void materialize() {
        File repository = repositoryDirectory.get().asFile
        String version = CatalogTools.loadVersion(versionFile.get().asFile.toPath())
        if (releaseTag.get() != version) {
            throw new IllegalStateException(
                    "Historical source tag ${releaseTag.get()} differs from configured version ${version}")
        }
        Map currentCatalog = CatalogTools.loadCatalog(repository)
        HistoricalReleaseSources.requireReachableTag(repository, version)
        File existing = existingAssetsDirectory.get().asFile
        AssembleReleaseTask.validateExistingAssetSet(existing, currentCatalog, version)

        List<Map> historicalTargets = CatalogTools.releaseTargets(currentCatalog).findAll { Map target ->
            new File(existing, AssembleReleaseTask.artifactName(target, version)).isFile()
        }
        String pluginName = currentCatalog.serverPlugin.artifact.toString()
                .replace('{pluginVersion}', version)
        boolean includePlugin = new File(existing, pluginName).isFile()
        if (historicalTargets.isEmpty() && !includePlugin) {
            throw new IllegalStateException(
                    'Compatibility backfill requires at least one existing production JAR')
        }

        File output = sourcesDirectory.get().asFile
        if (output.exists() && !output.deleteDir()) {
            throw new IllegalStateException("Could not clean historical source output: ${output}")
        }
        Files.createDirectories(output.toPath())

        File worktree = Files.createTempDirectory('nclskins-historical-source-worktree-').toFile()
        if (!worktree.delete()) {
            throw new IllegalStateException("Could not prepare historical worktree path: ${worktree}")
        }
        boolean worktreeAdded = false
        try {
            ReleaseSelection.git(repository, [
                    'worktree', 'add', '--detach', worktree.absolutePath,
                    "refs/tags/${version}"
            ])
            worktreeAdded = true
            String tagCommit = HistoricalReleaseSources.requireTaggedCheckout(
                    repository, worktree, version)
            Map taggedCatalog = CatalogTools.loadCatalog(worktree)
            CatalogTools.validate(worktree, taggedCatalog)

            List<File> copied = []
            historicalTargets.each { Map currentTarget ->
                Map taggedTarget = CatalogTools.selectTarget(
                        taggedCatalog, currentTarget.id.toString())
                String expectedProduction = AssembleReleaseTask.artifactName(currentTarget, version)
                if (taggedTarget.releaseEligible != true ||
                        AssembleReleaseTask.artifactName(taggedTarget, version) != expectedProduction) {
                    throw new IllegalStateException(
                            "${currentTarget.id}: tagged target identity differs from existing production")
                }
                buildTaggedTarget(worktree, taggedCatalog, taggedTarget)
                String sourceName = AssembleReleaseTask.sourceArtifactName(taggedTarget, version)
                File source = new File(worktree, "${taggedTarget.path}/build/libs/${sourceName}")
                copied.add(copySource(source, new File(output, sourceName)))
            }
            if (includePlugin) {
                String currentSourceName = currentCatalog.serverPlugin.sourcesArtifact.toString()
                        .replace('{pluginVersion}', version)
                String taggedPluginName = taggedCatalog.serverPlugin.artifact.toString()
                        .replace('{pluginVersion}', version)
                String taggedSourceName = taggedCatalog.serverPlugin.sourcesArtifact.toString()
                        .replace('{pluginVersion}', version)
                if (taggedPluginName != pluginName || taggedSourceName != currentSourceName) {
                    throw new IllegalStateException(
                            'Tagged server plugin artifact identity differs from existing production')
                }
                runGradle(worktree, taggedCatalog.serverPlugin.packaging.buildJdk as int,
                        ['-PnclskinsBuildLogicWorkspace=historical-plugin',
                         ':server-plugin:sourcesJar'])
                copied.add(copySource(
                        new File(worktree, "server-plugin/build/libs/${taggedSourceName}"),
                        new File(output, taggedSourceName)))
            }
            HistoricalReleaseSources.writeIndex(output, version, tagCommit, copied)
            HistoricalReleaseSources.verify(
                    output, version, tagCommit, copied.collect { it.name } as Set<String>)
        } catch (Exception error) {
            output.deleteDir()
            throw error
        } finally {
            if (worktreeAdded) {
                try {
                    ReleaseSelection.git(repository, [
                            'worktree', 'remove', '--force', worktree.absolutePath])
                } catch (Exception cleanup) {
                    logger.warn("Could not remove historical source worktree: ${cleanup.message}")
                }
            }
            if (worktree.exists()) worktree.deleteDir()
        }
    }

    static void buildTaggedTarget(File worktree, Map catalog, Map target) {
        File targetDirectory = new File(worktree, target.path.toString())
        File wrapper = TargetRuntime.wrapper(worktree, catalog, target)
        String javaHome = TargetRuntime.resolveJavaHome(target.java.buildJdk as int)
        List<String> arguments = [
                "-PnclskinsSourceGraph=${TargetBuildTask.sourceGraphFingerprint(worktree)}".toString(),
                "-PnclskinsBuildLogicWorkspace=historical-${target.id}".toString(),
                '-p', targetDirectory.absolutePath, 'clean', 'build'
        ]
        runProcess([wrapper.absolutePath] + arguments, worktree, javaHome,
                "Historical target ${target.id}")
        List<String> errors = []
        ArtifactVerifier.verify(
                worktree, catalog, target, CatalogTools.loadVersion(worktree), errors)
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Historical target ${target.id} artifact verification failed:\n- " +
                    errors.join('\n- '))
        }
    }

    static void runGradle(File worktree, int javaVersion, List<String> arguments) {
        String javaHome = TargetRuntime.resolveJavaHome(javaVersion)
        runProcess([new File(worktree, 'gradlew').absolutePath] + arguments,
                worktree, javaHome, 'Historical server plugin sources')
    }

    static void runProcess(
            List<String> command, File directory, String javaHome, String description) {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory)
        builder.redirectErrorStream(true)
        TargetRuntime.configureEnvironment(builder, javaHome)
        Process process = builder.start()
        String output = process.inputStream.getText('UTF-8')
        int exit = process.waitFor()
        if (exit != 0) {
            throw new IllegalStateException(
                    "${description} failed (${exit})\n${output.takeRight(16000)}")
        }
    }

    static File copySource(File source, File destination) {
        if (!HistoricalReleaseSources.safeSourceName(source.name) ||
                !Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Missing or unsafe historical source artifact: ${source}")
        }
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        destination
    }
}

package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

abstract class ResetServerPluginBuildToolsRuntimeTask extends DefaultTask {
    ResetServerPluginBuildToolsRuntimeTask() {
        minecraftVersion.convention('1.20.1')
    }
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @Input
    abstract Property<String> getKernel()

    @Input
    abstract Property<String> getMinecraftVersion()

    @TaskAction
    void reset() {
        String selected = kernel.get()
        if (!(selected in ['craftbukkit', 'spigot'])) {
            throw new GradleException(
                    'serverPluginKernel must be craftbukkit or spigot')
        }
        File root = repositoryDirectory.get().asFile
        String minecraft = minecraftVersion.get()
        ServerPluginRuntimeSupport.buildToolsRuntime(CatalogTools.loadCatalog(root), minecraft)
        File source = new File(root,
                ".gradle/nclskins/server-runtimes/buildtools-${minecraft}/${selected}")
        if (!source.exists()) {
            logger.lifecycle("No BuildTools runtime exists for ${selected}")
            return
        }
        File trash = new File(root, '.gradle/nclskins/server-runtimes/.trash/' +
                "buildtools-${minecraft}-${selected}-${Instant.now().toEpochMilli()}")
        Files.createDirectories(trash.parentFile.toPath())
        try {
            Files.move(source.toPath(), trash.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), trash.toPath())
        }
        logger.lifecycle("Moved BuildTools runtime for ${selected} to recoverable ${trash}")
    }
}

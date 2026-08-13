package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

abstract class ResetServerPluginRuntimeTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @Input
    abstract Property<String> getTopologyId()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @TaskAction
    void reset() {
        File root = repositoryDirectory.get().asFile
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        Map topology = catalog.serverPluginTopologies.find { it.id == topologyId.get() } as Map
        if (topology == null) throw new GradleException("Unknown server plugin topology ${topologyId.get()}")
        File source = RunLayout.topologyDirectory(root, topology)
        if (!source.exists()) {
            logger.lifecycle("No runtime state exists for ${topologyId.get()}")
            return
        }
        File trash = RunLayout.topologyTrashDirectory(root, topology, Instant.now().toEpochMilli())
        Files.createDirectories(trash.parentFile.toPath())
        try {
            Files.move(source.toPath(), trash.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), trash.toPath())
        }
        logger.lifecycle("Moved ${topologyId.get()} runtime state to recoverable ${trash}")
    }
}

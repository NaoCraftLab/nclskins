package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

abstract class ComputeServerPluginReleaseStateTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @Input
    abstract Property<String> getReleaseTag()

    @OutputFile
    abstract RegularFileProperty getStateFile()

    @TaskAction
    void compute() {
        File repository = repositoryDirectory.get().asFile
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile)
        Map state = ServerPluginReleaseState.compute(repository, catalog, releaseTag.get())
        ServerPluginReleaseState.write(stateFile.get().asFile, state)
        logger.lifecycle("Server plugin release: publish=${state.publish}, reason=${state.reason}, " +
                "active=${state.activeVersion}")
    }
}

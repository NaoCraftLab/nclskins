package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class PrepareRunDirectoriesTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @TaskAction
    void prepare() {
        File root = repositoryDirectory.get().asFile
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        RunDirectorySupport.prepareAll(root, catalog)
        int modRuntimes = catalog.targets.sum { Map target ->
            CatalogTools.targetRuntimeSpecs(target).size()
        } as int
        int clients = modRuntimes * 2
        int backends = modRuntimes + catalog.serverPluginTopologies.sum { Map topology ->
            topology.mode == 'standalone' ? 1 : 2
        } as int
        logger.lifecycle("Prepared ${clients} client server lists and confirmed " +
                "EULA/authentication/operator lists for ${backends} managed backends")
    }
}

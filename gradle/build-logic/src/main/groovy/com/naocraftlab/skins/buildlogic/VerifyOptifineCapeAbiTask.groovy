package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

abstract class VerifyOptifineCapeAbiTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @InputFile
    abstract RegularFileProperty getOptifineJar()

    @InputFile
    abstract RegularFileProperty getMinecraftClientJar()

    @TaskAction
    void verify() {
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        OptifineCapeAbiVerifier.verify(catalog.optionalDependencies.optifine as Map,
                optifineJar.get().asFile, minecraftClientJar.get().asFile)
        logger.lifecycle('Pinned OptiFine I6 reconstructed cape ABI verification passed')
    }
}

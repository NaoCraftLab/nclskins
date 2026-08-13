package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

abstract class PatchNeoForgeRuntimeMetadataTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getInputArtifact()

    @OutputFile
    abstract RegularFileProperty getOutputArtifact()

    @TaskAction
    void patch() {
        NeoForgeRuntimeMetadata.patchYacl(
                inputArtifact.get().asFile.toPath(),
                outputArtifact.get().asFile.toPath())
    }
}

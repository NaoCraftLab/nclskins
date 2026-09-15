package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

abstract class PatchNeoForgeRuntimeMetadataTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getInputArtifact()

    @OutputFile
    abstract RegularFileProperty getOutputArtifact()

    @Input
    abstract Property<String> getArtifactKind()

    @TaskAction
    void patch() {
        switch (artifactKind.get()) {
            case 'yacl':
                NeoForgeRuntimeMetadata.patchYacl(
                        inputArtifact.get().asFile.toPath(), outputArtifact.get().asFile.toPath())
                break
            case 'sqlite':
                NeoForgeRuntimeMetadata.patchSqlite(
                        inputArtifact.get().asFile.toPath(), outputArtifact.get().asFile.toPath())
                break
            default:
                throw new IllegalArgumentException("Unsupported NeoForge runtime artifact kind: ${artifactKind.get()}")
        }
    }
}

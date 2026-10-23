package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

abstract class ExtractWaveyCapesAbiTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getPublicArtifact()

    @Input abstract Property<String> getSha512()

    @OutputFile abstract RegularFileProperty getOutputJar()

    @TaskAction
    void extract() {
        WaveyCapesCompileAbi.extract(publicArtifact.get().asFile, sha512.get(), outputJar.get().asFile)
    }
}

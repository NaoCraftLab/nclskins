package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

abstract class VerifyModMenuAbiTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @InputFile
    abstract RegularFileProperty getAbiFile()

    @Input
    abstract Property<String> getTargetId()

    @Classpath
    abstract ConfigurableFileCollection getResolutionClasspath()

    @TaskAction
    void verifyAbi() {
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        Map declaration = CatalogTools.loadJson(abiFile.get().asFile.toPath())
        File javap = new File(System.getProperty('java.home'), 'bin/javap')
        ModMenuAbiVerifier.verify(
                catalog, declaration, targetId.get(), resolutionClasspath.asPath, javap)
        logger.lifecycle("Mod Menu ABI verification passed for ${targetId.get()}")
    }
}

package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

abstract class CapabilityAbiTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @InputFile
    abstract RegularFileProperty getAbiFile()

    @Input
    abstract Property<String> getTargetId()

    @Input
    abstract Property<String> getMode()

    @Classpath
    abstract ConfigurableFileCollection getResolutionClasspath()

    @TaskAction
    void executeVerification() {
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        Map abi = CatalogTools.loadJson(abiFile.get().asFile.toPath())
        File javap = new File(System.getProperty('java.home'), 'bin/javap')
        Map resolved = AbiVerifier.resolve(catalog, abi, targetId.get(), resolutionClasspath.asPath, javap)
        if (mode.get() == 'capture') {
            println CatalogTools.json(resolved)
            return
        }
        AbiVerifier.verify(catalog, abi, targetId.get(), resolved)
        logger.lifecycle("Capability ABI verification passed for ${targetId.get()}")
    }
}

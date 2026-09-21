package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

abstract class VerifyFancyMenuAbiTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @Input
    abstract Property<String> getTargetId()

    @Classpath
    abstract ConfigurableFileCollection getResolutionClasspath()

    @TaskAction
    void verifyAbi() {
        FancyMenuAbiVerifier.verify(resolutionClasspath.asPath,
                new File(System.getProperty('java.home'), 'bin/javap'))
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        FancyMenuAbiVerifier.verifyMetadata(resolutionClasspath.files,
                CatalogTools.selectTarget(catalog, targetId.get()),
                !catalog.optionalDependencies.fancymenu.runtimeUnavailableTargets.contains(targetId.get()))
    }
}

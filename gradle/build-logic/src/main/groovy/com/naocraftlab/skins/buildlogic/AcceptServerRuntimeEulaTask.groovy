package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import java.time.Instant

abstract class AcceptServerRuntimeEulaTask extends DefaultTask {
    @Input
    abstract Property<Boolean> getAccepted()

    @OutputFile
    abstract RegularFileProperty getMarkerFile()

    @TaskAction
    void accept() {
        if (!accepted.getOrElse(false)) {
            throw new GradleException(
                    'Explicit acceptance required: ./gradlew acceptServerRuntimeEula ' +
                    '-PnclskinsAcceptMinecraftEula=true')
        }
        File marker = markerFile.get().asFile
        ServerPluginRuntimeSupport.writeAtomic(marker.toPath(),
                ServerPluginRuntimeSupport.eulaMarker(Instant.now().toString()))
        ServerPluginRuntimeSupport.restrict(marker.parentFile.toPath(), true)
        ServerPluginRuntimeSupport.restrict(marker.toPath(), false)
        logger.lifecycle("Recorded local Minecraft EULA acceptance at ${marker}")
    }
}

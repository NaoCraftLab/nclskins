package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.TaskAction

abstract class VerifyArchitectureTask extends DefaultTask {
    @Classpath
    abstract ConfigurableFileCollection getClassRoots()

    @TaskAction
    void verify() {
        List<String> errors = ArchitectureBoundaries.verify(classRoots.files)
        if (!errors.empty) throw new IllegalStateException('Architecture boundaries failed:\n' + errors.join('\n'))
    }
}

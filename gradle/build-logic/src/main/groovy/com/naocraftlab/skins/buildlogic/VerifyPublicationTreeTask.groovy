package com.naocraftlab.skins.buildlogic

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class VerifyPublicationTreeTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @TaskAction
    void verify() {
        File root = repositoryDirectory.get().asFile
        List<String> errors = PublicationTreeVerifier.verify(root.toPath())
        if (!errors.isEmpty()) throw new IllegalStateException('Publication tree verification failed:\n- ' + errors.join('\n- '))
        logger.lifecycle('Publication tree contains no agent tooling, comments, local paths, or credential patterns')
    }
}

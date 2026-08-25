package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import java.nio.file.Files
import java.nio.file.Path

abstract class VerifyUpdateCatalogDeploymentTask extends DefaultTask {
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getSiteDirectory()

    @TaskAction
    void verifyDeployment() {
        Path root = siteDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException('invalid update catalog site directory')
        }
        Map<String, byte[]> expected = new TreeMap<>()
        Files.walk(root).withCloseable { stream ->
            stream.filter { Path path ->
                Files.isRegularFile(path) && path.fileName.toString().endsWith('.json')
            }.forEach { Path path ->
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalArgumentException('update catalog site cannot contain symlinks')
                }
                String relative = root.relativize(path).toString()
                        .replace(File.separatorChar, '/' as char)
                expected[relative] = Files.readAllBytes(path)
            }
        }
        UpdateCatalogDeploymentProbe.verify(
                expected,
                UpdateCatalogDeploymentProbe.jdkFetcher(),
                { Thread.sleep(5000) } as UpdateCatalogDeploymentProbe.Sleeper)
    }
}

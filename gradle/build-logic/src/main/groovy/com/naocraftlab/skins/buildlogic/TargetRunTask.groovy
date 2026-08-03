package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class TargetRunTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @Input
    abstract Property<String> getTargetId()

    @Input
    abstract Property<String> getRunKind()

    @Input
    abstract Property<Boolean> getDryRun()

    @TaskAction
    void runTarget() {
        File root = repositoryDirectory.get().asFile
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        Map target = CatalogTools.selectTarget(catalog, targetId.get())
        String kind = runKind.get()
        if (!(kind in ['Client', 'Server'])) throw new IllegalStateException("Unsupported target run kind ${kind}")
        String javaHome = TargetRuntime.resolveJavaHome(target.java.buildJdk as int)
        List<String> command = command(root, catalog, target, kind, dryRun.get())
        ProcessBuilder builder = new ProcessBuilder(command).directory(root).inheritIO()
        TargetRuntime.configureEnvironment(builder, javaHome)
        Process process = builder.start()
        try {
            int exit = process.waitFor()
            if (exit != 0) throw new IllegalStateException("${target.id} run${kind} failed (${exit})")
        } catch (InterruptedException error) {
            process.destroy()
            Thread.currentThread().interrupt()
            throw new IllegalStateException("${target.id} run${kind} interrupted", error)
        }
    }

    static List<String> command(File root, Map catalog, Map target, String kind, boolean dryRun) {
        File targetDirectory = new File(root, target.path.toString())
        File wrapper = TargetRuntime.wrapper(root, catalog, target)
        String nativeTask = "run${kind}".toString()
        List<String> command = [wrapper.absolutePath, '-p', targetDirectory.absolutePath, '--no-daemon', nativeTask]
        if (kind == 'Server') {
            int port = target.development.serverPort as int
            if (target.loader.id == 'neoforge') command.add("-PnclskinsServerPort=${port}".toString())
            else command.add("--args=--port ${port}".toString())
        }
        if (dryRun) command.add('--dry-run')
        command
    }
}

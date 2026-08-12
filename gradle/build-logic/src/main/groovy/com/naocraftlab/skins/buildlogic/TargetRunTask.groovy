package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult

import javax.inject.Inject

abstract class TargetRunTask extends DefaultTask {
    @Inject
    abstract ExecOperations getExecOperations()

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
        if (!(kind in IdeaRunConfigurations.RUN_KINDS)) {
            throw new IllegalStateException("Unsupported target run kind ${kind}")
        }
        String javaHome = TargetRuntime.resolveJavaHome(target.java.buildJdk as int)
        List<String> command = command(root, catalog, target, kind, dryRun.get())
        String path = new File(javaHome, 'bin').absolutePath + File.pathSeparator +
                (System.getenv('PATH') ?: '')
        ExecResult result = execOperations.exec {
            commandLine command
            workingDir root
            environment 'JAVA_HOME', javaHome
            environment 'PATH', path
            standardInput = System.in
            standardOutput = System.out
            errorOutput = System.err
            ignoreExitValue = true
        }
        int exit = result.exitValue
        if (exit != 0) throw new IllegalStateException("${target.id} run${kind} failed (${exit})")
    }

    static List<String> command(File root, Map catalog, Map target, String kind, boolean dryRun) {
        File targetDirectory = new File(root, target.path.toString())
        File wrapper = TargetRuntime.wrapper(root, catalog, target)
        String nativeTask = kind == 'LicensedClient' ? 'runClientLicensed' : "run${kind}".toString()
        List<String> command = [wrapper.absolutePath, '-p', targetDirectory.absolutePath, '--no-daemon', nativeTask]
        if (kind == 'Server') {
            int port = target.development.serverPort as int
            Optional<String> property = LoaderBackend.require(target.loader.id.toString())
                    .serverPortProperty(port)
            command.add(property.orElse("--args=--port ${port}".toString()))
        }
        if (dryRun) command.add('--dry-run')
        command
    }
}

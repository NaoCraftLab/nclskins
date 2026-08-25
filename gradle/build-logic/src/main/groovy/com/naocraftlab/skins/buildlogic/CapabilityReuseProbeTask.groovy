package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files

abstract class CapabilityReuseProbeTask extends DefaultTask {
    private final Set<String> executedSemanticSuites = [] as Set

    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @InputFile
    abstract RegularFileProperty getAbiFile()

    @InputFile
    abstract RegularFileProperty getCoverageFile()

    @Input
    abstract Property<String> getTargetId()

    @Input
    @Optional
    abstract Property<String> getCapabilityKey()

    @Input
    abstract Property<Boolean> getCompileCandidates()

    @OutputFile
    abstract RegularFileProperty getReportFile()

    @TaskAction
    void probe() {
        File root = repositoryDirectory.get().asFile
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        Map abi = CatalogTools.loadJson(abiFile.get().asFile.toPath())
        Map coverage = CatalogTools.loadJson(coverageFile.get().asFile.toPath())
        Map target = CatalogTools.selectTarget(catalog, targetId.get())
        List<String> capabilities = capabilityKey.getOrElse('').isBlank()
                ? CatalogTools.REQUIRED_CAPABILITIES.toList().sort()
                : [capabilityKey.get()]
        Map<String, Object> selections = new LinkedHashMap<>()
        capabilities.each { String capability ->
            List<Map> inspected = CapabilityReuse.candidates(catalog, target, capability)
                    .collect {
                        CapabilityReuse.inspect(
                                root, catalog, abi, coverage, target, capability, it)
                    }
            Map accepted = compileCandidates.get()
                    ? this.compileUntilCompatible(
                    root, catalog, abi, coverage, target, capability, inspected)
                    : inspected.find { it.staticStatus == 'COMPATIBLE' }
            if (accepted == null) {
                selections[capability] = [status: 'NO_REUSABLE_IMPLEMENTATION', candidates: inspected]
                this.writeReport(target, selections)
                throw new IllegalStateException(
                        "${target.id}/${capability}: no reusable implementation; see ${reportFile.get().asFile}")
            }
            String selected = (target.capabilities as Map)[capability].toString()
            if (accepted.implementation != selected) {
                selections[capability] = [
                        status    : 'CATALOG_SELECTION_VIOLATES_REUSE_FIRST',
                        selected  : selected,
                        accepted  : accepted.implementation,
                        candidates: inspected
                ]
                this.writeReport(target, selections)
                CapabilityReuse.requireReuseFirstSelection(
                        target.id.toString(),
                        capability,
                        selected,
                        accepted.implementation.toString())
            }
            selections[capability] = [
                    status    : compileCandidates.get()
                            ? 'COMPILED_ABI_AND_SEMANTIC_TCK_PASSED'
                            : 'STATICALLY_VALIDATED',
                    selected  : selected,
                    candidates: inspected
            ]
        }
        this.writeReport(target, selections)
        logger.lifecycle(
                "Capability reuse probe passed for ${target.id}: ${capabilities.size()} selections")
    }

    Map compileUntilCompatible(
            File root,
            Map catalog,
            Map abi,
            Map coverage,
            Map target,
            String capability,
            List<Map> candidates) {
        for (Map candidate : candidates) {
            if (candidate.staticStatus != 'COMPATIBLE') continue
            String override = "${capability}=${candidate.implementation}"
            File wrapper = TargetRuntime.wrapper(root, catalog, target)
            File targetDirectory = new File(root, target.path.toString())
            String compileTask = LoaderBackend.require(target.loader.id.toString())
                    .clientCompileTask(target)
            List<String> command = [
                    wrapper.absolutePath,
                    '-p', targetDirectory.absolutePath,
                    compileTask,
                    'processResources',
                    'verifyDedicatedServerIsolation',
                    'captureCapabilityAbi',
                    '-q',
                    '--no-configuration-cache',
                    "-PnclskinsCapabilityProbe=${override}"
            ]
            boolean externalAbi = CatalogTools.EXTERNAL_ABI_CAPABILITIES.contains(capability)
            if (externalAbi && target.loader.id == 'fabric') {
                command.add(command.indexOf('captureCapabilityAbi'), 'verifyModMenuAbi')
            }
            ProcessBuilder builder = new ProcessBuilder(
                    command.collect { it.toString() }).directory(root)
            builder.redirectErrorStream(true)
            TargetRuntime.configureEnvironment(
                    builder, TargetRuntime.resolveJavaHome(target.java.buildJdk as int))
            Process process = builder.start()
            String output = process.inputStream.getText(StandardCharsets.UTF_8.name())
            int exit = process.waitFor()
            candidate.compileExit = exit
            if (exit != 0) {
                candidate.failures.add('target compilation failed')
                candidate.compileTail = output.readLines().takeRight(30)
                continue
            }
            if (!externalAbi) {
                Map captured = this.parseCapturedAbi(output)
                Map actual = captured[candidate.abiImplementation] as Map
                if (!CapabilityReuse.matchesDeclaredAbi(
                        abi, candidate.abiImplementation?.toString(), actual)) {
                    candidate.failures.add('resolved ABI differs from every declared profile baseline')
                    continue
                }
            }
            if (!this.runSemanticSuite(root, coverage, candidate)) {
                candidate.failures.add(
                        "semantic suite ${candidate.semanticSuite} failed: ${candidate.semanticTestIds}")
                continue
            }
            candidate.probeStatus = externalAbi
                    ? 'COMPILED_EXTERNAL_ABI_AND_SEMANTIC_TCK_PASSED'
                    : 'COMPILED_ABI_AND_SEMANTIC_TCK_PASSED'
            return candidate
        }
        null
    }

    boolean runSemanticSuite(File root, Map coverage, Map candidate) {
        String suite = candidate.semanticSuite?.toString()
        if (suite == null) return false
        if (!executedSemanticSuites.add(suite)) return true
        Map declaration = (coverage.sharedSuites as Map)[suite] as Map
        Set<String> modules = (declaration.tests as List).collect { Object source ->
            source.toString().split('/', 2)[0]
        } as Set
        List<String> command = [
                new File(root, 'gradlew').absolutePath,
                '--no-configuration-cache'
        ]
        command.addAll(modules.sort().collect { String module -> ":${module}:test" })
        ProcessBuilder builder = new ProcessBuilder(
                command.collect { it.toString() }).directory(root)
        builder.redirectErrorStream(true)
        Process process = builder.start()
        String output = process.inputStream.getText(StandardCharsets.UTF_8.name())
        int exit = process.waitFor()
        if (exit == 0) return true
        candidate.semanticTail = output.readLines().takeRight(30)
        false
    }

    static Map parseCapturedAbi(String output) {
        int start = output.indexOf('{')
        int end = output.lastIndexOf('}')
        if (start < 0 || end < start) {
            throw new IllegalStateException('captureCapabilityAbi produced no JSON object')
        }
        new JsonSlurper().parseText(output.substring(start, end + 1)) as Map
    }

    void writeReport(Map target, Map selections) {
        Map report = [
                schemaVersion: 1,
                target       : target.id,
                minecraft    : target.minecraft.version,
                loader       : target.loader.id,
                selections   : selections
        ]
        File output = reportFile.get().asFile
        Files.createDirectories(output.toPath().parent)
        Files.writeString(output.toPath(), CatalogTools.json(report), StandardCharsets.UTF_8)
    }
}

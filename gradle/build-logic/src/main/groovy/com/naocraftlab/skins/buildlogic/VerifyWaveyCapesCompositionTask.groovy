package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeoutException
import java.util.zip.ZipFile

abstract class VerifyWaveyCapesCompositionTask extends DefaultTask {
    VerifyWaveyCapesCompositionTask() {
        runtimeProfile.convention('isolated')
        prepareOnly.convention(false)
        guardOnly.convention(false)
        guardRoster.convention([])
        abiSdkPin.convention([:])
    }
    @Input abstract Property<String> getMode()
    @Input abstract Property<Boolean> getSubmissionPreview()
    @Input abstract Property<String> getTargetId()
    @Input abstract Property<Integer> getJavaRelease()
    @Input abstract Property<String> getRuntimeProfile()
    @Input abstract Property<Boolean> getPrepareOnly()
    @Input abstract Property<Boolean> getGuardOnly()
    @Input abstract ListProperty<String> getGuardRoster()
    @Input abstract MapProperty<String, String> getAbiSdkPin()
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getIsolatedSdk()
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getGuardReceipt()
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) abstract ConfigurableFileCollection getLoaderSources()
    @Classpath abstract ConfigurableFileCollection getLoaderRuntimeClasspath()
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getLoaderVmArgs()
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getLoaderProgramArgs()
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) abstract ConfigurableFileCollection getProbeSources()
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) abstract ConfigurableFileCollection getMixinConfigurations()
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) abstract DirectoryProperty getServiceResources()
    @Classpath abstract ConfigurableFileCollection getResolutionClasspath()
    @Classpath abstract ConfigurableFileCollection getExecutableClasspath()
    @Classpath abstract ConfigurableFileCollection getResourceClasspath()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getCompilerExecutable()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getJavaExecutable()
    @OutputDirectory abstract DirectoryProperty getWorkDirectory()

    private static String sha256(File file) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        file.withInputStream { input ->
            byte[] buffer = new byte[65536]
            for (int count = input.read(buffer); count >= 0; count = input.read(buffer)) {
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        digest.digest().encodeHex().toString()
    }

    private static String directoryHash(File root) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        List<File> files = []
        root.eachFileRecurse { if (it.isFile()) files.add(it) }
        files.sort { root.toPath().relativize(it.toPath()).toString() }.each { File file ->
            String relative = root.toPath().relativize(file.toPath()).toString()
            digest.update((relative + '\u0000' + VerifyWaveyCapesCompositionTask.sha256(file) + '\n').getBytes(StandardCharsets.UTF_8))
        }
        digest.digest().encodeHex().toString()
    }

    private static File ownerJar(List<File> classpath, String entry) {
        File result = classpath.find { File file ->
            if (!file.isFile() || !file.name.endsWith('.jar')) return false
            new ZipFile(file).withCloseable { archive -> archive.getEntry(entry) != null }
        }
        if (result == null) throw new GradleException("Missing genuine native/public class ${entry}")
        result
    }

    String completeDirect(Map receipt) {
        WaveyCompositionGuards.complete(this, receipt)
        prepareOnly.get() ? 'PREPARED_NOT_STARTED' : 'PASS'
    }

    protected Process startCompositionProcess(List<String> argv, File work, File log) {
        new ProcessBuilder(argv).directory(work).redirectErrorStream(true).redirectOutput(log).start()
    }

    protected String compositionLogHash(File log) {
        VerifyWaveyCapesCompositionTask.sha256(log)
    }

    protected void saveCompositionReceipt(File resultFile, Map receipt) {
        resultFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(receipt))
    }

    void runCompositionProcess(File work, Map receipt, Closure save, String name, List command, int expected = 0, String marker = null) {
        File log = new File(work, "${name}.log")
        List<String> argv = command.collect { it.toString() }
        long started = System.nanoTime()
        Integer exit = null
        Throwable primary = null
        boolean interrupted = false
        boolean awaited = false
        Map entry = [name: name, argv: argv, expectedExit: expected, log: log.absolutePath, marker: marker]
        Closure failed = { Throwable failure ->
            if (primary == null) primary = failure
            else if (!primary.is(failure)) primary.addSuppressed(failure)
            interrupted |= failure instanceof InterruptedException
        }
        Process process = startCompositionProcess(argv, work, log)
        try {
            entry.pid = process.pid()
            entry.parentPid = ProcessHandle.current().pid()
            receipt.activeProcess = [name: name, pid: entry.pid, parentPid: entry.parentPid]
            save()
            awaited = true
            exit = CompositionProcess.await(process, 120_000L, 5_000L)
            if (exit != expected || marker != null && !log.getText('UTF-8').contains(marker))
                throw new GradleException("${targetId.get()}: ${name} failed (exit ${exit}, expected ${expected}); ${log}")
        } catch (Throwable failure) { failed(failure) }
        finally {
            interrupted |= Thread.interrupted()
            try {
                if (!awaited) {
                    try { CompositionProcess.await(process, 0L, 5_000L) }
                    catch (TimeoutException cleanupTimeout) { cleanupTimeout.suppressed.each { failed(it) } }
                    catch (Throwable failure) { failed(failure) }
                }
                interrupted |= Thread.interrupted()
                try {
                    entry.reaped = !process.isAlive()
                    entry.exit = entry.reaped ? process.exitValue() : null
                    entry.elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000D
                    entry.sha256 = compositionLogHash(log)
                } catch (Throwable failure) { failed(failure) }
                try {
                    entry.failureClass = primary?.class?.name
                    receipt.commands.add(entry)
                } catch (Throwable failure) { failed(failure) }
                try { receipt.remove('activeProcess') }
                catch (Throwable failure) { failed(failure) }
                try { save() }
                catch (Throwable failure) { failed(failure) }
            } finally {
                interrupted |= Thread.interrupted()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        if (primary != null) throw primary
    }

    @TaskAction
    void verifyComposition() {
        String kind = mode.get()
        boolean immediate = kind in ['rendered-buffer', 'mesh']
        if (!(immediate || kind in ['prepared', 'buffered', 'combined'])) throw new GradleException("Unknown composition mode ${kind}")
        File work = workDirectory.get().asFile
        project.delete(work)
        work.mkdirs()
        List<File> compilerInputs = resolutionClasspath.files.findAll { it.exists() }.toList()
        List<File> resources = resourceClasspath.files.findAll { it.exists() }.toList()
        List<File> classpath = (executableClasspath.files + resourceClasspath.files).findAll { it.exists() }.toList().unique()
        List<File> sources = probeSources.files.sort { it.absolutePath }
        if ((sources + classpath + compilerInputs).any { it.toPath().any { part -> part.toString() == '.agents' } })
            throw new GradleException('Canonical composition may not depend on agent/research inputs')
        Map receipt = [task: path, target: targetId.get(), mode: kind, submissionPreview: submissionPreview.get(), javaRelease: javaRelease.get(),
                sources: sources.collectEntries { [(it.absolutePath): VerifyWaveyCapesCompositionTask.sha256(it)] },
                mixins: mixinConfigurations.files.collectEntries { [(it.name): VerifyWaveyCapesCompositionTask.sha256(it)] },
                compiler: [path: compilerExecutable.get().asFile.absolutePath, sha256: VerifyWaveyCapesCompositionTask.sha256(compilerExecutable.get().asFile)],
                java: [path: javaExecutable.get().asFile.absolutePath, sha256: VerifyWaveyCapesCompositionTask.sha256(javaExecutable.get().asFile)],
                classpath: classpath.collect { [path: it.absolutePath, sha256: it.isDirectory() ? VerifyWaveyCapesCompositionTask.directoryHash(it) : VerifyWaveyCapesCompositionTask.sha256(it)] },
                compilerClasspath: compilerInputs.collect { [path: it.absolutePath, sha256: it.isDirectory() ? VerifyWaveyCapesCompositionTask.directoryHash(it) : VerifyWaveyCapesCompositionTask.sha256(it)] },
                resourceClasspath: resources.collect { [path: it.absolutePath, sha256: it.isDirectory() ? VerifyWaveyCapesCompositionTask.directoryHash(it) : VerifyWaveyCapesCompositionTask.sha256(it)] },
                commands: [], result: 'RUNNING', nativeGpuVisual: 'NOT_RUN',
                abortScope: 'differential own-retention only; confirmed native-driver recovery limitation is NOT_ACCEPTED']
        File resultFile = new File(work, 'result.json')
        Closure save = { saveCompositionReceipt(resultFile, receipt) }
        Closure run = { String name, List command, int expected = 0, String marker = null ->
            runCompositionProcess(work, receipt, save, name, command, expected, marker)
        }
        try {
            if (runtimeProfile.get() != 'isolated') {
                WaveyLoaderComposition.verify(this, work, compilerInputs, classpath, sources, receipt, run, save)
                receipt.result = completeDirect(receipt)
                save()
                return
            }
            File fixtures = new File(work, 'fixtures'); fixtures.mkdirs()
            File transformed = new File(work, 'transformed'); transformed.mkdirs()
            File bound = new File(work, 'bound'); bound.mkdirs()
            String compileClasspath = compilerInputs.collect { it.absolutePath }.join(File.pathSeparator)
            run('compiler-version', [compilerExecutable.get().asFile, '-version'])
            run('java-version', [javaExecutable.get().asFile, '-version'])
            run('compile', [compilerExecutable.get().asFile, '-proc:none', '--release', javaRelease.get(),
                    '-encoding', 'UTF-8', '-cp', compileClasspath, '-d', fixtures] + sources)
            project.copy { from(serviceResources); into(fixtures) }
            project.copy { from(mixinConfigurations); into(fixtures) }
            if (prepareOnly.get()) {
                receipt.plannedNativeCases = immediate ? ['public-legacy-preview'] : submissionPreview.get()
                        ? ['native-enchanted-elytra', 'preview-publication'] : ['extraction-baked-public']
                receipt.executedNativeCases = 0
                receipt.plannedFmlContexts = 0
                receipt.executedFmlContexts = 0
                receipt.result = 'PREPARED_NOT_STARTED'
                save()
                return
            }
            String fixtureClasspath = ([fixtures] + classpath).collect { it.absolutePath }.join(File.pathSeparator)
            String prefix = 'com.naocraftlab.skins.buildlogic.probe.wavey.'
            List java = [javaExecutable.get().asFile, '-Xmx512m', '-Djava.awt.headless=true',
                    '--add-opens', 'java.base/java.lang=ALL-UNNAMED']
            File wavey = VerifyWaveyCapesCompositionTask.ownerJar(classpath, 'dev/tr7zw/waveycapes/render/CapeNodeCollector.class')
            File nativeJar = VerifyWaveyCapesCompositionTask.ownerJar(classpath, immediate
                    ? 'net/minecraft/client/renderer/RenderType.class' : 'net/minecraft/client/renderer/rendertype/RenderType.class')
            if (kind == 'combined') {
                run('bind-combined-terminals', java + ['-cp', fixtureClasspath, prefix + 'BindCombinedCapeTerminals', bound])
            } else if (immediate) {
                run('bind-immediate-terminals', java + ['-cp', fixtureClasspath, prefix + 'BindImmediateCapeProbe', wavey, nativeJar, bound, kind])
                run('bind-immediate-preview-inputs', java + ['-cp', fixtureClasspath, prefix + 'BindImmediatePreviewInputs', bound, kind])
            } else {
                run('bind-geometry', java + ['-cp', fixtureClasspath, prefix + (kind == 'prepared' ? 'BindCapeGeometry' : 'BindBufferedCapeGeometry'), wavey, bound])
                run('bind-terminals', java + ['-cp', fixtureClasspath, prefix + (kind == 'prepared' ? 'BindCapeTerminals' : 'BindBufferedCapeTerminals'), nativeJar, bound])
            }
            if (!immediate && !submissionPreview.get()) run('bind-baked-preview-inputs', java + ['-Dnclskins.wavey.mode=' + kind, '-Dnclskins.wavey.extraction=true', '-cp', fixtureClasspath, prefix + 'BindBakedPreviewInputs', bound])
            String configs = kind == 'combined' ? 'nclskins.cape-pass-prepared.mixins.json' : immediate ? 'nclskins.cape-pass-immediate.mixins.json' : kind == 'prepared' ? 'nclskins.cape-pass-prepared.mixins.json,nclskins.cape-foil-pipeline.mixins.json'
                    : 'nclskins.cape-pass-buffered.mixins.json,nclskins.cape-foil-pipeline.mixins.json'
            String weaveClasspath = ([bound, fixtures] + classpath).collect { it.absolutePath }.join(File.pathSeparator)
            run('weave', java + ['-Dmixin.debug.countInjections=true', '-cp', weaveClasspath, prefix + (kind == 'combined' ? 'WeaveCombinedCapeProbe' : immediate ? 'WeaveImmediateCapeProbe' : kind == 'prepared' ? 'WeaveCapeProbe' : 'WeaveBufferedCapeProbe'), transformed, configs])
            File geometry = new File(work, 'geometry')
            File stateWoven = new File(work, 'state')
            if (kind == 'combined') {
                run('bind-combined-geometry', java + ['-cp', fixtureClasspath, prefix + 'BindCombinedCapeGeometry', geometry])
                run('weave-combined-state', java + ['-cp', fixtureClasspath, prefix + 'WeaveCapePublication', stateWoven,
                        'missing-publication.mixins.json,preview-state.mixins.json'])
            }
            File publicationRaw = new File(work, 'publication-raw')
            File publicationWoven = new File(work, 'publication-woven')
            File publicationMissing = new File(work, 'publication-missing')
            File previewRaw = new File(work, 'preview-raw')
            File previewMissing = new File(work, 'preview-missing')
            if (!immediate) {
                run('bind-publication', java + ['-cp', fixtureClasspath, prefix + 'BindCapePublicationInputs', publicationRaw])
                if (!submissionPreview.get()) {
                    run('bind-preview-publication', java + ['-cp', fixtureClasspath, prefix + 'BindExtractionPublicationInputs', previewRaw])
                    run('bind-missing-preview-publication', java + ['-cp', fixtureClasspath, prefix + 'BindExtractionPublicationInputs', previewMissing, 'missing'])
                }
                String publicationWeave = ((submissionPreview.get() ? [] : [previewRaw]) + [publicationRaw, bound, fixtures] + classpath).collect { it.absolutePath }.join(File.pathSeparator)
                String previewConfig = submissionPreview.get() ? '' : ',preview-state.mixins.json'
                List bakedGui = submissionPreview.get() ? [] : ['baked']
                run('weave-publication', java + ['-cp', publicationWeave, prefix + 'WeaveCapePublication', publicationWoven,
                        'publication.mixins.json' + previewConfig] + bakedGui)
                run('weave-missing-publication', java + ['-cp', publicationWeave, prefix + 'WeaveCapePublication', publicationMissing,
                        'missing-publication.mixins.json' + previewConfig] + bakedGui)
            }
            List<File> consumerInputs = kind == 'combined' ? [stateWoven, transformed, geometry, bound, fixtures]
                    : submissionPreview.get() ? [publicationWoven, transformed, publicationRaw, bound, fixtures] : [transformed, bound, fixtures]
            String runtimeClasspath = (consumerInputs + classpath).collect { it.absolutePath }.join(File.pathSeparator)
            Closure scenario = { String name, String probe, List args = [], List properties = [], int exit = 0, String marker = null ->
                run(name, java + properties + ['-cp', runtimeClasspath, prefix + probe] + args, exit, marker)
            }
            if (immediate) {
                scenario('public-legacy-preview', 'ImmediatePublicPreviewProbe', [new File(work, 'public-preview')], [], 0, 'LEGACY_PUBLIC_PREVIEW_PASS')
                ['world', 'inspection', 'menu'].each { String context ->
                    ['live', 'stale-before', 'stale-during', 'stale-after', 'foreign-first', 'foreign-last', 'geometry-abort'].each { String state ->
                        String name = "immediate-${context}-${state}"
                        scenario(name, 'ImmediateCapeProbe', [new File(work, name), context, state], [], 0, 'IMMEDIATE_COMPOSITION_PASS')
                    }
                }
                scenario('immediate-no-support-sensitivity', 'ImmediateCapeProbe', [new File(work, 'no-support-data'), 'world', 'live'],
                        ['-Dnclskins.wavey.noSupport=true'], 1, 'IMMEDIATE_OWN_FOIL_MISSING')
                scenario('immediate-unmarked-abort', 'ImmediateCapeProbe', [new File(work, 'unmarked-abort-data'), 'world', 'geometry-abort'],
                        ['-Dnclskins.wavey.noSupport=true'], 0, 'IMMEDIATE_COMPOSITION_PASS')
            } else if (kind == 'combined') {
                ['world', 'worldless'].each { scope ->
                    ['foreign-first', 'foreign-last'].each { order ->
                        ['SMOOTH', 'BLOCKY'].each { style ->
                            ['normal', 'ACCUMULATE', 'DEPTH_BOUNDS', 'TRANSMITTANCE'].each { stage ->
                                List states = stage in ['normal', 'ACCUMULATE']
                                        ? ['live', 'stale-before', 'stale-after', 'retire-build', 'retire-draw', 'own-exception', 'foreign-exception', 'draw-exception']
                                        : ['live', 'stale-after']
                                states.each { status ->
                                    String name = "combined-${scope}-${status}-${order}-${stage}-${style}"
                                    scenario(name, 'CombinedCapeProbe', [new File(work, name), scope, status, order, stage, style], [], 0, 'COMBINED_COMPOSITION_PASS')
                                }
                            }
                        }
                    }
                }
                ['no-support', 'wrong-material'].each { mutation ->
                    ['SMOOTH', 'BLOCKY'].each { style ->
                        String name = "combined-sensitivity-${mutation}-${style}"
                        scenario(name, 'CombinedCapeProbe', [new File(work, name), 'world', mutation, 'foreign-first', 'normal', style],
                                [], 1, 'COMBINED_INTEGRATION_MISSING')
                    }
                }
            } else if (kind == 'prepared') {
                ['live', 'replace-before', 'replace-after', 'registry-close-before', 'registry-close-after',
                 'owner-close-before', 'owner-close-after', 'retire-during'].each { String state ->
                    ['own-first', 'foreign-first'].each { String order ->
                        String name = "lifetime-${state}-${order}"
                        scenario(name, 'CapeLifetimeProbe', [new File(work, name), state, order], [], 0, 'PRODUCTION_WOVEN_CAPTURE_PASS')
                    }
                }
                ['own-first', 'foreign-first'].each { order ->
                    scenario("aba-${order}", 'CapeAbaProbe', [order], [], 0, 'PRODUCTION_ABA_PASS')
                }
                ['captured', 'unmarked'].each { String capture ->
                    ['normal', 'abort'].each { String behavior ->
                        String name = "driver-${capture}-${behavior}"
                        scenario(name, 'CapeDriverAbortProbe', [new File(work, name), capture, behavior], [], 0, 'ACTUAL_DRIVER_DIFFERENTIAL')
                    }
                }
                ['world', 'menu'].each { String world ->
                    ['SMOOTH', 'BLOCKY'].each { String style ->
                        String name = "production-bridge-${world}-${style}"
                        scenario(name, 'CapeBridgeProbe', [new File(work, name), world, style], [], 0, 'PRODUCTION_BRIDGE_PASS')
                    }
                }
                scenario('partial-registration', 'CapeRegistrationFailureProbe', [], [], 0, 'REGISTRATION_FAILURE_PASS')
                scenario('unsupported-public-abi', 'CapeUnsupportedAbiProbe', [], [], 0, 'UNSUPPORTED_ABI_PASS')
                scenario('callback-linkage', 'CapeCallbackLinkageProbe', [new File(work, 'callback-data')], [], 0, 'CALLBACK_LINKAGE_PASS')
                scenario('closed-legacy', 'CapeClosedLegacyProbe', [], [], 0, 'CLOSED_LEGACY_PASS')
                scenario('no-support-sensitivity', 'CapeBridgeProbe', [new File(work, 'no-support-data'), 'world'],
                        ['-Dnclskins.wavey.noSupport=true'], 1, 'NATIVE_BRIDGE_FOIL_MISSING')
            } else {
                run('cape-stream-coherence', java + ['-cp', runtimeClasspath, 'com.naocraftlab.skins.compat.waveycapes.CapeStreamProbe'], 0, 'CAPTURED_BASE_FOIL_STREAM_PASS')
                ['live', 'before', 'after'].each { String state ->
                    scenario("lifetime-${state}", 'BufferedCapeProbe', [new File(work, "data-${state}"), state], [], 0, 'BUFFERED_PRODUCTION_PASS')
                }
                if (submissionPreview.get()) {
                    scenario('native-enchanted-elytra', 'NativeEnchantedElytra', [], [], 0, 'NATIVE_ELYTRA_CONSUMPTION_PASS')
                    scenario('preview-publication', 'BufferedPreviewCapeProbe', [new File(work, 'preview-data')], [], 0, 'PREVIEW_PUBLICATION_PASS')
                }
            }
            if (!immediate) {
                List<File> nativeInputs = kind == 'combined' ? [transformed, publicationRaw, geometry, bound, fixtures] : [transformed, publicationRaw, bound, fixtures]
                Closure publication = { String name, boolean preview, boolean missing, List properties, int exit, String marker, String style ->
                    List<File> inputs = [missing && !preview ? publicationMissing : publicationWoven]
                    if (preview) inputs.add(missing ? previewMissing : previewRaw)
                    String cp = (inputs + nativeInputs + classpath).collect { it.absolutePath }.join(File.pathSeparator)
                    run(name, java + properties + ['-cp', cp, prefix + (preview ? 'ExtractionPublicationProbe' : 'CapePublicationProbe'), new File(work, name), style], exit, marker)
                }
                ['SMOOTH', 'BLOCKY'].each { style ->
                    publication("world-publication-${style}", false, false, [], 0, 'WORLD_PUBLICATION_PASS', style)
                    if (!submissionPreview.get()) publication("extraction-publication-${style}", true, false, [], 0, 'EXTRACTION_PUBLICATION_PASS', style)
                }
                if (!submissionPreview.get()) {
                    String cp = ([publicationWoven, previewRaw] + nativeInputs + classpath).collect { it.absolutePath }.join(File.pathSeparator)
                    run('extraction-baked-public', java + ['-cp', cp, prefix + 'ExtractionBakedPreviewProbe', new File(work, 'baked-public-data')], 0, 'EXTRACTION_BAKED_PUBLIC_PASS')
                }
                publication('missing-world-publication', false, true, [], 1, 'WORLD_PUBLICATION_MISSING', 'SMOOTH')
                publication('wrong-world-raw', false, false, ['-Dnclskins.wavey.wrongRaw=true'], 1, 'WORLD_PUBLICATION_MISSING', 'SMOOTH')
                if (!submissionPreview.get()) publication('missing-extraction-publication', true, true, [], 1, 'EXTRACTION_PUBLICATION_MISSING', 'SMOOTH')
            }
            receipt.result = 'PASS'
            receipt.outputHashes = [fixtures: VerifyWaveyCapesCompositionTask.directoryHash(fixtures), bound: VerifyWaveyCapesCompositionTask.directoryHash(bound), transformed: VerifyWaveyCapesCompositionTask.directoryHash(transformed)]
            save()
            logger.lifecycle("${targetId.get()}: genuine WaveyCapes / production-woven ${kind} composition PASS; GPU/visual NOT_RUN; ${resultFile}")
        } catch (Throwable failure) {
            boolean interrupted = Thread.interrupted() | (failure instanceof InterruptedException)
            try {
                receipt.result = 'FAIL'
                save()
            } catch (Throwable reportingFailure) {
                if (!failure.is(reportingFailure)) failure.addSuppressed(reportingFailure)
                interrupted |= reportingFailure instanceof InterruptedException
            } finally {
                interrupted |= Thread.interrupted()
                if (interrupted) Thread.currentThread().interrupt()
            }
            throw failure
        }
    }
}

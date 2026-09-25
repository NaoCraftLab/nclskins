package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

import javax.tools.ToolProvider
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

abstract class VerifyOptifineCapeCompositionTask extends DefaultTask {
    @InputFile abstract RegularFileProperty getCatalogFile()
    @InputFile abstract RegularFileProperty getOptifineJar()
    @InputFile abstract RegularFileProperty getMinecraftClientJar()
    @InputFile abstract RegularFileProperty getForgeSrgJar()
    @InputFile abstract RegularFileProperty getNclForgeJar()
    @InputDirectory abstract DirectoryProperty getProbeDirectory()
    @InputFiles abstract ConfigurableFileCollection getRuntimeJars()
    @OutputDirectory abstract DirectoryProperty getWorkDirectory()
    @Internal abstract DirectoryProperty getRepositoryDirectory()

    @TaskAction
    void verify() {
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        Map pinned = catalog.optionalDependencies.optifine as Map
        File optifine = optifineJar.get().asFile
        File base = minecraftClientJar.get().asFile
        File forge = forgeSrgJar.get().asFile
        File artifact = nclForgeJar.get().asFile
        String forgeSha = digest(forge)
        if (forgeSha != pinned.forgeSrgSha256.toString()) {
            throw new IllegalStateException('Forge 1.20.1 SRG platform JAR SHA-256 changed')
        }
        File root = repositoryDirectory.get().asFile
        List<File> relevantSources = [
                new File(root, 'compat/capabilities/appearance/optifine-cape'),
                new File(root, 'compat/capabilities/gui/immediate-resource-location-player-info/src/main/java/com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/mixin/AbstractClientPlayerPreviewMixin.java'),
                new File(root, 'client-contract/src/main/java/com/naocraftlab/skins/client/PlayerAppearanceSink.java'),
                new File(root, 'client-runtime/src/main/java/com/naocraftlab/skins/runtime/CapeProjection.java'),
                new File(root, 'client-runtime/src/main/java/com/naocraftlab/skins/runtime/OptifineCapeCoordinator.java'),
                new File(root, 'client-runtime/src/main/java/com/naocraftlab/skins/runtime/OptifineCapeReader.java')
        ].collectMany { File path -> path.isDirectory() ?
                project.fileTree(path).matching { include '**/*.java', '**/*.json' }.files : [path] }
        if (relevantSources.any { !it.isFile() }
                || relevantSources.any { it.lastModified() > artifact.lastModified() }) {
            throw new IllegalStateException('Production Forge JAR predates current OptiFine or preview mixin source')
        }
        new ZipFile(artifact).withCloseable { ZipFile zip ->
            [
                    'nclskins.optifine-cape.mixins.json',
                    'nclskins.resourcelocation-playerinfo.mixins.json',
                    'nclskins.resourcelocation-playerinfo.refmap.json',
                    'com/naocraftlab/skins/compat/client/resourcelocation/optifine/mixin/OptifinePlayerCapeMixin.class',
                    'com/naocraftlab/skins/compat/client/resourcelocation/optifine/mixin/OptifineCapeUtilsMixin.class',
                    'com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/mixin/AbstractClientPlayerPreviewMixin.class'
            ].each { String entry ->
                if (zip.getEntry(entry) == null) throw new IllegalStateException("Production Forge JAR lacks ${entry}")
            }
        }
        Map<String, byte[]> classes = OptifineCapeAbiVerifier.verifiedClasses(pinned, optifine, base)
        File work = workDirectory.get().asFile
        work.mkdirs()
        File bundledExtras = new File(work, 'production-mixinextras-forge.jar')
        File bundledExtrasCore = new File(work, 'production-mixinextras-core.jar')
        new ZipFile(artifact).withCloseable { ZipFile zip ->
            String entry = 'META-INF/jarjar/mixinextras-forge-0.5.3.jar'
            if (zip.getEntry(entry) == null) throw new IllegalStateException('Production MixinExtras Forge JAR missing')
            bundledExtras.bytes = zip.getInputStream(zip.getEntry(entry)).withCloseable { it.readAllBytes() }
        }
        new ZipFile(bundledExtras).withCloseable { ZipFile zip ->
            String entry = 'META-INF/jars/MixinExtras-0.5.3.jar'
            if (zip.getEntry(entry) == null) throw new IllegalStateException('Production MixinExtras core JAR missing')
            bundledExtrasCore.bytes = zip.getInputStream(zip.getEntry(entry)).withCloseable { it.readAllBytes() }
        }
        File patchedJar = new File(work, 'pinned-i6-input.jar')
        new ZipOutputStream(patchedJar.newOutputStream()).withCloseable { ZipOutputStream zip ->
            classes.each { String name, byte[] bytes ->
                zip.putNextEntry(new ZipEntry(name + '.class'))
                zip.write(bytes)
                zip.closeEntry()
                if (name == 'net/optifine/player/CapeUtils') {
                    zip.putNextEntry(new ZipEntry('srg/' + name + '.class'))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        File probe = probeDirectory.get().asFile
        File compiled = new File(work, 'probe-classes')
        compiled.mkdirs()
        List<File> sources = project.fileTree(new File(probe, 'java')).matching {
            include '**/*.java'
        }.files.sort { it.path }
        if (sources.size() != 3) throw new IllegalStateException('Composition probe source set changed')
        List<File> libraries = runtimeJars.files.sort { it.path }
        String libraryPath = libraries*.absolutePath.join(File.pathSeparator)
        def compiler = ToolProvider.systemJavaCompiler
        if (compiler == null) throw new IllegalStateException('Composition probe requires a JDK')
        List<String> options = ['--release', '17', '-classpath', libraryPath,
                                '-d', compiled.absolutePath] + sources*.absolutePath
        int compiledExit = compiler.run(null, System.out, System.err, options as String[])
        if (compiledExit != 0) throw new IllegalStateException('Headless Mixin probe compilation failed')
        logger.lifecycle("OptiFine I6 SHA-256 ${digest(optifine)}")
        logger.lifecycle("Minecraft base SHA-256 ${digest(base)}")
        logger.lifecycle("Forge SRG SHA-256 ${forgeSha}")
        logger.lifecycle("NCL Forge artifact SHA-256 ${digest(artifact)}")
        logger.lifecycle("Production MixinExtras Forge SHA-256 ${digest(bundledExtras)}")
        logger.lifecycle("Production MixinExtras core SHA-256 ${digest(bundledExtrasCore)}")
        ['present', 'absent'].each { String scenario ->
            List<File> inputs = [compiled, new File(probe, 'resources')]
            if (scenario == 'present') inputs.add(patchedJar)
            inputs.addAll([artifact, forge])
            inputs.addAll(libraries.findAll { !it.name.startsWith('mixinextras-common-') })
            inputs.addAll([bundledExtras, bundledExtrasCore])
            String classpath = inputs*.absolutePath.join(File.pathSeparator)
            String executable = new File(System.getProperty('java.home'), 'bin/java').absolutePath
            File outputFile = new File(work, "${scenario}.log")
            Process process = new ProcessBuilder(executable, '-cp', classpath,
                    'com.naocraftlab.skins.buildlogic.probe.OptifineCompositionProbe', scenario)
                    .directory(root).redirectErrorStream(true).redirectOutput(outputFile).start()
            boolean finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            logger.lifecycle(outputFile.getText('UTF-8').trim())
            if (!finished || process.exitValue() != 0) {
                throw new IllegalStateException("Real Mixin composition ${scenario} failed")
            }
        }
    }

    private static String digest(File file) {
        MessageDigest.getInstance('SHA-256').digest(file.bytes).encodeHex().toString()
    }
}

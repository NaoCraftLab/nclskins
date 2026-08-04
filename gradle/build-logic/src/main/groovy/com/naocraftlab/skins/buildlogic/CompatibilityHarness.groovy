package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipFile

final class CompatibilityHarness {
    private CompatibilityHarness() {}

    static void verify(File root, Map catalog, Map target, String modVersion) {
        List<Map> results = (target.compatibility.minecraftVersions as List).collect { Object version ->
            prepareAndResolve(root, catalog, target, modVersion, version.toString())
        }
        String expected = results.first().sha256
        if (results.any { it.sha256 != expected }) {
            throw new IllegalStateException("${target.id}: compatibility runtimes used different production JAR hashes")
        }
        File report = new File(root, "build/compatibility-runs/${target.id}/verification.json")
        report.parentFile.mkdirs()
        report.setText(JsonOutput.prettyPrint(JsonOutput.toJson([
                schemaVersion: 1,
                target       : target.id,
                artifact     : artifact(root, target, modVersion).canonicalPath,
                sha256       : expected,
                runtimes     : results.collect { [minecraftVersion: it.minecraftVersion, loaderVersion: it.loaderVersion] }
        ])) + '\n', StandardCharsets.UTF_8.name())
    }

    static void run(File root, Map catalog, Map target, String modVersion, String minecraftVersion, String kind) {
        Map result = prepareAndResolve(root, catalog, target, modVersion, minecraftVersion)
        File harness = result.directory as File
        if (kind == 'Server') {
            File eula = new File(harness, 'run/eula.txt')
            eula.parentFile.mkdirs()
            eula.setText('eula=true\n', StandardCharsets.UTF_8.name())
            new File(harness, 'run/logs/latest.log').delete()
        }
        File wrapper = TargetRuntime.wrapper(root, catalog, target)
        List<String> command = [
                wrapper.absolutePath,
                '-p',
                harness.absolutePath,
                '--no-daemon',
                "run${kind}".toString()]
        if (kind == 'Server' && target.loader.id == 'fabric') {
            command.add("--args=--nogui --port ${target.development.serverPort}".toString())
        }
        execute(command, root, target, "compatibility ${minecraftVersion} run${kind}")
    }

    private static Map prepareAndResolve(
            File root, Map catalog, Map target, String modVersion, String minecraftVersion) {
        Map runtime = CatalogTools.compatibilityRuntime(target, minecraftVersion)
        File production = artifact(root, target, modVersion)
        if (!production.isFile()) {
            throw new IllegalStateException("${target.id}: missing production JAR ${production}; build the target first")
        }
        String before = sha256(production)
        File harness = new File(root, "build/compatibility-runs/${target.id}/${minecraftVersion}")
        harness.mkdirs()
        extractAccessRules(production, harness, target)
        new File(harness, 'settings.gradle').setText(settings(target), StandardCharsets.UTF_8.name())
        new File(harness, 'build.gradle').setText(
                buildFile(root, catalog, target, runtime, production), StandardCharsets.UTF_8.name())
        new File(harness, 'gradle.properties').setText(
                'org.gradle.jvmargs=-Xmx2G -Dfile.encoding=UTF-8\norg.gradle.configuration-cache=false\n',
                StandardCharsets.UTF_8.name())
        File wrapper = TargetRuntime.wrapper(root, catalog, target)
        execute(
                [wrapper.absolutePath, '-p', harness.absolutePath, '--no-daemon', 'resolveCompatibilityRuntime'],
                root,
                target,
                "compatibility ${minecraftVersion} resolution")
        File classpathFile = new File(harness, 'runtime-classpath.txt')
        if (!classpathFile.isFile() || classpathFile.text.isBlank()) {
            throw new IllegalStateException("${target.id}: compatibility ${minecraftVersion} produced no runtime classpath")
        }
        Map abi = CatalogTools.loadJson(new File(root, 'gradle/abi-fingerprints.json'))
        File javap = new File(TargetRuntime.resolveJavaHome(target.java.buildJdk as int), 'bin/javap')
        Map resolvedAbi = AbiVerifier.resolve(catalog, abi, target.id.toString(), classpathFile.text.trim(), javap)
        AbiVerifier.verify(catalog, abi, target.id.toString(), resolvedAbi)
        String after = sha256(production)
        if (before != after) {
            throw new IllegalStateException("${target.id}: production JAR changed during compatibility resolution")
        }
        [directory: harness, minecraftVersion: minecraftVersion, loaderVersion: runtime.loaderVersion, sha256: after]
    }

    private static String settings(Map target) {
        String repositories = target.loader.id == 'fabric'
                ? "maven { url = 'https://maven.fabricmc.net/' }"
                : "maven { url = 'https://maven.neoforged.net/releases' }"
        """pluginManagement {
    repositories {
        ${repositories}
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = 'nclskins-${target.id}-compatibility'
"""
    }

    static String buildFile(File root, Map catalog, Map target, Map runtime, File production) {
        String escapedJar = production.canonicalPath.replace('\\', '\\\\').replace("'", "\\'")
        List<String> clientArguments = CatalogTools.clientArguments(catalog)
        if (target.loader.id == 'fabric') {
            return """plugins {
    id 'net.fabricmc.fabric-loom' version '${targetCatalogPlugin(root, 'loom')}'
}
loom {
    accessWidenerPath = file('src/main/resources/${target.metadata.accessWidener}')
    runs {
        client {
            programArgs ${groovyStringList(clientArguments)}
        }
    }
}
repositories {
    maven { url = 'https://maven.fabricmc.net/' }
    maven { url = 'https://api.modrinth.com/maven' }
    mavenCentral()
}
dependencies {
    minecraft 'com.mojang:minecraft:${runtime.minecraftVersion}'
    implementation 'net.fabricmc:fabric-loader:${runtime.loaderVersion}'
    implementation 'net.fabricmc.fabric-api:fabric-api:${target.loader.apiVersion}'
    localRuntime 'maven.modrinth:modmenu:${target.loader.modMenuVersion}'
    runtimeOnly files('${escapedJar}')
}
${serverStopHarness()}
tasks.register('resolveCompatibilityRuntime') {
    doLast {
        configurations.runtimeClasspath.resolve()
        file('runtime-classpath.txt').text = configurations.runtimeClasspath.asPath
        if (!file('${escapedJar}').isFile()) throw new GradleException('Missing production JAR')
    }
}
"""
        }
        """plugins {
    id 'java-library'
    id 'net.neoforged.moddev' version '${targetCatalogPlugin(root, 'modDevGradle')}'
}
repositories {
    maven { url = 'https://maven.neoforged.net/releases' }
    mavenCentral()
}
neoForge {
    accessTransformers.from('src/main/resources/${target.metadata.accessTransformer}')
    enable {
        version = '${runtime.loaderVersion}'
        disableRecompilation = true
    }
    runs {
        client {
            client()
            ${clientArguments.collect { "programArgument '${it}'" }.join('\n            ')}
        }
        server {
            server()
            programArgument '--nogui'
            programArgument '--port'
            programArgument '${target.development.serverPort}'
        }
    }
}
dependencies {
    runtimeOnly files('${escapedJar}')
}
${serverStopHarness()}
tasks.register('resolveCompatibilityRuntime') {
    dependsOn tasks.named('createMinecraftArtifacts')
    doLast {
        configurations.runtimeClasspath.resolve()
        file('runtime-classpath.txt').text = configurations.runtimeClasspath.asPath
        if (!file('${escapedJar}').isFile()) throw new GradleException('Missing production JAR')
    }
}
"""
    }

    private static void extractAccessRules(File production, File harness, Map target) {
        String path = target.loader.id == 'fabric'
                ? target.metadata.accessWidener.toString()
                : target.metadata.accessTransformer.toString()
        File output = new File(harness, "src/main/resources/${path}")
        output.parentFile.mkdirs()
        new ZipFile(production).withCloseable { ZipFile archive ->
            def entry = archive.getEntry(path)
            if (entry == null) throw new IllegalStateException("${target.id}: production JAR lacks ${path}")
            output.bytes = archive.getInputStream(entry).bytes
        }
    }

    private static String serverStopHarness() {
        """def nclskinsServerInput = new PipedInputStream()
def nclskinsServerCommands = new PipedOutputStream(nclskinsServerInput)
tasks.named('runServer', JavaExec) {
    standardInput = nclskinsServerInput
    doFirst {
        Thread.startDaemon('nclskins-compatibility-server-stop') {
            File log = file('run/logs/latest.log')
            for (int attempt = 0; attempt < 600; attempt++) {
                if (log.isFile() && log.getText('UTF-8').contains('Done (')) {
                    nclskinsServerCommands.write('stop\\n'.getBytes('UTF-8'))
                    nclskinsServerCommands.flush()
                    return
                }
                Thread.sleep(500)
            }
        }
    }
}
"""
    }

    private static String targetCatalogPlugin(File root, String key) {
        CatalogTools.loadCatalog(root).plugins[key].toString()
    }

    private static String groovyStringList(List<String> values) {
        values.collect { "'${it}'" }.join(', ')
    }

    private static File artifact(File root, Map target, String modVersion) {
        String name = target.artifact.file.toString().replace('{modVersion}', modVersion)
        new File(root, "${target.path}/build/libs/${name}")
    }

    private static String sha256(File file) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        file.withInputStream { input ->
            byte[] buffer = new byte[8192]
            int count
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        digest.digest().collect { String.format('%02x', it & 0xff) }.join()
    }

    private static void execute(List<String> command, File root, Map target, String label) {
        String javaHome = TargetRuntime.resolveJavaHome(target.java.buildJdk as int)
        ProcessBuilder builder = new ProcessBuilder(command).directory(root).inheritIO()
        TargetRuntime.configureEnvironment(builder, javaHome)
        Process process = builder.start()
        int exit = process.waitFor()
        if (exit != 0) throw new IllegalStateException("${target.id}: ${label} failed (${exit})")
    }
}

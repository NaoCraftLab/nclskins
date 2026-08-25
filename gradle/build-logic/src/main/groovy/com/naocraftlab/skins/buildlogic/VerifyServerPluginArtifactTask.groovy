package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

import java.nio.charset.StandardCharsets
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile


abstract class VerifyServerPluginArtifactTask extends DefaultTask {
    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @InputFile
    abstract RegularFileProperty getVersionFile()

    @InputFile
    abstract RegularFileProperty getArtifactFile()

    @TaskAction
    void verify() {
        File repository = repositoryDirectory.get().asFile
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile)
        String version = CatalogTools.loadVersion(versionFile.get().asFile.toPath())
        File artifact = artifactFile.get().asFile
        Map plugin = catalog.serverPlugin as Map
        String expectedName = plugin.artifact.toString().replace('{pluginVersion}', version)
        if (artifact.name != expectedName) {
            throw new GradleException("Server plugin artifact must be ${expectedName}, got ${artifact.name}")
        }
        List<String> errors = []
        ZipFile zip = new ZipFile(artifact)
        try {
            List<? extends ZipEntry> entries = Collections.list(zip.entries())
            List<String> names = entries.collect { it.name }
            names.groupBy { it }.findAll { String ignored, List duplicates ->
                duplicates.size() > 1
            }.keySet().each { errors.add("duplicate JAR entry ${it}") }
            ['plugin.yml', 'bungee.yml', 'velocity-plugin.json',
             'nclskins-server-plugin.json', 'META-INF/MANIFEST.MF'].each { String required ->
                if (!names.contains(required)) errors.add("missing ${required}")
            }
            if (names.contains('paper-plugin.yml')) errors.add('paper-plugin.yml is forbidden')
            List<String> forbiddenPrefixes = [
                    'com/google/gson/', 'org/bukkit/', 'io/papermc/paper/',
                    'com/velocitypowered/api/', 'net/md_5/bungee/api/',
                    'org/sqlite/', 'META-INF/native/', 'META-INF/services/org.sqlite.',
                    'org/apache/logging/log4j/', 'org/apache/log4j/', 'org/slf4j/',
                    'ch/qos/logback/', 'META-INF/services/org.slf4j.',
                    'META-INF/services/org.apache.logging.'
            ]
            names.each { String name ->
                if (forbiddenPrefixes.any { name.startsWith(it) } ||
                        name.endsWith('.mixins.json') || name.endsWith('.accesswidener') ||
                        name.toLowerCase(Locale.ROOT).contains('accesstransformer') ||
                        name.endsWith('/JndiLookup.class') || name.endsWith('/JndiManager.class') ||
                        name.toLowerCase(Locale.ROOT) ==~ /.*(?:log4j2|logback).*(?:xml|json|ya?ml|properties)$/ ||
                        name.toLowerCase(Locale.ROOT) ==~ /.*\.(?:so|dll|dylib|jnilib)$/) {
                    errors.add("forbidden bundled payload ${name}")
                }
            }
            if (!names.any { it.startsWith('com/naocraftlab/skins/server/lib/gson/') }) {
                errors.add('relocated Gson is missing')
            }
            Set<String> adapterPrefixes = [
                    'legacy/authlib4/LegacyAuthlib4NativeAdapter.class',
                    'paper/authlib4/PaperAuthlib4NativeAdapter.class',
                    'paper/authlib6/PaperAuthlib6NativeAdapter.class',
                    'paper/authlib7/PaperAuthlib7NativeAdapter.class',
                    'paper/authlib9/PaperAuthlib9NativeAdapter.class'
            ] as Set
            adapterPrefixes.each { String leaf ->
                if (!names.contains('com/naocraftlab/skins/server/plugin/adapter/' + leaf)) {
                    errors.add("missing exact native adapter ${leaf}")
                }
            }
            entries.findAll { it.name.endsWith('.class') }.each { ZipEntry entry ->
                byte[] bytes = zip.getInputStream(entry).readAllBytes()
                if (bytes.length < 8) {
                    errors.add("truncated class ${entry.name}")
                    return
                }
                int major = ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff)
                if (major > 61) errors.add("${entry.name} uses classfile major ${major}")
                String classSymbols = new String(bytes, StandardCharsets.ISO_8859_1)
                if (classSymbols.contains('setPlayerProfile')) {
                    errors.add("${entry.name} references forbidden Player#setPlayerProfile")
                }
                if (entry.name.startsWith('com/naocraftlab/skins/server/plugin/bukkit/')) {
                    String symbols = new String(bytes, StandardCharsets.ISO_8859_1)
                    ['getPluginMeta', 'getMinecraftVersion'].each { String symbol ->
                        if (symbols.contains(symbol)) {
                            errors.add("${entry.name} references Paper-only ${symbol}")
                        }
                    }
                }
            }
            String paperBackend = 'com/naocraftlab/skins/server/plugin/bukkit/PaperProfilePublicationBackend.class'
            String paperPublication =
                    'com/naocraftlab/skins/server/plugin/bukkit/PaperProfilePublicationBackend$PaperPublication.class'
            String paperProfileState =
                    'com/naocraftlab/skins/server/plugin/bukkit/PaperProfileStateBinding.class'
            String paperSymbols = names.contains(paperBackend)
                    ? new String(zip.getInputStream(zip.getEntry(paperBackend)).readAllBytes(),
                            StandardCharsets.ISO_8859_1)
                    : ''
            String profileStateSymbols = names.contains(paperProfileState)
                    ? new String(zip.getInputStream(zip.getEntry(paperProfileState)).readAllBytes(),
                            StandardCharsets.ISO_8859_1)
                    : ''
            if (!names.contains(paperBackend) ||
                    !names.contains(paperPublication) ||
                    !names.contains(paperProfileState) ||
                    !['getProfile', 'unregisterEntity', 'trackAndShowEntity'].every {
                        String symbol -> paperSymbols.contains(symbol)
                    } ||
                    !['gameProfile', 'ImmutableMultimap', 'removeAll', 'installMutable'].every {
                        String symbol -> profileStateSymbols.contains(symbol)
                    }) {
                errors.add('Paper backend lacks exact mutable/immutable profile and observer-only publication symbols')
            }
            Manifest manifest = new Manifest(zip.getInputStream(zip.getEntry('META-INF/MANIFEST.MF')))
            if (manifest.mainAttributes.getValue('paperweight-mappings-namespace') != 'mojang' ||
                    manifest.mainAttributes.getValue('Implementation-Version') != version ||
                    manifest.mainAttributes.getValue('Multi-Release') == 'true') {
                errors.add('manifest lacks exact Mojang namespace or implementation version')
            }
            String pluginYaml = text(zip, 'plugin.yml')
            if (!pluginYaml.contains('name: NCLSkinsPlugin') ||
                    !pluginYaml.contains("version: '${version}'") ||
                    !pluginYaml.contains("api-version: '1.20'") ||
                    !pluginYaml.contains('folia-supported: true') ||
                    !pluginYaml.contains('softdepend: [BungeeGuard]') ||
                    pluginYaml.contains('commands:') ||
                    pluginYaml.contains('nclskin:')) {
                errors.add('plugin.yml differs from the universal Bukkit contract')
            }
            String bungeeYaml = text(zip, 'bungee.yml')
            if (!bungeeYaml.contains("version: '${version}'") ||
                    !bungeeYaml.contains('depends: [BungeeGuard]')) {
                errors.add('bungee.yml differs from the protected relay contract')
            }
            Map velocity = new JsonSlurper().parseText(text(zip, 'velocity-plugin.json')) as Map
            if (velocity.id != 'nclskins-plugin' || velocity.name != 'NCL Skins Plugin' ||
                    velocity.version != version) {
                errors.add('velocity-plugin.json differs from the public plugin identity')
            }
            Map metadata = new JsonSlurper().parseText(
                    text(zip, 'nclskins-server-plugin.json')) as Map
            String expectedFingerprint = ServerPluginFingerprint.current(repository, catalog)
            if (metadata.serverImplementationVersion != version ||
                    metadata.protocolIds != plugin.protocols ||
                    metadata.matrixId != plugin.matrixId ||
                    metadata.serverFingerprint != expectedFingerprint) {
                errors.add('embedded server plugin metadata or fingerprint differs from source graph')
            }
        } finally {
            zip.close()
        }
        if (errors) {
            throw new GradleException(errors.collect { "- ${it}" }.join('\n'))
        }
        logger.lifecycle("Verified universal server plugin ${artifact.name} (${CatalogTools.sha256(artifact.bytes)})")
    }

    private static String text(ZipFile zip, String path) {
        ZipEntry entry = zip.getEntry(path)
        entry == null ? '' : new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8)
    }
}

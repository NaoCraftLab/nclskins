package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

final class ArtifactVerifier {
    static final List<String> FORBIDDEN_PREFIXES = ['com/microsoft/aad', 'com/nimbusds/', 'com/sun/jna/', 'com/fasterxml/jackson/', 'com/google/gson/']
    static final List<String> FORBIDDEN_DEV_RUNTIME_PREFIXES = ['com/terraformersmc/modmenu/', 'META-INF/jars/modmenu']
    static final Pattern FORBIDDEN_CONTENT = Pattern.compile('login' + '\\.microsoftonline\\.com|refresh' + '_token|launcher' + '_accounts\\.json|accounts\\.json')
    static final Pattern FORBIDDEN_MIXIN = Pattern.compile('(?:User|Session|Authlib).*Mixin\\.class$')
    static final Pattern TOKEN = Pattern.compile('Bearer\\s+[A-Za-z0-9._~+/=-]{20,}|eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}')
    static final String COLLECTIONS = 'resourcepacks/mojang_collections/'
    static final String BUTTONS = 'assets/nclskins/textures/gui/icons/'
    static final Map<String, Map> FORGE_REFMAPS = [
        'forge-1.20.1': [
            path: 'nclskins.mc1201.refmap.json',
            mappings: [
                'com/naocraftlab/skins/compat/v1_20_1/client/mixin/AccessibilityOptionsScreenMixin': [
                    options: 'Lnet/minecraft/client/gui/screens/AccessibilityOptionsScreen;m_232690_(Lnet/minecraft/client/Options;)[Lnet/minecraft/client/OptionInstance;'
                ],
                'com/naocraftlab/skins/compat/v1_20_1/client/mixin/OptionsScreenMixin': [
                    init: 'Lnet/minecraft/client/gui/screens/OptionsScreen;m_7856_()V',
                    'Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;': 'Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;m_264139_(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;'
                ],
                'com/naocraftlab/skins/compat/v1_20_1/client/mixin/ScreenRenderablesAccessor': [
                    renderables: 'f_169369_:Ljava/util/List;'
                ]
            ]
        ]
    ]

    static void verify(File root, Map catalog, Map target, String version, List<String> errors) {
        String filename = target.artifact.file.toString().replace('{modVersion}', version)
        File artifact = new File(root, "${target.path}/build/libs/${filename}")
        if (!artifact.isFile()) { errors.add("${target.id}: missing production artifact ${artifact}"); return }
        ZipFile zip
        try { zip = new ZipFile(artifact) } catch (Exception error) { errors.add("${target.id}: invalid JAR: ${error.message}"); return }
        zip.withCloseable { archive ->
            List<String> names = archive.entries().collect { it.name }
            if (names.size() != (names as Set).size()) errors.add("${target.id}: artifact contains duplicate ZIP entries")
            if (names.any { String name -> FORBIDDEN_PREFIXES.any { name.startsWith(it) } }) errors.add("${target.id}: artifact embeds a forbidden auth/native/JSON dependency")
            if (names.any { String name -> FORBIDDEN_DEV_RUNTIME_PREFIXES.any { name.startsWith(it) } }) errors.add("${target.id}: artifact embeds the dev-only Mod Menu dependency")
            if (names.any { FORBIDDEN_MIXIN.matcher(it).find() }) errors.add("${target.id}: artifact contains a forbidden session/auth mixin candidate")
            verifyClassfiles(archive, target, names, errors)
            verifyLegal(root, archive, target, errors)
            verifyMetadata(archive, catalog, target, version, errors)
            verifyResources(root, archive, catalog, target, names, errors)
            verifyManifest(archive, target, errors)
            verifyForgeRefmap(archive, target, names, errors)
            verifyMenuPreviewCompatibility(archive, target, errors)
            names.findAll { !it.endsWith('/') }.each { String name ->
                byte[] bytes = read(archive, name)
                String content = new String(bytes, StandardCharsets.ISO_8859_1)
                if (FORBIDDEN_CONTENT.matcher(content).find()) errors.add("${target.id}:${name}: forbidden OAuth/launcher reference")
                if (TOKEN.matcher(content).find()) errors.add("${target.id}:${name}: credential-like value found")
            }
        }
    }

    static void verifyCompatibilityReport(
            File root, Map target, String version, List<String> errors) {
        if (!(target.compatibility instanceof Map)) return
        File reportFile = new File(root, "build/compatibility-runs/${target.id}/verification.json")
        if (!reportFile.isFile()) {
            errors.add("${target.id}: missing runtime compatibility verification report")
            return
        }
        Map report
        try {
            report = CatalogTools.loadJson(reportFile)
        } catch (Exception error) {
            errors.add("${target.id}: invalid runtime compatibility report: ${error.message}")
            return
        }
        String name = target.artifact.file.toString().replace('{modVersion}', version)
        File artifact = new File(root, "${target.path}/build/libs/${name}")
        String digest = sha256(artifact)
        List expectedRuntimes = (target.compatibility.minecraftVersions as List).collect { Object mc ->
            [minecraftVersion: mc, loaderVersion: target.compatibility.loaderVersions[mc]]
        }
        if (report.target != target.id || report.artifact != artifact.canonicalPath ||
                report.sha256 != digest || report.runtimes != expectedRuntimes) {
            errors.add("${target.id}: runtime compatibility report differs from the current production JAR or catalog")
        }
    }

    private static String sha256(File file) {
        if (!file.isFile()) return ''
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

    static void verifyClassfiles(ZipFile archive, Map target, List<String> names, List<String> errors) {
        List<String> classes = names.findAll { it.endsWith('.class') }
        if (classes.isEmpty()) { errors.add("${target.id}: artifact contains no classfiles"); return }
        int expected = target.java.classfileMajor as int
        classes.each { String name ->
            byte[] bytes = read(archive, name)
            if (bytes.length < 8 || bytes[0..3] as byte[] != [0xCA, 0xFE, 0xBA, 0xBE] as byte[]) { errors.add("${target.id}:${name}: invalid classfile header"); return }
            int major = ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff)
            if (major != expected) errors.add("${target.id}:${name}: expected classfile major ${expected}, got ${major}")
        }
    }

    static void verifyLegal(File root, ZipFile archive, Map target, List<String> errors) {
        ['LICENSE', 'NOTICE'].each { String name ->
            String entry = "META-INF/${name}"
            ZipEntry zipEntry = archive.getEntry(entry)
            if (zipEntry == null) { errors.add("${target.id}: missing ${entry}"); return }
            byte[] expected = new File(root, name).bytes
            if (!MessageDigest.isEqual(expected, read(archive, entry))) errors.add("${target.id}: ${entry} differs from repository ${name}")
        }
    }

    static void verifyMetadata(ZipFile archive, Map catalog, Map target, String version, List<String> errors) {
        String loader = target.loader.id.toString()
        if (loader == 'fabric') {
            Map metadata = json(archive, 'fabric.mod.json', target, errors)
            if (metadata == null) return
            Map expected = [schemaVersion: 1, id: catalog.mod.id, version: version, name: catalog.mod.name, description: catalog.mod.descriptions.en_us, authors: catalog.mod.authors, contact: catalog.mod.contact, license: catalog.mod.license, icon: catalog.mod.icon]
            expected.each { key, value -> if (metadata[key] != value) errors.add("${target.id}: Fabric ${key} differs from catalog") }
            if (metadata.environment != '*') errors.add("${target.id}: Fabric environment must be universal")
            if (metadata.entrypoints != [main: [target.metadata.serverEntrypoint], client: [target.metadata.entrypoint]]) errors.add("${target.id}: Fabric entrypoints differ from catalog")
            if (metadata.depends != ['fabricloader': target.loader.predicate, 'fabric-api': target.loader.apiPredicate, minecraft: target.minecraft.predicate, java: ">=${target.java.release}"]) errors.add("${target.id}: Fabric dependencies differ from catalog")
            if (metadata.suggests != [modmenu: ">=${target.loader.modMenuVersion}".toString()]) errors.add("${target.id}: Fabric Mod Menu suggestion differs from catalog")
            if (metadata.custom != [modmenu: [links: ['modmenu.modrinth': MetadataRenderer.modrinthUrl(catalog.mod as Map), 'modmenu.curseforge': MetadataRenderer.curseForgeUrl(catalog.mod as Map)], update_checker: true]]) errors.add("${target.id}: Fabric Mod Menu card metadata differs from catalog")
            if (metadata.accessWidener != target.metadata.accessWidener) errors.add("${target.id}: Fabric access widener differs from catalog")
            List expectedMixins = (target.metadata.serverMixins ?: []).collect { [config: it] } + (target.metadata.mixins ?: []).collect { [config: it, environment: 'client'] }
            if ((metadata.mixins ?: []) != expectedMixins) errors.add("${target.id}: Fabric mixin list differs from catalog")
            verifyBytecodeMarker(archive, target.metadata.serverEntrypoint.toString().replace('.', '/') + '.class', target, ['net/fabricmc/api/ModInitializer'], ['net/minecraft/client'], errors)
        } else {
            String path = loader == 'forge' ? 'META-INF/mods.toml' : 'META-INF/neoforge.mods.toml'
            ZipEntry entry = archive.getEntry(path)
            if (entry == null) { errors.add("${target.id}: missing ${path}"); return }
            String text = new String(read(archive, path), StandardCharsets.UTF_8)
            String expectedText = MetadataRenderer.render(catalog, target, version)[path]
            if (text != expectedText) errors.add("${target.id}: ${path} differs from catalog-generated metadata")
            if (loader == 'neoforge') {
                ((target.metadata.serverMixins ?: []) + (target.metadata.mixins ?: [])).each { String mixin -> if (!text.contains("config=\"${mixin}\"")) errors.add("${target.id}: ${path} lacks Mixin config ${mixin}") }
            }
            if (loader == 'forge') {
                if (!text.readLines().contains('displayTest="IGNORE_SERVER_VERSION"')) errors.add("${target.id}: Forge metadata must allow vanilla clients")
                verifyBytecodeMarker(archive, target.metadata.entrypointClass.toString(), target, [], ['net/minecraft/client'], errors)
                verifyBytecodeMarker(archive, target.metadata.clientEntrypointClass.toString(), target, ['net/minecraftforge/api/distmarker/Dist', 'CLIENT'], [], errors)
            } else {
                verifyBytecodeMarker(archive, target.metadata.entrypointClass.toString(), target, ['Lnet/neoforged/fml/common/Mod;'], ['net/minecraft/client'], errors)
                verifyBytecodeMarker(archive, target.metadata.clientEntrypointClass.toString(), target, ['net/neoforged/api/distmarker/Dist', 'CLIENT'], [], errors)
            }
        }
        if ((target.metadata.files as List).contains('pack.mcmeta')) {
            Map pack = json(archive, 'pack.mcmeta', target, errors)
            if (pack != null && pack != [pack: [description: 'NCL Skins resources', pack_format: target.metadata.packFormat]]) errors.add("${target.id}: pack.mcmeta differs from catalog")
        }
    }

    static void verifyResources(File root, ZipFile archive, Map catalog, Map target, List<String> names, List<String> errors) {
        Set<String> expected = [catalog.mod.icon, 'assets/nclskins/lang/en_us.json', 'assets/nclskins/lang/ru_ru.json', COLLECTIONS + 'assets/nclskins/lang/en_us.json', COLLECTIONS + 'assets/nclskins/lang/ru_ru.json', COLLECTIONS + 'NOTICE-MOJANG.md', COLLECTIONS + 'pack.mcmeta'] as Set
        if (target.metadata.accessWidener) expected.add(target.metadata.accessWidener.toString())
        if (target.metadata.accessTransformer) expected.add(target.metadata.accessTransformer.toString())
        expected.addAll(target.metadata.serverMixins ?: [])
        expected.addAll(target.metadata.mixins ?: [])
        expected.findAll { !names.contains(it) }.each { errors.add("${target.id}: missing required resource ${it}") }
        ['en_us', 'ru_ru'].each { String locale ->
            Map language = json(archive, "assets/nclskins/lang/${locale}.json", target, errors)
            if (language != null) {
                String description = catalog.mod.descriptions[locale].toString()
                ['modmenu.descriptionTranslation.nclskins', 'fml.menu.mods.info.description.nclskins'].each { String key ->
                    if (language[key] != description) errors.add("${target.id}: ${locale} ${key} differs from catalog")
                }
            }
        }
        if (names.any { it.endsWith('.pixel.json') }) errors.add("${target.id}: artifact contains pixel-grid agent data")
        List<String> archiveButtons = names.findAll { it.startsWith(BUTTONS) && it.endsWith('.png') }.sort()
        File canonicalResources = new File(root, 'compat/resources/canonical/src/main/resources')
        List<File> sourceButtons = new File(canonicalResources, BUTTONS).listFiles()?.findAll { it.name.endsWith('.png') }?.sort { it.name } ?: []
        Set<String> expectedButtons = sourceButtons.collect { BUTTONS + it.name } as Set
        if ((archiveButtons as Set) != expectedButtons) errors.add("${target.id}: button icon manifest differs")
        sourceButtons.each { File source -> compareResource(archive, BUTTONS + source.name, source, target, 15, 15, errors) }
        compareResource(archive, catalog.mod.icon.toString(), new File(canonicalResources, catalog.mod.icon.toString()), target, 320, 320, errors)
        File collections = new File(root, 'compat/resources/mojang-collections/src/main/resources/resourcepacks/mojang_collections')
        List<File> skins = []
        Files.walk(collections.toPath()).withCloseable { stream -> stream.filter { Files.isRegularFile(it) && it.toString().endsWith('.png') && it.toString().contains('/assets/') }.forEach { skins.add(it.toFile()) } }
        Set<String> expectedSkins = skins.collect { COLLECTIONS + collections.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/' as char) } as Set
        Set<String> actualSkins = names.findAll { it.startsWith(COLLECTIONS + 'assets/') && it.endsWith('.png') } as Set
        if (actualSkins != expectedSkins) errors.add("${target.id}: Mojang collection skin manifest differs")
        skins.each { File source -> compareResource(archive, COLLECTIONS + collections.toPath().relativize(source.toPath()).toString().replace(File.separatorChar, '/' as char), source, target, 64, 64, errors) }
        Set<String> collectionsIds = skins.collect { File skin -> collections.toPath().relativize(skin.toPath()).getName(1).toString() } as Set
        collectionsIds.each { String id -> if (!names.contains(COLLECTIONS + "assets/${id}/notice-mojang.md")) errors.add("${target.id}: missing collection provenance notice for ${id}") }
    }

    static void compareResource(ZipFile archive, String path, File source, Map target, int width, int height, List<String> errors) {
        if (!source.isFile() || archive.getEntry(path) == null) return
        byte[] expected = source.bytes
        byte[] actual = read(archive, path)
        if (!MessageDigest.isEqual(expected, actual)) errors.add("${target.id}: resource hash differs for ${path}")
        if (actual.length < 24 || !MessageDigest.isEqual(actual[0..7] as byte[], [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a] as byte[]) || new String(actual, 12, 4, StandardCharsets.ISO_8859_1) != 'IHDR') { errors.add("${target.id}: invalid PNG ${path}"); return }
        int actualWidth = readInt(actual, 16)
        int actualHeight = readInt(actual, 20)
        if (actualWidth != width || actualHeight != height) errors.add("${target.id}: ${path} must be ${width}x${height}")
    }

    static void verifyManifest(ZipFile archive, Map target, List<String> errors) {
        ZipEntry entry = archive.getEntry('META-INF/MANIFEST.MF')
        if (entry == null) { errors.add("${target.id}: missing manifest"); return }
        String text = new String(read(archive, entry.name), StandardCharsets.UTF_8).replaceAll('\\r?\\n ', '')
        Map attributes = [:]
        text.readLines().each { String line -> int split = line.indexOf(':'); if (split > 0) attributes[line.substring(0, split).trim()] = line.substring(split + 1).trim() }
        if (attributes['Automatic-Module-Name'] != target.artifact.automaticModuleName) errors.add("${target.id}: Automatic-Module-Name differs from catalog")
        if (target.loader.id == 'forge') {
            String mixins = ((target.metadata.serverMixins ?: []) + (target.metadata.mixins ?: [])).join(',')
            if ((attributes['MixinConfigs'] ?: '') != mixins) errors.add("${target.id}: MixinConfigs differs from catalog")
        }
    }

    static void verifyForgeRefmap(ZipFile archive, Map target, List<String> names, List<String> errors) {
        if (target.loader.id != 'forge') return
        Map baseline = FORGE_REFMAPS[target.id.toString()]
        List<String> configs = (target.metadata.serverMixins ?: []) + (target.metadata.mixins ?: [])
        if (!configs.isEmpty() && baseline == null) { errors.add("${target.id}: no Forge Mixin refmap baseline"); return }
        if (baseline == null) return
        Set<String> declared = [] as Set
        configs.each { String path ->
            Map config = json(archive, path, target, errors)
            if (config == null) return
            if (config.refmap != baseline.path) errors.add("${target.id}: ${path} must declare refmap ${baseline.path}")
            String packageName = config.package?.toString()
            if (packageName == null) { errors.add("${target.id}: ${path} lacks Mixin package"); return }
            ((config.mixins ?: []) + (config.client ?: [])).each { declared.add(packageName.replace('.', '/') + '/' + it) }
        }
        Set<String> refmapEntries = names.findAll { it.endsWith('.refmap.json') } as Set
        if (refmapEntries != [baseline.path] as Set) errors.add("${target.id}: Forge Mixin refmap resource set differs")
        Map refmap = json(archive, baseline.path.toString(), target, errors)
        if (refmap == null) return
        if (declared != (baseline.mappings as Map).keySet() as Set) errors.add("${target.id}: Forge Mixin declarations differ from refmap baseline")
        if (refmap.mappings != baseline.mappings) errors.add("${target.id}: Forge Mixin production mapping baseline differs")
        if (!(refmap.data instanceof Map) || refmap.data.searge != baseline.mappings) errors.add("${target.id}: Forge Mixin searge mapping baseline differs")
    }

    static void verifyMenuPreviewCompatibility(ZipFile archive, Map target, List<String> errors) {
        if (target.minecraft.epoch != '1.20.1') return
        String layoutElement = target.loader.id == 'fabric'
                ? 'net/minecraft/class_8021'
                : 'net/minecraft/client/gui/layouts/LayoutElement'
        verifyBytecodeMarker(
                archive,
                'com/naocraftlab/skins/compat/v1_20_1/client/NclSkinsMenuPreview.class',
                target,
                [layoutElement],
                [],
                errors)
    }

    static Map json(ZipFile archive, String path, Map target, List<String> errors) {
        ZipEntry entry = archive.getEntry(path)
        if (entry == null) { errors.add("${target.id}: missing ${path}"); return null }
        try { new JsonSlurper().parseText(new String(read(archive, path), StandardCharsets.UTF_8)) as Map }
        catch (Exception error) { errors.add("${target.id}: invalid ${path}: ${error.message}"); null }
    }
    static void verifyBytecodeMarker(ZipFile archive, String path, Map target, List<String> required, List<String> forbidden, List<String> errors) {
        ZipEntry entry = archive.getEntry(path)
        if (entry == null) { errors.add("${target.id}: missing entrypoint ${path}"); return }
        String bytes = new String(read(archive, path), StandardCharsets.ISO_8859_1)
        required.each { if (!bytes.contains(it)) errors.add("${target.id}:${path}: missing bytecode marker ${it}") }
        forbidden.each { if (bytes.contains(it)) errors.add("${target.id}:${path}: forbidden bytecode marker ${it}") }
    }
    static byte[] read(ZipFile archive, String name) { archive.getInputStream(archive.getEntry(name)).withCloseable { it.readAllBytes() } }
    static int readInt(byte[] bytes, int offset) { ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16) | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff) }
    private ArtifactVerifier() {}
}

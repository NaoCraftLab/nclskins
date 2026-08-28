package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

final class ArtifactVerifier {
    static final List<String> FORBIDDEN_PREFIXES = [
            'com/microsoft/aad', 'com/nimbusds/', 'com/sun/jna/',
            'com/fasterxml/jackson/', 'com/google/gson/', 'org/sqlite/',
            'org/apache/logging/log4j/', 'org/apache/log4j/', 'org/slf4j/',
            'ch/qos/logback/', 'org/apache/logging/log4j/core/',
            'META-INF/services/org.slf4j.', 'META-INF/services/org.apache.logging.']
    static final List<String> FORBIDDEN_DEV_RUNTIME_PREFIXES = [
            'com/terraformersmc/modmenu/',
            'META-INF/jars/modmenu',
            'net/covers1624/devlogin/',
            'META-INF/jars/DevLogin'
    ]
    static final Pattern FORBIDDEN_CONTENT = Pattern.compile('login' + '\\.microsoftonline\\.com|refresh' + '_token|launcher' + '_accounts\\.json|accounts\\.json')
    static final Pattern FORBIDDEN_MIXIN = Pattern.compile('(?:User|Session|Authlib).*Mixin\\.class$')
    static final Pattern TOKEN = Pattern.compile('Bearer\\s+[A-Za-z0-9._~+/=-]{20,}|eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}')
    static final String COLLECTIONS = 'resourcepacks/mojang_collections/'
    static final String BUTTONS = 'assets/nclskins/textures/gui/icons/'
    static final Map<String, Integer> BUTTON_ICON_SIZES = [
        'body_all_off.png'       : 20,
        'body_all_on.png'        : 20,
        'body_both_arms_off.png' : 20,
        'body_left_arm_off.png'  : 20,
        'body_only_arms_on.png'  : 20,
        'body_only_left_arm.png' : 20,
        'body_only_right_arm.png': 20,
        'body_right_arm_off.png' : 20,
        'cape.png'               : 20,
        'collapse_all.png'       : 20,
        'compatibility_sparkle.png': 20,
        'compatibility_warning.png': 20,
        'delete.png'             : 20,
        'duplicate.png'          : 20,
        'edit.png'               : 20,
        'elytra.png'             : 20,
        'expand_all.png'         : 20,
        'folder.png'             : 20,
        'head_off.png'           : 20,
        'head_on.png'            : 20,
        'legs_all_off.png'       : 20,
        'legs_all_on.png'        : 20,
        'legs_left_off.png'      : 20,
        'legs_right_off.png'     : 20,
        'no_cape.png'            : 32,
        'plus.png'               : 32
    ].asImmutable()
    static final Map<String, Map> FORGE_REFMAPS = [
        'forge-1.20.1': [
            path: 'nclskins.resourcelocation-playerinfo.refmap.json',
            mappings: [
                'com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/mixin/AccessibilityOptionsScreenMixin': [
                    options: 'Lnet/minecraft/client/gui/screens/AccessibilityOptionsScreen;m_232690_(Lnet/minecraft/client/Options;)[Lnet/minecraft/client/OptionInstance;'
                ],
                'com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/mixin/AbstractClientPlayerPreviewMixin': [
                        getCloakTextureLocation : 'Lnet/minecraft/client/player/AbstractClientPlayer;m_108561_()Lnet/minecraft/resources/ResourceLocation;',
                        isCapeLoaded            : 'Lnet/minecraft/client/player/AbstractClientPlayer;m_108555_()Z',
                        getSkinTextureLocation  : 'Lnet/minecraft/client/player/AbstractClientPlayer;m_108560_()Lnet/minecraft/resources/ResourceLocation;',
                        isElytraLoaded          : 'Lnet/minecraft/client/player/AbstractClientPlayer;m_108562_()Z',
                        isSkinLoaded            : 'Lnet/minecraft/client/player/AbstractClientPlayer;m_108559_()Z',
                        getModelName            : 'Lnet/minecraft/client/player/AbstractClientPlayer;m_108564_()Ljava/lang/String;',
                        getElytraTextureLocation: 'Lnet/minecraft/client/player/AbstractClientPlayer;m_108563_()Lnet/minecraft/resources/ResourceLocation;'
                ],
                'com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/mixin/HttpTextureUploadMixin'          : [
                        'upload(Lcom/mojang/blaze3d/platform/NativeImage;)V': 'Lnet/minecraft/client/renderer/texture/HttpTexture;m_118020_(Lcom/mojang/blaze3d/platform/NativeImage;)V'
                ],
                'com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/mixin/LivingEntityRendererPreviewMixin': [
                        render                                                                                                                                                                                              : 'Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;m_7392_(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V',
                        'Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V': 'Lnet/minecraft/client/renderer/entity/layers/RenderLayer;m_6494_(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V'
                ],
                'com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/mixin/ModelPartPreviewMixin'           : [
                        getRandomCube: 'Lnet/minecraft/client/model/geom/ModelPart;m_233558_(Lnet/minecraft/util/RandomSource;)Lnet/minecraft/client/model/geom/ModelPart$Cube;'
                ],
                'com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/mixin/OptionsScreenMixin': [
                    init: 'Lnet/minecraft/client/gui/screens/OptionsScreen;m_7856_()V',
                    'Lnet/minecraft/client/gui/screens/OptionsScreen;openScreenButton(Lnet/minecraft/network/chat/Component;Ljava/util/function/Supplier;)Lnet/minecraft/client/gui/components/Button;': 'Lnet/minecraft/client/gui/screens/OptionsScreen;m_260993_(Lnet/minecraft/network/chat/Component;Ljava/util/function/Supplier;)Lnet/minecraft/client/gui/components/Button;'
                ],
                'com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/mixin/PlayerPreviewMixin'              : [
                        isModelPartShown: 'Lnet/minecraft/world/entity/player/Player;m_36170_(Lnet/minecraft/world/entity/player/PlayerModelPart;)Z',
                        getItemBySlot   : 'Lnet/minecraft/world/entity/player/Player;m_6844_(Lnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/world/item/ItemStack;'
                ],
                'com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/mixin/ScreenRenderablesAccessor': [
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
            if (names.any { containsFinderMetadata(it) }) {
                errors.add("${target.id}: artifact contains Finder metadata")
            }
            if (names.any { String name -> FORBIDDEN_PREFIXES.any { name.startsWith(it) } }) errors.add("${target.id}: artifact embeds a forbidden auth/native/JSON dependency")
            if (names.any { String name -> forbiddenLoggingPayload(name) }) {
                errors.add("${target.id}: artifact embeds a logging implementation, provider, or configuration")
            }
            if (names.any { String name -> FORBIDDEN_DEV_RUNTIME_PREFIXES.any { name.startsWith(it) } }) errors.add("${target.id}: artifact embeds the dev-only Mod Menu dependency")
            if (names.any { FORBIDDEN_MIXIN.matcher(it).find() }) errors.add("${target.id}: artifact contains a forbidden session/auth mixin candidate")
            verifyContentClosure(root, archive, catalog, target, names, errors)
            verifyClassfiles(archive, target, names, errors)
            verifyGeneratedBindings(archive, catalog, target, names, errors)
            verifyLegal(root, archive, target, errors)
            verifyMetadata(archive, catalog, target, version, errors)
            verifyUpdateNotification(archive, target, names, errors)
            verifyResources(root, archive, catalog, target, names, errors)
            verifyManifest(archive, target, errors)
            verifyForgeRefmap(archive, target, names, errors)
            verifyMixinExtrasPayload(archive, catalog, target, names, errors)
            verifyMenuPreviewCompatibility(archive, target, errors)
            verifyPreviewRegistration(archive, target, names, errors)
            names.findAll { !it.endsWith('/') }.each { String name ->
                byte[] bytes = read(archive, name)
                String content = new String(bytes, StandardCharsets.ISO_8859_1)
                if (FORBIDDEN_CONTENT.matcher(content).find()) errors.add("${target.id}:${name}: forbidden OAuth/launcher reference")
                if (TOKEN.matcher(content).find()) errors.add("${target.id}:${name}: credential-like value found")
            }
        }
    }

    private static boolean forbiddenLoggingPayload(String name) {
        String lower = name.toLowerCase(Locale.ROOT)
        name.endsWith('/JndiLookup.class') || name.endsWith('/JndiManager.class') ||
                lower.endsWith('log4j2.xml') || lower.endsWith('log4j2.json') ||
                lower.endsWith('log4j2.yaml') || lower.endsWith('logback.xml')
    }

    static boolean containsFinderMetadata(String name) {
        List<String> segments = name.split('/') as List<String>
        segments.contains('.DS_Store') || segments.contains('__MACOSX')
    }

    static void verifyPreviewRegistration(
            ZipFile archive, Map target, List<String> names, List<String> errors) {
        if (!target.capabilities.preview.toString().startsWith('avatar-pip-')) return
        String constructorMixin = 'com/naocraftlab/skins/compat/client/identifier/submission/mixin/GuiRendererMixin.class'
        String fabricRegistration = 'com/naocraftlab/skins/loader/fabric/FabricPipRendererRegistration.class'
        String neoForgeRegistration = 'com/naocraftlab/skins/loader/neoforge/NeoForgePipRendererRegistration.class'
        List<String> registrations = [constructorMixin, fabricRegistration, neoForgeRegistration]
                .findAll { names.contains(it) }
        String expected = target.id == 'fabric-1.21.11'
                ? constructorMixin
                : target.loader.id == 'fabric' ? fabricRegistration : neoForgeRegistration
        if (registrations != [expected]) {
            errors.add("${target.id}: expected exactly one native PIP registration ${expected}, found ${registrations}")
            return
        }
        String registrationBytecode = new String(read(archive, expected), StandardCharsets.ISO_8859_1)
        ['java/lang/reflect', 'getDeclaredMethod', 'getDeclaredConstructor'].each { String forbidden ->
            if (registrationBytecode.contains(forbidden)) {
                errors.add("${target.id}:${expected}: PIP registration contains reflection (${forbidden})")
            }
        }
        boolean hasSubmissionConstructorMixin = names.contains('nclskins.identifier-submission-fabric.mixins.json')
        if (hasSubmissionConstructorMixin != (target.id == 'fabric-1.21.11')) {
            errors.add("${target.id}: Fabric 1.21.11 constructor mixin presence is incorrect")
        }
        List<String> removedPreviewClasses = [
                'com/naocraftlab/skins/compat/client/identifier/submission/NclPreviewState.class',
                'com/naocraftlab/skins/compat/client/identifier/submission/mixin/AvatarRenderStateMixin.class',
                'com/naocraftlab/skins/compat/client/identifier/submission/mixin/GuiEntityRendererMixin.class'
        ]
        removedPreviewClasses.findAll { names.contains(it) }.each { String name ->
            errors.add("${target.id}: artifact retains removed global preview hook ${name}")
        }
        if (target.minecraft.epoch == '1.21.11') {
            [
                    'com/naocraftlab/skins/compat/client/identifier/submission/BakedPreviewRenderer.class',
                    'com/naocraftlab/skins/compat/client/identifier/submission/LivePreviewRenderer.class'
            ].findAll { !names.contains(it) }.each { String name ->
                errors.add("${target.id}: artifact lacks required dedicated PIP renderer ${name}")
            }
        }
        ['com/unascribed/ears', 'traben/entity_model_features',
         'traben/entity_texture_features'].each { String forbidden ->
            names.findAll { it.endsWith('.class') }.each { String name ->
                if (new String(read(archive, name), StandardCharsets.ISO_8859_1)
                        .contains(forbidden)) {
                    errors.add("${target.id}:${name}: preview compatibility references ${forbidden}")
                }
            }
        }
    }

    static void verifyContentClosure(
            File root,
            ZipFile archive,
            Map catalog,
            Map target,
            List<String> names,
            List<String> errors) {
        Set<String> actualClasses = names.findAll { it.endsWith('.class') } as Set
        File classDirectory = new File(root, "${target.path}/build/classes/java")
        List<String> productionSourceSets = ['main']
        if (target.sourceLayout == 'fabricSplit') productionSourceSets.add('client')
        Set<String> expectedClasses = [] as Set
        productionSourceSets.each { String sourceSet ->
            expectedClasses.addAll(relativeFiles(
                    new File(classDirectory, sourceSet), errors, target,
                    "compiled ${sourceSet} class"))
        }
        verifyExactEntrySet(target, 'classfile', actualClasses, expectedClasses, errors)

        Map resolved = CatalogTools.resolveTargetSources(root, catalog, target)
        Set<String> sourceStems = [] as Set
        (resolved.java as List).each { Object rawRoot ->
            File sourceRoot = new File(root, rawRoot.toString())
            relativeFiles(sourceRoot, errors, target, 'Java source')
                    .findAll { it.endsWith('.java') }
                    .each { sourceStems.add(it.substring(0, it.length() - '.java'.length())) }
        }
        Set<String> generatedClasses = [
                'com/naocraftlab/skins/generated/TargetClientBindings.class',
                'com/naocraftlab/skins/generated/TargetServerBindings.class'
        ] as Set
        Set<String> foreignClasses = actualClasses.findAll { String entry ->
            !generatedClasses.contains(entry) && !sourceStems.any { String stem ->
                entry == "${stem}.class" || entry.startsWith("${stem}\$")
            }
        } as Set
        if (!foreignClasses.isEmpty()) {
            errors.add("${target.id}: classfiles are not owned by selected source bundles: ${sample(foreignClasses)}")
        }

        Set<String> actualResources = names.findAll {
            !it.endsWith('/') && !it.endsWith('.class')
        } as Set
        File resourceDirectory = new File(root, "${target.path}/build/resources/main")
        Set<String> processedResources = relativeFiles(
                resourceDirectory, errors, target, 'processed resource')
        Set<String> toolchainTransformedResources = [] as Set
        if (target.artifact.remapJar && target.metadata.accessWidener) {
            toolchainTransformedResources.add(target.metadata.accessWidener.toString())
        }
        processedResources.findAll {
            actualResources.contains(it) && !toolchainTransformedResources.contains(it)
        }.each { String entry ->
            File processed = new File(resourceDirectory, entry)
            if (!MessageDigest.isEqual(processed.bytes, read(archive, entry))) {
                errors.add("${target.id}: resource differs from processed target output: ${entry}")
            }
        }
        Set<String> expectedResources = new LinkedHashSet<>(processedResources)
        expectedResources.addAll(['META-INF/MANIFEST.MF', 'META-INF/LICENSE', 'META-INF/NOTICE'])
        Map forgeRefmap = FORGE_REFMAPS[target.id.toString()]
        if (forgeRefmap != null) expectedResources.add(forgeRefmap.path.toString())
        expectedResources.addAll(mixinExtrasResources(catalog, target))
        verifyExactEntrySet(target, 'resource', actualResources, expectedResources, errors)

        Set<String> selectedResources = [] as Set
        (resolved.resources as List).each { Object rawRoot ->
            selectedResources.addAll(relativeFiles(
                    new File(root, rawRoot.toString()), errors, target, 'resource source'))
        }
        selectedResources.removeIf { it.endsWith('.pixel.json') }
        selectedResources.addAll(target.metadata.files as List)
        selectedResources.add('nclskins-server-compatibility.json')
        LocalizationVerifier.aliases(catalog).keySet().each { String alias ->
            selectedResources.add("assets/nclskins/lang/${alias}.json".toString())
            selectedResources.add((COLLECTIONS + "assets/nclskins/lang/${alias}.json").toString())
        }
        selectedResources.addAll(['META-INF/MANIFEST.MF', 'META-INF/LICENSE', 'META-INF/NOTICE'])
        if (forgeRefmap != null) selectedResources.add(forgeRefmap.path.toString())
        selectedResources.addAll(mixinExtrasResources(catalog, target))
        Set<String> foreignResources = actualResources - selectedResources
        if (!foreignResources.isEmpty()) {
            errors.add("${target.id}: resources are not owned by selected source bundles or generated target metadata: ${sample(foreignResources)}")
        }
    }

    private static Set<String> mixinExtrasResources(Map catalog, Map target) {
        if (target.loader.id != 'forge') return [] as Set
        String version = catalog.plugins.mixinExtras.toString()
        return [
                'META-INF/jarjar/metadata.json',
                "META-INF/jarjar/mixinextras-forge-${version}.jar"
        ] as Set
    }

    private static void verifyMixinExtrasPayload(
            ZipFile archive,
            Map catalog,
            Map target,
            List<String> names,
            List<String> errors) {
        if (target.loader.id != 'forge') return
        String version = catalog.plugins.mixinExtras.toString()
        String payload = "META-INF/jarjar/mixinextras-forge-${version}.jar"
        if (!names.contains(payload)) {
            errors.add("${target.id}: missing official MixinExtras Forge JarJar payload ${payload}")
            return
        }
        String metadata = new String(read(archive, 'META-INF/jarjar/metadata.json'), StandardCharsets.UTF_8)
        if (!metadata.contains('io.github.llamalad7') ||
                !metadata.contains('mixinextras-forge') ||
                !metadata.contains(version)) {
            errors.add("${target.id}: MixinExtras JarJar metadata does not pin ${version}")
        }
    }

    static void verifyExactEntrySet(
            Map target,
            String kind,
            Collection<String> actual,
            Collection<String> expected,
            List<String> errors) {
        Set<String> unexpected = (actual as Set) - (expected as Set)
        Set<String> missing = (expected as Set) - (actual as Set)
        if (!unexpected.isEmpty()) {
            errors.add("${target.id}: unexpected ${kind} entries: ${sample(unexpected)}")
        }
        if (!missing.isEmpty()) {
            errors.add("${target.id}: missing ${kind} entries from target build outputs: ${sample(missing)}")
        }
    }

    private static Set<String> relativeFiles(
            File directory,
            List<String> errors,
            Map target,
            String label) {
        if (!directory.isDirectory()) {
            errors.add("${target.id}: missing ${label} directory ${directory}")
            return [] as Set
        }
        Set<String> result = [] as Set
        Files.walk(directory.toPath()).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { path ->
                def relative = directory.toPath().relativize(path)
                result.add(relative.toString().replace(File.separatorChar, '/' as char))
            }
        }
        result
    }

    private static String sample(Collection<String> entries) {
        List<String> sorted = entries.toList().sort()
        String suffix = sorted.size() > 8 ? " (+${sorted.size() - 8} more)" : ''
        sorted.take(8).join(', ') + suffix
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

    static void verifyGeneratedBindings(
            ZipFile archive,
            Map catalog,
            Map target,
            List<String> names,
            List<String> errors) {
        List<String> bindings = [
                'com/naocraftlab/skins/generated/TargetClientBindings.class',
                'com/naocraftlab/skins/generated/TargetServerBindings.class'
        ]
        bindings.each { String path ->
            if (!names.contains(path)) {
                errors.add("${target.id}: missing generated binding ${path}")
            }
        }
        if (bindings.any { !names.contains(it) }) return
        String constants = bindings.collect { String path ->
            new String(read(archive, path), StandardCharsets.ISO_8859_1)
        }.join('\n')
        [target.id, target.epochProfile, target.loaderProfile,
         target.integrationProfile, target.buildProfile].each { Object value ->
            if (!constants.contains(value.toString())) {
                errors.add("${target.id}: generated bindings omit profile ${value}")
            }
        }
        String providerClass = catalog.profiles.epochs[target.epochProfile]
                .clientProviderClass.toString()
        if (providerClass != null) {
            String providerPath = providerClass.replace('.', '/') + '.class'
            if (!names.contains(providerPath) || !constants.contains(providerClass.replace('.', '/'))) {
                errors.add("${target.id}: generated client provider is not artifact-reachable: ${providerClass}")
            }
        }
        (target.capabilities as Map).each { Object role, Object implementation ->
            if (!constants.contains(role.toString()) || !constants.contains(implementation.toString())) {
                errors.add("${target.id}: generated bindings cannot reach ${role}=${implementation}")
            }
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
            if (metadata.entrypoints != [main: [target.metadata.serverEntrypoint], client: [target.metadata.entrypoint], modmenu: [target.metadata.modMenuEntrypoint]]) errors.add("${target.id}: Fabric entrypoints differ from catalog")
            if (metadata.depends != ['fabricloader': target.loader.predicate, 'fabric-api': target.loader.apiPredicate, minecraft: target.minecraft.predicate, java: ">=${target.java.release}"]) errors.add("${target.id}: Fabric dependencies differ from catalog")
            Map expectedSuggestions = [modmenu: ">=${target.loader.modMenuVersion}".toString()]
            (catalog.optionalDependencies as Map).each { Object dependencyId, Object ignored ->
                String predicate = CatalogTools.optionalDependencyPredicate(catalog, target, dependencyId.toString())
                if (predicate != null) expectedSuggestions[dependencyId.toString()] = predicate
            }
            if (metadata.suggests != expectedSuggestions) errors.add("${target.id}: Fabric suggestions differ from catalog")
            if (metadata.custom != [modmenu: [links: MetadataRenderer.modMenuLinks(catalog.mod as Map), update_checker: true]]) errors.add("${target.id}: Fabric Mod Menu card metadata differs from catalog")
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

    static void verifyUpdateNotification(
            ZipFile archive, Map target, List<String> names, List<String> errors) {
        String implementation = target.capabilities.updateNotification?.toString()
        String loader = target.loader.id.toString()
        if (loader == 'fabric') {
            String expected = target.id == 'fabric-1.20.1'
                    ? 'modmenu-default-index' : 'modmenu-static-catalog'
            if (implementation != expected) {
                errors.add("${target.id}: unexpected update notification capability ${implementation}")
                return
            }
            String entrypoint = target.metadata.modMenuEntrypoint.toString()
                    .replace('.', '/') + '.class'
            if (!names.contains(entrypoint)) {
                errors.add("${target.id}: missing Mod Menu entrypoint ${entrypoint}")
                return
            }
            String bytecode = new String(read(archive, entrypoint), StandardCharsets.ISO_8859_1)
            List<String> modernReferences = [
                    'com/terraformersmc/modmenu/api/UpdateChecker',
                    'com/terraformersmc/modmenu/api/UpdateInfo',
                    'com/terraformersmc/modmenu/api/UpdateChannel',
                    'com/naocraftlab/skins/runtime/update/UpdateCatalogClient',
                    target.id.toString()
            ]
            if (implementation == 'modmenu-default-index') {
                modernReferences.findAll { bytecode.contains(it) }.each { String reference ->
                    errors.add("${target.id}: legacy Mod Menu entrypoint references ${reference}")
                }
            } else {
                modernReferences.findAll { !bytecode.contains(it) }.each { String reference ->
                    errors.add("${target.id}: modern Mod Menu entrypoint lacks ${reference}")
                }
            }
            return
        }

        if (implementation != 'native-static-catalog') {
            errors.add("${target.id}: Forge-family target lacks native static update capability")
        }
        String modMenuEntrypoint =
                'com/naocraftlab/skins/loader/fabric/NclSkinsModMenuApi.class'
        if (names.contains(modMenuEntrypoint)) {
            errors.add("${target.id}: native target contains Fabric Mod Menu entrypoint")
        }
        names.findAll { it.endsWith('.class') }.each { String name ->
            String bytecode = new String(read(archive, name), StandardCharsets.ISO_8859_1)
            if (bytecode.contains('com/terraformersmc/modmenu/')) {
                errors.add("${target.id}:${name}: native target references Mod Menu")
            }
        }
        String metadataPath = loader == 'forge'
                ? 'META-INF/mods.toml' : 'META-INF/neoforge.mods.toml'
        if (names.contains(metadataPath)) {
            String metadata = new String(read(archive, metadataPath), StandardCharsets.UTF_8)
            String expectedUrl = MetadataRenderer.nativeUpdatesUrl(target)
            if (!metadata.contains("updateJSONURL=\"${expectedUrl}\"")) {
                errors.add("${target.id}: native metadata lacks exact update endpoint")
            }
        }
    }

    static void verifyResources(File root, ZipFile archive, Map catalog, Map target, List<String> names, List<String> errors) {
        List<String> locales = LocalizationVerifier.artifactLocales(catalog)
        Set<String> expected = [catalog.mod.icon, COLLECTIONS + 'NOTICE-MOJANG.md', COLLECTIONS + 'pack.mcmeta'] as Set
        expected.addAll(locales.collect { "assets/nclskins/lang/${it}.json".toString() })
        expected.addAll(locales.collect {
            (COLLECTIONS + "assets/nclskins/lang/${it}.json").toString()
        })
        if (target.metadata.accessWidener) expected.add(target.metadata.accessWidener.toString())
        if (target.metadata.accessTransformer) expected.add(target.metadata.accessTransformer.toString())
        expected.addAll(target.metadata.serverMixins ?: [])
        expected.addAll(target.metadata.mixins ?: [])
        expected.findAll { !names.contains(it) }.each { errors.add("${target.id}: missing required resource ${it}") }
        Set<String> expectedCanonicalLanguages = locales.collect {
            "assets/nclskins/lang/${it}.json".toString()
        } as Set
        Set<String> actualCanonicalLanguages = names.findAll {
            it.startsWith('assets/nclskins/lang/') && it.endsWith('.json')
        } as Set
        if (actualCanonicalLanguages != expectedCanonicalLanguages) {
            errors.add("${target.id}: canonical locale inventory differs")
        }
        Set<String> expectedCollectionLanguages = locales.collect {
            (COLLECTIONS + "assets/nclskins/lang/${it}.json").toString()
        } as Set
        Set<String> actualCollectionLanguages = names.findAll {
            it.startsWith(COLLECTIONS + 'assets/nclskins/lang/') && it.endsWith('.json')
        } as Set
        if (actualCollectionLanguages != expectedCollectionLanguages) {
            errors.add("${target.id}: Mojang collection locale inventory differs")
        }

        Map english = json(archive, 'assets/nclskins/lang/en_us.json', target, errors)
        locales.each { String locale ->
            String source = LocalizationVerifier.sourceLocale(catalog, locale)
            Map language = json(archive, "assets/nclskins/lang/${locale}.json", target, errors)
            if (language != null) {
                String description = catalog.mod.descriptions[source].toString()
                LocalizationVerifier.DESCRIPTION_KEYS.each { String key ->
                    if (language[key] != description) errors.add("${target.id}: ${locale} ${key} differs from catalog")
                }
                if (english != null) {
                    LocalizationVerifier.validateLanguage(locale, language, english, false, errors)
                }
                if (language.values().any { it.toString().contains('@NCLSKINS_DESCRIPTION@') }) {
                    errors.add("${target.id}: ${locale} retains a raw description token")
                }
            }
            Map collection = json(archive,
                    COLLECTIONS + "assets/nclskins/lang/${locale}.json", target, errors)
            if (collection != null && collection.keySet() != LocalizationVerifier.collectionKeys(root)) {
                errors.add("${target.id}: ${locale} Mojang collection key manifest differs")
            }
        }
        LocalizationVerifier.aliases(catalog).each { String alias, String source ->
            ['assets/nclskins/lang/', COLLECTIONS + 'assets/nclskins/lang/'].each { String prefix ->
                String aliasPath = prefix + alias + '.json'
                String sourcePath = prefix + source + '.json'
                if (archive.getEntry(aliasPath) != null && archive.getEntry(sourcePath) != null &&
                        !MessageDigest.isEqual(read(archive, aliasPath), read(archive, sourcePath))) {
                    errors.add("${target.id}: ${aliasPath} differs from processed ${sourcePath}")
                }
            }
        }
        if (names.any { it.endsWith('.pixel.json') }) errors.add("${target.id}: artifact contains pixel-grid agent data")
        if (target.loader.id == 'neoforge') {
            List<String> forbiddenModListApis = [
                    'net/neoforged/neoforge/client/gui/modlist/ModDisplayInfo',
                    'net/neoforged/neoforge/client/gui/modlist/DefaultModDisplayInfo'
            ]
            names.findAll { it.startsWith('com/naocraftlab/skins/') && it.endsWith('.class') }.each { String classEntry ->
                String bytecode = new String(read(archive, classEntry), StandardCharsets.ISO_8859_1)
                forbiddenModListApis.findAll { bytecode.contains(it) }.each { String forbiddenApi ->
                    errors.add("${target.id}: ${classEntry} contains forbidden code-driven mod-list metadata API ${forbiddenApi}")
                }
            }
        }
        List<String> archiveButtons = names.findAll { it.startsWith(BUTTONS) && it.endsWith('.png') }.sort()
        File canonicalResources = new File(root, 'compat/resources/canonical/src/main/resources')
        List<File> sourceButtons = new File(canonicalResources, BUTTONS).listFiles()?.findAll { it.name.endsWith('.png') }?.sort { it.name } ?: []
        Set<String> sourceButtonNames = sourceButtons.collect { it.name } as Set
        Set<String> expectedButtons = BUTTON_ICON_SIZES.keySet().collect { BUTTONS + it } as Set
        if (sourceButtonNames != BUTTON_ICON_SIZES.keySet()) errors.add("${target.id}: source button icon manifest differs from the size contract")
        if ((archiveButtons as Set) != expectedButtons) errors.add("${target.id}: button icon manifest differs")
        sourceButtons.each { File source ->
            Integer size = BUTTON_ICON_SIZES[source.name]
            if (size != null) compareResource(archive, BUTTONS + source.name, source, target, size, size, errors)
        }
        compareResource(archive, catalog.mod.icon.toString(), new File(canonicalResources, catalog.mod.icon.toString()), target, 128, 128, errors)
        verifyNestedPackIcon(root, archive, catalog, target, errors)
        File collections = new File(root, 'compat/resources/mojang-collections/src/main/resources/resourcepacks/mojang_collections')
        List<File> skins = []
        Files.walk(collections.toPath()).withCloseable { stream ->
            stream.filter {
                def relative = collections.toPath().relativize(it)
                Files.isRegularFile(it) && it.toString().endsWith('.png') &&
                        relative.nameCount > 0 && relative.getName(0).toString() == 'assets'
            }.forEach { skins.add(it.toFile()) }
        }
        Set<String> expectedSkins = skins.collect { COLLECTIONS + collections.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/' as char) } as Set
        Set<String> actualSkins = names.findAll { it.startsWith(COLLECTIONS + 'assets/') && it.endsWith('.png') } as Set
        if (actualSkins != expectedSkins) errors.add("${target.id}: Mojang collection skin manifest differs")
        skins.each { File source -> compareResource(archive, COLLECTIONS + collections.toPath().relativize(source.toPath()).toString().replace(File.separatorChar, '/' as char), source, target, 64, 64, errors) }
        Set<String> collectionsIds = skins.collect { File skin -> collections.toPath().relativize(skin.toPath()).getName(1).toString() } as Set
        collectionsIds.each { String id -> if (!names.contains(COLLECTIONS + "assets/${id}/notice-mojang.md")) errors.add("${target.id}: missing collection provenance notice for ${id}") }
    }

    static void verifyNestedPackIcon(
            File root, ZipFile archive, Map catalog, Map target, List<String> errors) {
        String path = COLLECTIONS + 'pack.png'
        if (archive.getEntry(path) == null) {
            errors.add("${target.id}: missing required resource ${path}")
            return
        }
        File canonical = new File(
                root,
                'compat/resources/canonical/src/main/resources/' + catalog.mod.icon.toString())
        compareResource(archive, path, canonical, target, 128, 128, errors)
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
                'com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/NclSkinsMenuPreview.class',
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

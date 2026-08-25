package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import javax.imageio.ImageIO
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.regex.Pattern

final class CatalogTools {
    static final Set<String> REQUIRED_CAPABILITIES = [
        'gui', 'textures', 'preview', 'appearance', 'loaderScreen', 'session',
        'clientExecutor', 'filePicker', 'bundledSkin', 'currentAppearance',
        'updateNotification',
        'serverSignal', 'serverSignalReceiver', 'serverProfileVerification',
        'serverProfileMutation', 'serverTracking', 'serverPlayerInfoPublication',
        'serverLoader'
    ] as Set
    static final Set<String> EXTERNAL_ABI_CAPABILITIES = ['updateNotification'] as Set
    static final Set<String> REQUIRED_TARGET_KEYS = [
        'id', 'path', 'minecraft', 'loader', 'java', 'development', 'gradleFamily',
        'sourceLayout', 'capabilities', 'metadata', 'artifact', 'epochProfile',
        'loaderProfile', 'integrationProfile', 'buildProfile', 'releaseEligible'
    ] as Set
    static final Set<String> TARGET_KEYS = REQUIRED_TARGET_KEYS + ['compatibility'] as Set
    static final Set<String> MOD_KEYS = [
            'id', 'name', 'group', 'license', 'descriptions', 'contact', 'links', 'platforms',
            'authors', 'icon', 'iconBlur'
    ] as Set
    static final Pattern VERSION_PATTERN = Pattern.compile(
        '^(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)' +
        '(?:-(?:alpha|beta)\\.[1-9][0-9]*)?$')
    static final Pattern UUID_PATTERN = Pattern.compile(
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
    static final Pattern PARCHMENT_VERSION_PATTERN = Pattern.compile(
            '^[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}(?:-nightly-SNAPSHOT)?$')
    static final Pattern PARCHMENT_ARTIFACT_VERSION_PATTERN = Pattern.compile(
            '^[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}' +
                    '(?:-nightly-[0-9]{8}\\.[0-9]{6}-[1-9][0-9]*)?$')

    static Map loadCatalog(File repositoryRoot) {
        def value = new JsonSlurper().parse(new File(repositoryRoot, 'gradle/targets.json'))
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException('gradle/targets.json must contain an object')
        }
        materialize(value) as Map
    }

    static Map loadCatalog(Path repositoryRoot) {
        loadCatalog(repositoryRoot.toFile())
    }

    static Map loadJson(File file) {
        def value = new JsonSlurper().parse(file)
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("${file} must contain an object")
        }
        materialize(value) as Map
    }

    static Map loadJson(Path file) {
        loadJson(file.toFile())
    }

    static Object materialize(Object value) {
        if (value instanceof Map) {
            Map copy = new LinkedHashMap()
            (value as Map).each { Object key, Object entry -> copy[key] = materialize(entry) }
            return copy
        }
        if (value instanceof List) {
            return (value as List).collect { materialize(it) }
        }
        value
    }

    static String loadVersion(File repositoryRoot) {
        parseVersion(new File(repositoryRoot, 'gradle/version.properties'))
    }

    static String loadVersion(Path path) {
        File file = path.toFile()
        parseVersion(file.name == 'version.properties' ? file : new File(file, 'gradle/version.properties'))
    }

    private static String parseVersion(File file) {
        List<String> lines = file
            .readLines(StandardCharsets.UTF_8.name())
            .collect { it.trim() }
            .findAll { it && !it.startsWith('#') }
        if (lines.size() != 1 || !lines[0].startsWith('modVersion=')) {
            throw new IllegalArgumentException('gradle/version.properties must define exactly modVersion')
        }
        String version = lines[0].substring('modVersion='.length()).trim()
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw new IllegalArgumentException("unsupported modVersion: ${version}")
        }
        version
    }

    static Map selectTarget(Map catalog, String targetId) {
        List selected = (catalog.targets as List).findAll { it.id == targetId }
        if (selected.size() != 1) {
            throw new IllegalArgumentException("unknown target: ${targetId}")
        }
        selected[0] as Map
    }

    static String minecraftCompileVersion(Map target) {
        Object raw = (target.minecraft as Map).compileVersion
        raw == null ? target.minecraft.version.toString() : raw.toString()
    }

    static List<Map> releaseTargets(Map catalog) {
        (catalog.targets as List<Map>).findAll { Map target ->
            target.releaseEligible == true
        }
    }

    static Map withCapabilityProbe(Map catalog, Map target, String rawOverride) {
        if (!(rawOverride instanceof String) || !rawOverride.contains('=')) {
            throw new IllegalArgumentException(
                    'nclskinsCapabilityProbe must use <capability>=<implementation>')
        }
        int separator = rawOverride.indexOf('=')
        String capability = rawOverride.substring(0, separator)
        String implementation = rawOverride.substring(separator + 1)
        if (!REQUIRED_CAPABILITIES.contains(capability) || implementation.isBlank()) {
            throw new IllegalArgumentException("invalid capability probe '${rawOverride}'")
        }
        Set<String> compatibleImplementations = (catalog.targets as List)
                .findAll { it instanceof Map }
                .collect { (it.capabilities as Map)[capability]?.toString() }
                .findAll { it != null }
                .toSet()
        if (!compatibleImplementations.contains(implementation)) {
            throw new IllegalArgumentException(
                    "${implementation} is not declared for capability ${capability}")
        }
        Map probed = materialize(target) as Map
        probed.capabilities[capability] = implementation
        probed
    }

    static Map catalogWithCapabilityProbe(Map catalog, String targetId, String rawOverride) {
        Map copy = materialize(catalog) as Map
        int index = (copy.targets as List).findIndexOf { it.id == targetId }
        if (index < 0) {
            throw new IllegalArgumentException("unknown target: ${targetId}")
        }
        (copy.targets as List)[index] = withCapabilityProbe(
                copy, (copy.targets as List)[index] as Map, rawOverride)
        copy
    }

    static List<String> clientArguments(Map catalog) {
        if (!(catalog.development instanceof Map) ||
                ((catalog.development as Map).keySet() as Set) !=
                ['clientUuid', 'licensedProfiles', 'loggingProfiles', 'operators'] as Set) {
            throw new IllegalArgumentException(
                    'development must define exactly clientUuid, licensedProfiles, loggingProfiles, and operators')
        }
        Map development = catalog.development as Map
        Object rawUuid = development.clientUuid
        if (!(rawUuid instanceof String) || !UUID_PATTERN.matcher(rawUuid as String).matches()) {
            throw new IllegalArgumentException('development.clientUuid must be a canonical lowercase UUID')
        }
        ['--uuid', rawUuid as String]
    }

    static List<Map> developmentOperators(Map catalog) {
        clientArguments(catalog)
        Object raw = (catalog.development as Map).operators
        if (!(raw instanceof List) || (raw as List).size() != 2) {
            throw new IllegalArgumentException('development.operators must define exactly two operators')
        }
        List<Map> operators = (raw as List).collect { Object entry ->
            if (!(entry instanceof Map) || ((entry as Map).keySet() as Set) !=
                    ['uuid', 'name', 'level', 'bypassesPlayerLimit'] as Set) {
                throw new IllegalArgumentException('development operator keys differ from schema')
            }
            Map operator = entry as Map
            if (!(operator.uuid instanceof String) ||
                    !UUID_PATTERN.matcher(operator.uuid.toString()).matches() ||
                    !(operator.name instanceof String) ||
                    !(operator.name.toString() ==~ /[A-Za-z0-9_]{3,16}/) ||
                    operator.level != 4 || operator.bypassesPlayerLimit != false) {
                throw new IllegalArgumentException('development operator is invalid')
            }
            operator
        }
        if (operators*.uuid.toSet().size() != operators.size() ||
                operators*.name.collect { it.toString().toLowerCase(Locale.ROOT) }.toSet().size() !=
                operators.size()) {
            throw new IllegalArgumentException('development operators must have unique UUIDs and names')
        }
        operators.asImmutable()
    }

    static String licensedClientProfile(Map catalog, Object rawLoader) {
        clientArguments(catalog)
        String loader = rawLoader.toString()
        Map profiles = (catalog.development as Map).licensedProfiles as Map
        if ((profiles.keySet() as Set) != ['fabric', 'forge', 'neoforge'] as Set ||
                profiles.any { Object key, Object value ->
                    !(value instanceof String) || !(value ==~ /[a-z][a-z0-9-]{2,63}/)
                }) {
            throw new IllegalArgumentException(
                    'development.licensedProfiles must map fabric, forge, and neoforge to safe profile labels')
        }
        if (profiles.fabric == profiles.forge || profiles.forge != profiles.neoforge) {
            throw new IllegalArgumentException(
                    'development.licensedProfiles must separate Fabric and share one Forge-family profile')
        }
        Object profile = profiles[loader]
        if (profile == null) {
            throw new IllegalArgumentException("unsupported licensed client loader: ${loader}")
        }
        profile.toString()
    }

    static void validateLoggingProfiles(Map catalog) {
        Map profiles = ((catalog.development as Map).loggingProfiles) as Map
        if ((profiles?.keySet() as Set) != ['loaders', 'serverKernels', 'proxies'] as Set ||
                ((profiles.loaders as Map)?.keySet() as Set) !=
                        ['fabric', 'forge', 'neoforge'] as Set ||
                ((profiles.serverKernels as Map)?.keySet() as Set) !=
                        ['craftbukkit', 'spigot', 'paper', 'purpur', 'folia'] as Set ||
                ((profiles.proxies as Map)?.keySet() as Set) !=
                        ['velocity', 'bungeecord'] as Set ||
                profiles.values().any { !(it instanceof Map) ||
                        (it as Map).values().any { value ->
                            !(value instanceof String) || !(value ==~ /[a-z][a-z0-9-]{2,63}/)
                        } }) {
            throw new IllegalArgumentException(
                    'development.loggingProfiles must exhaustively map loaders, server kernels, and proxies')
        }
    }

    static String repositoryRelative(File repositoryRoot, Object rawPath, String label) {
        if (!(rawPath instanceof String) || rawPath.isEmpty()) {
            throw new IllegalArgumentException("${label} must be a non-empty repository-relative path")
        }
        Path candidate = Path.of(rawPath as String)
        if (candidate.isAbsolute() || candidate.any { it.toString() == '..' }) {
            throw new IllegalArgumentException("${label} must be repository-relative without '..': ${rawPath}")
        }
        Path root = repositoryRoot.toPath().toRealPath()
        Path resolved = root.resolve(candidate).normalize()
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("${label} escapes the repository: ${rawPath}")
        }
        root.relativize(resolved).toString().replace(File.separatorChar, '/' as char)
    }

    static Map capabilityDeclaration(Map catalog, String implementationId) {
        Object raw = (catalog.capabilityImplementations as Map)[implementationId]
        if (!(raw instanceof Map) || (raw as Map).keySet() as Set != ['bundle', 'abiImplementation'] as Set) {
            throw new IllegalArgumentException("${implementationId}: invalid capability implementation declaration")
        }
        Map declaration = raw as Map
        if (!(declaration.bundle instanceof String) || !declaration.bundle ||
            !(declaration.abiImplementation instanceof String) || !declaration.abiImplementation) {
            throw new IllegalArgumentException("${implementationId}: capability declaration values must be non-empty")
        }
        declaration
    }

    static List<String> resolveBundleOrder(Map catalog, Collection<String> roots) {
        Map bundles = catalog.sourceBundles as Map
        List<String> resolved = []
        Set<String> visited = [] as Set
        List<String> visiting = []
        Closure<Void> visit
        visit = { String id ->
            if (visited.contains(id)) {
                return
            }
            if (visiting.contains(id)) {
                List<String> cycle = visiting.subList(visiting.indexOf(id), visiting.size()) + id
                throw new IllegalArgumentException('source bundle dependency cycle: ' + cycle.join(' -> '))
            }
            Object raw = bundles[id]
            if (!(raw instanceof Map)) {
                throw new IllegalArgumentException("missing source bundle '${id}'")
            }
            Map bundle = raw as Map
            if (!(bundle.side in ['common', 'client']) || !(bundle.requires instanceof List)) {
                throw new IllegalArgumentException("${id}: invalid source bundle side or requirements")
            }
            visiting.add(id)
            (bundle.requires as List).each { Object requiredRaw ->
                if (!(requiredRaw instanceof String) || !requiredRaw) {
                    throw new IllegalArgumentException("${id}: requires entries must be non-empty strings")
                }
                Object required = bundles[requiredRaw]
                if (!(required instanceof Map)) {
                    throw new IllegalArgumentException("${id}: missing required source bundle '${requiredRaw}'")
                }
                if (bundle.side == 'common' && (required as Map).side == 'client') {
                    throw new IllegalArgumentException("${id}: common bundle cannot require client bundle '${requiredRaw}'")
                }
                visit(requiredRaw as String)
            }
            visiting.remove(visiting.size() - 1)
            visited.add(id)
            resolved.add(id)
        }
        roots.each { visit(it) }
        resolved
    }

    static Map resolveTargetSources(File repositoryRoot, Map catalog, Map target) {
        List<String> roots = (catalog.baseBundles as List).collect { it.toString() }
        roots.add(accessBundle(catalog, target))
        roots.add(clientProviderBundle(catalog, target))
        (target.capabilities as Map).values().each {
            roots.add(capabilityDeclaration(catalog, it.toString()).bundle.toString())
        }
        List<String> order = resolveBundleOrder(catalog, roots)
        List<String> common = []
        List<String> client = []
        List<String> resources = []
        Map<String, String> owners = [:]
        order.each { String id ->
            Map bundle = (catalog.sourceBundles as Map)[id] as Map
            List<String> destination = bundle.side == 'common' ? common : client
            (bundle.java as List).eachWithIndex { Object raw, int index ->
                String path = repositoryRelative(repositoryRoot, raw, "${id}.java[${index}]")
                String previous = owners.putIfAbsent(path, bundle.side.toString())
                if (previous != null && previous != bundle.side) {
                    throw new IllegalArgumentException("Java source root '${path}' has conflicting sides")
                }
                if (!(path in common) && !(path in client)) {
                    destination.add(path)
                }
            }
            (bundle.resources as List).eachWithIndex { Object raw, int index ->
                String path = repositoryRelative(repositoryRoot, raw, "${id}.resources[${index}]")
                if (!(path in resources)) {
                    resources.add(path)
                }
            }
        }
        [bundles: order, commonJava: common, clientJava: client, java: (common + client).unique(), resources: resources]
    }

    static String accessBundle(Map catalog, Map target) {
        Map epochs = ((catalog.profiles as Map).epochs as Map)
        Map epoch = epochs[target.epochProfile] as Map
        Object bundle = (epoch.accessBundles as Map)[target.loader.id]
        if (!(bundle instanceof String) || bundle.isBlank() ||
                !((catalog.sourceBundles as Map).containsKey(bundle))) {
            throw new IllegalArgumentException(
                    "${target.id}: API profile has no declared access bundle for ${target.loader.id}")
        }
        bundle.toString()
    }

    static String clientProviderBundle(Map catalog, Map target) {
        Map epoch = ((catalog.profiles as Map).epochs as Map)[target.epochProfile] as Map
        Object bundle = epoch.clientProviderBundle
        if (!(bundle instanceof String) || bundle.isBlank() ||
                !((catalog.sourceBundles as Map).containsKey(bundle))) {
            throw new IllegalArgumentException(
                    "${target.id}: API profile has no declared client provider bundle")
        }
        bundle.toString()
    }

    static String optionalDependencyVersion(Map catalog, Map target, String dependencyId) {
        Map declaration = (catalog.optionalDependencies as Map)[dependencyId] as Map
        Object raw = (declaration.versions as Map)[target.id]
        raw == null ? null : raw.toString()
    }

    static String parchmentVersion(Map catalog, Map target) {
        Map declaration = parchmentDeclaration(catalog, target)
        Object raw = declaration?.version
        raw == null ? null : raw.toString()
    }

    static String parchmentArtifactUrl(Map catalog, Map target) {
        Map declaration = parchmentDeclaration(catalog, target)
        if (declaration == null) return null
        String minecraftVersion = target.minecraft.version.toString()
        String version = declaration.version.toString()
        String artifactVersion = declaration.artifactVersion.toString()
        "https://maven.parchmentmc.org/org/parchmentmc/data/parchment-${minecraftVersion}/" +
                "${version}/parchment-${minecraftVersion}-${artifactVersion}.zip"
    }

    static String parchmentArtifactVersion(Map catalog, Map target) {
        Map declaration = parchmentDeclaration(catalog, target)
        Object raw = declaration?.artifactVersion
        raw == null ? null : raw.toString()
    }

    private static Map parchmentDeclaration(Map catalog, Map target) {
        Object mappings = catalog.mappings
        if (!(mappings instanceof Map) || !((mappings as Map).parchment instanceof Map)) {
            return null
        }
        Object raw = ((mappings as Map).parchment as Map)[target.minecraft.version]
        raw instanceof Map ? raw as Map : null
    }

    static String optionalDependencyPredicate(Map catalog, Map target, String dependencyId) {
        Map declaration = (catalog.optionalDependencies as Map)[dependencyId] as Map
        if (declaration.predicates instanceof Map) {
            Object raw = (declaration.predicates as Map)[target.loader.id]
            return raw == null ? null : raw.toString()
        }
        String version = optionalDependencyVersion(catalog, target, dependencyId)
        if (version == null) return null
        int qualifier = version.indexOf('+')
        String baseVersion = qualifier < 0 ? version : version.substring(0, qualifier)
        target.loader.id == 'fabric' ? ">=${baseVersion}" : "[${baseVersion},)"
    }

    static void validate(File repositoryRoot, Map catalog) {
        List<String> errors = []
        Set expectedTop = [
                'schemaVersion', 'development', 'mod', 'plugins', 'mappings',
                'gradleFamilies', 'gsonCompatibility',
                'profiles', 'baseBundles', 'sourceBundles', 'capabilityImplementations',
                'optionalDependencies', 'publicationDependencies',
                'serverPlugin', 'serverPluginRuntimes', 'serverPluginTopologies', 'targets'
        ] as Set
        if (catalog.schemaVersion != 20) {
            errors.add("unsupported schemaVersion: ${catalog.schemaVersion}")
        }
        if ((catalog.keySet() as Set) != expectedTop) {
            errors.add('catalog top-level keys differ from schema')
        }
        try {
            clientArguments(catalog)
            ['fabric', 'forge', 'neoforge'].each { licensedClientProfile(catalog, it) }
            validateLoggingProfiles(catalog)
            developmentOperators(catalog)
        } catch (IllegalArgumentException error) {
            errors.add(error.message)
        }
        Map mod = catalog.mod instanceof Map ? catalog.mod as Map : [:]
        if ((mod.keySet() as Set) != MOD_KEYS) {
            errors.add('mod identity keys differ from schema')
        }
        ['id', 'name', 'group', 'license'].each {
            if (!(mod[it] instanceof String) || !mod[it]) {
                errors.add("mod.${it} must be non-empty")
            }
        }
        if (mod.license != 'GPL-3.0-only') {
            errors.add('mod.license must be GPL-3.0-only')
        }
        Map descriptions = mod.descriptions instanceof Map ? mod.descriptions as Map : [:]
        if ((descriptions.keySet() as Set) != ['en_us', 'ru_ru'] as Set ||
                descriptions.values().any { !(it instanceof String) || it.isBlank() || it.contains('\u2014') || it.contains('\u2013') }) {
            errors.add('mod.descriptions must define non-empty en_us and ru_ru strings without long dashes')
        }
        Map contact = mod.contact instanceof Map ? mod.contact as Map : [:]
        if ((contact.keySet() as Set) != ['homepage', 'sources', 'issues'] as Set ||
                contact.values().any { !(it instanceof String) || !(it ==~ /https:\/\/[^\s]+/) }) {
            errors.add('mod.contact must define HTTPS homepage, sources and issues URLs')
        }
        Map links = mod.links instanceof Map ? mod.links as Map : [:]
        if ((links.keySet() as Set) != ['youtube', 'telegramBot', 'x'] as Set ||
                links.values().any { !(it instanceof String) || !(it ==~ /https:\/\/[^\s]+/) }) {
            errors.add('mod.links must define HTTPS YouTube, Telegram Bot and X URLs')
        }
        Map platforms = mod.platforms instanceof Map ? mod.platforms as Map : [:]
        Map modrinthPlatform = platforms.modrinth instanceof Map ? platforms.modrinth as Map : [:]
        Map curseForgePlatform = platforms.curseforge instanceof Map ? platforms.curseforge as Map : [:]
        if ((platforms.keySet() as Set) != ['modrinth', 'curseforge'] as Set ||
                (modrinthPlatform.keySet() as Set) != ['projectId', 'slug'] as Set ||
                !(modrinthPlatform.projectId ==~ /[A-Za-z0-9]{8}/) ||
                !(modrinthPlatform.slug ==~ /[a-z0-9][a-z0-9_-]*/) ||
                (curseForgePlatform.keySet() as Set) != ['projectId', 'slug'] as Set ||
                !(curseForgePlatform.projectId instanceof Integer) ||
                (curseForgePlatform.projectId as int) <= 0 ||
                !(curseForgePlatform.slug ==~ /[a-z0-9][a-z0-9_-]*/)) {
            errors.add('mod.platforms must define exact Modrinth and CurseForge project IDs and slugs')
        }
        if (!(mod.authors instanceof List) || !(mod.authors as List) ||
            (mod.authors as List).any { !(it instanceof String) || !it }) {
            errors.add('mod.authors must be a non-empty string array')
        }
        if (!(mod.icon instanceof String) || !mod.icon.endsWith('.png') ||
            mod.icon.contains('\\') || mod.icon.contains('..')) {
            errors.add('mod.icon must be a safe JAR-relative PNG path')
        }
        if (!(mod.iconBlur instanceof Boolean)) {
            errors.add('mod.iconBlur must be a boolean')
        } else if (mod.iconBlur) {
            errors.add('mod.iconBlur must remain false for the crisp 128x128 pixel artwork')
        }
        Map optionalDependencies = catalog.optionalDependencies instanceof Map
                ? catalog.optionalDependencies as Map : [:]
        if ((optionalDependencies.keySet() as Set) != ['sqlite_jdbc', 'yet_another_config_lib_v3'] as Set) {
            errors.add('optionalDependencies must declare sqlite_jdbc and yet_another_config_lib_v3')
        } else {
            Map sqlite = optionalDependencies.sqlite_jdbc instanceof Map
                    ? optionalDependencies.sqlite_jdbc as Map : [:]
            Map sqlitePredicates = sqlite.predicates instanceof Map ? sqlite.predicates as Map : [:]
            if ((sqlite.keySet() as Set) != ['side', 'predicates'] as Set
                    || sqlite.side != 'client'
                    || (sqlitePredicates.keySet() as Set) != LoaderBackend.ids() as Set
                    || !(sqlitePredicates.fabric ==~ />=[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/)
                    || !(sqlitePredicates.forge ==~ /\[[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+,\)/)
                    || !(sqlitePredicates.neoforge ==~ /\[[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+,\)/)) {
                errors.add('sqlite_jdbc must define explicit Fabric, Forge and NeoForge ranges')
            }
            Map yacl = optionalDependencies.yet_another_config_lib_v3 instanceof Map
                    ? optionalDependencies.yet_another_config_lib_v3 as Map : [:]
            Map versions = yacl.versions instanceof Map ? yacl.versions as Map : [:]
            Set targetIds = (catalog.targets as List).collect { it.id.toString() } as Set
            if ((yacl.keySet() as Set) != ['side', 'versions'] as Set
                    || yacl.side != 'client'
                    || (versions.keySet() as Set) != targetIds
                    || versions.values().any { Object version ->
                !(version instanceof String) || !(version ==~ /3\.[0-9]+\.[0-9]+\+[^\s]+/)
            }) {
                errors.add('yet_another_config_lib_v3 must define one exact client version per target')
            }
        }
        validatePublicationDependencies(catalog, errors)
        if (mod.icon instanceof String) {
            File icon = new File(repositoryRoot, "compat/resources/canonical/src/main/resources/${mod.icon}")
            try {
                def image = ImageIO.read(icon)
                if (image == null || image.width != 128 || image.height != 128) {
                    errors.add('mod.icon must resolve to the canonical 128x128 PNG')
                }
            } catch (Exception error) {
                errors.add("cannot read mod.icon: ${error.message}")
            }
        }
        ['en_us', 'ru_ru'].each { String locale ->
            File language = new File(repositoryRoot, "compat/resources/canonical/src/main/resources/assets/nclskins/lang/${locale}.json")
            try {
                Map values = loadJson(language)
                values.findAll { key, value -> value instanceof String && (value.contains('\u2014') || value.contains('\u2013')) }
                        .keySet()
                        .each { String key -> errors.add("${locale}: ${key} must not use long dashes") }
                ['modmenu.descriptionTranslation.nclskins',
                 'fml.menu.mods.info.description.nclskins',
                 'neoforge.screen.mods.info.description.nclskins'].each { String key ->
                    if (values[key] != '@NCLSKINS_DESCRIPTION@') errors.add("${locale}: ${key} must use the catalog description template token")
                }
                Map expectedLinkLabels = locale == 'en_us'
                        ? ['nclskins.modmenu.youtube': 'YouTube',
                           'nclskins.modmenu.telegram_bot': 'Telegram Bot',
                           'nclskins.modmenu.x': 'X']
                        : ['nclskins.modmenu.youtube': 'YouTube',
                           'nclskins.modmenu.telegram_bot': 'Telegram-бот',
                           'nclskins.modmenu.x': 'X']
                expectedLinkLabels.each { String key, String value ->
                    if (values[key] != value) errors.add("${locale}: ${key} must equal ${value}")
                }
            } catch (Exception error) {
                errors.add("cannot read ${locale} translations: ${error.message}")
            }
        }
        ['en_us', 'ru_ru'].each { String locale ->
            File language = new File(repositoryRoot, "compat/resources/mojang-collections/src/main/resources/resourcepacks/mojang_collections/assets/nclskins/lang/${locale}.json")
            try {
                Map values = loadJson(language)
                values.findAll { key, value -> value instanceof String && (value.contains('\u2014') || value.contains('\u2013')) }
                        .keySet()
                        .each { String key -> errors.add("mojang-collections ${locale}: ${key} must not use long dashes") }
            } catch (Exception error) {
                errors.add("cannot read mojang-collections ${locale} translations: ${error.message}")
            }
        }
        Map gson = catalog.gsonCompatibility instanceof Map ? catalog.gsonCompatibility as Map : [:]
        if ((gson.keySet() as Set) != ['minimum', 'maximum'] as Set || gson.values().any { !(it instanceof String) || it.isBlank() }) {
            errors.add('gsonCompatibility must define non-empty minimum and maximum versions')
        }
        Set pluginKeys = [
                'loom', 'modDevGradle', 'forgeGradle', 'librarian',
                'mixinGradle', 'mixinProcessor', 'mixinExtras', 'devLogin'
        ] as Set
        if (!(catalog.plugins instanceof Map) || ((catalog.plugins as Map).keySet() as Set) != pluginKeys ||
            (catalog.plugins as Map).values().any { !(it instanceof String) || !it }) {
            errors.add('plugins must pin every supported build plugin')
        }
        Map mappings = catalog.mappings instanceof Map ? catalog.mappings as Map : [:]
        Map parchment = mappings.parchment instanceof Map ? mappings.parchment as Map : [:]
        Set parchmentMinecraftVersions = ['1.20.1', '1.21.1', '1.21.11'] as Set
        Set targetMinecraftVersions = (catalog.targets as List)
                .collect { (it.minecraft as Map).version.toString() } as Set
        if ((mappings.keySet() as Set) != ['parchment'] as Set ||
                (parchment.keySet() as Set) != parchmentMinecraftVersions ||
                !targetMinecraftVersions.containsAll(parchmentMinecraftVersions) ||
                parchment.values().any { Object raw ->
                    if (!(raw instanceof Map) ||
                            ((raw as Map).keySet() as Set) != ['version', 'artifactVersion'] as Set) {
                        return true
                    }
                    Map declaration = raw as Map
                    if (!(declaration.version instanceof String) ||
                            !(declaration.artifactVersion instanceof String) ||
                            !PARCHMENT_VERSION_PATTERN.matcher(declaration.version as String).matches() ||
                            !PARCHMENT_ARTIFACT_VERSION_PATTERN.matcher(
                                    declaration.artifactVersion as String).matches()) {
                        return true
                    }
                    boolean snapshot = (declaration.version as String).endsWith('-SNAPSHOT')
                    String version = declaration.version as String
                    String artifactVersion = declaration.artifactVersion as String
                    if (snapshot) {
                        String snapshotPrefix = version.substring(
                                0, version.length() - '-SNAPSHOT'.length())
                        return !artifactVersion.startsWith(snapshotPrefix)
                    }
                    artifactVersion != version
                }) {
            errors.add('mappings.parchment must pin exact supported releases or unique dated nightly artifacts')
        }
        Map families = catalog.gradleFamilies instanceof Map ? catalog.gradleFamilies as Map : [:]
        Set<Path> wrapperRoots = [] as Set
        families.each { Object idRaw, Object familyRaw ->
            String id = idRaw.toString()
            if (!(familyRaw instanceof Map) || ((familyRaw as Map).keySet() as Set) != ['version', 'wrapperPath'] as Set) {
                errors.add("${id}: invalid Gradle family")
                return
            }
            Map family = familyRaw as Map
            try {
                String relative = repositoryRelative(repositoryRoot, family.wrapperPath, "${id}.wrapperPath")
                File root = family.wrapperPath == '.' ? repositoryRoot : new File(repositoryRoot, relative)
                Path canonical = root.toPath().toAbsolutePath().normalize()
                if (!wrapperRoots.add(canonical)) {
                    errors.add("${id}: duplicate wrapperPath")
                }
                ['gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties'].each {
                    if (!new File(root, it).isFile()) {
                        errors.add("${id}: incomplete Gradle wrapper")
                    }
                }
                File properties = new File(root, 'gradle/wrapper/gradle-wrapper.properties')
                if (properties.isFile() && !properties.getText('UTF-8').contains("gradle-${family.version}-bin.zip")) {
                    errors.add("${id}: wrapper version differs from catalog")
                }
            } catch (IllegalArgumentException error) {
                errors.add(error.message)
            }
        }
        Map profiles = catalog.profiles instanceof Map ? catalog.profiles as Map : [:]
        Set<String> profileGroups = ['epochs', 'loaders', 'integrations', 'builds'] as Set
        if ((profiles.keySet() as Set) != profileGroups ||
                profiles.values().any { !(it instanceof Map) || (it as Map).isEmpty() }) {
            errors.add('profiles must define non-empty epochs, loaders, integrations and builds')
        }
        Map bundles = catalog.sourceBundles instanceof Map ? catalog.sourceBundles as Map : [:]
        bundles.each { Object idRaw, Object bundleRaw ->
            String id = idRaw.toString()
            if (!(bundleRaw instanceof Map) || ((bundleRaw as Map).keySet() as Set) != ['java', 'resources', 'side', 'requires'] as Set) {
                errors.add("${id}: invalid source bundle keys")
                return
            }
            Map bundle = bundleRaw as Map
            if (!(bundle.side in ['common', 'client'])) {
                errors.add("${id}: invalid source bundle side")
            }
            ['java', 'resources', 'requires'].each { String field ->
                if (!(bundle[field] instanceof List) || (bundle[field] as List).size() != (bundle[field] as List).toSet().size()) {
                    errors.add("${id}.${field} must be a unique array")
                }
            }
            ['java', 'resources'].each { String field ->
                if (bundle[field] instanceof List) {
                    (bundle[field] as List).eachWithIndex { Object raw, int index ->
                        try {
                            String path = repositoryRelative(repositoryRoot, raw, "${id}.${field}[${index}]")
                            if (!new File(repositoryRoot, path).isDirectory()) {
                                errors.add("${id}: missing ${field} directory ${path}")
                            }
                        } catch (IllegalArgumentException error) {
                            errors.add(error.message)
                        }
                    }
                }
            }
        }
        try {
            resolveBundleOrder(catalog, bundles.keySet().collect { it.toString() })
        } catch (IllegalArgumentException error) {
            errors.add(error.message)
        }
        if (!(catalog.baseBundles instanceof List) || !(catalog.baseBundles as List) || (catalog.baseBundles as List).size() != (catalog.baseBundles as List).toSet().size() || (catalog.baseBundles as List).any { !bundles.containsKey(it) }) {
            errors.add('baseBundles must be a unique non-empty array of declared bundles')
        }
        List targets = catalog.targets instanceof List ? catalog.targets as List : []
        if (!targets) {
            errors.add('targets must be a non-empty array')
        }
        Set selectedImplementations = [] as Set
        targets.findAll { it instanceof Map }.each { selectedImplementations.addAll(((it as Map).capabilities ?: [:]).values()) }
        Map declarations = catalog.capabilityImplementations instanceof Map ? catalog.capabilityImplementations as Map : [:]
        if ((declarations.keySet() as Set) != selectedImplementations) {
            errors.add('capabilityImplementations must exactly cover selected capability IDs')
        }
        declarations.each { Object id, Object ignored ->
            try {
                Map declaration = capabilityDeclaration(catalog, id.toString())
                if (!bundles.containsKey(declaration.bundle)) {
                    errors.add("${id}: missing source bundle '${declaration.bundle}'")
                }
            } catch (IllegalArgumentException error) {
                errors.add(error.message)
            }
        }
        List ids = []
        List paths = []
        List ports = []
        List artifacts = []
        List coordinates = []
        List modules = []
        targets.each { Object raw ->
            if (!(raw instanceof Map)) {
                errors.add('target must be an object')
                return
            }
            Map target = raw as Map
            Set targetKeys = target.keySet() as Set
            if (!targetKeys.containsAll(REQUIRED_TARGET_KEYS) || !TARGET_KEYS.containsAll(targetKeys)) {
                errors.add("${target.id}: target keys differ from schema")
            }
            String loader = target.loader instanceof Map ? target.loader.id?.toString() : null
            String minecraft = target.minecraft instanceof Map ? target.minecraft.version?.toString() : null
            String expectedId = loader && minecraft ? "${loader}-${minecraft}" : null
            String expectedPath = loader && minecraft ? "targets/${minecraft}/${loader}" : null
            if (target.id != expectedId || target.path != expectedPath) {
                errors.add("${target.id}: target identity/path differs from loader and Minecraft version")
            }
            Map minecraftDeclaration = target.minecraft instanceof Map ? target.minecraft as Map : [:]
            Set minecraftKeys = minecraftDeclaration.keySet() as Set
            if (!(minecraftKeys in [
                    ['version', 'predicate', 'epoch'] as Set,
                    ['version', 'compileVersion', 'runtimeVersion', 'minimumRuntimeVersion', 'predicate', 'epoch'] as Set
            ]) || minecraftDeclaration.values().any { !(it instanceof String) || it.isBlank() }) {
                errors.add("${target.id}: invalid Minecraft declaration")
            }
            if (!(target.releaseEligible instanceof Boolean)) {
                errors.add("${target.id}: releaseEligible must be a boolean")
            }
            String compileVersion = minecraftDeclaration.compileVersion?.toString()
            if (compileVersion != null) {
                String runtimeVersion = minecraftDeclaration.runtimeVersion?.toString()
                String minimumRuntimeVersion = minecraftDeclaration.minimumRuntimeVersion?.toString()
                def compileMatch = compileVersion =~ /${java.util.regex.Pattern.quote(minecraft)}-snapshot-([0-9]+)/
                def runtimeMatch = runtimeVersion =~ /${java.util.regex.Pattern.quote(minecraft)}-alpha\.([0-9]+)/
                def minimumRuntimeMatch = minimumRuntimeVersion =~ /${java.util.regex.Pattern.quote(minecraft)}-alpha\.([0-9]+)/
                if (compileVersion == minecraft ||
                        !compileMatch.matches() ||
                        !runtimeMatch.matches() ||
                        !minimumRuntimeMatch.matches() ||
                        minecraftDeclaration.predicate != ">=${minimumRuntimeVersion}" ||
                        (compileMatch.matches() && runtimeMatch.matches()
                                && compileMatch.group(1) != runtimeMatch.group(1)) ||
                        (runtimeMatch.matches() && minimumRuntimeMatch.matches()
                                && Integer.parseInt(minimumRuntimeMatch.group(1))
                                > Integer.parseInt(runtimeMatch.group(1)))) {
                    errors.add("${target.id}: snapshot compileVersion must map to its runtimeVersion and use an explicit lower-only minimumRuntimeVersion predicate")
                }
                if (target.releaseEligible != false) {
                    errors.add("${target.id}: snapshot compileVersion target must not be release eligible")
                }
            }
            Map loaderDeclaration = target.loader instanceof Map ? target.loader as Map : [:]
            if ((loaderDeclaration.keySet() as Set) != ['id', 'version', 'predicate', 'apiVersion', 'apiPredicate', 'modMenuVersion'] as Set || !(loader in LoaderBackend.ids()) || !(loaderDeclaration.version instanceof String) || loaderDeclaration.version.isBlank() || !(loaderDeclaration.predicate instanceof String) || loaderDeclaration.predicate.isBlank()) {
                errors.add("${target.id}: invalid loader declaration")
            }
            if (loader == 'fabric') {
                if (!(loaderDeclaration.version ==~ /[0-9]+\.[0-9]+\.[0-9]+/) || loaderDeclaration.predicate != ">=${loaderDeclaration.version}") errors.add("${target.id}: Fabric Loader must use a semantic version and an identical lower-only predicate")
                if (!(loaderDeclaration.apiVersion instanceof String) || loaderDeclaration.apiVersion.isBlank() || loaderDeclaration.apiPredicate != ">=${loaderDeclaration.apiVersion}") errors.add("${target.id}: Fabric API version and lower-only predicate must be explicit and identical")
                if (!(loaderDeclaration.modMenuVersion instanceof String) || !(loaderDeclaration.modMenuVersion ==~ /[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?/)) errors.add("${target.id}: Fabric Mod Menu version must be explicit")
            } else {
                if (loaderDeclaration.apiVersion != null || loaderDeclaration.apiPredicate != null || loaderDeclaration.modMenuVersion != null) errors.add("${target.id}: non-Fabric target must not declare Fabric API or Mod Menu")
                if (loaderDeclaration.predicate != "[${loaderDeclaration.version},)") errors.add("${target.id}: loader predicate must contain only the build-version lower bound")
            }
            if (compileVersion == null && loader in LoaderBackend.ids() &&
                    minecraftDeclaration.predicate != LoaderBackend.require(loader)
                    .minecraftPredicate(minecraftDeclaration.version.toString())) {
                errors.add("${target.id}: Minecraft predicate must contain only the target-version lower bound")
            }
            validateTargetProfiles(target, profiles, loader, minecraftDeclaration, errors)
            validateCompatibility(target, loader, minecraft, loaderDeclaration, errors)
            if (!(target.capabilities instanceof Map) || ((target.capabilities as Map).keySet() as Set) != REQUIRED_CAPABILITIES) {
                errors.add("${target.id}: capability map differs from schema")
            } else {
                (target.capabilities as Map).each { Object key, Object implementation ->
                    try {
                        capabilityDeclaration(catalog, implementation.toString())
                    } catch (IllegalArgumentException error) {
                        errors.add("${target.id}: ${error.message}")
                    }
                }
            }
            Map java = target.java instanceof Map ? target.java as Map : [:]
            if ((java.keySet() as Set) != ['release', 'classfileMajor', 'buildJdk'] as Set ||
                !(java.release instanceof Number) || !(java.classfileMajor instanceof Number) ||
                java.classfileMajor as int != (java.release as int) + 44) {
                errors.add("${target.id}: invalid Java declaration")
            }
            Map development = target.development instanceof Map ? target.development as Map : [:]
            if ((development.keySet() as Set) != ['serverPort'] as Set ||
                !(development.serverPort instanceof Number) ||
                (development.serverPort as int) < 1 || (development.serverPort as int) > 65535) {
                errors.add("${target.id}: invalid development server port")
            }
            if (!families.containsKey(target.gradleFamily)) {
                errors.add("${target.id}: unknown Gradle family")
            }
            if (!(target.sourceLayout in ['single', 'fabricSplit']) ||
                (target.sourceLayout == 'fabricSplit' && loader != 'fabric')) {
                errors.add("${target.id}: invalid source layout")
            }
            Map metadata = target.metadata instanceof Map ? target.metadata as Map : [:]
            Set<String> expectedMetadataKeys = loader in LoaderBackend.ids()
                    ? LoaderBackend.require(loader).metadataKeys()
                    : [] as Set
            if ((metadata.keySet() as Set) != expectedMetadataKeys) {
                errors.add("${target.id}: metadata keys differ from loader schema")
            }
            if (loader != 'fabric') {
                String expectedBranding = loader == 'neoforge' && minecraftDeclaration.epoch == '26.2'
                        ? 'icon-only' : 'legacy-logo'
                if (metadata.modListBranding != expectedBranding) {
                    errors.add("${target.id}: modListBranding must be ${expectedBranding}")
                }
            }
            if (loader != 'fabric' && !(metadata.loaderVersion ==~ /\[[1-9][0-9]*,\)/)) {
                errors.add("${target.id}: language loader range must contain only a major lower bound")
            }
            Map artifact = target.artifact instanceof Map ? target.artifact as Map : [:]
            if ((artifact.keySet() as Set) != ['file', 'remapJar', 'mavenArtifactId', 'automaticModuleName'] as Set) {
                errors.add("${target.id}: invalid artifact declaration")
            }
            if (!(artifact.file instanceof String) || !artifact.file.endsWith('.jar') || !artifact.file.contains('{modVersion}') || !(artifact.remapJar instanceof Boolean) || !(artifact.mavenArtifactId instanceof String) || artifact.mavenArtifactId.isBlank() || !(artifact.automaticModuleName instanceof String) || !(artifact.automaticModuleName ==~ /[A-Za-z][A-Za-z0-9_.]*/)) {
                errors.add("${target.id}: invalid artifact values")
            }
            File targetDir = target.path ? new File(repositoryRoot, target.path.toString()) : null
            if (targetDir == null || !targetDir.isDirectory()) {
                errors.add("${target.id}: target path does not exist")
            }
            try {
                Map resolvedSources = resolveTargetSources(repositoryRoot, catalog, target)
                Set<String> selectedMixinResources = [] as Set
                (resolvedSources.resources as List).each { String root ->
                    Path resourceRoot = new File(repositoryRoot, root).toPath()
                    Files.walk(resourceRoot).withCloseable { stream ->
                        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith('.mixins.json') }
                                .forEach { Path resource ->
                                    selectedMixinResources.add(resourceRoot.relativize(resource)
                                            .toString()
                                            .replace(File.separatorChar, '/' as char))
                                }
                    }
                }
                Set<String> declaredMixinResources = ((metadata.serverMixins ?: [])
                        + (metadata.mixins ?: []))
                        .collect { it.toString() } as Set
                if (selectedMixinResources != declaredMixinResources) {
                    errors.add("${target.id}: selected Mixin resources "
                            + "${selectedMixinResources.sort()} differ from metadata "
                            + "${declaredMixinResources.sort()}")
                }
                Map<String, List<String>> classes = [:].withDefault { [] }
                (resolvedSources.java as List).each { String root ->
                    Path sourceRoot = new File(repositoryRoot, root).toPath()
                    Files.walk(sourceRoot).withCloseable { stream ->
                        stream.filter { Files.isRegularFile(it) && it.toString().endsWith('.java') }.forEach { Path source ->
                            String classPath = sourceRoot.relativize(source).toString().replace(File.separatorChar, '/' as char)
                            classes[classPath].add(root)
                        }
                    }
                }
                classes.findAll { String name, List<String> owners -> owners.size() > 1 }.each { String name, List<String> owners -> errors.add("${target.id}: duplicate class source ${name} in ${owners}") }
                MetadataRenderer.render(catalog, target, loadVersion(repositoryRoot))
            } catch (IllegalArgumentException error) {
                errors.add("${target.id}: ${error.message}")
            } catch (Exception error) {
                errors.add("${target.id}: source or metadata validation failed: ${error.message}")
            }
            ids.add(target.id)
            paths.add(target.path)
            ports.addAll(targetRuntimeSpecs(target).collect { it.serverPort })
            artifacts.add(artifact.file)
            coordinates.add(artifact.mavenArtifactId)
            modules.add(artifact.automaticModuleName)
        }
        Set<String> fabricLoaderVersions = targets.findAll { it instanceof Map && it.loader?.id == 'fabric' }.collect { it.loader.version.toString() } as Set
        if (fabricLoaderVersions.size() != 1) {
            errors.add("Fabric targets must share one dependency-tree-compatible loader floor: ${fabricLoaderVersions.sort()}")
        }
        [ids, paths, ports, artifacts, coordinates, modules].each { List values ->
            if (values.contains(null) || values.size() != values.toSet().size()) {
                errors.add("target uniqueness constraint failed: ${values}")
            }
        }
        validateServerPlugin(repositoryRoot, catalog, errors)
        validateAbi(repositoryRoot, catalog, errors)
        if (errors) {
            throw new IllegalArgumentException(errors.collect { "- ${it}" }.join('\n'))
        }
    }

    private static void validateServerPlugin(File repositoryRoot, Map catalog, List<String> errors) {
        Map plugin = catalog.serverPlugin instanceof Map ? catalog.serverPlugin as Map : [:]
        Set<String> pluginKeys = [
                'name', 'slug', 'artifact', 'sourcesArtifact', 'matrixId', 'javaRelease', 'packaging',
                'protocols', 'compatibility', 'excluded', 'productionInputs', 'platforms'
        ] as Set
        if ((plugin.keySet() as Set) != pluginKeys) {
            errors.add('serverPlugin keys differ from schema')
            return
        }
        if (plugin.name != 'NCL Skins Plugin' || plugin.slug != 'nclskins-plugin' ||
                plugin.artifact != 'nclskins-plugin-{pluginVersion}.jar' ||
                plugin.sourcesArtifact != 'nclskins-plugin-{pluginVersion}-sources.jar' ||
                plugin.matrixId != 'official-v1' || plugin.javaRelease != 17) {
            errors.add('serverPlugin identity, artifacts, matrix, and Java release must be exact')
        }
        if (plugin.protocols != ['appearance-refresh-v1', 'proxy-refresh-v1']) {
            errors.add('serverPlugin protocols must retain the ordered v1 compatibility set')
        }
        if (plugin.packaging != [
                buildJdk      : 25,
                gson          : '2.10',
                shadow        : '9.6.1',
                paperApi      : '1.20.1-R0.1-20230921.165944-178',
                velocityApi   : '4.0.0',
                bungeeApi     : '1.21-R0.4',
                gsonRelocation: 'com.naocraftlab.skins.server.lib.gson']) {
            errors.add('serverPlugin packaging dependencies and relocation must be exact')
        }
        Map expectedCompatibility = [
                '1.20.1' : ['craftbukkit', 'spigot', 'paper', 'purpur', 'folia'],
                '1.21.1' : ['paper', 'purpur'],
                '1.21.11': ['paper', 'purpur', 'folia'],
                '26.1.1' : ['paper'],
                '26.1.2' : ['paper', 'purpur', 'folia'],
                '26.2'   : ['paper', 'purpur', 'folia']
        ]
        if (plugin.compatibility != expectedCompatibility ||
                plugin.excluded != ['waterfall', 'sponge', 'geyser', '26.3']) {
            errors.add('serverPlugin compatibility and exclusions differ from the approved matrix')
        }
        Map inputs = plugin.productionInputs instanceof Map ? plugin.productionInputs as Map : [:]
        if ((inputs.keySet() as Set) !=
                ['mod', 'server', 'shared', 'release-only', 'test-run'] as Set ||
                inputs.any { Object ignored, Object value ->
                    !(value instanceof List) || (value as List).isEmpty() ||
                            (value as List).size() != (value as List).toSet().size() ||
                            (value as List).any { !(it instanceof String) || !it }
                }) {
            errors.add('serverPlugin productionInputs must classify unique paths by ownership')
        } else {
            List<String> allInputs = inputs.values().flatten().collect { it.toString() }
            if (allInputs.size() != allInputs.toSet().size()) {
                errors.add('serverPlugin productionInputs cannot assign one path to multiple owners')
            }
            ['server', 'shared'].each { String owner ->
                (inputs[owner] as List).each { Object raw ->
                    if (!new File(repositoryRoot, raw.toString()).exists()) {
                        errors.add("serverPlugin ${owner} production input does not exist: ${raw}")
                    }
                }
            }
        }
        Map platforms = plugin.platforms instanceof Map ? plugin.platforms as Map : [:]
        Map modrinth = platforms.modrinth instanceof Map ? platforms.modrinth as Map : [:]
        Map curseforge = platforms.curseforge instanceof Map ? platforms.curseforge as Map : [:]
        boolean validModrinthId = modrinth.projectId == null ||
                modrinth.projectId ==~ /[A-Za-z0-9]{8}/
        boolean validCurseForgeId = curseforge.projectId == null ||
                (curseforge.projectId instanceof Integer && curseforge.projectId as int > 0)
        if ((platforms.keySet() as Set) != ['modrinth', 'curseforge'] as Set ||
                (modrinth.keySet() as Set) != ['projectId', 'slug'] as Set ||
                (curseforge.keySet() as Set) != ['projectId', 'slug'] as Set ||
                !validModrinthId || !validCurseForgeId ||
                modrinth.slug != 'nclskins-plugin' || curseforge.slug != 'nclskins-plugin') {
            errors.add('serverPlugin platforms must reserve nclskins-plugin with null or exact project IDs')
        }
        if ((modrinth.projectId != null &&
                modrinth.projectId == catalog.mod.platforms.modrinth.projectId) ||
                (curseforge.projectId != null &&
                        curseforge.projectId == catalog.mod.platforms.curseforge.projectId)) {
            errors.add('serverPlugin platform project IDs must differ from the mod projects')
        }

        List rawRuntimes = catalog.serverPluginRuntimes instanceof List
                ? catalog.serverPluginRuntimes as List : []
        List<Map> runtimes = rawRuntimes.findAll { it instanceof Map } as List<Map>
        Set<String> runtimeKeys = [
                'id', 'platform', 'version', 'build', 'channel', 'url', 'sha256', 'javaRelease'
        ] as Set
        Set runtimeIds = [] as Set
        if (runtimes.size() != 20 || runtimes.size() != rawRuntimes.size()) {
            errors.add('serverPluginRuntimes must define exactly 20 immutable artifacts')
        }
        runtimes.each { Map runtime ->
            if ((runtime.keySet() as Set) != runtimeKeys ||
                    !(runtime.id ==~ /[a-z0-9][a-z0-9.-]*/) ||
                    !runtimeIds.add(runtime.id) ||
                    !(runtime.platform in [
                            'paper', 'purpur', 'folia', 'velocity', 'bungeecord',
                            'bungeeguard', 'protocollib', 'buildtools']) ||
                    !(runtime.version instanceof String) || !runtime.version ||
                    !(runtime.build instanceof String) || !runtime.build ||
                    !(runtime.channel in ['STABLE', 'ALPHA', 'BETA']) ||
                    !(runtime.url ==~ /https:\/\/[^\s]+/) ||
                    !(runtime.sha256 ==~ /[0-9a-f]{64}/) ||
                    !(runtime.javaRelease in [17, 21, 25])) {
                errors.add("${runtime.id}: invalid immutable server runtime declaration")
            }
            if (runtime.platform in ['paper', 'folia'] &&
                    !runtime.url.toString().contains("/objects/${runtime.sha256}/")) {
                errors.add("${runtime.id}: Fill artifact URL must be content addressed")
            }
        }
        Set expectedBackendRuntimeIds = [] as Set
        expectedCompatibility.each { String minecraft, List<String> kernels ->
            kernels.findAll { it in ['paper', 'purpur', 'folia'] }.each { String kernel ->
                expectedBackendRuntimeIds.add("${kernel}-${minecraft}".toString())
            }
        }
        if (!runtimeIds.containsAll(expectedBackendRuntimeIds + [
                'velocity-4.0.0-6', 'bungeecord-2086', 'bungeeguard-1.4.0',
                'protocollib-5.4.0', 'buildtools-200'] as Set) ||
                runtimes.find { it.id == 'paper-26.1.1' }?.channel != 'ALPHA' ||
                runtimes.find { it.id == 'folia-1.20.1' }?.channel != 'ALPHA' ||
                runtimes.find { it.id == 'folia-26.2' }?.channel != 'BETA') {
            errors.add('serverPluginRuntimes do not cover exact pins or Folia upstream channels')
        }

        List rawTopologies = catalog.serverPluginTopologies instanceof List
                ? catalog.serverPluginTopologies as List : []
        List<Map> topologies = rawTopologies.findAll { it instanceof Map } as List<Map>
        Set<String> topologyKeys = ['id', 'minecraft', 'kernel', 'mode', 'ports', 'dependencies'] as Set
        Map<String, Integer> counts = [standalone: 0, velocity: 0, bungeecord: 0]
        Set topologyIds = [] as Set
        List<Integer> ports = []
        topologies.each { Map topology ->
            String mode = topology.mode?.toString()
            String minecraft = topology.minecraft?.toString()
            String kernel = topology.kernel?.toString()
            Map topologyPorts = topology.ports instanceof Map ? topology.ports as Map : [:]
            List dependencies = topology.dependencies instanceof List ? topology.dependencies as List : []
            if ((topology.keySet() as Set) != topologyKeys || !(mode in counts.keySet()) ||
                    topology.id != "${minecraft}-${kernel}-${mode}" ||
                    !topologyIds.add(topology.id) || !expectedCompatibility.containsKey(minecraft) ||
                    !(kernel in expectedCompatibility[minecraft]) ||
                    dependencies.size() != dependencies.toSet().size()) {
                errors.add("${topology.id}: invalid server plugin topology identity")
                return
            }
            counts[mode] = counts[mode] + 1
            Set expectedPortKeys = mode == 'standalone'
                    ? ['server'] as Set : ['proxy', 'lobby', 'target'] as Set
            if ((topologyPorts.keySet() as Set) != expectedPortKeys ||
                    topologyPorts.values().any { !(it instanceof Integer) }) {
                errors.add("${topology.id}: topology ports differ from mode schema")
            } else {
                ports.addAll(topologyPorts.values().collect { it as int })
            }
            if (mode == 'standalone' && dependencies != []) {
                errors.add("${topology.id}: standalone topology must not install proxy dependencies")
            }
            if (mode == 'velocity' &&
                    (kernel != 'paper' || dependencies != ['velocity'])) {
                errors.add("${topology.id}: Velocity representative topology requires Paper")
            }
            if (mode == 'bungeecord') {
                List expectedDependencies = minecraft == '1.20.1' && kernel == 'spigot'
                        ? ['bungeecord', 'bungeeguard', 'protocollib']
                        : ['bungeecord', 'bungeeguard']
                boolean representative = kernel == 'paper' ||
                        (minecraft == '1.20.1' && kernel == 'spigot')
                if (!representative || dependencies != expectedDependencies) {
                    errors.add("${topology.id}: invalid BungeeCord dependency set")
                }
            }
            if (kernel == 'craftbukkit' && mode != 'standalone') {
                errors.add("${topology.id}: CraftBukkit is standalone-only")
            }
            if (kernel == 'spigot' && !(minecraft == '1.20.1' && mode != 'velocity')) {
                errors.add("${topology.id}: Spigot is supported only on 1.20.1 outside Velocity")
            }
        }
        if (topologies.size() != rawTopologies.size() || topologies.size() != 30 ||
                counts != [standalone: 17, velocity: 6, bungeecord: 7]) {
            errors.add('serverPluginTopologies must contain 17 standalone, 6 Velocity, and 7 BungeeCord runs')
        }
        Set<Integer> modPorts = (catalog.targets as List).collectMany { Map target ->
            targetRuntimeSpecs(target).collect { (it.serverPort as Number).intValue() }
        } as Set<Integer>
        List<Integer> expectedPorts = (26000..26052).toList() + [26153, 26054, 26055]
        if (ports != expectedPorts || ports.toSet().size() != 56 ||
                !ports.toSet().intersect(modPorts).isEmpty()) {
            errors.add('server plugin ports must match the exact topology allocation, be unique, and be disjoint from mod runs')
        }
    }

    static void validateTargetProfiles(
            Map target,
            Map profiles,
            String loader,
            Map minecraft,
            List<String> errors) {
        Map groups = [
                epochProfile      : profiles.epochs,
                loaderProfile     : profiles.loaders,
                integrationProfile: profiles.integrations,
                buildProfile      : profiles.builds
        ]
        Map selected = [:]
        groups.each { String targetKey, Object rawGroup ->
            Object id = target[targetKey]
            Object entry = rawGroup instanceof Map && id instanceof String
                    ? (rawGroup as Map)[id]
                    : null
            if (!(id instanceof String) || id.isBlank() || !(entry instanceof Map)) {
                errors.add("${target.id}: unknown ${targetKey} '${id}'")
            } else {
                selected[targetKey] = entry as Map
            }
        }

        Map epoch = selected.epochProfile instanceof Map ? selected.epochProfile as Map : [:]
        Set<String> epochCapabilities = REQUIRED_CAPABILITIES -
                ['loaderScreen', 'serverLoader', 'preview', 'updateNotification',
                 'serverSignal', 'serverSignalReceiver'] as Set
        if ((epoch.keySet() as Set) != ['minecraftEpoch', 'javaRelease', 'clientProviderClass',
                'clientProviderBundle', 'accessBundles', 'previewCapabilities',
                'serverSignalCapabilities', 'serverSignalReceiverCapabilities',
                'capabilities'] as Set ||
                !(epoch.clientProviderClass instanceof String) ||
                !(epoch.clientProviderClass ==~ /[a-zA-Z_$][a-zA-Z0-9_$]*(\.[a-zA-Z_$][a-zA-Z0-9_$]*)+/) ||
                !(epoch.clientProviderBundle instanceof String) ||
                !(epoch.accessBundles instanceof Map) ||
                !(epoch.previewCapabilities instanceof Map) ||
                !(epoch.serverSignalCapabilities instanceof Map) ||
                !(epoch.serverSignalReceiverCapabilities instanceof Map) ||
                !(epoch.capabilities instanceof Map) ||
                ((epoch.capabilities as Map).keySet() as Set) != epochCapabilities) {
            errors.add("${target.id}: invalid epoch profile shape")
        } else {
            if (epoch.minecraftEpoch != minecraft.epoch || epoch.javaRelease != target.java?.release) {
                errors.add("${target.id}: epoch profile differs from Minecraft epoch or Java release")
            }
            (epoch.capabilities as Map).each { Object key, Object implementation ->
                if ((target.capabilities as Map)[key] != implementation) {
                    errors.add("${target.id}: ${key} differs from epoch profile")
                }
            }
            Object accessBundle = (epoch.accessBundles as Map)[loader]
            if (!(accessBundle instanceof String) || accessBundle.isBlank()) {
                errors.add("${target.id}: epoch profile has no access bundle for loader")
            }
            Object previewImplementation = (epoch.previewCapabilities as Map)[loader]
            if (!(previewImplementation instanceof String) || previewImplementation.isBlank()) {
                errors.add("${target.id}: epoch profile has no preview capability for loader")
            } else if ((target.capabilities as Map).preview != previewImplementation) {
                errors.add("${target.id}: preview differs from epoch/loader profile")
            }
            [serverSignal: 'serverSignalCapabilities',
             serverSignalReceiver: 'serverSignalReceiverCapabilities'].each {
                String capability, String profileKey ->
                Object implementation = (epoch[profileKey] as Map)[loader]
                if (!(implementation instanceof String) || implementation.isBlank()) {
                    errors.add("${target.id}: epoch profile has no ${capability} capability for loader")
                } else if ((target.capabilities as Map)[capability] != implementation) {
                    errors.add("${target.id}: ${capability} differs from epoch/loader profile")
                }
            }
        }

        Map loaderProfile = selected.loaderProfile instanceof Map ? selected.loaderProfile as Map : [:]
        if ((loaderProfile.keySet() as Set) != ['loader', 'serverLoader'] as Set ||
                loaderProfile.loader != loader ||
                loaderProfile.serverLoader != (target.capabilities as Map).serverLoader) {
            errors.add("${target.id}: loader profile differs from target loader/server capability")
        }

        Map integration = selected.integrationProfile instanceof Map ? selected.integrationProfile as Map : [:]
        if ((integration.keySet() as Set) != ['loader', 'loaderScreen'] as Set ||
                integration.loader != loader ||
                integration.loaderScreen != (target.capabilities as Map).loaderScreen) {
            errors.add("${target.id}: integration profile differs from loader-screen capability")
        }

        Map build = selected.buildProfile instanceof Map ? selected.buildProfile as Map : [:]
        if ((build.keySet() as Set) != ['loader', 'gradleFamily', 'sourceLayout'] as Set ||
                build.loader != loader ||
                build.gradleFamily != target.gradleFamily ||
                build.sourceLayout != target.sourceLayout) {
            errors.add("${target.id}: build profile differs from target build declaration")
        }
    }

    static void validateCompatibility(
            Map target,
            String loader,
            String minecraft,
            Map loaderDeclaration,
            List<String> errors) {
        if (!target.containsKey('compatibility')) {
            return
        }
        Object raw = target.compatibility
        if (!(raw instanceof Map)) {
            errors.add("${target.id}: compatibility must be an object")
            return
        }
        Map compatibility = raw as Map
        if ((compatibility.keySet() as Set) !=
                ['minecraftVersions', 'loaderVersions', 'serverPorts'] as Set ||
                !(compatibility.minecraftVersions instanceof List) ||
                !(compatibility.loaderVersions instanceof Map) ||
                !(compatibility.serverPorts instanceof Map)) {
            errors.add("${target.id}: invalid compatibility declaration")
            return
        }
        List versions = compatibility.minecraftVersions as List
        Map loaderVersions = compatibility.loaderVersions as Map
        Map serverPorts = compatibility.serverPorts as Map
        if (!versions || versions.any { !(it instanceof String) || it.isBlank() } ||
                versions.size() != versions.toSet().size()) {
            errors.add("${target.id}: compatibility Minecraft versions must be unique non-empty strings")
        }
        if (versions && versions.first() != minecraft) {
            errors.add("${target.id}: compatibility must start with the compile-baseline Minecraft version")
        }
        if ((loaderVersions.keySet() as List) != versions ||
                loaderVersions.values().any { !(it instanceof String) || it.isBlank() }) {
            errors.add("${target.id}: compatibility loader versions must exactly follow Minecraft versions")
        }
        if ((serverPorts.keySet() as List) != versions ||
                serverPorts.values().any { !(it instanceof Number) ||
                    (it as int) < 1 || (it as int) > 65535 } ||
                serverPorts.values().size() != serverPorts.values().toSet().size()) {
            errors.add("${target.id}: compatibility server ports must exactly follow Minecraft versions")
        }
        if (loaderVersions[minecraft] != loaderDeclaration.version) {
            errors.add("${target.id}: compatibility baseline loader must equal the build dependency")
        }
        if (serverPorts[minecraft] != target.development.serverPort) {
            errors.add("${target.id}: compatibility baseline port must equal the development port")
        }
        if (!(loader in ['fabric', 'neoforge'])) {
            errors.add("${target.id}: compatibility is supported only for Fabric and NeoForge")
        }
    }

    static Map compatibilityRuntime(Map target, String minecraftVersion) {
        if (!(target.compatibility instanceof Map)) {
            throw new IllegalArgumentException("${target.id}: target has no runtime compatibility matrix")
        }
        Map compatibility = target.compatibility as Map
        if (!(minecraftVersion in (compatibility.minecraftVersions as List))) {
            throw new IllegalArgumentException(
                    "${target.id}: unsupported compatibility Minecraft version ${minecraftVersion}")
        }
        [minecraftVersion: minecraftVersion,
         loaderVersion: compatibility.loaderVersions[minecraftVersion],
         serverPort: compatibility.serverPorts[minecraftVersion]]
    }

    static List<Map> targetRuntimeSpecs(Map target) {
        if (!(target.compatibility instanceof Map)) {
            return [[target: target,
                     minecraftVersion: target.minecraft.version.toString(),
                     loaderVersion: target.loader.version.toString(),
                     serverPort: (target.development.serverPort as Number).intValue(),
                     baseline: true]]
        }
        String baseline = target.minecraft.version.toString()
        (target.compatibility.minecraftVersions as List).collect { Object rawVersion ->
            String version = rawVersion.toString()
            Map runtime = compatibilityRuntime(target, version)
            [target: target,
             minecraftVersion: version,
             loaderVersion: runtime.loaderVersion.toString(),
             serverPort: (runtime.serverPort as Number).intValue(),
             baseline: version == baseline]
        }
    }

    static void validateAbi(File repositoryRoot, Map catalog, List<String> errors) {
        Map abi
        try {
            abi = loadJson(new File(repositoryRoot, 'gradle/abi-fingerprints.json'))
        } catch (Exception error) {
            errors.add("cannot read ABI fingerprints: ${error.message}")
            return
        }
        if (abi.schemaVersion != 5 || !(abi.implementations instanceof Map)) {
            errors.add('ABI fingerprint schemaVersion must be 5')
            return
        }
        Map<String, String> selected = [:]
        Map<String, Set<String>> epochs = [:].withDefault { [] as Set }
        (catalog.targets as List).each { Object raw ->
            Map target = raw as Map
            (target.capabilities as Map).each { Object kindRaw, Object implementationRaw ->
                if (EXTERNAL_ABI_CAPABILITIES.contains(kindRaw.toString())) return
                Map declaration = capabilityDeclaration(catalog, implementationRaw.toString())
                String implementation = declaration.abiImplementation.toString()
                String kind = kindRaw.toString()
                String previous = selected.putIfAbsent(implementation, kind)
                if (previous != null && previous != kind) {
                    errors.add("${implementation}: selected for conflicting ABI kinds")
                }
                epochs[implementation].add((target.minecraft as Map).epoch.toString())
            }
        }
        Map implementations = abi.implementations as Map
        if ((implementations.keySet() as Set) != (selected.keySet() as Set)) {
            errors.add('ABI baselines must exactly cover selected ABI implementations')
        }
        implementations.each { Object idRaw, Object entryRaw ->
            String id = idRaw.toString()
            if (!(entryRaw instanceof Map)) {
                errors.add("${id}: invalid ABI entry")
                return
            }
            Map entry = entryRaw as Map
            if (entry.kind != selected[id]) {
                errors.add("${id}: ABI kind differs from catalog")
            }
            if (!(entry.epochs instanceof List) || (entry.epochs as Set) != epochs[id]) {
                errors.add("${id}: ABI epochs differ from selecting targets")
            }
            if (!(entry.classes instanceof List) || !(entry.classes as List)) {
                errors.add("${id}: ABI classes must be non-empty")
            }
            if (!validAbiDeclarationHash(entry.baselineSha256)) {
                errors.add("${id}: invalid ABI declaration hash")
            }
        }
        Set<String> catalogProfiles = (catalog.targets as List).collect { it.epochProfile.toString() } as Set
        Map resolved = abi.resolvedByProfile instanceof Map ? abi.resolvedByProfile as Map : [:]
        if ((resolved.keySet() as Set) != catalogProfiles) {
            errors.add('resolved ABI baselines must exactly cover selected API profiles')
        }
        resolved.each { Object profile, Object rawProfiles ->
            if (!(rawProfiles instanceof Map)) {
                errors.add("${profile}: resolved ABI baseline must be an object")
                return
            }
            Set<String> profileEpochs = (catalog.targets as List)
                    .findAll { it.epochProfile.toString() == profile.toString() }
                    .collect { it.minecraft.epoch.toString() } as Set
            Set<String> required = epochs.findAll { String id, Set values -> !(values.intersect(profileEpochs)).isEmpty() }.keySet() as Set
            if ((rawProfiles.keySet() as Set) != required) {
                errors.add("${profile}: resolved ABI implementations differ from selected profile")
            }
            (rawProfiles as Map).each { Object implementation, Object rawClasses ->
                if (!(rawClasses instanceof Map) || (rawClasses as Map).any { Object name, Object digest -> !(digest instanceof String) || !(digest ==~ /[0-9a-f]{64}/) }) {
                    errors.add("${profile}/${implementation}: invalid resolved ABI class hashes")
                }
            }
        }
    }

    static boolean validAbiDeclarationHash(Object value) {
        value instanceof String && value ==~ /[0-9a-f]{64}/ && value != '0' * 64
    }

    static void validatePublicationDependencies(Map catalog, List<String> errors) {
        Map dependencies = catalog.publicationDependencies instanceof Map
                ? catalog.publicationDependencies as Map : [:]
        Set<String> expectedIds = [
                'fabric_api', 'yet_another_config_lib_v3', 'sqlite_jdbc'
        ] as Set
        if ((dependencies.keySet() as Set) != expectedIds) {
            errors.add('publicationDependencies must declare Fabric API, YACL, and SQLite JDBC')
            return
        }
        Set<String> loaderIds = LoaderBackend.ids()
        dependencies.each { Object idRaw, Object declarationRaw ->
            String id = idRaw.toString()
            if (!(declarationRaw instanceof Map)) {
                errors.add("${id}: publication dependency must be an object")
                return
            }
            Map declaration = declarationRaw as Map
            Map dependencyPlatforms = declaration.platforms instanceof Map
                    ? declaration.platforms as Map : [:]
            Map modrinth = dependencyPlatforms.modrinth instanceof Map
                    ? dependencyPlatforms.modrinth as Map : [:]
            Map curseforge = dependencyPlatforms.curseforge instanceof Map
                    ? dependencyPlatforms.curseforge as Map : [:]
            Set loaders = declaration.loaders instanceof List
                    ? (declaration.loaders as List).collect { it.toString() } as Set : [] as Set
            if ((declaration.keySet() as Set) != ['modId', 'loaders', 'type', 'platforms'] as Set ||
                    !(declaration.modId instanceof String) || declaration.modId.isBlank() ||
                    loaders.isEmpty() || !loaderIds.containsAll(loaders) ||
                    !(declaration.type in ['required', 'optional']) ||
                    (dependencyPlatforms.keySet() as Set) != ['modrinth', 'curseforge'] as Set ||
                    (modrinth.keySet() as Set) != ['projectId'] as Set ||
                    !(modrinth.projectId ==~ /[A-Za-z0-9]{8}/) ||
                    (curseforge.keySet() as Set) != ['projectId', 'slug'] as Set ||
                    !(curseforge.projectId instanceof Integer) ||
                    (curseforge.projectId as int) <= 0 ||
                    !(curseforge.slug ==~ /[a-z0-9][a-z0-9_-]*/)) {
                errors.add("${id}: invalid publication dependency declaration")
            }
        }
        Map fabric = dependencies.fabric_api as Map
        if (fabric.type != 'required' || (fabric.loaders as Set) != ['fabric'] as Set) {
            errors.add('Fabric API must be required only for Fabric')
        }
        ['yet_another_config_lib_v3', 'sqlite_jdbc'].each { String id ->
            Map declaration = dependencies[id] as Map
            if (declaration.type != 'optional' || (declaration.loaders as Set) != loaderIds) {
                errors.add("${id} must be optional for every loader")
            }
        }
    }

    static Map<String, Set<String>> classifyAffected(File repositoryRoot, Map catalog, Collection<String> rawPaths) {
        Map<String, Set<String>> affected = [:].withDefault { [] as Set }
        List targets = catalog.targets as List
        Set<String> targetIds = targets.collect { it.id.toString() } as Set
        List<String> serverInputs = catalog.serverPlugin instanceof Map &&
                catalog.serverPlugin.productionInputs instanceof Map
                ? (catalog.serverPlugin.productionInputs.server as List).collect { it.toString() }
                : []
        Set<String> serverBuildFiles = serverInputs.findAll { it.endsWith('/src/main') }.collect {
            it.substring(0, it.length() - '/src/main'.length()) + '/build.gradle'
        } as Set<String>
        Map<String, Set<String>> sourceOwners = [:].withDefault { [] as Set }
        targets.each { Object raw ->
            Map target = raw as Map
            resolveTargetSources(repositoryRoot, catalog, target).java.each { String root -> sourceOwners[root].add(target.id.toString()) }
            resolveTargetSources(repositoryRoot, catalog, target).resources.each { String root -> sourceOwners[root].add(target.id.toString()) }
        }
        rawPaths.each { String raw ->
            String path = raw.startsWith('./') ? raw.substring(2) : raw
            boolean removedRootChangelog = !new File(repositoryRoot, path).exists() &&
                    path ==~ /[A-Z][A-Z0-9_]*_CHANGELOG\.md/
            if (path == 'gradle/version.properties') {
                targetIds.each { affected[it].add('version-promotion') }
                return
            }
            if (path in ['CHANGELOG.md', 'PLUGIN_CHANGELOG.md', 'README.md', '.gitignore'] ||
                removedRootChangelog ||
                path.startsWith('.github/') || path.startsWith('.idea/') || path.startsWith('pub/') ||
                path.contains('/src/test/') || path.contains('/src/testFixtures/')) {
                return
            }
            if (serverInputs.any { path == it || path.startsWith(it + '/') } ||
                    serverBuildFiles.contains(path)) {
                return
            }
            Map target = targets.find { path == it.path || path.startsWith(it.path.toString() + '/') } as Map
            if (target != null) {
                affected[target.id.toString()].add("target-local:${target.path}")
                return
            }
            Map relocatedTarget = targets.find {
                String previousPath = "targets/${it.loader.id}/${it.minecraft.version}"
                path == previousPath || path.startsWith(previousPath + '/')
            } as Map
            if (relocatedTarget != null) {
                affected[relocatedTarget.id.toString()].add("target-relocated:${relocatedTarget.path}")
                return
            }
            List matches = sourceOwners.findAll { String root, Set owners -> path == root || path.startsWith(root + '/') }.collectMany { it.value as List }
            if (matches) {
                matches.each { affected[it].add('source') }
                return
            }
            if (path.startsWith('compat/capabilities/') &&
                    !new File(repositoryRoot, path).exists()) {
                targetIds.each { affected[it].add('removed-capability-source') }
                return
            }
            if (path.endsWith('/build.gradle')) {
                String module = path.substring(0, path.length() - '/build.gradle'.length())
                Set<String> moduleOwners = sourceOwners.findAll { String root, Set owners ->
                    root == module || root.startsWith(module + '/')
                }.collectMany { it.value as List } as Set
                if (!moduleOwners.isEmpty()) {
                    moduleOwners.each { affected[it].add('module-build') }
                    return
                }
            }
            if (path.startsWith('gradle/') || path == 'build.gradle' || path == 'settings.gradle' || path in ['LICENSE', 'NOTICE']) {
                targetIds.each { affected[it].add('shared-build') }
                return
            }
            throw new IllegalArgumentException("cannot map changed production/build path to targets: ${path}")
        }
        affected
    }

    static Map affectedResult(File repositoryRoot, Map catalog, Collection<String> rawPaths) {
        Map<String, Set<String>> reasons = classifyAffected(repositoryRoot, catalog, rawPaths)
        List<String> ids = (catalog.targets as List).collect { it.id.toString() }.findAll { reasons.containsKey(it) && !reasons[it].isEmpty() }
        [paths: rawPaths as List, targetIds: ids, reasons: reasons.collectEntries { String id, Set<String> values -> [id, values.sort()] }]
    }

    static String sha256(byte[] bytes) {
        MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()
    }

    static String json(Object value) {
        JsonOutput.prettyPrint(JsonOutput.toJson(value)) + '\n'
    }
}

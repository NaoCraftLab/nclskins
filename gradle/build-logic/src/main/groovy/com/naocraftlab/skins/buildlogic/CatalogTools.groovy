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
        'gui', 'guiCapeMaterial', 'textures', 'preview', 'appearance', 'loaderScreen', 'session',
        'clientExecutor', 'filePicker', 'bundledSkin', 'currentAppearance',
        'updateNotification', 'skinExtensionEnvironment',
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
            'id', 'name', 'group', 'license', 'localization', 'descriptions', 'contact', 'links', 'platforms',
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

    static String minecraftExperimentalRuntimeVersion(String minecraft, String compileVersion) {
        def match = compileVersion =~ /${java.util.regex.Pattern.quote(minecraft)}-(snapshot|pre|rc)-([0-9]+)/
        if (!match.matches()) {
            return null
        }
        String phase = match.group(1) == 'snapshot' ? 'alpha' : match.group(1)
        "${minecraft}-${phase}.${match.group(2)}".toString()
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

    static File blockbenchRoot(File repositoryRoot, Map catalog) {
        Map producers = catalog.resourceProducers instanceof Map ? catalog.resourceProducers as Map : [:]
        Object raw = producers.canonicalBlockbench
        if (!(raw instanceof String) || raw.isBlank()) {
            throw new IllegalArgumentException('catalog has no canonical Blockbench resource producer')
        }
        new File(repositoryRoot, repositoryRelative(repositoryRoot, raw, 'resourceProducers.canonicalBlockbench'))
    }

    static File blockbenchFile(File repositoryRoot, Map catalog, String resourcePath) {
        if (!(resourcePath ==~ '^[A-Za-z0-9._/-]+\\.png$') || resourcePath.contains('..')) {
            throw new IllegalArgumentException("unsafe Blockbench resource path: ${resourcePath}")
        }
        File root = blockbenchRoot(repositoryRoot, catalog)
        new File(root, resourcePath.substring(0, resourcePath.length() - '.png'.length()) + '.bbmodel')
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
        Map versions = declaration.versions instanceof Map ? declaration.versions as Map : [:]
        Object raw = versions[target.id]
        raw == null ? null : raw.toString()
    }

    static boolean optionalDependencyDevelopmentRuntimeEnabled(
            Map catalog, Map target, String dependencyId) {
        Map declaration = (catalog.optionalDependencies as Map)[dependencyId] as Map
        List runtimeTargets = declaration.runtimeTargets instanceof List
                ? declaration.runtimeTargets as List : []
        runtimeTargets.collect { it.toString() }.contains(target.id.toString())
    }

    static Map optionalDevelopmentArtifact(Map catalog, Map target, String dependencyId) {
        Map dependencies = catalog.optionalDependencies instanceof Map
                ? catalog.optionalDependencies as Map : [:]
        if (!dependencies.containsKey(dependencyId)) {
            throw new IllegalArgumentException("unknown optional dependency: ${dependencyId}")
        }
        String targetId = target.id?.toString()
        if (!(catalog.targets instanceof List) ||
                !(catalog.targets as List).any { it.id?.toString() == targetId }) {
            throw new IllegalArgumentException("unknown target for optional dependency: ${targetId}")
        }
        Map declaration = dependencies[dependencyId] as Map
        Map versions = declaration.developmentVersions instanceof Map
                ? declaration.developmentVersions as Map : [:]
        Map artifacts = declaration.developmentArtifacts instanceof Map
                ? declaration.developmentArtifacts as Map : [:]
        Object versionId = versions[targetId]
        Object raw = versionId == null ? null : artifacts[versionId.toString()]
        raw == null ? null : raw as Map
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


    static boolean validAbiDeclarationHash(Object value) {
        value instanceof String && value ==~ /[0-9a-f]{64}/ && value != '0' * 64
    }


    static Map<String, Set<String>> classifyAffected(
            File repositoryRoot,
            Map catalog,
            Collection<String> rawPaths,
            Collection<Map> historicalCatalogs = []) {
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
        Map<String, Set<String>> historicalSourceOwners = [:].withDefault { [] as Set }
        Closure<Void> collectSourceOwners = { Map sourceCatalog, Map<String, Set<String>> owners ->
            (sourceCatalog.targets as List).each { Object raw ->
                Map target = raw as Map
                String targetId = target.id.toString()
                if (!targetIds.contains(targetId)) return
                Map sources = resolveTargetSources(repositoryRoot, sourceCatalog, target)
                (sources.java as List).each { String root -> owners[root].add(targetId) }
                (sources.resources as List).each { String root -> owners[root].add(targetId) }
                if (sourceCatalog.resourceProducers instanceof Map &&
                        sourceCatalog.resourceProducers.canonicalBlockbench instanceof String) {
                    String producer = repositoryRelative(repositoryRoot,
                            sourceCatalog.resourceProducers.canonicalBlockbench,
                            'resourceProducers.canonicalBlockbench')
                    owners[producer].add(targetId)
                }
            }
        }
        collectSourceOwners(catalog, sourceOwners)
        historicalCatalogs.each { Map historicalCatalog ->
            collectSourceOwners(historicalCatalog, historicalSourceOwners)
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
            List historicalMatches = historicalSourceOwners.findAll { String root, Set owners ->
                path == root || path.startsWith(root + '/')
            }.collectMany { it.value as List }
            if (historicalMatches) {
                historicalMatches.each { affected[it].add('historical-source') }
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

    static Map affectedResult(
            File repositoryRoot,
            Map catalog,
            Collection<String> rawPaths,
            Collection<Map> historicalCatalogs = []) {
        Map<String, Set<String>> reasons = classifyAffected(
                repositoryRoot, catalog, rawPaths, historicalCatalogs)
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

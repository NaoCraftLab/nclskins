package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.regex.Pattern

final class CatalogTools {
    static final Set<String> REQUIRED_CAPABILITIES = [
        'gui', 'textures', 'preview', 'appearance', 'loaderScreen', 'session',
        'clientExecutor', 'filePicker', 'bundledSkin', 'currentAppearance',
        'serverSignal', 'serverCommand', 'serverProfileVerification',
        'serverProfileMutation', 'serverTracking', 'serverPlayerInfoPublication',
        'serverLoader'
    ] as Set
    static final Set<String> TARGET_KEYS = [
        'id', 'path', 'minecraft', 'loader', 'java', 'development', 'gradleFamily',
        'sourceLayout', 'capabilities', 'metadata', 'artifact'
    ] as Set
    static final Set<String> MOD_KEYS = [
        'id', 'name', 'group', 'license', 'description', 'homepage', 'authors',
        'icon', 'iconBlur'
    ] as Set
    static final Pattern VERSION_PATTERN = Pattern.compile(
        '^(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)' +
        '(?:-(?:alpha|beta)\\.[1-9][0-9]*)?$')

    static Map loadCatalog(File repositoryRoot) {
        def value = new JsonSlurper().parse(new File(repositoryRoot, 'gradle/targets.json'))
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException('gradle/targets.json must contain an object')
        }
        value as Map
    }

    static Map loadCatalog(Path repositoryRoot) {
        loadCatalog(repositoryRoot.toFile())
    }

    static Map loadJson(File file) {
        def value = new JsonSlurper().parse(file)
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("${file} must contain an object")
        }
        value as Map
    }

    static Map loadJson(Path file) {
        loadJson(file.toFile())
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

    static void validate(File repositoryRoot, Map catalog) {
        List<String> errors = []
        Set expectedTop = [
            'schemaVersion', 'mod', 'plugins', 'gradleFamilies', 'gsonCompatibility',
            'baseBundles', 'sourceBundles', 'capabilityImplementations', 'targets'
        ] as Set
        if (catalog.schemaVersion != 3) {
            errors.add("unsupported schemaVersion: ${catalog.schemaVersion}")
        }
        if ((catalog.keySet() as Set) != expectedTop) {
            errors.add('catalog top-level keys differ from schema')
        }
        Map mod = catalog.mod instanceof Map ? catalog.mod as Map : [:]
        if ((mod.keySet() as Set) != MOD_KEYS) {
            errors.add('mod identity keys differ from schema')
        }
        ['id', 'name', 'group', 'license', 'description', 'homepage'].each {
            if (!(mod[it] instanceof String) || !mod[it]) {
                errors.add("mod.${it} must be non-empty")
            }
        }
        if (mod.license != 'GPL-3.0-only') {
            errors.add('mod.license must be GPL-3.0-only')
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
        }
        Map gson = catalog.gsonCompatibility instanceof Map ? catalog.gsonCompatibility as Map : [:]
        if ((gson.keySet() as Set) != ['minimum', 'maximum'] as Set || gson.values().any { !(it instanceof String) || it.isBlank() }) {
            errors.add('gsonCompatibility must define non-empty minimum and maximum versions')
        }
        Set pluginKeys = ['loom', 'modDevGradle', 'forgeGradle', 'mixinGradle', 'mixinProcessor'] as Set
        if (!(catalog.plugins instanceof Map) || ((catalog.plugins as Map).keySet() as Set) != pluginKeys ||
            (catalog.plugins as Map).values().any { !(it instanceof String) || !it }) {
            errors.add('plugins must pin every supported build plugin')
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
            if ((target.keySet() as Set) != TARGET_KEYS) {
                errors.add("${target.id}: target keys differ from schema")
            }
            String loader = target.loader instanceof Map ? target.loader.id?.toString() : null
            String minecraft = target.minecraft instanceof Map ? target.minecraft.version?.toString() : null
            String expectedId = loader && minecraft ? "${loader}-${minecraft}" : null
            String expectedPath = loader && minecraft ? "targets/${loader}/${minecraft}" : null
            if (target.id != expectedId || target.path != expectedPath) {
                errors.add("${target.id}: target identity/path differs from loader and Minecraft version")
            }
            Map minecraftDeclaration = target.minecraft instanceof Map ? target.minecraft as Map : [:]
            if ((minecraftDeclaration.keySet() as Set) != ['version', 'predicate', 'epoch'] as Set || minecraftDeclaration.values().any { !(it instanceof String) || it.isBlank() }) {
                errors.add("${target.id}: invalid Minecraft declaration")
            }
            Map loaderDeclaration = target.loader instanceof Map ? target.loader as Map : [:]
            if ((loaderDeclaration.keySet() as Set) != ['id', 'version', 'predicate', 'apiVersion', 'apiPredicate'] as Set || !(loader in ['fabric', 'forge', 'neoforge']) || !(loaderDeclaration.version instanceof String) || loaderDeclaration.version.isBlank() || !(loaderDeclaration.predicate instanceof String) || loaderDeclaration.predicate.isBlank()) {
                errors.add("${target.id}: invalid loader declaration")
            }
            if (loader == 'fabric') {
                if (!(loaderDeclaration.apiVersion instanceof String) || loaderDeclaration.apiVersion.isBlank() || !(loaderDeclaration.apiPredicate instanceof String) || loaderDeclaration.apiPredicate.isBlank()) errors.add("${target.id}: Fabric API versions must be explicit")
            } else if (loaderDeclaration.apiVersion != null || loaderDeclaration.apiPredicate != null) {
                errors.add("${target.id}: non-Fabric target must not declare a Fabric API")
            }
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
            ports.add(development.serverPort)
            artifacts.add(artifact.file)
            coordinates.add(artifact.mavenArtifactId)
            modules.add(artifact.automaticModuleName)
        }
        [ids, paths, ports, artifacts, coordinates, modules].each { List values ->
            if (values.contains(null) || values.size() != values.toSet().size()) {
                errors.add("target uniqueness constraint failed: ${values}")
            }
        }
        validateAbi(repositoryRoot, catalog, errors)
        if (errors) {
            throw new IllegalArgumentException(errors.collect { "- ${it}" }.join('\n'))
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
        if (abi.schemaVersion != 4 || !(abi.implementations instanceof Map)) {
            errors.add('ABI fingerprint schemaVersion must be 4')
            return
        }
        Map<String, String> selected = [:]
        Map<String, Set<String>> epochs = [:].withDefault { [] as Set }
        (catalog.targets as List).each { Object raw ->
            Map target = raw as Map
            (target.capabilities as Map).each { Object kindRaw, Object implementationRaw ->
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
            if (!(entry.baselineSha256 instanceof String) || !(entry.baselineSha256 ==~ /[0-9a-f]{64}/)) {
                errors.add("${id}: invalid ABI declaration hash")
            }
        }
        Set<String> catalogEpochs = (catalog.targets as List).collect { it.minecraft.epoch.toString() } as Set
        Map resolved = abi.resolvedByEpoch instanceof Map ? abi.resolvedByEpoch as Map : [:]
        if ((resolved.keySet() as Set) != catalogEpochs) {
            errors.add('resolved ABI baselines must exactly cover catalog epochs')
        }
        resolved.each { Object epoch, Object rawProfiles ->
            if (!(rawProfiles instanceof Map)) {
                errors.add("${epoch}: resolved ABI baseline must be an object")
                return
            }
            Set<String> required = epochs.findAll { String id, Set values -> values.contains(epoch.toString()) }.keySet() as Set
            if ((rawProfiles.keySet() as Set) != required) {
                errors.add("${epoch}: resolved ABI profiles differ from selected implementations")
            }
            (rawProfiles as Map).each { Object implementation, Object rawClasses ->
                if (!(rawClasses instanceof Map) || (rawClasses as Map).any { Object name, Object digest -> !(digest instanceof String) || !(digest ==~ /[0-9a-f]{64}/) }) {
                    errors.add("${epoch}/${implementation}: invalid resolved ABI class hashes")
                }
            }
        }
    }

    static Map<String, Set<String>> classifyAffected(File repositoryRoot, Map catalog, Collection<String> rawPaths) {
        Map<String, Set<String>> affected = [:].withDefault { [] as Set }
        List targets = catalog.targets as List
        Set<String> targetIds = targets.collect { it.id.toString() } as Set
        Map<String, Set<String>> sourceOwners = [:].withDefault { [] as Set }
        targets.each { Object raw ->
            Map target = raw as Map
            resolveTargetSources(repositoryRoot, catalog, target).java.each { String root -> sourceOwners[root].add(target.id.toString()) }
            resolveTargetSources(repositoryRoot, catalog, target).resources.each { String root -> sourceOwners[root].add(target.id.toString()) }
        }
        rawPaths.each { String raw ->
            String path = raw.startsWith('./') ? raw.substring(2) : raw
            if (path == 'gradle/version.properties') {
                targetIds.each { affected[it].add('version-promotion') }
                return
            }
            if (path in ['CHANGELOG.md', 'README.md', '.gitignore'] ||
                path.startsWith('.github/') || path.startsWith('.idea/') ||
                path.contains('/src/test/') || path.contains('/src/testFixtures/')) {
                return
            }
            Map target = targets.find { path == it.path || path.startsWith(it.path.toString() + '/') } as Map
            if (target != null) {
                affected[target.id.toString()].add("target-local:${target.path}")
                return
            }
            List matches = sourceOwners.findAll { String root, Set owners -> path == root || path.startsWith(root + '/') }.collectMany { it.value as List }
            if (matches) {
                matches.each { affected[it].add('source') }
                return
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

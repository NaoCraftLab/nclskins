package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.lang.module.ModuleFinder
import java.security.MessageDigest
import java.util.zip.ZipFile

final class WaveyLegacyComposition {
    private static final List<String> REFMAP_PROPERTIES = ['mixin.env.remapRefMap', 'mixin.env.refMapRemappingFile',
            'net.minecraftforge.gradle.GradleStart.srg.srg-mcp']
    private static final Set<String> ARGUMENTS = ['--launchTarget', '--version', '--assetIndex', '--assetsDir', '--gameDir',
            '--fml.forgeVersion', '--fml.mcVersion', '--fml.forgeGroup', '--fml.mcpVersion', '--fml.neoForgeVersion',
            '--fml.fmlVersion', '--fml.neoFormVersion', '--mixin.config'] as Set
    private static List<String> expand(List input) {
        input.collectMany { value ->
            String text = value.toString()
            if (!text.startsWith('@')) return [text]
            File file = new File(text.substring(1))
            if (!file.isFile() || file.length() > 262144L) throw new IllegalArgumentException('Provider argument file missing/oversized')
            def tokens = new StreamTokenizer(new StringReader(file.getText('UTF-8')))
            tokens.resetSyntax(); tokens.whitespaceChars(0, 32); tokens.wordChars(33, 255); tokens.quoteChar(34); tokens.commentChar(35)
            List<String> result = []
            while (tokens.nextToken() != StreamTokenizer.TT_EOF) result.add(tokens.sval)
            if (result.any { it == null || it.startsWith('@') }) throw new IllegalArgumentException('Nested provider argument file')
            result
        }
    }
    static Map safeProvider(Map input, File expectedMapping = null) {
        List<String> expanded = expand(input.args)
        String main = input.mainClass
        if (main == 'net.neoforged.devlaunch.Main' && expanded && expanded.remove(0) == 'cpw.mods.bootstraplauncher.BootstrapLauncher') main = 'cpw.mods.bootstraplauncher.BootstrapLauncher'
        if (main != 'cpw.mods.bootstraplauncher.BootstrapLauncher') throw new IllegalArgumentException('Public legacy bootstrap required')
        List<String> args = expanded
        for (int i = 0; i < args.size(); i += 2) {
            if (!ARGUMENTS.contains(args[i]) || i + 1 == args.size()) throw new IllegalArgumentException('Unsafe legacy program argument')
        }
        List<String> vm = []
        Set<String> paired = ['-p', '--module-path', '--add-modules', '--add-opens', '--add-exports'] as Set
        List<String> original = expand(input.jvmArgs)
        for (int i = 0; i < original.size(); i++) {
            String value = original[i]
            if (paired.contains(value)) {
                if (++i == original.size()) throw new IllegalArgumentException('Missing JVM argument value')
                vm.add(value); vm.add(original[i])
            } else if (value == '-XstartOnFirstThread' || value == '-Djava.net.preferIPv6Addresses=system'
                    || value.startsWith('--add-opens=') || value.startsWith('--add-exports=') || value.startsWith('--add-modules=')) vm.add(value)
            else if (value.startsWith('-DlegacyClassPath.file=') || value.startsWith('-DignoreList=') || value.startsWith('-DmergeModules=') || value.startsWith('-Dfml.modFolders=') || value.startsWith('-Dlog4j2.configurationFile=')
                    || value in ['-Dforge.enableGameTest=true', '-Dneoforge.enableGameTest=true']) vm.add(value)
            else if (!(value ==~ /-Xm[sx][0-9]+[mMgG]/) && !value.startsWith('-Dfile.encoding=')
                    && !value.startsWith('-Duser.') && !value.startsWith('-Dforge.logging.') && value != '-ea')
                throw new IllegalArgumentException('Unsafe legacy JVM argument')
        }
        Map props = (input.properties ?: [:]).findAll { k, v -> k in ['legacyClassPath.file', 'ignoreList', 'mergeModules'] || REFMAP_PROPERTIES.contains(k) }
        if (expectedMapping != null || REFMAP_PROPERTIES.any { props.containsKey(it) }) validateRefmapProperties(props, expectedMapping)
        Map environment = (input.environment ?: [:]).findAll { k, v -> k in ['MOD_CLASSES', 'MCP_MAPPINGS'] }
        [mainClass: main, args: args, jvmArgs: vm, properties: props, environment: environment,
                classpath: input.classpath.collect { it.toString() }.unique()]
    }
    private static String hash(File file) { MessageDigest.getInstance('SHA-256').digest(file.bytes).encodeHex().toString() }
    private static File validateRefmapProperties(Map props, File expectedMapping = null) {
        if (!REFMAP_PROPERTIES.every { props[it] instanceof String } || props[REFMAP_PROPERTIES[0]] != 'true')
            throw new IllegalArgumentException('Native refmap properties missing or malformed')
        String path = props[REFMAP_PROPERTIES[1]]
        if (path != props[REFMAP_PROPERTIES[2]] || path.any { it < ' ' } || path.contains('{') || path.contains('}')
                || !new File(path).isAbsolute() || expectedMapping != null && path != expectedMapping.absolutePath)
            throw new IllegalArgumentException('Native refmap mapping binding mismatch')
        File mapping = new File(path)
        if (!mapping.isFile()) throw new IllegalArgumentException('Native refmap mapping missing')
        int renamed = 0
        mapping.eachLine('UTF-8') { line ->
            String[] parts = line.split(' ', -1)
            if (parts.length == 3 && parts[0] == 'CL:' && parts[1] == parts[2]) return
            boolean method = parts[0] == 'MD:'
            if (parts.length != (method ? 5 : 3) || !(parts[0] in ['FD:', 'MD:']))
                throw new IllegalArgumentException('Native refmap mapping must use SRG records')
            String source = parts[1], target = parts[method ? 3 : 2]
            int sourceSlash = source.lastIndexOf('/'), targetSlash = target.lastIndexOf('/')
            if (sourceSlash < 1 || targetSlash < 1 || source.substring(0, sourceSlash) != target.substring(0, targetSlash)
                    || method && (parts[2] != parts[4] || !(parts[2] ==~ /\((?:\[*(?:[BCDFIJSZ]|L[^;\s]+;))*\)(?:V|\[*(?:[BCDFIJSZ]|L[^;\s]+;))/)))
                throw new IllegalArgumentException('Native refmap mapping owner/descriptor mismatch')
            String from = source.substring(sourceSlash + 1), to = target.substring(targetSlash + 1)
            if (from ==~ /[fm]_[0-9]+_/) {
                if (!(to ==~ /[A-Za-z_$][A-Za-z0-9_$]*/) || to ==~ /[fm]_[0-9]+_/)
                    throw new IllegalArgumentException('Native refmap mapping direction mismatch')
                renamed++
            } else if (from != to) throw new IllegalArgumentException('Native refmap mapping direction mismatch')
        }
        if (renamed == 0) throw new IllegalArgumentException('Native refmap mapping has no SRG to named members')
        mapping
    }
    static void writeLegacyClasspath(File providerFile, File outputFile, List<File> runtime) {
        outputFile.setText((java.nio.file.Files.readAllLines(providerFile.toPath()) +
                runtime.collect { it.absolutePath }).unique().join('\n'), 'UTF-8')
    }
    private static Map<String, byte[]> archiveEntries(byte[] bytes) {
        Map<String, byte[]> entries = [:]
        new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes)).withCloseable { zip ->
            for (def entry = zip.nextEntry; entry != null; entry = zip.nextEntry) {
                if (!entry.directory) entries[entry.name] = zip.readAllBytes()
            }
        }
        entries
    }
    private static List<File> nativeCsvResources(List<File> inputs) {
        List<File> resources = inputs.findAll { it.isFile() && !it.name.endsWith('.jar') && it.name.endsWith('.zip') }.findAll { input ->
            new ZipFile(input).withCloseable { zip -> zip.getEntry('fields.csv') != null || zip.getEntry('methods.csv') != null }
        }
        if (resources.size() != 1) throw new IllegalArgumentException('Native CSV resource carrier cardinality')
        new ZipFile(resources.first()).withCloseable { zip ->
            ['fields.csv': 'f', 'methods.csv': 'm'].each { name, prefix ->
                def entry = zip.getEntry(name)
                if (entry == null) throw new IllegalArgumentException('Native CSV root resource missing')
                List<String> lines = zip.getInputStream(entry).getText('UTF-8').readLines()
                if (lines.size() < 2 || !lines.first().startsWith('searge,name,')) throw new IllegalArgumentException('Native CSV empty/header mismatch')
                List<String[]> rows = lines.drop(1).collect { it.split(',', -1) }
                if (!rows.every { it.length >= 2 && !it[0].isEmpty() && !it[1].isEmpty() }
                        || !rows.any { it[0] ==~ /${prefix}_[0-9]+_/ }
                        || !rows.findAll { it[0] ==~ /[fm]_[0-9]+_/ }.every {
                            it[1] ==~ /[A-Za-z_$][A-Za-z0-9_$]*/ && !(it[1] ==~ /[fm]_[0-9]+_/)
                        }) throw new IllegalArgumentException('Native CSV namespace mismatch')
            }
        }
        resources
    }
    private static Map mixinExtrasAdapter(List<File> runtime) {
        String operation = 'com/llamalad7/mixinextras/injector/wrapoperation/Operation.class'
        List<File> adapters = runtime.findAll { input -> new ZipFile(input).withCloseable { zip ->
            if (zip.getEntry(operation) != null) throw new IllegalArgumentException('Compiler MixinExtras is not an executable adapter')
            zip.getEntry('mixinextras.init.mixins.json') != null
        } }
        if (adapters.size() != 1) throw new IllegalArgumentException('Native MixinExtras adapter cardinality')
        File adapter = adapters.first()
        Map entries = archiveEntries(adapter.bytes)
        def manifest = new java.util.jar.Manifest(new ByteArrayInputStream(entries['META-INF/MANIFEST.MF'] ?: new byte[0]))
        if (manifest.mainAttributes.getValue('FMLModType') != 'GAMELIBRARY'
                || manifest.mainAttributes.getValue('MixinConfigs') != 'mixinextras.init.mixins.json')
            throw new IllegalArgumentException('Native MixinExtras normal library metadata missing')
        Map config = new JsonSlurper().parse(entries['mixinextras.init.mixins.json']) as Map
        if (config.plugin != 'com.llamalad7.mixinextras.platform.forge.MixinExtrasConfigPlugin')
            throw new IllegalArgumentException('Native MixinExtras init route missing')
        Map metadata = new JsonSlurper().parse(entries['META-INF/jarjar/metadata.json'] ?: new byte[0]) as Map
        List nested = metadata.jars.findAll { it.identifier.group == 'com.github.LlamaLad7' && it.identifier.artifact == 'MixinExtras' }
        if (nested.size() != 1 || !(entries[nested.first().path] instanceof byte[]))
            throw new IllegalArgumentException('Native MixinExtras nested supplier missing')
        byte[] payload = entries[nested.first().path]
        Map library = archiveEntries(payload)
        List<String> required = ['injector/wrapoperation/Operation', 'injector/wrapoperation/WrapOperation',
                'injector/wrapmethod/WrapMethod', 'injector/ModifyReturnValue', 'sugar/Local', 'sugar/Share',
                'sugar/ref/LocalBooleanRef', 'MixinExtrasBootstrap']
        if (!required.every { library.containsKey('com/llamalad7/mixinextras/' + it + '.class') })
            throw new IllegalArgumentException('Native MixinExtras nested API incomplete')
        [file: adapter, sha256: hash(adapter), nested: nested.first().path,
                nestedSha256: MessageDigest.getInstance('SHA-256').digest(payload).encodeHex().toString(), api: required]
    }
    private static void excludeGameArchivesFromBoot(List<String> vm, Map props, List<File> gameArchives) {
        String key = '-DignoreList='
        List<String> inheritedVm = vm.findAll { it.startsWith(key) }
        String inherited = props.containsKey('ignoreList') ? String.valueOf(props.ignoreList)
                : inheritedVm ? inheritedVm.last().substring(key.length()) : 'asm,securejarhandler'
        List<String> prefixes = inherited.split(',', -1).findAll { !it.isEmpty() }.toList()
        vm.removeAll { it.startsWith(key) }
        props.ignoreList = (prefixes + gameArchives.collect { it.name }).unique().join(',')
    }
    static void verify(VerifyWaveyCapesCompositionTask task, File work, List<File> compilerInputs,
                       List<File> existingRuntime, List<File> sources, Map receipt, Closure run, Closure save) {
        String mode = task.mode.get()
        int release = task.javaRelease.get()
        if (mode == 'mesh' ? release != 21 : mode != 'rendered-buffer' || release != 17) throw new IllegalArgumentException('Legacy native toolchain mismatch')
        Map provider = safeProvider(new JsonSlurper().parse(task.loaderProgramArgs.get().asFile) as Map)
        if (release == 17) {
            File mapping = validateRefmapProperties(provider.properties)
            receipt.refmapRemapping = [properties: provider.properties.findAll { k, v -> REFMAP_PROPERTIES.contains(k) },
                    mapping: mapping.absolutePath, sha256: hash(mapping)]
        }
        List<File> nativeInputs = task.loaderRuntimeClasspath.files.toList()
        List<File> systemResources = release == 17 ? nativeCsvResources(nativeInputs) : []
        List<File> runtime = nativeInputs.findAll { it.isFile() && it.name.endsWith('.jar') }.toList()
        if (runtime.any { it.path.contains('/.agents/') }) throw new IllegalArgumentException('Ignored runtime input')
        List<File> own = existingRuntime.findAll { input ->
            boolean found = false
            WaveyLoaderComposition.merge(input, [:], { name -> if (name.startsWith('com/naocraftlab/skins/') || input.isDirectory() && name in ['META-INF/mods.toml', 'META-INF/neoforge.mods.toml']) found = true; false })
            found
        }
        List<File> wavey = task.namedWaveyRuntime.isPresent() ? [task.namedWaveyRuntime.get().asFile] : existingRuntime.findAll { input ->
            input.isFile() && input.name.endsWith('.jar') && new ZipFile(input).withCloseable { it.getEntry('dev/tr7zw/waveycapes/render/CapeNodeCollector.class') != null }
        }.unique()
        if (wavey.size() != 1) throw new IllegalArgumentException('Genuine named Wavey runtime cardinality')
        runtime = (runtime + wavey + existingRuntime.findAll { it.name.startsWith('sqlite-jdbc-') }).unique()
        runtime.removeAll(own)
        runtime.removeAll { input -> input.name == 'companions.jar' }
        Map extras = release == 17 ? mixinExtrasAdapter(runtime) : [:]
        List<String> gameNames = ['nclskins-verification.jar', wavey.first().name] + (extras ? [extras.file.name] : [])
        if (runtime.any { input -> !gameNames.contains(input.name) && gameNames.any { input.name.startsWith(it) } })
            throw new IllegalArgumentException('GAME BOOT exclusion collaterally drops native input')
        if (extras && provider.classpath.contains(new File(work, 'runtime/mods/' + extras.file.name).absolutePath))
            throw new IllegalArgumentException('MixinExtras normal route is legacy-claimed')
        Map<String, byte[]> game = [:]
        own.each { WaveyLoaderComposition.merge(it, game, { it != 'META-INF/MANIFEST.MF' && it != 'module-info.class' }) }
        if (task.generatedMixinRefmap.isPresent()) {
            File generated = task.generatedMixinRefmap.get().asFile
            Map parsed = new JsonSlurper().parse(generated) as Map
            if (!(parsed.mappings instanceof Map) || parsed.mappings.isEmpty())
                throw new IllegalArgumentException('Native generated NCL refmap empty')
            if (game.containsKey(generated.name) && !Arrays.equals(game[generated.name], generated.bytes))
                throw new IllegalArgumentException('Native generated NCL refmap mismatch')
            game[generated.name] = generated.bytes
            receipt.generatedMixinRefmap = [path: generated.absolutePath, sha256: hash(generated)]
        }
        game.findAll { name, bytes -> name.startsWith('nclskins.') && name.endsWith('.mixins.json') }.each { name, bytes ->
            Map config = new JsonSlurper().parse(bytes) as Map
            if (config.refmap != null && (!task.generatedMixinRefmap.isPresent()
                    || config.refmap != task.generatedMixinRefmap.get().asFile.name || !game.containsKey(config.refmap)))
                throw new IllegalArgumentException('Declared NCL generated refmap missing/mismatched')
        }
        String cp = (compilerInputs + runtime).collect { it.absolutePath }.unique().join(File.pathSeparator)
        String javac = task.compilerExecutable.get().asFile.absolutePath, java = task.javaExecutable.get().asFile.absolutePath
        Map<String, File> outputs = ['boot', 'spi', 'game', 'contract'].collectEntries { role ->
            File directory = new File(work, role); directory.mkdirs(); [(role): directory]
        }
        List<File> loader = task.loaderSources.files.toList()
        Closure compile = { role, inputs, classpath -> run('compile-' + role,
                [javac, '-proc:none', '--release', release, '-encoding', 'UTF-8', '-cp', classpath, '-d', outputs[role]] + inputs) }
        compile('boot', loader.findAll { it.path.contains('/legacy-boot/') || it.path.contains('/legacy-forge/') || it.path.contains('/legacy-neoforge/') }, cp)
        File pure = sources.find { it.name == 'ImmediateTerminalBindings.java' }
        List<File> pureSources = [pure, sources.find { it.name == 'ImmediatePreviewBindings.java' }]
        compile('spi', loader.findAll { it.path.contains('/legacy-spi/') } + pureSources, cp)
        compile('game', (sources.findAll { !pureSources.contains(it) } + loader.findAll { it.path.contains('/legacy-game/') || it.name == 'CaseLifecycle.java' }).unique(), outputs.spi.absolutePath + File.pathSeparator + cp)
        compile('contract', loader.findAll { it.path.contains('/legacy-contract/') }, outputs.spi.absolutePath + File.pathSeparator + cp)
        run('legacy-terminal-contract', [java, '-Dnclskins.wavey.mode=' + mode, '-cp', ([outputs.contract, outputs.spi] + runtime).collect { it.absolutePath }.join(File.pathSeparator),
                'com.naocraftlab.skins.buildlogic.contract.LegacyTerminalContract'] + (runtime + compilerInputs).unique(), 0, 'CANONICAL_LEGACY_CONTRACT_PASS')
        File root = new File(work, 'runtime'), mods = new File(root, 'mods'); mods.mkdirs()
        File waveyJar = new File(mods, wavey.first().name)
        java.nio.file.Files.copy(wavey.first().toPath(), waveyJar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Map<String, byte[]> boot = [:], spi = [:]
        WaveyLoaderComposition.merge(outputs.game, game, { !it.contains('/Headless') || it.startsWith('com/naocraftlab/skins/buildlogic/probe/wavey/HeadlessCapeClient') })
        WaveyLoaderComposition.merge(outputs.boot, boot, { true })
        WaveyLoaderComposition.merge(outputs.spi, spi, { true })
        File extrasJar = null
        if (extras) {
            extrasJar = new File(mods, extras.file.name)
            if (extrasJar.canonicalFile == extras.file.canonicalFile || provider.classpath.contains(extrasJar.absolutePath))
                throw new IllegalArgumentException('MixinExtras normal route is legacy-claimed')
            java.nio.file.Files.copy(extras.file.toPath(), extrasJar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            if (hash(extrasJar) != extras.sha256) throw new IllegalArgumentException('MixinExtras staging bytes changed')
            receipt.mixinExtras = extras.findAll { k, v -> k != 'file' } + [source: extras.file.absolutePath, normalCandidate: extrasJar.absolutePath,
                    intendedLayer: 'GAME', initRoute: 'mixinextras.init.mixins.json -> MixinExtrasConfigPlugin.onLoad -> MixinExtrasBootstrap.init']
        }
        String metadata = release == 17 ? 'META-INF/mods.toml' : 'META-INF/neoforge.mods.toml'
        if (!game.containsKey(metadata) || !game.containsKey('com/naocraftlab/skins/runtime/WaveyProducerFixture.class')
                || !game.containsKey('com/naocraftlab/skins/runtime/CapeProviderCoordinator.class')) throw new IllegalArgumentException('Legacy production runtime/fixture metadata grouping')
        game['META-INF/services/cpw.mods.modlauncher.api.ServiceRunner'] = 'com.naocraftlab.skins.buildlogic.probe.wavey.LegacyCapeService\n'.bytes
        game['META-INF/MANIFEST.MF'] = 'Manifest-Version: 1.0\nAutomatic-Module-Name: nclskins\n\n'.bytes
        boot['META-INF/services/cpw.mods.modlauncher.api.ILaunchHandlerService'] = 'com.naocraftlab.skins.buildlogic.boot.LegacyClientLaunchHandler\n'.bytes
        boot['META-INF/MANIFEST.MF'] = 'Manifest-Version: 1.0\nAutomatic-Module-Name: nclskins.verification.boot\n\n'.bytes
        spi['META-INF/services/cpw.mods.modlauncher.serviceapi.ILaunchPluginService'] = 'com.naocraftlab.skins.buildlogic.processor.LegacyCapeTerminalPlugin\n'.bytes
        spi['META-INF/MANIFEST.MF'] = 'Manifest-Version: 1.0\nAutomatic-Module-Name: nclskins.verification.terminals\n\n'.bytes
        File gameJar = new File(mods, 'nclskins-verification.jar'), bootJar = new File(work, 'verification-boot.jar'), spiJar = new File(work, 'verification-terminals.jar')
        [[gameJar, game], [bootJar, boot], [spiJar, spi]].each { WaveyLoaderComposition.archive(it[0], it[1]) }
        def descriptors = ModuleFinder.of(gameJar.toPath(), bootJar.toPath(), spiJar.toPath()).findAll()*.descriptor()
        if (descriptors.size() != 3 || descriptors.indices.any { i -> descriptors.indices.any { j -> i != j && !Collections.disjoint(descriptors[i].packages(), descriptors[j].packages()) } })
            throw new IllegalArgumentException('Legacy BOOT/SPI/GAME package overlap')
        receipt.preflight = WaveyLoaderComposition.preflight(runtime + [gameJar, spiJar])
        receipt.moduleGrouping = [runtimeFixtureSameModule: true, normalMetadata: true, configs: game.keySet().findAll { it.startsWith('nclskins.') && it.endsWith('.mixins.json') }.sort()]
        List<String> args = new ArrayList<>(provider.args)
        int target = args.indexOf('--launchTarget'), gameDir = args.indexOf('--gameDir')
        if (target < 0 || gameDir < 0) throw new IllegalArgumentException('Legacy provider launch target/game directory missing')
        args[target + 1] = 'nclskins' + (release == 17 ? 'forge' : 'neoforge') + 'clientdevverification'; args[gameDir + 1] = root.absolutePath
        List<String> vm = new ArrayList<>(provider.jvmArgs)
        Map props = new LinkedHashMap(provider.properties)
        List<File> gameArchives = [gameJar, waveyJar] + (extrasJar == null ? [] : [extrasJar])
        if (runtime.any { input -> !gameArchives.any { it.name == input.name } && gameArchives.any { input.name.startsWith(it.name) } })
            throw new IllegalArgumentException('GAME BOOT exclusion collaterally drops native input')
        excludeGameArchivesFromBoot(vm, props, gameArchives)
        List<String> prefixes = props.ignoreList.split(',').toList()
        if (!gameArchives.every { input -> prefixes.any { input.name.startsWith(it) } })
            throw new IllegalArgumentException('GAME library readmitted to BOOT')
        if (systemResources) {
            runtime.each { input -> new ZipFile(input).withCloseable { zip ->
                if (zip.getEntry('fields.csv') != null || zip.getEntry('methods.csv') != null)
                    throw new IllegalArgumentException('Native CSV first-resource ownership conflict')
            } }
            receipt.systemResources = systemResources.collect { [path: it.absolutePath, sha256: hash(it), reader: 'ClassLoader.getSystemResource', entries: ['fields.csv', 'methods.csv']] }
        }
        String legacy = props['legacyClassPath.file'] ?: vm.find { it.startsWith('-DlegacyClassPath.file=') }?.substring('-DlegacyClassPath.file='.length())
        if (legacy == null || !new File(legacy).isFile()) throw new IllegalArgumentException('Provider legacy classpath missing')
        File legacyFile = new File(work, 'legacy-classpath.txt')
        writeLegacyClasspath(new File(legacy), legacyFile, runtime + [gameJar, bootJar, spiJar])
        vm.removeAll { it.startsWith('-DlegacyClassPath.file=') || it.startsWith('-Dfml.modFolders=') }; props['legacyClassPath.file'] = legacyFile.absolutePath
        List<String> argv = [java] + vm + props.collect { k, v -> '-D' + k + '=' + v } + [
                '-Djava.awt.headless=true', '-Dfml.modFolders=nclskins%%' + gameJar.absolutePath, '-Dnclskins.wavey.loaderBatch=true', '-Dnclskins.wavey.mode=' + mode,
                '-Dnclskins.wavey.root=' + root.absolutePath, '-Dnclskins.wavey.definitionProfile=' + (task.guardOnly.get() ? 'guards' : 'positive'),
                '-Dnclskins.wavey.configs=' + receipt.moduleGrouping.configs.join(','), '-Xmx768m', '-ea',
                '-cp', (runtime + systemResources + [bootJar, spiJar]).collect { it.absolutePath }.join(File.pathSeparator), provider.mainClass] + args
        receipt.runtimeProfile = task.runtimeProfile.get()
        receipt.loaderSources = loader.collectEntries { [(it.absolutePath): hash(it)] }
        receipt.guardOnly = task.guardOnly.get(); receipt.guardRoster = task.guardRoster.get()
        String profile = task.guardOnly.get() ? 'guards' : 'positive'
        receipt.definitionProfiles = [[name: profile, module: gameJar.absolutePath, sha256: hash(gameJar), negativeOnly: false, state: 'PREPARED_NOT_STARTED']]
        receipt.loaderInvocations = [[name: profile, argv: argv, cwd: root.absolutePath, maxRuntimeSeconds: 120, implicitWorkers: 0,
                environment: provider.environment + [MOD_CLASSES: 'nclskins%%' + gameJar.absolutePath]]]
        receipt.plannedFmlContexts = 1; receipt.executedFmlContexts = 0
        receipt.scope = 'Legacy CLIENT recipe compiled/prepared; runtime/advanced preview NOT_EXECUTED'
        save()
        if (!task.prepareOnly.get()) {
            receipt.executedFmlContexts = 1; save()
            run('connected-client-' + profile, argv, 0, 'CANONICAL_CLIENT_CLOSED_PASS')
            receipt.guardResults = WaveyCompositionGuards.readResults(new File(work, 'connected-client-' + profile + '.log'),
                    task.guardOnly.get() ? WaveyCompositionGuards.ISOLATED : WaveyCompositionGuards.COMPATIBLE)
            receipt.definitionProfiles[0].state = 'PASS'; save()
        }
    }
}

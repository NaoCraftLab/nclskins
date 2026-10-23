package com.naocraftlab.skins.buildlogic

import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import java.lang.module.ModuleFinder

final class WaveyLoaderComposition {
    static boolean parsePrepareOnly(Object value, boolean present) {
        if (!present) return false
        if (value instanceof String && value == 'false') return false
        if (value instanceof String && value == 'true') return true
        throw new IllegalArgumentException('nclskinsWaveyLoaderPrepareOnly must be exactly true or false')
    }

    static String select(String profile, String loader, String mode, boolean submission) {
        boolean legacy = !submission && (loader == 'forge' && mode == 'rendered-buffer' || loader == 'neoforge' && mode == 'mesh')
        if (profile == 'default' && legacy) return 'legacy-client-' + mode
        if (profile == 'legacy-client-' + mode && legacy) return profile
        if (profile == 'default') profile = loader == 'neoforge' && mode in ['buffered', 'prepared', 'combined']
                ? 'direct-client-' + mode : 'isolated'
        if (profile == 'isolated') return profile
        if (loader != 'neoforge' || !(mode in ['buffered', 'prepared', 'combined']) || profile != 'direct-client-' + mode || mode in ['prepared', 'combined'] && submission)
            throw new IllegalArgumentException('CLIENT profile requires an implemented direct FML buffered/prepared/combined ABI')
        profile
    }

    static void archive(File destination, Map<String, byte[]> entries) {
        new JarOutputStream(new FileOutputStream(destination)).withCloseable { jar ->
            entries.sort().each { String name, byte[] bytes ->
                JarEntry entry = new JarEntry(name); entry.time = 0L
                jar.putNextEntry(entry); jar.write(bytes); jar.closeEntry()
            }
        }
    }

    static void merge(File source, Map<String, byte[]> entries, Closure admit) {
        Closure add = { String name, byte[] bytes ->
            if (admit(name)) {
                if (entries.containsKey(name) && !Arrays.equals(entries[name], bytes))
                    throw new IllegalArgumentException("Non-identical module entry ${name}")
                entries[name] = bytes
            }
        }
        if (source.isDirectory()) {
            source.eachFileRecurse { File entry ->
                if (entry.isFile()) add(source.toPath().relativize(entry.toPath()).toString(), entry.bytes)
            }
        } else new ZipFile(source).withCloseable { zip ->
            zip.entries().each { entry -> if (!entry.directory) add(entry.name, zip.getInputStream(entry).bytes) }
        }
    }

    static Map preflight(List<File> inputs) {
        Set<String> packages = [] as Set
        Map<String, List<String>> owners = [:].withDefault { [] }
        List<Map> services = []
        Closure scan
        scan = { byte[] bytes, String label ->
            new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes)).withCloseable { zip ->
                for (def entry = zip.nextEntry; entry != null; entry = zip.nextEntry) {
                    if (entry.directory) continue
                    byte[] content = zip.readAllBytes()
                    String name = entry.name
                    if (name.endsWith('.class') && !name.startsWith('META-INF/') && name.contains('/')) {
                        packages.add(name.substring(0, name.lastIndexOf('/')).replace('/', '.'))
                        if (name.startsWith('net/minecraft/') || name.startsWith('dev/tr7zw/')) owners[name].add(label)
                    }
                    if (name.startsWith('META-INF/services/')) {
                        services.add([archive: label, service: name, provider: new String(content, 'UTF-8').trim()])
                        if (name.contains('GraphicsBootstrapper') || name.contains('TestExecutionListener'))
                            throw new IllegalArgumentException("Unsafe bootstrap service ${name}")
                    }
                    if (name.endsWith('.jar')) scan(content, label + '!' + name)
                }
            }
        }
        inputs.each { File file ->
            if (!file.isFile() || !file.name.endsWith('.jar')) throw new IllegalArgumentException("Non-archive runtime input ${file.name}")
            if (file.name.startsWith('junit-fml-')) throw new IllegalArgumentException('Implicit FML listener is forbidden')
            scan(file.bytes, file.name)
        }
        Map duplicates = owners.findAll { name, roots -> roots.size() != 1 }
        if (!duplicates.isEmpty()) throw new IllegalArgumentException("Native/Wavey duplicate classes: ${duplicates.keySet()}")
        if (packages.contains('com.naocraftlab.skins.buildlogic.boot')) throw new IllegalArgumentException('BOOT/GAME package overlap')
        [nativeClassCount: owners.size(), packageCount: packages.size(), nativeDuplicates: duplicates,
                bootPackageOverlap: false, services: services, recursiveNestedArchives: true]
    }

    static void verify(VerifyWaveyCapesCompositionTask task, File work, List<File> compilerInputs,
                       List<File> existingRuntime, List<File> sources, Map receipt, Closure run, Closure save) {
        if (task.runtimeProfile.get().startsWith('legacy-client-')) {
            WaveyLegacyComposition.verify(task, work, compilerInputs, existingRuntime, sources, receipt, run, save)
            return
        }
        int release = task.javaRelease.get()
        if (!(release in [21, 25])) throw new IllegalArgumentException('Direct-FML profile requires catalog Java21/25')
        List<File> provider = task.loaderRuntimeClasspath.files.findAll { it.exists() }.toList()
        if (provider.any { it.path.contains('/.agents/') }) throw new IllegalArgumentException('Ignored provider input')
        receipt.runtimeProfile = task.runtimeProfile.get()
        receipt.scope = 'bounded representative CLIENT discriminator; NOT full canonical matrix'
        receipt.isolatedSensitivity = 'Mandatory separate guard-only stage; compatible bridge/no-support cases in positive batch; no-mod/old-version retained in target tests'
        receipt.guardOnly = task.guardOnly.get()
        receipt.guardRoster = task.guardRoster.get()
        receipt.abiSdkPin = task.abiSdkPin.get()
        receipt.loaderRuntimeClasspath = provider.collect { [path: it.absolutePath, sha256: WaveyCompositionGuards.fileHash(it)] }
        receipt.loaderVmArgs = [path: task.loaderVmArgs.get().asFile.absolutePath, sha256: WaveyCompositionGuards.fileHash(task.loaderVmArgs.get().asFile)]
        receipt.loaderProgramArgs = [path: task.loaderProgramArgs.get().asFile.absolutePath, sha256: WaveyCompositionGuards.fileHash(task.loaderProgramArgs.get().asFile)]
        receipt.loaderSources = task.loaderSources.files.collectEntries {
            [(it.absolutePath): java.security.MessageDigest.getInstance('SHA-256').digest(it.bytes).encodeHex().toString()]
        }
        File boot = new File(work, 'boot'), spi = new File(work, 'spi'), game = new File(work, 'game'), contract = new File(work, 'contract')
        [boot, spi, game, contract].each { it.mkdirs() }
        List<File> extras = existingRuntime.findAll { File file ->
            if (!file.isFile() || !file.name.endsWith('.jar')) return false
            new ZipFile(file).withCloseable { zip ->
                zip.getEntry('dev/tr7zw/waveycapes/render/CapeNodeCollector.class') != null || file.name.startsWith('sqlite-jdbc-')
            }
        }
        List<File> ownRuntime = existingRuntime.findAll { File input ->
            boolean own = false
            merge(input, [:], { String name ->
                if (name.startsWith('com/naocraftlab/skins/') || input.isDirectory() && name == 'META-INF/neoforge.mods.toml') own = true
                false
            })
            own
        }
        List<File> runtime = (provider + extras + ownRuntime).unique()
        List<File> fml = runtime.findAll { it.name ==~ /loader-(10|11|12)\..*\.jar/ }
        if (fml.size() != 1 || (task.mode.get() == 'combined') != fml.first().name.startsWith('loader-12.'))
            throw new IllegalArgumentException('Matched pinned FML10/11 buffered/prepared or FML12 combined processor ABI required')
        boolean frameContext = fml.first().name.startsWith('loader-12.')
        receipt.processorAbi = [loader: fml.first().name, frameContext: frameContext, runtimeLink: 'NOT_RUN']
        String compilerCp = (compilerInputs + runtime).collect { it.absolutePath }.unique().join(File.pathSeparator)
        String java = task.javaExecutable.get().asFile.absolutePath
        String javac = task.compilerExecutable.get().asFile.absolutePath
        Closure compile = { String role, File output, List<File> inputs, String cp ->
            run('compile-' + role, [javac, '-proc:none', '--release', release, '-encoding', 'UTF-8', '-cp', cp, '-d', output] + inputs)
        }
        List<File> loaderSources = task.loaderSources.files.toList()
        compile('boot', boot, loaderSources.findAll { it.path.contains('/boot/') }, compilerCp)
        compile('spi', spi, loaderSources.findAll { it.path.contains('/spi/') || it.path.contains('/terminal/') }, compilerCp)
        compile('game', game, (sources + loaderSources.findAll { it.path.contains('/game/') || it.path.contains('/' + task.mode.get() + '/') }).unique(), compilerCp)
        compile('contract', contract, loaderSources.findAll { it.path.contains('/contract/') }, [spi, game].collect { it.absolutePath }.join(File.pathSeparator) + File.pathSeparator + compilerCp)
        run('terminal-contract', [java, '-Dnclskins.wavey.frameContext=' + frameContext, '-Dnclskins.wavey.mode=' + task.mode.get(), '-Dnclskins.wavey.extraction=' + !task.submissionPreview.get(), '-cp', ([contract, spi, game] + runtime).collect { it.absolutePath }.join(File.pathSeparator),
                'com.naocraftlab.skins.buildlogic.contract.TerminalContractProbe', game, boot] + existingRuntime,
                0, 'CANONICAL_TERMINAL_CONTRACT_PASS')
        Map<String, byte[]> plugin = [:]
        merge(spi, plugin, { true })
        plugin['META-INF/MANIFEST.MF'] = 'Manifest-Version: 1.0\nFMLModType: LIBRARY\nAutomatic-Module-Name: nclskins.verification.terminals\n\n'.getBytes('UTF-8')
        plugin['META-INF/services/net.neoforged.neoforgespi.transformation.ClassProcessorProvider'] =
                'com.naocraftlab.skins.buildlogic.processor.CapeTerminalProvider\n'.getBytes('UTF-8')
        File runtimeRoot = new File(work, 'runtime'); new File(runtimeRoot, 'mods').mkdirs()
        File pluginJar = new File(runtimeRoot, 'mods/verification-terminals.jar'); archive(pluginJar, plugin)
        Map<String, byte[]> module = [:]
        if (!task.submissionPreview.get()) {
            File extraction = new File(work, 'extraction-terminals'); extraction.mkdirs()
            run('prepare-extraction-terminals', [java, '-cp', ([game] + compilerInputs + runtime).collect { it.absolutePath }.join(File.pathSeparator),
                    'com.naocraftlab.skins.buildlogic.probe.wavey.BindExtractionPublicationInputs', extraction])
            merge(extraction, module, { it == 'com/naocraftlab/skins/buildlogic/probe/wavey/RawPublicationTerminals.class' })
        }
        List<File> ownInputs = runtime.findAll { File input ->
            boolean own = false
            merge(input, [:], { String name ->
                if (name.startsWith('com/naocraftlab/skins/') || input.isDirectory() && name == 'META-INF/neoforge.mods.toml') own = true
                false
            })
            own
        }
        ownInputs.each { merge(it, module, { !it.equals('META-INF/MANIFEST.MF') && !it.equals('module-info.class') }) }
        merge(game, module, { !it.startsWith('com/naocraftlab/skins/buildlogic/probe/Headless')
                && !it.startsWith('com/naocraftlab/skins/buildlogic/terminal/') })
        module['META-INF/MANIFEST.MF'] = 'Manifest-Version: 1.0\nAutomatic-Module-Name: nclskins\n\n'.getBytes('UTF-8')
        if (!module.containsKey('META-INF/neoforge.mods.toml') || !module.containsKey('com/naocraftlab/skins/runtime/WaveyProducerFixture.class')
                || !module.containsKey('com/naocraftlab/skins/runtime/CapeProviderCoordinator.class')) throw new IllegalArgumentException('Production metadata/runtime/fixture module grouping missing')
        if (module.keySet().any { it.startsWith('com/naocraftlab/skins/buildlogic/boot/') || it.startsWith('com/naocraftlab/skins/buildlogic/processor/') })
            throw new IllegalArgumentException('BOOT/SPI copied into GAME')
        File moduleJar = new File(runtimeRoot, 'mods/nclskins-verification.jar'); archive(moduleJar, module)
        List<Map> profiles = [[name: task.guardOnly.get() ? 'guards' : 'positive', root: runtimeRoot, module: moduleJar, negativeOnly: false]]
        if (!task.guardOnly.get()) {
        Map<String, byte[]> missingWorld = new LinkedHashMap<>(module)
        String worldConfig = 'nclskins.player-skin-providers.mixins.json'
        Map worldDefinition = new groovy.json.JsonSlurper().parseText(new String(module[worldConfig], 'UTF-8')) as Map
        int originalClientCount = worldDefinition.client.size()
        worldDefinition.client = worldDefinition.client.findAll { !it.toString().endsWith('AvatarRendererMaterialMixin') }
        if (originalClientCount - worldDefinition.client.size() != 1) throw new IllegalArgumentException('Exact missing world publication definition')
        missingWorld[worldConfig] = groovy.json.JsonOutput.toJson(worldDefinition).getBytes('UTF-8')
        Map<String, Map<String, byte[]>> negatives = ['missing-world': missingWorld]
        if (!task.submissionPreview.get()) {
            Map<String, byte[]> missingPreview = new LinkedHashMap<>(module)
            String owner = 'com/naocraftlab/skins/compat/client/identifier/extraction/AvatarRenderStatePreviewRenderer.class'
            def node = new org.objectweb.asm.tree.ClassNode()
            new org.objectweb.asm.ClassReader(module[owner]).accept(node, 0)
            int removed = 0
            node.methods.each { method -> method.instructions.toArray().each { instruction ->
                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode
                        && instruction.owner == 'com/naocraftlab/skins/compat/client/identifier/CapeMaterialSnapshot$Carrier'
                        && instruction.name == 'nclskins$setCapeMaterial'
                        && instruction.desc == '(Lcom/naocraftlab/skins/compat/client/identifier/CapeMaterialSnapshot;)V') {
                    method.instructions.set(instruction, new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.POP2)); removed++
                }
            } }
            if (removed != 1) throw new IllegalArgumentException('Exact missing extraction publication definition')
            def writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS); node.accept(writer)
            missingPreview[owner] = writer.toByteArray()
            negatives['missing-extraction'] = missingPreview
        }
        negatives.each { String name, Map<String, byte[]> definition ->
            File root = new File(work, 'profiles/' + name), mods = new File(root, 'mods'); mods.mkdirs()
            File negativeJar = new File(mods, 'nclskins-verification.jar'); archive(negativeJar, definition)
            archive(new File(mods, 'verification-terminals.jar'), plugin)
            profiles.add([name: name, root: root, module: negativeJar, negativeOnly: true])
        }
        }
        receipt.definitionProfiles = profiles.collect { profile -> [name: profile.name, root: profile.root.absolutePath,
                module: profile.module.absolutePath, sha256: java.security.MessageDigest.getInstance('SHA-256').digest(profile.module.bytes).encodeHex().toString(),
                negativeOnly: profile.negativeOnly, state: 'PREPARED_NOT_STARTED'] }
        def descriptors = ModuleFinder.of(moduleJar.toPath(), pluginJar.toPath()).findAll()*.descriptor()
        if (descriptors.size() != 2 || !Collections.disjoint(descriptors[0].packages(), descriptors[1].packages()))
            throw new IllegalArgumentException('SPI/GAME module package overlap')
        runtime.removeAll(ownInputs)
        receipt.preflight = preflight(runtime + [moduleJar, pluginJar])
        receipt.moduleGrouping = [module: moduleJar.absolutePath, runtimeFixtureSameModule: true,
                normalMetadata: true, configs: module.keySet().findAll { it.startsWith('nclskins.') && it.endsWith('.mixins.json') }.sort()]
        File agent = new File(work, 'composition-agent.jar')
        Map<String, byte[]> agentEntries = [:]
        merge(boot, agentEntries, { it.endsWith('/CompositionAgent.class') })
        agentEntries['META-INF/MANIFEST.MF'] = 'Manifest-Version: 1.0\nPremain-Class: com.naocraftlab.skins.buildlogic.boot.CompositionAgent\n\n'.getBytes('UTF-8')
        archive(agent, agentEntries)
        File tmp = new File(work, 'tmp'); tmp.mkdirs()
        List<String> argv = [java, '-javaagent:' + agent.absolutePath, '@' + task.loaderVmArgs.get().asFile.absolutePath,
                '-Djava.awt.headless=true', '-Djava.io.tmpdir=' + tmp.absolutePath, '-Dnclskins.wavey.loaderBatch=true',
                '-Dnclskins.wavey.mode=' + task.mode.get(), '-Dnclskins.wavey.extraction=' + !task.submissionPreview.get(),
                '-Dnclskins.wavey.configs=' + receipt.moduleGrouping.configs.join(','), '-Xmx768m', '-ea',
                '-cp', ([boot] + runtime).collect { it.absolutePath }.join(File.pathSeparator),
                'com.naocraftlab.skins.buildlogic.boot.ClientCapeLauncher', task.loaderProgramArgs.get().asFile.absolutePath, runtimeRoot.absolutePath]
        receipt.loaderInvocation = [argv: argv, cwd: work.absolutePath, maxRuntimeSeconds: 120, implicitWorkers: 0,
                programArgs: task.loaderProgramArgs.get().asFile.text, vmArgs: task.loaderVmArgs.get().asFile.text]
        receipt.loaderInvocations = profiles.collect { profile ->
            List<String> invocation = new ArrayList<>(argv)
            invocation[invocation.size() - 1] = profile.root.absolutePath
            invocation.add(1, '-Dnclskins.wavey.definitionProfile=' + profile.name)
            [name: profile.name, argv: invocation, cwd: work.absolutePath, maxRuntimeSeconds: 120, implicitWorkers: 0]
        }
        receipt.plannedFmlContexts = profiles.size()
        receipt.executedFmlContexts = 0
        if (task.guardOnly.get()) {
            File sdk = task.isolatedSdk.get().asFile
            if (java.security.MessageDigest.getInstance('SHA-512').digest(sdk.bytes).encodeHex().toString() != task.abiSdkPin.get().sha512)
                throw new IllegalArgumentException('Isolated ABI SDK catalog pin mismatch')
            Map inputs = WaveyCompositionGuards.DIRECT_BINDINGS.collectEntries { [(it): receipt[it]] }
            inputs.production = [path: moduleJar.absolutePath, sha256: WaveyCompositionGuards.fileHash(moduleJar)]
            inputs.sdk = [path: sdk.absolutePath, sha256: WaveyCompositionGuards.fileHash(sdk)]
            String binding = WaveyCompositionGuards.bindingHash(inputs)
            File bindings = new File(work, 'isolated-abi.properties')
            Properties properties = new Properties()
            properties.setProperty('binding', binding)
            properties.setProperty('production.path', moduleJar.absolutePath)
            properties.setProperty('production.sha256', inputs.production.sha256)
            properties.setProperty('sdk.path', sdk.absolutePath)
            properties.setProperty('sdk.sha256', inputs.sdk.sha256)
            properties.setProperty('version', task.abiSdkPin.get().version)
            bindings.withWriter('UTF-8') { properties.store(it, null) }
            List<String> abiCommand = [java, '-Xmx512m', '-ea', '-Djava.awt.headless=true',
                    '-Xlog:class+init=info:file=' + new File(work, 'isolated-abi-class-init.log').absolutePath,
                    '-cp', ([game] + runtime).collect { it.absolutePath }.join(File.pathSeparator),
                    'com.naocraftlab.skins.buildlogic.probe.wavey.CapeUnsupportedAbiProbe', '--isolated', bindings.absolutePath]
            receipt.abiProof = [kind: 'isolated-production-abi', state: 'PREPARED_NOT_STARTED', inputs: inputs, binding: binding,
                    invocation: [argv: abiCommand, cwd: work.absolutePath, maxRuntimeSeconds: 120, cleanupSeconds: 5],
                    bindings: [path: bindings.absolutePath, sha256: WaveyCompositionGuards.fileHash(bindings)], executedAbiJvms: 0,
                    classInit: new File(work, 'isolated-abi-class-init.log').absolutePath]
            receipt.proofKinds = ['isolated-production-abi', 'connected-game-guards']
        }
        save()
        if (!task.prepareOnly.get() && task.guardOnly.get()) {
            receipt.abiProof.executedAbiJvms = 1
            save()
            run('isolated-production-abi', receipt.abiProof.invocation.argv, 0, 'ISOLATED_PRODUCTION_ABI_CLOSED_PASS')
            receipt.abiProof.state = 'PASS'
            receipt.abiProof.classInitSha256 = WaveyCompositionGuards.fileHash(new File(receipt.abiProof.classInit))
            WaveyCompositionGuards.verifyAbi(receipt)
            save()
        }
        if (!task.prepareOnly.get()) receipt.loaderInvocations.each { invocation ->
            receipt.executedFmlContexts++
            save()
            run('connected-client-' + invocation.name, invocation.argv, 0, 'CANONICAL_CLIENT_CLOSED_PASS')
            if (invocation.name in ['guards', 'positive']) {
                List<String> required = invocation.name == 'guards' ? WaveyCompositionGuards.CONNECTED : WaveyCompositionGuards.COMPATIBLE
                receipt.guardResults = WaveyCompositionGuards.readResults(
                        new File(work, 'connected-client-' + invocation.name + '.log'), required)
            }
            receipt.definitionProfiles.find { it.name == invocation.name }.state = 'PASS'
            save()
        }
    }
}

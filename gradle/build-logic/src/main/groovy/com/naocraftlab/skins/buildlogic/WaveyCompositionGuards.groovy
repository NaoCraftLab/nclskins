package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.objectweb.asm.*
import org.objectweb.asm.tree.*

final class WaveyCompositionGuards {
    static final List<String> ISOLATED = List.of('partial-registration', 'unsupported-public-abi', 'callback-linkage', 'closed-legacy')
    static final List<String> CONNECTED = List.of('partial-registration', 'callback-linkage', 'closed-legacy')
    static List<String> roster(VerifyWaveyCapesCompositionTask task) { task.runtimeProfile.get().startsWith('direct-client-') ? CONNECTED : ISOLATED }
    static final List<String> COMPATIBLE = List.of('bridge-contract', 'no-support-sensitivity')
    private static final List<String> BINDINGS = List.of('target', 'mode', 'submissionPreview', 'javaRelease',
            'runtimeProfile', 'sources', 'loaderSources', 'mixins', 'compiler', 'java', 'classpath', 'compilerClasspath', 'resourceClasspath')
    static final List<String> DIRECT_BINDINGS = BINDINGS + ['abiSdkPin', 'loaderRuntimeClasspath', 'loaderVmArgs', 'loaderProgramArgs']
    static final List<String> ABI_CELLS = ['supported', 'missing-getCapePasses', 'missing-wrapper']
    private static final String THREAD = 'com/naocraftlab/skins/compat/waveycapes/CapeRenderThread'
    private static final Map MUTATIONS = [
            'missing-getCapePasses': ['dev/tr7zw/waveycapes/render/CapeRenderer', 'getCapePasses', '(Ldev/tr7zw/transition/mc/entitywrapper/PlayerWrapper;)Ljava/util/List;'],
            'missing-wrapper': ['dev/tr7zw/waveycapes/render/CapePass', 'wrapper', '()Ljava/util/function/UnaryOperator;']]

    static String byteHash(byte[] bytes) { MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString() }
    static String fileHash(File file) {
        if (!file.isDirectory()) return byteHash(file.bytes)
        List<File> files = []; file.eachFileRecurse { if (it.isFile()) files.add(it) }
        byteHash(files.sort { file.toPath().relativize(it.toPath()).toString() }.collect {
            file.toPath().relativize(it.toPath()).toString() + '\u0000' + byteHash(it.bytes) + '\n'
        }.join('').getBytes('UTF-8'))
    }
    private static Object canonical(Object value) {
        value instanceof Map ? new TreeMap(value.collectEntries { key, item -> [(key): canonical(item)] })
                : value instanceof List ? value.collect { canonical(it) } : value
    }
    static String bindingHash(Map inputs) { byteHash(JsonOutput.toJson(canonical(inputs)).getBytes('UTF-8')) }

    private static void fileBinding(Map binding, String reason) {
        require(binding?.path instanceof String && binding.sha256 instanceof String, reason)
        File file = new File(binding.path)
        require(file.isFile() && fileHash(file) == binding.sha256, reason)
    }

    private static byte[] transformed(byte[] bytes, List selector, boolean terminal) {
        def node = new ClassNode(); new ClassReader(bytes).accept(node, 0)
        require(node.name == selector[0], 'ABI owner')
        List methods = node.methods.findAll { it.name == selector[1] && it.desc == selector[2] }
        require(methods.size() == 1, 'ABI exact selector')
        def method = methods.first()
        if (terminal) {
            require(method.access == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                    && node.methods.count { it.name == selector[1] } == 1, 'ABI terminal shape')
            method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables?.clear()
            method.instructions.add(new InsnNode(Opcodes.ICONST_1)); method.instructions.add(new InsnNode(Opcodes.IRETURN))
            method.maxStack = 1; method.maxLocals = 0
        } else node.methods.remove(method)
        def writer = new ClassWriter(0); node.accept(writer); writer.toByteArray()
    }

    static void verifyAbi(Map receipt) {
        require(receipt.proofKinds == ['isolated-production-abi', 'connected-game-guards'], 'mandatory proof kinds')
        Map proof = receipt.abiProof as Map
        require(proof?.kind == 'isolated-production-abi' && proof.state == 'PASS' && proof.executedAbiJvms == 1, 'ABI not executed')
        require(proof.inputs instanceof Map && proof.binding == bindingHash(proof.inputs), 'ABI input binding')
        DIRECT_BINDINGS.each { require(receipt.containsKey(it) && proof.inputs[it] == receipt[it], 'ABI input mismatch: ' + it) }
        ['production', 'sdk'].each { fileBinding(proof.inputs[it] as Map, 'ABI stale artifact') }
        fileBinding(proof.bindings as Map, 'ABI bindings file')
        Properties properties = new Properties(); new File(proof.bindings.path).withReader('UTF-8') { properties.load(it) }
        require(properties.getProperty('binding') == proof.binding && properties.getProperty('version') == receipt.abiSdkPin.version,
                'ABI bindings content')
        ['production', 'sdk'].each { key -> require(properties.getProperty(key + '.path') == proof.inputs[key].path
                && properties.getProperty(key + '.sha256') == proof.inputs[key].sha256, 'ABI bindings artifact') }
        List commands = receipt.commands.findAll { it.name == 'isolated-production-abi' }
        require(commands.size() == 1, 'ABI invocation roster')
        Map command = commands.first()
        require(command.exit == 0 && command.expectedExit == 0 && command.reaped == true && command.failureClass == null
                && command.marker == 'ISOLATED_PRODUCTION_ABI_CLOSED_PASS' && command.argv == proof.invocation.argv,
                'ABI process not successful/closed')
        require(!command.argv.any { it.startsWith('-javaagent:') || it.startsWith('--add-opens') || it.startsWith('--add-exports') }
                && command.argv.contains('--isolated'), 'ABI forbidden bootstrap')
        fileBinding([path: command.log, sha256: command.sha256], 'ABI log binding')
        fileBinding([path: proof.classInit, sha256: proof.classInitSha256], 'ABI class-init binding')
        require(!new File(proof.classInit).readLines('UTF-8').any { it ==~ /.*Initializing '(net\/minecraft\/|com\/mojang\/|net\/neoforged\/|org\/lwjgl\/).*/ }, 'ABI engine initialized')
        List<String> lines = new File(command.log).readLines('UTF-8')
        require(lines.findAll { it.startsWith('ABI_BINDING_PASS ') } == ['ABI_BINDING_PASS ' + proof.binding]
                && lines.count('ISOLATED_PRODUCTION_ABI_CLOSED_PASS') == 1, 'ABI binding/close marker')
        ['ABI_CELL_PASS ', 'ABI_CELL_CLEANUP_PASS ', 'ABI_CELL_CLOSED '].each { prefix ->
            require(lines.findAll { it.startsWith(prefix) } == ABI_CELLS.collect { prefix + it }, 'ABI cell roster/cleanup')
        }
        List lifecycle = ABI_CELLS.collectMany { cell -> ['ABI_CELL_PASS ', 'ABI_CELL_CLEANUP_PASS ', 'ABI_CELL_CLOSED '].collect { it + cell } }
        require(lines.first() == 'ABI_BINDING_PASS ' + proof.binding && lines.last() == 'ISOLATED_PRODUCTION_ABI_CLOSED_PASS'
                && lines.findAll { it.startsWith('ABI_CELL_') } == lifecycle, 'ABI receipt ordering')
        ABI_CELLS.eachWithIndex { cell, index ->
            int start = index == 0 ? 0 : lines.indexOf('ABI_CELL_CLOSED ' + ABI_CELLS[index - 1])
            int end = lines.indexOf('ABI_CELL_PASS ' + cell)
            List events = lines.findAll { it.startsWith('ABI_') && it.split(' ').size() > 1 && it.split(' ')[1] == cell }
            require(events.every { lines.indexOf(it) > start && (it.startsWith('ABI_CELL_') || lines.indexOf(it) < end) }, 'ABI cell event ordering')
            require(lines.indexOf('ABI_CELL_CLEANUP_PASS ' + cell) == end + 1
                    && lines.indexOf('ABI_CELL_CLOSED ' + cell) == end + 2, 'ABI cleanup/close ordering')
        }
        require(lines.findAll { it.startsWith('ABI_MISSING_MEMBER_PASS ') } == MUTATIONS.keySet().collect { 'ABI_MISSING_MEMBER_PASS ' + it }, 'ABI missing members')
        List<String> linkage = lines.findAll { it.startsWith('ABI_PRODUCTION_LINKAGE_PASS ') }
        require(linkage.size() == 2 && linkage[0] == 'ABI_PRODUCTION_LINKAGE_PASS missing-getCapePasses NoSuchMethodError dev.tr7zw.waveycapes.render.CapeRenderer.getCapePasses(dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper)'
                && linkage[1] == 'ABI_PRODUCTION_LINKAGE_PASS missing-wrapper NoSuchMethodError dev.tr7zw.waveycapes.render.CapePass.wrapper()', 'ABI production linkage')
        Map<String, byte[]> production = [:], sdk = [:], nested = [:]
        Map<String, String> nestedOrigins = [:], nestedHashes = [:]
        new ZipFile(proof.inputs.production.path).withCloseable { zip -> zip.entries().each { e -> if (e.name.endsWith('.class')) production[e.name] = zip.getInputStream(e).bytes } }
        new ZipFile(proof.inputs.sdk.path).withCloseable { zip -> zip.entries().each { e ->
            if (e.name.endsWith('.class')) sdk[e.name] = zip.getInputStream(e).bytes
            if (e.name.endsWith('.jar')) {
                byte[] carrier = zip.getInputStream(e).bytes; nestedHashes[e.name] = byteHash(carrier)
                new ZipInputStream(new ByteArrayInputStream(carrier)).withCloseable { input ->
                    for (def x = input.nextEntry; x != null; x = input.nextEntry) if (x.name.endsWith('.class')) {
                        require(!nested.containsKey(x.name), 'ABI nested ambiguity')
                        nested[x.name] = input.readAllBytes(); nestedOrigins[x.name] = e.name
                    }
                }
            }
        } }
        require(nestedHashes.keySet().any { it.contains('TRansition') } && nestedHashes.keySet().any { it.contains('TRender') }, 'ABI genuine companions')
        List<String> expectedMutations = [], expectedTerminals = []
        ABI_CELLS.each { cell ->
            require(lines.count { it.startsWith('ABI_NESTED ' + cell + ' ') } == nestedHashes.size()
                    && lines.findAll { it.startsWith('ABI_NESTED ' + cell + ' ') }.toSet()
                    == nestedHashes.collect { k, v -> 'ABI_NESTED ' + cell + ' ' + k + ' ' + v }.toSet(), 'ABI nested binding')
            byte[] thread = production[THREAD + '.class']
            if (cell != 'missing-getCapePasses') expectedTerminals.add('ABI_TERMINAL ' + cell + ' ' + THREAD + ' isClientThread ()Z 1 ' + byteHash(thread) + ' '
                    + byteHash(transformed(thread, [THREAD, 'isClientThread', '()Z'], true)))
            if (MUTATIONS.containsKey(cell)) {
                List selector = MUTATIONS[cell]; byte[] original = sdk[selector[0] + '.class']
                expectedMutations.add('ABI_MUTATION ' + cell + ' ' + selector.join(' ') + ' 1 ' + byteHash(original) + ' ' + byteHash(transformed(original, selector, false)))
            }
            List definitions = lines.findAll { it.startsWith('ABI_DEFINE ' + cell + ' ') }.collect { it.split(' ') as List }
            require(definitions*.getAt(2).toSet().size() == definitions.size(), 'ABI duplicate definition')
            List requiredDefinitions = ['com.naocraftlab.skins.compat.waveycapes.WaveyCapesIntegration',
                    'com.naocraftlab.skins.compat.waveycapes.WaveyCapesSupport', 'dev.tr7zw.waveycapes.support.SupportManager',
                    'dev.tr7zw.waveycapes.render.CapeRenderer', 'dev.tr7zw.waveycapes.render.CapePass']
            if (cell != 'missing-wrapper') requiredDefinitions.add('dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper')
            if (cell != 'missing-getCapePasses') requiredDefinitions.add(THREAD.replace('/', '.'))
            requiredDefinitions.each { required ->
                require(definitions.any { it[2] == required }, 'ABI missing actual definition')
            }
            require(cell != 'missing-getCapePasses' || !definitions.any { it[2] == THREAD.replace('/', '.') }, 'ABI unreachable terminal')
            definitions.each { definition ->
                require(definition.size() == 6, 'ABI definition shape')
                String resource = definition[2].replace('.', '/') + '.class'
                boolean own = resource.startsWith('com/naocraftlab/skins/')
                require(!(sdk.containsKey(resource) && nested.containsKey(resource)), 'ABI ambiguous definition')
                byte[] original = own ? production[resource] : sdk[resource] ?: nested[resource]
                require(original != null && definition[4] == byteHash(original), 'ABI definition provenance')
                require(definition[3] == (own ? 'production' : sdk.containsKey(resource) ? 'sdk' : 'nested:' + nestedOrigins[resource]), 'ABI definition carrier')
                byte[] chosen = resource == THREAD + '.class' ? transformed(original, [THREAD, 'isClientThread', '()Z'], true)
                        : MUTATIONS.containsKey(cell) && resource == MUTATIONS[cell][0] + '.class' ? transformed(original, MUTATIONS[cell], false) : original
                require(definition[5] == byteHash(chosen), 'ABI chosen definition')
            }
        }
        require(lines.findAll { it.startsWith('ABI_TERMINAL ') } == expectedTerminals
                && lines.findAll { it.startsWith('ABI_MUTATION ') } == expectedMutations, 'ABI exact mutation/terminal roster')
        List prefixes = ['ABI_BINDING_PASS ', 'ABI_CELL_PASS ', 'ABI_CELL_CLEANUP_PASS ', 'ABI_CELL_CLOSED ',
                'ABI_MISSING_MEMBER_PASS ', 'ABI_PRODUCTION_LINKAGE_PASS ', 'ABI_NESTED ', 'ABI_TERMINAL ', 'ABI_MUTATION ', 'ABI_DEFINE ']
        require(lines.findAll { it.startsWith('ABI_') }.every { line ->
            prefixes.any { line.startsWith(it) } && (line.startsWith('ABI_BINDING_PASS ') || ABI_CELLS.contains(line.split(' ')[1]))
        }, 'ABI extra record/cell')
    }

    private static void require(boolean value, String reason) {
        if (!value) throw new IllegalStateException('Wavey guard admission: ' + reason)
    }

    static Map<String, String> readResults(File log, List<String> roster) {
        require(log.isFile(), 'missing guard log')
        List<String> lines = log.readLines('UTF-8')
        List<String> observed = lines.findAll { it.startsWith('CANONICAL_CASE_PASS guard-') }
                .collect { it.substring('CANONICAL_CASE_PASS guard-'.length()) }
        ['CANONICAL_CASE_PASS guard-', 'CANONICAL_CASE_RESET_PASS guard-'].each { String prefix ->
            List<String> names = lines.findAll { it.startsWith(prefix) }.collect { it.substring(prefix.length()) }
            require(names.size() == roster.size() && names.toSet() == roster.toSet(), 'guard result roster')
        }
        require(lines.findAll { it.startsWith('CANONICAL_CASE_PASS guard-') || it.startsWith('CANONICAL_CASE_RESET_PASS guard-') } == observed.collectMany {
            ['CANONICAL_CASE_PASS guard-' + it, 'CANONICAL_CASE_RESET_PASS guard-' + it]
        }, 'guard PASS/RESET ordering')
        if (roster.contains('bridge-contract')) {
            require(lines.findAll { it.startsWith('CANONICAL_CASE_PASS ') || it.startsWith('CANONICAL_CASE_RESET_PASS ') }.last()
                    == 'CANONICAL_CASE_RESET_PASS guard-bridge-contract', 'bridge must be last')
        } else require(observed == roster, 'guard case ordering')
        roster.collectEntries { [(it): 'PASS'] }
    }

    private static void requireResults(Map receipt, List<String> roster) {
        require(receipt.guardResults instanceof Map && receipt.guardResults.keySet() == roster.toSet()
                && receipt.guardResults.values().every { it == 'PASS' }, 'incomplete guard results')
    }

    static void verifyEvidence(Map receipt, List<String> roster, String profile) {
        requireResults(receipt, roster)
        List expected = profile == 'guards' ? ['guards'] : receipt.mode in ['rendered-buffer', 'mesh'] ? ['positive'] : receipt.submissionPreview ? ['positive', 'missing-world'] : ['positive', 'missing-world', 'missing-extraction']
        require(receipt.definitionProfiles instanceof List && receipt.definitionProfiles*.name == expected
                && receipt.definitionProfiles.every { it.state == 'PASS' }
                && receipt.executedFmlContexts == expected.size(), 'profile not executed')
        require(receipt.commands instanceof List, 'connected invocation roster')
        List commands = receipt.commands.findAll { it.name instanceof String && it.name.startsWith('connected-client-') }
        List expectedCommands = receipt.definitionProfiles.collect { 'connected-client-' + it.name }
        require(commands*.name == expectedCommands, 'connected invocation roster')
        receipt.definitionProfiles.each { Map definition ->
            Map command = commands.find { it.name == 'connected-client-' + definition.name }
            require(command.exit == 0 && command.expectedExit == 0 && command.reaped == true
                    && command.failureClass == null && command.marker == 'CANONICAL_CLIENT_CLOSED_PASS', 'guard process not successful/closed')
            require(command.log instanceof String, 'guard log binding')
            File log = new File(command.log)
            require(log.isFile() && MessageDigest.getInstance('SHA-256').digest(log.bytes).encodeHex().toString() == command.sha256, 'guard log binding')
            List<String> lines = log.readLines('UTF-8')
            require(lines.count('CANONICAL_CLIENT_CLOSED_PASS') == 1, 'loader close not observed')
            if (definition.name in ['positive', 'guards']) {
                require(readResults(log, roster) == receipt.guardResults, 'guard result/log mismatch')
            } else {
                readResults(log, [])
                ['CANONICAL_EXPECTED_BEHAVIOR_RED ', 'CANONICAL_CASE_PASS ', 'CANONICAL_CASE_RESET_PASS '].each { String prefix ->
                    require(lines.findAll { it.startsWith(prefix) } == [prefix + definition.name], 'definition result/log mismatch')
                }
                require(lines.findAll { it.startsWith('CANONICAL_EXPECTED_BEHAVIOR_RED ') || it.startsWith('CANONICAL_CASE_') }
                        == ['CANONICAL_EXPECTED_BEHAVIOR_RED ' + definition.name, 'CANONICAL_CASE_PASS ' + definition.name,
                            'CANONICAL_CASE_RESET_PASS ' + definition.name], 'definition PASS/RESET ordering')
            }
            require(lines.last() == 'CANONICAL_CLIENT_CLOSED_PASS', 'loader close ordering')
        }
    }

    static void complete(VerifyWaveyCapesCompositionTask task, Map receipt) {
        if (!task.prepareOnly.get()) requireResults(receipt, task.guardOnly.get() ? roster(task) : COMPATIBLE)
        if (task.guardOnly.get()) {
            require(task.name == 'verifyWaveyCapesGuards' && task.guardRoster.get() == roster(task), 'isolated roster/wiring')
        } else {
            def stage = task.project.tasks.findByName('verifyWaveyCapesGuards')
            require(stage instanceof VerifyWaveyCapesCompositionTask && stage.guardOnly.get()
                    && stage.guardRoster.get() == roster(task), 'missing mandatory stage/edge')
            require(task.taskDependencies.getDependencies(task).contains(stage), 'missing stage dependency')
            require(stage.prepareOnly.get() == task.prepareOnly.get() && stage.mode.get() == task.mode.get()
                    && stage.targetId.get() == task.targetId.get() && stage.runtimeProfile.get() == task.runtimeProfile.get(), 'stage profile mismatch')
            require(stage.probeSources.files == task.probeSources.files && stage.loaderSources.files == task.loaderSources.files, 'guard source closure mismatch')
            require(task.guardReceipt.isPresent() && task.guardReceipt.get().asFile == stage.workDirectory.file('result.json').get().asFile,
                    'missing/mismatched guard receipt edge')
            require(task.guardReceipt.get().asFile.isFile(), 'missing guard receipt')
            Map isolated = new JsonSlurper().parse(task.guardReceipt.get().asFile) as Map
            (roster(task) == CONNECTED ? DIRECT_BINDINGS : BINDINGS).each { String key -> require(receipt.containsKey(key) && receipt[key] == isolated[key], 'stage input mismatch: ' + key) }
            require(isolated.guardOnly == true && isolated.guardRoster == roster(task), 'stage receipt roster')
            if (task.prepareOnly.get()) require(isolated.result == 'PREPARED_NOT_STARTED' && isolated.executedFmlContexts == 0, 'prepare stage executed')
            else {
                require(isolated.result == 'PASS', 'stage not passed')
                verifyEvidence(isolated, roster(task), 'guards')
                if (roster(task) == CONNECTED) verifyAbi(isolated)
            }
        }
        if (task.prepareOnly.get()) {
            require(receipt.executedFmlContexts == 0 && !receipt.guardResults, 'prepare has runtime results')
            if (task.guardOnly.get() && roster(task) == CONNECTED) {
                require(receipt.proofKinds == ['isolated-production-abi', 'connected-game-guards']
                        && receipt.abiProof?.kind == 'isolated-production-abi'
                        && receipt.abiProof.state == 'PREPARED_NOT_STARTED' && receipt.abiProof.executedAbiJvms == 0
                        && !receipt.commands?.any { it.name == 'isolated-production-abi' || it.name.startsWith('connected-client-') }, 'prepare ABI executed/missing')
            }
            receipt.guardStatus = 'PREPARED_NOT_STARTED'
        } else {
            verifyEvidence(receipt, task.guardOnly.get() ? roster(task) : COMPATIBLE, task.guardOnly.get() ? 'guards' : 'positive')
            if (task.guardOnly.get() && roster(task) == CONNECTED) verifyAbi(receipt)
            receipt.guardStatus = 'PASS'
        }
    }
}

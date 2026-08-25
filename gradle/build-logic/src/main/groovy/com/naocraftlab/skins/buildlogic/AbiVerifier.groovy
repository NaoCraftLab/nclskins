package com.naocraftlab.skins.buildlogic

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.regex.Pattern

final class AbiVerifier {
    static final Set<String> MEMBER_MODIFIERS = ['public', 'protected', 'private', 'abstract', 'static', 'final', 'transient', 'volatile', 'synchronized', 'native', 'strictfp', 'default'] as Set
    static final Pattern INVOCATION = Pattern.compile('^\\s*\\d+:\\s+(invokevirtual|invokestatic|invokeinterface|invokespecial)\\s+#\\d+(?:,\\s*\\d+)?\\s+//\\s+(?:InterfaceMethod|Method)\\s+([^:]+):(\\S+)\\s*$')

    static Map resolve(Map catalog, Map abi, String targetId, String classpath, File javap) {
        Map target = CatalogTools.selectTarget(catalog, targetId)
        Map declarations = catalog.capabilityImplementations as Map
        Set<String> selected = (target.capabilities as Map)
                .findAll { Object key, Object ignored ->
                    !CatalogTools.EXTERNAL_ABI_CAPABILITIES.contains(key.toString())
                }.values().collect { declarations[it].abiImplementation.toString() } as Set
        Map result = new TreeMap()
        Map<String, Map> cache = [:]
        selected.each { String implementation ->
            Map entry = abi.implementations[implementation] as Map
            if (entry == null) throw new IllegalStateException("${targetId} selects ${implementation} without a declared ABI surface")
            Set<String> declaredNames = (entry.classes as List).collect { it.name.toString() } as Set
            (entry.mixinAnchors ?: []).each { Map anchor -> if (!declaredNames.contains(anchor.owner.toString())) throw new IllegalStateException("${targetId}/${implementation}: Mixin anchor owner ${anchor.owner} has no declared class surface") }
            Map classHashes = new TreeMap()
            (entry.classes as List).each { Map classEntry ->
                String declaredName = classEntry.name.toString()
                Map surface = resolveClass(javap, classpath, declaredName, cache)
                requireEqual(targetId, implementation, declaredName, 'class access', classEntry.access, surface.access)
                requireEqual(targetId, implementation, declaredName, 'class finality', classEntry.finality, surface.finality)
                List<Map> selectedMembers = []
                (classEntry.members ?: []).each { Map expected ->
                    if (expected.kind == 'class') {
                        String nestedName = declaredName + '$' + expected.name.toString().replace('.', '$')
                        String descriptor = 'L' + nestedName.replace('.', '/') + ';'
                        requireEqual(targetId, implementation, nestedName, 'nested class descriptor', expected.descriptor, descriptor)
                        Map nested = resolveClass(javap, classpath, nestedName, cache)
                        requireEqual(targetId, implementation, nestedName, 'class access', expected.access, nested.access)
                        requireEqual(targetId, implementation, nestedName, 'class finality', expected.finality, nested.finality)
                        classHashes[nestedName] = selectedHash(nestedName, nested, [], [])
                        return
                    }
                    List<Map> identities = (surface.members as List).findAll { Map actual -> actual.kind == expected.kind && actual.name == expected.name && actual.descriptor == expected.descriptor }
                    List<Map> exact = identities.findAll { Map actual -> actual.access == expected.access && actual.finality == expected.finality }
                    if (exact.size() != 1) {
                        List<Map> candidates = (surface.members as List).findAll { Map actual -> actual.name == expected.name || (expected.kind == 'constructor' && actual.kind == 'constructor') }
                        throw new IllegalStateException("${targetId}/${implementation}/${declaredName}: declared ${diagnostic(expected)} does not match; actual candidates: ${candidates.collect { diagnostic(it) }.join('; ') ?: 'none'}")
                    }
                    selectedMembers.add(exact.first())
                }
                List<String> anchors = resolveAnchors(javap, classpath, implementation, entry, declaredName, surface)
                classHashes[declaredName] = selectedHash(declaredName, surface, selectedMembers, anchors)
            }
            result[implementation] = classHashes
        }
        result
    }

    static void verify(Map catalog, Map abi, String targetId, Map actual) {
        Map target = CatalogTools.selectTarget(catalog, targetId)
        String profile = target.epochProfile.toString()
        Map profileBaseline = abi.resolvedByProfile[profile] as Map
        if (profileBaseline == null) throw new IllegalStateException("no resolved ABI baseline for API profile ${profile}")
        Map declarations = catalog.capabilityImplementations as Map
        Set<String> selected = (target.capabilities as Map)
                .findAll { Object key, Object ignored ->
                    !CatalogTools.EXTERNAL_ABI_CAPABILITIES.contains(key.toString())
                }.values().collect { declarations[it].abiImplementation.toString() } as Set
        Map expected = new TreeMap()
        selected.each { String implementation ->
            if (!profileBaseline.containsKey(implementation)) throw new IllegalStateException("API profile ${profile} lacks selected ABI implementation ${implementation} for ${targetId}")
            expected[implementation] = profileBaseline[implementation]
        }
        if (expected == actual) return
        List<String> differences = []
        ((expected.keySet() + actual.keySet()) as Set).sort().each { String implementation ->
            Map expectedClasses = expected[implementation] as Map ?: [:]
            Map actualClasses = actual[implementation] as Map ?: [:]
            ((expectedClasses.keySet() + actualClasses.keySet()) as Set).sort().each { String name ->
                if (expectedClasses[name] != actualClasses[name]) differences.add("${implementation}/${name}: expected ${expectedClasses[name]}, got ${actualClasses[name]}")
            }
        }
        throw new IllegalStateException("declared capability ABI baseline changed for ${targetId}:\n  " + differences.join('\n  '))
    }

    static Map resolveClass(File javap, String classpath, String name, Map<String, Map> cache) {
        if (cache.containsKey(name)) return cache[name]
        List<String> failures = []
        for (String candidate : candidates(name)) {
            ProcessResult basic = run([javap.absolutePath, '-p', '-s', '-classpath', classpath, candidate])
            if (basic.exit == 0 && !basic.output.isBlank()) {
                String resolvedNestedAccess = null
                if (candidate.contains('$')) {
                    ProcessResult verbose = run([javap.absolutePath, '-p', '-s', '-v', '-classpath', classpath, candidate])
                    if (verbose.exit != 0) throw new IllegalStateException("cannot inspect nested-class flags for ${candidate}: ${verbose.output.trim()}")
                    resolvedNestedAccess = nestedAccess(verbose.output, candidate)
                    if (resolvedNestedAccess == null) throw new IllegalStateException("cannot find InnerClasses access flags for ${candidate}")
                }
                Map parsed = parseJavap(normalize(basic.output), candidate, resolvedNestedAccess)
                cache[name] = parsed
                return parsed
            }
            failures.add("${candidate}: ${basic.output.trim()}")
        }
        throw new IllegalStateException("cannot resolve ${name} with javap; attempts: ${failures.join('; ')}")
    }

    static List<String> resolveAnchors(File javap, String classpath, String implementation, Map entry, String declaredName, Map surface) {
        List<String> canonical = []
        (entry.mixinAnchors ?: []).findAll { it.owner == declaredName }.each { Map anchor ->
            String kind = anchor.kind.toString()
            if (kind == 'interface-injection') {
                List<String> expectedInterfaces = anchor.containsKey('targetInterfaces')
                        ? (anchor.targetInterfaces as List).collect { it.toString() }.sort()
                        : null
                if (expectedInterfaces != null && (surface.interfaces as List).sort() != expectedInterfaces) throw new IllegalStateException("${implementation}/${declaredName}: direct target interfaces differ from interface-injection declaration")
                List<String> injected = (anchor.members as List).collect { "${it.name}${it.descriptor}" }.sort()
                List conflicts = (surface.members as List).findAll { it.kind == 'method' && injected.contains("${it.name}${it.descriptor}") }
                if (!conflicts.isEmpty()) throw new IllegalStateException("${implementation}/${declaredName}: target already declares interface-injection members")
                canonical.add(['anchor', 'interface-injection', declaredName, anchor.interface, 'target-interfaces=' + (expectedInterfaces == null ? '*' : expectedInterfaces.join(',')), 'members=' + injected.join(',')].join('|'))
                return
            }
            String method = anchor.method.toString()
            String descriptor = anchor.descriptor.toString()
            List methods = (surface.members as List).findAll { it.kind == 'method' && it.name == method && it.descriptor == descriptor }
            if (methods.size() != 1) throw new IllegalStateException("${implementation}/${declaredName}: ${kind} anchor method ${method}${descriptor} is absent or ambiguous")
            Map code = bytecode(javap, classpath, surface, method, descriptor)
            if (!code.hasCode) throw new IllegalStateException("${implementation}/${declaredName}: ${kind} anchor method has no Code attribute")
            if (kind == 'head') {
                canonical.add(['anchor', 'head', declaredName, method, descriptor].join('|'))
                return
            }
            if (kind != 'invoke') throw new IllegalStateException("unsupported Mixin anchor kind ${kind}")
            List matches = (code.invocations as List).findAll { Map invocation -> invocation.opcode == anchor.opcode && invocation.owner == anchor.targetOwner && invocation.name == anchor.targetMethod && invocation.descriptor == anchor.targetDescriptor }
            if (matches.size() != (anchor.count as int)) throw new IllegalStateException("${implementation}/${declaredName}: expected ${anchor.count} invocation(s), found ${matches.size()}")
            canonical.add(['anchor', 'invoke', declaredName, method, descriptor, anchor.opcode, anchor.targetOwner, anchor.targetMethod, anchor.targetDescriptor, "count=${matches.size()}"].join('|'))
        }
        canonical
    }

    static Map bytecode(File javap, String classpath, Map surface, String method, String descriptor) {
        ProcessResult result = run([javap.absolutePath, '-p', '-s', '-c', '-classpath', classpath, surface.name.toString()])
        if (result.exit != 0) throw new IllegalStateException("cannot inspect bytecode for ${surface.name}: ${result.output.trim()}")
        List<String> lines = result.output.replace('\r\n', '\n').readLines()
        String currentName = null
        String currentDescriptor = null
        boolean hasCode = false
        List<Map> invocations = []
        for (int index = 0; index < lines.size(); index++) {
            String line = lines[index].trim()
            if (line.startsWith('descriptor: ')) {
                Map member = parseMember(lines[index - 1].trim(), line.substring('descriptor: '.length()), surface.name.toString(), surface.finality.toString())
                currentName = member?.kind in ['method', 'constructor'] ? member.name : null
                currentDescriptor = member?.descriptor
                continue
            }
            if (currentName != method || currentDescriptor != descriptor) continue
            if (line == 'Code:') hasCode = true
            def match = INVOCATION.matcher(lines[index])
            if (match.matches()) {
                String reference = match.group(2)
                int split = reference.lastIndexOf('.')
                String owner = split > 0
                        ? reference.substring(0, split).replace('/', '.')
                        : surface.name.toString()
                String name = (split > 0 ? reference.substring(split + 1) : reference)
                        .replace('"', '')
                invocations.add([opcode: match.group(1), owner: owner,
                                 name: name, descriptor: match.group(3)])
            }
        }
        [hasCode: hasCode, invocations: invocations]
    }

    static Map parseJavap(String output, String name, String nestedAccess) {
        List<String> lines = output.readLines()
        if (lines.isEmpty()) throw new IllegalStateException("javap returned no declaration for ${name}")
        String header = lines.first()
        def declaration = header =~ /\b(?:class|interface)\b/
        if (!declaration.find() || !header.endsWith('{')) throw new IllegalStateException("cannot parse class declaration for ${name}: ${header}")
        List<String> modifiers = header.substring(0, declaration.start()).tokenize()
        String access = nestedAccess ?: visibility(modifiers)
        String finality = classFinality(header, modifiers)
        List<Map> members = []
        for (int index = 0; index < lines.size(); index++) {
            if (!lines[index].startsWith('descriptor: ')) continue
            if (index == 0) throw new IllegalStateException("descriptor without declaration for ${name}")
            Map member = parseMember(lines[index - 1], lines[index].substring('descriptor: '.length()), name, finality)
            if (member != null) members.add(member)
        }
        [name: name, access: access, finality: finality, members: members, interfaces: directInterfaces(header)]
    }

    static Map parseMember(String statement, String descriptor, String owner, String ownerFinality) {
        if (statement == 'static {};') return null
        List<String> modifiers = []
        for (String token : statement.tokenize()) { if (!MEMBER_MODIFIERS.contains(token)) break; modifiers.add(token) }
        String kind
        String name
        if (statement.contains('(')) {
            String declared = statement.substring(0, statement.indexOf('(')).trim().tokenize().last()
            String simple = owner.substring(owner.lastIndexOf('.') + 1)
            if ([owner, owner.replace('$', '.'), simple, simple.replace('$', '.')].contains(declared)) { kind = 'constructor'; name = '<init>' }
            else { kind = 'method'; name = declared }
        } else { kind = 'field'; name = statement.substring(0, statement.length() - 1).tokenize().last() }
        [kind: kind, name: name, descriptor: descriptor, access: memberAccess(modifiers), finality: memberFinality(kind, modifiers, ownerFinality)]
    }

    static String selectedHash(String name, Map surface, List<Map> members, List<String> anchors) {
        List<String> canonical = ['capability-abi-surface-v2', ['class', name, surface.access, surface.finality].join('|')]
        canonical.addAll(members.sort { Map a, Map b -> memberSortKey(a) <=> memberSortKey(b) }.collect { ['member', it.kind, it.name, it.descriptor, it.access, it.finality].join('|') })
        canonical.addAll(anchors.sort())
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        digest.digest((canonical.join('\n') + '\n').getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
    }

    static List<String> candidates(String name) {
        List<String> result = [name]
        List<String> pieces = name.split('\\.') as List
        for (int split = pieces.size() - 1; split > 0; split--) {
            String candidate = pieces.take(split).join('.') + '$' + pieces.drop(split).join('$')
            if (!result.contains(candidate)) result.add(candidate)
        }
        result
    }

    static String normalize(String output) {
        output.replace('\r\n', '\n').readLines().collect { it.trim().replaceAll('\\s+', ' ') }.findAll { !it.isBlank() && !it.startsWith('Compiled from ') }.join('\n') + '\n'
    }

    static String nestedAccess(String output, String binaryName) {
        int marker = output.lastIndexOf('InnerClasses:')
        if (marker < 0) return null
        String internal = binaryName.replace('.', '/')
        for (String line : output.substring(marker).readLines()) {
            if (!line.contains("=class ${internal}")) continue
            List<String> modifiers = line.split('#')[0].trim().tokenize().findAll { it in ['public', 'protected', 'private', 'static'] }
            if (!modifiers.isEmpty()) return modifiers.join(' ')
        }
        null
    }

    static String visibility(List<String> modifiers) { modifiers.find { it in ['public', 'protected', 'private'] } ?: 'package' }
    static String classFinality(String header, List<String> modifiers) {
        if ((header =~ /\binterface\b/).find()) return header.contains('java.lang.annotation.Annotation') ? 'annotation' : 'interface'
        if (header.contains('extends java.lang.Record')) return 'record'
        if (header.contains('extends java.lang.Enum<')) return 'enum'
        if (modifiers.contains('abstract')) return 'abstract'
        if (modifiers.contains('final')) return 'final'
        'concrete'
    }
    static List<String> directInterfaces(String header) {
        if (!header.contains(' implements ')) return []
        splitGeneric(header.split(' implements ', 2)[1].replaceFirst(/\{$/, '').trim()).collect { eraseGenerics(it) }
    }
    static List<String> splitGeneric(String value) {
        List<String> result = []; StringBuilder current = new StringBuilder(); int depth = 0
        value.toCharArray().each { char character ->
            if (character == '<') depth++
            else if (character == '>') depth--
            if (character == ',' && depth == 0) { result.add(current.toString().trim()); current.setLength(0) }
            else current.append(character)
        }
        if (current.length() > 0) result.add(current.toString().trim())
        result.findAll { !it.isBlank() }
    }
    static String eraseGenerics(String value) {
        StringBuilder result = new StringBuilder(); int depth = 0
        value.toCharArray().each { char character -> if (character == '<') depth++; else if (character == '>') depth--; else if (depth == 0) result.append(character) }
        result.toString().trim()
    }
    static String memberAccess(List<String> modifiers) {
        (modifiers.any { it in ['public', 'protected', 'private'] } ? modifiers : ['package'] + modifiers).join(' ')
    }
    static String memberFinality(String kind, List<String> modifiers, String owner) {
        if (kind == 'constructor') return 'n/a'
        if (kind == 'field') return modifiers.contains('final') ? 'final' : 'mutable'
        if (owner == 'annotation') return 'annotation-member'
        if (modifiers.contains('abstract')) return 'abstract'
        if (modifiers.contains('default')) return 'default'
        if (modifiers.contains('static')) return 'static'
        if (modifiers.contains('final')) return 'final'
        if (modifiers.contains('private')) return 'implementation'
        'virtual'
    }
    static String diagnostic(Map member) { "${member.kind} ${member.name} ${member.descriptor} access=${member.access} finality=${member.finality}" }
    static String memberSortKey(Map member) { [member.kind, member.name, member.descriptor, member.access, member.finality].join('\n') }
    static void requireEqual(String target, String implementation, String name, String label, Object expected, Object actual) { if (expected != actual) throw new IllegalStateException("${target}/${implementation}/${name}: ${label} is ${actual}, declaration says ${expected}") }
    static ProcessResult run(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
        builder.environment().put('LC_ALL', 'C')
        Process process = builder.start()
        String output = process.inputStream.getText('UTF-8')
        new ProcessResult(process.waitFor(), output)
    }
    private AbiVerifier() {}

    static final class ProcessResult {
        final int exit
        final String output
        ProcessResult(int exit, String output) { this.exit = exit; this.output = output }
    }
}

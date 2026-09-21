package com.naocraftlab.skins.buildlogic

import java.nio.file.Files
import java.nio.file.Path

final class ServerPluginAbiVerifier {
    private static final Set<String> ROOT_KEYS = ['schemaVersion', 'bindings'] as Set
    private static final Set<String> BINDING_KEYS = [
            'id', 'adapterSource', 'versions', 'families', 'authlibFamily',
            'profileModel', 'artifactPolicy', 'exactMembers'] as Set
    private static final Set<String> BUKKIT_FAMILIES =
            ['craftbukkit', 'spigot', 'paper', 'purpur', 'folia'] as Set

    static List<String> verify(Path root, Map catalog, Map declaration) {
        List<String> errors = []
        if ((declaration.keySet() as Set) != ROOT_KEYS) {
            errors.add("server plugin ABI declaration must contain exactly ${ROOT_KEYS.sort()}")
        }
        if (declaration.schemaVersion != 1) {
            errors.add('server plugin ABI declaration schemaVersion must be 1')
        }
        List<Map> bindings = declaration.bindings instanceof List
                ? (declaration.bindings as List).findAll { it instanceof Map } as List<Map> : []
        if (bindings.isEmpty() || bindings*.id.toSet().size() != bindings.size()) {
            errors.add('server plugin ABI declaration must define unique binding families')
        }
        bindings.each { Map binding -> verifyBinding(root, binding, errors) }

        Map compatibility = catalog.serverPlugin instanceof Map
                && catalog.serverPlugin.compatibility instanceof Map
                ? catalog.serverPlugin.compatibility as Map : [:]
        Set<String> expectedIdentities = [] as Set
        compatibility.each { Object rawVersion, Object rawFamilies ->
            String version = rawVersion.toString()
            (rawFamilies instanceof List ? rawFamilies : []).each { Object rawFamily ->
                String family = rawFamily.toString()
                if (!BUKKIT_FAMILIES.contains(family)) return
                expectedIdentities.add("${family}-${version}".toString())
                List<Map> matches = bindings.findAll { Map binding ->
                    (binding.versions as List).contains(version)
                            && (binding.families as List).contains(family)
                }
                if (matches.size() != 1) {
                    errors.add("${family}-${version}: expected one exact ABI binding, got ${matches*.id}")
                }
                verifyPinnedRuntime(catalog, version, family, errors)
            }
        }
        bindings.each { Map binding ->
            (binding.versions as List).each { Object version ->
                if (!(binding.families as List).any { Object family ->
                    expectedIdentities.contains("${family}-${version}".toString())
                }) {
                    errors.add("${binding.id}: no catalogued identity for version ${version}")
                }
            }
            (binding.families as List).each { Object family ->
                if (!(binding.versions as List).any { Object version ->
                    expectedIdentities.contains("${family}-${version}".toString())
                }) {
                    errors.add("${binding.id}: no catalogued identity for family ${family}")
                }
            }
        }
        errors
    }

    private static void verifyBinding(Path root, Map binding, List<String> errors) {
        String id = binding.id?.toString()
        if ((binding.keySet() as Set) != BINDING_KEYS) {
            errors.add("${id}: ABI binding must contain exactly ${BINDING_KEYS.sort()}")
            return
        }
        if (!(binding.versions instanceof List) || binding.versions.isEmpty()
                || !(binding.families instanceof List) || binding.families.isEmpty()) {
            errors.add("${id}: versions and families must be non-empty lists")
        }
        if (!(binding.exactMembers instanceof List) || binding.exactMembers.size() < 3
                || binding.exactMembers.toSet().size() != binding.exactMembers.size()) {
            errors.add("${id}: exactMembers must contain at least three unique contracts")
        }
        if (binding.artifactPolicy != 'inspect-when-materialized') {
            errors.add("${id}: artifactPolicy must preserve explicit native materialization status")
        }
        Path source = root.resolve(binding.adapterSource?.toString() ?: '').normalize()
        if (!source.startsWith(root) || !Files.isRegularFile(source)) {
            errors.add("${id}: adapterSource does not exist")
        } else if (!Files.readString(source).contains('super("' + id + '"')) {
            errors.add("${id}: adapterSource does not declare the exact production adapter id")
        }
    }

    private static void verifyPinnedRuntime(
            Map catalog, String version, String family, List<String> errors) {
        List runtimes = catalog.serverPluginRuntimes instanceof List
                ? catalog.serverPluginRuntimes as List : []
        boolean pinned = family == 'craftbukkit' || family == 'spigot'
                ? runtimes.any { it instanceof Map && it.platform == 'buildtools' && it.version == version }
                : runtimes.any { it instanceof Map && it.platform == family && it.version == version }
        if (!pinned) {
            errors.add("${family}-${version}: exact pinned runtime artifact is missing")
        }
    }
}

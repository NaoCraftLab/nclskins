package com.naocraftlab.skins.buildlogic

final class CapabilityReuse {
    static List<Map> candidates(Map catalog, Map target, String capability) {
        if (!CatalogTools.REQUIRED_CAPABILITIES.contains(capability)) {
            throw new IllegalArgumentException("unknown capability: ${capability}")
        }
        Map<String, List<Map>> usages = [:].withDefault { [] }
        (catalog.targets as List).each { Map candidateTarget ->
            String implementation = (candidateTarget.capabilities as Map)[capability]?.toString()
            if (implementation != null) usages[implementation].add(candidateTarget)
        }
        usages.collect { String implementation, List<Map> sourceTargets ->
            List<Map> otherTargets = sourceTargets.findAll { it.id != target.id }
            boolean sameEpoch = otherTargets.any {
                it.minecraft.epoch == target.minecraft.epoch
            }
            [
                    implementation: implementation,
                    selected      : implementation == (target.capabilities as Map)[capability],
                    reused        : !otherTargets.isEmpty(),
                    sameEpoch     : sameEpoch,
                    distance      : otherTargets.isEmpty()
                            ? Integer.MAX_VALUE
                            : otherTargets.collect {
                        versionDistance(
                                target.minecraft.version.toString(),
                                it.minecraft.version.toString())
                    }.min(),
                    sourceTargets : sourceTargets.collect { it.id.toString() }.sort()
            ]
        }.sort { Map left, Map right -> compareCandidates(left, right) }
    }

    static Map inspect(
            File repositoryRoot,
            Map catalog,
            Map abi,
            Map coverage,
            Map target,
            String capability,
            Map candidate) {
        String implementation = candidate.implementation.toString()
        List<String> failures = []
        Map declaration
        try {
            declaration = CatalogTools.capabilityDeclaration(catalog, implementation)
            CatalogTools.resolveBundleOrder(
                    catalog,
                    (catalog.baseBundles as List).collect { it.toString() } +
                            [CatalogTools.accessBundle(catalog, target),
                             CatalogTools.clientProviderBundle(catalog, target),
                             declaration.bundle.toString()])
        } catch (IllegalArgumentException error) {
            failures.add(error.message)
            declaration = [:]
        }
        Map semantic = coverage.implementations instanceof Map
                ? (coverage.implementations as Map)[implementation] as Map
                : null
        if (semantic == null || semantic.capabilityKey != capability) {
            failures.add('missing executable semantic contract')
        }
        String abiId = declaration.abiImplementation?.toString()
        Map abiEntry = abi.implementations instanceof Map
                ? (abi.implementations as Map)[abiId] as Map
                : null
        if (CatalogTools.EXTERNAL_ABI_CAPABILITIES.contains(capability)) {
            String expected = target.id == 'fabric-1.20.1'
                    ? 'modmenu-default-index'
                    : target.loader.id == 'fabric'
                    ? 'modmenu-static-catalog'
                    : 'native-static-catalog'
            if (implementation != expected || abiId != implementation) {
                failures.add('external update integration ABI is incompatible with target')
            }
        } else if (abiEntry == null || abiEntry.kind != capability) {
            failures.add('missing capability ABI declaration')
        }
        Map result = new LinkedHashMap(candidate)
        result.bundle = declaration.bundle
        result.abiImplementation = abiId
        result.semanticSuite = semantic?.sharedSuite
        result.semanticTestIds = semantic?.sharedSuite == null
                ? []
                : (((coverage.sharedSuites as Map)[semantic.sharedSuite] as Map)?.semantics ?: [])
        result.staticStatus = failures.isEmpty() ? 'COMPATIBLE' : 'REJECTED'
        result.failures = failures
        result
    }

    static boolean matchesDeclaredAbi(Map abi, String implementation, Map actual) {
        if (implementation == null || actual == null) return false
        (abi.resolvedByProfile as Map).values().any { Object rawProfiles ->
            rawProfiles instanceof Map && (rawProfiles as Map)[implementation] == actual
        }
    }

    static void requireReuseFirstSelection(
            String targetId, String capability, String selected, String firstAccepted) {
        if (selected != firstAccepted) {
            throw new IllegalStateException(
                    "${targetId}/${capability}: catalog selects ${selected}, but existing " +
                            "${firstAccepted} passes first")
        }
    }

    private static int compareCandidates(Map left, Map right) {
        int result = (left.reused ? 0 : 1) <=> (right.reused ? 0 : 1)
        if (result != 0) return result
        result = (left.sameEpoch ? 0 : 1) <=> (right.sameEpoch ? 0 : 1)
        if (result != 0) return result
        result = (left.distance as int) <=> (right.distance as int)
        result != 0 ? result : left.implementation.toString() <=> right.implementation.toString()
    }

    private static int versionDistance(String left, String right) {
        List<Integer> a = numericParts(left)
        List<Integer> b = numericParts(right)
        int size = Math.max(a.size(), b.size())
        int distance = 0
        for (int index = 0; index < size; index++) {
            int av = index < a.size() ? a[index] : 0
            int bv = index < b.size() ? b[index] : 0
            distance = Math.addExact(Math.multiplyExact(distance, 1000), Math.abs(av - bv))
        }
        distance
    }

    private static List<Integer> numericParts(String version) {
        version.tokenize('.').collect { String part ->
            def match = part =~ /^(\d+)/
            match.find() ? match.group(1).toInteger() : 0
        }
    }

    private CapabilityReuse() {}
}

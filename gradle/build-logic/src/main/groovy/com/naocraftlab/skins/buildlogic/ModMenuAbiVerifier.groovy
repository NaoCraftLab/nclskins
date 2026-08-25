package com.naocraftlab.skins.buildlogic

final class ModMenuAbiVerifier {
    static final Set<String> ROOT_KEYS = ['schemaVersion', 'profiles', 'targets'] as Set
    static final Set<String> PROFILE_KEYS =
            ['classes', 'forbiddenMembers', 'forbiddenClasses'] as Set
    static final Set<String> CLASS_KEYS =
            ['name', 'access', 'finality', 'members'] as Set
    static final Set<String> MEMBER_KEYS =
            ['kind', 'name', 'descriptor', 'access', 'finality'] as Set
    static final Set<String> FORBIDDEN_MEMBER_KEYS =
            ['owner', 'kind', 'name', 'descriptor'] as Set

    static List<String> validate(Map catalog, Map declaration) {
        List<String> errors = []
        if (declaration.schemaVersion != 1 ||
                (declaration.keySet() as Set) != ROOT_KEYS ||
                !(declaration.profiles instanceof Map) ||
                !(declaration.targets instanceof Map)) {
            return ['Mod Menu ABI declaration root differs from schema v1']
        }
        Set<String> fabricTargets = (catalog.targets as List)
                .findAll { Map target -> target.loader.id == 'fabric' }
                .collect { Map target -> target.id.toString() } as Set
        Map targets = declaration.targets as Map
        if ((targets.keySet() as Set) != fabricTargets) {
            errors.add('Mod Menu ABI targets must exactly cover Fabric targets')
        }
        Map profiles = declaration.profiles as Map
        if ((profiles.keySet() as Set) !=
                ['modmenu-default-index', 'modmenu-static-catalog'] as Set) {
            errors.add('Mod Menu ABI profiles differ from supported semantic integrations')
        }
        targets.each { Object targetId, Object profileId ->
            if (!(profileId instanceof String) || !profiles.containsKey(profileId)) {
                errors.add("${targetId}: unknown Mod Menu ABI profile ${profileId}")
            }
        }
        profiles.each { Object profileId, Object rawProfile ->
            if (!(rawProfile instanceof Map) ||
                    ((rawProfile as Map).keySet() as Set) != PROFILE_KEYS) {
                errors.add("${profileId}: Mod Menu ABI profile shape differs from schema")
                return
            }
            Map profile = rawProfile as Map
            if (!(profile.classes instanceof List) || !(profile.classes as List)) {
                errors.add("${profileId}: classes must be non-empty")
            } else {
                (profile.classes as List).each { Object rawClass ->
                    if (!(rawClass instanceof Map) ||
                            ((rawClass as Map).keySet() as Set) != CLASS_KEYS ||
                            !validClass(rawClass as Map)) {
                        errors.add("${profileId}: invalid class surface")
                    }
                }
            }
            if (!(profile.forbiddenMembers instanceof List) ||
                    (profile.forbiddenMembers as List).any { Object raw ->
                        !(raw instanceof Map) ||
                                ((raw as Map).keySet() as Set) != FORBIDDEN_MEMBER_KEYS ||
                                !(raw as Map).values().every { it instanceof String && !it.isBlank() }
                    }) {
                errors.add("${profileId}: invalid forbidden member surface")
            }
            if (!(profile.forbiddenClasses instanceof List) ||
                    (profile.forbiddenClasses as List).any {
                        !(it instanceof String) || it.isBlank()
                    }) {
                errors.add("${profileId}: invalid forbidden class surface")
            }
        }
        errors
    }

    static void verify(
            Map catalog,
            Map declaration,
            String targetId,
            String classpath,
            File javap) {
        List<String> errors = validate(catalog, declaration)
        if (!errors.isEmpty()) {
            throw new IllegalStateException(errors.join('\n'))
        }
        Map target = CatalogTools.selectTarget(catalog, targetId)
        if (target.loader.id != 'fabric') {
            throw new IllegalArgumentException("${targetId} does not use Mod Menu")
        }
        String profileId = (declaration.targets as Map)[targetId]?.toString()
        Map profile = (declaration.profiles as Map)[profileId] as Map
        Map<String, Map> cache = [:]
        (profile.classes as List).each { Map expectedClass ->
            String name = expectedClass.name.toString()
            Map actualClass = AbiVerifier.resolveClass(javap, classpath, name, cache)
            requireEqual(targetId, name, 'class access', expectedClass.access, actualClass.access)
            requireEqual(targetId, name, 'class finality', expectedClass.finality, actualClass.finality)
            (expectedClass.members as List).each { Map expectedMember ->
                List<Map> matches = (actualClass.members as List).findAll { Map actual ->
                    MEMBER_KEYS.every { String key -> actual[key] == expectedMember[key] }
                }
                if (matches.size() != 1) {
                    throw new IllegalStateException(
                            "${targetId}/${name}: exact Mod Menu ABI member differs: " +
                                    "${expectedMember.name}${expectedMember.descriptor}")
                }
            }
        }
        (profile.forbiddenMembers as List).each { Map forbidden ->
            Map owner = AbiVerifier.resolveClass(
                    javap, classpath, forbidden.owner.toString(), cache)
            List matches = (owner.members as List).findAll { Map actual ->
                actual.kind == forbidden.kind && actual.name == forbidden.name &&
                        actual.descriptor == forbidden.descriptor
            }
            if (!matches.isEmpty()) {
                throw new IllegalStateException(
                        "${targetId}/${forbidden.owner}: forbidden Mod Menu ABI member exists: " +
                                "${forbidden.name}${forbidden.descriptor}")
            }
        }
        (profile.forbiddenClasses as List).each { Object rawName ->
            String name = rawName.toString()
            try {
                AbiVerifier.resolveClass(javap, classpath, name, cache)
                throw new IllegalStateException(
                        "${targetId}: forbidden Mod Menu ABI class exists: ${name}")
            } catch (IllegalStateException error) {
                if (error.message == "${targetId}: forbidden Mod Menu ABI class exists: ${name}") {
                    throw error
                }
            }
        }
    }

    private static boolean validClass(Map entry) {
        entry.name instanceof String && !entry.name.isBlank() &&
                entry.access instanceof String && !entry.access.isBlank() &&
                entry.finality instanceof String && !entry.finality.isBlank() &&
                entry.members instanceof List && !(entry.members as List).isEmpty() &&
                (entry.members as List).every { Object raw ->
                    raw instanceof Map && ((raw as Map).keySet() as Set) == MEMBER_KEYS &&
                            (raw as Map).values().every { it instanceof String && !it.isBlank() }
                }
    }

    private static void requireEqual(
            String targetId, String owner, String subject, Object expected, Object actual) {
        if (expected != actual) {
            throw new IllegalStateException(
                    "${targetId}/${owner}: ${subject} expected ${expected}, got ${actual}")
        }
    }

    private ModMenuAbiVerifier() {}
}

package com.naocraftlab.skins.buildlogic

final class UpdateCatalogSite {
    static Map catalog(Map catalog, List<Map> inventory) {
        List<Map> eligibleTargets = CatalogTools.releaseTargets(catalog)
                .sort { Map left, Map right -> left.id.toString() <=> right.id.toString() }
        Set<String> eligibleIds = eligibleTargets.collect { it.id.toString() }.toSet()
        List<Map> associatedReleases = inventory.findAll { Map release ->
            !(release.targetIds as Collection).toSet().disjoint(eligibleIds)
        }.sort { Map left, Map right ->
            NclReleaseVersion.parse(left.version.toString()) <=>
                    NclReleaseVersion.parse(right.version.toString())
        }

        Map<String, Object> releases = new LinkedHashMap<>()
        associatedReleases.each { Map release ->
            NclReleaseVersion version = NclReleaseVersion.parse(release.version.toString())
            Map<String, Object> metadata = new TreeMap<>()
            metadata.channel = version.channel()
            metadata.url = release.url.toString()
            releases[version.value] = metadata
        }

        Map<String, Object> targets = new TreeMap<>()
        eligibleTargets.each { Map target ->
            String targetId = target.id.toString()
            List<String> versions = associatedReleases.findAll { Map release ->
                (release.targetIds as Collection).contains(targetId)
            }.collect { it.version.toString() }
            Map<String, Object> metadata = new TreeMap<>()
            metadata.loader = (target.loader as Map).id.toString()
            metadata.minecraftVersion = target.minecraft.version.toString()
            metadata.versions = versions
            targets[targetId] = metadata
        }

        Map<String, Object> root = new TreeMap<>()
        root.project = 'nclskins'
        root.releases = releases
        root.schemaVersion = 1
        root.targets = targets
        root
    }

    static String catalogJson(Map catalog, List<Map> inventory) {
        CatalogTools.json(UpdateCatalogSite.catalog(catalog, inventory))
    }

    static Map nativeCatalog(Map catalog, List<Map> inventory, String targetId) {
        Map target = CatalogTools.selectTarget(catalog, targetId)
        if (target.releaseEligible != true ||
                !['forge', 'neoforge'].contains((target.loader as Map).id.toString())) {
            throw new IllegalArgumentException("target has no native update catalog: ${targetId}")
        }
        Map common = UpdateCatalogSite.catalog(catalog, inventory)
        Map targetCatalog = (common.targets as Map)[targetId] as Map
        List<String> versions = targetCatalog.versions as List<String>
        String minecraftVersion = target.minecraft.version.toString()
        Map<String, Object> versionMap = new LinkedHashMap<>()
        versions.each { String version ->
            versionMap[version] = ((common.releases as Map)[version] as Map).url.toString()
        }
        Map<String, Object> promos = new TreeMap<>()
        String homepage = (catalog.mod as Map).contact.homepage.toString()
        if (!versions.isEmpty()) {
            String newest = versions.last()
            homepage = ((common.releases as Map)[newest] as Map).url.toString()
            promos["${minecraftVersion}-latest".toString()] = newest
            promos["${minecraftVersion}-recommended".toString()] = newest
        }
        Map<String, Object> nativeCatalog = new TreeMap<>()
        nativeCatalog[minecraftVersion] = versionMap
        nativeCatalog.homepage = homepage
        nativeCatalog.promos = promos
        nativeCatalog
    }

    static String nativeJson(Map catalog, List<Map> inventory, String targetId) {
        CatalogTools.json(nativeCatalog(catalog, inventory, targetId))
    }

    static Map<String, String> files(Map catalog, List<Map> inventory) {
        Map<String, String> files = new TreeMap<>()
        files['.nojekyll'] = ''
        files['updates/v1/catalog.json'] = catalogJson(catalog, inventory)
        CatalogTools.releaseTargets(catalog).findAll { Map target ->
            ['forge', 'neoforge'].contains((target.loader as Map).id.toString())
        }.sort { Map left, Map right -> left.id.toString() <=> right.id.toString() }
                .each { Map target ->
                    String targetId = target.id.toString()
                    if (!(targetId ==~ /[a-z0-9][a-z0-9.-]*/)) {
                        throw new IllegalArgumentException("unsafe native target id: ${targetId}")
                    }
                    files["updates/v1/native/${targetId}.json".toString()] =
                            nativeJson(catalog, inventory, targetId)
                }
        files
    }

    private UpdateCatalogSite() {}
}

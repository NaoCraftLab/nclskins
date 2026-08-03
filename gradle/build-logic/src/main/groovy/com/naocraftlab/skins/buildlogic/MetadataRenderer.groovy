package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput

final class MetadataRenderer {
    static Map<String, String> render(Map catalog, Map target, String modVersion) {
        String loader = target.loader.id
        Map<String, String> resources
        if (loader == 'fabric') {
            resources = ['fabric.mod.json': fabric(catalog, target, modVersion)]
        } else if (loader == 'forge') {
            resources = ['META-INF/mods.toml': forge(catalog, target, modVersion), 'pack.mcmeta': rootPack(target)]
        } else if (loader == 'neoforge') {
            resources = ['META-INF/neoforge.mods.toml': neoforge(catalog, target, modVersion)]
        } else {
            throw new IllegalArgumentException("unsupported loader: ${loader}")
        }
        resources['resourcepacks/mojang_collections/pack.mcmeta'] = mojangPack(target)
        Set expected = (target.metadata.files as List) as Set
        if (resources.keySet() as Set != expected) {
            throw new IllegalArgumentException("${target.id}: generated metadata paths differ from catalog")
        }
        resources
    }

    static String fabric(Map catalog, Map target, String version) {
        Map mod = catalog.mod as Map
        Map metadata = target.metadata as Map
        Map result = [
            schemaVersion: 1,
            id: mod.id,
            version: version,
            name: mod.name,
            description: mod.description,
            authors: mod.authors,
            contact: [homepage: mod.homepage],
            license: mod.license,
            icon: mod.icon,
            environment: '*'
        ]
        if (metadata.accessWidener) {
            result.accessWidener = metadata.accessWidener
        }
        result.entrypoints = [main: [metadata.serverEntrypoint], client: [metadata.entrypoint]]
        List mixins = []
        (metadata.serverMixins as List).each { mixins.add([config: it]) }
        (metadata.mixins as List).each { mixins.add([config: it, environment: 'client']) }
        if (mixins) {
            result.mixins = mixins
        }
        result.depends = [
            fabricloader: target.loader.predicate,
            'fabric-api': target.loader.apiPredicate,
            minecraft: target.minecraft.predicate,
            java: ">=${target.java.release}"
        ]
        CatalogTools.json(result)
    }

    static String forge(Map catalog, Map target, String version) {
        Map mod = catalog.mod as Map
        Map metadata = target.metadata as Map
        String id = mod.id
        [
            "modLoader=${quote(metadata.modLoader)}",
            "loaderVersion=${quote(metadata.loaderVersion)}",
            "license=${quote(mod.license)}",
            "issueTrackerURL=${quote(mod.homepage)}",
            '',
            '[[mods]]',
            "modId=${quote(id)}",
            "version=${quote(version)}",
            "displayName=${quote(mod.name)}",
            "displayURL=${quote(mod.homepage)}",
            "authors=${quote((mod.authors as List).join(', '))}",
            "logoFile=${quote(mod.icon)}",
            "logoBlur=${booleanValue(mod.iconBlur)}",
            'displayTest="IGNORE_SERVER_VERSION"',
            "description=${quote(mod.description)}",
            '',
            "[[dependencies.${id}]]",
            'modId="forge"',
            'mandatory=true',
            "versionRange=${quote(target.loader.predicate)}",
            'ordering="NONE"',
            'side="BOTH"',
            '',
            "[[dependencies.${id}]]",
            'modId="minecraft"',
            'mandatory=true',
            "versionRange=${quote(target.minecraft.predicate)}",
            'ordering="NONE"',
            'side="BOTH"',
            ''
        ].join('\n')
    }

    static String neoforge(Map catalog, Map target, String version) {
        Map mod = catalog.mod as Map
        Map metadata = target.metadata as Map
        String id = mod.id
        List<String> lines = [
            "modLoader=${quote(metadata.modLoader)}",
            "loaderVersion=${quote(metadata.loaderVersion)}",
            "license=${quote(mod.license)}",
            '',
            '[[mods]]',
            "modId=${quote(id)}",
            "version=${quote(version)}",
            "displayName=${quote(mod.name)}",
            "displayURL=${quote(mod.homepage)}",
            "authors=${quote((mod.authors as List).join(', '))}",
            "logoFile=${quote(mod.icon)}",
            "logoBlur=${booleanValue(mod.iconBlur)}",
            "description=${quote(mod.description)}",
            ''
        ]
        ((metadata.serverMixins as List) + (metadata.mixins as List)).each {
            lines.addAll(['[[mixins]]', "config=${quote(it)}", ''])
        }
        lines.addAll([
            "[[dependencies.${id}]]",
            'modId="neoforge"',
            'type="required"',
            "versionRange=${quote(target.loader.predicate)}",
            'ordering="NONE"',
            'side="BOTH"',
            '',
            "[[dependencies.${id}]]",
            'modId="minecraft"',
            'type="required"',
            "versionRange=${quote(target.minecraft.predicate)}",
            'ordering="NONE"',
            'side="BOTH"',
            '',
            "[features.${id}]",
            "javaVersion=\"[${target.java.release},)\"",
            ''
        ])
        lines.join('\n')
    }

    static String rootPack(Map target) {
        Object format = target.metadata.packFormat
        if (!(format instanceof Number)) {
            throw new IllegalArgumentException('root mod pack metadata requires an integer packFormat')
        }
        CatalogTools.json([pack: [description: 'NCL Skins resources', pack_format: format]])
    }

    static String mojangPack(Map target) {
        Object format = target.metadata.packFormat
        Map section = [description: [translate: 'pack.nclskins.mojang_collections.description']]
        if (format instanceof Number) {
            section.pack_format = format
        } else if (format instanceof List && (format as List).size() == 2 && (format as List).every { it instanceof Number }) {
            section.min_format = format
            section.max_format = format
        } else {
            throw new IllegalArgumentException('packFormat must be an integer or [major, minor]')
        }
        CatalogTools.json([pack: section])
    }

    static String quote(Object value) {
        JsonOutput.toJson(value.toString())
    }

    static String booleanValue(Object value) {
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException('value must be a boolean')
        }
        value ? 'true' : 'false'
    }
}

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
        Map contact = mod.contact as Map
        Map result = [
            schemaVersion: 1,
            id: mod.id,
            version: version,
            name: mod.name,
            description: mod.descriptions.en_us,
            authors: mod.authors,
            contact    : contact,
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
        result.suggests = [modmenu: ">=${target.loader.modMenuVersion}"]
        result.custom = [modmenu: [
                links         : [
                        'modmenu.modrinth'  : modrinthUrl(mod),
                        'modmenu.curseforge': curseForgeUrl(mod)
                ],
                update_checker: true
        ]]
        CatalogTools.json(result)
    }

    static String forge(Map catalog, Map target, String version) {
        Map mod = catalog.mod as Map
        Map metadata = target.metadata as Map
        Map contact = mod.contact as Map
        String id = mod.id
        [
            "modLoader=${quote(metadata.modLoader)}",
            "loaderVersion=${quote(metadata.loaderVersion)}",
            "license=${quote(mod.license)}",
            "issueTrackerURL=${quote(contact.issues)}",
            'showAsResourcePack=false',
            '',
            '[[mods]]',
            "modId=${quote(id)}",
            "version=${quote(version)}",
            "displayName=${quote(mod.name)}",
            "displayURL=${quote(contact.homepage)}",
            "updateJSONURL=${quote(forgeUpdatesUrl(mod, false))}",
            "authors=${quote((mod.authors as List).join(', '))}",
            "logoFile=${quote(mod.icon)}",
            "logoBlur=${booleanValue(mod.iconBlur)}",
            'displayTest="IGNORE_SERVER_VERSION"',
            "features={java_version=${quote("[${target.java.release},)")}}",
            "description=${quote(mod.descriptions.en_us)}",
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
        Map contact = mod.contact as Map
        String id = mod.id
        List<String> lines = [
            "modLoader=${quote(metadata.modLoader)}",
            "loaderVersion=${quote(metadata.loaderVersion)}",
            "license=${quote(mod.license)}",
            "issueTrackerURL=${quote(contact.issues)}",
            'showAsResourcePack=false',
            'showAsDataPack=false',
            '',
            '[[mods]]',
            "modId=${quote(id)}",
            "version=${quote(version)}",
            "displayName=${quote(mod.name)}",
            "displayURL=${quote(contact.homepage)}",
            "updateJSONURL=${quote(forgeUpdatesUrl(mod, true))}",
            "authors=${quote((mod.authors as List).join(', '))}",
            "logoFile=${quote(mod.icon)}",
            "logoBlur=${booleanValue(mod.iconBlur)}",
            "description=${quote(mod.descriptions.en_us)}",
            ''
        ]
        ((metadata.serverMixins as List) + (metadata.mixins as List)).each {
            lines.addAll(['[[mixins]]', "config=${quote(it)}", ''])
        }
        if (metadata.accessTransformer) {
            lines.addAll(['[[accessTransformers]]', "file=${quote(metadata.accessTransformer)}", ''])
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

    static String modrinthUrl(Map mod) {
        "https://modrinth.com/mod/${mod.platforms.modrinth.slug}"
    }

    static String curseForgeUrl(Map mod) {
        "https://www.curseforge.com/minecraft/mc-mods/${mod.platforms.curseforge.slug}"
    }

    static String forgeUpdatesUrl(Map mod, boolean neoForge) {
        String base = "https://api.modrinth.com/updates/${mod.platforms.modrinth.slug}/forge_updates.json"
        neoForge ? base + '?neoforge=only' : base
    }
}

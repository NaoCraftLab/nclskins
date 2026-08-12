package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput

final class MetadataRenderer {
    static Map<String, String> render(Map catalog, Map target, String modVersion) {
        String loader = target.loader.id
        Map<String, String> resources = LoaderBackend.require(loader)
                .metadata(catalog, target, modVersion)
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
        result.entrypoints = [
                main   : [metadata.serverEntrypoint],
                client : [metadata.entrypoint],
                modmenu: [metadata.modMenuEntrypoint]
        ]
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
        (catalog.optionalDependencies as Map).each { Object dependencyId, Object ignored ->
            String predicate = CatalogTools.optionalDependencyPredicate(
                    catalog, target, dependencyId.toString())
            if (predicate != null) result.suggests[dependencyId.toString()] = predicate
        }
        result.custom = [modmenu: [
                links         : modMenuLinks(mod),
                update_checker: true
        ]]
        CatalogTools.json(result)
    }

    static String forge(Map catalog, Map target, String version) {
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
            '',
            '[[mods]]',
            "modId=${quote(id)}",
            "version=${quote(version)}",
            "displayName=${quote(mod.name)}",
            "displayURL=${quote(contact.homepage)}",
            "issueTrackerURL=${quote(contact.issues)}",
            "updateJSONURL=${quote(forgeUpdatesUrl(mod, false))}",
            "authors=${quote((mod.authors as List).join(', '))}",
            "logoFile=${quote(mod.icon)}",
            "logoBlur=${booleanValue(mod.iconBlur)}",
            'displayTest="IGNORE_SERVER_VERSION"',
            "features={java_version=${quote("[${target.java.release},)")}}",
            "description=${quote(mod.descriptions.en_us)}",
            '',
            "[modproperties.${id}]",
            "catalogueImageIcon=${quote(mod.icon)}",
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
        ]
        (catalog.optionalDependencies as Map).each { Object dependencyId, Object declaration ->
            String predicate = CatalogTools.optionalDependencyPredicate(
                    catalog, target, dependencyId.toString())
            if (predicate == null) return
            lines.addAll([
                    "[[dependencies.${id}]]",
                    "modId=${quote(dependencyId)}",
                    'mandatory=false',
                    "versionRange=${quote(predicate)}",
                    'ordering="NONE"',
                    "side=${quote(((declaration as Map).side as String).toUpperCase(Locale.ROOT))}",
                    ''
            ])
        }
        lines.join('\n')
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
            "issueTrackerURL=${quote(contact.issues)}",
            "updateJSONURL=${quote(forgeUpdatesUrl(mod, true))}",
            "authors=${quote((mod.authors as List).join(', '))}"
        ]
        if (metadata.modListBranding == 'icon-only') {
            lines.addAll([
                    "iconFile=${quote(mod.icon)}",
                    "iconBlur=${booleanValue(mod.iconBlur)}",
                    'bannerFile=false'
            ])
        } else {
            lines.addAll([
                    "logoFile=${quote(mod.icon)}",
                    "logoBlur=${booleanValue(mod.iconBlur)}"
            ])
        }
        lines.addAll([
            "description=${quote(mod.descriptions.en_us)}",
            '',
            "[modproperties.${id}]",
            "catalogueImageIcon=${quote(mod.icon)}",
            ''
        ])
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
            ''
        ])
        (catalog.optionalDependencies as Map).each { Object dependencyId, Object declaration ->
            String predicate = CatalogTools.optionalDependencyPredicate(
                    catalog, target, dependencyId.toString())
            if (predicate == null) return
            lines.addAll([
                    "[[dependencies.${id}]]",
                    "modId=${quote(dependencyId)}",
                    'type="optional"',
                    "versionRange=${quote(predicate)}",
                    'ordering="NONE"',
                    "side=${quote(((declaration as Map).side as String).toUpperCase(Locale.ROOT))}",
                    ''
            ])
        }
        lines.addAll([
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

    static Map<String, String> modMenuLinks(Map mod) {
        [
                'modmenu.modrinth'             : modrinthUrl(mod),
                'modmenu.curseforge'           : curseForgeUrl(mod),
                'nclskins.modmenu.youtube'     : mod.links.youtube.toString(),
                'nclskins.modmenu.telegram_bot': mod.links.telegramBot.toString(),
                'nclskins.modmenu.x'           : mod.links.x.toString()
        ]
    }

    static String forgeUpdatesUrl(Map mod, boolean neoForge) {
        String base = "https://api.modrinth.com/updates/${mod.platforms.modrinth.slug}/forge_updates.json"
        neoForge ? base + '?neoforge=only' : base
    }
}

package com.naocraftlab.skins.buildlogic

final class GithubReleaseSupport {
    static Map plan(Map manifest, List<Map> remoteAssets, Closure<String> remoteSha256) {
        Map<String, Map> canonical = desiredAssets(manifest).collectEntries { Map asset ->
            [(asset.file.toString()): asset]
        }
        Map<String, Map> preserved = (manifest.preservedGithub ?: []).collectEntries { Map asset ->
            [(asset.assetFile?.toString() ?: asset.file.toString()): asset]
        }
        if (preserved.any { String file, Map entry ->
            !canonical.containsKey(file) || entry.sha256 != canonical[file].sha256
        }) {
            throw new IllegalStateException('Preserved GitHub assets differ from release assets')
        }
        Map<String, Map> desired = canonical.collectEntries { String file, Map asset ->
            Map existing = preserved[file]
            String remoteName = existing?.file?.toString() ?: file
            [(remoteName): [asset: asset, preserved: existing]]
        }
        if (desired.size() != canonical.size()) {
            throw new IllegalStateException('Preserved GitHub asset aliases overlap')
        }
        Map<String, List<Map>> remoteByName = remoteAssets.groupBy { it.name?.toString() ?: '' }
        Map replacement = manifest.pluginReplacement as Map
        Map plugin = canonical.values().find { it.kind == 'server-plugin' }
        if (replacement != null && (plugin == null || replacement.file == plugin.file ||
                !(replacement.sha256 ==~ /[0-9a-f]{64}/) || replacement.id == null)) {
            throw new IllegalStateException('Invalid plugin replacement record')
        }
        if (replacement != null) {
            String version = plugin.file.toString().replaceFirst('^nclskins-plugin-', '').replaceFirst(/\.jar$/, '')
            if (PlanReleaseTask.previousPluginAsset([asset: plugin, versionNumber: version],
                    [[name: replacement.file]]) == null) {
                throw new IllegalStateException('Replacement must identify an older plugin build')
            }
        }
        List<Map> actions = []
        remoteByName.each { String name, List<Map> entries ->
            if (entries.size() > 1) {
                actions.add([action: 'conflict', file: name, reason: 'multiple GitHub assets use the same name'])
            } else if (replacement != null && name == replacement.file) {
                Map old = entries.first()
                boolean exact = requiredId(old) == replacement.id.toString() &&
                        remoteSha256.call(old) == replacement.sha256
                actions.add([action: exact ? 'delete-previous-plugin' : 'conflict', file: name,
                             remoteId: requiredId(old), reason: exact ? 'superseded plugin build' : 'previous plugin changed'])
            } else if (!desired.containsKey(name)) {
                actions.add([action: 'conflict', file: name, remoteId: requiredId(entries.first()),
                             reason: 'unknown existing release asset'])
            }
        }
        desired.each { String name, Map expectation ->
            Map asset = expectation.asset as Map
            Map preservedAsset = expectation.preserved as Map
            List<Map> entries = remoteByName[name] ?: []
            if (entries.size() > 1) return
            if (entries.isEmpty()) {
                actions.add(preservedAsset == null
                        ? [action: 'upload', file: name, kind: asset.kind]
                        : [action: 'conflict', file: name, kind: asset.kind,
                           reason: 'preserved release asset is missing'])
                return
            }
            Map remote = entries.first()
            String actual = remoteSha256.call(remote)
            String remoteId = requiredId(remote)
            if (actual == asset.sha256 &&
                    (preservedAsset == null || preservedAsset.id.toString() == remoteId)) {
                actions.add([action: 'keep', file: name, kind: asset.kind,
                             remoteId: remoteId])
            } else {
                actions.add([action: 'conflict', file: name, kind: asset.kind,
                             remoteId: remoteId, reason: 'existing release asset differs'])
            }
        }
        if (replacement != null && !remoteByName.containsKey(replacement.file) &&
                !actions.any { it.file == plugin.file && it.action == 'keep' }) {
            actions.add([action: 'conflict', file: replacement.file, reason: 'previous plugin disappeared before replacement'])
        }
        [actions: actions, conflicts: actions.findAll { it.action == 'conflict' }]
    }

    static List<Map> desiredAssets(Map manifest) {
        (manifest.assets as List<Map>).findAll { Map asset ->
            asset.kind in ['mod', 'server-plugin']
        }
    }

    private static String requiredId(Map remote) {
        if (remote.id == null) throw new IllegalStateException('GitHub release asset has no ID')
        remote.id.toString()
    }

    private GithubReleaseSupport() {}
}

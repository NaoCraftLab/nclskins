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
        List<Map> actions = []
        remoteByName.each { String name, List<Map> entries ->
            if (entries.size() > 1) {
                actions.add([action: 'conflict', file: name, reason: 'multiple GitHub assets use the same name'])
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

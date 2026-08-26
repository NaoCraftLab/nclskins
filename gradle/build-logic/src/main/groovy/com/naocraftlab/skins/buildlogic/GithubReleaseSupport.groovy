package com.naocraftlab.skins.buildlogic

final class GithubReleaseSupport {
    static Map plan(Map manifest, List<Map> remoteAssets, Closure<String> remoteSha256) {
        Map<String, Map> desired = desiredAssets(manifest).collectEntries { Map asset ->
            [(asset.file.toString()): asset]
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
        desired.each { String name, Map asset ->
            List<Map> entries = remoteByName[name] ?: []
            if (entries.size() > 1) return
            if (entries.isEmpty()) {
                actions.add([action: 'upload', file: name, kind: asset.kind])
                return
            }
            Map remote = entries.first()
            String actual = remoteSha256.call(remote)
            if (actual == asset.sha256) {
                actions.add([action: 'keep', file: name, kind: asset.kind,
                             remoteId: requiredId(remote)])
            } else {
                actions.add([action: 'conflict', file: name, kind: asset.kind,
                             remoteId: requiredId(remote), reason: 'existing release asset differs'])
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

package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper

final class ReleasePlan {
    static Map load(File file) {
        Map plan = CatalogTools.materialize(new JsonSlurper().parse(file)) as Map
        String digest = plan.remove('digest')?.toString()
        if (plan.schemaVersion != 2 || digest != CatalogTools.sha256(CatalogTools.json(plan).getBytes('UTF-8')) ||
                !(plan.version instanceof String) || !CatalogTools.VERSION_PATTERN.matcher(plan.version as String).matches() ||
                !(plan.sourceCommit ==~ /[0-9a-f]{40}/) ||
                (plan.mode == 'local' ? plan.tagCommit != null : !(plan.tagCommit ==~ /[0-9a-f]{40}/)) ||
                !(plan.components instanceof List) || !(plan.buildTargetIds instanceof List)) {
            throw new IllegalStateException('Invalid or modified release plan')
        }
        Map state = plan.serverState instanceof Map ? plan.serverState as Map : [:]
        Object active = state.activeVersion
        if (state.containsKey('sourceCommit') || state.keySet().any { it.toString().contains('Fingerprint') } ||
                state.schemaVersion != 2 || state.sealed != true ||
                state.currentVersion != plan.version || state.requestedVersion != plan.requestedVersion ||
                state.componentKind != plan.componentKind ||
                !(active instanceof String) || !CatalogTools.VERSION_PATTERN.matcher(active as String).matches()) {
            throw new IllegalStateException('Invalid release plan server plugin state')
        }
        Map selected
        try { selected = ServerPluginVersion.select(plan.requestedVersion.toString(), state.modVersion.toString(), state.pluginVersion.toString()) }
        catch (IllegalArgumentException error) { throw new IllegalStateException('Invalid release plan component selection', error) }
        if (selected.any { key, value -> state[key] != value } || state.activeVersion != state.functionalVersion ||
                plan.buildTargetIds != plan.components.findAll { it.build && it.id != 'server-plugin' }.collect { it.id } ||
                plan.buildPlugin != plan.components.any { it.build && it.id == 'server-plugin' } ||
                plan.components.collect { it.id }.toSet().size() != plan.components.size() ||
                (plan.componentKind == 'plugin-only' && (plan.components*.id != ['server-plugin'] || plan.release.notes != null))) {
            throw new IllegalStateException('Invalid release plan component selection')
        }
        plan.digest = digest
        if (plan.componentKind == 'plugin-only' && plan.mode != 'local') requireSourceBranch(plan.sourceBranch?.toString(), plan.version.toString())
        plan
    }

    static Map seal(Map plan) {
        new LinkedHashMap(plan) + [digest: CatalogTools.sha256(CatalogTools.json(plan).getBytes('UTF-8'))]
    }

    static Map classify(Map desired, Map inventories, boolean recovered, boolean alreadyReleased = false) {
        Map states = [:]
        ['modrinth', 'curseforge'].each { String platform ->
            Map state = PublicationSupport.classify(platform, desired, inventories[platform] as List<Map>)
            if (state.action == 'conflict' || state.action.toString().startsWith('update-metadata')) {
                throw new IllegalStateException("${platform} ${desired.id}: ${state.reason}")
            }
            states[platform] = state
        }
        if (!recovered && states.values().any { it.action != 'upload' }) {
            throw new IllegalStateException("${desired.id}: recover exact production/source bytes before retry")
        }
        boolean preserve = recovered && states.every { String platform, Map state ->
            state.action == 'skip' || (alreadyReleased && platform == 'curseforge' && state.action == 'upload-source')
        }
        [build: !recovered, preserve: preserve, states: states]
    }

    static void requireCheckout(File repository, Map plan) {
        if (ReleaseSelection.git(repository, ['rev-parse', 'HEAD']).trim() != plan.sourceCommit ||
                plan.version != plan.serverState.currentVersion ||
                ServerPluginVersion.select(plan.requestedVersion.toString(), CatalogTools.loadVersion(repository),
                        ServerPluginVersion.load(repository)) != (plan.serverState as Map).findAll { key, value ->
                            key in ['requestedVersion', 'currentVersion', 'modVersion', 'pluginVersion', 'functionalVersion', 'componentKind']
                        }) {
            throw new IllegalStateException('Release plan belongs to another source commit or version')
        }
    }

    static void requireSourceBranch(String branch, String version) {
        String line = version.replaceFirst(/-(?:alpha|beta)\.[1-9][0-9]*$/, '')
        if (branch != 'main' && !(version.contains('-') && branch == "release/${line}")) {
            throw new IllegalStateException('Release source must be main or the matching prerelease branch')
        }
    }

    static Map placeholder(String file, String kind, String target) {
        [file: file, kind: kind, target: target, size: 0,
         sha1: '0' * 40, sha256: '0' * 64, sha512: '0' * 128]
    }
}

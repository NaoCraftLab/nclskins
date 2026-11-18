package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper

final class ReleasePlan {
    static Map load(File file) {
        Map plan = CatalogTools.materialize(new JsonSlurper().parse(file)) as Map
        String digest = plan.remove('digest')?.toString()
        boolean pluginOnly = plan.componentKind == 'plugin-only'
        if (plan.schemaVersion != 1 || digest != CatalogTools.sha256(CatalogTools.json(plan).getBytes('UTF-8')) ||
                !(plan.version instanceof String) || (pluginOnly ? !ServerPluginVersion.isFullVersion(plan.version) :
                    !CatalogTools.VERSION_PATTERN.matcher(plan.version as String).matches()) ||
                !(plan.sourceCommit ==~ /[0-9a-f]{40}/) || !(plan.tagCommit ==~ /[0-9a-f]{40}/) ||
                !(plan.components instanceof List) || !(plan.buildTargetIds instanceof List)) {
            throw new IllegalStateException('Invalid or modified release plan')
        }
        Map state = plan.serverState instanceof Map ? plan.serverState as Map : [:]
        Object active = state.activeVersion
        if (!(state.schemaVersion in [1, 2]) || state.sealed != true ||
                state.currentVersion != plan.version || state.sourceCommit != (pluginOnly ? plan.sourceCommit : plan.tagCommit) ||
                !(active instanceof String) || !CatalogTools.VERSION_PATTERN.matcher(active as String).matches()) {
            throw new IllegalStateException('Invalid release plan server plugin state')
        }
        if (state.schemaVersion == 2) {
            Map selection = ServerPluginVersion.select(plan.version.toString(), state.modVersion.toString(), state.pluginVersion.toString())
            if (state.requestedVersion != plan.version || state.componentKind != selection.kind ||
                    state.reason != selection.kind || state.activeVersion != selection.baseline ||
                    state.publish != (selection.kind != 'mod-only') || pluginOnly != (selection.kind == 'plugin-only')) {
                throw new IllegalStateException('Invalid selected release plan state')
            }
        }
        if (pluginOnly) {
            if (state.schemaVersion != 2 || plan.githubTag != ServerPluginVersion.parts(plan.version.toString()).base ||
                    plan.mode != 'backfill' || plan.existingRelease?.id == null ||
                    plan.release.notes != '' || plan.components*.id != ['server-plugin'] ||
                    plan.components.first().versionNumber != plan.version ||
                    plan.buildPlugin != plan.components.first().build || plan.buildTargetIds != []) {
                throw new IllegalStateException('Invalid plugin-only release plan')
            }
        } else if (plan.componentKind != null || plan.githubTag != null) {
            throw new IllegalStateException('Unexpected release plan identity')
        }
        plan.digest = digest
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
        boolean pluginOnly = plan.componentKind == 'plugin-only'
        if (ReleaseSelection.git(repository, ['rev-parse', 'HEAD']).trim() != plan.sourceCommit ||
                CatalogTools.loadVersion(repository) != (pluginOnly ? plan.serverState.modVersion : plan.version) ||
                (plan.serverState.schemaVersion == 2 && ServerPluginVersion.load(repository) != plan.serverState.pluginVersion)) {
            throw new IllegalStateException('Release plan belongs to another source commit or version')
        }
    }

    static boolean historicalModAsset(Object file, String tag) {
        file instanceof String && PublicationSupport.safeFileName(file) &&
                file ==~ /nclskins-${java.util.regex.Pattern.quote(tag)}\+[0-9A-Za-z.]+-(fabric|forge|neoforge)\.jar/
    }

    static void requireSourceBranch(String branch, String version) {
        String line = ServerPluginVersion.parts(version).base.toString().replaceFirst(/-(?:alpha|beta)\.[1-9][0-9]*$/, '')
        if (branch != 'main' && !(version.contains('-') && branch == "release/${line}")) {
            throw new IllegalStateException('Release source must be main or the matching prerelease branch')
        }
    }

    static Map placeholder(String file, String kind, String target) {
        [file: file, kind: kind, target: target, size: 0,
         sha1: '0' * 40, sha256: '0' * 64, sha512: '0' * 128]
    }
}

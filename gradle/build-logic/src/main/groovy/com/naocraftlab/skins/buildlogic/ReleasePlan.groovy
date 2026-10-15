package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper

final class ReleasePlan {
    static Map load(File file) {
        Map plan = CatalogTools.materialize(new JsonSlurper().parse(file)) as Map
        String digest = plan.remove('digest')?.toString()
        if (plan.schemaVersion != 1 || digest != CatalogTools.sha256(CatalogTools.json(plan).getBytes('UTF-8')) ||
                !(plan.version instanceof String) || !CatalogTools.VERSION_PATTERN.matcher(plan.version as String).matches() ||
                !(plan.sourceCommit ==~ /[0-9a-f]{40}/) || !(plan.tagCommit ==~ /[0-9a-f]{40}/) ||
                !(plan.components instanceof List) || !(plan.buildTargetIds instanceof List)) {
            throw new IllegalStateException('Invalid or modified release plan')
        }
        Map state = plan.serverState instanceof Map ? plan.serverState as Map : [:]
        Object active = state.activeVersion
        if (state.schemaVersion != 1 || state.sealed != true ||
                state.currentVersion != plan.version || state.sourceCommit != plan.tagCommit ||
                !(active instanceof String) || !CatalogTools.VERSION_PATTERN.matcher(active as String).matches()) {
            throw new IllegalStateException('Invalid release plan server plugin state')
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
        if (ReleaseSelection.git(repository, ['rev-parse', 'HEAD']).trim() != plan.sourceCommit ||
                CatalogTools.loadVersion(repository) != plan.version) {
            throw new IllegalStateException('Release plan belongs to another source commit or version')
        }
    }

    static Map placeholder(String file, String kind, String target) {
        [file: file, kind: kind, target: target, size: 0,
         sha1: '0' * 40, sha256: '0' * 64, sha512: '0' * 128]
    }
}

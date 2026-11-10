package com.naocraftlab.skins.buildlogic

import java.util.concurrent.TimeUnit

final class ReleaseProcess {
    static Map admit(File repository, String requested, String event, String ref, String expected,
                     boolean resume, Closure<String> remoteBranch) {
        Map parts = ServerPluginVersion.parts(requested)
        String version = parts.base
        String head = ReleaseSelection.git(repository, ['rev-parse', 'HEAD']).trim()
        if (!(expected ==~ /[0-9a-f]{40}/) || head != expected || !(event in ['push', 'workflow_dispatch'])) {
            throw new IllegalStateException('Release checkout differs from workflow source')
        }
        Map<String, String> snapshots = [:]
        def branchSha = { String name ->
            if (!snapshots.containsKey(name)) {
                String sha = remoteBranch.call(name)
                if (!(sha ==~ /[0-9a-f]{40}/)) throw new IllegalStateException('Invalid remote branch snapshot')
                snapshots[name] = sha
            }
            snapshots[name]
        }
        String main = branchSha('main')
        String branch
        if (event == 'workflow_dispatch') {
            if (!ref?.startsWith('refs/heads/')) throw new IllegalStateException('Dispatch must select an approved branch')
            branch = ref.substring('refs/heads/'.length())
            ReleasePlan.requireSourceBranch(branch, version)
            if (branchSha(branch) != head) throw new IllegalStateException('Remote source branch drifted')
        } else {
            if (requested != version || ref != "refs/tags/${version}" ||
                    ReleaseSelection.git(repository, ['rev-parse', "refs/tags/${version}^{commit}"]).trim() != head) {
                throw new IllegalStateException('Push must select the exact mod tag')
            }
            boolean onMain = head == main || (resume && ReleaseSelection.git(repository,
                    ['rev-list', '--max-count=1', head, '--not', main]).trim().isEmpty())
            branch = onMain ? 'main' : "release/${version.replaceFirst(/-(?:alpha|beta)\.[1-9][0-9]*$/, '')}".toString()
            ReleasePlan.requireSourceBranch(branch, version)
            if (!resume && branchSha(branch) != head) throw new IllegalStateException('Tagged source differs from remote branch snapshot')
            if (resume) ReleaseSelection.git(repository, ['merge-base', '--is-ancestor', head, branchSha(branch)])
        }
        [source: head, catalog: main, branch: branch, lane: branchSha(branch)]
    }

    static Map ciOutputs(Map plan, String catalog, boolean resumed) {
        Object baseline = plan.serverState?.activeVersion
        if (!(baseline instanceof String) || !CatalogTools.VERSION_PATTERN.matcher(baseline).matches() ||
                !(plan.digest ==~ /[0-9a-f]{64}/) || !(plan.sourceCommit ==~ /[0-9a-f]{40}/) ||
                !(catalog ==~ /[0-9a-f]{40}/) || !(plan.components instanceof List)) {
            throw new IllegalStateException('Invalid pinned CI output identity')
        }
        ServerPluginVersion.parts(plan.requestedVersion?.toString())
        List matrix = resumed ? [] : plan.components.findAll { it.build == true }.collect { Map component ->
            if (!(component.id ==~ /[a-z0-9][a-z0-9.-]*/)) throw new IllegalStateException('Invalid component ID')
            [id: component.id]
        }
        [version: plan.requestedVersion, source: plan.sourceCommit, catalog: catalog,
         matrix: groovy.json.JsonOutput.toJson([include: matrix]), build: (!matrix.isEmpty()).toString(),
         resumed: resumed.toString(), baseline: baseline, digest: plan.digest]
    }

    static void writeOutputs(File destination, Map values) {
        String content = values.collect { key, value ->
            if (!(key ==~ /[a-z]+/) || value == null || value.toString().contains('\n') || value.toString().contains('\r')) {
                throw new IllegalStateException('Unsafe CI output')
            }
            "${key}=${value}\n"
        }.join('')
        destination.append(content, 'UTF-8')
    }

    static void requireResume(Map run, String id, String repository, String requested, Map plan) {
        ReleasePlan.requireSourceBranch(plan.sourceBranch?.toString(), ServerPluginVersion.parts(requested).base.toString())
        if (!(id ==~ /[1-9][0-9]*/) || run.id?.toString() != id ||
                run.path != '.github/workflows/release.yml' || run.repository?.full_name != repository ||
                !(run.event in ['push', 'workflow_dispatch']) || run.head_sha != plan.sourceCommit ||
                plan.requestedVersion != requested || plan.mode == 'local' ||
                (run.event == 'workflow_dispatch' ? run.head_branch != plan.sourceBranch :
                        !(run.head_branch in [plan.version, plan.sourceBranch]))) {
            throw new IllegalStateException('Resume run has wrong repository, workflow, source or version provenance')
        }
    }

    static void run(ProcessBuilder builder, String label, long timeoutSeconds, Closure output) {
        Process process = builder.redirectErrorStream(true).start()
        Thread cleanup = new Thread({ terminate(process) }, "${label}-cleanup")
        Runtime.runtime.addShutdownHook(cleanup)
        Thread reader = new Thread({
            process.inputStream.withReader('UTF-8') { stream ->
                stream.eachLine { output.call("[${label}] ${it}".toString()) }
            }
        }, "${label}-output")
        reader.daemon = true
        reader.start()
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException("${label} exceeded ${timeoutSeconds}s; terminating process tree")
            }
            reader.join(5000)
            if (process.exitValue() != 0) {
                throw new IllegalStateException("${label} failed (${process.exitValue()}); see streamed output")
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt()
            throw new IllegalStateException("${label} cancelled", error)
        } finally {
            terminate(process)
            try { Runtime.runtime.removeShutdownHook(cleanup) } catch (IllegalStateException ignored) { }
        }
    }

    private static void terminate(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList()
        descendants.reverseEach { it.destroy() }
        process.destroy()
        descendants.reverseEach { if (it.isAlive()) it.destroyForcibly() }
        if (process.isAlive()) process.destroyForcibly()
    }
}

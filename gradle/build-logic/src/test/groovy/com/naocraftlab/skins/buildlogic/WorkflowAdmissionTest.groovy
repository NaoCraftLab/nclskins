package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import static org.junit.jupiter.api.Assertions.*

final class WorkflowAdmissionTest {
    @TempDir File root

    @Test void freshSourceRequiresExactApprovedRemoteSnapshot() {
        ReleaseSelection.git(root, ['init', '-q', '-b', 'main'])
        new File(root, 'source').text = 'fixture'
        ReleaseSelection.git(root, ['add', '.'])
        ReleaseSelection.git(root, ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                '-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture'])
        String sha = ReleaseSelection.git(root, ['rev-parse', 'HEAD']).trim()
        List calls = []
        def remote = { String branch -> calls.add(branch); sha }
        Map admitted = ReleaseProcess.admit(root, '1.4.0', 'workflow_dispatch', 'refs/heads/main', sha, false, remote)
        assertEquals(sha, admitted.source)
        assertEquals(sha, admitted.catalog)
        assertEquals(['main'], calls)
        assertThrows(IllegalStateException) {
            ReleaseProcess.admit(root, '1.4.0', 'workflow_dispatch', 'refs/heads/main', sha, false, { 'b' * 40 })
        }
        assertThrows(IllegalStateException) {
            ReleaseProcess.admit(root, '1.4.0', 'workflow_dispatch', 'refs/heads/release/1.4.0', sha, false, remote)
        }
        ReleaseSelection.git(root, ['checkout', '-qb', 'release/1.4.0'])
        assertEquals('release/1.4.0', ReleaseProcess.admit(root, '1.4.0-beta.1', 'workflow_dispatch',
                'refs/heads/release/1.4.0', sha, false, remote).branch)
        assertThrows(IllegalStateException) {
            ReleaseProcess.admit(root, '1.4.0-beta.1', 'workflow_dispatch', 'refs/heads/release/1.5.0', sha, false, remote)
        }
    }

    @Test void stableMainTagResumeSurvivesMainAdvanceButFreshAdmissionDoesNot() {
        String source = initializeTag('1.4.0')
        String current = advance('main')
        ReleaseSelection.git(root, ['checkout', '-q', '--detach', source])
        Map admitted = ReleaseProcess.admit(root, '1.4.0', 'push', 'refs/tags/1.4.0', source, true, { current })
        assertEquals('main', admitted.branch)
        assertEquals(source, admitted.source)
        assertEquals(current, admitted.catalog)
        assertEquals(current, admitted.lane)
        assertThrows(IllegalStateException) {
            ReleaseProcess.admit(root, '1.4.0', 'push', 'refs/tags/1.4.0', source, false, { current })
        }
    }

    @Test void prereleaseTagResumeSelectsAncestralMainOrMatchingReleaseLine() {
        String source = initializeTag('1.4.0-beta.1')
        String main = advance('main')
        ReleaseSelection.git(root, ['checkout', '-q', '--detach', source])
        assertEquals('main', ReleaseProcess.admit(root, '1.4.0-beta.1', 'push',
                'refs/tags/1.4.0-beta.1', source, true, { main }).branch)
        ReleaseSelection.git(root, ['checkout', '-qb', 'release/1.4.0'])
        String release = advance('release/1.4.0')
        ReleaseSelection.git(root, ['checkout', '-q', '--detach', release])
        ReleaseSelection.git(root, ['tag', '1.4.0-beta.2'])
        String next = advance('release/1.4.0')
        ReleaseSelection.git(root, ['checkout', '-q', '--detach', release])
        Map admitted = ReleaseProcess.admit(root, '1.4.0-beta.2', 'push', 'refs/tags/1.4.0-beta.2', release,
                true, { branch -> branch == 'main' ? main : next })
        assertEquals('release/1.4.0', admitted.branch)
        assertEquals(next, admitted.lane)
        assertEquals(main, admitted.catalog)
    }

    @Test void resumeDoesNotAdmitWrongTagSourceOrStableReleaseLine() {
        String source = initializeTag('1.4.0')
        String main = advance('main')
        ReleaseSelection.git(root, ['checkout', '-q', '--detach', source])
        ['refs/tags/1.4.1', 'refs/heads/main', 'refs/heads/arbitrary'].each { ref ->
            assertThrows(IllegalStateException) {
                ReleaseProcess.admit(root, '1.4.0', 'push', ref, source, true, { main })
            }
        }
        assertThrows(IllegalStateException) {
            ReleaseProcess.admit(root, '1.4.0', 'push', 'refs/tags/1.4.0', main, true, { main })
        }
        ReleaseSelection.git(root, ['checkout', '-q', '--orphan', 'foreign'])
        String unrelated = advance('foreign')
        ReleaseSelection.git(root, ['checkout', '-q', '--detach', source])
        assertThrows(IllegalStateException) {
            ReleaseProcess.admit(root, '1.4.0', 'push', 'refs/tags/1.4.0', source, true,
                    { branch -> branch == 'main' ? unrelated : main })
        }
    }

    private String initializeTag(String version) {
        ReleaseSelection.git(root, ['init', '-q', '-b', 'main'])
        String source = advance('main')
        ReleaseSelection.git(root, ['tag', version])
        source
    }

    private String advance(String value) {
        new File(root, 'source').text = value + UUID.randomUUID()
        ReleaseSelection.git(root, ['add', '.'])
        ReleaseSelection.git(root, ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                '-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture'])
        ReleaseSelection.git(root, ['rev-parse', 'HEAD']).trim()
    }

    @Test void ciOutputsCarryFullPluginIdentityAndRejectUnboundBaseline() {
        Map plan = [requestedVersion: '1.2.0.2', version: '1.2.0', sourceCommit: 'a' * 40,
                digest: 'd' * 64, serverState: [activeVersion: '1.2.0'],
                components: [[id: 'server-plugin', build: true]]]
        Map out = ReleaseProcess.ciOutputs(plan, 'b' * 40, false)
        assertEquals('1.2.0.2', out.version)
        assertEquals('{"include":[{"id":"server-plugin"}]}', out.matrix)
        assertEquals('true', out.build)
        assertEquals('false', ReleaseProcess.ciOutputs(plan, 'b' * 40, true).build)
        [null, '', ' ', '1.2.0\nINJECTED=yes', 120].each { value ->
            assertThrows(IllegalStateException) {
                ReleaseProcess.ciOutputs(plan + [serverState: [activeVersion: value]], 'b' * 40, false)
            }
        }
    }

    @Test void configuredCompositionRoutesModOnlyJointAndPluginOnlyMatrices() {
        [['1.4.0', '1.4.0', '1.2.0.2', 'mod-only', ['fabric-26.2']],
         ['1.4.0', '1.4.0', '1.4.0.1', 'joint', ['fabric-26.2', 'server-plugin']],
         ['1.2.0.2', '1.4.0', '1.2.0.2', 'plugin-only', ['server-plugin']]].each { row ->
            Map selected = ServerPluginVersion.select(row[0], row[1], row[2])
            assertEquals(row[3], selected.componentKind)
            Map plan = [requestedVersion: selected.requestedVersion, sourceCommit: 'a' * 40,
                    digest: 'd' * 64, serverState: [activeVersion: selected.functionalVersion],
                    components: row[4].collect { [id: it, build: true] }]
            Map output = ReleaseProcess.ciOutputs(plan, 'b' * 40, false)
            assertEquals(row[4], new groovy.json.JsonSlurper().parseText(output.matrix).include*.id)
        }
    }

    @Test void resumeProvenanceEnforcesTheApprovedSourcePolicyForTagRuns() {
        Map plan = [requestedVersion: '1.4.0', version: '1.4.0', sourceCommit: 'a' * 40,
                sourceBranch: 'main', mode: 'fresh']
        Map run = [id: 123, path: '.github/workflows/release.yml', repository: [full_name: 'NaoCraftLab/nclskins'],
                event: 'push', head_sha: 'a' * 40, head_branch: '1.4.0']
        ReleaseProcess.requireResume(run, '123', 'NaoCraftLab/nclskins', '1.4.0', plan)
        ['release/1.4.0', 'arbitrary', null].each { branch ->
            assertThrows(IllegalStateException) {
                ReleaseProcess.requireResume(run, '123', 'NaoCraftLab/nclskins', '1.4.0', plan + [sourceBranch: branch])
            }
        }
        ['0', '0123', '124'].each { id ->
            assertThrows(IllegalStateException) {
                ReleaseProcess.requireResume(run, id, 'NaoCraftLab/nclskins', '1.4.0', plan)
            }
        }
        assertThrows(IllegalStateException) {
            ReleaseProcess.requireResume(run + [head_branch: '1.4.1'], '123', 'NaoCraftLab/nclskins', '1.4.0', plan)
        }
        Map prerelease = plan + [version: '1.4.0-beta.1', requestedVersion: '1.4.0-beta.1', sourceBranch: 'release/1.4.0']
        ReleaseProcess.requireResume(run + [head_branch: '1.4.0-beta.1'], '123', 'NaoCraftLab/nclskins', '1.4.0-beta.1', prerelease)
    }

    @Test void resumeProvenanceCannotCrossRepositoryWorkflowSourceOrVersion() {
        Map plan = [requestedVersion: '1.2.0.2', sourceCommit: 'a' * 40, sourceBranch: 'main', mode: 'backfill']
        Map run = [id: 123, path: '.github/workflows/release.yml', repository: [full_name: 'NaoCraftLab/nclskins'],
                event: 'workflow_dispatch', head_sha: 'a' * 40, head_branch: 'main']
        ReleaseProcess.requireResume(run, '123', 'NaoCraftLab/nclskins', '1.2.0.2', plan)
        [run + [path: '.github/workflows/other.yml'], run + [repository: [full_name: 'other/repo']],
         run + [head_sha: 'b' * 40], run + [head_branch: 'other'], run + [event: 'pull_request']].each { bad ->
            assertThrows(IllegalStateException) {
                ReleaseProcess.requireResume(bad, '123', 'NaoCraftLab/nclskins', '1.2.0.2', plan)
            }
        }
        assertThrows(IllegalStateException) {
            ReleaseProcess.requireResume(run, '123', 'NaoCraftLab/nclskins', '1.2.0.3', plan)
        }
    }
}

package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

import java.nio.file.Files

abstract class PlanReleaseTask extends DefaultTask {
    @Internal abstract DirectoryProperty getRepositoryDirectory()
    @Input abstract Property<String> getReleaseTag()
    @Input abstract Property<Boolean> getLocalPreparation()
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()

    PlanReleaseTask() {
        localPreparation.convention(false)
        workflowAdmission.convention(project.providers.environmentVariable('GITHUB_ACTIONS').map { it == 'true' }.orElse(false))
        resumeRun.convention(project.providers.environmentVariable('RESUME_RUN').orElse(''))
        outputs.upToDateWhen { false }
        notCompatibleWithConfigurationCache('Release planning performs live remote discovery')
    }

    @Input abstract Property<Boolean> getWorkflowAdmission()
    @Input abstract Property<String> getResumeRun()
    @Internal Closure workflowTransport
    @Internal Closure workflowEnvironment
    @Internal String admittedSourceBranch

    String workflowValue(String key) {
        workflowEnvironment != null ? workflowEnvironment.call(key) : System.getenv(key)
    }

    Map workflowRead(String path, int limit = 2097152) {
        String repository = workflowValue('GITHUB_REPOSITORY')
        if (repository != 'NaoCraftLab/nclskins') throw new IllegalStateException('Wrong release workflow repository')
        String url = "https://api.github.com/repos/${repository}/${path}".toString()
        workflowTransport != null ? workflowTransport.call(url, limit) :
                ReleaseHttp.readGithub(url, workflowValue('GITHUB_TOKEN'), limit)
    }

    Object workflowJson(String path) {
        Map response = workflowRead(path)
        if (response.status != 200 || (response.body as byte[]).length > 2097152) throw new IllegalStateException('Invalid workflow response')
        new groovy.json.JsonSlurper().parse(response.body as byte[])
    }

    @TaskAction
    void planRelease() {
        if (!workflowAdmission.getOrElse(false)) { planFresh(); return }
        if (localPreparation.get()) throw new IllegalStateException('Workflow cannot publish local preparation')
        File root = repositoryDirectory.get().asFile
        String requested = releaseTag.get()
        String resume = resumeRun.getOrElse('')
        if (resume && !(resume ==~ /[1-9][0-9]*/)) throw new IllegalStateException('Invalid resume run ID')
        Map admission = ReleaseProcess.admit(root, requested, workflowValue('EVENT_NAME'), workflowValue('EVENT_REF'),
                workflowValue('GITHUB_SHA'), !resume.isEmpty(), { String branch ->
                    Map reference = workflowJson("git/ref/heads/${branch}") as Map
                    if (reference.object?.type != 'commit') throw new IllegalStateException('Invalid workflow branch reference')
                    reference.object?.sha?.toString()
                })
        admittedSourceBranch = admission.branch.toString()
        Map plan
        if (resume) {
            plan = resumeBundle(resume, requested, admission)
        } else {
            planFresh()
            plan = ReleasePlan.load(new File(outputDirectory.get().asFile, 'release-plan.json'))
            if (plan.sourceCommit != admission.source) throw new IllegalStateException('Source drifted during planning')
        }
        String output = workflowValue('GITHUB_OUTPUT')
        if (!output) throw new IllegalStateException('Missing workflow output destination')
        ReleaseProcess.writeOutputs(new File(output), ReleaseProcess.ciOutputs(plan, admission.catalog.toString(), !resume.isEmpty()))
    }

    Map resumeBundle(String resume, String requested, Map admission) {
        String repository = workflowValue('GITHUB_REPOSITORY')
        Map run = workflowJson("actions/runs/${resume}") as Map
        if (run.path != '.github/workflows/release.yml' || run.repository?.full_name != repository ||
                run.id?.toString() != resume || !(run.event in ['push', 'workflow_dispatch'])) {
            throw new IllegalStateException('Invalid resume run provenance')
        }
        Map listing = workflowJson("actions/runs/${resume}/artifacts?per_page=100&page=1") as Map
        if (!(listing.artifacts instanceof List) || !(listing.total_count instanceof Number) ||
                listing.total_count != listing.artifacts.size() || listing.total_count > 100) {
            throw new IllegalStateException('Truncated resume artifact inventory')
        }
        List matches = listing.artifacts.findAll { it.name == "release-bundle-${requested}" }
        if (matches.size() != 1) throw new IllegalStateException('Missing or duplicate resume bundle')
        Map artifact = matches.first() as Map
        if (artifact.expired != false || !(artifact.id?.toString() ==~ /[1-9][0-9]*/) ||
                !(artifact.digest ==~ /sha256:[0-9a-f]{64}/) ||
                artifact.workflow_run?.id?.toString() != resume || artifact.workflow_run?.head_sha != run.head_sha ||
                !(artifact.size_in_bytes instanceof Number) || artifact.size_in_bytes <= 0 || artifact.size_in_bytes > 268435456) {
            throw new IllegalStateException('Unsafe or unbound resume artifact')
        }
        Map download = workflowRead("actions/artifacts/${artifact.id}/zip", 268435456)
        byte[] bytes = download.body as byte[]
        if (download.status != 200 || bytes.length != artifact.size_in_bytes ||
                CatalogTools.sha256(bytes) != artifact.digest.toString().substring(7)) {
            throw new IllegalStateException('Resume artifact digest or size mismatch')
        }
        File destination = new File(repositoryDirectory.get().asFile, 'release-bundle')
        File temporary = new File(repositoryDirectory.get().asFile, 'build/resume-bundle-staging')
        GenerateUpdateCatalogTask.validateOutput(repositoryDirectory.get().asFile.toPath(), destination.toPath(), [])
        GenerateUpdateCatalogTask.validateOutput(repositoryDirectory.get().asFile.toPath(), temporary.toPath(), [])
        if (java.nio.file.Files.exists(destination.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                java.nio.file.Files.exists(temporary.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException('Resume destination already exists')
        }
        try {
            ReleaseDownload.unpackActionsArtifact(bytes, temporary)
            Map manifest = PublicationSupport.loadManifest(temporary)
            Map plan = ReleasePlan.load(new File(temporary, 'release-plan.json'))
            ReleaseProcess.requireResume(run, resume, repository, requested, plan)
            ReleaseSelection.git(repositoryDirectory.get().asFile,
                    ['merge-base', '--is-ancestor', plan.sourceCommit.toString(), admission.lane.toString()])
            if (manifest.releasePlanDigest != plan.digest) throw new IllegalStateException('Resume bundle differs from plan')
            java.nio.file.Files.move(temporary.toPath(), destination.toPath())
            return plan
        } finally {
            if (temporary.exists()) temporary.deleteDir()
        }
    }

    void planFresh() {
        File repository = repositoryDirectory.get().asFile
        Map catalog = CatalogTools.loadCatalog(repository)
        String requested = releaseTag.get()
        Map release = ReleaseMetadata.prepare(new File(repository, 'gradle/version.properties'),
                new File(repository, 'gradle/plugin-version.properties'), new File(repository, 'CHANGELOG.md'),
                new File(repository, 'PLUGIN_CHANGELOG.md'), requested)
        String version = release.currentVersion
        boolean pluginOnly = release.componentKind == 'plugin-only'
        boolean local = localPreparation.get()
        String commit = ReleaseSelection.git(repository, ['rev-parse', 'HEAD']).trim()
        String branch = admittedSourceBranch ?: ReleaseSelection.git(repository, ['branch', '--show-current']).trim()
        if (pluginOnly && !local) ReleasePlan.requireSourceBranch(branch, version)
        String tagCommit = local ? null : ReleaseSelection.git(repository, ['rev-parse', "refs/tags/${version}^{commit}"]).trim()
        if (!local) ReleaseSelection.git(repository, ['merge-base', '--is-ancestor', tagCommit, commit])
        PublishPlatformsTask platforms = project.tasks.getByName('preflightReleasePlatforms') as PublishPlatformsTask
        PublishGithubReleaseTask github = project.tasks.getByName('preflightGithubRelease') as PublishGithubReleaseTask
        String githubToken = local ? null : github.requireEnvironment('GITHUB_TOKEN')
        String repositoryName = local ? null : github.requireRepository()
        if (!local) {
        Map tagReference = github.json(github.request('GET',
                "${github.apiBase()}/repos/${repositoryName}/git/ref/tags/${version}",
                github.headers(githubToken), null, null, [200] as Set<Integer>, githubToken)) as Map
        String remoteTagCommit = PublishGithubReleaseTask.resolveTagCommit(tagReference) { String object ->
            github.json(github.request('GET', "${github.apiBase()}/repos/${repositoryName}/git/tags/${object}",
                    github.headers(githubToken), null, null, [200] as Set<Integer>, githubToken)) as Map
        }
        if (remoteTagCommit != tagCommit) throw new IllegalStateException('Remote tag changed before planning')
        if (pluginOnly) {
            Map sourceReference = github.json(github.request('GET',
                    "${github.apiBase()}/repos/${repositoryName}/git/ref/heads/${branch}",
                    github.headers(githubToken), null, null, [200] as Set<Integer>, githubToken)) as Map
            if (sourceReference.object?.type != 'commit' || sourceReference.object?.sha != commit) {
                throw new IllegalStateException('Remote source branch changed before planning')
            }
        }
        }
        String modrinthToken = local ? null : platforms.requireSecret('MODRINTH_TOKEN')
        String curseKey = local ? null : platforms.requireSecret('CURSEFORGE_API_KEY')
        String curseUploadToken = local ? null : platforms.requireSecret('CURSEFORGE_UPLOAD_TOKEN')
        Map existing = local ? null : github.findRelease(github.apiBase(), repositoryName, version, githubToken)
        if (pluginOnly && !local && existing == null) throw new IllegalStateException('Plugin-only requires an existing GitHub Release')
        File output = outputDirectory.get().asFile
        if (output.exists() && !output.deleteDir()) throw new IllegalStateException('Cannot reset release plan output')
        Files.createDirectories(new File(output, 'assets').toPath())
        Map state = pluginState(repository, catalog, requested)
        String pluginNotes = pluginReleaseNotes(repository, state)
        List<Map> components = (pluginOnly ? [] : CatalogTools.releaseTargets(catalog)).collect { Map target ->
            AssembleReleaseTask.publicationTarget(catalog, target, release,
                    ReleasePlan.placeholder(AssembleReleaseTask.artifactName(target, version), 'mod', target.id.toString()),
                    ReleasePlan.placeholder(AssembleReleaseTask.sourceArtifactName(target, version), 'mod-sources', target.id.toString()))
        }
        String pluginVersion = state.pluginVersion ?: version
        String pluginFile = "nclskins-plugin-${pluginVersion}.jar"
        boolean existingPlugin = existing?.assets?.any { it.name == pluginFile }
        if (state.publish || existingPlugin) {
            List<String> games = AssembleReleaseTask.serverPluginGameVersions(catalog)
            components.add([id: 'server-plugin', kind: 'server-plugin', name: "${pluginVersion}+universal".toString(),
                    versionNumber: pluginVersion, channel: release.channel, minecraftVersion: games.first(),
                    gameVersions: games, loaders: AssembleReleaseTask.serverPluginLoaders(catalog.serverPlugin.compatibility as Map),
                    javaRelease: 17, javaReleases: AssembleReleaseTask.serverPluginJavaReleases(catalog),
                    environment: 'server', dependencies: [modrinth: [], curseforge: []],
                    platforms: catalog.serverPlugin.platforms, releaseNotes: pluginNotes,
                    asset: ReleasePlan.placeholder(pluginFile, 'server-plugin', null),
                    sourcesAsset: ReleasePlan.placeholder(pluginFile.replace('.jar', '-sources.jar'), 'server-plugin-sources', null)])
        }
        Map manifest = [platforms: catalog.mod.platforms, releaseNotes: [text: release.notes]]
        Map inventories = local ? [modrinth: components.collectEntries { [(it.id): []] },
                curseforge: components.collectEntries { [(it.id): []] }] :
                platforms.fetchAll(manifest, components, modrinthToken, curseKey)
        List<Map> githubAssets = existing == null ? [] : existing.assets as List<Map>
        Map replacement = previousPluginAsset(components.find { it.id == 'server-plugin' }, githubAssets)
        if (replacement != null) {
            replacement = [file: replacement.name, id: replacement.id,
                           sha256: github.remoteSha256(github.apiBase(), repositoryName, replacement, githubToken)]
        }
        Map<String, Map> githubByComponent = components.collectEntries { Map component ->
            [(component.id.toString()): findGithubAsset(component, githubAssets)]
        }
        Set<String> matchedGithubNames = githubByComponent.values().findAll { it != null }
                .collect { it.name.toString() } as Set<String>
        if (replacement != null) matchedGithubNames.add(replacement.file.toString())
        if ((pluginOnly ? githubAssets.findAll { it.name.toString().startsWith('nclskins-plugin-') }.size() : githubAssets.size()) != matchedGithubNames.size() ||
                githubAssets.collect { it.name }.toSet().size() != githubAssets.size()) {
            throw new IllegalStateException('Unknown or duplicate existing GitHub asset')
        }
        List<Map> preservedGithub = []
        if (pluginOnly) {
            githubAssets.findAll { !it.name.toString().startsWith('nclskins-plugin-') }.each { Map asset ->
                Set<String> allowed = CatalogTools.releaseTargets(catalog).collect { AssembleReleaseTask.artifactName(it, version) } as Set<String>
                if (!allowed.contains(asset.name.toString())) throw new IllegalStateException('Unknown historical mod asset')
                preservedGithub.add([file: asset.name, id: asset.id, sha256: github.remoteSha256(github.apiBase(), repositoryName, asset, githubToken)])
            }
        }
        components.each { Map component ->
            Map remote = [modrinth: inventories.modrinth[component.id], curseforge: inventories.curseforge[component.id]]
            Map githubAsset = githubByComponent[component.id]
            if (githubAsset != null) adoptPreservedPluginCoordinate(component, remote)
            boolean recovered = recoverPair(component, remote, githubAssets, output, this.&download)
            Map classification = ReleasePlan.classify(component, remote, recovered, githubAsset != null)
            if (component.id == 'server-plugin' && classification.build) {
                requirePluginBuildAdvance(component, remote)
            }
            component.build = classification.build
            component.preserve = classification.preserve
            component.states = classification.states
            if (githubAsset != null) {
                if (!classification.preserve) {
                    throw new IllegalStateException("${component.id}: published component requires repair; refusing implicit marketplace writes")
                }
                String digest = github.remoteSha256(github.apiBase(), repositoryName, githubAsset, githubToken)
                if (digest != component.asset.sha256) throw new IllegalStateException("${component.id}: GitHub bytes differ")
                preservedGithub.add([file: githubAsset.name, assetFile: component.asset.file,
                                     sha256: digest, id: githubAsset.id])
            }
        }
        if (!local) verifyAvailability(platforms, components.findAll { !it.preserve }, modrinthToken, curseUploadToken)
        Map plan = ReleasePlan.seal([schemaVersion: 2, version: version, requestedVersion: requested, componentKind: release.componentKind,
                mode: local ? 'local' : existing == null ? 'tag' : 'backfill', sourceCommit: commit, tagCommit: tagCommit,
                sourceBranch: branch,
                catalogCommit: commit, release: release, serverState: state, pluginNotes: pluginNotes,
                platforms: catalog.mod.platforms, existingRelease: existing == null ? null :
                    [id: existing.id, body: existing.body, name: existing.name, prerelease: existing.prerelease],
                preservedGithub: preservedGithub, pluginReplacement: replacement, components: components,
                buildTargetIds: components.findAll { it.build && it.id != 'server-plugin' }.collect { it.id },
                buildPlugin: components.any { it.build && it.id == 'server-plugin' }])
        Files.writeString(new File(output, 'release-plan.json').toPath(), CatalogTools.json(plan))
        String summary = "## Release ${version}\n\nSource: `${commit}`\n\n| Component | Build | Preserve | Modrinth | CurseForge |\n|---|---|---|---|---|\n" +
                components.collect { "| ${it.id} | ${it.build} | ${it.preserve} | ${it.states.modrinth.action} | ${it.states.curseforge.action} |" }.join('\n') + '\n'
        logger.lifecycle(summary)
        String summaryPath = System.getenv('GITHUB_STEP_SUMMARY')
        if (summaryPath) new File(summaryPath) << summary
    }

    File download(String url, File destination, Map hashes) {
        ReleaseDownload.fetch(url, destination, hashes)
    }

    Map pluginState(File repository, Map catalog, String version) {
        ServerPluginReleaseState.compute(repository, catalog, version)
    }

    String pluginReleaseNotes(File repository, Map state) {
        ServerPluginChangelog.validate(new File(repository, 'PLUGIN_CHANGELOG.md'), state)
    }

    static Map previousPluginAsset(Map component, List<Map> assets) {
        if (component == null) return null
        Map current = findGithubAsset(component, assets)
        List<Map> previous = assets.findAll {
            it.name?.toString()?.startsWith('nclskins-plugin-') && it != current
        }
        if (previous.size() > 1) throw new IllegalStateException('Multiple previous plugin assets')
        if (previous.isEmpty()) return null
        Map asset = previous.first()
        def matcher = asset.name.toString() =~ /^nclskins-plugin-(.+)\.jar$/
        if (!matcher.matches()) throw new IllegalStateException('Unknown plugin asset')
        String oldVersion = matcher.group(1)
        String newVersion = component.versionNumber.toString()
        if (ServerPluginVersion.parts(oldVersion).base != ServerPluginVersion.parts(newVersion).base ||
                ServerPluginVersion.compare(newVersion, oldVersion) <= 0) {
            throw new IllegalStateException('Plugin replacement must advance the build within the same version')
        }
        asset
    }

    static Map findGithubAsset(Map component, List<Map> assets) {
        String file = component.asset.file.toString()
        String stem = file.endsWith('.jar') ? file.substring(0, file.length() - 4) : file
        List<Map> matches = assets.findAll { Map asset ->
            String name = asset.name?.toString()
            name == file || (component.id == 'server-plugin' &&
                    CatalogTools.VERSION_PATTERN.matcher(component.versionNumber.toString()).matches() &&
                    name ==~ /${java.util.regex.Pattern.quote(stem)}\.[1-9][0-9]*\.jar/)
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("${component.id}: multiple GitHub asset aliases")
        }
        matches ? matches.first() : null
    }

    static void requirePluginBuildAdvance(Map component, Map remote) {
        String desired = component.versionNumber.toString()
        Map desiredParts = ServerPluginVersion.parts(desired)
        Set<BigInteger> previousBuilds = [] as Set<BigInteger>
        ['modrinth', 'curseforge'].each { String platform ->
            (remote[platform] as List<Map>).findAll {
                PublicationSupport.normalizedChannel(platform, it) == component.channel &&
                        !(platform == 'curseforge' && PublicationSupport.curseForgeChild(it))
            }.each { Map entry ->
                String previous = PublicationSupport.normalizedVersion(platform, entry)
                if (ServerPluginVersion.compare(desired, previous) <= 0) {
                    throw new IllegalStateException('New plugin build must be newer than published marketplace versions')
                }
                Map previousParts = ServerPluginVersion.parts(previous)
                if (previousParts.base == desiredParts.base) previousBuilds.add(previousParts.build as BigInteger)
            }
        }
        boolean hasPublished = ['modrinth', 'curseforge'].any { remote[it] }
        BigInteger expected = previousBuilds.isEmpty() ? (hasPublished ? BigInteger.ONE : desiredParts.build) : previousBuilds.max() + BigInteger.ONE
        if (desiredParts.build != expected) {
            throw new IllegalStateException("Next plugin build must be ${expected} in ${desiredParts.base}")
        }
    }

    static void adoptPreservedPluginCoordinate(Map component, Map remote) {
        if (component.id != 'server-plugin') return
        String asset = component.asset.file.toString()
        Map<String, List<Map>> matches = [
                modrinth : (remote.modrinth as List<Map>).findAll {
                    PublicationSupport.normalizedChannel('modrinth', it) == component.channel &&
                            PublicationSupport.normalizedFileName('modrinth', it) == asset
                },
                curseforge: (remote.curseforge as List<Map>).findAll {
                    !PublicationSupport.curseForgeChild(it) &&
                            PublicationSupport.normalizedChannel('curseforge', it) == component.channel &&
                            PublicationSupport.normalizedFileName('curseforge', it) == asset
                }
        ]
        if (matches.values().any { it.size() > 1 }) {
            throw new IllegalStateException('server-plugin: duplicate preserved artifact coordinate')
        }
        List<Map> coordinates = matches.collectMany { String platform, List<Map> entries ->
            entries.collect { Map entry ->
                [name: PublicationSupport.normalizedName(platform, entry),
                 version: PublicationSupport.normalizedVersion(platform, entry)]
            }
        }
        if (coordinates.isEmpty()) return
        Set<String> names = coordinates.collect { it.name?.toString() } as Set<String>
        Set<String> versions = coordinates.collect { it.version?.toString() } as Set<String>
        if (names.size() != 1 || versions.size() != 1) {
            throw new IllegalStateException('server-plugin: preserved marketplace coordinates differ')
        }
        String version = versions.first()
        String base = component.versionNumber.toString()
        if (!(version == base || version ==~ /${java.util.regex.Pattern.quote(base)}\.[1-9][0-9]*/) ||
                names.first() != "${version}+universal") {
            throw new IllegalStateException('server-plugin: invalid preserved marketplace coordinate')
        }
        component.name = names.first()
        component.versionNumber = version
    }

    static boolean recoverPair(Map component, Map remote, List<Map> githubAssets, File output,
            Closure<File> download = ReleaseDownload.&fetch) {
        List<Map> mr = (remote.modrinth as List<Map>).findAll { it.name == component.name && it.version_type == component.channel }
        List<Map> cf = (remote.curseforge as List<Map>).findAll { it.fileName == component.asset.file }
        if (mr.size() > 1 || cf.size() > 1) throw new IllegalStateException("${component.id}: duplicate coordinate")
        Map primary = mr ? (mr.first().files as List<Map>).find { it.primary == true } : null
        Map source = mr ? (mr.first().files as List<Map>).find { it.filename == component.sourcesAsset.file && it.file_type == 'sources-jar' } : null
        Map cfSource = cf ? (remote.curseforge as List<Map>).find {
            it.fileName == component.sourcesAsset.file && it.parentProjectFileId?.toString() == cf.first().id.toString()
        } : null
        boolean anyExisting = mr || cf || githubAssets.any { it.name == component.asset.file }
        if (!anyExisting) return false
        if ((primary == null && !cf) || (source == null && cfSource == null)) {
            throw new IllegalStateException("${component.id}: exact published pair unavailable; resume the original verified bundle")
        }
        Map productionHashes = primary ? primary.hashes as Map : curseHashes(cf.first())
        Map sourceHashes = source ? source.hashes as Map : curseHashes(cfSource)
        File production = download.call((primary ? primary.url : cf.first().downloadUrl).toString(),
                new File(output, "assets/${component.asset.file}"), productionHashes)
        File sources = download.call((source ? source.url : cfSource.downloadUrl).toString(),
                new File(output, "assets/${component.sourcesAsset.file}"), sourceHashes)
        component.asset = AssembleReleaseTask.assetMetadata(production, component.asset.kind.toString(), component.asset.target?.toString())
        component.sourcesAsset = AssembleReleaseTask.assetMetadata(sources, component.sourcesAsset.kind.toString(), component.sourcesAsset.target?.toString())
        true
    }

    static Map curseHashes(Map file) {
        Map hash = (file.hashes as List<Map>).find { it.algo == 1 }
        if (hash == null) throw new IllegalStateException('CurseForge file has no SHA-1')
        [sha1: hash.value]
    }

    static void verifyAvailability(PublishPlatformsTask client, List<Map> components, String token, String key) {
        if (components.isEmpty()) return
        String base = client.apiBase('MODRINTH_API_BASE', 'https://api.modrinth.com/v2')
        List games = client.json(client.request('GET', "${base}/tag/game_version", [:], null, null, [200] as Set<Integer>, [])) as List
        List loaders = client.json(client.request('GET', "${base}/tag/loader", [:], null, null, [200] as Set<Integer>, [])) as List
        client.request('GET', "${base}/user", ['Authorization': token], null, null, [200] as Set<Integer>, [token])
        List cf = client.json(client.request('GET', "${client.apiBase('CURSEFORGE_UPLOAD_API_BASE', 'https://minecraft.curseforge.com')}/api/game/versions",
                ['X-Api-Token': key], null, null, [200] as Set<Integer>, [key])) as List
        Set<String> cfVersions = cf.collect { it.name.toString() } as Set<String>
        components.each { Map component ->
            if (!(games.collect { it.version }.containsAll(component.gameVersions as List)) ||
                    !(loaders.collect { it.name }.containsAll(PublicationSupport.desiredLoaders(component))) ||
                    !cfVersions.containsAll(PublicationSupport.curseForgeMetadata([releaseNotes: [text: '']], component).gameVersionNames as List)) {
                throw new IllegalStateException("${component.id}: platform game version or loader is not available")
            }
        }
    }
}

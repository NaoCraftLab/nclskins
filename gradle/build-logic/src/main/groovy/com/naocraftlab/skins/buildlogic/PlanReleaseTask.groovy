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
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()

    PlanReleaseTask() {
        outputs.upToDateWhen { false }
        notCompatibleWithConfigurationCache('Release planning performs live remote discovery')
    }

    @TaskAction
    void planRelease() {
        File repository = repositoryDirectory.get().asFile
        Map catalog = CatalogTools.loadCatalog(repository)
        CatalogTools.validate(repository, catalog)
        String version = releaseTag.get()
        Map release = ReleaseMetadata.validate(new File(repository, 'gradle/version.properties'),
                new File(repository, 'CHANGELOG.md'), version)
        String commit = ReleaseSelection.git(repository, ['rev-parse', 'HEAD']).trim()
        String tagCommit = ReleaseSelection.git(repository, ['rev-parse', "refs/tags/${version}^{commit}"]).trim()
        ReleaseSelection.git(repository, ['merge-base', '--is-ancestor', tagCommit, commit])
        PublishPlatformsTask platforms = project.tasks.getByName('preflightReleasePlatforms') as PublishPlatformsTask
        PublishGithubReleaseTask github = project.tasks.getByName('preflightGithubRelease') as PublishGithubReleaseTask
        String githubToken = github.requireEnvironment('GITHUB_TOKEN')
        String repositoryName = github.requireRepository()
        Map tagReference = github.json(github.request('GET',
                "${github.apiBase()}/repos/${repositoryName}/git/ref/tags/${version}",
                github.headers(githubToken), null, null, [200] as Set<Integer>, githubToken)) as Map
        String remoteTagCommit = PublishGithubReleaseTask.resolveTagCommit(tagReference) { String object ->
            github.json(github.request('GET', "${github.apiBase()}/repos/${repositoryName}/git/tags/${object}",
                    github.headers(githubToken), null, null, [200] as Set<Integer>, githubToken)) as Map
        }
        if (remoteTagCommit != tagCommit) throw new IllegalStateException('Remote tag changed before planning')
        String modrinthToken = platforms.requireSecret('MODRINTH_TOKEN')
        String curseKey = platforms.requireSecret('CURSEFORGE_API_KEY')
        String curseUploadToken = platforms.requireSecret('CURSEFORGE_UPLOAD_TOKEN')
        Map existing = github.findRelease(github.apiBase(), repositoryName, version, githubToken)
        File output = outputDirectory.get().asFile
        if (output.exists() && !output.deleteDir()) throw new IllegalStateException('Cannot reset release plan output')
        Files.createDirectories(new File(output, 'assets').toPath())
        Map state = ServerPluginReleaseState.compute(repository, catalog, version)
        String pluginNotes = ServerPluginChangelog.validate(new File(repository, 'PLUGIN_CHANGELOG.md'), state)
        List<Map> components = CatalogTools.releaseTargets(catalog).collect { Map target ->
            AssembleReleaseTask.publicationTarget(catalog, target, release,
                    ReleasePlan.placeholder(AssembleReleaseTask.artifactName(target, version), 'mod', target.id.toString()),
                    ReleasePlan.placeholder(AssembleReleaseTask.sourceArtifactName(target, version), 'mod-sources', target.id.toString()))
        }
        String pluginFile = "nclskins-plugin-${version}.jar"
        boolean existingPlugin = existing?.assets?.any { it.name == pluginFile }
        if (state.publish || existingPlugin) {
            List<String> games = AssembleReleaseTask.serverPluginGameVersions(catalog)
            components.add([id: 'server-plugin', kind: 'server-plugin', name: "${version}+universal".toString(),
                    versionNumber: version, channel: release.channel, minecraftVersion: games.first(),
                    gameVersions: games, loaders: AssembleReleaseTask.serverPluginLoaders(catalog.serverPlugin.compatibility as Map),
                    javaRelease: 17, javaReleases: AssembleReleaseTask.serverPluginJavaReleases(catalog),
                    environment: 'server', dependencies: [modrinth: [], curseforge: []],
                    platforms: catalog.serverPlugin.platforms, releaseNotes: pluginNotes,
                    asset: ReleasePlan.placeholder(pluginFile, 'server-plugin', null),
                    sourcesAsset: ReleasePlan.placeholder(pluginFile.replace('.jar', '-sources.jar'), 'server-plugin-sources', null)])
        }
        Map manifest = [platforms: catalog.mod.platforms, releaseNotes: [text: release.notes]]
        Map inventories = platforms.fetchAll(manifest, components, modrinthToken, curseKey)
        List<Map> githubAssets = existing == null ? [] : existing.assets as List<Map>
        Set<String> allowed = components.collect { it.asset.file } as Set<String>
        if (githubAssets.any { !allowed.contains(it.name) } ||
                githubAssets.collect { it.name }.toSet().size() != githubAssets.size()) {
            throw new IllegalStateException('Unknown or duplicate existing GitHub asset')
        }
        List<Map> preservedGithub = []
        components.each { Map component ->
            Map remote = [modrinth: inventories.modrinth[component.id], curseforge: inventories.curseforge[component.id]]
            boolean recovered = recoverPair(component, remote, githubAssets, output, this.&download)
            Map classification = ReleasePlan.classify(component, remote, recovered)
            component.build = classification.build
            component.preserve = classification.preserve
            component.states = classification.states
            Map githubAsset = githubAssets.find { it.name == component.asset.file }
            if (githubAsset != null) {
                String digest = github.remoteSha256(github.apiBase(), repositoryName, githubAsset, githubToken)
                if (digest != component.asset.sha256) throw new IllegalStateException("${component.id}: GitHub bytes differ")
                preservedGithub.add([file: component.asset.file, sha256: digest, id: githubAsset.id])
            }
        }
        verifyAvailability(platforms, components.findAll { !it.preserve }, modrinthToken, curseUploadToken)
        Map plan = ReleasePlan.seal([schemaVersion: 1, version: version,
                mode: existing == null ? 'tag' : 'backfill', sourceCommit: commit, tagCommit: tagCommit,
                catalogCommit: commit, release: release, serverState: state, pluginNotes: pluginNotes,
                platforms: catalog.mod.platforms, existingRelease: existing == null ? null :
                    [id: existing.id, body: existing.body, name: existing.name, prerelease: existing.prerelease],
                preservedGithub: preservedGithub, components: components,
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

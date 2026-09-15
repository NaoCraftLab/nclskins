package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

import java.nio.file.Files
import java.nio.file.StandardCopyOption

abstract class AssemblePlannedReleaseTask extends DefaultTask {
    @Internal abstract DirectoryProperty getRepositoryDirectory()
    @InputFile abstract RegularFileProperty getPlanFile()
    @InputDirectory abstract DirectoryProperty getComponentDirectory()
    @OutputDirectory abstract DirectoryProperty getReleaseRoot()

    @TaskAction
    void assemble() {
        File repository = repositoryDirectory.get().asFile
        Map plan = ReleasePlan.load(planFile.get().asFile)
        ReleasePlan.requireCheckout(repository, plan)
        File output = new File(releaseRoot.get().asFile, plan.version.toString())
        File assetsDirectory = new File(output, 'assets')
        if (assetsDirectory.exists() && !assetsDirectory.deleteDir()) throw new IllegalStateException('Cannot reset bundle assets')
        Files.createDirectories(assetsDirectory.toPath())
        Map catalog = CatalogTools.loadCatalog(repository)
        List<Map> components = CatalogTools.materialize(plan.components) as List<Map>
        List<Map> assets = []
        components.each { Map component ->
            Map receipt = null
            if (component.build) {
                File receiptFile = new File(componentDirectory.get().asFile, "${component.id}.receipt.json")
                receipt = CatalogTools.materialize(new groovy.json.JsonSlurper().parse(receiptFile)) as Map
                if (receipt.planDigest != plan.digest || receipt.sourceCommit != plan.sourceCommit || receipt.componentId != component.id) {
                    throw new IllegalStateException('Component receipt belongs to another plan or commit')
                }
                File receipts = new File(output, 'receipts')
                Files.createDirectories(receipts.toPath())
                Files.copy(receiptFile.toPath(), new File(receipts, receiptFile.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            ['asset', 'sourcesAsset'].each { String key ->
                Map expected = component[key] as Map
                File file = new File(componentDirectory.get().asFile, expected.file.toString())
                if (!file.isFile() || Files.isSymbolicLink(file.toPath())) {
                    throw new IllegalStateException("Missing component artifact: ${expected.file}")
                }
                Map actual = AssembleReleaseTask.assetMetadata(file, expected.kind.toString(), expected.target?.toString())
                if (!component.build && actual != expected) throw new IllegalStateException("Recovered bytes changed: ${file.name}")
                if (component.build && !(receipt.assets as List).any { it == actual }) {
                    throw new IllegalStateException('Component bytes differ from verified build receipt')
                }
                if (component.build && key == 'asset' && component.id != 'server-plugin') {
                    AssembleReleaseTask.requireServerPluginBaseline(file, plan.serverState.activeVersion.toString())
                }
                File destination = new File(assetsDirectory, file.name)
                Files.copy(file.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                component[key] = actual
                assets.add(actual)
            }
        }
        Map server = new LinkedHashMap(plan.serverState as Map) +
                [publish: false, artifact: null, sourcesArtifact: null, publication: null, publications: [:]]
        Map plugin = components.find { it.id == 'server-plugin' }
        if (plugin != null) {
            server += [publish: true, artifact: plugin.asset, sourcesArtifact: plugin.sourcesAsset, publication: plugin]
        }
        List<Map> targets = components.findAll { it.id != 'server-plugin' }
        AssembleReleaseTask.requireUniqueArtifactContents(targets)
        String notes = plan.release.notes.toString()
        File notesFile = new File(output, 'release-notes.md')
        Files.writeString(notesFile.toPath(), notes)
        Map manifest = [schemaVersion: 4, mode: plan.mode, version: plan.version,
                channel: plan.release.channel, prerelease: plan.release.prerelease,
                sourceCommit: plan.sourceCommit, tagCommit: plan.tagCommit, baseTag: null,
                targetCount: targets.size(), selectedTargetIds: targets.collect { it.id },
                selection: [paths: [], reasons: [:]], platforms: catalog.mod.platforms,
                releaseNotes: [file: notesFile.name, sha256: ReleaseBundle.sha256(notesFile), text: notes],
                targets: targets, serverPlugin: server, assets: assets,
                releasePlanDigest: plan.digest, preservedTargetIds: components.findAll { it.preserve }.collect { it.id },
                existingRelease: plan.existingRelease, preservedGithub: plan.preservedGithub,
                pluginReplacement: plan.pluginReplacement]
        Files.writeString(new File(output, 'release-manifest.json').toPath(), CatalogTools.json(manifest))
        Files.copy(planFile.get().asFile.toPath(), new File(output, 'release-plan.json').toPath(), StandardCopyOption.REPLACE_EXISTING)
        PublicationSupport.loadManifest(output)
        logger.lifecycle("Assembled ${plan.version}: built ${plan.buildTargetIds}, plugin build=${plan.buildPlugin}, preserved ${manifest.preservedTargetIds}")
    }
}

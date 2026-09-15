package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

import java.nio.file.Files
import java.nio.file.StandardCopyOption

abstract class ExportReleaseComponentTask extends DefaultTask {
    @Internal abstract DirectoryProperty getRepositoryDirectory()
    @InputFile abstract RegularFileProperty getPlanFile()
    @Input abstract Property<String> getComponentId()
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()

    @TaskAction
    void exportComponent() {
        File root = repositoryDirectory.get().asFile
        Map plan = ReleasePlan.load(planFile.get().asFile)
        ReleasePlan.requireCheckout(root, plan)
        Map component = (plan.components as List<Map>).find { it.id == componentId.get() && it.build }
        if (component == null) throw new IllegalStateException('Component is not selected for build in the release plan')
        Map catalog = CatalogTools.loadCatalog(root)
        String path = component.id == 'server-plugin' ? 'server-plugin' :
                CatalogTools.selectTarget(catalog, component.id.toString()).path.toString()
        File output = outputDirectory.get().asFile
        Files.createDirectories(output.toPath())
        List<Map> assets = []
        ['asset', 'sourcesAsset'].each { String key ->
            Map expected = component[key] as Map
            File file = new File(root, "${path}/build/libs/${expected.file}")
            if (!file.isFile() || Files.isSymbolicLink(file.toPath())) throw new IllegalStateException('Missing built artifact')
            Map metadata = AssembleReleaseTask.assetMetadata(file, expected.kind.toString(), expected.target?.toString())
            Files.copy(file.toPath(), new File(output, file.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            assets.add(metadata)
        }
        Files.writeString(new File(output, "${component.id}.receipt.json").toPath(), CatalogTools.json(
                [planDigest: plan.digest, sourceCommit: plan.sourceCommit, componentId: component.id, assets: assets]))
    }
}

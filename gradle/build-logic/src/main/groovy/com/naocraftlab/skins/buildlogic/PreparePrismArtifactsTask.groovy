package com.naocraftlab.skins.buildlogic

import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class PreparePrismArtifactsTask extends TargetBuildTask {
    @Internal Closure<Map> snapshotProvider
    @Internal boolean forceBuild
    @Internal boolean cleanBuild

    @Override
    @TaskAction
    void buildTargets() {
        File root = repositoryDirectory.get().asFile
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        Map before = snapshotProvider.call()
        Map status = BuildReceipt.status(root, catalog, before)
        if (!forceBuild && !cleanBuild && status.reusable) {
            logger.lifecycle('Reusing verified Prism artifacts (' + status.receipt.verificationLevel + ')')
            return
        }
        BuildReceipt.invalidate(root)
        buildFreshArtifacts()
        BuildReceipt.publish(root, catalog, before, snapshotProvider.call(), cleanBuild ? 'clean' : 'incremental')
    }
    void buildFreshArtifacts() {
        super.buildTargets()
    }
}

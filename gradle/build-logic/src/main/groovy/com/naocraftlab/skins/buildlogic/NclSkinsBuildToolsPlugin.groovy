package com.naocraftlab.skins.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

final class NclSkinsBuildToolsPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.extensions.extraProperties.set('nclskinsCatalogTools', CatalogTools)
        project.extensions.extraProperties.set('nclskinsMetadataRenderer', MetadataRenderer)
        project.extensions.extraProperties.set('nclskinsLoaderBackend', LoaderBackend)
        project.extensions.extraProperties.set('nclskinsRunLayout', RunLayout)
        project.extensions.extraProperties.set(
                'nclskinsExternalArtifactIntegrity', ExternalArtifactIntegrity)
        project.extensions.extraProperties.set('nclskinsCapabilityAbiTaskType', CapabilityAbiTask)
        project.extensions.extraProperties.set('nclskinsVerifyModMenuAbiTaskType', VerifyModMenuAbiTask)
        project.extensions.extraProperties.set('nclskinsGenerateMetadataTaskType', GenerateMetadataTask)
        project.extensions.extraProperties.set('nclskinsGenerateUpdateCatalogTaskType', GenerateUpdateCatalogTask)
        project.extensions.extraProperties.set('nclskinsVerifyUpdateCatalogDeploymentTaskType', VerifyUpdateCatalogDeploymentTask)
        project.extensions.extraProperties.set('nclskinsGenerateTargetBindingsTaskType', GenerateTargetBindingsTask)
        project.extensions.extraProperties.set('nclskinsVerifyCatalogTaskType', VerifyCatalogTask)
        project.extensions.extraProperties.set('nclskinsVerifyPublicationTreeTaskType', VerifyPublicationTreeTask)
        project.extensions.extraProperties.set('nclskinsServerPluginFingerprint', ServerPluginFingerprint)
        project.extensions.extraProperties.set('nclskinsVerifyServerPluginArtifactTaskType', VerifyServerPluginArtifactTask)
        project.extensions.extraProperties.set('nclskinsComputeServerPluginReleaseStateTaskType', ComputeServerPluginReleaseStateTask)
    }
}

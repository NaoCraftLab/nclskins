package com.naocraftlab.skins.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment

final class NclSkinsBuildToolsPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        if (project == project.rootProject && new File(project.projectDir, 'gradle/targets.json').isFile()) {
            project.repositories.maven {
                url = project.uri('https://maven.fabricmc.net/')
                content { includeGroup 'net.fabricmc' }
            }
            def runtime = project.configurations.create('sneakyCapeCompositionRuntime') {
                transitive = false
                attributes.attribute(Usage.USAGE_ATTRIBUTE,
                        project.objects.named(Usage, Usage.JAVA_RUNTIME))
                attributes.attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                        project.objects.named(TargetJvmEnvironment, TargetJvmEnvironment.STANDARD_JVM))
            }
            [
                    'net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7',
                    'io.github.llamalad7:mixinextras-common:0.5.3',
                    'org.ow2.asm:asm:9.9.1',
                    'org.ow2.asm:asm-tree:9.9.1',
                    'org.ow2.asm:asm-commons:9.9.1',
                    'org.ow2.asm:asm-util:9.9.1',
                    'org.ow2.asm:asm-analysis:9.9.1',
                    'com.google.guava:guava:32.1.2-jre',
                    'com.google.code.gson:gson:2.10.1',
                    'org.slf4j:slf4j-api:2.0.9',
                    'com.mojang:authlib:9.0.75'
            ].each { String coordinate -> project.dependencies.add(runtime.name, coordinate) }
        }
        project.extensions.extraProperties.set('nclskinsCatalogTools', CatalogTools)
        project.extensions.extraProperties.set('nclskinsNativeModelTests', NativeModelTests)
        project.extensions.extraProperties.set('nclskinsMetadataRenderer', MetadataRenderer)
        project.extensions.extraProperties.set('nclskinsLoaderBackend', LoaderBackend)
        project.extensions.extraProperties.set('nclskinsRunLayout', RunLayout)
        project.extensions.extraProperties.set(
                'nclskinsExternalArtifactIntegrity', ExternalArtifactIntegrity)
        project.extensions.extraProperties.set('nclskinsCapabilityAbiTaskType', CapabilityAbiTask)
        project.extensions.extraProperties.set('nclskinsVerifyModMenuAbiTaskType', VerifyModMenuAbiTask)
        project.extensions.extraProperties.set('nclskinsVerifyOptifineCapeAbiTaskType', VerifyOptifineCapeAbiTask)
        project.extensions.extraProperties.set('nclskinsVerifyOptifineCapeCompositionTaskType', VerifyOptifineCapeCompositionTask)
        project.extensions.extraProperties.set('nclskinsVerifyWaveyCapesAbiTaskType', VerifyWaveyCapesAbiTask)
        project.extensions.extraProperties.set('nclskinsGenerateMetadataTaskType', GenerateMetadataTask)
        project.extensions.extraProperties.set('nclskinsGenerateUpdateCatalogTaskType', GenerateUpdateCatalogTask)
        project.extensions.extraProperties.set('nclskinsVerifyUpdateCatalogDeploymentTaskType', VerifyUpdateCatalogDeploymentTask)
        project.extensions.extraProperties.set('nclskinsGenerateTargetBindingsTaskType', GenerateTargetBindingsTask)
        project.extensions.extraProperties.set('nclskinsGenerateBlockbenchPngTaskType', BlockbenchPng.GenerateTask)
        project.extensions.extraProperties.set('nclskinsVerifyCatalogTaskType', VerifyCatalogTask)
        project.extensions.extraProperties.set('nclskinsVerifyPublicationTreeTaskType', VerifyPublicationTreeTask)
        project.extensions.extraProperties.set('nclskinsServerPluginFingerprint', ServerPluginFingerprint)
        project.extensions.extraProperties.set('nclskinsVerifyServerPluginArtifactTaskType', VerifyServerPluginArtifactTask)
        project.extensions.extraProperties.set('nclskinsComputeServerPluginReleaseStateTaskType', ComputeServerPluginReleaseStateTask)
    }
}

package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.assertTrue

final class CpmWorkerRegistrationTest {
    @Test
    void seamlessFollowupBindingsAndNativeControlsAreRegistered() {
        File repository = new File('../..').canonicalFile
        def config = new groovy.json.JsonSlurper().parse(new File(repository,
                'compat/cpm/src/main/resources/nclskins.cpm.mixins.json'))
        String source = new File(repository, 'gradle/build-logic/src/main/groovy/com/naocraftlab/skins/buildlogic/VerifyCpmIntegrationTask.groovy').text
        for (String name : ['CpmAnimatedTextureMixin', 'CpmDefinitionResourceMixin', 'CpmProjectTextureMixin'])
            assertTrue(config.client.contains(name), "Production registration: ${name}")
        for (String label : ['preview-resource-transform', 'preview-resource-server', 'owned-preview-resource-dispatch',
                'project-defaults-setup', 'project-defaults', 'project-defaults-missing-texture-binding', 'project-defaults-uuid-preview'])
            assertTrue(source.contains("'${label}'"), "Canonical followup probe: ${label}")
        assertTrue(source.contains("'project-defaults-setup', [projectTransforms] + hostClasspath"))
        assertTrue(source.contains("'project-defaults', [defaultsHost, projectTransforms] + hostClasspath"))
        assertTrue(source.contains("'project-defaults-missing-texture-binding', [defaultsHost] + hostClasspath"))
        assertTrue(source.contains("'project-binding-' + mode, [sessionHost, projectTransforms] + hostClasspath"))
        assertTrue(source.contains("'project-composition-' + mode, [sessionHost, projectTransforms] + hostClasspath"))
        assertTrue(source.contains("List<File> behaviorClasspath = [previewResourceTransforms, new File(work, 'transformed')"))
        assertTrue(source.indexOf("'preview-resource-transform'") < source.indexOf("'material-behavior'"))
        assertTrue(source.indexOf("'preview-resource-transform'") < source.indexOf("'raw-behavior'"))
        assertTrue(source.contains("'raw-behavior', [previewResourceTransforms, new File(work, 'raw-transformed'), new File(work, 'transformed')]"))
        assertTrue(source.contains("'material-behavior', [previewResourceTransforms,"))
        assertTrue(source.contains("[previewResourceTransforms, new File(work, 'accessory-artifact-' + topology)"))
        assertTrue(source.contains("List<File> mutationClasspath = [mutation, previewResourceTransforms,"))
    }

    @Test
    void canonicalGateIncludesNativeWorkerUrlAndPairedDraftComposition() {
        File repository = new File('../..').canonicalFile
        String source = new File(repository, 'gradle/build-logic/src/main/groovy/com/naocraftlab/skins/buildlogic/VerifyCpmIntegrationTask.groovy').text
        for (String label : ['native-worker-lifecycle', 'native-input-url', 'native-project-url-pairs', 'native-project-url',
                'native-input-lossless-reopen', 'native-project-input-reopen', 'native-project-pairs-reopen',
                'model-metadata-transform', 'model-metadata-native', 'model-metadata-shapes', 'model-metadata-server',
                'project-binding-optional', 'project-admission-mutant', 'project-admission-unsupported',
                'project-admission-healthy', 'project-dependency'])
            assertTrue(source.contains("'${label}'"), "Canonical CPM gate must execute ${label}")
        for (String mode : ['lifecycle', 'url', 'project-url-pairs', 'project-url', 'reopen', 'project-reopen', 'project-pairs-reopen', 'topology'])
            assertTrue(source.contains("'${mode}'"), "Canonical native probe mode ${mode}")
        assertTrue(source.contains("'project-admission-unsupported', [projectAdmissionMutant] + hostClasspath, 'CpmProjectOptionalProbe'"),
                'Unsupported admission must execute without the healthy project transforms')
        assertTrue(source.contains("'project-admission-healthy', [projectTransforms] + hostClasspath, 'CpmProjectOptionalProbe'"),
                'Healthy admission must execute with actual project transforms')
        assertTrue(source.contains("'project-dependency', [projectTransforms] + hostClasspath, 'CpmProjectDependencyProbe'"),
                'Acquired dependency fidelity must remain in the canonical gate')
    }
}

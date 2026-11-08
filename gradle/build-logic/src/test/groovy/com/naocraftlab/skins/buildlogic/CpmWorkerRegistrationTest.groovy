package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

final class CpmWorkerRegistrationTest {
    @Test
    void galleryFocusAndNativeSpriteConsumersAreConnectedToCanonicalDispatch() {
        File repository = new File('../..').canonicalFile
        String source = new File(repository,'gradle/build-logic/src/cpmComposition/java/com/naocraftlab/skins/buildlogic/probe/CpmOwnedPreviewDispatchProbe.java').text
        String canonical = source.substring(source.indexOf('CpmPendingTextureProbe.run();'),source.indexOf('public static void observe('))
        assertTrue(canonical.contains('focusConsumers(current);'))
        assertTrue(canonical.contains('buttonFocusConsumers();'))
        assertTrue(source.contains('Actual SDK renderDefaultSprite respects modality and genuine hover:'))
        assertTrue(source.contains('Compiled host -> real native AbstractButton.onClick -> actual ActionButton -> runtime dispatch exactly once'))
        assertTrue(source.contains('graphics.fillClips.get(index)'))
    }

    @Test
    void accessoryContentComponentReceivesActualReadonlyBindingsAfterProducerPreparation() {
        File repository = new File('../..').canonicalFile
        String task = new File(repository, 'gradle/build-logic/src/main/groovy/com/naocraftlab/skins/buildlogic/VerifyCpmIntegrationTask.groovy').text
        def registration = new groovy.json.JsonSlurper().parse(new File(repository,'compat/cpm/src/main/resources/nclskins.cpm.mixins.json'))
        for (String binding : ['CpmResolvedPartsAccessor','CpmResolvedOtherPartsAccessor','CpmCopyTransformsAccessor','CpmCopyVisibilityAccessor',
                               'CpmAnimationStateAccessor','CpmVisibilityTargetAccessor','CpmLegacyAnimationsAccessor','CpmLegacyVisibilityAccessor']) {
            assertTrue(registration.client.contains(binding))
            assertTrue(task.contains("'${binding}'"))
            String source = new File(repository,"compat/cpm/src/main/java/com/naocraftlab/skins/compat/cpm/mixin/${binding}.java").text
            assertTrue(source.contains('@Accessor('))
            assertFalse(source.contains('@Mutable'))
        }
        int component = task.indexOf("'accessory-content-' + mode")
        assertTrue(component > task.indexOf("'preview-resource-transform'"))
        assertTrue(component > task.indexOf("['present', 'absent']"))
        String invocation = task.substring(component, task.indexOf('String rawFamily', component))
        assertTrue(invocation.contains("[previewResourceTransforms, new File(work, 'transformed')] + hostClasspath"))
        assertTrue(invocation.contains("'CpmAccessoryContentProbe'"))
        assertTrue(task.contains("'mapped-null'"))
        assertTrue(task.contains("'legacy-animated'"))
        assertTrue(task.contains("'copy-position'"))
        assertTrue(task.contains("List<File> rawClasspath = [modelTransforms, seams, compiled"))
        assertTrue(task.contains("List<File> materialClasspath = [modelTransforms, seams, compiled"))
        assertTrue(task.contains("List<File> behaviorClasspath = [previewResourceTransforms, new File(work, 'transformed'), modelTransforms, compiled]"))
    }

    @Test
    void extendableClassDeclarationAcceptsAccessWidenerWhitespace() {
        String owner = 'net/minecraft/client/model/geom/ModelPart'
        for (String separator : [' ', '\t', ' \t  ']) {
            String declaration = ['extendable', 'class', owner].join(separator)
            assertTrue(VerifyCpmIntegrationTask.declaresExtendableClass(
                    'accessWidener v1 named\r\n' + declaration + '\r\n', owner),
                    "Valid access widener separator: ${separator.inspect()}")
        }
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('extendable class ' + owner + 'Other', owner))
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('accessible class ' + owner, owner))
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('# extendable class ' + owner, owner))
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('extendable class ' + owner + ' extra', owner))
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('extendable field ' + owner, owner))
    }

    @Test
    void accessTransformerClassExtensionRequiresExactOwner() {
        String owner = 'net/minecraft/client/model/geom/ModelPart'
        String binaryName = 'net.minecraft.client.model.geom.ModelPart'
        for (String separator : [' ', '\t', ' \t  ']) {
            assertTrue(VerifyCpmIntegrationTask.declaresExtendableClass(
                    'public-f' + separator + binaryName + '\n', owner))
        }
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('public ' + binaryName, owner))
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('public-f ' + binaryName + 'Other', owner))
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('# public-f ' + binaryName, owner))
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('public-f ' + binaryName + ' field', owner))
    }

    @Test
    void materialFixtureRetainsGenuineManagerHierarchyAndCompilerClasspath() {
        File repository = new File('../..').canonicalFile
        String fixture = new File(repository, 'gradle/build-logic/src/cpmComposition/fixtures/material/java/com/naocraftlab/skins/buildlogic/probe/fixture/material/PlayerRenderManager.java').text
        assertTrue(fixture.contains('extends com.tom.cpm.shared.model.render.ModelRenderManager<Object,Object,Object,Object>'))
        assertTrue(fixture.contains('armor=java.util.function.Function.identity()'))
        String task = new File(repository, 'gradle/build-logic/src/main/groovy/com/naocraftlab/skins/buildlogic/VerifyCpmIntegrationTask.groovy').text
        assertTrue(task.contains('String libraries = probeClasspath.asPath + File.pathSeparator + resolutionClasspath.asPath'))
        String material = task.substring(task.indexOf('int materialResult ='), task.indexOf("String equipment ="))
        assertTrue(material.contains("'-classpath', libraries"))
        assertTrue(material.indexOf('if (materialResult != 0)') < material.indexOf("'material-link'"))
    }

    @Test
    void galleryAdmissionAcknowledgementAndTypedFixtureConsumersRemainConnected() {
        File repository = new File('../..').canonicalFile
        String port = new File(repository,'client-contract/src/main/java/com/naocraftlab/skins/client/PreviewRenderer.java').text
        assertTrue(port.contains('prepareGalleryBody(String source, boolean cpm, java.util.function.BooleanSupplier candidateCurrent)'))
        assertTrue(!port.contains('prepareGalleryBody(String source, boolean cpm)'))
        assertTrue(port.contains('confirmGalleryBody(String source)'))
        assertTrue(port.contains('galleryAccessories(String source, PreviewRequest request, java.util.function.BooleanSupplier inputCurrent)'))
        assertTrue(!port.contains('galleryAccessories(String source, PreviewRequest request)'))
        def owners = [
                'remote-player/com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/RemotePlayerPreviewRenderer',
                'player-skin/com/naocraftlab/skins/compat/client/resourcelocation/skinlookup/VanillaAppearancePreviewRenderer',
                'avatar-pip-submission/com/naocraftlab/skins/compat/client/identifier/submission/SubmissionPreviewRenderer',
                'avatar-pip-render-state-attack-time/com/naocraftlab/skins/compat/client/identifier/extraction/AvatarRenderStatePreviewRenderer',
                'avatar-pip-render-state-no-attack-time/com/naocraftlab/skins/compat/client/identifier/extraction/AvatarRenderStatePreviewRenderer']
        owners.each { String owner ->
            int split = owner.indexOf('/')
            String path = 'compat/capabilities/preview/' + owner.substring(0,split) + '/src/main/java/' + owner.substring(split+1) + '.java'
            String source = new File(repository,path).text
            assertTrue(source.contains('BooleanSupplier candidateCurrent'),path)
            assertTrue(source.contains('gallery.confirm(source)'),path)
            String query = source.substring(source.indexOf('@Override public PreviewAccessories galleryAccessories('), source.indexOf('private java.util.function.Consumer<PreviewAccessories> accessoryObserver'))
            assertTrue(query.contains('BooleanSupplier inputCurrent'),path)
            assertTrue(query.count('inputCurrent.getAsBoolean()') == 2,path)
            assertTrue(!query.contains('gallery.retains'),path)
            assertTrue(query.contains('gallerySlots.get(source) == slot'),path)
            assertTrue(source.contains('if (closed) return;'),path)
        }
        File probes = new File(repository,'gradle/build-logic/src/cpmComposition/java/com/naocraftlab/skins/buildlogic/probe')
        for (String name : ['CpmPreviewScopeProbe','CpmExtractionBodyProbe','CpmBakedDisplayProbe'])
            assertTrue(new File(probes,name+'.java').text.contains('"confirmGalleryBody"'),name)
        String boundary = new File(probes,'CpmOwnedPreviewDispatchProbe.java').text
        assertTrue(boundary.contains('return runtime.galleryPreviewAdmission(preview)'))
        assertTrue(boundary.contains('return runtime.galleryAccessoryAdmission(preview)'))
        assertTrue(boundary.contains('runtime.observePreviewBody(preview, source, accepted)'))
        assertTrue(!boundary.contains('new ViewSpec.BodySource(ViewSpec.BodyRole.GALLERY_MAIN'))
        assertTrue(new File(probes,'CpmPreviewScreenSlice.java').text.contains('List.of("width","height")'))
        String behavior = new File(probes,'CpmBehaviorProbe.java').text
        String extraction = new File(probes,'CpmExtractionBodyProbe.java').text
        String cloak = new File(probes,'CpmCloakGeometryProbe.java').text
        assertTrue(behavior.contains('player.getDefinitionFuture().get(5, TimeUnit.SECONDS)'))
        assertTrue(behavior.contains('definition.startResolve()'))
        assertTrue(behavior.contains('CpmExtractionBodyProbe.submissionBoundaries'))
        assertTrue(new File(probes,'CpmAccessoryProbe.java').text.contains('CpmCloakGeometryProbe.run('))
        assertTrue(!behavior.contains('CpmCloakGeometryProbe.preview('))
        assertTrue(!extraction.contains('exactOwnedFeedback'))
        assertTrue(!extraction.contains('AccessoryProjection.decoded'))
        assertTrue(!cloak.contains('static void preview('))
        assertTrue(extraction.contains('P1 own actual native pass/material/common ACK, not metadata readiness'))
    }

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
    void liveAndPhysicalBakedDepthAccessRetainsBothTextureAbis() {
        File repository = new File('../..').canonicalFile
        File probes = new File(repository, 'gradle/build-logic/src/cpmComposition/java/com/naocraftlab/skins/buildlogic/probe')
        String live = new File(probes, 'CpmExtractionBodyProbe.java').text
        String baked = new File(probes, 'CpmBakedDisplayProbe.java').text
        for (String source : [live, baked]) {
            String renderer = source.substring(source.indexOf('public static final class GameRenderer {'),
                    source.indexOf('public static final class GameRenderer {') + 700)
            assertTrue(renderer.contains('mainRenderTarget() { return Client.current.getMainRenderTarget(); }'),
                    'Both selected GameRenderer substitutes must expose the native depth accessor')
        }
        assertTrue(live.contains('interface DepthTarget { GpuTexture getDepthTexture(); }'))
        assertTrue(live.contains('class MainRenderTarget implements DepthTarget'))
        assertTrue(live.contains('public LegacyTexture getDepthTexture() { return new LegacyTexture(16, 16, Format.D32_FLOAT); }'),
                'The covariant implementation must retain the legacy descriptor and depth data')
        assertTrue(live.contains('interface GpuTexture { Format getFormat();'))
        assertTrue(live.contains('return new LegacyTexture(width, height, format);'),
                'Native device format selection must survive the CPU terminal')
        assertTrue(baked.contains('public CpmExtractionBodyProbe.Format getFormat(){return CpmExtractionBodyProbe.Format.RGBA8_UNORM;}'),
                'Nonphysical baked color textures must not inherit a depth format')
        assertTrue(live.contains('types.put("net/minecraft/client/renderer/GameRenderer", GameRenderer.class)'))
        assertTrue(baked.contains('types.put("net/minecraft/client/renderer/GameRenderer", GameRenderer.class)'))
        assertTrue(baked.contains('if(pip && !physical)'), 'Static/nonphysical preparation remains separately bounded')
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

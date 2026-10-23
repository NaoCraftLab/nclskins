package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesArtifactTest {
    @Test
    void immediateSortFlagHasExactForgeRuntimeMapping() {
        assertEquals([sortOnUpload: 'f_110393_:Z'], ArtifactVerifier.FORGE_REFMAPS['forge-1.20.1']
                .mappings['com/naocraftlab/skins/compat/client/cape/mixin/RenderTypeFlagsMixin'])
    }

    @Test
    void allMaterialFamiliesRequireTheirOwnGuardedAdapterWithoutForeignPayload() {
        ['player-info-cache', 'skin-lookup', 'cape-foil-pipeline-samplers',
         'cape-foil-pipeline-target-state', 'cape-foil-pipeline-bind-groups', 'cape-foil-combined'].each { String implementation ->
            Map target = [id: 'fixture', capabilities: [appearance: implementation]]
            boolean immediate = implementation in ['player-info-cache', 'skin-lookup']
            boolean prepared = implementation in ['cape-foil-pipeline-bind-groups', 'cape-foil-combined']
            List<String> names = [
                    'com/naocraftlab/skins/compat/waveycapes/WaveyCapesIntegration.class',
                    'com/naocraftlab/skins/compat/waveycapes/CapeRenderThread.class',
                    'com/naocraftlab/skins/compat/waveycapes/WaveyCapesSupport.class',
                    'com/naocraftlab/skins/client/TextureUseValidity.class']
            names += immediate ? [
                    'com/naocraftlab/skins/compat/client/cape/CapePassMaterial.class',
                    'com/naocraftlab/skins/compat/client/cape/CapturedCapePass.class',
                    'nclskins.cape-pass-immediate.mixins.json'] : [
                    'com/naocraftlab/skins/compat/waveycapes/WaveyCapePasses.class',
                    'com/naocraftlab/skins/compat/client/identifier/foil/CapturedCapePass.class',
                    prepared ? 'nclskins.cape-pass-prepared.mixins.json' : 'nclskins.cape-pass-buffered.mixins.json']
            List<String> errors = []
            ArtifactVerifier.verifyWaveyCapesArtifact(target, names, errors)
            assertTrue(errors.empty, errors.toString())
            errors.clear()
            ArtifactVerifier.verifyWaveyCapesArtifact(target, names.drop(1), errors)
            assertFalse(errors.empty)
            ['dev/tr7zw/waveycapes/support/ModSupport.class', 'dev/tr7zw/transition/Fixture.java',
             'META-INF/jars/TRender-1.jar', 'META-INF/jarjar/TRansition-1.jar',
             'META-INF/jars/waveycapes.jar', 'com/naocraftlab/skins/buildlogic/probe/CapeHost.class',
             'com/naocraftlab/skins/buildlogic/boot/CompositionAgent.class',
             'com/naocraftlab/skins/buildlogic/processor/CapeTerminalProvider.java',
             'com/naocraftlab/skins/buildlogic/terminal/CapeTerminalBindings.class',
             'com/naocraftlab/skins/buildlogic/terminal/ExtractionTerminalBindings.java',
             'com/naocraftlab/skins/buildlogic/contract/ProcessorOrderContract.class',
             'com/naocraftlab/skins/buildlogic/probe/wavey/ModeCapeCases.java',
             'com/naocraftlab/skins/buildlogic/probe/wavey/CapeNativeDriver.class',
             'META-INF/services/net.neoforged.neoforgespi.transformation.ClassProcessorProvider',
             '.agents/source.java'].each { String forbidden ->
                errors.clear()
                ArtifactVerifier.verifyWaveyCapesArtifact(target, names + forbidden, errors)
                assertFalse(errors.empty, forbidden)
            }
        }
    }
}

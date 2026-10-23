package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesCatalogTest {
    private final File repository = new File('../..').canonicalFile

    @Test
    void bufferedGeometrySelectsOneExactNativeLoopTopology() {
        Map catalog = CatalogTools.loadCatalog(repository)
        catalog.targets.each { Map target ->
            List bundles = CatalogTools.resolveTargetSources(repository, catalog, target).bundles
            String appearance = target.capabilities.appearance
            List expected = appearance == 'cape-foil-pipeline-samplers' ? ['cape-geometry-unified']
                    : appearance == 'cape-foil-pipeline-target-state' ? ['cape-geometry-split'] : []
            assertEquals(expected, bundles.findAll { it in ['cape-geometry-unified', 'cape-geometry-split'] }, target.id.toString())
        }
    }

    @Test
    void verifiedPassApiPinsRemainArtifactsOnlyOnEveryLoader() {
        Map catalog = CatalogTools.loadCatalog(repository)
        CatalogTools.validate(repository, catalog)
        Map wavey = catalog.optionalDependencies.waveycapes
        assertEquals('46362c5bc2b0169dd8a658262b8194bdf0b0a11b', wavey.sourceCommit)
        assertEquals(catalog.targets.collect { it.id } as Set, wavey.artifacts.keySet())
        catalog.targets.each { Map target ->
            assertEquals('1.11.3', wavey.artifacts[target.id].version)
            assertNull(CatalogTools.optionalDependencyVersion(catalog, target, 'waveycapes'))
            assertNull(CatalogTools.optionalDependencyPredicate(catalog, target, 'waveycapes'))
            String metadata = MetadataRenderer.render(catalog, target, '1.3.0', '1.3.0').values().join('\n')
            assertFalse(metadata.contains('waveycapes'), target.id.toString())
        }
        for (String field : ['sourceCommit', 'version', 'sha512', 'targetSet']) {
            Map broken = new JsonSlurper().parseText(JsonOutput.toJson(catalog)) as Map
            switch (field) {
                case 'sourceCommit': broken.optionalDependencies.waveycapes.sourceCommit = '0' * 40; break
                case 'version': broken.optionalDependencies.waveycapes.artifacts['fabric-26.2'].version = '1.11.2'; break
                case 'sha512': broken.optionalDependencies.waveycapes.artifacts['fabric-26.2'].sha512 = '0' * 127; break
                case 'targetSet': broken.optionalDependencies.waveycapes.artifacts.remove('fabric-26.2'); break
            }
            assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, broken) }
        }
    }
}

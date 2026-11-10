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

}

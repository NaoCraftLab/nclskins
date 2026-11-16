package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

class PlayerSkinFitRegistrationTest {
    File repository = new File('../..').canonicalFile

    @Test void selectingTargetsRegisterBothOptionalFitConfigsFromTheirSelectedResources() {
        Map catalog = CatalogTools.loadCatalog(repository)
        List selecting = catalog.targets.findAll { it.capabilities.preview == 'player-skin-preview' }
        assert selecting*.id == ['fabric-1.21.1', 'neoforge-1.21.1']
        selecting.each { Map target ->
            Map projection = CatalogTools.resolveTargetSources(repository, catalog, target)
            Map resources = MetadataRenderer.render(catalog, target, CatalogTools.loadVersion(repository), '1.2.0')
            List registered = target.loader.id == 'fabric'
                    ? new JsonSlurper().parseText(resources['fabric.mod.json']).mixins.collect { it.config }
                    : (resources['META-INF/neoforge.mods.toml'] =~ /(?m)^config="([^"]+)"$/).collect { it[1] }
            ['nclskins.player-skin-fit.mixins.json', 'nclskins.player-skin-ears-fit.mixins.json'].each { String name ->
                assert registered.count(name) == 1
                List selected = projection.resources.collect { new File(repository, "${it}/${name}") }.findAll { it.isFile() }
                assert selected.size() == 1
                Map config = new JsonSlurper().parse(selected[0]) as Map
                assert config.required
                assert config.injectors.defaultRequire == 1
                if (name == 'nclskins.player-skin-ears-fit.mixins.json') {
                    assert config.plugin == 'com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.ears.EarsMeasurementPlugin'
                }
            }
        }
    }
}

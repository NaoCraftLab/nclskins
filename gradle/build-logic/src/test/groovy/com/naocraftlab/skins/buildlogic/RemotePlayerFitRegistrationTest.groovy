package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path
import java.util.jar.Manifest

class RemotePlayerFitRegistrationTest {
    @TempDir Path directory
    File repository = new File('../..').canonicalFile

    @Test void selectingTargetsPublishBothOptionalFitConfigsExactlyOnce() {
        Map catalog = CatalogTools.loadCatalog(repository)
        List selecting = catalog.targets.findAll { it.capabilities.preview == 'remote-player-preview' }
        assert selecting*.id == ['fabric-1.20.1', 'forge-1.20.1']
        selecting.each { Map target ->
            Map projection = CatalogTools.resolveTargetSources(repository, catalog, target)
            Map resources = MetadataRenderer.render(catalog, target, CatalogTools.loadVersion(repository), '1.2.0')
            List registered
            if (target.loader.id == 'fabric') {
                registered = new JsonSlurper().parseText(resources['fabric.mod.json']).mixins.collect { it.config }
            } else {
                String conventions = new File(repository, 'gradle/target-conventions.gradle').text
                String registration = conventions.substring(conventions.indexOf("tasks.named('jar', Jar).configure {"),
                        conventions.indexOf("tasks.matching { it.name == 'remapJar' }"))
                File root = directory.resolve(target.id).toFile()
                root.mkdirs()
                def project = ProjectBuilder.builder().withProjectDir(root).build()
                project.pluginManager.apply('java')
                File script = new File(root, 'manifest.gradle')
                script.text = 'import groovy.json.JsonSlurper\n' +
                        "ext.spec = new JsonSlurper().parseText('${JsonOutput.toJson(target)}')\n" +
                        "ext.productionJarName = providers.provider { 'nclskins.jar' }\n" + registration
                project.apply(from: script)
                File manifest = new File(root, 'MANIFEST.MF')
                project.tasks.getByName('jar').manifest.effectiveManifest.writeTo(manifest)
                registered = manifest.withInputStream { new Manifest(it).mainAttributes.getValue('MixinConfigs').split(',') as List }
            }
            ['nclskins.remote-player-fit.mixins.json', 'nclskins.remote-player-ears-fit.mixins.json'].each { String name ->
                assert registered.count(name) == 1
                List selected = projection.resources.collect { new File(repository, "${it}/${name}") }.findAll { it.isFile() }
                assert selected.size() == 1
                Map config = new JsonSlurper().parse(selected[0]) as Map
                assert config.required
                assert config.injectors.defaultRequire == 1
                if (name == 'nclskins.remote-player-ears-fit.mixins.json') {
                    assert config.plugin == 'com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.ears.EarsMeasurementPlugin'
                }
            }
        }
    }
}

package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

class MixinHelperPackageVerifierTest {
    @TempDir Path temp

    @Test
    void everyAppearanceEpochRequiresOrdinaryHelpersOutsideConfiguredMixinPackage() {
        [
                '1.20.1': ['resourcelocation/playerinfo/', ['RegisteredSkinTexture.class']],
                '1.21.1': ['resourcelocation/skinlookup/', ['RegisteredSkinTexture.class']],
                '26.3': ['identifier/', ['SneakyUnmodifiedSkinPixels.class']]
        ].each { String epoch, List selection ->
            String ordinary = 'com/naocraftlab/skins/compat/client/' + selection[0]
            List<String> helpers = selection[1] as List<String>
            Map target = [id: "fabric-${epoch}", minecraft: [epoch: epoch]]
            List<String> valid = helpers.collect { ordinary + it }
            assertTrue(problems(target, valid).isEmpty())
            helpers.each { String helper ->
                assertEquals(1, problems(target, valid - (ordinary + helper)).size())
                assertEquals(1, problems(target, valid + (ordinary + 'mixin/' + helper)).size())
            }
        }
    }

    @Test
    void removedOriginalModInteropClassesAreForbidden() {
        Map target = [id: 'fabric-26.3', minecraft: [epoch: '26.3']]
        String ordinary = 'com/naocraftlab/skins/compat/client/identifier/'
        String duck = ordinary + 'SneakyUnmodifiedSkinPixels.class'
        ['OfficialCapeCaptureBridge.class',
         'mixin/SkinManagerOfficialCapeMixin.class',
         'mixin/SkinManagerOfficialOwnerMixin.class',
         'mixin/SkinManagerOfficialScopeMixin.class'].each { String forbidden ->
            assertEquals(1, problems(target, [duck, ordinary + forbidden]).size())
        }
    }

    @Test
    void packagedMixinConfigsRejectOrdinaryClassesInTheirPackageAndSubpackages() {
        Map target = [id: 'fabric-26.3', minecraft: [epoch: '26.3']]
        String duck = 'com/naocraftlab/skins/compat/client/identifier/SneakyUnmodifiedSkinPixels.class'
        String config = 'nclskins.probe.mixins.json'
        String mixin = 'test/mixin/Entry.class'
        String nested = 'test/mixin/Entry$1.class'
        String secondary = 'test/mixin/sub/Second.class'
        String json = '{"package":"test.mixin","client":["Entry","sub.Second"]}'
        assertTrue(problems(target, [duck, mixin, nested, secondary], config, json).isEmpty())
        assertEquals(1, problems(target, [duck, mixin, nested, secondary,
                'test/mixin/Helper.class'], config, json).size())
        assertEquals(1, problems(target, [duck, mixin, nested, secondary,
                'test/mixin/sub/Helper.class'], config, json).size())
    }

    @Test
    void sharedMixinPackageAcceptsClassesDeclaredByEveryConfig() {
        Map target = [id: 'fabric-1.20.1', minecraft: [epoch: '1.20.1']]
        String duck = 'com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/RegisteredSkinTexture.class'
        Map<String, String> configs = [
                'nclskins.first.mixins.json': '{"package":"test.mixin","client":["First"]}',
                'nclskins.second.mixins.json': '{"package":"test.mixin","client":["Second","Accessor"]}',
                'nclskins.child.mixins.json': '{"package":"test.mixin.sub","client":["Third"]}'
        ]
        List<String> declared = [duck, 'test/mixin/First.class', 'test/mixin/Second.class',
                'test/mixin/Accessor.class', 'test/mixin/sub/Third.class']
        assertTrue(problemsWithConfigs(target, declared, configs).isEmpty())
        assertEquals(1, problemsWithConfigs(target,
                declared + 'test/mixin/sub/OrdinaryHelper.class', configs).size())
    }

    private List<String> problems(Map target, List<String> entries,
            String configPath = null, String configJson = null) {
        problemsWithConfigs(target, entries,
                configPath == null ? [:] : [(configPath): configJson])
    }

    private List<String> problemsWithConfigs(Map target, List<String> entries,
            Map<String, String> configs) {
        Path jar = temp.resolve(UUID.randomUUID().toString() + '.jar')
        new ZipOutputStream(jar.toFile().newOutputStream()).withCloseable { ZipOutputStream zip ->
            configs.each { String configPath, String configJson ->
                zip.putNextEntry(new ZipEntry(configPath))
                zip.write(configJson.getBytes('UTF-8'))
                zip.closeEntry()
            }
            entries.each { String entry ->
                zip.putNextEntry(new ZipEntry(entry))
                zip.closeEntry()
            }
        }
        List<String> errors = []
        new ZipFile(jar.toFile()).withCloseable { ZipFile zip ->
            ArtifactVerifier.verifyMixinHelperPackages(zip, target,
                    configs.keySet().toList() + entries, errors)
        }
        errors
    }
}

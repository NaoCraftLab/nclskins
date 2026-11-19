package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.gradle.testfixtures.ProjectBuilder

import static org.junit.jupiter.api.Assertions.*

class OptionalCompileReferenceTest {
    private final File repository = new File('../..').canonicalFile
    @TempDir File workspace

    @Test
    void actualCpmConventionKeepsTheExactReferenceOutOfRuntime() {
        Map catalog = CatalogTools.loadCatalog(repository)
        ['fabric-26.4': 'cxjd3tij', 'fabric-26.3': 'y57itTtd'].each { id, pin ->
            Map target = CatalogTools.selectTarget(catalog, id)
            File root = new File(workspace, id)
            assertTrue(root.mkdir())
            def project = ProjectBuilder.builder().withProjectDir(root).build()
            project.pluginManager.apply('java')
            project.ext.targetCatalog = catalog
            project.ext.targetSpec = target
            project.ext.nclskinsCatalogTools = CatalogTools
            project.ext.nclskinsCpmCompileConfiguration = 'compileOnly'
            project.apply(from: new File(repository, 'gradle/cpm-conventions.gradle'))

            def cpm = { String configuration ->
                project.configurations.getByName(configuration).allDependencies.findAll {
                    it.group == 'maven.modrinth' && it.name == 'h1E7sQNL'
                }
            }
            assertEquals([pin], cpm('compileClasspath')*.version)
            assertFalse(cpm('compileClasspath').first().transitive)
            assertTrue(cpm('runtimeClasspath').isEmpty())
            assertEquals(id == 'fabric-26.4' ? [] : [pin], cpm('testRuntimeClasspath')*.version)
        }
    }

    @Test
    void experimentalTargetResolvesDonorPinsButRemainsOutsideReleaseSelection() {
        Map catalog = CatalogTools.loadCatalog(repository)
        Map target = CatalogTools.selectTarget(catalog, 'fabric-26.4')

        assertEquals(13, catalog.targets.size())
        assertEquals(12, CatalogTools.releaseTargets(catalog).size())
        assertEquals(false, target.releaseEligible)
        assertFalse(CatalogTools.releaseTargets(catalog).any { it.id == target.id })
        assertEquals('fabric-26.3', CatalogTools.optionalCompileTargetId(catalog, target, 'fancymenu'))
        assertEquals('fabric-26.3', CatalogTools.optionalCompileTargetId(catalog, target, 'waveycapes'))
        assertEquals('fabric-26.4', CatalogTools.optionalCompileTargetId(catalog, target, 'cpm'))
        assertEquals('26.4-snapshot-10', CatalogTools.minecraftCompileVersion(target))
        assertEquals('26.4-alpha.10', CatalogTools.minecraftExperimentalRuntimeVersion(
                target.minecraft.version, CatalogTools.minecraftCompileVersion(target)))
        assertEquals('26.4-alpha.10', target.minecraft.runtimeVersion)
        assertEquals('>=26.4-alpha.10', target.minecraft.predicate)
        assertFalse(catalog.optionalDependencies.fancymenu.versions.containsKey(target.id))
        assertFalse(catalog.optionalDependencies.waveycapes.artifacts.containsKey(target.id))
        assertEquals([], catalog.optionalDependencies.fancymenu.runtimeArtifacts[target.id])
        assertNull(CatalogTools.optionalDependencyVersion(catalog, target, 'waveycapes'))
        assertNull(CatalogTools.optionalDependencyPredicate(catalog, target, 'waveycapes'))
    }

    @Test
    void referencesCannotBePromotedOrUseAnAmbiguousOrForeignDonor() {
        [promote: 'optional compile references require', sameTarget: 'invalid fancymenu compile reference donor',
         otherLine: 'invalid fancymenu compile reference donor', otherLoader: 'invalid waveycapes compile reference donor',
         knownExactPin: 'ambiguous or incompatible fancymenu', claimedRuntime: 'cannot have runtime artifacts'].each { mutation, expected ->
            Map catalog = CatalogTools.loadCatalog(repository)
            Map target = CatalogTools.selectTarget(catalog, 'fabric-26.4')
            Map fancy = catalog.optionalDependencies.fancymenu
            switch (mutation) {
                case 'promote': target.releaseEligible = true; break
                case 'sameTarget': target.compileOnlyOptionalReferences.fancymenu = target.id; break
                case 'otherLine': target.compileOnlyOptionalReferences.fancymenu = 'fabric-26.2'; break
                case 'otherLoader': target.compileOnlyOptionalReferences.waveycapes = 'neoforge-26.3'; break
                case 'knownExactPin': fancy.versions[target.id] = fancy.versions['fabric-26.3']; break
                case 'claimedRuntime': fancy.runtimeArtifacts[target.id] = fancy.runtimeArtifacts['fabric-26.3']; break
            }
            def error = assertThrows(IllegalArgumentException, {
                CatalogTools.optionalCompileTargetId(catalog, target, 'fancymenu')
            }, mutation)
            assertTrue(error.message.contains(expected), error.message)
            if (mutation == 'promote') {
                assertFalse(CatalogTools.releaseTargets(catalog).any { it.id == target.id })
            }
        }
    }
}

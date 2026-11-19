package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class OptionalCompileReferenceTest {
    private final File repository = new File('../..').canonicalFile

    @Test
    void experimentalTargetResolvesDonorPinsButRemainsOutsideReleaseSelection() {
        Map catalog = CatalogTools.loadCatalog(repository)
        Map target = CatalogTools.selectTarget(catalog, 'fabric-26.4')

        CatalogTools.validate(repository, catalog)
        assertFalse(CatalogTools.releaseTargets(catalog).any { it.id == target.id })
        assertEquals('fabric-26.3', CatalogTools.optionalCompileTargetId(catalog, target, 'fancymenu'))
        assertEquals('fabric-26.3', CatalogTools.optionalCompileTargetId(catalog, target, 'waveycapes'))
        assertEquals('fabric-26.4', CatalogTools.optionalCompileTargetId(catalog, target, 'cpm'))
        assertEquals('26.4-snapshot-10', CatalogTools.minecraftCompileVersion(target))
        assertEquals('>=26.4-alpha.10', target.minecraft.predicate)
    }

    @Test
    void referencesCannotBePromotedOrUseAnAmbiguousOrForeignDonor() {
        [promote: 'optional compile references require', sameTarget: 'invalid fancymenu compile reference donor',
         otherLine: 'invalid fancymenu compile reference donor', otherLoader: 'invalid waveycapes compile reference donor',
         knownExactPin: 'ambiguous or incompatible fancymenu', claimedRuntime: 'cannot have runtime artifacts',
         removeReference: 'WaveyCapes must pin'].each { mutation, expected ->
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
                case 'removeReference': target.compileOnlyOptionalReferences.remove('waveycapes'); break
            }
            def error = assertThrows(IllegalArgumentException, { CatalogTools.validate(repository, catalog) }, mutation)
            assertTrue(error.message.contains(expected), error.message)
            if (mutation == 'promote') {
                assertFalse(CatalogTools.releaseTargets(catalog).any { it.id == target.id })
            }
        }
    }
}

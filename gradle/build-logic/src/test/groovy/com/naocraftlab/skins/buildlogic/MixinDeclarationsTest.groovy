package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

import static org.junit.jupiter.api.Assertions.*

final class MixinDeclarationsTest {
    @TempDir Path temporary
    private final File repository = new File('../..').canonicalFile

    @Test
    void catalogRejectsRepeatedClientConfigsAndCrossSideDuplicates() {
        ['fabric-26.1', 'neoforge-26.1', 'forge-1.20.1'].each { String id ->
            [false, true].each { boolean crossSide ->
                Map catalog = CatalogTools.loadCatalog(repository)
                Map target = catalog.targets.find { it.id == id } as Map
                String config = target.metadata.mixins.last()
                (crossSide ? target.metadata.serverMixins : target.metadata.mixins).add(config)
                def failure = assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, catalog) }
                assertTrue(failure.message.contains("duplicate Mixin config ${config}"), failure.message)
            }
        }
    }

    @Test
    void artifactsRejectDuplicatesEvenWhenTheyMatchCatalog() {
        ['fabric-26.1', 'neoforge-26.1', 'forge-1.20.1'].each { String id ->
            [false, true].each { boolean duplicate ->
                Map catalog = CatalogTools.loadCatalog(repository)
                Map target = catalog.targets.find { it.id == id } as Map
                if (duplicate) target.metadata.mixins.add(target.metadata.mixins.last())
                Map<String, String> resources = MetadataRenderer.render(catalog, target, '1.1.0-beta.1')
                resources['META-INF/MANIFEST.MF'] = "Manifest-Version: 1.0\nAutomatic-Module-Name: ${target.artifact.automaticModuleName}\nMixinConfigs: ${((target.metadata.serverMixins ?: []) + target.metadata.mixins).join(',')}\n"
                File jar = temporary.resolve("${id}-${duplicate}.jar").toFile()
                new ZipOutputStream(new FileOutputStream(jar)).withCloseable { zip ->
                    resources.each { String name, String content ->
                        zip.putNextEntry(new ZipEntry(name))
                        zip.write(content.getBytes('UTF-8'))
                        zip.closeEntry()
                    }
                }
                List<String> errors = []
                new ZipFile(jar).withCloseable { archive ->
                    ArtifactVerifier.verifyMetadata(archive, catalog, target, '1.1.0-beta.1', errors)
                    ArtifactVerifier.verifyManifest(archive, target, errors)
                }
                assertEquals(duplicate, errors.any { it.contains('duplicate Mixin config') }, errors.toString())
                assertFalse(errors.any { it.contains('differs from catalog') }, errors.toString())
            }
        }
    }
}

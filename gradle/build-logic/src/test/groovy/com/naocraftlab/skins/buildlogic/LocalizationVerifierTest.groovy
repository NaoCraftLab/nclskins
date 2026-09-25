package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

import static org.junit.jupiter.api.Assertions.*

final class LocalizationVerifierTest {
    private final File repository = new File('../..').canonicalFile
    private final Map catalog = CatalogTools.loadCatalog(repository)

    @Test
    void catalogOwnsExactAuthoredAndGeneratedLocaleTopology() {
        assertEquals(LocalizationVerifier.EXPECTED_SOURCE_LOCALES,
                LocalizationVerifier.sourceLocales(catalog))
        assertEquals(LocalizationVerifier.EXPECTED_ALIASES,
                LocalizationVerifier.aliases(catalog))
        assertEquals([
                'en_us', 'ru_ru', 'de_de', 'pt_br', 'es_mx', 'es_es',
                'es_ar', 'es_cl', 'es_ec', 'es_uy', 'es_ve'
        ], LocalizationVerifier.artifactLocales(catalog))

        List<String> errors = []
        LocalizationVerifier.validateDeclaration(catalog, errors)
        assertEquals([], errors)

        Map missingDescription = cloneMap(catalog)
        missingDescription.mod.descriptions.remove('de_de')
        errors.clear()
        LocalizationVerifier.validateDeclaration(missingDescription, errors)
        assertTrue(errors.any { it.contains('one non-empty') })

        Map duplicateSource = cloneMap(catalog)
        duplicateSource.mod.localization.sourceLocales[5] = 'es_mx'
        errors.clear()
        LocalizationVerifier.validateDeclaration(duplicateSource, errors)
        assertFalse(errors.isEmpty())

        Map aliasToAlias = cloneMap(catalog)
        aliasToAlias.mod.localization.aliases.es_ve = 'es_ar'
        errors.clear()
        LocalizationVerifier.validateDeclaration(aliasToAlias, errors)
        assertFalse(errors.isEmpty())

        Map extraAlias = cloneMap(catalog)
        extraAlias.mod.localization.aliases.es_co = 'es_mx'
        errors.clear()
        LocalizationVerifier.validateDeclaration(extraAlias, errors)
        assertFalse(errors.isEmpty())
    }

    @Test
    void canonicalSourcesMatchProductionInventoryAndArgumentContract() {
        File directory = new File(repository,
                'compat/resources/canonical/src/main/resources/assets/nclskins/lang')
        Map baseline = CatalogTools.loadJson(new File(directory, 'en_us.json'))
        assertEquals(290, baseline.size())
        assertEquals('NCL Skins', baseline['key.category.nclskins.main'])
        assertTrue(LocalizationVerifier.productionKeys(repository).containsAll([
                'key.category.nclskins.main', 'key.nclskins.open_gallery',
                'key.nclskins.edit_active_preset', 'key.nclskins.open_providers',
                'key.nclskins.open_skin_catalog', 'key.nclskins.open_skin_import']))
        Set<String> expectedProduction = baseline.keySet() as Set
        Set<String> actualProduction = LocalizationVerifier.productionKeys(repository)
        assertTrue((expectedProduction - actualProduction).isEmpty() &&
                (actualProduction - expectedProduction).isEmpty(),
                "missing=${expectedProduction - actualProduction}, extra=${actualProduction - expectedProduction}")

        LocalizationVerifier.sourceLocales(catalog).each { String locale ->
            Map language = CatalogTools.loadJson(new File(directory, "${locale}.json"))
            List<String> errors = []
            LocalizationVerifier.validateLanguage(locale, language, baseline, true, errors)
            assertEquals([], errors, locale)
        }

        Map missing = new LinkedHashMap(baseline)
        missing.remove('nclskins.gallery.title')
        List<String> errors = []
        LocalizationVerifier.validateLanguage('test', missing, baseline, true, errors)
        assertTrue(errors*.toString().contains(
                'test: missing key nclskins.gallery.title'), errors.toString())

        Map orphan = new LinkedHashMap(baseline)
        orphan['nclskins.typo'] = 'Typos are rejected'
        errors.clear()
        LocalizationVerifier.validateLanguage('test', orphan, baseline, true, errors)
        assertTrue(errors*.toString().contains('test: extra key nclskins.typo'))

        Map placeholder = new LinkedHashMap(baseline)
        placeholder['nclskins.gallery.create_named'] = 'Create'
        errors.clear()
        LocalizationVerifier.validateLanguage('test', placeholder, baseline, true, errors)
        assertTrue(errors*.toString().contains(
                'test: nclskins.gallery.create_named has incompatible format arguments'))

        Map empty = new LinkedHashMap(baseline)
        empty['nclskins.gallery.title'] = ''
        errors.clear()
        LocalizationVerifier.validateLanguage('test', empty, baseline, true, errors)
        assertTrue(errors*.toString().contains('test: nclskins.gallery.title must not be empty'))

        Map translatedProductName = new LinkedHashMap(baseline)
        translatedProductName['nclskins.compatibility.feature.ears'] = 'Ohren'
        errors.clear()
        LocalizationVerifier.validateLanguage('de_de', translatedProductName, baseline, true, errors)
        assertTrue(errors.any { it.contains('must preserve external name Ears') })

        Map russian = CatalogTools.loadJson(new File(directory, 'ru_ru.json'))
        assertEquals('Оффлайн', russian['nclskins.session.offline'])
        Map missingNoCapeMeaning = new LinkedHashMap(russian)
        missingNoCapeMeaning['nclskins.editor.no_cape'] = 'Выкл'
        errors.clear()
        LocalizationVerifier.validateLanguage(
                'ru_ru', missingNoCapeMeaning, baseline, true, errors)
        assertTrue(errors.any { it.contains('no-cape choice') })

        Map slashModelAlias = new LinkedHashMap(russian)
        slashModelAlias['nclskins.editor.arms_classic'] = 'Модель: Классическая/Широкая'
        errors.clear()
        LocalizationVerifier.validateLanguage(
                'ru_ru', slashModelAlias, baseline, true, errors)
        assertTrue(errors.any { it.contains('without slash synonyms') })

        Map partFirstLayers = new LinkedHashMap(russian)
        partFirstLayers['nclskins.editor.outer_head_on'] = '%1$s: Вкл'
        errors.clear()
        LocalizationVerifier.validateLanguage(
                'ru_ru', partFirstLayers, baseline, true, errors)
        assertTrue(errors.any { it.contains('one or two state-first lines') })
    }

    @Test
    void collectionSourcesHaveExactManifestAndLocalFinderMetadataDoesNotAffectValidation(
            @TempDir Path temp) {
        File directory = new File(repository,
                'compat/resources/mojang-collections/src/main/resources/resourcepacks/' +
                        'mojang_collections/assets/nclskins/lang')
        Set<String> expected = LocalizationVerifier.collectionKeys(repository)
        assertEquals(127, expected.size())
        LocalizationVerifier.sourceLocales(catalog).each { String locale ->
            Set<String> actual = CatalogTools.loadJson(
                    new File(directory, "${locale}.json")).keySet() as Set
            assertTrue((expected - actual).isEmpty() && (actual - expected).isEmpty(),
                    "${locale}: missing=${expected - actual}, extra=${actual - expected}")
        }

        assertTrue(ArtifactVerifier.containsFinderMetadata('assets/nclskins/.DS_Store'))
        assertTrue(ArtifactVerifier.containsFinderMetadata('__MACOSX/assets/icon.png'))
        assertFalse(ArtifactVerifier.containsFinderMetadata('assets/nclskins/icon.png'))

        Path canonical = temp.resolve(
                'compat/resources/canonical/src/main/resources/assets/nclskins/lang')
        Path collections = temp.resolve(
                'compat/resources/mojang-collections/src/main/resources/resourcepacks/' +
                        'mojang_collections/assets/nclskins/lang')
        Files.createDirectories(canonical)
        Files.createDirectories(collections)
        List<String> errors = []
        LocalizationVerifier.validateRepository(temp.toFile(), catalog, errors)
        assertTrue(errors.any { it.startsWith('canonical source locale files') })
        assertTrue(errors.any { it.startsWith('mojang-collections source locale files') })
        List<String> beforeFinderMetadata = new ArrayList<>(errors)
        Path finderFile = canonical.parent.resolve('.DS_Store')
        Path finderArchive = canonical.parent.resolve('__MACOSX/assets/icon.png')
        Files.write(finderFile, [1, 2, 3] as byte[])
        Files.createDirectories(finderArchive.parent)
        Files.write(finderArchive, [4, 5, 6] as byte[])

        errors.clear()
        LocalizationVerifier.validateRepository(temp.toFile(), catalog, errors)

        assertEquals(beforeFinderMetadata, errors)
        assertTrue(Files.exists(finderFile))
        assertTrue(Files.exists(finderArchive))
    }

    @Test
    void capeProvenanceOwnsTheExactCurrentInventoryAndNoticeLinks() {
        File pack = new File(repository,
                'compat/resources/mojang-collections/src/main/resources/resourcepacks/' +
                        'mojang_collections')
        Set<String> collections = []
        Files.walk(new File(pack, 'assets').toPath()).withCloseable { stream ->
            stream.filter { Path path ->
                Files.isRegularFile(path) && path.toString().endsWith('.png') &&
                        (path.toString().replace('\\', '/').contains('/textures/entity/player/') ||
                                path.toString().replace('\\', '/').contains('/textures/entity/cape/'))
            }.forEach { Path path -> collections.add(
                    pack.toPath().relativize(path).getName(1).toString()) }
        }
        Map provenance = new JsonSlurper().parse(
                new File(repository, 'gradle/asset-provenance/mojang-capes.json')) as Map

        assertEquals(12, collections.size())
        assertEquals([
                'mojang_minecon_earth_2017', 'mojang_builders_and_biomes',
                'mojang_striding_hero', 'mojang_the_garden_awakens',
                'mojang_chase_the_skies', 'mojang_the_copper_age',
                'mojang_mounts_of_mayhem', 'mojang_tiny_takeover',
                'mojang_chaos_cubed', 'mojang_account_ownership',
                'mojang_account_events', 'mojang_global_events'
        ] as Set, collections)
        assertFalse(new File(pack, 'assets/nclskins/collections.json').exists())
        assertEquals(false, provenance.releaseApproved)
        assertNull(provenance.approvedAssetSetSha256)
        assertEquals(28, provenance.entries.size())
        assertEquals([
                mojang_account_ownership: 4,
                mojang_account_events: 21,
                mojang_global_events: 3
        ], provenance.entries.countBy { it.collectionId })
        assertTrue(provenance.entries.every { it.owner == 'Mojang Studios' })
        assertTrue(provenance.entries.every { it.releaseApproved == false })
        assertEquals(2, provenance.entries.count {
            it.sourceKind == 'archival_exact_copy' && it.officialUrlFound == false
        })

        Set<String> manifestPaths = provenance.entries.collect { it.texturePath } as Set
        Set<String> diskPaths = []
        Files.walk(new File(pack, 'assets').toPath()).withCloseable { stream ->
            stream.filter { Path path ->
                Files.isRegularFile(path) && path.toString().endsWith('.png') &&
                        path.toString().replace('\\', '/').contains('/textures/entity/cape/')
            }.forEach { Path path ->
                diskPaths.add(pack.toPath().relativize(path).toString().replace('\\', '/'))
            }
        }
        assertEquals(manifestPaths, diskPaths)
        provenance.entries.each { Map entry ->
            File texture = new File(pack, entry.texturePath.toString())
            assertEquals(entry.sha256, HexFormat.of().formatHex(
                    MessageDigest.getInstance('SHA-256').digest(texture.bytes)))
            String notice = new File(pack,
                    "assets/${entry.collectionId}/notice-mojang.md").getText('UTF-8')
            assertTrue(notice.contains("textures/entity/cape/${entry.capeId}.png"))
            assertTrue(notice.contains(entry.sha256.toString()))
        }
        assertEquals(12, Files.walk(pack.toPath()).withCloseable { stream ->
            stream.filter { it.fileName.toString() == 'notice-mojang.md' }.count()
        })
        assertFalse(new File(pack, 'assets/nclskins/cape-provenance.json').exists())
        assertTrue((manifestPaths*.toString()).every {
            !it.contains('/bacon.png') && !it.contains('/birthday.png') &&
                    !it.contains('/mojang.png') && !it.contains('/moderator.png')
        })
    }

    @Test
    void coherentCapeTakedownKeepsDerivedMetadataAndProvenanceConsistent(@TempDir Path temp) {
        Path capes = temp.resolve(
                'compat/resources/mojang-collections/src/main/resources/resourcepacks/' +
                        'mojang_collections/assets/mojang_fixture/textures/entity/cape')
        Files.createDirectories(capes)
        Files.write(capes.resolve('retained.png'), [1] as byte[])
        Files.write(capes.resolve('removed.png'), [2] as byte[])

        assertTrue(LocalizationVerifier.collectionKeys(temp.toFile()).containsAll([
                'nclskins.mojang_fixture.cape.retained.name',
                'nclskins.mojang_fixture.cape.retained.description',
                'nclskins.mojang_fixture.cape.removed.name',
                'nclskins.mojang_fixture.cape.removed.description'
        ]))

        Files.delete(capes.resolve('removed.png'))
        Set<String> remainingKeys = LocalizationVerifier.collectionKeys(temp.toFile())
        assertTrue(remainingKeys.contains('nclskins.mojang_fixture.cape.retained.name'))
        assertFalse(remainingKeys.any { it.contains('.cape.removed.') })

        Set<String> manifest = ['assets/mojang_fixture/textures/entity/cape/retained.png']
        Set<String> files = ['assets/mojang_fixture/textures/entity/cape/retained.png']
        List<String> errors = []
        ArtifactVerifier.verifyMojangCapeInventory(manifest, files, 'fixture', errors)
        assertEquals([], errors)

        files.add('assets/mojang_fixture/textures/entity/cape/orphan.png')
        ArtifactVerifier.verifyMojangCapeInventory(manifest, files, 'fixture', errors)
        assertEquals(['fixture: Mojang cape provenance inventory differs'], errors*.toString())
    }

    @Test
    void spanishVariantsUseReviewedGlossaries() {
        File directory = new File(repository,
                'compat/resources/canonical/src/main/resources/assets/nclskins/lang')
        Map mexico = CatalogTools.loadJson(new File(directory, 'es_mx.json'))
        Map spain = CatalogTools.loadJson(new File(directory, 'es_es.json'))
        assertEquals('Mis aspectos', mexico['nclskins.gallery.title'])
        assertEquals('Mis aspectos', spain['nclskins.gallery.title'])
        assertEquals('Agregar skin', mexico['nclskins.add_source.title'])
        assertEquals('Añadir skin', spain['nclskins.add_source.title'])
        assertEquals('Elegir carpeta', mexico['nclskins.external_import.choose_folder'])
        assertEquals('Seleccionar carpeta', spain['nclskins.external_import.choose_folder'])
        assertEquals('Delgado', mexico['nclskins.add_source.filter_slim'])
        assertEquals('Delgado', spain['nclskins.add_source.filter_slim'])
        assertNotEquals(mexico, spain)
    }

    @Test
    void collectionsUseReviewedNaturalNamesCreditsAndMinecraftTerms() {
        File directory = new File(repository,
                'compat/resources/mojang-collections/src/main/resources/resourcepacks/' +
                        'mojang_collections/assets/nclskins/lang')
        Map baseline = CatalogTools.loadJson(new File(directory, 'en_us.json'))
        LocalizationVerifier.sourceLocales(catalog).each { String locale ->
            Map language = CatalogTools.loadJson(new File(directory, "${locale}.json"))
            List<String> errors = []
            LocalizationVerifier.validateCollectionSemantics(locale, language, baseline, errors)
            assertEquals([], errors, locale)
        }

        Map mutant = new LinkedHashMap(CatalogTools.loadJson(new File(directory, 'es_es.json')))
        mutant['nclskins.mojang_chase_the_skies.name'] = 'Chase the Skies'
        List<String> errors = []
        LocalizationVerifier.validateCollectionSemantics('es_es', mutant, baseline, errors)
        assertTrue(errors.any { it.contains('collection names must match') })

        Map germanMutant = new LinkedHashMap(
                CatalogTools.loadJson(new File(directory, 'de_de.json')))
        germanMutant['nclskins.mojang_tiny_takeover.description'] =
                'Zwei Skins wurden für die Herausforderungen von „Die Kleinen übernehmen“ veröffentlicht.'
        errors.clear()
        LocalizationVerifier.validateCollectionSemantics(
                'de_de', germanMutant, baseline, errors)
        assertTrue(errors.any { it.contains('must match the reviewed collection title') })

        Map russianMutant = new LinkedHashMap(
                CatalogTools.loadJson(new File(directory, 'ru_ru.json')))
        russianMutant['nclskins.mojang_builders_and_biomes.authors'] =
                'Mojang Studios Stockholm and Redmond artists'
        russianMutant['nclskins.mojang_tiny_takeover.skin.baby_bee_fan.name'] =
                'Поклонник детёныша пчелы'
        errors.clear()
        LocalizationVerifier.validateCollectionSemantics(
                'ru_ru', russianMutant, baseline, errors)
        assertTrue(errors.any { it.contains('descriptive author credit must be localized') })
        assertTrue(errors.any { it.contains('baby_bee_fan must use its reviewed natural name') })
    }

    private static Map cloneMap(Map value) {
        new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(value)) as Map
    }
}

package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path

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
        assertEquals(232, baseline.size())
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
    void collectionSourcesHaveExactManifestAndFinderMetadataFailsClosed(@TempDir Path temp) {
        File directory = new File(repository,
                'compat/resources/mojang-collections/src/main/resources/resourcepacks/' +
                        'mojang_collections/assets/nclskins/lang')
        Set<String> expected = LocalizationVerifier.collectionKeys(repository)
        assertEquals(62, expected.size())
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

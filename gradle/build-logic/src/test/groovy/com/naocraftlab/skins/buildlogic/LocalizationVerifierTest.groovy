package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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
        assertEquals(306, baseline.size())
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

        Map<String, List<String>> limitedWriteDescriptions = [
                en_us: ["Saves the active look's skin provided by the provider's catalog",
                        "Saves the active look's cape provided by the provider's catalog"],
                ru_ru: ['Сохраняет скин активного образа, предоставленный каталогом провайдера',
                        'Сохраняет плащ активного образа, предоставленный каталогом провайдера'],
                de_de: ['Speichert den Skin des aktiven Looks aus dem Katalog des Anbieters',
                        'Speichert den Umhang des aktiven Looks aus dem Katalog des Anbieters'],
                pt_br: ['Salva a skin da aparência ativa fornecida pelo catálogo do provedor',
                        'Salva a capa da aparência ativa fornecida pelo catálogo do provedor'],
                es_mx: ['Guarda la skin del aspecto activo proporcionada por el catálogo del proveedor',
                        'Guarda la capa del aspecto activo proporcionada por el catálogo del proveedor'],
                es_es: ['Guarda la skin del aspecto activo proporcionada por el catálogo del proveedor',
                        'Guarda la capa del aspecto activo proporcionada por el catálogo del proveedor']
        ]
        limitedWriteDescriptions.each { String locale, List<String> descriptions ->
            Map language = CatalogTools.loadJson(new File(directory, "${locale}.json"))
            assertEquals(descriptions[0], language['nclskins.providers.capability.limited_write.skin.description'])
            assertEquals(descriptions[1], language['nclskins.providers.capability.limited_write.cape.description'])
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
        assertEquals(138, expected.size())
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

        assertEquals(13, collections.size())
        assertEquals([
                'mojang_minecon_earth_2017', 'mojang_builders_and_biomes',
                'mojang_striding_hero', 'mojang_the_garden_awakens',
                'mojang_chase_the_skies', 'mojang_the_copper_age',
                'mojang_mounts_of_mayhem', 'mojang_tiny_takeover',
                'mojang_chaos_cubed', 'mojang_account_ownership',
                'mojang_account_events', 'mojang_global_events', 'mojang_the_camp_out'
        ] as Set, collections)
        assertFalse(new File(pack, 'assets/nclskins/collections.json').exists())
        assertEquals(false, provenance.releaseApproved)
        assertNull(provenance.approvedAssetSetSha256)
        assertEquals(31, provenance.entries.size())
        assertEquals([
                mojang_account_ownership: 4,
                mojang_account_events: 24,
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
        assertEquals(13, Files.walk(pack.toPath()).withCloseable { stream ->
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

    @Test
    void campOutUsesAllExactApprovedValuesAndRejectsWordingMutations() {
        File directory = new File(repository,
                'compat/resources/mojang-collections/src/main/resources/resourcepacks/' +
                        'mojang_collections/assets/nclskins/lang')
        Map expected = [
                en_us: ['Two skins released for Camp Out Community Challenge.', 'Big Foot', 'Mushroom Forager'],
                ru_ru: ['Два скина, выпущенных для Camp Out Community Challenge.', 'Снежный человек', 'Грибник'],
                de_de: ['Zwei Skins zur Camp Out Community Challenge.', 'Bigfoot', 'Pilzsammler'],
                pt_br: ['Duas skins lançadas para o evento Camp Out Community Challenge.', 'Pé-grande', 'Coletor de cogumelos'],
                es_mx: ['Dos skins publicadas para Camp Out Community Challenge.', 'Pie Grande', 'Recolector de hongos'],
                es_es: ['Dos skins publicadas para Camp Out Community Challenge.', 'Pie Grande', 'Recolector de setas']
        ]
        Map baseline = CatalogTools.loadJson(new File(directory, 'en_us.json'))
        LocalizationVerifier.sourceLocales(catalog).each { String locale ->
            Map language = CatalogTools.loadJson(new File(directory, "${locale}.json"))
            Map wording = [
                    'nclskins.mojang_the_camp_out.name': 'Camp Out Community Challenge',
                    'nclskins.mojang_the_camp_out.description': expected[locale][0],
                    'nclskins.mojang_the_camp_out.authors': 'Mojang Studios',
                    'nclskins.mojang_the_camp_out.skin.big_foot.name': expected[locale][1],
                    'nclskins.mojang_the_camp_out.skin.mushroom_forager.name': expected[locale][2]
            ]
            wording.each { String key, String value ->
                assertEquals(value, language[key], "${locale} ${key}")
                Map mutant = new LinkedHashMap(language)
                mutant[key] = 'Wrong approved value'
                List<String> errors = []
                LocalizationVerifier.validateCollectionSemantics(locale, mutant, baseline, errors)
                assertFalse(errors.isEmpty(), "${locale} ${key}")
            }
            Map missing = new LinkedHashMap(language)
            missing.remove('nclskins.mojang_the_camp_out.skin.big_foot.name')
            List<String> errors = []
            LocalizationVerifier.validateLanguage(locale, missing, baseline, false, errors)
            assertTrue(errors.any { it.contains('missing key nclskins.mojang_the_camp_out.skin.big_foot.name') })
            Map orphan = new LinkedHashMap(language)
            orphan['nclskins.mojang_the_camp_out.skin.big_foot.description'] = 'Orphan'
            errors.clear()
            LocalizationVerifier.validateLanguage(locale, orphan, baseline, false, errors)
            assertTrue(errors.any { it.contains('extra key nclskins.mojang_the_camp_out.skin.big_foot.description') })
        }
    }

    @Test
    void campOutOriginalPathsHashesAndNoticeAreCheckedOnDisposableSources(@TempDir Path temp) {
        String packPath = 'compat/resources/mojang-collections/src/main/resources/resourcepacks/mojang_collections'
        Path originalPack = new File(repository, packPath).toPath()
        Map<String, String> hashes = [
                'assets/mojang_the_camp_out/textures/entity/player/slim/big_foot.png':
                        '72d5dcde3bb9fc775b4ed72911564b760c2865be383c8ded6d38e258b85efb39',
                'assets/mojang_the_camp_out/textures/entity/player/wide/mushroom_forager.png':
                        'd34d94e146925c8a79e319279df2b31407e853eaf475a4e51d044b093fbf0852'
        ]
        hashes.each { String path, String hash ->
            assertEquals(hash, HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256')
                    .digest(originalPack.resolve(path).toFile().bytes)))
        }
        ['positive', 'extra-variant', 'extra-png', 'missing-png', 'wrong-hash', 'missing-notice'].each { String mutation ->
            Path fixture = temp.resolve(mutation)
            Path pack = fixture.resolve(packPath)
            Files.walk(originalPack).withCloseable { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { Path path ->
                    Path destination = pack.resolve(originalPack.relativize(path))
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination)
                }
            }
            Path provenance = fixture.resolve('gradle/asset-provenance/mojang-capes.json')
            Files.createDirectories(provenance.parent)
            Files.copy(new File(repository, 'gradle/asset-provenance/mojang-capes.json').toPath(), provenance)
            Path bigFoot = pack.resolve(hashes.keySet().first())
            switch (mutation) {
                case 'extra-variant':
                    Path wide = pack.resolve('assets/mojang_the_camp_out/textures/entity/player/wide/big_foot.png')
                    Files.copy(bigFoot, wide)
                    break
                case 'extra-png':
                    Files.copy(bigFoot, bigFoot.parent.resolve('extra.png'))
                    break
                case 'missing-png':
                    Files.delete(bigFoot)
                    break
                case 'wrong-hash':
                    Files.write(bigFoot, pack.resolve(hashes.keySet().last()).toFile().bytes)
                    break
                case 'missing-notice':
                    Files.delete(pack.resolve('assets/mojang_the_camp_out/notice-mojang.md'))
                    break
            }
            Path jar = fixture.resolve('resources.zip')
            new ZipOutputStream(Files.newOutputStream(jar)).withCloseable { zip ->
                Files.walk(pack).withCloseable { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { Path path ->
                        zip.putNextEntry(new ZipEntry(ArtifactVerifier.COLLECTIONS + pack.relativize(path).toString().replace('\\', '/')))
                        zip.write(Files.readAllBytes(path))
                        zip.closeEntry()
                    }
                }
            }
            List<String> errors = []
            new ZipFile(jar.toFile()).withCloseable { archive ->
                ArtifactVerifier.verifyMojangCollectionProvenance(fixture.toFile(), pack.toFile(), archive,
                        [id: 'fixture'], archive.entries().collect { it.name }, errors)
            }
            if (mutation == 'positive') assertEquals([], errors)
            else if (mutation == 'missing-notice') assertTrue(errors.any { it.contains('notice inventory differs') }, errors.toString())
            else assertTrue(errors.any { it.contains('Camp Out') }, "${mutation}: ${errors}")
        }
    }

    private static Map cloneMap(Map value) {
        new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(value)) as Map
    }
}

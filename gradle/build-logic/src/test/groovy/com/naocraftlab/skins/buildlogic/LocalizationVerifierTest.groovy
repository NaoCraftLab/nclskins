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
    void cpmDependencyCopyPreservesDownloadConsentInEveryAuthoredLocale() {
        Map<String, List<String>> expected = [
                en_us: ['Download a required CPM template?', 'This project needs a template from an external service. Downloading it will send your IP address to that service. No Minecraft account credentials or model data will be uploaded', 'Download template'],
                ru_ru: ['Загрузить необходимый шаблон CPM?', 'Этому проекту нужен шаблон с внешнего сервиса. При загрузке сервис получит ваш IP-адрес. Данные учётной записи Minecraft и модели отправлены не будут', 'Загрузить шаблон'],
                de_de: ['Benötigte CPM-Vorlage herunterladen?', 'Dieses Projekt benötigt eine Vorlage von einem externen Dienst. Beim Herunterladen wird deine IP-Adresse an diesen Dienst übermittelt. Es werden keine Zugangsdaten deines Minecraft-Kontos oder Modelldaten hochgeladen', 'Vorlage herunterladen'],
                pt_br: ['Baixar um template necessário do CPM?', 'Este projeto precisa de um template de um serviço externo. Ao baixá-lo, seu endereço IP será enviado a esse serviço. Nenhuma credencial da sua conta do Minecraft nem dados do modelo serão enviados', 'Baixar template'],
                es_mx: ['¿Descargar una plantilla de CPM necesaria?', 'Este proyecto necesita una plantilla de un servicio externo. Al descargarla, se enviará tu dirección IP a ese servicio. No se subirán credenciales de tu cuenta de Minecraft ni datos del modelo', 'Descargar plantilla'],
                es_es: ['¿Descargar una plantilla de CPM necesaria?', 'Este proyecto necesita una plantilla de un servicio externo. Al descargarla, se enviará tu dirección IP a ese servicio. No se subirán credenciales de tu cuenta de Minecraft ni datos del modelo', 'Descargar plantilla']]
        String prefix = 'nclskins.add.cpm_dependency_'
        Map<String, String> limits = [
                en_us: 'This project needs more external data than this import can download',
                ru_ru: 'Для этого проекта нужно больше внешних данных, чем можно загрузить за один импорт',
                de_de: 'Dieses Projekt benötigt mehr externe Daten, als bei einem Import heruntergeladen werden können',
                pt_br: 'Este projeto precisa de mais dados externos do que é possível baixar em uma importação',
                es_mx: 'Este proyecto necesita más datos externos de los que se pueden descargar en una importación',
                es_es: 'Este proyecto necesita más datos externos de los que se pueden descargar en una importación']
        expected.each { String locale, List<String> values -> values.add(limits[locale]) }
        List<String> suffixes = ['title', 'warning', 'confirm', 'limit']
        Set<String> keys = suffixes.collect { prefix + it } as Set
        assertEquals(LocalizationVerifier.sourceLocales(catalog) as Set, expected.keySet())
        assertTrue(LocalizationVerifier.productionKeys(repository).containsAll(keys))
        LocalizationVerifier.sourceLocales(catalog).each { String locale ->
            Map language = CatalogTools.loadJson(new File(repository,
                    "compat/resources/canonical/src/main/resources/assets/nclskins/lang/${locale}.json"))
            assertEquals(keys, language.keySet().findAll { it.startsWith(prefix) } as Set)
            suffixes.eachWithIndex { String suffix, int index ->
                String value = language[prefix + suffix]
                assertEquals(expected[locale][index], value, locale + ':' + suffix)
                assertFalse(value.endsWith('.'), locale + ':' + suffix)
                assertFalse(value.contains('CPM Paste'), locale + ':' + suffix)
            }
            assertTrue(language[prefix + 'title'].contains('CPM'), locale)
            assertTrue(language[prefix + 'warning'].contains('IP'), locale)
            assertTrue(language[prefix + 'warning'].contains('Minecraft'), locale)
        }
    }

    @Test
    void cpmPublicationCopyPreservesLoadingAndRecoveryWithoutRetiredConsentInEveryAuthoredLocale() {
        Map<String, List<String>> expected = [
                missing_session: ['This model needs to be uploaded to CPM Paste. Start the game with a licensed Minecraft account', 'Для этой модели нужна загрузка на CPM Paste. Запустите игру с лицензионной учётной записью Minecraft'],
                proof_rejected: ['CPM Paste could not verify your Minecraft account. Restart the game with a licensed Minecraft account', 'CPM Paste не удалось подтвердить учётную запись Minecraft. Перезапустите игру с лицензионной учётной записью'],
                count_quota: ["You've reached the upload limit on CPM Paste! You can delete unneeded models from %1\$s - %2\$s - %3\$s", 'Вы достигли лимита загрузок на CPM Paste! Удалить лишние модели можно в меню %1$s - %2$s - %3$s'],
                count_quota_unavailable: ["You've reached the upload limit on CPM Paste!", 'Вы достигли лимита загрузок на CPM Paste!'],
                size_quota: ['The model data exceeds the size limit on CPM Paste', 'Данные модели превышают допустимый размер на CPM Paste'],
                network: ['Could not connect to CPM Paste. Check your connection', 'Не удалось подключиться к CPM Paste. Проверьте подключение'],
                protocol: ['CPM Paste cannot complete the upload right now. Try again later', 'CPM Paste сейчас не может завершить загрузку. Попробуйте позже'],
                uncertain: ['The upload result could not be confirmed. The model may have been saved on CPM Paste. Uploading again may create another copy', 'Не удалось подтвердить результат загрузки. Модель могла сохраниться на CPM Paste. Повторная загрузка может создать ещё одну копию'],
                loading: ['Uploading model to CPM Paste...', 'Загрузка модели на CPM Paste...'],
                timeout: ['Timed out. Start the import again', 'Время ожидания истекло. Начните импорт заново']]
        String prefix = 'nclskins.add.cpm_publication_'
        Set<String> keys = expected.keySet().collect { prefix + it } as Set
        Set<String> reachable = LocalizationVerifier.productionKeys(repository)
        assertTrue(reachable.containsAll(keys))
        Set<String> retired = ['title', 'warning', 'confirm'].collect { prefix + it } as Set
        assertTrue(Collections.disjoint(reachable, retired))
        LocalizationVerifier.sourceLocales(catalog).each { String locale ->
            Map language = CatalogTools.loadJson(new File(repository,
                    "compat/resources/canonical/src/main/resources/assets/nclskins/lang/${locale}.json"))
            assertEquals(keys, language.keySet().findAll { it.startsWith(prefix) } as Set)
            retired.each { assertFalse(language.containsKey(it), locale + ':' + it) }
            expected.each { String suffix, List<String> copy ->
                String key = prefix + suffix
                String value = language[key]
                assertNotNull(value, locale + ':' + key)
                assertFalse(value.trim().isEmpty(), locale + ':' + key)
                if (suffix != 'loading') assertFalse(value.endsWith('.'), locale + ':' + key)
                if (suffix == 'loading') assertTrue(value.endsWith('...'), locale + ':' + key)
                if (locale in ['en_us', 'ru_ru']) assertEquals(copy[locale == 'en_us' ? 0 : 1], value, locale + ':' + key)
                else assertNotEquals(copy[0], value, locale + ':' + key)
                if (suffix != 'timeout') assertTrue(value.contains('CPM Paste'), locale + ':' + key)
            }
            assertFalse(language.containsKey(prefix + 'retry'), locale)
            assertNotEquals(language[prefix + 'missing_session'], language[prefix + 'proof_rejected'], locale)
            assertNotEquals(language[prefix + 'count_quota'], language[prefix + 'size_quota'], locale)
            assertNotEquals(language[prefix + 'network'], language[prefix + 'protocol'], locale)
        }
    }

    @Test
    void countQuotaMenuCopyIsAtomicAndPositionalInEveryAuthoredLocale() {
        Map<String, List<String>> expected = [
                en_us: ["You've reached the upload limit on CPM Paste! You can delete unneeded models from %1\$s - %2\$s - %3\$s", "You've reached the upload limit on CPM Paste!"],
                ru_ru: ['Вы достигли лимита загрузок на CPM Paste! Удалить лишние модели можно в меню %1$s - %2$s - %3$s', 'Вы достигли лимита загрузок на CPM Paste!'],
                de_de: ['Du hast das Upload-Limit auf CPM Paste erreicht! Nicht mehr benötigte Modelle kannst du unter %1$s - %2$s - %3$s löschen', 'Du hast das Upload-Limit auf CPM Paste erreicht!'],
                pt_br: ['Você atingiu o limite de uploads no CPM Paste! Para excluir modelos que não usa mais, acesse %1$s - %2$s - %3$s', 'Você atingiu o limite de uploads no CPM Paste!'],
                es_mx: ['¡Alcanzaste el límite de subidas en CPM Paste! Puedes eliminar los modelos que ya no necesitas en %1$s - %2$s - %3$s', '¡Alcanzaste el límite de subidas en CPM Paste!'],
                es_es: ['¡Has alcanzado el límite de subidas en CPM Paste! Puedes eliminar los modelos que ya no necesitas en %1$s - %2$s - %3$s', '¡Has alcanzado el límite de subidas en CPM Paste!']]
        assertEquals(LocalizationVerifier.sourceLocales(catalog) as Set, expected.keySet())
        expected.each { String locale, List<String> copy ->
            Map language = CatalogTools.loadJson(new File(repository,
                    "compat/resources/canonical/src/main/resources/assets/nclskins/lang/${locale}.json"))
            assertEquals(copy[0], language['nclskins.add.cpm_publication_count_quota'], locale)
            assertEquals(copy[1], language['nclskins.add.cpm_publication_count_quota_unavailable'], locale)
            ['%1$s', '%2$s', '%3$s'].each { assertEquals(1, copy[0].count(it), locale) }
            assertFalse(copy[1].contains('%'), locale)
            assertTrue(language.keySet().every { !it.startsWith('button.cpm.') }, locale)
        }
    }

    @Test
    void previewAndImportDeltaCopyKeepsPublicKeysAndRemovesOnlyRetiredConcepts() {
        Map expected = [
                'nclskins.editor.cpm_preview_failed': ['Could not load the CPM model', 'Не удалось загрузить модель CPM'],
                'nclskins.editor.cpm_preview_blocked': ['CPM settings prevent this model from being shown. Showing the base skin', 'Настройки CPM запрещают показ модели. Показан базовый скин'],
                'nclskins.editor.cpm_preview_timeout': ['Timed out waiting for the CPM model. Showing the base skin', 'Время ожидания модели CPM истекло. Показан базовый скин'],
                'nclskins.add_source.url_hint': ['PNG URL', 'Ссылка на PNG'],
                'nclskins.add_source.url_cpm_hint': ['URL for png, cpmmodel or cpmproject', 'Ссылка на png, cpmmodel или cpmproject'],
                'nclskins.compatibility.reason.cpm_fresh_moves_overlap': ['Active facial animation may display this skin incorrectly.', 'Активная анимация лица может неправильно отображать этот скин.'],
                'nclskins.compatibility.reason.cpm_just_expressions_overlap': ['Active facial animation may display this skin incorrectly.', 'Активная анимация лица может неправильно отображать этот скин.']]
        def retired = ['nclskins.editor.cpm_fallback_choose', 'nclskins.editor.cpm_fallback_choose_hint',
                'nclskins.editor.cpm_fallback_variant_mismatch', 'nclskins.add.cpm_publication_retry',
                'nclskins.add_source.cpm.external_blocked']
        Set reachable = LocalizationVerifier.productionKeys(repository)
        assertTrue(reachable.containsAll(expected.keySet()))
        assertTrue(retired.every { !reachable.contains(it) })
        Map failureCopy = [en_us: 'Could not load the CPM model', ru_ru: 'Не удалось загрузить модель CPM',
                de_de: 'Das CPM-Modell konnte nicht geladen werden', pt_br: 'Não foi possível carregar o modelo CPM',
                es_mx: 'No se pudo cargar el modelo CPM', es_es: 'No se ha podido cargar el modelo CPM']
        LocalizationVerifier.sourceLocales(catalog).each { locale ->
            Map language = CatalogTools.loadJson(new File(repository, "compat/resources/canonical/src/main/resources/assets/nclskins/lang/${locale}.json"))
            assertEquals(failureCopy[locale], language['nclskins.editor.cpm_preview_failed'], locale)
            assertFalse(language['nclskins.editor.cpm_preview_failed'].contains('%'))
            assertFalse(language['nclskins.editor.cpm_preview_failed'].contains('\n'))
            retired.each { assertFalse(language.containsKey(it), locale + ':' + it) }
            expected.each { key, values ->
                assertNotNull(language[key], locale + ':' + key)
                if (locale in ['en_us', 'ru_ru']) assertEquals(values[locale == 'en_us' ? 0 : 1], language[key], locale + ':' + key)
                else assertNotEquals(values[0], language[key], locale + ':' + key)
            }
            assertEquals(language['nclskins.compatibility.reason.cpm_fresh_moves_overlap'], language['nclskins.compatibility.reason.cpm_just_expressions_overlap'])
            assertTrue(language['nclskins.compatibility.reason.cpm_fresh_moves_overlap'].endsWith('.'))
            assertTrue(language['nclskins.add_source.url_hint'].contains('PNG'))
            assertTrue(language['nclskins.add_source.url_cpm_hint'].contains('png'))
        }
    }

    @Test
    void cpmChooserCopyIsCompleteAndExactForApprovedLanguages() {
        Map<String, List<String>> expected = [
                title: ['Choose a base skin for the model', 'Выберите базовый скин для модели'],
                launcher_blocked: ['Launcher imports are unavailable when choosing a base skin', 'При выборе базового скина импорт из лаунчеров недоступен'],
                mod_blocked: ['Mod imports are unavailable when choosing a base skin', 'При выборе базового скина импорт из модов недоступен'],
                nested_file: ['Choose a PNG file for the base skin. CPM model files cannot be used here.', 'Для базового скина выберите PNG-файл. Файл модели CPM здесь использовать нельзя.'],
                dependency_missing: ['Install Customizable Player Models to import a model file.', 'Для импорта файла модели установите Customizable Player Models.'],
                adapter_unavailable: ['Model file import is unavailable with the installed CPM version.', 'Импорт файлов моделей недоступен в установленной версии CPM.'],
                invalid_model: ['Could not read the CPM model. Check the file or link', 'Не удалось прочитать модель CPM. Проверьте файл или ссылку'],
                model_unsupported: ['NCL Skins does not support importing this model yet', 'Импорт этой модели пока не поддерживается в NCL Skins'],
                required_slim: ['This CPM model requires a Slim skin', 'Эта модель CPM предназначена только для стройной модели скина'],
                required_classic: ['This CPM model requires a Classic skin', 'Эта модель CPM предназначена только для классической модели скина'],
                local_overflow: ['This model depends on local CPM data that cannot be saved in a skin. Choose another model.', 'Модель зависит от локальных данных CPM, которые нельзя сохранить в скине. Выберите другую модель.'],
                portability_unsupported: ["Portability of this CPM model's data is not supported. Choose another model.", 'Переносимость данных этой модели CPM не поддерживается. Выберите другую модель.'],
                conversion_failed: ['This CPM model cannot be converted to a skin', 'Эту модель CPM нельзя преобразовать в скин'],
                invalid_output: ['CPM returned an unsupported skin. Import cancelled.', 'CPM вернул неподдерживаемый скин. Импорт отменён.'],
                stale_resources: ['Catalog resources changed. Reopen the base skin chooser.', 'Ресурсы каталога изменились. Откройте выбор базового скина заново.'],
                stale_world: ['The game context changed. Choose the base skin again.', 'Игровой контекст изменился. Выберите базовый скин ещё раз.']]
        File directory = new File(repository, 'compat/resources/canonical/src/main/resources/assets/nclskins/lang')
        LocalizationVerifier.sourceLocales(catalog).each { String locale ->
            Map language = CatalogTools.loadJson(new File(directory, "${locale}.json"))
            expected.each { String suffix, List<String> copy ->
                String key = 'nclskins.add_source.cpm.' + suffix
                assertNotNull(language[key], locale + ':' + key)
                if (locale in ['en_us', 'ru_ru']) assertEquals(copy[locale == 'en_us' ? 0 : 1], language[key], key)
                else assertNotEquals(copy[0], language[key], locale + ':' + key)
            }
        }
    }

    @Test
    void convertedAlternateCopyIsExactAndCompleteWithoutChooserMismatchReuse() {
        File directory = new File(repository, 'compat/resources/canonical/src/main/resources/assets/nclskins/lang')
        String key = 'nclskins.editor.cpm_variant_unavailable'
        Map expected = [
                en_us: 'This skin type is unavailable for the imported CPM model.',
                ru_ru: 'Этот тип скина недоступен для импортированной модели CPM.',
                de_de: 'Dieser Skin-Typ ist für das importierte CPM-Modell nicht verfügbar.',
                pt_br: 'Este tipo de skin não está disponível para o modelo CPM importado.',
                es_mx: 'Este tipo de skin no está disponible para el modelo CPM importado.',
                es_es: 'Este tipo de skin no está disponible para el modelo CPM importado.']
        LocalizationVerifier.sourceLocales(catalog).each { String locale ->
            Map language = CatalogTools.loadJson(new File(directory, "${locale}.json"))
            assertEquals(expected[locale], language[key], locale + ':' + key)
            assertNotEquals(language['nclskins.add_source.cpm.required_slim'], language[key])
            assertNotEquals(language['nclskins.add_source.cpm.required_classic'], language[key])
        }
        assertTrue(LocalizationVerifier.productionKeys(repository).contains(key))
    }

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
        assertEquals(360, baseline.size())
        assertEquals('Embedded', baseline['nclskins.capes.embedded'])
        assertEquals('%s: %s', baseline['nclskins.inspection.source'])
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

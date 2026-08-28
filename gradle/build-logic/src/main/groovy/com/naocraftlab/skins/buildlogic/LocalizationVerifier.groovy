package com.naocraftlab.skins.buildlogic

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Matcher
import java.util.regex.Pattern

final class LocalizationVerifier {
    static final List<String> EXPECTED_SOURCE_LOCALES = [
            'en_us', 'ru_ru', 'de_de', 'pt_br', 'es_mx', 'es_es'
    ].asImmutable()
    static final Map<String, String> EXPECTED_ALIASES = [
            es_ar: 'es_mx',
            es_cl: 'es_mx',
            es_ec: 'es_mx',
            es_uy: 'es_mx',
            es_ve: 'es_mx'
    ].asImmutable()
    static final Set<String> DESCRIPTION_KEYS = [
            'modmenu.descriptionTranslation.nclskins',
            'fml.menu.mods.info.description.nclskins',
            'neoforge.screen.mods.info.description.nclskins'
    ].asImmutable()
    static final Set<String> INTENTIONAL_EMPTY_KEYS = [
            'nclskins.compatibility.tooltip.blank'
    ].asImmutable()
    static final List<String> PROTECTED_EXTERNAL_NAMES = [
            'Minecraft Launcher', 'CurseForge App', 'Modrinth App', 'Skin Shuffle',
            'SimpleSkinSwapper', 'Skin Swapper', 'Quick Skin', 'Prism Launcher',
            'Ears', 'Fresh Moves', 'Just Expressions', 'YouTube', 'Telegram'
    ].asImmutable()
    static final Map<String, List<String>> EXPECTED_MODEL_LABELS = [
            en_us: ['Model: Classic', 'Model: Slim'],
            ru_ru: ['Модель: Классическая', 'Модель: Стройная'],
            de_de: ['Modell: Klassisch', 'Modell: Schmal'],
            pt_br: ['Modelo: Clássica', 'Modelo: Slim'],
            es_mx: ['Modelo: Clásico', 'Modelo: Delgado'],
            es_es: ['Modelo: Clásico', 'Modelo: Delgado']
    ].asImmutable()
    static final Map<String, List<String>> EXPECTED_FILTER_MODEL_LABELS = [
            en_us: ['Classic', 'Slim'],
            ru_ru: ['Классические', 'Стройные'],
            de_de: ['Klassisch', 'Schmal'],
            pt_br: ['Clássica', 'Slim'],
            es_mx: ['Clásico', 'Delgado'],
            es_es: ['Clásico', 'Delgado']
    ].asImmutable()
    static final Map<String, String> EXPECTED_NO_CAPE_LABELS = [
            en_us: 'No cape',
            ru_ru: 'Без плаща',
            de_de: 'Kein Umhang',
            pt_br: 'Sem capa',
            es_mx: 'Sin capa',
            es_es: 'Sin capa'
    ].asImmutable()
    static final Map<String, List<String>> EXPECTED_COLLECTION_NAMES = [
            en_us: ['MINECON Earth 2017', 'Builders & Biomes: Farmer’s Market', 'Striding Hero',
                    'The Garden Awakens', 'Chase the Skies', 'The Copper Age', 'Mounts of Mayhem',
                    'Tiny Takeover', 'Chaos Cubed'],
            ru_ru: ['MINECON Earth 2017', 'Строители и Биомы: Фермерский рынок', 'Шагающий герой',
                    'Пробуждение сада', 'В погоне за небесами', 'Медный век', 'Скакуны хаоса',
                    'Нашествие крошек', 'Хаос в кубе'],
            de_de: ['MINECON Earth 2017', 'Builders & Biomes: Farmer’s Market',
                    'Striding Hero', 'Der Garten erwacht', 'Jagd auf den Himmel',
                    'Das Kupferzeitalter', 'Reittiere des Chaos', 'Tiny Takeover',
                    'Chaos Cubed'],
            pt_br: ['MINECON Earth 2017', 'Builders & Biomes: Farmer’s Market',
                    'Striding Hero', 'O Despertar do Jardim', 'Persiga os Céus',
                    'A Era do Cobre', 'Montarias do Caos', 'Tiny Takeover',
                    'Caos ao Cubo'],
            es_mx: ['MINECON Earth 2017', 'Builders & Biomes: Farmer’s Market',
                    'Héroe caminante', 'El jardín despierta', 'Persigue los Cielos',
                    'La Edad del Cobre',
                    'Monturas del Caos', 'Bebés al poder', 'Caos al cubo'],
            es_es: ['MINECON Earth 2017', 'Builders & Biomes: Farmer’s Market',
                    'Héroe caminante', 'El jardín despierta', 'Persigue los Cielos',
                    'La Era del Cobre',
                    'Monturas del Caos', 'Bebés al poder', 'Caos al cubo']
    ].asImmutable()
    private static final Map<String, Map<String, String>> EXPECTED_COLLECTION_DESCRIPTIONS = [
            ru_ru: [
                    builders_and_biomes: 'Девять скинов к дополнению «Строители и Биомы: Фермерский рынок» для Minecraft.',
                    tiny_takeover: 'Два скина, выпущенных для испытаний «Нашествие крошек».'
            ],
            de_de: [
                    builders_and_biomes: 'Neun Skins wurden für die Minecraft-Erweiterung „Builders & Biomes: Farmer’s Market“ veröffentlicht.',
                    chase_the_skies: 'Zwei Skins wurden für die Herausforderungen von „Jagd auf den Himmel“ veröffentlicht.',
                    mounts_of_mayhem: 'Ein Skin wurde für die Herausforderungen des Drops „Reittiere des Chaos“ veröffentlicht.',
                    tiny_takeover: 'Zwei Skins wurden für die Herausforderungen von „Tiny Takeover“ veröffentlicht.',
                    chaos_cubed: 'Drei Skins wurden für das Event „Chaos Cubed“ veröffentlicht.'
            ],
            pt_br: [
                    builders_and_biomes: 'Nove skins foram lançadas para a expansão “Builders & Biomes: Farmer’s Market” de Minecraft.',
                    tiny_takeover: 'Duas skins foram lançadas nos desafios de “Tiny Takeover”.'
            ],
            es_mx: [
                    builders_and_biomes: 'Nueve skins publicadas para la expansión «Builders & Biomes: Farmer’s Market» de Minecraft.'
            ],
            es_es: [
                    builders_and_biomes: 'Nueve skins publicadas para la expansión «Builders & Biomes: Farmer’s Market» de Minecraft.'
            ]
    ].asImmutable()
    private static final Map<String, String> EXPECTED_BUILDERS_CREDIT = [
            en_us: 'Mojang Studios Stockholm and Redmond artists',
            ru_ru: 'Художники Mojang Studios из Стокгольма и Редмонда',
            de_de: 'Künstlerinnen und Künstler von Mojang Studios in Stockholm und Redmond',
            pt_br: 'Artistas da Mojang Studios em Estocolmo e Redmond',
            es_mx: 'Artistas de Mojang Studios en Estocolmo y Redmond',
            es_es: 'Artistas de Mojang Studios en Estocolmo y Redmond'
    ].asImmutable()
    private static final List<String> COLLECTION_IDS = [
            'minecon_earth_2017', 'builders_and_biomes', 'striding_hero',
            'the_garden_awakens', 'chase_the_skies', 'the_copper_age',
            'mounts_of_mayhem', 'tiny_takeover', 'chaos_cubed'
    ].asImmutable()
    private static final Map<String, Map<String, String>> EXPECTED_ENTITY_SKIN_NAMES = [
            ru_ru: [stray: 'Зимогор', strider: 'Лавомерка', villager_1: 'Крестьянин 1',
                    wither_skeleton: 'Визер-скелет', creaking: 'Скрипун',
                    zombie_horse_onesie: 'Кигуруми лошади-зомби',
                    baby_bee_fan: 'Фанат пчёлок', baby_axolotl_fan: 'Фанат аксолотликов'],
            de_de: [stray: 'Eiswanderer', strider: 'Schreiter', villager_1: 'Dorfbewohner 1',
                    wither_skeleton: 'Wither-Skelett', creaking: 'Knarz',
                    zombie_horse_onesie: 'Zombiepferd-Kostüm',
                    baby_bee_fan: 'Babybienen-Fan', baby_axolotl_fan: 'Babyaxolotl-Fan'],
            pt_br: [stray: 'Errante', strider: 'Lavagante', villager_1: 'Aldeão 1',
                    wither_skeleton: 'Esqueleto Wither', creaking: 'Rangente',
                    zombie_horse_onesie: 'Macacão de cavalo-zumbi',
                    baby_bee_fan: 'Fã de abelhinha', baby_axolotl_fan: 'Fã de axolotinho'],
            es_es: [stray: 'Errante', strider: 'Lavagante', villager_1: 'Aldeano 1',
                    wither_skeleton: 'Esqueleto del Wither', creaking: 'Crepitante',
                    zombie_horse_onesie: 'Pijama de caballo zombi',
                    baby_bee_fan: 'Fan de la abejita', baby_axolotl_fan: 'Fan del ajolotito'],
            es_mx: [stray: 'Errante', strider: 'Strider', villager_1: 'Aldeano 1',
                    wither_skeleton: 'Esqueleto del Wither', creaking: 'Crepitante',
                    zombie_horse_onesie: 'Mameluco de caballo zombi',
                    baby_bee_fan: 'Fan de la abejita', baby_axolotl_fan: 'Fan del ajolotito']
    ].asImmutable()
    private static final List<String> LAYER_KEYS = [
            'outer_head_on', 'outer_head_off', 'outer_body_all_on', 'outer_body_all_off',
            'outer_body_no_arms', 'outer_body_arms_without_body',
            'outer_body_only_left_arm', 'outer_body_only_right_arm',
            'outer_body_and_left_arm', 'outer_body_and_right_arm',
            'outer_legs_all_on', 'outer_legs_all_off',
            'outer_legs_no_left_leg', 'outer_legs_no_right_leg'
    ].asImmutable()
    private static final Map<String, List<String>> EXPECTED_LAYER_LABELS = [
            en_us: ['On: %1$s', 'Off: %1$s',
                    'On: %1$s, %2$s, %3$s', 'Off: %1$s, %2$s, %3$s',
                    'On: %1$s\nOff: %2$s, %3$s', 'On: %2$s, %3$s\nOff: %1$s',
                    'On: %2$s\nOff: %1$s, %3$s', 'On: %3$s\nOff: %1$s, %2$s',
                    'On: %1$s, %2$s\nOff: %3$s', 'On: %1$s, %3$s\nOff: %2$s',
                    'On: %1$s, %2$s', 'Off: %1$s, %2$s',
                    'On: %2$s\nOff: %1$s', 'On: %1$s\nOff: %2$s'],
            ru_ru: ['Вкл: %1$s', 'Выкл: %1$s',
                    'Вкл: %1$s, %2$s, %3$s', 'Выкл: %1$s, %2$s, %3$s',
                    'Вкл: %1$s\nВыкл: %2$s, %3$s', 'Вкл: %2$s, %3$s\nВыкл: %1$s',
                    'Вкл: %2$s\nВыкл: %1$s, %3$s', 'Вкл: %3$s\nВыкл: %1$s, %2$s',
                    'Вкл: %1$s, %2$s\nВыкл: %3$s', 'Вкл: %1$s, %3$s\nВыкл: %2$s',
                    'Вкл: %1$s, %2$s', 'Выкл: %1$s, %2$s',
                    'Вкл: %2$s\nВыкл: %1$s', 'Вкл: %1$s\nВыкл: %2$s'],
            de_de: ['An: %1$s', 'Aus: %1$s',
                    'An: %1$s, %2$s, %3$s', 'Aus: %1$s, %2$s, %3$s',
                    'An: %1$s\nAus: %2$s, %3$s', 'An: %2$s, %3$s\nAus: %1$s',
                    'An: %2$s\nAus: %1$s, %3$s', 'An: %3$s\nAus: %1$s, %2$s',
                    'An: %1$s, %2$s\nAus: %3$s', 'An: %1$s, %3$s\nAus: %2$s',
                    'An: %1$s, %2$s', 'Aus: %1$s, %2$s',
                    'An: %2$s\nAus: %1$s', 'An: %1$s\nAus: %2$s'],
            pt_br: ['Ativado: %1$s', 'Desativado: %1$s',
                    'Ativados: %1$s, %2$s, %3$s', 'Desativados: %1$s, %2$s, %3$s',
                    'Ativado: %1$s\nDesativadas: %2$s, %3$s',
                    'Ativadas: %2$s, %3$s\nDesativado: %1$s',
                    'Ativada: %2$s\nDesativados: %1$s, %3$s',
                    'Ativada: %3$s\nDesativados: %1$s, %2$s',
                    'Ativados: %1$s, %2$s\nDesativada: %3$s',
                    'Ativados: %1$s, %3$s\nDesativada: %2$s',
                    'Ativadas: %1$s, %2$s', 'Desativadas: %1$s, %2$s',
                    'Ativada: %2$s\nDesativada: %1$s', 'Ativada: %1$s\nDesativada: %2$s'],
            es_mx: ['Activado: %1$s', 'Desactivado: %1$s',
                    'Activadas: %1$s, %2$s, %3$s', 'Desactivadas: %1$s, %2$s, %3$s',
                    'Activada: %1$s\nDesactivadas: %2$s, %3$s',
                    'Activadas: %2$s, %3$s\nDesactivada: %1$s',
                    'Activada: %2$s\nDesactivadas: %1$s, %3$s',
                    'Activada: %3$s\nDesactivadas: %1$s, %2$s',
                    'Activadas: %1$s, %2$s\nDesactivada: %3$s',
                    'Activadas: %1$s, %3$s\nDesactivada: %2$s',
                    'Activadas: %1$s, %2$s', 'Desactivadas: %1$s, %2$s',
                    'Activada: %2$s\nDesactivada: %1$s', 'Activada: %1$s\nDesactivada: %2$s'],
            es_es: ['Activado: %1$s', 'Desactivado: %1$s',
                    'Activadas: %1$s, %2$s, %3$s', 'Desactivadas: %1$s, %2$s, %3$s',
                    'Activada: %1$s\nDesactivadas: %2$s, %3$s',
                    'Activadas: %2$s, %3$s\nDesactivada: %1$s',
                    'Activada: %2$s\nDesactivadas: %1$s, %3$s',
                    'Activada: %3$s\nDesactivadas: %1$s, %2$s',
                    'Activadas: %1$s, %2$s\nDesactivada: %3$s',
                    'Activadas: %1$s, %3$s\nDesactivada: %2$s',
                    'Activadas: %1$s, %2$s', 'Desactivadas: %1$s, %2$s',
                    'Activada: %2$s\nDesactivada: %1$s', 'Activada: %1$s\nDesactivada: %2$s']
    ].asImmutable()
    private static final Pattern LOCALE_ID = Pattern.compile('^[a-z]{2}_[a-z]{2}$')
    private static final Pattern TRANSLATION_LITERAL = Pattern.compile(
            '"((?:nclskins|modmenu|fml|neoforge|pack)\\.[a-z0-9_.-]+)"')
    private static final Pattern FORMAT_ARGUMENT = Pattern.compile('%(?:[1-9][0-9]*\\$)?[a-zA-Z%]')
    private static final Set<String> PRODUCTION_EXTENSIONS = [
            '.java', '.kt', '.json', '.toml', '.gradle'
    ].asImmutable()

    private LocalizationVerifier() {}

    static List<String> sourceLocales(Map catalog) {
        Object raw = ((catalog.mod instanceof Map ? catalog.mod : [:]) as Map)
                .get('localization')
        if (!(raw instanceof Map) || !((raw as Map).sourceLocales instanceof List)) {
            return []
        }
        ((raw as Map).sourceLocales as List).collect { it.toString() }
    }

    static Map<String, String> aliases(Map catalog) {
        Object raw = ((catalog.mod instanceof Map ? catalog.mod : [:]) as Map)
                .get('localization')
        if (!(raw instanceof Map) || !((raw as Map).aliases instanceof Map)) {
            return [:]
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>()
        ((raw as Map).aliases as Map).each { Object alias, Object source ->
            result[alias.toString()] = source.toString()
        }
        result
    }

    static List<String> artifactLocales(Map catalog) {
        sourceLocales(catalog) + aliases(catalog).keySet().toList()
    }

    static String sourceLocale(Map catalog, String locale) {
        sourceLocales(catalog).contains(locale) ? locale : aliases(catalog)[locale]
    }

    static void validateDeclaration(Map catalog, List<String> errors) {
        Map mod = catalog.mod instanceof Map ? catalog.mod as Map : [:]
        Map localization = mod.localization instanceof Map ? mod.localization as Map : [:]
        if ((localization.keySet() as Set) != ['sourceLocales', 'aliases'] as Set) {
            errors.add('mod.localization must define exactly sourceLocales and aliases')
            return
        }
        List<String> sources = sourceLocales(catalog)
        Map<String, String> mappings = aliases(catalog)
        if (sources != EXPECTED_SOURCE_LOCALES) {
            errors.add("mod.localization.sourceLocales must equal ${EXPECTED_SOURCE_LOCALES}")
        }
        if (sources.size() != sources.toSet().size() ||
                sources.any { !LOCALE_ID.matcher(it).matches() }) {
            errors.add('mod.localization.sourceLocales must contain unique lowercase locale IDs')
        }
        if (mappings != EXPECTED_ALIASES) {
            errors.add("mod.localization.aliases must equal ${EXPECTED_ALIASES}")
        }
        mappings.each { String alias, String source ->
            if (!LOCALE_ID.matcher(alias).matches() || sources.contains(alias) ||
                    !sources.contains(source) || mappings.containsKey(source)) {
                errors.add("invalid locale alias ${alias} -> ${source}")
            }
        }
        List<String> outputs = artifactLocales(catalog)
        if (outputs.size() != outputs.toSet().size()) {
            errors.add('mod.localization produces duplicate locale output paths')
        }
        Map descriptions = mod.descriptions instanceof Map ? mod.descriptions as Map : [:]
        if ((descriptions.keySet() as List) != sources || descriptions.any { Object locale, Object value ->
            !(value instanceof String) || value.toString().isBlank() ||
                    value.toString().contains('\u2014') || value.toString().contains('\u2013')
        }) {
            errors.add('mod.descriptions must define one non-empty dash-safe value per source locale in manifest order')
        }
    }

    static void validateRepository(File repositoryRoot, Map catalog, List<String> errors) {
        List<String> sources = sourceLocales(catalog)
        if (sources.isEmpty()) return
        File canonicalDirectory = new File(repositoryRoot,
                'compat/resources/canonical/src/main/resources/assets/nclskins/lang')
        File collectionsDirectory = new File(repositoryRoot,
                'compat/resources/mojang-collections/src/main/resources/resourcepacks/' +
                        'mojang_collections/assets/nclskins/lang')
        validateSourceInventory(canonicalDirectory, sources, 'canonical', errors)
        validateSourceInventory(collectionsDirectory, sources, 'mojang-collections', errors)

        Map<String, Map> canonical = loadLanguages(canonicalDirectory, sources, 'canonical', errors)
        Map<String, Map> collections = loadLanguages(
                collectionsDirectory, sources, 'mojang-collections', errors)
        if (canonical.keySet().containsAll(sources)) {
            Map baseline = canonical.en_us
            canonical.each { String locale, Map language ->
                validateLanguage(locale, language, baseline, true, errors)
            }
            Set<String> reachable = productionKeys(repositoryRoot)
            Set<String> baselineKeys = normalizedKeys(baseline)
            difference(reachable, baselineKeys).each {
                errors.add("canonical missing production key ${it}")
            }
            difference(baselineKeys, reachable).each {
                errors.add("canonical orphan key ${it}")
            }
        }
        Set<String> expectedCollections = collectionKeys(repositoryRoot)
        collections.each { String locale, Map language ->
            Set<String> actual = normalizedKeys(language)
            difference(expectedCollections, actual).each {
                errors.add("mojang-collections ${locale}: missing key ${it}")
            }
            difference(actual, expectedCollections).each {
                errors.add("mojang-collections ${locale}: orphan key ${it}")
            }
            validateValues("mojang-collections ${locale}", language, errors)
        }
        if (collections.keySet().containsAll(sources)) {
            collections.each { String locale, Map language ->
                validateCollectionSemantics(locale, language, collections.en_us, errors)
            }
        }
        validateFinderMetadata(new File(repositoryRoot,
                'compat/resources/canonical/src/main/resources'), errors)
        validateFinderMetadata(new File(repositoryRoot,
                'compat/resources/mojang-collections/src/main/resources'), errors)
    }

    static void validateLanguage(
            String locale,
            Map language,
            Map baseline,
            boolean sourceTemplate,
            List<String> errors) {
        Set<String> actual = normalizedKeys(language)
        Set<String> expected = normalizedKeys(baseline)
        difference(expected, actual).each { errors.add("${locale}: missing key ${it}") }
        difference(actual, expected).each { errors.add("${locale}: extra key ${it}") }
        validateValues(locale, language, errors)
        Set<String> shared = new TreeSet<>(expected)
        shared.retainAll(actual)
        shared.each { String key ->
            String reference = baseline[key]?.toString()
            String translation = language[key]?.toString()
            if (argumentSignature(reference) != argumentSignature(translation)) {
                errors.add("${locale}: ${key} has incompatible format arguments")
            }
            if (reference.count('\n') != translation.count('\n')) {
                errors.add("${locale}: ${key} has incompatible line breaks")
            }
        }
        if (sourceTemplate) {
            DESCRIPTION_KEYS.each { String key ->
                if (language[key] != '@NCLSKINS_DESCRIPTION@') {
                    errors.add("${locale}: ${key} must use the catalog description template token")
                }
            }
        }
        if (language['nclskins.modmenu.youtube'] != 'YouTube') {
            errors.add("${locale}: nclskins.modmenu.youtube must preserve YouTube")
        }
        if (language['nclskins.modmenu.x'] != 'X') {
            errors.add("${locale}: nclskins.modmenu.x must preserve X")
        }
        if (!language['nclskins.modmenu.telegram_bot']?.toString()?.contains('Telegram')) {
            errors.add("${locale}: nclskins.modmenu.telegram_bot must preserve Telegram")
        }
        PROTECTED_EXTERNAL_NAMES.each { String name ->
            shared.each { String key ->
                if (baseline[key]?.toString()?.contains(name) &&
                        !language[key]?.toString()?.contains(name)) {
                    errors.add("${locale}: ${key} must preserve external name ${name}")
                }
            }
        }
        List<String> expectedModels = EXPECTED_MODEL_LABELS[locale]
        if (expectedModels != null && [language['nclskins.editor.arms_classic'],
                language['nclskins.editor.arms_slim']] != expectedModels) {
            errors.add("${locale}: editor model labels must use one reviewed term without slash synonyms")
        }
        List<String> expectedFilters = EXPECTED_FILTER_MODEL_LABELS[locale]
        if (expectedFilters != null && [language['nclskins.add_source.filter_classic'],
                language['nclskins.add_source.filter_slim']] != expectedFilters) {
            errors.add("${locale}: catalog model filters must match reviewed editor terminology")
        }
        String expectedNoCape = EXPECTED_NO_CAPE_LABELS[locale]
        if (expectedNoCape != null && language['nclskins.editor.no_cape'] != expectedNoCape) {
            errors.add("${locale}: no-cape choice must use its reviewed dedicated label")
        }
        List<String> expectedLayers = EXPECTED_LAYER_LABELS[locale]
        if (expectedLayers != null) {
            List<String> actualLayers = LAYER_KEYS.collect {
                language["nclskins.editor.${it}"]?.toString()
            }
            if (actualLayers != expectedLayers) {
                errors.add("${locale}: outer-layer labels must group parts into one or two state-first lines")
            }
        }
    }

    static void validateCollectionSemantics(
            String locale, Map language, Map baseline, List<String> errors) {
        List<String> expectedNames = EXPECTED_COLLECTION_NAMES[locale]
        if (expectedNames != null) {
            List<String> actualNames = COLLECTION_IDS.collect {
                language["nclskins.mojang_${it}.name"]?.toString()
            }
            if (actualNames != expectedNames) {
                errors.add("mojang-collections ${locale}: collection names must match reviewed source map")
            }
        }
        EXPECTED_COLLECTION_DESCRIPTIONS[locale]?.each { String collectionId, String expected ->
            String key = "nclskins.mojang_${collectionId}.description"
            if (language[key] != expected) {
                errors.add("mojang-collections ${locale}: ${key} must match the reviewed collection title")
            }
        }
        String buildersCredit = EXPECTED_BUILDERS_CREDIT[locale]
        if (buildersCredit != null &&
                language['nclskins.mojang_builders_and_biomes.authors'] != buildersCredit) {
            errors.add("mojang-collections ${locale}: descriptive author credit must be localized")
        }
        language.findAll { Object key, Object ignored ->
            key.toString().endsWith('.authors') &&
                    key.toString() != 'nclskins.mojang_builders_and_biomes.authors'
        }.each { Object key, Object value ->
            if (value != baseline[key]) {
                errors.add("mojang-collections ${locale}: ${key} must preserve the credited identity")
            }
        }
        EXPECTED_ENTITY_SKIN_NAMES[locale]?.each { String skinId, String expected ->
            Map.Entry entry = language.find { Object key, Object ignored ->
                key.toString().endsWith(".skin.${skinId}.name")
            }
            if (entry == null || entry.value != expected) {
                errors.add("mojang-collections ${locale}: ${skinId} must use its reviewed natural name")
            }
        }
    }

    static List<String> argumentSignature(String value) {
        if (value == null) return []
        Matcher matcher = FORMAT_ARGUMENT.matcher(value)
        List<String> arguments = []
        while (matcher.find()) {
            String token = matcher.group()
            if (token != '%%') arguments.add(token)
        }
        boolean positional = arguments.every { it ==~ /%[1-9][0-9]*\$[a-zA-Z]/ }
        positional ? arguments.sort() : arguments
    }

    static Set<String> productionKeys(File repositoryRoot) {
        Set<String> keys = []
        [
                'client-runtime/src/main',
                'client-contract/src/main',
                'compat',
                'targets'
        ].each { String relative ->
            File root = new File(repositoryRoot, relative)
            if (!root.isDirectory()) return
            Files.walk(root.toPath()).withCloseable { stream ->
                stream.filter { Path path ->
                    Files.isRegularFile(path) && isProductionSource(path)
                }.forEach { Path path ->
                    String text
                    try {
                        text = Files.readString(path, StandardCharsets.UTF_8)
                    } catch (Exception ignored) {
                        return
                    }
                    Matcher matcher = TRANSLATION_LITERAL.matcher(text)
                    while (matcher.find()) {
                        String key = matcher.group(1)
                        if (!key.endsWith('.json') && !key.endsWith('.accesswidener') &&
                                !key.endsWith('.mixins')) {
                            keys.add(key)
                        }
                    }
                }
            }
        }
        keys.addAll(DESCRIPTION_KEYS)
        keys.addAll([
                'nclskins.modmenu.youtube',
                'nclskins.modmenu.telegram_bot',
                'nclskins.modmenu.x',
                'pack.nclskins.mojang_collections.name',
                'pack.nclskins.mojang_collections.description',
                'nclskins.standard_skins.name',
                'nclskins.standard_skins.description',
                'nclskins.standard_skins.authors'
        ])
        (1..8).each { keys.add("nclskins.compatibility.tooltip.${it}") }
        ['ears', 'fresh_moves', 'just_expressions'].each {
            keys.add("nclskins.compatibility.feature.${it}")
        }
        ['malformed_ears_data', 'malformed_expressive_data', 'missing_expressive_runtime'].each {
            keys.add("nclskins.compatibility.reason.${it}")
        }
        List<String> importSources = [
                'minecraft_launcher', 'curseforge_app', 'modrinth_app', 'skin_shuffle',
                'skin_swapper_family', 'quick_skin', 'prism_launcher'
        ]
        importSources.each { String source ->
            keys.add("nclskins.external_import.${source}")
            keys.add("nclskins.external_import.unavailable.${source}")
            keys.add("nclskins.external_import.invalid_folder.${source}")
        }
        keys.removeAll([
                'nclskins.compatibility.feature.',
                'nclskins.compatibility.reason.',
                'nclskins.compatibility.tooltip.',
                'nclskins.external_import.',
                'nclskins.external_import.unavailable.',
                'nclskins.external_import.invalid_folder.'
        ])
        keys.collect { it.toString() } as Set
    }

    static Set<String> collectionKeys(File repositoryRoot) {
        File assets = new File(repositoryRoot,
                'compat/resources/mojang-collections/src/main/resources/resourcepacks/' +
                        'mojang_collections/assets')
        Set<String> keys = []
        File[] collections = assets.listFiles()?.findAll {
            it.isDirectory() && it.name.startsWith('mojang_')
        }?.sort { it.name } ?: []
        collections.each { File collection ->
            String prefix = "nclskins.${collection.name}"
            keys.add("${prefix}.name")
            keys.add("${prefix}.description")
            keys.add("${prefix}.authors")
            File player = new File(collection, 'textures/entity/player')
            if (player.isDirectory()) {
                Files.walk(player.toPath()).withCloseable { stream ->
                    stream.filter { Path path ->
                        Files.isRegularFile(path) && path.fileName.toString().endsWith('.png')
                    }.forEach { Path path ->
                        String name = path.fileName.toString()
                        keys.add("${prefix}.skin.${name.substring(0, name.length() - 4)}.name")
                    }
                }
            }
        }
        keys.collect { it.toString() } as Set
    }

    private static Map<String, Map> loadLanguages(
            File directory,
            List<String> locales,
            String label,
            List<String> errors) {
        Map<String, Map> result = [:]
        locales.each { String locale ->
            File file = new File(directory, "${locale}.json")
            try {
                result[locale] = CatalogTools.loadJson(file)
            } catch (Exception error) {
                errors.add("cannot read ${label} ${locale}: ${error.message}")
            }
        }
        result
    }

    private static void validateSourceInventory(
            File directory,
            List<String> locales,
            String label,
            List<String> errors) {
        Set<String> expected = locales.collect { "${it}.json" } as Set
        Set<String> actual = directory.listFiles()?.findAll {
            it.isFile() && it.name.endsWith('.json')
        }?.collect { it.name } as Set ?: [] as Set
        if (actual != expected) {
            errors.add("${label} source locale files ${actual.sort()} differ from ${expected.sort()}")
        }
    }

    private static void validateValues(String locale, Map language, List<String> errors) {
        language.each { Object rawKey, Object rawValue ->
            String key = rawKey.toString()
            if (!(rawValue instanceof String)) {
                errors.add("${locale}: ${key} must be a string")
                return
            }
            String value = rawValue.toString()
            if (value.isEmpty() && !INTENTIONAL_EMPTY_KEYS.contains(key)) {
                errors.add("${locale}: ${key} must not be empty")
            }
            if (!value.isEmpty() && INTENTIONAL_EMPTY_KEYS.contains(key)) {
                errors.add("${locale}: ${key} must remain intentionally empty")
            }
            if (value.contains('\u2014') || value.contains('\u2013')) {
                errors.add("${locale}: ${key} must not use long dashes")
            }
        }
    }

    private static Set<String> normalizedKeys(Map language) {
        new LinkedHashSet<>(language.keySet().collect { it.toString() })
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left.collect { it.toString() })
        result.removeAll(right.collect { it.toString() })
        result
    }

    private static boolean isProductionSource(Path path) {
        String normalized = path.toString().replace(File.separatorChar, '/' as char)
        if (normalized.contains('/build/') || normalized.contains('/src/test/') ||
                normalized.contains('/lang/')) {
            return false
        }
        PRODUCTION_EXTENSIONS.any { normalized.endsWith(it) }
    }

    private static void validateFinderMetadata(File root, List<String> errors) {
        if (!root.isDirectory()) return
        Files.walk(root.toPath()).withCloseable { stream ->
            stream.filter { Path path ->
                Files.isRegularFile(path) && (path.fileName.toString() == '.DS_Store' ||
                        path.toString().replace(File.separatorChar, '/' as char).contains('/__MACOSX/'))
            }.forEach { Path path ->
                errors.add("production resource root contains Finder metadata: ${root.toPath().relativize(path)}")
            }
        }
    }
}

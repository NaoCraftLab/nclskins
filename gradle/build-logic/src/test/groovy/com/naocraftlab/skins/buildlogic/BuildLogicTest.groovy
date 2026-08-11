package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import javax.imageio.ImageIO
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

import static org.junit.jupiter.api.Assertions.*

final class BuildLogicTest {
    private final File repository = new File('../..').canonicalFile
    private final Map catalog = CatalogTools.loadCatalog(repository)
    private final Map abi = CatalogTools.loadJson(new File(repository, 'gradle/abi-fingerprints.json'))

    @Test
    void currentCatalogIsValid() {
        assertEquals(12, catalog.schemaVersion)
        assertEquals('00000000-0000-0000-0000-000000000001', catalog.development.clientUuid)
        assertEquals(LinkedHashMap, catalog.getClass())
        assertEquals(LinkedHashMap, catalog.gradleFamilies.getClass())
        assertEquals(LinkedHashMap, catalog.targets.first().getClass())
        assertEquals(
                catalog.targets.collect { "targets/${it.minecraft.version}/${it.loader.id}".toString() },
                catalog.targets.collect { it.path })
        CatalogTools.validate(repository, catalog)
    }

    @Test
    void parchmentMappingsAreExactCatalogOwnedAndLimitedToPre261Targets() {
        Map<String, Map<String, String>> expected = [
                '1.20.1' : [
                        version: '2023.09.03', artifactVersion: '2023.09.03'],
                '1.21.1' : [version: '2024.11.17', artifactVersion: '2024.11.17'],
                '1.21.11': [
                        version: '2025.12.21-nightly-SNAPSHOT',
                        artifactVersion: '2025.12.21-nightly-20251221.125209-1']
        ]
        assertEquals(expected, catalog.mappings.parchment)
        assertEquals('1.2.0', catalog.plugins.librarian)

        catalog.targets.each { Map target ->
            Map<String, String> expectedMapping = expected[target.minecraft.version]
            assertEquals(
                    expectedMapping?.version,
                    CatalogTools.parchmentVersion(catalog, target),
                    target.id.toString())
            String expectedUrl = expectedMapping == null ? null :
                    "https://maven.parchmentmc.org/org/parchmentmc/data/" +
                            "parchment-${target.minecraft.version}/${expectedMapping.version}/" +
                            "parchment-${target.minecraft.version}-${expectedMapping.artifactVersion}.zip"
            assertEquals(
                    expectedUrl,
                    CatalogTools.parchmentArtifactUrl(catalog, target),
                    target.id.toString())
            assertEquals(
                    expectedMapping?.artifactVersion,
                    CatalogTools.parchmentArtifactVersion(catalog, target),
                    target.id.toString())
        }

        ['latest', 'BLEEDING-SNAPSHOT', '2025.12.+', '[2025.12.20,)'].each { String dynamic ->
            Map invalid = cloneMap(catalog)
            invalid.mappings.parchment['1.21.11'].version = dynamic
            assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, invalid) }
        }

        Map mutableSnapshot = cloneMap(catalog)
        mutableSnapshot.mappings.parchment['1.21.11'].artifactVersion =
                '2025.12.21-nightly-SNAPSHOT'
        assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, mutableSnapshot) }
    }

    @Test
    void everyMappedLoaderUsesItsCatalogOwnedParchmentIntegration() {
        String fabric = new File(repository, 'gradle/loader-conventions/fabric.gradle').text
        String forge = new File(repository, 'gradle/loader-conventions/forge.gradle').text
        String neoforge = new File(repository, 'gradle/loader-conventions/neoforge.gradle').text
        String forgeTarget = new File(repository, 'targets/1.20.1/forge/build.gradle').text
        String settings = new File(repository, 'settings.gradle').text

        assertTrue(fabric.contains('mappings loom.layered()'))
        assertTrue(fabric.contains('officialMojangMappings()'))
        assertTrue(fabric.contains('parchment(parchmentArtifactUrl)'))
        assertTrue(forge.contains("mappings channel: 'parchment'"))
        assertTrue(neoforge.contains('parchment {'))
        assertTrue(neoforge.contains('parchmentArtifact ='))
        assertTrue(settings.contains("maven { url = 'https://maven.parchmentmc.org' }"))
        assertTrue(forgeTarget.contains(
                "id 'org.parchmentmc.librarian.forgegradle' version '${catalog.plugins.librarian}'"))
    }

    @Test
    void canonicalIconDimensionsMatchTheRendererAndMetadataContracts() {
        assertEquals(false, catalog.mod.iconBlur)
        File resources = new File(repository, 'compat/resources/canonical/src/main/resources')
        def modIcon = ImageIO.read(new File(resources, catalog.mod.icon.toString()))
        assertEquals(128, modIcon.width)
        assertEquals(128, modIcon.height)

        File icons = new File(resources, ArtifactVerifier.BUTTONS)
        Set<String> sourceNames = icons.listFiles()
                .findAll { it.name.endsWith('.png') }
                .collect { it.name } as Set
        assertEquals(ArtifactVerifier.BUTTON_ICON_SIZES.keySet(), sourceNames)
        assertEquals(20, ArtifactVerifier.BUTTON_ICON_SIZES.values().count { it == 20 })
        assertEquals(2, ArtifactVerifier.BUTTON_ICON_SIZES.values().count { it == 32 })
        ArtifactVerifier.BUTTON_ICON_SIZES.each { String name, int size ->
            def image = ImageIO.read(new File(icons, name))
            assertEquals(size, image.width, name)
            assertEquals(size, image.height, name)
            if (size == 20) {
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        if (x < 2 || y < 2 || x >= size - 2 || y >= size - 2) {
                            assertEquals(
                                    0,
                                    image.getRGB(x, y) >>> 24,
                                    "${name} must keep a transparent two-pixel source border at ${x},${y}")
                        }
                    }
                }
            }
        }
    }

    @Test
    void reuseProbePrefersSameEpochAndRejectsUndeclaredImplementations() {
        Map target = CatalogTools.selectTarget(catalog, 'fabric-1.20.1')
        List<Map> candidates = CapabilityReuse.candidates(catalog, target, 'gui')

        assertEquals('immediate-1.20', candidates.first().implementation)
        assertTrue(candidates.first().sameEpoch)
        assertTrue(candidates.first().reused)
        assertEquals(
                'immediate-1.21',
                CatalogTools.withCapabilityProbe(
                        catalog, target, 'gui=immediate-1.21').capabilities.gui)
        assertThrows(IllegalArgumentException) {
            CatalogTools.withCapabilityProbe(catalog, target, 'gui=missing-adapter')
        }
        assertThrows(IllegalArgumentException) {
            CatalogTools.withCapabilityProbe(
                    catalog, target, 'textures=immediate-1.20')
        }
    }

    @Test
    void syntheticNinthTargetReusesEveryExistingCapabilityWithoutNewDeclarations() {
        Map target = cloneMap(CatalogTools.selectTarget(catalog, 'fabric-26.2'))
        target.id = 'fabric-26.2-fixture'

        CatalogTools.REQUIRED_CAPABILITIES.each { String capability ->
            List<Map> candidates = CapabilityReuse.candidates(catalog, target, capability)
            assertFalse(candidates.isEmpty(), capability)
            assertEquals(target.capabilities[capability], candidates.first().implementation, capability)
            assertTrue(candidates.first().reused, capability)
        }
    }

    @Test
    void targetProfilesRejectHiddenOverrides() {
        Map changed = cloneMap(catalog)
        changed.targets.find { it.id == 'fabric-1.21.1' }
                .capabilities.textures = 'resource-location-legacy'

        IllegalArgumentException failure = assertThrows(IllegalArgumentException) {
            CatalogTools.validate(repository, changed)
        }
        assertTrue(failure.message.contains('textures differs from epoch profile'))
    }

    @Test
    void selectedMixinResourcesMustBeRegisteredInTargetMetadata() {
        Map changed = cloneMap(catalog)
        changed.targets.find { it.id == 'fabric-1.21.11' }
                .metadata.serverMixins = []

        IllegalArgumentException failure = assertThrows(IllegalArgumentException) {
            CatalogTools.validate(repository, changed)
        }
        assertTrue(failure.message.contains(
                'fabric-1.21.11: selected Mixin resources'))
        assertTrue(failure.message.contains('nclskins.authlib9.mixins.json'))
        assertTrue(failure.message.contains('differ from metadata'))
    }

    @Test
    void reuseNegativeFixturesReportHiddenDependencyWrongSuiteAbiAndNewAdapter() {
        Map target = CatalogTools.selectTarget(catalog, 'fabric-1.20.1')
        Map coverage = CatalogTools.loadJson(
                new File(repository, 'gradle/capability-semantic-coverage.json'))
        Map candidate = CapabilityReuse.candidates(catalog, target, 'gui').first()

        Map hiddenDependency = cloneMap(catalog)
        hiddenDependency.sourceBundles['immediate-1.20'].requires = ['missing-hidden-bundle']
        Map hidden = CapabilityReuse.inspect(
                repository, hiddenDependency, abi, coverage, target, 'gui', candidate)
        assertEquals('REJECTED', hidden.staticStatus)
        assertTrue(
                hidden.failures.any {
                    it.toLowerCase().contains('missing') &&
                            it.toLowerCase().contains('source bundle')
                },
                hidden.failures.toString())

        Map wrongSuite = cloneMap(coverage)
        wrongSuite.implementations['immediate-1.20'].capabilityKey = 'textures'
        Map wrong = CapabilityReuse.inspect(
                repository, catalog, abi, wrongSuite, target, 'gui', candidate)
        assertEquals('REJECTED', wrong.staticStatus)
        assertTrue(wrong.failures.contains('missing executable semantic contract'))

        assertFalse(CapabilityReuse.matchesDeclaredAbi(
                abi, 'immediate-1.20', ['example.Wrong': '0' * 64]))
        assertThrows(IllegalStateException) {
            CapabilityReuse.requireReuseFirstSelection(
                    'fixture-target', 'gui', 'new-adapter', 'immediate-1.20')
        }
    }

    @Test
    void zeroAbiDeclarationHashIsRejected() {
        assertFalse(CatalogTools.validAbiDeclarationHash('0' * 64))
        assertFalse(CatalogTools.validAbiDeclarationHash('abc'))
        assertTrue(CatalogTools.validAbiDeclarationHash(
                abi.implementations['immediate-1.20'].baselineSha256))
    }

    @Test
    void clientUuidIsCanonicalCatalogOwnedAndUsedByEveryClientRun() {
        List<String> expected = ['--uuid', '00000000-0000-0000-0000-000000000001']
        assertEquals(expected, CatalogTools.clientArguments(catalog))
        catalog.targets.each { Map target ->
            assertEquals(expected, CatalogTools.clientArguments(catalog), target.id.toString())
        }

        Map missing = cloneMap(catalog)
        missing.remove('development')
        assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, missing) }
        assertThrows(IllegalArgumentException) { CatalogTools.clientArguments(missing) }

        Map extra = cloneMap(catalog)
        extra.development.unexpected = true
        assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, extra) }
        assertThrows(IllegalArgumentException) { CatalogTools.clientArguments(extra) }

        ['00000000-0000-0000-0000-00000000001',
         'AAAAAAAA-0000-0000-0000-000000000001',
         'not-a-uuid'].each { String invalidUuid ->
            Map invalid = cloneMap(catalog)
            invalid.development.clientUuid = invalidUuid
            assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, invalid) }
            assertThrows(IllegalArgumentException) { CatalogTools.clientArguments(invalid) }
        }
    }

    @Test
    void loaderAndCompatibilityClientRunsReceiveUuidWithoutServerLeakage() {
        Map<String, String> loaderScripts = [
                fabric  : new File(repository, 'gradle/loader-conventions/fabric.gradle').text,
                forge   : new File(repository, 'gradle/loader-conventions/forge.gradle').text,
                neoforge: new File(repository, 'gradle/loader-conventions/neoforge.gradle').text
        ]
        loaderScripts.each { String loader, String script ->
            String helperCall = 'nclskinsCatalogTools.clientArguments(targetCatalog)'
            assertEquals(1, occurrences(script, helperCall), loader)
            int clientStart = script.indexOf('client {')
            int serverStart = script.indexOf('server {', clientStart)
            int helperStart = script.indexOf(helperCall)
            assertTrue(clientStart >= 0 && helperStart > clientStart, loader)
            if (serverStart >= 0) {
                assertTrue(helperStart < serverStart, loader)
                assertFalse(script.substring(serverStart).contains(helperCall), loader)
            } else {
                assertEquals('fabric', loader)
            }
        }

        File fixtureJar = new File(repository, 'build/compatibility-runs/test-fixture.jar')
        catalog.targets.findAll { it.containsKey('compatibility') }.each { Map target ->
            Map runtime = CatalogTools.compatibilityRuntime(target, target.minecraft.version.toString())
            String script = CompatibilityHarness.buildFile(repository, catalog, target, runtime, fixtureJar)
            assertEquals(1, occurrences(script, "'--uuid'"), target.id.toString())
            assertEquals(1, occurrences(script, "'00000000-0000-0000-0000-000000000001'"), target.id.toString())
            int serverStart = script.indexOf('        server {')
            if (serverStart < 0) {
                serverStart = script.indexOf("tasks.named('runServer'")
            }
            assertTrue(serverStart >= 0, target.id.toString())
            String serverRun = script.substring(serverStart)
            assertFalse(serverRun.contains('--uuid'), target.id.toString())
            assertFalse(serverRun.contains('00000000-0000-0000-0000-000000000001'), target.id.toString())
        }
    }

    @Test
    void compatibilityMatricesAreOptionalExactAndBaselineFirst() {
        Map ordinary = catalog.targets.find { !it.containsKey('compatibility') } as Map
        assertNotNull(ordinary)
        Map family = catalog.targets.find { it.id == 'fabric-26.1' } as Map
        assertEquals(['26.1', '26.1.1', '26.1.2'], family.compatibility.minecraftVersions)
        assertEquals(
                [minecraftVersion: '26.1.2', loaderVersion: '0.19.3'],
                CatalogTools.compatibilityRuntime(family, '26.1.2'))
        assertThrows(IllegalArgumentException) {
            CatalogTools.compatibilityRuntime(family, '26.1.3')
        }

        Map invalid = cloneMap(family)
        invalid.compatibility.minecraftVersions = ['26.1.1', '26.1']
        List<String> errors = []
        CatalogTools.validateCompatibility(
                invalid,
                invalid.loader.id.toString(),
                invalid.minecraft.version.toString(),
                invalid.loader as Map,
                errors)
        assertTrue(errors.any { it.contains('compile-baseline') })
    }

    @Test
    void canonicalPlayerFacingTranslationsHaveLocaleParityAndLookTerminology() {
        Map english = CatalogTools.loadJson(new File(
                repository,
                'compat/resources/canonical/src/main/resources/assets/nclskins/lang/en_us.json'))
        Map russian = CatalogTools.loadJson(new File(
                repository,
                'compat/resources/canonical/src/main/resources/assets/nclskins/lang/ru_ru.json'))

        assertEquals(english.keySet(), russian.keySet())
        [en_us: english, ru_ru: russian].each { String locale, Map translations ->
            translations.each { String key, Object value ->
                assertInstanceOf(String, value, "${locale}: ${key}")
                String normalized = value.toString().toLowerCase(Locale.ROOT)
                assertFalse(normalized.contains('preset'), "${locale}: ${key}")
                assertFalse(normalized.contains('пресет'), "${locale}: ${key}")
            }
        }

        assertEquals('My looks', english['nclskins.gallery.title'])
        assertEquals('New look', english['nclskins.gallery.add_hint'])
        assertEquals('Search', english['nclskins.gallery.search_hint'])
        assertEquals('Refresh session', english['nclskins.session.retry'])
        assertEquals('Offline', english['nclskins.session.offline'])
        assertEquals(
                'Minecraft is temporarily delaying skin and cape changes. Your latest choice will apply automatically.',
                english['nclskins.rate_limit.delayed'])
        assertEquals('Мои образы', russian['nclskins.gallery.title'])
        assertEquals('Новый образ', russian['nclskins.gallery.add_hint'])
        assertEquals('Поиск', russian['nclskins.gallery.search_hint'])
        assertEquals('Обновить сессию', russian['nclskins.session.retry'])
        assertEquals('Оффлайн', russian['nclskins.session.offline'])
        assertEquals(
                'Minecraft временно задерживает смену скина и плаща. Последний образ применится автоматически.',
                russian['nclskins.rate_limit.delayed'])
        assertEquals(
                'The site did not allow automatic downloading',
                english['nclskins.add_source.url_site_blocked'])
        assertEquals(
                'Сайт не разрешил автоматическое скачивание',
                russian['nclskins.add_source.url_site_blocked'])
        assertEquals('Minecraft Event Skins', english['pack.nclskins.mojang_collections.name'])
        assertEquals(
                'Officially published skins by Mojang',
                english['pack.nclskins.mojang_collections.description'])
        assertEquals('Скины событий Minecraft', russian['pack.nclskins.mojang_collections.name'])
        assertEquals(
                'Официально опубликованные скины от Mojang',
                russian['pack.nclskins.mojang_collections.description'])
    }

    @Test
    void sharedConfigurationScreenUsesPickerAndAllServerControls() {
        String factory = new File(
                repository,
                'compat/config-screen/src/main/java/com/naocraftlab/skins/compat/config/YaclConfigurationScreenFactory.java').text
        String bridge = new File(
                repository,
                'compat/config-screen/src/main/java/com/naocraftlab/skins/compat/config/MinecraftConfigurationBridge.java').text
        String folderController = new File(
                repository,
                'compat/config-screen/src/main/java/com/naocraftlab/skins/compat/config/FolderPickerController.java').text

        assertTrue(factory.contains('new FolderPickerController('))
        assertTrue(factory.contains('.customController('))
        assertFalse(factory.contains('StringControllerBuilder'))
        [
                'enabled.name',
                'trusted_proxy_forwarding.name',
                'max_concurrent_lookups.name',
                'lookup_rate_per_second.name',
                'lookup_burst.name'
        ].each { String suffix ->
            assertTrue(factory.contains("nclskins.config.server.realtime_refresh.${suffix}"), suffix)
        }
        assertEquals(2, occurrences(factory, '.flag(OptionFlag.GAME_RESTART)'))
        assertFalse(factory.contains('.available('))
        assertTrue(factory.contains('checkedServerAccess.visible()'))
        assertTrue(factory.contains('if (serverDraft != null)'))
        assertTrue(factory.contains('checkedServerAccess.restartRequired()'))
        assertFalse(factory.contains('nclskins.config.server.unavailable'))
        assertTrue(factory.contains('restartWhenServerRunning('))
        assertTrue(folderController.contains('ActionController.ActionControllerElement'))
        assertTrue(folderController.contains('option.pendingValue()'))
        assertTrue(folderController.contains('draft.selectDataDirectory('))
        assertTrue(folderController.contains('selected.ifPresent(option::requestSet)'))
        assertTrue(folderController.contains('public boolean canReset()'))
        assertTrue(folderController.contains('return true;'))
        assertTrue(bridge.contains('new ConfirmLinkScreen(callback, YACL_URL, true)'))
        assertFalse(bridge.contains('nclskins.config.missing_yacl'))
        assertTrue(bridge.contains('screenSetter.accept(parent)'))
        assertTrue(bridge.contains('linkOpener.accept(URI.create(YACL_URL))'))
        assertTrue(bridge.contains('minecraft.getConnection() != null'))
        assertTrue(bridge.contains('minecraft.getSingleplayerServer() != null'))
        assertTrue(bridge.contains('ServerConfigurationAccess.from('))
        assertFalse(bridge.contains('Minecraft.class.getMethod('))
        assertFalse(bridge.contains('getMethod("openUri"'))
    }

    @Test
    void everyPublishedDependencyPredicateContainsOnlyItsMinimumVersion() {
        catalog.targets.each { Map target ->
            if (target.loader.id == 'fabric') {
                assertTrue(target.loader.version ==~ /[0-9]+\.[0-9]+\.[0-9]+/)
                assertEquals(">=${target.loader.version}".toString(), target.loader.predicate)
                assertEquals(">=${target.loader.apiVersion}".toString(), target.loader.apiPredicate)
                assertEquals(">=${target.minecraft.version}".toString(), target.minecraft.predicate)
            } else {
                assertEquals("[${target.loader.version},)".toString(), target.loader.predicate)
                assertEquals("[${target.minecraft.version},)".toString(), target.minecraft.predicate)
            }
        }
        assertEquals(['0.19.3'] as Set, catalog.targets.findAll { it.loader.id == 'fabric' }.collect { it.loader.version } as Set)
    }

    @Test
    void everyCatalogLoaderUsesARegisteredBackend() {
        assertEquals(['fabric', 'forge', 'neoforge'] as Set, LoaderBackend.ids())
        catalog.targets.each { Map target ->
            LoaderBackend backend = LoaderBackend.require(target.loader.id.toString())
            assertEquals(target.minecraft.predicate,
                    backend.minecraftPredicate(target.minecraft.version.toString()))
            assertEquals(target.metadata.keySet() as Set, backend.metadataKeys())
            assertFalse(backend.metadata(catalog, target, '1.0.0').isEmpty())
        }
        assertThrows(IllegalArgumentException) { LoaderBackend.require('unknown') }
    }

    @Test
    void upperBoundAndMismatchedFabricFloorAreRejected() {
        Map upperBound = cloneMap(catalog)
        upperBound.targets.find { it.id == 'forge-1.20.1' }.loader.predicate = '[47.4.10,48)'
        assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, upperBound) }
        Map minecraftUpperBound = cloneMap(catalog)
        minecraftUpperBound.targets.find { it.id == 'neoforge-26.1' }.minecraft.predicate = '[26.1,26.2)'
        assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, minecraftUpperBound) }
        Map mismatchedLoader = cloneMap(catalog)
        mismatchedLoader.targets.find { it.id == 'fabric-26.2' }.loader.predicate = '>=0.19.0'
        assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, mismatchedLoader) }
    }

    @Test
    void everyTargetResolvesSourceBundles() {
        catalog.targets.each { Map target ->
            Map resolved = CatalogTools.resolveTargetSources(repository, catalog, target)
            assertFalse(resolved.java.isEmpty(), target.id.toString())
            assertFalse(resolved.resources.isEmpty(), target.id.toString())
        }
    }

    @Test
    void everyPipTargetSelectsExactlyOneLoaderNativeRegistration() {
        Map<String, String> expected = [
                'fabric-1.21.11'  : 'avatar-pip-1.21.11-fabric',
                'neoforge-1.21.11': 'avatar-pip-1.21.11-neoforge',
                'fabric-26.1'     : 'avatar-pip-26.1-fabric',
                'neoforge-26.1'   : 'avatar-pip-26.1-neoforge',
                'fabric-26.2'     : 'avatar-pip-26.2-fabric',
                'neoforge-26.2'   : 'avatar-pip-26.2-neoforge'
        ]
        Set<String> registrationBundles = expected.values() as Set

        List<Map> targets = catalog.targets.findAll {
            it.capabilities.preview.toString().startsWith('avatar-pip-')
        } as List<Map>
        assertEquals(expected.keySet(), targets.collect { it.id } as Set)
        targets.each { Map target ->
            assertEquals(expected[target.id], target.capabilities.preview, target.id.toString())
            Map resolved = CatalogTools.resolveTargetSources(repository, catalog, target)
            assertEquals(
                    [expected[target.id]],
                    (resolved.bundles as List).findAll { registrationBundles.contains(it) },
                    target.id.toString())
        }
    }

    @Test
    void onlyFabric12111UsesTheGuiRendererConstructorMixin() {
        List<Map> targets = catalog.targets.findAll {
            it.capabilities.preview.toString().startsWith('avatar-pip-')
        } as List<Map>
        targets.each { Map target ->
            Map resolved = CatalogTools.resolveTargetSources(repository, catalog, target)
            boolean hasConstructorMixin = (resolved.java as List).contains(
                    'loader/fabric/pip-1.21.11/src/main/java')
            boolean declaresMixinConfig = (target.metadata.mixins as List).contains(
                    'nclskins.mc12111.fabric.mixins.json')
            assertEquals(target.id == 'fabric-1.21.11', hasConstructorMixin, target.id.toString())
            assertEquals(target.id == 'fabric-1.21.11', declaresMixinConfig, target.id.toString())
        }
        String constructorMixin = new File(
                repository,
                'loader/fabric/pip-1.21.11/src/main/java/com/naocraftlab/skins/compat/mc12111/mixin/GuiRendererMixin.java').text
        assertTrue(constructorMixin.contains('List<PictureInPictureRenderer<?>>'))
        assertFalse(constructorMixin.contains('java.lang.reflect'))
        assertFalse(constructorMixin.contains('getMethod('))
        assertFalse(constructorMixin.contains('getConstructor('))
    }

    @Test
    void modernPipRegistrationsUseLoaderOwnedApisWithoutReflection() {
        ['26.1', '26.2'].each { String epoch ->
            String fabric = new File(
                    repository,
                    "loader/fabric/pip-${epoch}/src/main/java/com/naocraftlab/skins/loader/fabric/FabricPipRendererRegistration.java").text
            String neoForge = new File(
                    repository,
                    "loader/neoforge/pip-${epoch}/src/main/java/com/naocraftlab/skins/loader/neoforge/NeoForgePipRendererRegistration.java").text
            assertTrue(fabric.contains('PictureInPictureRendererRegistry.register'))
            assertTrue(neoForge.contains('RegisterPictureInPictureRenderersEvent'))
            assertTrue(neoForge.contains('event.register('))
            [fabric, neoForge].each { String source ->
                assertFalse(source.contains('java.lang.reflect'))
                assertFalse(source.contains('getMethod('))
                assertFalse(source.contains('getConstructor('))
            }
        }
    }

    @Test
    void settingsMixinsReplaceTheOpenScreenSupplierByMeaning() {
        [
                'compat/capabilities/gui/immediate-1.20/src/main/java/com/naocraftlab/skins/compat/v1_20_1/client/mixin/OptionsScreenMixin.java',
                'compat/capabilities/gui/immediate-1.21/src/main/java/com/naocraftlab/skins/mc1211/mixin/OptionsScreenMixin.java',
                'compat/capabilities/gui/submission-1.21.11/src/main/java/com/naocraftlab/skins/compat/mc12111/mixin/OptionsScreenMixin.java',
                'compat/capabilities/gui/extraction-26.2/src/main/java/com/naocraftlab/skins/compat/mc262/mixin/OptionsScreenMixin.java'
        ].each { String path ->
            String source = new File(repository, path).text
            assertTrue(source.contains('OptionsScreen;openScreenButton'), path)
            assertTrue(source.contains('index = 1'), path)
            assertFalse(source.contains('GridLayout\$RowHelper'), path)
            assertFalse(source.contains('addChild'), path)
        }
    }

    @Test
    void modernDepthHookRequiresExactlyOnePrepareSignature() {
        String source = new File(
                repository,
                'compat/capabilities/preview/avatar-pip-26.2/src/main/java/com/naocraftlab/skins/compat/mc262/mixin/PictureInPictureRendererMixin.java').text
        assertEquals(2, source.count('@Group(name = "nclskins$captureDepthMode", min = 1, max = 1)'))
        assertEquals(2, source.count('require = 0'))
        assertTrue(source.contains('PictureInPictureRenderState;Lnet/minecraft/client/renderer/state/gui/GuiRenderState;I)V'))
        assertTrue(source.contains('PictureInPictureRenderState;Lnet/minecraft/client/renderer/state/gui/GuiRenderState;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;I)V'))
    }

    @Test
    void missingBundleAndCyclesAreRejected() {
        Map missing = cloneMap(catalog)
        missing.sourceBundles['portable-common'].requires = ['missing']
        assertThrows(IllegalArgumentException) { CatalogTools.resolveBundleOrder(missing, missing.baseBundles as List<String>) }
        Map cycle = cloneMap(catalog)
        cycle.sourceBundles['portable-common'].requires = ['canonical-resources']
        cycle.sourceBundles['canonical-resources'].requires = ['portable-common']
        assertThrows(IllegalArgumentException) { CatalogTools.resolveBundleOrder(cycle, cycle.baseBundles as List<String>) }
    }

    @Test
    void affectedClassificationUsesCatalogOwnership() {
        assertEquals(['forge-1.20.1'] as Set, selected('targets/1.20.1/forge/build.gradle'))
        assertEquals(['forge-1.20.1'] as Set, selected('targets/forge/1.20.1/build.gradle'))
        assertEquals(['fabric-1.20.1', 'forge-1.20.1'] as Set, selected('compat/capabilities/gui/immediate-1.20/src/main/java/Example.java'))
        assertEquals(catalog.targets.collect { it.id } as Set, selected('core/src/main/java/com/naocraftlab/skins/core/Example.java'))
        assertEquals(catalog.targets.collect { it.id } as Set, selected('client-runtime/build.gradle'))
        assertEquals(catalog.targets.collect { it.id } as Set, selected('gradle/version.properties'))
    }

    @Test
    void unknownProductionPathIsRejected() {
        assertThrows(IllegalArgumentException) { CatalogTools.classifyAffected(repository, catalog, ['new-runtime/Main.java']) }
    }

    @Test
    void metadataMatchesEveryLoaderContract() {
        String version = CatalogTools.loadVersion(repository)
        catalog.targets.each { Map target ->
            Map<String, String> resources = MetadataRenderer.render(catalog, target, version)
            assertEquals(target.metadata.files as Set, resources.keySet() as Set, target.id.toString())
            assertTrue(resources.values().any { it.contains('GPL-3.0-only') }, target.id.toString())
            Map bundledPack = new JsonSlurper().parseText(
                    resources['resourcepacks/mojang_collections/pack.mcmeta']) as Map
            if (target.metadata.packFormat instanceof List) {
                assertEquals(target.metadata.packFormat, bundledPack.pack.min_format, target.id.toString())
                assertEquals(target.metadata.packFormat, bundledPack.pack.max_format, target.id.toString())
                assertFalse(bundledPack.pack.containsKey('pack_format'), target.id.toString())
            } else {
                assertEquals(target.metadata.packFormat, bundledPack.pack.pack_format, target.id.toString())
            }
            if (target.loader.id == 'fabric') {
                Map metadata = new JsonSlurper().parseText(resources['fabric.mod.json']) as Map
                assertEquals('*', metadata.environment)
                assertEquals([main: [target.metadata.serverEntrypoint], client: [target.metadata.entrypoint], modmenu: [target.metadata.modMenuEntrypoint]], metadata.entrypoints)
                assertEquals(catalog.mod.descriptions.en_us, metadata.description)
                assertEquals(catalog.mod.contact, metadata.contact)
                assertEquals([
                        modmenu    : ">=${target.loader.modMenuVersion}".toString(),
                        sqlite_jdbc              : catalog.optionalDependencies.sqlite_jdbc.predicates.fabric,
                        yet_another_config_lib_v3: CatalogTools.optionalDependencyPredicate(catalog, target, 'yet_another_config_lib_v3')
                ], metadata.suggests)
                assertEquals(true, metadata.custom.modmenu.update_checker)
                assertEquals(['modmenu.modrinth': 'https://modrinth.com/mod/nclskins', 'modmenu.curseforge': 'https://www.curseforge.com/minecraft/mc-mods/nclskins'], metadata.custom.modmenu.links)
            } else if (target.loader.id == 'forge') {
                assertTrue(resources['META-INF/mods.toml'].contains('logoBlur=false'))
                assertTrue(resources['META-INF/mods.toml'].contains('displayTest="IGNORE_SERVER_VERSION"'))
                assertTrue(resources['META-INF/mods.toml'].contains('showAsResourcePack=false'))
                assertTrue(resources['META-INF/mods.toml'].contains('features={java_version="[17,)"}'))
                assertTrue(resources['META-INF/mods.toml'].contains('updateJSONURL="https://api.modrinth.com/updates/nclskins/forge_updates.json"'))
                assertTrue(resources['META-INF/mods.toml'].contains('modId="sqlite_jdbc"'))
                assertTrue(resources['META-INF/mods.toml'].contains('modId="yet_another_config_lib_v3"'))
                assertTrue(resources['META-INF/mods.toml'].contains('mandatory=false'))
                assertTrue(resources['META-INF/mods.toml'].contains('side="CLIENT"'))
                assertFalse(resources['META-INF/mods.toml'].contains('[[mixins]]'))
            } else {
                assertTrue(resources['META-INF/neoforge.mods.toml'].contains('logoBlur=false'))
                assertTrue(resources['META-INF/neoforge.mods.toml'].contains("javaVersion=\"[${target.java.release},)\""))
                assertTrue(resources['META-INF/neoforge.mods.toml'].contains('showAsResourcePack=false'))
                assertTrue(resources['META-INF/neoforge.mods.toml'].contains('showAsDataPack=false'))
                assertTrue(resources['META-INF/neoforge.mods.toml'].contains('updateJSONURL="https://api.modrinth.com/updates/nclskins/forge_updates.json?neoforge=only"'))
                assertTrue(resources['META-INF/neoforge.mods.toml'].contains('modId="sqlite_jdbc"'))
                assertTrue(resources['META-INF/neoforge.mods.toml'].contains('modId="yet_another_config_lib_v3"'))
                assertTrue(resources['META-INF/neoforge.mods.toml'].contains('type="optional"'))
                assertTrue(resources['META-INF/neoforge.mods.toml'].contains('side="CLIENT"'))
                assertTrue(resources['META-INF/neoforge.mods.toml'].contains("file=\"${target.metadata.accessTransformer}\""))
                assertEquals((target.metadata.serverMixins ?: []) + target.metadata.mixins, resources['META-INF/neoforge.mods.toml'].readLines().findAll { it.startsWith('config=') }.collect { it.substring('config="'.length(), it.length() - 1) })
            }
        }
    }

    @Test
    void ideaRunsUseOnlyRootGradleTasks() {
        Set<String> taskNames = [] as Set
        catalog.targets.each { Map target ->
            ['Client', 'Server'].each { String runKind ->
                String taskName = IdeaRunConfigurations.taskName(target, runKind)
                assertTrue(taskNames.add(taskName))
                String rendered = IdeaRunConfigurations.render(target, runKind)
                def configuration = new groovy.xml.XmlSlurper().parseText(rendered).configuration
                assertEquals('GradleRunConfiguration', configuration.@type.toString())
                assertEquals('$PROJECT_DIR$', configuration.ExternalSystemSettings.option.find { it.@name == 'externalProjectPath' }.@value.toString())
                assertEquals(taskName, configuration.ExternalSystemSettings.option.find { it.@name == 'taskNames' }.list.option.@value.toString())
                assertFalse(rendered.contains('python'))
                assertFalse(rendered.contains('do' + 'cs/'))
                assertFalse(rendered.contains('scr' + 'ipts/'))
            }
        }
        assertEquals(20, taskNames.size())
    }

    @Test
    void privateToolReferencesAreRejectedFromPublicCode() {
        PublicationTreeVerifier.PRIVATE_TOOL_REFERENCES.each { String marker ->
            assertTrue(PublicationTreeVerifier.containsPrivateToolReference("prefix ${marker} suffix"))
        }
        assertFalse(PublicationTreeVerifier.containsPrivateToolReference('gradle/build-logic'))
    }

    @Test
    void targetRunCommandsUseCatalogWrappersPortsAndNativeTasks() {
        catalog.targets.each { Map target ->
            List<String> client = TargetRunTask.command(repository, catalog, target, 'Client', true)
            assertEquals('runClient', client[-2])
            assertEquals('--dry-run', client[-1])
            assertEquals(TargetRuntime.wrapper(repository, catalog, target).absolutePath, client.first())
            List<String> server = TargetRunTask.command(repository, catalog, target, 'Server', true)
            assertTrue(server.contains('runServer'))
            int port = target.development.serverPort as int
            if (target.loader.id == 'neoforge') assertTrue(server.contains("-PnclskinsServerPort=${port}".toString()))
            else assertTrue(server.contains("--args=--port ${port}".toString()))
        }
    }

    @Test
    void committedAbiBaselinesMatchEachTargetSelection() {
        catalog.targets.each { Map target ->
            Map declarations = catalog.capabilityImplementations as Map
            Set<String> selected = (target.capabilities as Map).values().collect { declarations[it].abiImplementation.toString() } as Set
            Map actual = new TreeMap()
            selected.each { actual[it] = abi.resolvedByProfile[target.epochProfile][it] }
            AbiVerifier.verify(catalog, abi, target.id.toString(), actual)
        }
    }

    @Test
    void genericAbiNamesAreSplitAndErased() {
        assertEquals(['java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>', 'java.lang.Runnable'], AbiVerifier.splitGeneric('java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>, java.lang.Runnable'))
        assertEquals('java.util.Map', AbiVerifier.eraseGenerics('java.util.Map<java.lang.String, java.lang.Integer>'))
    }

    @Test
    void selectedAbiHashSortsMembersDeterministically() {
        Map surface = [access: 'public', finality: 'concrete']
        List<Map> members = [
                [kind: 'method', name: 'zeta', descriptor: '()V', access: 'public', finality: 'virtual'],
                [kind: 'method', name: 'alpha', descriptor: '()V', access: 'public', finality: 'virtual']
        ]
        assertEquals(AbiVerifier.selectedHash('Example', surface, members, []), AbiVerifier.selectedHash('Example', surface, members.reverse(), []))
    }

    @Test
    void changedAbiBaselineIsRejected() {
        Map target = catalog.targets.first() as Map
        Map declarations = catalog.capabilityImplementations as Map
        Set<String> selected = (target.capabilities as Map).values().collect { declarations[it].abiImplementation.toString() } as Set
        Map actual = new TreeMap()
        selected.each { actual[it] = cloneMap(abi.resolvedByProfile[target.epochProfile][it] as Map) }
        String implementation = selected.first()
        String className = (actual[implementation] as Map).keySet().first()
        actual[implementation][className] = '0' * 64
        assertThrows(IllegalStateException) { AbiVerifier.verify(catalog, abi, target.id.toString(), actual) }
    }

    @Test
    void semanticManifestMatchesCurrentSources() {
        Map coverage = CatalogTools.loadJson(new File(repository, 'gradle/capability-semantic-coverage.json'))
        assertEquals([], SemanticVerifier.verify(repository.toPath(), catalog, abi, coverage))
    }

    @Test
    void semanticVerifierRequiresACompleteScopedRenderProxy() {
        Path sourceRoot = Files.createTempDirectory('nclskins-preview-semantics-')
        try {
            Files.writeString(
                    sourceRoot.resolve('Preview.java'),
                    'class PreviewPlayer extends RemotePlayer { '
                            + 'void render() { LocalPlayer player = minecraft.player; } }')
            List<String> errors = []

            SemanticVerifier.verifyPreviewBundle('fixture-preview', [sourceRoot] as Set, errors)

            assertTrue(errors.any { it.contains('lacks required isolated-proxy marker') })
            assertTrue(errors.any { it.contains('lacks readiness/animation marker') })
        } finally {
            sourceRoot.toFile().deleteDir()
        }
    }

    @Test
    void semanticVerifierRejectsSecond12111ScreenBackgroundPass() {
        List<String> errors = []

        SemanticVerifier.verifyLeaf(
                'submission-1.21.11',
                'gui',
                '''
                class ScreenLeaf {
                    ClientRuntime runtime;
                    void render(Object graphics, int mouseX, int mouseY, float partialTick) {
                        renderBackground(graphics, mouseX, mouseY, partialTick);
                    }
                }
                ''',
                errors)

        assertTrue(errors.any { it.contains('renders its native background twice') }, errors.toString())
    }

    @Test
    void semanticVerifierRejectsOneShared12111BakedTexture() {
        List<String> errors = []

        SemanticVerifier.verifyLeaf(
                'submission-1.21.11',
                'gui',
                'class ScreenLeaf { ClientRuntime runtime; '
                        + 'Minecraft12111SimplePreviewRenderer bakedRenderer; }',
                errors)

        assertTrue(errors.any {
            it.contains('native host lacks marker')
                    && it.contains('bakedRenderers.computeIfAbsent(')
        }, errors.toString())
    }

    @Test
    void submission12111OwnsNativeUiAndExactSettingsHooks() {
        File screen = new File(
                repository,
                'compat/capabilities/gui/submission-1.21.11/src/main/java/com/naocraftlab/skins/compat/mc12111/NclSkinsScreen.java')
        File menu = new File(
                repository,
                'compat/capabilities/gui/submission-1.21.11/src/main/java/com/naocraftlab/skins/compat/mc12111/NclSkinsMenuPanel.java')
        File mixins = new File(
                repository,
                'compat/capabilities/preview/avatar-pip-1.21.11/src/main/resources/nclskins.mc12111.mixins.json')
        String screenSource = screen.text
        String menuSource = menu.text
        String mixinSource = mixins.text

        assertFalse(screenSource.contains('ViewHostCoordinator'))
        assertFalse(screenSource.contains('setRectangle('))
        assertFalse(menuSource.contains('setRectangle('))
        assertTrue(screenSource.contains('Minecraft12111ScrollController'))
        assertTrue(screenSource.contains('''scrollController.render(
                graphics,
                OFFSCREEN_MOUSE_COORDINATE,
                OFFSCREEN_MOUSE_COORDINATE,
                partialTick);'''))
        assertTrue(new File(
                repository,
                'compat/capabilities/gui/submission-1.21.11/src/main/java/com/naocraftlab/skins/compat/mc12111/Minecraft12111ScrollController.java')
                .text.contains('renderScrollbar(graphics, mouseX, mouseY);'))
        assertTrue(screenSource.contains('NativeWidgetSignature'))
        assertTrue(screenSource.contains('NativeTabGroup'))
        assertTrue(screenSource.contains('maskWidgetsOutsideClip('))
        assertTrue(screenSource.contains('''
                            ACTION_ICON_RENDER_SIZE,
                            ACTION_ICON_RENDER_SIZE,
                            ACTION_ICON_TEXTURE_SIZE,
                            ACTION_ICON_TEXTURE_SIZE,
                            ACTION_ICON_TEXTURE_SIZE,
                            ACTION_ICON_TEXTURE_SIZE);'''))
        assertTrue(menuSource.contains('panelBounds().contains(mouseX, mouseY)'))
        assertTrue(mixinSource.contains('"OptionsScreenMixin"'))
        assertTrue(mixinSource.contains('"AccessibilityOptionsScreenMixin"'))
        assertTrue(new File(
                repository,
                'client-runtime/src/main/java/com/naocraftlab/skins/runtime/ViewHostCoordinator.java').exists())
    }

    @Test
    void playerLayerAnchorsCoverRemotePlayersWithoutSuppressingWorldFailures() {
        Map<String, String> layerMixins = [
                '1.20.1': 'compat/capabilities/gui/immediate-1.20/src/main/java/com/naocraftlab/skins/compat/v1_20_1/client/mixin/LivingEntityRendererPreviewMixin.java',
                '1.21.1': 'compat/capabilities/gui/immediate-1.21/src/main/java/com/naocraftlab/skins/mc1211/mixin/LivingEntityRendererPreviewMixin.java',
                '1.21.11': 'compat/capabilities/preview/avatar-pip-1.21.11/src/main/java/com/naocraftlab/skins/compat/mc12111/mixin/LivingEntityRendererPreviewMixin.java',
                '26.1': 'compat/capabilities/preview/avatar-pip-26.1/src/main/java/com/naocraftlab/skins/compat/mc262/mixin/LivingEntityRendererPreviewMixin.java',
                '26.2': 'compat/capabilities/preview/avatar-pip-render-26.2/src/main/java/com/naocraftlab/skins/compat/mc262/mixin/LivingEntityRendererPreviewMixin.java'
        ]

        layerMixins.each { String epoch, String relativePath ->
            String source = new File(repository, relativePath).text
            if (epoch in ['1.20.1', '1.21.1']) {
                assertTrue(source.contains('if (!(entity instanceof Player))'), epoch)
            } else {
                assertTrue(source.contains('if (!(state instanceof AvatarRenderState'),
                        epoch)
            }
            assertFalse(source.contains('boolean localPlayer'), epoch)
            assertTrue(source.contains('EditorPreviewLayerGuard.isActive()'), epoch)
            assertTrue(source.contains('EditorPreviewLayerGuard.handle(failure)'), epoch)
        }
    }

    @Test
    void submission12111PreviewExtractsAndSubmitsInsideDedicatedDeferredRenderer() {
        File previewRoot = new File(
                repository,
                'compat/capabilities/preview/avatar-pip-1.21.11/src/main')
        File renderer = new File(
                previewRoot,
                'java/com/naocraftlab/skins/compat/mc12111/Minecraft12111PreviewRenderer.java')
        File liveRenderer = new File(
                previewRoot,
                'java/com/naocraftlab/skins/compat/mc12111/Minecraft12111LivePreviewRenderer.java')
        File liveState = new File(
                previewRoot,
                'java/com/naocraftlab/skins/compat/mc12111/Minecraft12111LivePreviewRenderState.java')
        File scope = new File(
                previewRoot,
                'java/com/naocraftlab/skins/compat/mc12111/Minecraft12111PreviewScope.java')
        File guiMixin = new File(
                previewRoot,
                'java/com/naocraftlab/skins/compat/mc12111/mixin/GuiEntityRendererMixin.java')
        File layerMixin = new File(
                previewRoot,
                'java/com/naocraftlab/skins/compat/mc12111/mixin/LivingEntityRendererPreviewMixin.java')
        File modelPartMixin = new File(
                previewRoot,
                'java/com/naocraftlab/skins/compat/mc12111/mixin/ModelPartPreviewMixin.java')
        File mixins = new File(previewRoot, 'resources/nclskins.mc12111.mixins.json')

        assertTrue(scope.exists())
        assertFalse(guiMixin.exists())
        assertTrue(liveRenderer.exists())
        assertTrue(liveState.exists())
        assertTrue(modelPartMixin.exists())
        assertTrue(renderer.text.contains('Minecraft12111LivePreviewRenderState'))
        assertTrue(scope.text.contains('minecraft.player != player'))
        assertTrue(liveRenderer.text.contains('EditorPreviewLayerGuard.open('))
        assertTrue(liveRenderer.text.contains('state.previewContext().open(minecraft)'))
        int extract = liveRenderer.text.indexOf('.extractEntity(')
        int submit = liveRenderer.text.indexOf('.submit(')
        assertTrue(extract >= 0 && submit > extract)
        assertTrue(liveRenderer.text.contains('renderAllFeatures()'))
        assertTrue(layerMixin.text.contains('Minecraft12111PreviewModelAnchors.open('))
        assertTrue(layerMixin.text.contains('state instanceof AvatarRenderState'))
        assertTrue(modelPartMixin.text.contains('cubes.isEmpty()'))
        assertFalse(mixins.text.contains('"GuiEntityRendererMixin"'))
        assertFalse(mixins.text.contains('"AvatarRenderStateMixin"'))
        assertTrue(mixins.text.contains('"ModelPartPreviewMixin"'))

        String fabricRegistration = new File(
                repository,
                'loader/fabric/pip-1.21.11/src/main/java/com/naocraftlab/skins/compat/mc12111/mixin/GuiRendererMixin.java').text
        String neoForgeRegistration = new File(
                repository,
                'loader/neoforge/pip-1.21.11/src/main/java/com/naocraftlab/skins/loader/neoforge/NeoForgePipRendererRegistration.java').text
        assertEquals(1, fabricRegistration.count('new Minecraft12111BakedPreviewRenderer(bufferSource)'))
        assertEquals(1, fabricRegistration.count('new Minecraft12111LivePreviewRenderer(bufferSource)'))
        assertEquals(1, neoForgeRegistration.count('Minecraft12111BakedPreviewRenderer::new'))
        assertEquals(1, neoForgeRegistration.count('Minecraft12111LivePreviewRenderer::new'))

        StringBuilder productionSources = new StringBuilder()
        [previewRoot,
         new File(repository, 'loader/fabric/pip-1.21.11/src/main/java'),
         new File(repository, 'loader/neoforge/pip-1.21.11/src/main/java')].each { File root ->
            root.eachFileRecurse(groovy.io.FileType.FILES) { File source ->
                if (source.name.endsWith('.java')) productionSources.append(source.text).append('\n')
            }
        }
        ['java.lang.reflect', 'com.unascribed.ears', 'traben.entity_model_features',
         'traben.entity_texture_features', 'NclPreviewState'].each { String forbidden ->
            assertFalse(productionSources.toString().contains(forbidden), forbidden)
        }
    }

    @Test
    void submission12111UsesVanillaElytraGeometryAndNeutralPoseInBothPreviewPaths() {
        File previewRoot = new File(
                repository,
                'compat/capabilities/preview/avatar-pip-1.21.11/src/main/java/com/naocraftlab/skins/compat/mc12111')
        String liveRenderer = new File(previewRoot, 'Minecraft12111PreviewRenderer.java').text
        String simpleRenderer = new File(previewRoot, 'Minecraft12111SimplePreviewRenderer.java').text
        String bakedRenderer = new File(previewRoot, 'Minecraft12111BakedPreviewRenderer.java').text
        String bakedState = new File(previewRoot, 'Minecraft12111BakedPreviewRenderState.java').text

        assertTrue(simpleRenderer.contains('ElytraModel.createLayer().bakeRoot()'))
        assertFalse(simpleRenderer.contains('mesh.getRoot().addOrReplaceChild('))
        assertTrue(bakedState.contains('Model<?> attachmentModel'))
        assertTrue(bakedRenderer.contains('Model<?> attachment = state.attachmentModel()'))
        assertTrue(liveRenderer.contains(
                'state.elytraRotX = CenteredPipPreviewTransform.ELYTRA_ROT_X'))
        assertTrue(liveRenderer.contains(
                'state.elytraRotY = CenteredPipPreviewTransform.ELYTRA_ROT_Y'))
        assertTrue(liveRenderer.contains(
                'state.elytraRotZ = CenteredPipPreviewTransform.ELYTRA_ROT_Z'))
    }

    @Test
    void cleanTargetBuildsCannotReuseAStaleNestedSourceGraph() {
        Path sourceRoot = Files.createTempDirectory('nclskins-source-graph-')
        try {
            Files.createDirectories(sourceRoot.resolve('compat/capabilities/gui/example/src/main/java'))
            String empty = TargetBuildTask.sourceGraphFingerprint(sourceRoot.toFile())
            Files.writeString(
                    sourceRoot.resolve('compat/capabilities/gui/example/src/main/java/Example.java'),
                    'class Example {}')
            String added = TargetBuildTask.sourceGraphFingerprint(sourceRoot.toFile())
            assertNotEquals(empty, added)
            Files.delete(sourceRoot.resolve('compat/capabilities/gui/example/src/main/java/Example.java'))
            assertEquals(empty, TargetBuildTask.sourceGraphFingerprint(sourceRoot.toFile()))
        } finally {
            sourceRoot.toFile().deleteDir()
        }

        String source = new File(
                repository,
                'gradle/build-logic/src/main/groovy/com/naocraftlab/skins/buildlogic/TargetBuildTask.groovy').text
        assertTrue(source.contains('"-PnclskinsSourceGraph=${sourceGraphFingerprint(root)}".toString()'))
        assertFalse(source.contains("'--no-configuration-cache'"))
    }

    @Test
    void parallelTargetBuildsIsolateCompositeBuildOutputs() {
        String targetBuild = new File(
                repository,
                'gradle/build-logic/src/main/groovy/com/naocraftlab/skins/buildlogic/TargetBuildTask.groovy').text
        String buildLogic = new File(repository, 'gradle/build-logic/build.gradle').text

        assertTrue(targetBuild.contains(
                '"-PnclskinsBuildLogicWorkspace=${target.id}".toString()'))
        assertTrue(buildLogic.contains(
                "providers.gradleProperty('nclskinsBuildLogicWorkspace').orNull"))
        assertTrue(buildLogic.contains(
                'layout.buildDirectory.set(layout.projectDirectory.dir("build/workspaces/${buildLogicWorkspace}"))'))
        assertTrue(buildLogic.contains('/[a-z0-9][a-z0-9.-]*/'))
    }

    @Test
    void semanticVerifierRejectsVanillaOnly12111BakedPitch() {
        Path sourceRoot = Files.createTempDirectory('nclskins-12111-preview-pitch-')
        try {
            Files.writeString(
                    sourceRoot.resolve('Preview.java'),
                    '''
                    class PreviewPlayer extends RemotePlayer {
                        void render() {
                            LocalPlayer player = minecraft.player;
                            EditorPreviewSession session;
                            ExactLocalPlayerScope scope;
                            EditorPreviewClock clock;
                            NativePlayerSkinLifecycle lifecycle;
                            submitEntityRenderState();
                            submitSkinRenderState();
                            NclPreviewState state;
                            LivingEntityRendererPreviewMixin layers;
                            EntityRenderState state2;
                            PlayerSkin.insecure();
                            CenteredPlayerPreviewGeometry.centeredEntityTranslation();
                            Minecraft12111SimplePreviewRenderer baked;
                            ItemStack empty = ItemStack.EMPTY;
                        }
                    }
                    ''')
            List<String> errors = []

            SemanticVerifier.verifyPreviewBundle(
                    'avatar-pip-1.21.11-fabric', [sourceRoot] as Set, errors)

            assertTrue(errors.any {
                it.contains('1.21.11 submission preview lacks required marker')
                        && it.contains('Minecraft12111BakedPreviewRenderState')
            }, errors.toString())
        } finally {
            sourceRoot.toFile().deleteDir()
        }
    }

    @Test
    void semanticVerifierRejectsBakedPitchConventionIn262LivePreview() {
        Path sourceRoot = Files.createTempDirectory('nclskins-preview-pitch-')
        try {
            Files.writeString(
                    sourceRoot.resolve('Preview.java'),
                    '''
                    class PreviewPlayer extends RemotePlayer {
                        void render() {
                            LocalPlayer player = minecraft.player;
                            EditorPreviewSession session;
                            ExactLocalPlayerScope scope;
                            EditorPreviewClock clock;
                            NativePlayerSkinLifecycle lifecycle;
                            Minecraft262PreviewContext context;
                            NclBakedPlayerRenderState state;
                            NclBakedPlayerSubmission submission;
                            GuiGraphicsExtractorPreviewMixin extractor;
                            GuiRendererMixin renderer;
                            @ModifyVariable Object registration;
                            List.copyOf();
                            ScreenOwnedRenderTarget target;
                            NclBakedPlayerTarget playerTarget;
                            standaloneEquipment();
                            PlayerCapeModel cape;
                            ElytraModel elytra;
                            float x = ELYTRA_ROT_X + ELYTRA_ROT_Z;
                            Minecraft262BakedPlayerPose.applyPitch(pose, state.pitchDegrees());
                            CenteredPlayerPreviewGeometry.centeredEntityTranslation();
                            CenteredPipPreviewTransform.modelPitchRadians(pitchDegrees);
                        }

                        float livePitch(float pitchDegrees) {
                            return CenteredPipPreviewTransform.modelPitchRadians(pitchDegrees);
                        }
                    }
                    ''')
            List<String> errors = []

            SemanticVerifier.verifyPreviewBundle(
                    'avatar-pip-26.2-fabric', [sourceRoot] as Set, errors)

            assertTrue(errors.any {
                it.contains('lacks live/baked pitch split marker') &&
                        it.contains('return CenteredPipPreviewTransform.pitchRadians(pitchDegrees)')
            }, errors.toString())
        } finally {
            sourceRoot.toFile().deleteDir()
        }
    }

    @Test
    void classfileVerifierRejectsWrongTargetMajor() {
        Path jar = Files.createTempFile('nclskins-artifact-', '.jar')
        try {
            byte[] classfile = [(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0, 0, 0, 61] as byte[]
            new ZipOutputStream(Files.newOutputStream(jar)).withCloseable { output ->
                output.putNextEntry(new ZipEntry('Example.class'))
                output.write(classfile)
                output.closeEntry()
            }
            ZipFile archive = new ZipFile(jar.toFile())
            archive.withCloseable {
                Map target = [id: 'fixture', java: [classfileMajor: 61]]
                List<String> errors = []
                ArtifactVerifier.verifyClassfiles(it, target, ['Example.class'], errors)
                assertEquals([], errors)
                target.java.classfileMajor = 65
                ArtifactVerifier.verifyClassfiles(it, target, ['Example.class'], errors)
                assertTrue(errors.any { message -> message.contains('expected classfile major 65') })
            }
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    @Test
    void artifactContentClosureRejectsEntriesOutsideSelectedBundlesAndBuildOutputs() {
        Path fixture = Files.createTempDirectory('nclskins-artifact-closure-')
        try {
            Map fixtureCatalog = [
                    baseBundles              : ['selected'],
                    sourceBundles            : [
                            selected: [
                                    java     : ['selected/java'],
                                    resources: ['selected/resources'],
                                    side     : 'common',
                                    requires : []
                            ]
                    ],
                    capabilityImplementations: [:],
                    profiles                 : [epochs: [fixture: [
                            accessBundles       : [fixture: 'selected'],
                            clientProviderBundle: 'selected'
                    ]]]
            ]
            Map target = [
                    id          : 'fixture',
                    path        : 'target',
                    loader      : [id: 'fixture'],
                    epochProfile: 'fixture',
                    capabilities: [:],
                    metadata    : [files: [], accessWidener: null],
                    artifact    : [remapJar: false]
            ]
            Map<String, byte[]> files = [
                    'selected/java/example/Selected.java'                  : 'class Selected {}'.bytes,
                    'selected/resources/selected.txt'                      : 'selected'.bytes,
                    'target/build/classes/java/main/example/Selected.class': [0] as byte[],
                    'target/build/resources/main/selected.txt'             : 'selected'.bytes
            ]
            files.each { String relative, byte[] bytes ->
                Path destination = fixture.resolve(relative)
                Files.createDirectories(destination.parent)
                Files.write(destination, bytes)
            }

            Closure<List<String>> verifyEntries = { List<String> entries, boolean corruptResource ->
                Path jar = Files.createTempFile(fixture, 'artifact-', '.jar')
                new ZipOutputStream(Files.newOutputStream(jar)).withCloseable { output ->
                    entries.each { String entry ->
                        output.putNextEntry(new ZipEntry(entry))
                        byte[] content = entry == 'selected.txt'
                                ? (corruptResource ? 'corrupt'.bytes : 'selected'.bytes)
                                : [0] as byte[]
                        output.write(content)
                        output.closeEntry()
                    }
                }
                List<String> errors = []
                new ZipFile(jar.toFile()).withCloseable { archive ->
                    List<String> names = archive.entries().collect { it.name }
                    ArtifactVerifier.verifyContentClosure(
                            fixture.toFile(), archive, fixtureCatalog, target, names, errors)
                }
                errors
            }

            List<String> expected = [
                    'example/Selected.class',
                    'selected.txt',
                    'META-INF/MANIFEST.MF',
                    'META-INF/LICENSE',
                    'META-INF/NOTICE'
            ]
            assertEquals([], verifyEntries(expected, false))

            List<String> errors = verifyEntries(expected + [
                    'example/Foreign.class',
                    'foreign.txt'
            ], false)
            assertTrue(errors.any { it.contains('unexpected classfile entries') && it.contains('Foreign.class') })
            assertTrue(errors.any { it.contains('not owned by selected source bundles') && it.contains('Foreign.class') })
            assertTrue(errors.any { it.contains('unexpected resource entries') && it.contains('foreign.txt') })
            assertTrue(errors.any { it.contains('resources are not owned by selected source bundles') && it.contains('foreign.txt') })
            assertTrue(verifyEntries(expected, true).any {
                it.contains('resource differs from processed target output: selected.txt')
            })
        } finally {
            fixture.toFile().deleteDir()
        }
    }

    @Test
    void nestedEventPackIconIsCanonicalAndExcludedFromSkinInventory() {
        File canonical = new File(
                repository, 'compat/resources/canonical/src/main/resources/icon.png')
        File collections = new File(
                repository,
                'compat/resources/mojang-collections/src/main/resources/resourcepacks/mojang_collections')
        File packIcon = new File(collections, 'pack.png')
        assertArrayEquals(canonical.bytes, packIcon.bytes)

        List<Path> pngs = []
        Files.walk(collections.toPath()).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith('.png') }
                    .forEach { pngs.add(it) }
        }
        assertEquals(36, pngs.size())
        assertEquals(35, pngs.count { it.toString().contains(File.separator + 'assets' + File.separator) })
        assertEquals(['pack.png'], pngs.findAll {
            !it.toString().contains(File.separator + 'assets' + File.separator)
        }.collect { collections.toPath().relativize(it).toString() })

        assertEquals([], nestedPackIconErrors(repository, catalog, canonical.bytes))
        byte[] corrupted = canonical.bytes.clone()
        corrupted[corrupted.length - 1] = (byte) (corrupted[corrupted.length - 1] ^ 1)
        assertTrue(nestedPackIconErrors(repository, catalog, corrupted).any {
            it.contains('resource hash differs')
        })
        assertTrue(nestedPackIconErrors(repository, catalog, null).any {
            it.contains('missing required resource')
        })
    }

    @Test
    void commentScannerPreservesCommentLikeLiterals() {
        assertFalse(PublicationTreeVerifier.containsComment('String value = "https://example.invalid/a//b";\n', false))
        assertFalse(PublicationTreeVerifier.containsComment("def value = /a\\/\\/b/\n", true))
        assertTrue(PublicationTreeVerifier.containsComment('int value = 1; // private note\n', false))
        assertTrue(PublicationTreeVerifier.containsComment('int value = 1; /* private note */\n', false))
    }

    private Set<String> selected(String path) {
        CatalogTools.classifyAffected(repository, catalog, [path]).findAll { String id, Set reasons -> !reasons.isEmpty() }.keySet() as Set
    }

    private static Map cloneMap(Map value) {
        new JsonSlurper().parseText(JsonOutput.toJson(value)) as Map
    }

    private static int occurrences(String value, String needle) {
        value.split(Pattern.quote(needle), -1).length - 1
    }

    private static List<String> nestedPackIconErrors(
            File repository, Map catalog, byte[] bytes) {
        Path jar = Files.createTempFile('nclskins-pack-icon-', '.jar')
        try {
            new ZipOutputStream(Files.newOutputStream(jar)).withCloseable { output ->
                if (bytes != null) {
                    output.putNextEntry(new ZipEntry(ArtifactVerifier.COLLECTIONS + 'pack.png'))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
            List<String> errors = []
            new ZipFile(jar.toFile()).withCloseable { archive ->
                ArtifactVerifier.verifyNestedPackIcon(
                        repository, archive, catalog, [id: 'fixture'], errors)
            }
            return errors
        } finally {
            Files.deleteIfExists(jar)
        }
    }
}

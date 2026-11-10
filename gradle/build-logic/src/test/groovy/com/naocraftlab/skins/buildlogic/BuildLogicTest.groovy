package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test

import javax.imageio.ImageIO
import java.nio.charset.StandardCharsets
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
    private final Map modMenuAbi = CatalogTools.loadJson(new File(repository, 'gradle/modmenu-abi.json'))
@Test
    void cpmSubprocessesUseDistinctEmptyHeadlessTemporaryDirectories() {
        File work = Files.createTempDirectory('ncl-cpm-registration').toFile()
        try {
            File source = new File(work, 'TmpProbe.java')
            source.text = '''package com.naocraftlab.skins.buildlogic.probe;
import java.nio.file.*;
public final class TmpProbe {
    public static void main(String[] args) throws Exception {
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        if (!Boolean.getBoolean("java.awt.headless")) throw new AssertionError("CPM probe must be headless");
        if (!temp.startsWith(Path.of(args[0]))) throw new AssertionError("CPM probe must own its temporary directory");
        try (var files = Files.list(temp)) {
            if (files.findAny().isPresent()) throw new AssertionError("CPM probe temporary directory must start empty");
        }
        Files.writeString(temp.resolve("owned.tmp"), "owned");
    }
}'''
            File classes = new File(work, 'classes'); classes.mkdirs()
            assertEquals(0, javax.tools.ToolProvider.systemJavaCompiler.run(null, null, null,
                    '--release', '17', '-d', classes.path, source.path))
            def project = org.gradle.testfixtures.ProjectBuilder.builder().withProjectDir(work).build()
            def task = project.tasks.create('cpmFixture', VerifyCpmIntegrationTask)
            def method = VerifyCpmIntegrationTask.getDeclaredMethod('runProbe', File, String, List, String, List, List)
            method.accessible = true
            for (String label : ['first', 'second'])
                method.invoke(task, work, label, [classes], 'TmpProbe', [work.absolutePath], [])
            assertNotEquals(new File(work, 'tmp-first').canonicalPath, new File(work, 'tmp-second').canonicalPath)
            assertTrue(new File(work, 'tmp-first/owned.tmp').isFile())
            assertTrue(new File(work, 'tmp-second/owned.tmp').isFile())
        } finally { work.deleteDir() }
    }















    @Test
    void externalDevelopmentArtifactIntegrityFailsClosed() {
        Path archive = Files.createTempFile('nclskins-sqlite-integrity-', '.jar')
        new ZipOutputStream(Files.newOutputStream(archive)).withCloseable { ZipOutputStream zip ->
            zip.putNextEntry(new ZipEntry('fabric.mod.json'))
            zip.write('{"schemaVersion":1,"id":"sqlite_jdbc","version":"test"}'
                    .getBytes(StandardCharsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(new ZipEntry('org/sqlite/JDBC.class'))
            zip.write(new byte[]{1, 2, 3})
            zip.closeEntry()
            zip.putNextEntry(new ZipEntry('META-INF/services/java.sql.Driver'))
            zip.write('org.sqlite.JDBC\n'.getBytes(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        File file = archive.toFile()
        Map expected = [
                size: file.length(),
                sha1: ReleaseBundle.sha1(file),
                sha512: ReleaseBundle.sha512(file),
                fabricModId: 'sqlite_jdbc',
                version: 'test'
        ]
        ExternalArtifactIntegrity.verify(file, expected)

        Map wrongSize = new LinkedHashMap(expected)
        wrongSize.size = file.length() + 1
        assertThrows(GradleException) {
            ExternalArtifactIntegrity.verify(file, wrongSize)
        }

        Map wrongChecksum = new LinkedHashMap(expected)
        wrongChecksum.sha512 = '0' * 128
        assertThrows(GradleException) {
            ExternalArtifactIntegrity.verify(file, wrongChecksum)
        }
    }





    @Test
    void reuseProbePrefersSameEpochAndRejectsUndeclaredImplementations() {
        Map target = CatalogTools.selectTarget(catalog, 'fabric-1.20.1')
        List<Map> candidates = CapabilityReuse.candidates(catalog, target, 'gui')

        assertEquals('immediate-resource-location-player-info', candidates.first().implementation)
        assertTrue(candidates.first().sameEpoch)
        assertTrue(candidates.first().reused)
        assertEquals(
                'immediate-resource-location-skin-lookup',
                CatalogTools.withCapabilityProbe(
                        catalog, target,
                        'gui=immediate-resource-location-skin-lookup').capabilities.gui)
        assertThrows(IllegalArgumentException) {
            CatalogTools.withCapabilityProbe(catalog, target, 'gui=missing-adapter')
        }
        assertThrows(IllegalArgumentException) {
            CatalogTools.withCapabilityProbe(
                    catalog, target,
                    'textures=immediate-resource-location-player-info')
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
    void reuseNegativeFixturesReportHiddenDependencyWrongSuiteAbiAndNewAdapter() {
        Map target = CatalogTools.selectTarget(catalog, 'fabric-1.20.1')
        Map coverage = CatalogTools.loadJson(
                new File(repository, 'gradle/capability-semantic-coverage.json'))
        Map candidate = CapabilityReuse.candidates(catalog, target, 'gui').first()

        Map hiddenDependency = cloneMap(catalog)
        hiddenDependency.sourceBundles['immediate-resource-location-player-info'].requires = ['missing-hidden-bundle']
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
        wrongSuite.implementations['immediate-resource-location-player-info'].capabilityKey = 'textures'
        Map wrong = CapabilityReuse.inspect(
                repository, catalog, abi, wrongSuite, target, 'gui', candidate)
        assertEquals('REJECTED', wrong.staticStatus)
        assertTrue(wrong.failures.contains('missing executable semantic contract'))

        assertFalse(CapabilityReuse.matchesDeclaredAbi(
                abi, 'immediate-resource-location-player-info', ['example.Wrong': '0' * 64]))
        assertThrows(IllegalStateException) {
            CapabilityReuse.requireReuseFirstSelection(
                    'fixture-target', 'gui', 'new-adapter', 'immediate-resource-location-player-info')
        }
    }

    @Test
    void zeroAbiDeclarationHashIsRejected() {
        assertFalse(CatalogTools.validAbiDeclarationHash('0' * 64))
        assertFalse(CatalogTools.validAbiDeclarationHash('abc'))
        assertTrue(CatalogTools.validAbiDeclarationHash(
                abi.implementations['immediate-resource-location-player-info'].baselineSha256))
    }







    @Test
    void everyCatalogLoaderUsesARegisteredBackend() {
        assertEquals(['fabric', 'forge', 'neoforge'] as Set, LoaderBackend.ids())
        catalog.targets.each { Map target ->
            LoaderBackend backend = LoaderBackend.require(target.loader.id.toString())
            String expectedPredicate = target.minecraft.minimumRuntimeVersion
                    ? ">=${target.minecraft.minimumRuntimeVersion}".toString()
                    : backend.minecraftPredicate(target.minecraft.version.toString())
            assertEquals(target.minecraft.predicate, expectedPredicate)
            assertEquals(target.metadata.keySet() as Set, backend.metadataKeys())
            assertFalse(backend.metadata(catalog, target, '1.0.0').isEmpty())
        }
        assertThrows(IllegalArgumentException) { LoaderBackend.require('unknown') }
    }



    @Test
    void configurationLinksSelectOneNativeApi() {
        catalog.targets.each { Map target ->
            Map resolved = CatalogTools.resolveTargetSources(repository, catalog, target)
            List roots = resolved.java.findAll { it.startsWith('compat/capabilities/configuration-link/') }
            String api = target.capabilities.gui == 'identifier-extraction-menu-tab-input-constants'
                    ? 'uri-blaze3d'
                    : target.capabilities.gui.toString().startsWith('immediate-')
                            ? 'string-root-util' : 'string-package-util'
            assertEquals(["compat/capabilities/configuration-link/${api}/src/main/java".toString()],
                    roots, target.id.toString())
        }
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
        assertEquals(['fabric-1.20.1', 'forge-1.20.1'] as Set, selected('compat/capabilities/gui/immediate-resource-location-player-info/src/main/java/Example.java'))
        assertEquals(catalog.targets.collect { it.id } as Set, selected('core/src/main/java/com/naocraftlab/skins/core/Example.java'))
        assertEquals(catalog.targets.collect { it.id } as Set, selected('client-runtime/build.gradle'))
        assertEquals(catalog.targets.collect { it.id } as Set, selected('gradle/version.properties'))
    }

    @Test
    void authoredResourceChangesUseCurrentAndHistoricalProducerOwnership() {
        String root = catalog.resourceProducers.canonicalBlockbench
        Set targets = catalog.targets*.id as Set
        for (String path : ["${root}/icon.bbmodel", "${root}/assets/nclskins/textures/gui/icons/action/add_cape.bbmodel",
                            "${root}/assets/nclskins/textures/gui/icons/new/nested.bbmodel",
                            "${root}/removed.bbmodel"]) {
            assertEquals(targets, selected(path))
        }
        Map previous = new JsonSlurper().parseText(JsonOutput.toJson(catalog)) as Map
        previous.resourceProducers.canonicalBlockbench = 'old/authored'
        assertEquals(targets, CatalogTools.classifyAffected(repository, catalog,
                ['old/authored/removed.bbmodel'], [previous]).keySet() as Set)
        assertThrows(IllegalArgumentException) {
            CatalogTools.classifyAffected(repository, catalog, ["${root}-unrelated/icon.bbmodel"])
        }
    }


    @Test
    void unknownProductionPathIsRejected() {
        assertThrows(IllegalArgumentException) { CatalogTools.classifyAffected(repository, catalog, ['new-runtime/Main.java']) }
    }

    @Test
    void removedCapabilitySourceConservativelyAffectsEveryTarget() {
        Map<String, Set<String>> affected = CatalogTools.classifyAffected(
                repository,
                catalog,
                ['compat/capabilities/removed-transport/src/main/java/Removed.java'])

        assertEquals(catalog.targets*.id as Set, affected.keySet() as Set)
        assertTrue(affected.values().every { it == ['removed-capability-source'] as Set })
    }



    @Test
    void metadataMatchesEveryLoaderContract() {
        String version = CatalogTools.loadVersion(repository)
        String requiredServerPluginVersion = '1.4.0'
        catalog.targets.each { Map target ->
            Map<String, String> resources = MetadataRenderer.render(
                    catalog, target, version, requiredServerPluginVersion)
            assertEquals(((target.metadata.files as List) +
                    'nclskins-server-compatibility.json') as Set,
                    resources.keySet() as Set, target.id.toString())
            assertTrue(resources.values().any { it.contains('GPL-3.0-only') }, target.id.toString())
            Map compatibility = new JsonSlurper().parseText(
                    resources['nclskins-server-compatibility.json']) as Map
            assertEquals(requiredServerPluginVersion, compatibility.requiredServerPluginVersion)
            assertEquals(['appearance-refresh-v1', 'proxy-refresh-v1'],
                    compatibility.protocolIds)
            assertEquals('official-v1', compatibility.matrixId)
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
                        fancymenu: '*',
                        cpm: '*',
                        sqlite_jdbc              : catalog.optionalDependencies.sqlite_jdbc.predicates.fabric,
                        yet_another_config_lib_v3: CatalogTools.optionalDependencyPredicate(catalog, target, 'yet_another_config_lib_v3')
                ], metadata.suggests)
                assertEquals(true, metadata.custom.modmenu.update_checker)
                assertEquals([
                        'modmenu.modrinth'              : 'https://modrinth.com/mod/nclskins',
                        'modmenu.curseforge'            : 'https://www.curseforge.com/minecraft/mc-mods/nclskins',
                        'nclskins.modmenu.youtube'      : catalog.mod.links.youtube,
                        'nclskins.modmenu.telegram_bot' : catalog.mod.links.telegramBot,
                        'nclskins.modmenu.x'            : catalog.mod.links.x
                ], metadata.custom.modmenu.links)
            } else if (target.loader.id == 'forge') {
                String metadata = resources['META-INF/mods.toml']
                assertEquals('legacy-logo', target.metadata.modListBranding)
                assertEquals(2, metadata.readLines().count { it == 'issueTrackerURL="https://github.com/NaoCraftLab/nclskins/issues"' })
                assertTrue(metadata.contains('logoFile="icon.png"'))
                assertTrue(metadata.contains('logoBlur=false'))
                assertTrue(metadata.contains('[modproperties.nclskins]\ncatalogueImageIcon="icon.png"'))
                assertTrue(metadata.contains('displayTest="IGNORE_SERVER_VERSION"'))
                assertTrue(metadata.contains('showAsResourcePack=false'))
                assertTrue(metadata.contains('features={java_version="[17,)"}'))
                assertTrue(metadata.contains(
                        'updateJSONURL="https://naocraftlab.github.io/nclskins/' +
                        "updates/v1/native/${target.id}.json\""))
                assertTrue(metadata.contains('modId="sqlite_jdbc"'))
                assertTrue(metadata.contains('modId="yet_another_config_lib_v3"'))
                assertTrue(metadata.contains('mandatory=false'))
                assertTrue(metadata.contains('side="CLIENT"'))
                assertFalse(metadata.contains('[[mixins]]'))
            } else {
                String metadata = resources['META-INF/neoforge.mods.toml']
                assertEquals(2, metadata.readLines().count { it == 'issueTrackerURL="https://github.com/NaoCraftLab/nclskins/issues"' })
                assertTrue(metadata.contains('[modproperties.nclskins]\ncatalogueImageIcon="icon.png"'))
                if (target.metadata.modListBranding == 'icon-only') {
                    assertTrue(target.id in ['neoforge-26.2', 'neoforge-26.3'])
                    assertFalse(metadata.contains('logoFile='))
                    assertFalse(metadata.contains('logoBlur='))
                    assertTrue(metadata.contains('iconFile="icon.png"'))
                    assertTrue(metadata.contains('iconBlur=false'))
                    assertTrue(metadata.contains('bannerFile=false'))
                } else {
                    assertEquals('legacy-logo', target.metadata.modListBranding)
                    assertTrue(metadata.contains('logoFile="icon.png"'))
                    assertTrue(metadata.contains('logoBlur=false'))
                    assertFalse(metadata.contains('iconFile='))
                    assertFalse(metadata.contains('bannerFile='))
                }
                assertTrue(metadata.contains("javaVersion=\"[${target.java.release},)\""))
                assertTrue(metadata.contains('showAsResourcePack=false'))
                assertTrue(metadata.contains('showAsDataPack=false'))
                assertTrue(metadata.contains(
                        'updateJSONURL="https://naocraftlab.github.io/nclskins/' +
                        "updates/v1/native/${target.id}.json\""))
                assertTrue(metadata.contains('modId="sqlite_jdbc"'))
                assertTrue(metadata.contains('modId="yet_another_config_lib_v3"'))
                assertTrue(metadata.contains('type="optional"'))
                assertTrue(metadata.contains('side="CLIENT"'))
                assertTrue(metadata.contains("file=\"${target.metadata.accessTransformer}\""))
                assertEquals((target.metadata.serverMixins ?: []) + target.metadata.mixins, metadata.readLines().findAll { it.startsWith('config=') }.collect { it.substring('config="'.length(), it.length() - 1) })
            }
        }
    }



    @Test
    void ideaRunsUseOnlyRootGradleTasks() {
        assertEquals(['Client', 'LicensedClient', 'Server'], IdeaRunConfigurations.RUN_KINDS)
        Set<String> taskNames = [] as Set
        IdeaRunConfigurations.orderedModRuntimes(catalog).each { Map runtime ->
            Map target = runtime.target as Map
            String minecraftVersion = runtime.minecraftVersion.toString()
            assertEquals([
                    "${minecraftVersion}:${target.loader.id}:runClient".toString(),
                    "${minecraftVersion}:${target.loader.id}:runClientLicensed".toString(),
                    "${minecraftVersion}:${target.loader.id}:runServer".toString()
            ], IdeaRunConfigurations.RUN_KINDS.collect { String runKind ->
                IdeaRunConfigurations.configurationName(target, minecraftVersion, runKind)
            })
            if (runtime.baseline) {
                assertEquals([
                        "${target.minecraft.version}:${target.loader.id}:runLicensedClient".toString(),
                        "${target.minecraft.version}:${target.loader.id}:runClient (licensed)".toString()
                ], IdeaRunConfigurations.previousConfigurationNames(target, 'LicensedClient'))
            }
            IdeaRunConfigurations.RUN_KINDS.each { String runKind ->
                String taskName = IdeaRunConfigurations.taskName(
                        target, minecraftVersion, runKind)
                assertTrue(taskNames.add(taskName))
                String rendered = IdeaRunConfigurations.render(
                        target, minecraftVersion, runKind)
                def configuration = new groovy.xml.XmlSlurper().parseText(rendered).configuration
                assertEquals('GradleRunConfiguration', configuration.@type.toString())
                assertEquals('$PROJECT_DIR$', configuration.ExternalSystemSettings.option.find { it.@name == 'externalProjectPath' }.@value.toString())
                assertEquals(taskName, configuration.ExternalSystemSettings.option.find { it.@name == 'taskNames' }.list.option.@value.toString())
                assertEquals('-PnclskinsDevLogging=true ' +
                        "-PnclskinsBuildLogicWorkspace=${IdeaRunConfigurations.buildLogicWorkspace(target, minecraftVersion, runKind)}",
                        configuration.ExternalSystemSettings.option
                                .find { it.@name == 'scriptParameters' }.@value.toString())
                assertEquals('', configuration.ExternalSystemSettings.option
                        .find { it.@name == 'vmOptions' }.@value.toString())
                assertEquals(IdeaRunConfigurations.displayFolder(minecraftVersion),
                        configuration.@folderName.toString())
                assertFalse(rendered.contains('python'))
                assertFalse(rendered.contains('do' + 'cs/'))
                assertFalse(rendered.contains('scr' + 'ipts/'))
            }
        }
        catalog.serverPluginTopologies.each { Map topology ->
            String taskName = ServerPluginRuntimeSupport.taskName(topology)
            assertTrue(taskNames.add(taskName), topology.id.toString())
            String rendered = IdeaRunConfigurations.renderServerPlugin(topology)
            def configuration = new groovy.xml.XmlSlurper().parseText(rendered).configuration
            assertEquals(ServerPluginRuntimeSupport.configurationName(topology),
                    configuration.@name.toString())
            assertEquals(IdeaRunConfigurations.displayFolder(topology.minecraft.toString()),
                    configuration.@folderName.toString())
            assertEquals(taskName, configuration.ExternalSystemSettings.option
                    .find { it.@name == 'taskNames' }.list.option.@value.toString())
            assertEquals('-PnclskinsDevLogging=true ' +
                    "-PnclskinsBuildLogicWorkspace=${taskName.toLowerCase(Locale.ROOT)}",
                    configuration.ExternalSystemSettings.option
                            .find { it.@name == 'scriptParameters' }.@value.toString())
            assertEquals('', configuration.ExternalSystemSettings.option
                    .find { it.@name == 'vmOptions' }.@value.toString())
            assertTrue(rendered.contains(IdeaRunConfigurations.GENERATED_MARKER))
        }
        assertEquals(IdeaRunConfigurations.orderedModRuntimes(catalog).size() *
                IdeaRunConfigurations.RUN_KINDS.size() +
                catalog.serverPluginTopologies.size(), taskNames.size())
        assertEquals(82, IdeaRunConfigurations.orderedConfigurationNames(catalog).size())
        assertTrue(IdeaRunConfigurations.orderedConfigurationNames(catalog)
                .contains('26.3:paper:runServer'))
        assertEquals('26.1', IdeaRunConfigurations.displayFolder('26.1'))
        assertEquals('26.1.1', IdeaRunConfigurations.displayFolder('26.1.1'))
        assertEquals('26.1.2', IdeaRunConfigurations.displayFolder('26.1.2'))
    }

    @Test
    void serverPluginManagedConfigsAreSecureAndExact() {
        assertEquals('nclskins-server-1.0.0-beta.2.jar',
                ServerPluginRunTask.legacyManagedPluginName('nclskins-plugin-1.0.0-beta.2.jar'))
        assertNull(ServerPluginRunTask.legacyManagedPluginName('BungeeGuard.jar'))
        assertTrue(ServerPluginRunTask.managedNclSkinsPlugin(
                'nclskins-plugin-1.0.0-beta.3.jar'))
        assertTrue(ServerPluginRunTask.managedNclSkinsPlugin(
                'nclskins-server-1.0.0-beta.2.jar'))
        assertFalse(ServerPluginRunTask.managedNclSkinsPlugin('BungeeGuard.jar'))
        assertTrue(ServerPluginRunTask.shouldReplaceManagedPlugins(
                'nclskins-plugin-1.0.0-beta.3.jar'))
        assertFalse(ServerPluginRunTask.shouldReplaceManagedPlugins('BungeeGuard.jar'))
        assertFalse(ServerPluginRunTask.shouldReplaceManagedPlugins('ProtocolLib.jar'))
        String legacy = ServerPluginRunTask.backendOverlay('spigot')
        String paper = ServerPluginRunTask.backendOverlay('paper')
        String velocityLogging = ServerPluginRunTask.velocityOverlay()
        [legacy, paper, velocityLogging].each { String overlay ->
            assertFalse(overlay.contains('<Root'))
            assertFalse(overlay.contains('<Appender '))
            assertFalse(overlay.contains('monitorInterval'))
            assertFalse(overlay.toLowerCase(Locale.ROOT).contains('lookup'))
            assertFalse(overlay.toLowerCase(Locale.ROOT).contains('script'))
        }
        assertTrue(legacy.contains('NclSkinsBukkitPlugin'))
        assertFalse(legacy.contains('TerminalConsole'))
        assertTrue(paper.contains('TerminalConsole'))
        assertTrue(paper.contains('LevelMatchFilter'))
        assertTrue(velocityLogging.contains('name="nclskins-plugin"'))
        Map standalone = catalog.serverPluginTopologies.find {
            it.id == '1.20.1-craftbukkit-standalone'
        } as Map
        Map<String, String> standaloneFiles = ServerPluginRuntimeSupport.managedFiles(
                standalone, 'velocity-secret', 'bungee-token')
        assertEquals([
                'server/server.properties',
                'server/plugins/NCLSkinsPlugin/nclskins-server.json5'
        ] as Set, standaloneFiles.keySet())
        assertTrue(standaloneFiles['server/server.properties'].contains('server-ip=127.0.0.1'))
        assertTrue(standaloneFiles['server/server.properties'].contains('online-mode=true'))
        assertTrue(standaloneFiles['server/plugins/NCLSkinsPlugin/nclskins-server.json5']
                .contains('"trustedProxyForwarding": false'))

        Map velocity = catalog.serverPluginTopologies.find {
            it.id == '1.20.1-paper-velocity'
        } as Map
        Map<String, String> velocityFiles = ServerPluginRuntimeSupport.managedFiles(
                velocity, 'velocity-secret', 'bungee-token')
        assertTrue(velocityFiles['proxy/velocity.toml'].contains('config-version = "2.8"'))
        assertTrue(velocityFiles['proxy/velocity.toml'].contains('player-info-forwarding-mode = "modern"'))
        assertTrue(velocityFiles['proxy/velocity.toml'].contains('forwarding-secret-file = "forwarding.secret"'))
        assertFalse(velocityFiles['proxy/velocity.toml'].contains('\nforwarding-secret ='))
        assertTrue(velocityFiles['proxy/velocity.toml'].endsWith('[forced-hosts]\n'))
        assertFalse(velocityFiles['proxy/velocity.toml'].contains('factions.example.com'))
        assertFalse(velocityFiles['proxy/velocity.toml'].contains('minigames.example.com'))
        assertTrue(velocityFiles['lobby/server.properties'].contains('online-mode=false'))
        assertTrue(velocityFiles['lobby/config/paper-global.yml'].contains("secret: 'velocity-secret'"))
        assertTrue(velocityFiles['lobby/plugins/NCLSkinsPlugin/nclskins-server.json5']
                .contains('"trustedProxyForwarding": true'))
        assertFalse(velocityFiles.values().any { it.contains('bungee-token') })

        Map bungee = catalog.serverPluginTopologies.find {
            it.id == '1.20.1-spigot-bungeecord'
        } as Map
        Map<String, String> bungeeFiles = ServerPluginRuntimeSupport.managedFiles(
                bungee, 'velocity-secret', 'bungee-token')
        assertTrue(bungeeFiles['proxy/config.yml'].contains('ip_forward: true'))
        assertTrue(bungeeFiles['proxy/config.yml'].contains('forced_hosts: {}'))
        assertFalse(bungeeFiles['proxy/config.yml'].contains('pvp.md-5.net'))
        assertTrue(bungeeFiles['lobby/spigot.yml'].contains('bungeecord: true'))
        assertTrue(bungeeFiles['lobby/bukkit.yml'].contains('connection-throttle: -1'))
        assertTrue(bungeeFiles['lobby/plugins/BungeeGuard/config.yml'].contains('bungee-token'))
        assertTrue(bungeeFiles['target/plugins/NCLSkinsPlugin/nclskins-server.json5']
                .contains('"trustedProxyForwarding": true'))
        assertFalse(bungeeFiles.values().any { it.contains('velocity-secret') })
        String bungeeToken = ServerPluginRuntimeSupport.randomBungeeToken(new RandomSecure())
        String velocitySecret = ServerPluginRuntimeSupport.randomVelocitySecret(new RandomSecure())
        assertTrue(ServerPluginRuntimeSupport.validBungeeToken(bungeeToken))
        assertTrue(ServerPluginRuntimeSupport.validVelocitySecret(velocitySecret))
        assertFalse(ServerPluginRuntimeSupport.validBungeeToken('placeholder'))
        assertFalse(ServerPluginRuntimeSupport.validVelocitySecret('placeholder'))
    }


    @Test
    void serverRuntimeEulaMarkerRequiresTheExplicitStructuredRecord() {
        Path directory = Files.createTempDirectory('nclskins-eula-marker-')
        try {
            Path marker = directory.resolve('eula.json')
            Files.writeString(marker, '{}')
            assertFalse(ServerPluginRuntimeSupport.validEulaMarker(marker))
            Files.writeString(marker, ServerPluginRuntimeSupport.eulaMarker(
                    '2026-08-12T00:00:00Z'))
            assertTrue(ServerPluginRuntimeSupport.validEulaMarker(marker))
        } finally {
            directory.toFile().deleteDir()
        }
    }





    private static final class RandomSecure extends java.security.SecureRandom {
        @Override
        void nextBytes(byte[] bytes) {
            Arrays.fill(bytes, (byte) 0x5a)
        }
    }


    @Test
    void targetRunCommandsUseCatalogWrappersPortsAndNativeTasks() {
        catalog.targets.each { Map target ->
            assertEquals(new File(repository,
                    "runs/${target.minecraft.version}/${target.loader.id}/client").canonicalFile,
                    RunLayout.modDirectory(repository, target, 'Client').canonicalFile)
            assertEquals(new File(repository,
                    "runs/${target.minecraft.version}/${target.loader.id}/client-licensed").canonicalFile,
                    RunLayout.modDirectory(repository, target, 'LicensedClient').canonicalFile)
            assertEquals(new File(repository,
                    "runs/${target.minecraft.version}/${target.loader.id}/server").canonicalFile,
                    RunLayout.modDirectory(repository, target, 'Server').canonicalFile)
            List<String> client = TargetRunTask.command(repository, catalog, target, 'Client', true)
            assertEquals('runClient', client[-2])
            assertEquals('--dry-run', client[-1])
            assertEquals(TargetRuntime.wrapper(repository, catalog, target).absolutePath, client.first())
            assertTrue(client.contains(
                    "-PnclskinsBuildLogicWorkspace=${IdeaRunConfigurations.buildLogicWorkspace(target, target.minecraft.version.toString(), 'Client')}".toString()))
            assertTrue(TargetRunTask.command(
                    repository, catalog, target, 'Client', true, true)
                    .contains('-PnclskinsDevLogging=true'))
            List<String> licensed = TargetRunTask.command(
                    repository, catalog, target, 'LicensedClient', true)
            assertEquals('runClientLicensed', licensed[-2])
            assertEquals('--dry-run', licensed[-1])
            assertEquals(TargetRuntime.wrapper(repository, catalog, target).absolutePath, licensed.first())
            List<String> server = TargetRunTask.command(repository, catalog, target, 'Server', true)
            assertTrue(server.contains('runServer'))
            int port = target.development.serverPort as int
            if (target.loader.id == 'neoforge') assertTrue(server.contains("-PnclskinsServerPort=${port}".toString()))
            else assertTrue(server.contains("--args=--port ${port}".toString()))
        }
    }

    @Test
    void serverPluginRunLayoutUsesVersionAndTargetDirectories() {
        catalog.serverPluginTopologies.each { Map topology ->
            String target = topology.mode == 'standalone'
                    ? topology.kernel.toString()
                    : "${topology.mode}-${topology.kernel}".toString()
            assertEquals(new File(repository,
                    "runs/${topology.minecraft}/${target}").canonicalFile,
                    RunLayout.topologyDirectory(repository, topology).canonicalFile)
        }
    }

    @Test
    void ideaWorkspaceOrderingChangesOnlyManagedRunItems() {
        Path workspace = Files.createTempFile('nclskins-workspace-', '.xml')
        workspace.toFile().text = '''<project version="4">
  <component name="RunManager" selected="Gradle.26.2:paper:runServerPlugin">
    <list>
      <item itemvalue="Gradle.user-before" />
      <item itemvalue="Gradle.26.2:paper:runServerPlugin" />
      <item itemvalue="Gradle.user-after" />
    </list>
  </component>
</project>
'''
        GenerateIdeaRunConfigurationsTask.updateWorkspace(workspace.toFile(),
                ['26.2:paper:runServerPlugin'] as Set,
                ['26.3:fabric:runClient', '26.2:paper:runServer'],
                ['26.2:paper:runServerPlugin': '26.2:paper:runServer'])
        String result = workspace.toFile().text
        assertTrue(result.contains('selected="Gradle.26.2:paper:runServer"'))
        assertTrue(result.indexOf('Gradle.user-before') < result.indexOf('Gradle.user-after'))
        assertTrue(result.indexOf('Gradle.user-after') <
                result.indexOf('Gradle.26.3:fabric:runClient'))
        assertTrue(result.indexOf('itemvalue="Gradle.26.3:fabric:runClient"') <
                result.indexOf('itemvalue="Gradle.26.2:paper:runServer"'))
        assertFalse(result.contains('itemvalue="Gradle.26.2:paper:runServerPlugin"'))
    }


    @Test
    void managedOperatorMergeIsExactAndPreservesUnmanagedEntries() {
        Path root = Files.createTempDirectory('nclskins-operators-')
        Path file = root.resolve('ops.json')
        file.toFile().text = JsonOutput.prettyPrint(JsonOutput.toJson([
                [uuid: '00f36371-8f11-4183-8151-bb74d0f72394', name: 'OldNaoName',
                 level: 1, bypassesPlayerLimit: true, legacy: 'remove-with-managed-uuid'],
                [uuid: '11111111-1111-4111-8111-111111111111', name: 'ExistingOperator',
                 level: 2, bypassesPlayerLimit: true, preserved: 'yes']
        ])) + '\n'
        List<Map> desired = CatalogTools.developmentOperators(catalog)
        RunDirectorySupport.ensureOperators(file, desired)
        List actual = new JsonSlurper().parse(file.toFile()) as List
        assertEquals(desired, actual.take(2))
        assertEquals([
                uuid: '11111111-1111-4111-8111-111111111111',
                name: 'ExistingOperator', level: 2,
                bypassesPlayerLimit: true, preserved: 'yes'
        ], actual[2])
        String once = file.toFile().text
        RunDirectorySupport.ensureOperators(file, desired)
        assertEquals(once, file.toFile().text)
    }

    @Test
    void clientServerListsAreExactForEveryRunVersion() {
        Map<String, List<String>> expectedNames = [
                '1.20.1': ['LAN', 'Fabric', 'Forge', 'CraftBukkit', 'Spigot', 'Paper',
                           'Purpur', 'Folia', 'Velocity Paper',
                           'BungeeCord Spigot', 'BungeeCord Paper'],
                '1.21.1': ['LAN', 'Fabric', 'NeoForge', 'Paper', 'Purpur',
                           'Velocity Paper', 'BungeeCord Paper'],
                '1.21.11': ['LAN', 'Fabric', 'NeoForge', 'Paper', 'Purpur', 'Folia',
                            'Velocity Paper', 'BungeeCord Paper'],
                '26.1': ['LAN', 'Fabric', 'NeoForge'],
                '26.1.1': ['LAN', 'Fabric', 'NeoForge', 'Paper',
                           'Velocity Paper', 'BungeeCord Paper'],
                '26.1.2': ['LAN', 'Fabric', 'NeoForge', 'Paper', 'Purpur', 'Folia',
                           'Velocity Paper', 'BungeeCord Paper'],
                '26.2': ['LAN', 'Fabric', 'NeoForge', 'Paper', 'Purpur', 'Folia',
                         'Velocity Paper', 'BungeeCord Paper'],
                 '26.3': ['LAN', 'Fabric', 'NeoForge', 'Paper', 'Purpur', 'Velocity Paper', 'BungeeCord Paper']
        ]
        expectedNames.each { String version, List<String> names ->
            List<Map<String, String>> entries = RunDirectorySupport.serverEntries(catalog, version)
            assertEquals(names, entries*.name)
            assertEquals('127.0.0.1:25565', entries.first().ip)
            assertTrue(entries.every { it.ip.startsWith('127.0.0.1:') })
            assertFalse(entries.any { it.ip.startsWith('localhost:') })
            assertEquals(entries.size(), entries*.name.toSet().size())
        }
        assertEquals('127.0.0.1:26000', RunDirectorySupport.serverEntries(catalog, '1.20.1')
                .find { it.name == 'CraftBukkit' }.ip)
        assertEquals('127.0.0.1:26017', RunDirectorySupport.serverEntries(catalog, '1.20.1')
                .find { it.name == 'Velocity Paper' }.ip)
        assertEquals('127.0.0.1:26153', RunDirectorySupport.serverEntries(catalog, '26.2')
                .find { it.name == 'BungeeCord Paper' }.ip)
        assertEquals('127.0.0.1:26010', RunDirectorySupport.serverEntries(catalog, '26.1.1')
                .find { it.name == 'Paper' }.ip)
        assertEquals('127.0.0.1:25578', RunDirectorySupport.serverEntries(catalog, '26.1.2')
                .find { it.name == 'Fabric' }.ip)
    }

    @Test
    void minecraftServerListMergePreservesUserEntriesAndUnknownFields() {
        Path file = Files.createTempFile('nclskins-servers-', '.dat')
        Files.delete(file)
        MinecraftServerList.merge(file, [
                [name: 'LAN', ip: 'localhost:25565'],
                [name: 'User Server', ip: 'example.test']
        ])
        MinecraftServerList.NbtTag root = MinecraftServerList.read(file)
        MinecraftServerList.NbtList servers =
                ((root.value as Map).servers.value as MinecraftServerList.NbtList)
        Map first = servers.values.first().value as Map
        first.icon = new MinecraftServerList.NbtTag(MinecraftServerList.STRING, 'preserved-icon')
        MinecraftServerList.writeAtomic(file, root)

        MinecraftServerList.merge(file, [
                [name: 'LAN', ip: 'localhost:25565'],
                [name: 'Paper', ip: 'localhost:26002']
        ], RunDirectorySupport.managedServerNames())
        assertEquals([
                [name: 'LAN', ip: 'localhost:25565'],
                [name: 'Paper', ip: 'localhost:26002'],
                [name: 'User Server', ip: 'example.test']
        ], MinecraftServerList.entries(file))
        root = MinecraftServerList.read(file)
        servers = ((root.value as Map).servers.value as MinecraftServerList.NbtList)
        first = servers.values.first().value as Map
        assertEquals('preserved-icon', first.icon.value)

        MinecraftServerList.merge(file, [
                [name: 'LAN', ip: 'localhost:25565']
        ], RunDirectorySupport.managedServerNames())
        assertEquals([
                [name: 'LAN', ip: 'localhost:25565'],
                [name: 'User Server', ip: 'example.test']
        ], MinecraftServerList.entries(file))
    }



    @Test
    void forgeRuntimeSqliteManifestPatchIsNarrowAndReproducible() {
        Path directory = Files.createTempDirectory('nclskins-forge-sqlite-manifest-')
        Path input = directory.resolve('sqlite.jar')
        new ZipOutputStream(Files.newOutputStream(input)).withCloseable { ZipOutputStream zip ->
            zip.putNextEntry(new ZipEntry(ForgeRuntimeMetadata.MANIFEST))
            zip.write(('Manifest-Version: 1.0\r\n' +
                    ForgeRuntimeMetadata.MULTI_RELEASE + '\r\n\r\n').getBytes('UTF-8'))
            zip.closeEntry()
            zip.putNextEntry(new ZipEntry('META-INF/mods.toml'))
            zip.write('modLoader="javafml"\n'.getBytes('UTF-8'))
            zip.closeEntry()
            zip.putNextEntry(new ZipEntry('org/sqlite/JDBC.class'))
            zip.write(new byte[]{7, 8, 9})
            zip.closeEntry()
        }
        Path first = directory.resolve('first.jar')
        Path second = directory.resolve('second.jar')
        ForgeRuntimeMetadata.patchSqlite(input, first)
        ForgeRuntimeMetadata.patchSqlite(input, second)
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second))
        new ZipFile(first.toFile()).withCloseable { ZipFile zip ->
            String manifest = zip.getInputStream(zip.getEntry(ForgeRuntimeMetadata.MANIFEST))
                    .withCloseable { new String(it.readAllBytes(), 'UTF-8') }
            assertFalse(manifest.contains(ForgeRuntimeMetadata.MULTI_RELEASE))
            assertTrue(manifest.contains('Manifest-Version: 1.0'))
            assertArrayEquals(new byte[]{7, 8, 9},
                    zip.getInputStream(zip.getEntry('org/sqlite/JDBC.class')).readAllBytes())
        }
    }



    @Test
    void managedModServersAreAlwaysAuthenticated() {
        Path root = Files.createTempDirectory('nclskins-mod-server-auth')
        Path properties = root.resolve('server.properties')
        Files.writeString(properties, '# preserved\nonline-mode=false\nview-distance=12\n')

        RunDirectorySupport.ensureServerOnlineMode(properties)

        assertEquals(
                '# preserved\nonline-mode=true\nview-distance=12\n',
                Files.readString(properties))
        RunDirectorySupport.ensureServerOnlineMode(properties)
        assertEquals(1, Files.readAllLines(properties).count { it == 'online-mode=true' })
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
    void optifinePatchReconstructionRejectsCorruptOrOutOfBoundsCommands() {
        byte[] base = '1234'.getBytes(StandardCharsets.US_ASCII)
        byte[] patch = [0xd1, 0xff, 0xd1, 0xff, 4,
                        3, 97, 98, 99,
                        249, 0, 1, 2] as byte[]
        assertEquals('abc23', new String(
                OptifineCapeAbiVerifier.reconstruct(base, patch), StandardCharsets.US_ASCII))
        byte[] corrupt = patch.clone()
        corrupt[11] = 4
        assertThrows(IllegalStateException.class) {
            OptifineCapeAbiVerifier.reconstruct(base, corrupt)
        }
        assertThrows(EOFException.class) {
            OptifineCapeAbiVerifier.reconstruct(base, patch[0..-2] as byte[])
        }
    }

































    private Set<String> selected(String path) {
        CatalogTools.classifyAffected(repository, catalog, [path]).findAll { String id, Set reasons -> !reasons.isEmpty() }.keySet() as Set
    }


    private static Map cloneMap(Map value) {
        new JsonSlurper().parseText(JsonOutput.toJson(value)) as Map
    }


}

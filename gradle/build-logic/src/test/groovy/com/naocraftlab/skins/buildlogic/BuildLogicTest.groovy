package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test

import javax.imageio.ImageIO
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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
    void currentCatalogIsValid() {
        assertEquals(22, catalog.schemaVersion)
        assertEquals('00000000-0000-0000-0000-000000000001', catalog.development.clientUuid)
        assertEquals([
                fabric  : 'nclskins-fabric',
                forge   : 'nclskins-forge-family',
                neoforge: 'nclskins-forge-family'
        ], catalog.development.licensedProfiles)
        assertEquals([
                loaders: [fabric: 'fabric-overlay', forge: 'forge-debug',
                          neoforge: 'neoforge-debug'],
                serverKernels: [craftbukkit: 'bukkit-legacy-overlay',
                                spigot: 'bukkit-legacy-overlay',
                                paper: 'paper-family-overlay',
                                purpur: 'paper-family-overlay',
                                folia: 'paper-family-overlay'],
                proxies: [velocity: 'velocity-overlay', bungeecord: 'bungeecord-fine']
        ], catalog.development.loggingProfiles)
        assertEquals([
                [uuid: '00f36371-8f11-4183-8151-bb74d0f72394', name: 'NaoCraftLab',
                 level: 4, bypassesPlayerLimit: false],
                [uuid: '60151dd6-1ea5-4531-89e8-d95981953a9b', name: 'NaoBurnLab',
                 level: 4, bypassesPlayerLimit: false]
        ], CatalogTools.developmentOperators(catalog))
        assertEquals('0.1.0.5', catalog.plugins.devLogin)
        assertEquals('0.5.3', catalog.plugins.mixinExtras)
        assertEquals([
                youtube    : 'https://www.youtube.com/@NaoCraftLab',
                telegramBot: 'https://t.me/naocraftlab_bot?start=c_yWYd4ACA',
                x          : 'https://x.com/naocraftlab'
        ], catalog.mod.links)
        assertEquals(LinkedHashMap, catalog.getClass())
        assertEquals(LinkedHashMap, catalog.gradleFamilies.getClass())
        assertEquals(LinkedHashMap, catalog.targets.first().getClass())
        assertEquals(
                catalog.targets.collect { "targets/${it.minecraft.version}/${it.loader.id}".toString() },
                catalog.targets.collect { it.path })
        assertEquals(11, catalog.targets.size())
        assertEquals(10, CatalogTools.releaseTargets(catalog).size())
        assertEquals('NCL Skins Plugin', catalog.serverPlugin.name)
        assertEquals('nclskins-plugin', catalog.serverPlugin.slug)
        assertEquals(25, catalog.serverPlugin.packaging.buildJdk)
        assertEquals(17, catalog.serverPlugin.javaRelease)
        assertNotEquals(catalog.mod.platforms.modrinth.projectId,
                catalog.serverPlugin.platforms.modrinth.projectId)
        assertNotEquals(catalog.mod.platforms.curseforge.projectId,
                catalog.serverPlugin.platforms.curseforge.projectId)
        assertEquals(20, catalog.serverPluginRuntimes.size())
        assertEquals(
                'https://fill-data.papermc.io/v1/objects/' +
                        '4540289f48c83e305fc2f2c495a84d1f4d0b7f360830251e169dd5a208740e70/' +
                        'velocity-4.0.0-6.jar',
                catalog.serverPluginRuntimes.find { it.id == 'velocity-4.0.0-6' }.url)
        assertEquals(
                'https://github.com/lucko/BungeeGuard/releases/download/v1.4.0/BungeeGuard.jar',
                catalog.serverPluginRuntimes.find { it.id == 'bungeeguard-1.4.0' }.url)
        assertEquals(30, catalog.serverPluginTopologies.size())
        assertEquals(56, catalog.serverPluginTopologies.collectMany {
            (it.ports as Map).values()
        }.toSet().size())
        Map characterization = CatalogTools.loadJson(
                new File(repository, 'gradle/server-plugin-characterization.json'))
        assertEquals(1, characterization.schemaVersion)
        assertEquals(catalog.targets.collect { it.id } as Set,
                (characterization.productionJarSha256 as Map).keySet() as Set)
        assertEquals(catalog.targets.collect { it.id } as Set,
                (characterization.sortedEntryNamesSha256 as Map).keySet() as Set)
        assertTrue((characterization.invariants as List)
                .contains('actor-receives-no-respawn-or-self-refresh'))
        Map experimental = catalog.targets.find { it.id == 'fabric-26.3' } as Map
        assertFalse(experimental.releaseEligible as boolean)
        assertEquals('26.3-snapshot-10', CatalogTools.minecraftCompileVersion(experimental))
        assertEquals('26.3-alpha.10', experimental.minecraft.runtimeVersion)
        assertEquals('26.3-alpha.10', experimental.minecraft.minimumRuntimeVersion)
        assertEquals('>=26.3-alpha.10', experimental.minecraft.predicate)
        assertEquals('0.158.2+26.3', experimental.loader.apiVersion)
        CatalogTools.validate(repository, catalog)
    }

    @Test
    void sqliteDevelopmentRuntimeIsExactAndExperimentalClientOnly() {
        Map experimental = CatalogTools.selectTarget(catalog, 'fabric-26.3')
        Map artifact = CatalogTools.optionalDevelopmentArtifact(
                catalog, experimental, 'sqlite_jdbc')
        assertEquals([
                coordinate              : 'maven.modrinth:bTTf2DEw:EEE7nXWy',
                projectId               : 'bTTf2DEw',
                versionId               : 'EEE7nXWy',
                version                 : '3.53.2.0+2026-06-06',
                fabricModId             : 'sqlite-jdbc',
                file                    : 'sqlite-jdbc-3.53.2.0+2026-06-06-all.jar',
                size                    : 11997526,
                sha1                    : '1a44c752f0c14c48d22f5c8d9ab6f893a7224d53',
                sha512                  : 'a25c390539aa7063d764b32efcfa1a03c3037e8b15e589374b7f523bec13712110adbd445f6d8909093149a0313dbbcba836569517c332397d2b16ea8917dd3d',
                declaredMinecraftMaximum: '26.1.2'
        ], artifact)
        assertNull(CatalogTools.optionalDevelopmentArtifact(
                catalog, CatalogTools.selectTarget(catalog, 'fabric-26.2'), 'sqlite_jdbc'))
        assertThrows(IllegalArgumentException) {
            CatalogTools.optionalDevelopmentArtifact(catalog, experimental, 'unknown')
        }
        assertThrows(IllegalArgumentException) {
            CatalogTools.optionalDevelopmentArtifact(catalog, [id: 'fabric-unknown'], 'sqlite_jdbc')
        }

        Map dynamic = cloneMap(catalog)
        dynamic.optionalDependencies.sqlite_jdbc.developmentArtifacts['fabric-26.3'].coordinate =
                'maven.modrinth:bTTf2DEw:latest'
        assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, dynamic) }

        Map wrongHash = cloneMap(catalog)
        wrongHash.optionalDependencies.sqlite_jdbc.developmentArtifacts['fabric-26.3'].sha1 =
                '0' * 39
        assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, wrongHash) }

        Map releasedTarget = cloneMap(catalog)
        releasedTarget.optionalDependencies.sqlite_jdbc.developmentArtifacts['fabric-26.2'] =
                cloneMap(artifact)
        assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, releasedTarget) }

        String fabric = new File(repository, 'gradle/loader-conventions/fabric.gradle').text
        assertTrue(fabric.contains("configurations.create('nclskinsSqliteClientRuntime')"))
        assertTrue(fabric.contains("tasks.register('verifySqliteClientRuntime')"))
        assertTrue(fabric.contains("it.name in ['runClient', 'runClientLicensed']"))
        assertTrue(fabric.contains('candidate.classpath(sqliteClientRuntime)'))
        assertFalse(fabric.contains("tasks.named('runServer').classpath(sqliteClientRuntime)"))
        assertFalse(fabric.contains('org.xerial:sqlite-jdbc'))
        assertFalse(fabric.contains('maven.modrinth:sqlite-jdbc'))
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
    void parchmentMappingsAreExactCatalogOwnedAndLimitedToSelectedTargets() {
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

        File icons = new File(resources, ArtifactVerifier.GUI_ICONS)
        Map<String, File> sourceIcons = [:]
        Files.walk(icons.toPath()).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith('.png') }.forEach { Path path ->
                sourceIcons[icons.toPath().relativize(path).toString().replace('\\', '/')] = path.toFile()
            }
        }
        assertEquals(ArtifactVerifier.GUI_ICON_SIZES.keySet(), sourceIcons.keySet())
        assertEquals(24, ArtifactVerifier.GUI_ICON_SIZES.values().count { it == 16 })
        assertEquals(2, ArtifactVerifier.GUI_ICON_SIZES.values().count { it == 32 })
        assertEquals([
                'action/edit.png',
                'action/duplicate.png',
                'action/delete.png',
                'action/select_folder.png',
                'status/compatibility/extended.png',
                'status/compatibility/incompatible.png'
        ] as Set, ArtifactVerifier.GUI_ICON_SAFE_AREA_REQUIRED)
        ArtifactVerifier.GUI_ICON_SIZES.each { String name, int size ->
            def image = ImageIO.read(sourceIcons[name])
            assertEquals(size, image.width, name)
            assertEquals(size, image.height, name)
            Set<Integer> alpha = (0..<image.height).collectMany { int y ->
                (0..<image.width).collect { int x -> image.getRGB(x, y) >>> 24 }
            } as Set<Integer>
            assertTrue(alpha.every { it == 0 || it == 255 }, "${name} must use binary alpha")
            if (ArtifactVerifier.GUI_ICON_SAFE_AREA_REQUIRED.contains(name)) {
                (0..<image.height).each { int y ->
                    (0..<image.width).each { int x ->
                        if (x < 2 || x >= 14 || y < 2 || y >= 14) {
                            assertEquals(0, image.getRGB(x, y) >>> 24, "${name} at ${x},${y}")
                        }
                    }
                }
            }
        }
        ArtifactVerifier.GUI_ICON_LOCKED_SHA256.each { String name, String expected ->
            assertEquals(expected, sha256(sourceIcons[name]), name)
        }
        ['action/collapse_all.png', 'action/expand_all.png'].each { String name ->
            def image = ImageIO.read(new File(icons, name))
            Set<Integer> pixels = (0..<image.height).collectMany { int y ->
                (0..<image.width).collect { int x -> image.getRGB(x, y) }
            } as Set<Integer>
            assertEquals([0, 255] as Set, pixels.collect { it >>> 24 } as Set, name)
            assertTrue(pixels.contains(0xFFFFFFFF as int), "${name} must contain a white glyph")
            assertTrue(pixels.contains(0xFF3F3F3F as int), "${name} must contain a #3F3F3F shadow")
            assertEquals(3, pixels.size(), "${name} must remain transparent plus two opaque colors")
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

        assertEquals('nclskins-fabric', CatalogTools.licensedClientProfile(catalog, 'fabric'))
        assertEquals('nclskins-forge-family', CatalogTools.licensedClientProfile(catalog, 'forge'))
        assertEquals('nclskins-forge-family', CatalogTools.licensedClientProfile(catalog, 'neoforge'))

        Map sharedAll = cloneMap(catalog)
        sharedAll.development.licensedProfiles.fabric = 'nclskins-forge-family'
        assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, sharedAll) }

        Map splitForgeFamily = cloneMap(catalog)
        splitForgeFamily.development.licensedProfiles.neoforge = 'nclskins-neoforge'
        assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, splitForgeFamily) }

        Map unsafeProfile = cloneMap(catalog)
        unsafeProfile.development.licensedProfiles.fabric = '../tokens'
        assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, unsafeProfile) }

        Map duplicateOperator = cloneMap(catalog)
        duplicateOperator.development.operators[1].uuid =
                duplicateOperator.development.operators[0].uuid
        assertThrows(IllegalArgumentException) {
            CatalogTools.validate(repository, duplicateOperator)
        }

        Map unsafeOperator = cloneMap(catalog)
        unsafeOperator.development.operators[0].name = '../NaoCraftLab'
        assertThrows(IllegalArgumentException) {
            CatalogTools.validate(repository, unsafeOperator)
        }
    }

    @Test
    void loaderAndCompatibilityClientRunsReceiveUuidWithoutServerLeakage() {
        Map<String, String> loaderScripts = [
                fabric  : new File(repository, 'gradle/loader-conventions/fabric.gradle').text,
                forge   : new File(repository, 'gradle/loader-conventions/forge.gradle').text,
                neoforge: new File(repository, 'gradle/loader-conventions/neoforge.gradle').text
        ]
        assertTrue(loaderScripts.forge.contains(
                "property 'devlogin.launch_target', 'cpw.mods.bootstraplauncher.BootstrapLauncher'"))
        assertFalse(loaderScripts.forge.contains('net.minecraftforge.bootstrap.ForgeBootstrap'))
        loaderScripts.each { String loader, String script ->
            String helperCall = 'nclskinsCatalogTools.clientArguments(targetCatalog)'
            String licensedHelperCall = 'nclskinsCatalogTools.licensedClientProfile('
            assertEquals(1, occurrences(script, helperCall), loader)
            assertEquals(1, occurrences(script, licensedHelperCall), loader)
            assertTrue(script.contains(".devlogin/\${licensedClientProfile}"), loader)
            assertTrue(script.contains("'devlogin.launch_profile', licensedClientProfile"), loader)
            assertTrue(script.contains("'devlogin.storage', licensedClientStorage"), loader)
            assertTrue(script.contains('clientLicensed {'), loader)
            int clientStart = script.indexOf('client {')
            int serverStart = script.indexOf('server {', clientStart)
            int helperStart = script.indexOf(helperCall)
            assertTrue(clientStart >= 0, loader)
            if (loader == 'forge') {
                int offlineTaskStart = script.indexOf("candidate.name == 'runClient'")
                assertTrue(offlineTaskStart >= 0 && helperStart > offlineTaskStart, loader)
                assertFalse(script.substring(clientStart, serverStart).contains(helperCall), loader)
                assertFalse(script.substring(script.indexOf('clientLicensed {'), serverStart)
                        .contains(helperCall), loader)
            } else if (serverStart >= 0) {
                assertFalse(script.substring(script.indexOf('clientLicensed {')).contains(helperCall), loader)
                assertTrue(helperStart > clientStart, loader)
                assertTrue(helperStart < serverStart, loader)
                assertFalse(script.substring(serverStart).contains(helperCall), loader)
            } else {
                assertEquals('fabric', loader)
            }
        }
        assertTrue(loaderScripts.fabric.contains('net.fabricmc.loader.impl.launch.knot.KnotClient'))
        assertTrue(loaderScripts.fabric.contains('net.covers1624.devlogin.DevLogin'))
        assertTrue(loaderScripts.forge.contains('cpw.mods.bootstraplauncher.BootstrapLauncher'))
        assertTrue(loaderScripts.forge.contains('net.covers1624.devlogin.DevLogin'))
        assertTrue(loaderScripts.neoforge.contains('devLogin = true'))

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
            assertTrue(script.contains(target.loader.id == 'fabric'
                    ? 'DevLogin' : 'devLogin = true'), target.id.toString())
            String licensedRun = script.substring(script.indexOf('clientLicensed {'), serverStart)
            assertFalse(licensedRun.contains('--uuid'), target.id.toString())
            assertFalse(licensedRun.contains('00000000-0000-0000-0000-000000000001'),
                    target.id.toString())
            assertFalse(script.contains('nclskins-compatibility-server-stop'),
                    target.id.toString())
            assertFalse(script.contains('new PipedInputStream()'), target.id.toString())
            assertTrue(script.contains("tasks.named('runServer', JavaExec)"),
                    target.id.toString())
            assertTrue(script.contains('standardInput = System.in'), target.id.toString())
            if (target.loader.id == 'fabric') {
                assertTrue(script.contains("file('build/classes/java/main').mkdirs()"),
                        target.id.toString())
            }
        }
        assertTrue(ArtifactVerifier.FORBIDDEN_DEV_RUNTIME_PREFIXES.contains(
                'net/covers1624/devlogin/'))
        assertTrue(ArtifactVerifier.FORBIDDEN_DEV_RUNTIME_PREFIXES.contains(
                'META-INF/jars/DevLogin'))
    }

    @Test
    void compatibilityMatricesAreOptionalExactAndBaselineFirst() {
        Map ordinary = catalog.targets.find { !it.containsKey('compatibility') } as Map
        assertNotNull(ordinary)
        Map family = catalog.targets.find { it.id == 'fabric-26.1' } as Map
        assertEquals(['26.1', '26.1.1', '26.1.2'], family.compatibility.minecraftVersions)
        assertEquals(
                [minecraftVersion: '26.1.2', loaderVersion: '0.19.3', serverPort: 25578],
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
        File localeDirectory = new File(repository,
                'compat/resources/canonical/src/main/resources/assets/nclskins/lang')
        Map english = CatalogTools.loadJson(new File(localeDirectory, 'en_us.json'))
        Map russian = CatalogTools.loadJson(new File(localeDirectory, 'ru_ru.json'))

        LocalizationVerifier.sourceLocales(catalog).each { String locale ->
            Map translations = CatalogTools.loadJson(
                    new File(localeDirectory, "${locale}.json"))
            assertEquals(english.keySet(), translations.keySet(), locale)
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
        assertEquals('Remove', english['nclskins.your_skins.delete'])
        assertEquals('Remove', english['nclskins.your_skins.delete_confirm'])
        assertEquals('Убрать', russian['nclskins.your_skins.delete'])
        assertEquals('Убрать', russian['nclskins.your_skins.delete_confirm'])
        assertEquals(
                'Minecraft временно задерживает смену скина и плаща. Последний образ применится автоматически.',
                russian['nclskins.rate_limit.delayed'])
        assertEquals(
                'The site did not allow automatic downloading',
                english['nclskins.add_source.url_site_blocked'])
        assertEquals(
                'Сайт не разрешил автоматическое скачивание',
                russian['nclskins.add_source.url_site_blocked'])
        assertEquals('From skin file', english['nclskins.add_source.choose_file'])
        assertEquals('Из файла скина', russian['nclskins.add_source.choose_file'])
        assertEquals('Collapse all', english['nclskins.collection.collapse_all'])
        assertEquals('Expand all', english['nclskins.collection.expand_all'])
        assertEquals('Свернуть всё', russian['nclskins.collection.collapse_all'])
        assertEquals('Развернуть всё', russian['nclskins.collection.expand_all'])
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
                assertEquals(
                        target.minecraft.minimumRuntimeVersion
                                ? ">=${target.minecraft.minimumRuntimeVersion}".toString()
                                : ">=${target.minecraft.version}".toString(),
                        target.minecraft.predicate)
            } else {
                assertEquals("[${target.loader.version},)".toString(), target.loader.predicate)
                assertEquals("[${target.minecraft.version},)".toString(), target.minecraft.predicate)
            }
        }
        assertEquals(['0.19.3'] as Set, catalog.targets.findAll { it.loader.id == 'fabric' }.collect { it.loader.version } as Set)
        Map neoForgeExtraction = catalog.targets.find { it.id == 'neoforge-26.2' } as Map
        assertEquals('26.2.0.57', neoForgeExtraction.loader.version)
        assertEquals('[26.2.0.57,)', neoForgeExtraction.loader.predicate)
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
        Map exactSnapshot = cloneMap(catalog)
        exactSnapshot.targets.find { it.id == 'fabric-26.3' }.minecraft.predicate = '26.3-alpha.10'
        assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, exactSnapshot) }
        Map mismatchedSnapshotAlias = cloneMap(catalog)
        mismatchedSnapshotAlias.targets.find { it.id == 'fabric-26.3' }.minecraft.runtimeVersion = '26.3-alpha.7'
        assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, mismatchedSnapshotAlias) }
        Map futureSnapshotFloor = cloneMap(catalog)
        Map futureMinecraft = futureSnapshotFloor.targets.find { it.id == 'fabric-26.3' }.minecraft
        futureMinecraft.minimumRuntimeVersion = '26.3-alpha.11'
        futureMinecraft.predicate = '>=26.3-alpha.11'
        assertThrows(IllegalArgumentException) { CatalogTools.validate(repository, futureSnapshotFloor) }
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
    void modListMetadataRemainsResourceOnly() {
        catalog.targets.each { Map target ->
            Map resolved = CatalogTools.resolveTargetSources(repository, catalog, target)
            (resolved.java as List).each { String sourceRoot ->
                File directory = new File(repository, sourceRoot)
                directory.eachFileRecurse { File source ->
                    if (source.name.endsWith('.java')) {
                        String text = source.text
                        assertFalse(text.contains('neoforged.neoforge.client.gui.modlist.ModDisplayInfo'), source.path)
                        assertFalse(text.contains('neoforged.neoforge.client.gui.modlist.DefaultModDisplayInfo'), source.path)
                    }
                }
            }
        }

        String configScreenSource = new File(
                repository,
                'loader/neoforge/common/src/main/java/com/naocraftlab/skins/loader/neoforge/NeoForgeConfigScreenRegistrar.java').text
        assertTrue(configScreenSource.contains('registerExtensionPoint(IConfigScreenFactory.class'))
    }

    @Test
    void everyPipTargetSelectsExactlyOneLoaderNativeRegistration() {
        Map<String, String> expected = [
                'fabric-1.21.11'  : 'avatar-pip-submission-fabric',
                'neoforge-1.21.11': 'avatar-pip-submission-neoforge',
                'fabric-26.1'     : 'avatar-pip-extraction-player-model-fabric',
                'neoforge-26.1'   : 'avatar-pip-extraction-player-model-neoforge',
                'fabric-26.2'     : 'avatar-pip-extraction-simple-model-attack-time-fabric',
                'neoforge-26.2'   : 'avatar-pip-extraction-simple-model-attack-time-neoforge',
                'fabric-26.3'     : 'avatar-pip-extraction-simple-model-no-attack-time-fabric'
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
    void onlySubmissionFabricTargetUsesTheGuiRendererConstructorMixin() {
        List<Map> targets = catalog.targets.findAll {
            it.capabilities.preview.toString().startsWith('avatar-pip-')
        } as List<Map>
        targets.each { Map target ->
            Map resolved = CatalogTools.resolveTargetSources(repository, catalog, target)
            boolean hasConstructorMixin = (resolved.java as List).contains(
                    'loader/fabric/pip-submission/src/main/java')
            boolean declaresMixinConfig = (target.metadata.mixins as List).contains(
                    'nclskins.identifier-submission-fabric.mixins.json')
            assertEquals(target.id == 'fabric-1.21.11', hasConstructorMixin, target.id.toString())
            assertEquals(target.id == 'fabric-1.21.11', declaresMixinConfig, target.id.toString())
        }
        String constructorMixin = new File(
                repository,
                'loader/fabric/pip-submission/src/main/java/com/naocraftlab/skins/compat/client/identifier/submission/mixin/GuiRendererMixin.java').text
        assertTrue(constructorMixin.contains('List<PictureInPictureRenderer<?>>'))
        assertFalse(constructorMixin.contains('java.lang.reflect'))
        assertFalse(constructorMixin.contains('getMethod('))
        assertFalse(constructorMixin.contains('getConstructor('))
    }

    @Test
    void modernPipRegistrationsUseLoaderOwnedApisWithoutReflection() {
        List<String> sources = [
                'loader/fabric/pip-extraction-player-model',
                'loader/fabric/pip-extraction-simple-model'
        ].collect { String module ->
            String fabric = new File(
                    repository,
                    "${module}/src/main/java/com/naocraftlab/skins/loader/fabric/FabricPipRendererRegistration.java").text
            assertTrue(fabric.contains('PictureInPictureRendererRegistry.register'))
            fabric
        }
        String neoForge = new File(
                repository,
                'loader/neoforge/pip-extraction/src/main/java/com/naocraftlab/skins/loader/neoforge/NeoForgePipRendererRegistration.java').text
        assertTrue(neoForge.contains('RegisterPictureInPictureRenderersEvent'))
        assertTrue(neoForge.contains('event.register('))
        sources.add(neoForge)
        sources.each { String source ->
            assertFalse(source.contains('java.lang.reflect'))
            assertFalse(source.contains('getMethod('))
            assertFalse(source.contains('getConstructor('))
        }
    }

    @Test
    void settingsMixinsReplaceTheOpenScreenSupplierByMeaning() {
        [
                'compat/capabilities/gui/immediate-resource-location-player-info/src/main/java/com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/mixin/OptionsScreenMixin.java',
                'compat/capabilities/gui/immediate-resource-location-skin-lookup/src/main/java/com/naocraftlab/skins/compat/client/resourcelocation/skinlookup/mixin/OptionsScreenMixin.java',
                'compat/capabilities/gui/identifier-submission/src/main/java/com/naocraftlab/skins/compat/client/identifier/submission/mixin/OptionsScreenMixin.java',
                'compat/capabilities/gui/extraction-shared/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/mixin/OptionsScreenMixin.java'
        ].each { String path ->
            String source = new File(repository, path).text
            assertTrue(source.contains('OptionsScreen;openScreenButton'), path)
            assertTrue(source.contains('@WrapOperation'), path)
            assertTrue(source.contains('options.skinCustomisation'), path)
            assertTrue(source.contains('original.call(instance, label, target)'), path)
            assertFalse(source.contains('ordinal ='), path)
            assertFalse(source.contains('GridLayout\$RowHelper'), path)
            assertFalse(source.contains('addChild'), path)
        }
    }

    @Test
    void modernDepthHookRequiresExactlyOnePrepareSignature() {
        [
                'compat/capabilities/preview/avatar-pip-depth-gui-render-state/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/mixin/PictureInPictureRendererMixin.java',
                'compat/capabilities/preview/avatar-pip-depth-feature-dispatcher/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/mixin/PictureInPictureRendererMixin.java'
        ].each { String path ->
            String source = new File(repository, path).text
            assertTrue(source.contains('@WrapMethod'))
            assertTrue(source.contains('@WrapOperation'))
            assertTrue(source.contains('try {'))
            assertTrue(source.contains('finally {'))
            assertFalse(source.contains('require = 0'))
            assertFalse(source.contains('@Group'))
        }
    }

    @Test
    void everyProductionSourceRequiresApiSemanticNamespacesAndIdentifiers() {
        List<String> versionPackageErrors = []
        List<String> versionTypeErrors = []
        List<String> semanticErrors = []

        SemanticVerifier.verifyVersionNamespace(
                'SharedPackage.java',
                'package com.naocraftlab.skins.compat.mc262; final class SharedPackage {}',
                [] as Set,
                versionPackageErrors)
        SemanticVerifier.verifyVersionNamespace(
                'SharedType.java',
                '''package com.naocraftlab.skins.compat.client.identifier;
                final class Minecraft262SharedType {}''',
                [] as Set,
                versionTypeErrors)
        SemanticVerifier.verifyVersionNamespace(
                'Semantic.java',
                'package com.naocraftlab.skins.compat.client.identifier; final class IdentifierSharedType {}',
                [] as Set,
                semanticErrors)

        assertTrue(versionPackageErrors.any { it.contains('version-named package') })
        assertTrue(versionTypeErrors.any { it.contains('version-named Java identifier') })
        assertEquals([], semanticErrors)
    }

    @Test
    void exactEpochLeafMayNotRetainItsVersionIdentifier() {
        List<String> errors = []

        SemanticVerifier.verifyVersionNamespace(
                'ExactLeaf.java',
                'package com.naocraftlab.skins.compat.client.identifier.submission; final class Minecraft12111Leaf {}',
                ['1.21.11'] as Set,
                errors)

        assertTrue(errors.any { it.contains('version-named Java identifier') })
    }

    @Test
    void catalogCodeIdentifiersRejectMinecraftEpochsButAllowApiVersions() {
        List<String> errors = []

        SemanticVerifier.verifyCodeIdentifier('bundle', 'avatar-pip-' + '26.2', errors)
        SemanticVerifier.verifyCodeIdentifier('mixin', 'nclskins.mc12111.mixins.json', errors)
        SemanticVerifier.verifyCodeIdentifier(
                'module', 'com.naocraftlab.skins.fabric.mc1201', errors)
        SemanticVerifier.verifyCodeIdentifier('adapter', 'paper-authlib7', errors)
        SemanticVerifier.verifyCodeIdentifier(
                'bundle', 'avatar-pip-extraction-simple-model', errors)

        assertEquals(3, errors.size())
    }

    @Test
    void sourceModuleDirectoriesRejectMinecraftEpochsButAllowSemanticAndApiNames() {
        List<String> errors = []

        SemanticVerifier.verifySourceModuleDirectoryName(
                'compat/capabilities/appearance/local-player-skin-' + '26.2', errors)
        SemanticVerifier.verifySourceModuleDirectoryName(
                'loader/fabric/pip-' + '26.2', errors)
        SemanticVerifier.verifySourceModuleDirectoryName(
                'server-plugin-adapters/paper-' + '1.20.1', errors)
        SemanticVerifier.verifySourceModuleDirectoryName(
                'compat/capabilities/appearance/local-player-skin', errors)
        SemanticVerifier.verifySourceModuleDirectoryName(
                'server-plugin-adapters/paper-authlib7', errors)

        assertEquals(3, errors.size())
        assertTrue(errors.every { it.contains('source module directory') })
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
    void updateNotificationCapabilityIsExactSemanticAndSourceOwned() {
        Map<String, String> expected = catalog.targets.collectEntries { Map target ->
            String implementation = target.id == 'fabric-1.20.1'
                    ? 'modmenu-default-index'
                    : target.loader.id == 'fabric'
                    ? 'modmenu-static-catalog'
                    : 'native-static-catalog'
            [(target.id.toString()): implementation]
        }
        assertEquals(expected, catalog.targets.collectEntries { Map target ->
            [(target.id.toString()): target.capabilities.updateNotification.toString()]
        })
        assertTrue(expected.values().every {
            it in ['modmenu-default-index', 'modmenu-static-catalog',
                   'native-static-catalog']
        })

        Map legacy = CatalogTools.resolveTargetSources(
                repository, catalog, CatalogTools.selectTarget(catalog, 'fabric-1.20.1'))
        Map modern = CatalogTools.resolveTargetSources(
                repository, catalog, CatalogTools.selectTarget(catalog, 'fabric-1.21.1'))
        Map nativeTarget = CatalogTools.resolveTargetSources(
                repository, catalog, CatalogTools.selectTarget(catalog, 'neoforge-26.2'))
        assertTrue(legacy.clientJava.contains(
                'loader/fabric/modmenu-default-index/src/main/java'))
        assertFalse(legacy.clientJava.contains(
                'loader/fabric/modmenu-static-catalog/src/main/java'))
        assertTrue(modern.clientJava.contains(
                'loader/fabric/modmenu-static-catalog/src/main/java'))
        assertFalse(modern.clientJava.contains(
                'loader/fabric/modmenu-default-index/src/main/java'))
        assertTrue(nativeTarget.bundles.contains('native-static-catalog'))
        assertFalse(nativeTarget.java.any { it.toString().contains('/modmenu-') })

        assertEquals(['fabric-1.20.1'] as Set, selected(
                'loader/fabric/modmenu-default-index/src/main/java/Example.java'))
        assertEquals(catalog.targets.findAll {
            it.capabilities.updateNotification == 'modmenu-static-catalog'
        }*.id as Set, selected(
                'loader/fabric/modmenu-static-catalog/src/main/java/Example.java'))

        String modernSource = new File(repository,
                'loader/fabric/modmenu-static-catalog/src/main/java/' +
                'com/naocraftlab/skins/loader/fabric/NclSkinsModMenuApi.java').text
        assertTrue(modernSource.contains('.orElse(null)'))
        assertFalse(modernSource.contains('getUpdateMessage()'))
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
    void refreshSignalIsBackendOwnedAndNoPluginEntrypointRegistersACommand() {
        String bukkit = new File(repository,
                'server-plugin-bukkit/src/main/java/com/naocraftlab/skins/server/plugin/bukkit/' +
                'NclSkinsBukkitPlugin.java').text
        assertTrue(bukkit.contains('PluginChannels.APPEARANCE_REFRESH'))
        assertFalse(bukkit.contains('org.bukkit.command.'))
        assertFalse(bukkit.contains('getCommand('))

        ['velocity', 'bungee'].each { String proxy ->
            String className = proxy == 'velocity'
                    ? 'NclSkinsVelocityPlugin.java' : 'NclSkinsBungeePlugin.java'
            String source = new File(repository,
                    "server-plugin-${proxy}/src/main/java/com/naocraftlab/skins/server/plugin/" +
                    "${proxy}/${className}").text
            assertTrue(source.contains('PluginChannels.PROXY_REFRESH'))
            assertFalse(source.contains('PluginChannels.APPEARANCE_REFRESH'))
            assertFalse(source.contains('CommandManager'))
            assertFalse(source.contains('registerCommand'))
        }

        String pluginYaml = new File(repository, 'server-plugin/src/main/resources/plugin.yml').text
        assertFalse(pluginYaml.contains('commands:'))
        assertFalse(pluginYaml.contains('nclskin:'))
    }

    @Test
    void nativeAcceptanceFixesRemainCrossLoaderAndObserverOnly() {
        String forgeSender = new File(repository,
                'compat/capabilities/server-signal/forge-event-channel/src/main/java/' +
                'com/naocraftlab/skins/compat/client/' +
                'MinecraftServerAppearanceRefreshNotifier.java').text
        assertTrue(forgeSender.contains('new ServerboundCustomPayloadPacket('))
        assertFalse(forgeSender.contains('isRemotePresent('))

        String paperPublication = new File(repository,
                'server-plugin-bukkit/src/main/java/com/naocraftlab/skins/server/plugin/bukkit/' +
                'PaperProfilePublicationBackend.java').text
        assertTrue(paperPublication.contains('getMethod("getProfile")'))
        assertTrue(paperPublication.contains('getDeclaredMethod(\n                "unregisterEntity"'))
        assertTrue(paperPublication.contains('getDeclaredMethod(\n                "trackAndShowEntity"'))
        assertTrue(paperPublication.contains('invokeObserver(unregisterEntity'))
        assertTrue(paperPublication.contains('invokeObserver(trackAndShowEntity'))
        assertTrue(paperPublication.contains('observer != checkedActor'))
        assertTrue(paperPublication.contains('profileState.install('))
        assertFalse(paperPublication.contains('hidePlayer('))
        assertFalse(paperPublication.contains('showPlayer('))
        assertFalse(paperPublication.contains('refreshPlayer'))
        assertFalse(paperPublication.contains('setPlayerProfile('))

        String paperProfileState = new File(repository,
                'server-plugin-bukkit/src/main/java/com/naocraftlab/skins/server/plugin/bukkit/' +
                'PaperProfileStateBinding.java').text
        assertTrue(paperProfileState.contains('serverPlayer.getField("gameProfile")'))
        assertTrue(paperProfileState.contains('gameProfile.getConstructor('))
        assertTrue(paperProfileState.contains('propertyMap.getConstructor(multimap)'))
        assertTrue(paperProfileState.contains('immutableLiveProfile.set('))
        assertTrue(paperProfileState.contains('installMutable('))
        assertFalse(paperProfileState.contains('getDeclaredFields('))
        assertFalse(paperProfileState.contains('getFields('))
    }

    @Test
    void metadataMatchesEveryLoaderContract() {
        String version = CatalogTools.loadVersion(repository)
        String requiredServerPluginVersion =
                MetadataRenderer.effectiveRequiredServerPluginVersion(version)
        catalog.targets.each { Map target ->
            Map<String, String> resources = MetadataRenderer.render(catalog, target, version)
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
                    assertEquals('neoforge-26.2', target.id)
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
    void modMenuAbiProfilesExactlyCoverFabricTargets() {
        assertEquals([], ModMenuAbiVerifier.validate(catalog, modMenuAbi))
        assertEquals('modmenu-default-index', modMenuAbi.targets['fabric-1.20.1'])
        ['fabric-1.21.1', 'fabric-1.21.11', 'fabric-26.1', 'fabric-26.2',
         'fabric-26.3'].each { String targetId ->
            assertEquals('modmenu-static-catalog', modMenuAbi.targets[targetId])
        }
        Map modern = modMenuAbi.profiles['modmenu-static-catalog'] as Map
        assertEquals([
                'getUpdateChecker()Lcom/terraformersmc/modmenu/api/UpdateChecker;',
                'checkForUpdates()Lcom/terraformersmc/modmenu/api/UpdateInfo;',
                'getDownloadLink()Ljava/lang/String;',
                'getUpdateChannel()Lcom/terraformersmc/modmenu/api/UpdateChannel;',
                'getUserPreference()Lcom/terraformersmc/modmenu/api/UpdateChannel;'
        ] as Set, (modern.classes as List).collectMany { Map entry ->
            (entry.members as List).collect { Map member ->
                "${member.name}${member.descriptor}".toString()
            }
        } as Set)

        Map missing = cloneMap(modMenuAbi)
        missing.targets.remove('fabric-26.3')
        assertTrue(ModMenuAbiVerifier.validate(catalog, missing).contains(
                'Mod Menu ABI targets must exactly cover Fabric targets'))
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
                assertEquals('-PnclskinsDevLogging=true', configuration.ExternalSystemSettings.option
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
            assertEquals('-PnclskinsDevLogging=true', configuration.ExternalSystemSettings.option
                    .find { it.@name == 'scriptParameters' }.@value.toString())
            assertEquals('', configuration.ExternalSystemSettings.option
                    .find { it.@name == 'vmOptions' }.@value.toString())
            assertTrue(rendered.contains(IdeaRunConfigurations.GENERATED_MARKER))
        }
        assertEquals(IdeaRunConfigurations.orderedModRuntimes(catalog).size() *
                IdeaRunConfigurations.RUN_KINDS.size() +
                catalog.serverPluginTopologies.size(), taskNames.size())
        assertEquals(75, IdeaRunConfigurations.orderedConfigurationNames(catalog).size())
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
    void serverPluginRunTaskDoesNotShadowRuntimeLookupWithResolvedRuntimeMap() {
        String source = Files.readString(Path.of(
                'src/main/groovy/com/naocraftlab/skins/buildlogic/ServerPluginRunTask.groovy'))
        assertFalse(source.contains('Map runtime = runtime(catalog'))
        assertTrue(source.contains("runtime(catalog, 'bungeeguard-1.4.0')"))
        assertTrue(source.contains("runtime(catalog, 'protocollib-5.4.0')"))
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

    @Test
    void serverRuntimeEulaTaskUsesAConfigurationCacheSafeOutputProperty() {
        String task = new File(
                repository,
                'gradle/build-logic/src/main/groovy/com/naocraftlab/skins/buildlogic/AcceptServerRuntimeEulaTask.groovy').text
        String rootBuild = new File(repository, 'build.gradle').text

        assertTrue(task.contains('abstract RegularFileProperty getMarkerFile()'))
        assertFalse(task.contains('project.rootDir'))
        assertTrue(rootBuild.contains(
                'markerFile.set(layout.projectDirectory.file(ServerPluginRuntimeSupport.EULA_MARKER))'))
    }

    @Test
    void serverPluginRunTaskUsesStaticPortPreflightAndConfiguredArtifact() {
        String task = new File(
                repository,
                'gradle/build-logic/src/main/groovy/com/naocraftlab/skins/buildlogic/ServerPluginRunTask.groovy').text
        String rootBuild = new File(repository, 'build.gradle').text

        assertTrue(task.contains('ServerPluginRunTask.requirePortFree(port)'))
        assertTrue(task.contains('abstract RegularFileProperty getPluginArtifact()'))
        assertTrue(task.contains('File plugin = pluginArtifact.get().asFile'))
        assertFalse(task.contains('project.version'))
        assertTrue(rootBuild.contains('pluginArtifact.set(layout.projectDirectory.file('))
    }

    @Test
    void serverPluginSupervisorUsesBlockingForwardedStdinWithoutLosingProcessMonitoring() {
        String task = new File(
                repository,
                'gradle/build-logic/src/main/groovy/com/naocraftlab/skins/buildlogic/ServerPluginRunTask.groovy').text

        assertTrue(task.contains('new LinkedBlockingQueue<>()'))
        assertTrue(task.contains('reader.readLine()'))
        assertTrue(task.contains('commands.poll(100, TimeUnit.MILLISECONDS)'))
        assertTrue(task.contains('processes.entrySet().find'))
        assertFalse(task.contains('reader.ready()'))
        assertFalse(task.contains('inputClosed'))
    }

    @Test
    void legacyServerPluginKernelsResolveThroughBuildToolsWithoutFakeRuntimeRows() {
        String task = new File(
                repository,
                'gradle/build-logic/src/main/groovy/com/naocraftlab/skins/buildlogic/ServerPluginRunTask.groovy').text

        assertTrue(task.contains("topology.kernel in ['craftbukkit', 'spigot']"))
        assertTrue(task.contains("? 'buildtools-200'"))
        assertTrue(task.contains('resolveRuntime(root, catalog, runtimeSpec, topology.kernel.toString())'))
        assertTrue(task.contains('ServerPluginRunTask.gitCommit(checkout)'))
    }

    private static final class RandomSecure extends java.security.SecureRandom {
        @Override
        void nextBytes(byte[] bytes) {
            Arrays.fill(bytes, (byte) 0x5a)
        }
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
    void modServerEulaRequiresExplicitManagedAcceptance() {
        Path root = Files.createTempDirectory('nclskins-mod-eula-')
        Map target = catalog.targets.first() as Map
        assertThrows(org.gradle.api.GradleException) {
            RunDirectorySupport.ensureTargetEula(root.toFile(), target)
        }
        File marker = new File(root.toFile(), ServerPluginRuntimeSupport.EULA_MARKER)
        Files.createDirectories(marker.parentFile.toPath())
        marker.text = ServerPluginRuntimeSupport.eulaMarker('2026-08-12T00:00:00Z')
        RunDirectorySupport.ensureTargetEula(root.toFile(), target)
        File eula = new File(RunLayout.modDirectory(root.toFile(), target, 'Server'), 'eula.txt')
        assertEquals('eula=true\n', eula.text)
        eula.text = 'eula=false\n'
        RunDirectorySupport.ensureTargetEula(root.toFile(), target)
        assertEquals('eula=true\n', eula.text)
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
                '26.3': ['LAN', 'Fabric']
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
    void neoForgeRuntimeYaclMetadataPatchIsNarrowAndReproducible() {
        Path directory = Files.createTempDirectory('nclskins-yacl-metadata-')
        Path input = directory.resolve('yacl.jar')
        new ZipOutputStream(Files.newOutputStream(input)).withCloseable { ZipOutputStream zip ->
            zip.putNextEntry(new ZipEntry('META-INF/neoforge.mods.toml'))
            zip.write(('''modLoader = "javafml"\n[[mods]]\n\t''' +
                    NeoForgeRuntimeMetadata.LEGACY_ICON + '\n').getBytes('UTF-8'))
            zip.closeEntry()
            zip.putNextEntry(new ZipEntry('yacl-128x.png'))
            zip.write(new byte[]{1, 2, 3})
            zip.closeEntry()
        }
        Path first = directory.resolve('first.jar')
        Path second = directory.resolve('second.jar')
        NeoForgeRuntimeMetadata.patchYacl(input, first)
        NeoForgeRuntimeMetadata.patchYacl(input, second)
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second))
        new ZipFile(first.toFile()).withCloseable { ZipFile zip ->
            String metadata = zip.getInputStream(zip.getEntry(NeoForgeRuntimeMetadata.METADATA))
                    .withCloseable { new String(it.readAllBytes(), 'UTF-8') }
            assertTrue(metadata.contains(NeoForgeRuntimeMetadata.SQUARE_ICON))
            assertFalse(metadata.contains(NeoForgeRuntimeMetadata.LEGACY_ICON))
            assertArrayEquals(new byte[]{1, 2, 3},
                    zip.getInputStream(zip.getEntry('yacl-128x.png')).readAllBytes())
        }

        String convention = new File(repository,
                'gradle/loader-conventions/neoforge.gradle').text
        assertTrue(convention.contains("targetSpec.minecraft.version == '26.2'"))
        assertTrue(convention.contains("tasks.register('patchYaclRuntimeMetadata'"))
        assertTrue(convention.contains('add(yaclRuntimeConfiguration, files(patchedYaclRuntime))'))
        assertTrue(convention.contains('yaclRuntimeGraph.transitive = false'))
    }

    @Test
    void targetRunsUseTheCurrentGradleConsoleForInteractiveIo() {
        String source = new File(repository,
                'gradle/build-logic/src/main/groovy/com/naocraftlab/skins/buildlogic/TargetRunTask.groovy').text
        assertTrue(source.contains('ExecOperations'))
        assertTrue(source.contains('standardInput = System.in'))
        assertTrue(source.contains('standardOutput = System.out'))
        assertTrue(source.contains('errorOutput = System.err'))
        assertFalse(source.contains('inheritIO()'))
    }

    @Test
    void compatibilityRunsUseTheCurrentGradleConsoleForInteractiveIo() {
        String taskSource = new File(repository,
                'gradle/build-logic/src/main/groovy/com/naocraftlab/skins/buildlogic/CompatibilityRunTask.groovy').text
        String harnessSource = new File(repository,
                'gradle/build-logic/src/main/groovy/com/naocraftlab/skins/buildlogic/CompatibilityHarness.groovy').text
        assertTrue(taskSource.contains('ExecOperations'))
        assertTrue(taskSource.contains('execOperations'))
        assertTrue(harnessSource.contains('executeInteractive('))
        assertTrue(harnessSource.contains('standardInput = System.in'))
        assertTrue(harnessSource.contains('standardOutput = System.out'))
        assertTrue(harnessSource.contains('errorOutput = System.err'))
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
    void committedAbiBaselinesMatchEachTargetSelection() {
        catalog.targets.each { Map target ->
            Map declarations = catalog.capabilityImplementations as Map
            Set<String> selected = (target.capabilities as Map)
                    .findAll { Object key, Object ignored ->
                        !CatalogTools.EXTERNAL_ABI_CAPABILITIES.contains(key.toString())
                    }.values().collect { declarations[it].abiImplementation.toString() } as Set
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
    void mixinPolicyRejectsNonDelegatingChainableWrappers() {
        Path root = Files.createTempDirectory('nclskins-mixin-policy-')
        try {
            Path source = root.resolve('compat/fixture/src/main/java/BadMixin.java')
            Files.createDirectories(source.parent)
            Files.writeString(source, '''
                class BadMixin {
                    @WrapOperation void replace(Object next) { }
                    @ModifyReturnValue int modify(int value) { return 7; }
                }
                ''')
            List<String> errors = []

            SemanticVerifier.verifyMixinInjectionPolicy(root, errors)

            assertTrue(errors.any { it.contains('delegate through original.call') })
            assertTrue(errors.any { it.contains('preserve an explicit original value') })
        } finally {
            root.toFile().deleteDir()
        }
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
    void semanticVerifierRejectsSecondSubmissionScreenBackgroundPass() {
        List<String> errors = []

        SemanticVerifier.verifyLeaf(
                'identifier-submission',
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
    void semanticVerifierRequiresFlatLegacyGalleryCardSurfaces() {
        List<String> errors = []

        SemanticVerifier.verifyLeaf(
                'immediate-resource-location-player-info',
                'gui',
                '''
                class ImmediateClientRuntime {
                    ClientRuntime runtime;
                    void renderPanel(Object graphics, ViewSpec.Panel panel, float textureU) {
                        if (panel.style() == ViewSpec.Panel.Style.VANILLA_LIST) {
                            graphics.blit(BACKGROUND_LOCATION, panel.bounds(), textureU);
                        }
                    }
                }
                ''',
                errors)

        assertTrue(errors.any { it.contains('translucent black surface') }, errors.toString())
    }

    @Test
    void semanticVerifierAcceptsAuthlib10SessionServiceOnlyForItsDedicatedLeaf() {
        String source = '''
            class Verifier implements OfficialTextureSignatureVerifier {
                SessionService service;
                void verify(Object property) {
                    service.getSecurePropertyValue(property);
                    new OfficialTextureAppearanceParser();
                    Optional.empty();
                }
            }
        '''
        List<String> authlib10Errors = []
        List<String> authlib9Errors = []

        SemanticVerifier.verifyLeaf('profile-verification-authlib-v10', 'serverProfileVerification', source, authlib10Errors)
        SemanticVerifier.verifyLeaf('profile-verification-authlib-v9', 'serverProfileVerification', source, authlib9Errors)

        assertEquals([], authlib10Errors)
        assertTrue(authlib9Errors.any { it.contains("lacks required marker 'MinecraftSessionService'") })
    }

    @Test
    void inputConstantsScreenUsesNativeMouseButtonWithoutChangingGlfwScreen() {
        File glfwScreen = new File(
                repository,
                'compat/capabilities/gui/extraction-screen-glfw/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/NclSkinsScreen.java')
        File inputConstantsScreen = new File(
                repository,
                'compat/capabilities/gui/extraction-screen-input-constants/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/NclSkinsScreen.java')

        assertTrue(glfwScreen.text.contains('private static final int LEFT_MOUSE_BUTTON = 0;'))
        assertTrue(inputConstantsScreen.text.contains(
                'private static final int NATIVE_LEFT_MOUSE_BUTTON = InputConstants.MOUSE_BUTTON_LEFT;'))
        assertTrue(inputConstantsScreen.text.contains(
                'private static final int PRODUCT_PRIMARY_POINTER_BUTTON = 0;'))
        assertTrue(inputConstantsScreen.text.contains(
                'runtime.pointerPressed(event.x(), event.y(), PRODUCT_PRIMARY_POINTER_BUTTON);'))
        assertTrue(inputConstantsScreen.text.contains('''runtime.pointerDragged(
                    event.x(), event.y(), PRODUCT_PRIMARY_POINTER_BUTTON, dragX, dragY);'''))
        assertTrue(inputConstantsScreen.text.contains(
                'runtime.pointerReleased(event.x(), event.y(), PRODUCT_PRIMARY_POINTER_BUTTON);'))
    }

    @Test
    void semanticVerifierRejectsOneSharedSubmissionBakedTexture() {
        List<String> errors = []

        SemanticVerifier.verifyLeaf(
                'identifier-submission',
                'gui',
                'class ScreenLeaf { ClientRuntime runtime; '
                        + 'SimplePreviewRenderer bakedRenderer; }',
                errors)

        assertTrue(errors.any {
            it.contains('native host lacks marker')
                    && it.contains('bakedRenderers.computeIfAbsent(')
        }, errors.toString())
    }

    @Test
    void identifierSubmissionOwnsNativeUiAndExactSettingsHooks() {
        File screen = new File(
                repository,
                'compat/capabilities/gui/identifier-submission/src/main/java/com/naocraftlab/skins/compat/client/identifier/submission/NclSkinsScreen.java')
        File menu = new File(
                repository,
                'compat/capabilities/gui/identifier-submission/src/main/java/com/naocraftlab/skins/compat/client/identifier/submission/NclSkinsMenuPanel.java')
        File mixins = new File(
                repository,
                'compat/capabilities/preview/avatar-pip-submission/src/main/resources/nclskins.identifier-submission.mixins.json')
        String screenSource = screen.text
        String menuSource = menu.text
        String mixinSource = mixins.text

        assertFalse(screenSource.contains('ViewHostCoordinator'))
        assertFalse(screenSource.contains('setRectangle('))
        assertFalse(menuSource.contains('setRectangle('))
        assertTrue(screenSource.contains('SubmissionScrollController'))
        assertTrue(screenSource.contains('''scrollController.render(
                graphics,
                OFFSCREEN_MOUSE_COORDINATE,
                OFFSCREEN_MOUSE_COORDINATE,
                partialTick);'''))
        assertTrue(new File(
                repository,
                'compat/capabilities/gui/identifier-submission/src/main/java/com/naocraftlab/skins/compat/client/identifier/submission/SubmissionScrollController.java')
                .text.contains('renderScrollbar(graphics, mouseX, mouseY);'))
        assertTrue(screenSource.contains('NativeWidgetSignature'))
        assertTrue(screenSource.contains('NativeTabGroup'))
        assertTrue(screenSource.contains('maskWidgetsOutsideClip('))
        assertTrue(screenSource.contains('''if (nativeDispatchDepth == 0 && !runtime.closed()) {
            refresh();
        }'''))
        assertTrue(screenSource.contains('int size = guiIcon.baseCanvas();'))
        assertTrue(screenSource.contains('iconTexture(guiIcon)'))
        assertFalse(screenSource.contains('ACTION_ICON_TEXTURE_SIZE'))
        assertTrue(menuSource.contains('panelBounds().contains(mouseX, mouseY)'))
        assertTrue(mixinSource.contains('"OptionsScreenMixin"'))
        assertTrue(mixinSource.contains('"AccessibilityOptionsScreenMixin"'))
        assertTrue(new File(
                repository,
                'client-runtime/src/main/java/com/naocraftlab/skins/runtime/ViewHostCoordinator.java').exists())
    }

    @Test
    void everyNativeGuiHostUsesTypedSemanticIconResources() {
        [
                'compat/gui-immediate/src/main/java/com/naocraftlab/skins/compat/gui/immediate/NclSkinsImmediateScreen.java',
                'compat/capabilities/gui/identifier-submission/src/main/java/com/naocraftlab/skins/compat/client/identifier/submission/NclSkinsScreen.java',
                'compat/capabilities/gui/extraction-screen-glfw/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/NclSkinsScreen.java',
                'compat/capabilities/gui/extraction-screen-input-constants/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/NclSkinsScreen.java'
        ].each { String path ->
            String source = new File(repository, path).text
            assertTrue(source.contains('GuiIcon'), path)
            assertTrue(source.contains('icon.resourcePath()'), path)
            assertTrue(source.contains('baseCanvas()'), path)
            assertFalse(source.contains('APPROVED_ACTION_ICONS'), path)
            assertFalse(source.contains('ACTION_ICON_TEXTURE_SIZE'), path)
        }
    }

    @Test
    void compatibilityIndicatorsAndAppearanceTogglesShareIconOnlyFrames() {
        String immediate = new File(
                repository,
                'compat/gui-immediate/src/main/java/com/naocraftlab/skins/compat/gui/immediate/NclSkinsImmediateScreen.java').text
        String submission = new File(
                repository,
                'compat/capabilities/gui/identifier-submission/src/main/java/com/naocraftlab/skins/compat/client/identifier/submission/NclSkinsScreen.java').text
        List<String> extraction = [
                'compat/capabilities/gui/extraction-screen-glfw/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/NclSkinsScreen.java',
                'compat/capabilities/gui/extraction-screen-input-constants/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/NclSkinsScreen.java'
        ].collect { new File(repository, it).text }

        String cardStyle = new File(
                repository,
                'client-runtime/src/main/java/com/naocraftlab/skins/runtime/CatalogCardStyle.java').text
        String backgroundKinds = cardStyle.substring(
                cardStyle.indexOf('public static boolean backgroundBehindContent('),
                cardStyle.indexOf('public static int backgroundBehindContentColor('))
        assertFalse(backgroundKinds.contains('COMPATIBILITY_INDICATOR'))

        String immediateIndicator = immediate.substring(
                immediate.indexOf('private final class CompatibilityIndicatorWidget'),
                immediate.indexOf('private static void renderActionIcon'))
        assertTrue(immediateIndicator.contains('renderActionIcon('))
        assertTrue(immediateIndicator.contains('isHoveredOrFocused()'))
        assertTrue(immediateIndicator.contains('drawCardFocusFrame('))
        assertFalse(immediateIndicator.contains('super.renderWidget('))
        String immediateIconButton = immediate.substring(
                immediate.indexOf('private final class IconButtonWidget'),
                immediate.indexOf('private final class CompatibilityIndicatorWidget'))
        assertTrue(immediateIconButton.contains('if (!iconOnly)'))
        assertTrue(immediateIconButton.contains('if (iconOnly && isHoveredOrFocused())'))
        assertTrue(immediateIconButton.contains('dispatchNativeWidget(widgetId, hasShiftDown())'))
        assertTrue(immediate.count('renderActionIcon(') >= 3)
        assertTrue(submission.contains('spec.hint()'))
        String submissionIndicator = submission.substring(
                submission.indexOf('case ICON_ONLY_BUTTON, COMPATIBILITY_INDICATOR -> {'),
                submission.indexOf('case CATALOG_DELETE -> {'))
        assertTrue(submissionIndicator.contains('iconTexture(guiIcon)'))
        assertTrue(submissionIndicator.contains('guiIcon.baseCanvas()'))
        assertFalse(submissionIndicator.contains('renderDefaultSprite('))
        assertTrue(submission.contains('boolean iconOnlyFrame = kind == ViewSpec.WidgetKind.ICON_ONLY_BUTTON'))
        assertTrue(submission.contains('if ((iconOnlyFrame && isHoveredOrFocused())'))
        assertTrue(submission.contains('spec.kind() != ViewSpec.WidgetKind.COMPATIBILITY_INDICATOR'))
        extraction.each { String source ->
            String iconButton = source.substring(
                    source.indexOf('private static final class IconButtonWidget'),
                    source.indexOf('private static final class CompatibilityIndicatorWidget'))
            assertTrue(iconButton.contains('if (!iconOnly)'))
            assertTrue(iconButton.contains('if (iconOnly && isHoveredOrFocused())'))
            assertTrue(iconButton.contains('onPress.accept(input)'))
            String indicator = source.substring(
                    source.indexOf('private static final class CompatibilityIndicatorWidget'),
                    source.indexOf('private static void extractActionIcon'))
            assertTrue(indicator.contains('extractActionIcon('))
            assertTrue(indicator.contains('isHoveredOrFocused()'))
            assertTrue(indicator.contains('extractCardFocusFrame('))
            assertFalse(indicator.contains('extractDefaultSprite('))
            assertTrue(source.count('extractActionIcon(') >= 3)
        }
    }

    @Test
    void editorPreviewCompletesBeforeCapeControlCompositeInEveryGuiHost() {
        String immediate = new File(
                repository,
                'compat/gui-immediate/src/main/java/com/naocraftlab/skins/compat/gui/immediate/NclSkinsImmediateScreen.java').text
        String submission = new File(
                repository,
                'compat/capabilities/gui/identifier-submission/src/main/java/com/naocraftlab/skins/compat/client/identifier/submission/NclSkinsScreen.java').text
        List<String> extraction = [
                'compat/capabilities/gui/extraction-screen-glfw/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/NclSkinsScreen.java',
                'compat/capabilities/gui/extraction-screen-input-constants/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/NclSkinsScreen.java'
        ].collect { new File(repository, it).text }

        assertTrue(immediate.contains('''if (editor) {
            renderPreviews(graphics, view);
            if (!view.previews().isEmpty()) {
                capabilities.finishPreviewPass(graphics);
            }
        }'''))
        assertTrue(immediate.indexOf('if (editor) {')
                < immediate.indexOf('renderCardBackgrounds(graphics, view, mouseX, mouseY);'))
        assertTrue(submission.contains('''if (editor) {
            renderPreviews(graphics, current);
            graphics.nextStratum();
            renderListPanels(graphics, current);
            renderCardBackgrounds(graphics, current, mouseX, mouseY);
            graphics.nextStratum();
            renderBackEquipment(graphics, current);'''))
        extraction.each { String source ->
            assertTrue(source.contains('''if (editor) {
            drawPreviews(graphics, view);
            graphics.nextStratum();
            drawListPanels(graphics, view);
            drawCardBackgrounds(graphics, view, mouseX, mouseY);
            graphics.nextStratum();
            drawBackEquipmentPreviews(graphics, view);'''))
        }
    }

    @Test
    void previewDepthAndSettlingUseExactHostSeams() {
        String depthMixin = new File(
                repository,
                'compat/capabilities/preview/avatar-pip-submission/src/main/java/com/naocraftlab/skins/compat/client/identifier/submission/mixin/PictureInPictureRendererDepthMixin.java').text
        assertTrue(depthMixin.contains(
                'CachedOrthoProjectionMatrixBuffer;<init>(Ljava/lang/String;FFZ)V'))
        assertTrue(depthMixin.contains('require = 1'))
        assertTrue(depthMixin.contains('expect = 1'))
        assertTrue(depthMixin.contains('allow = 1'))

        [
                'compat/capabilities/preview/remote-player/src/main/java/com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/RemotePlayerPreviewRenderer.java',
                'compat/capabilities/preview/player-skin/src/main/java/com/naocraftlab/skins/compat/client/resourcelocation/skinlookup/VanillaAppearancePreviewRenderer.java',
                'compat/capabilities/preview/avatar-pip-submission/src/main/java/com/naocraftlab/skins/compat/client/identifier/submission/SubmissionPreviewRenderer.java',
                'compat/capabilities/preview/avatar-pip-render-state-attack-time/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/AvatarRenderStatePreviewRenderer.java',
                'compat/capabilities/preview/avatar-pip-render-state-no-attack-time/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/AvatarRenderStatePreviewRenderer.java'
        ].each { String path ->
            String source = new File(repository, path).text
            assertTrue(source.contains('previewTickGate.shouldTick('), path)
            assertTrue(source.contains('player.tick();'), path)
        }
    }

    @Test
    void playerLayerAnchorsCoverRemotePlayersWithoutSuppressingWorldFailures() {
        Map<String, String> layerMixins = [
                '1.20.1': 'compat/capabilities/gui/immediate-resource-location-player-info/src/main/java/com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/mixin/LivingEntityRendererPreviewMixin.java',
                '1.21.1': 'compat/capabilities/gui/immediate-resource-location-skin-lookup/src/main/java/com/naocraftlab/skins/compat/client/resourcelocation/skinlookup/mixin/LivingEntityRendererPreviewMixin.java',
                '1.21.11': 'compat/capabilities/preview/avatar-pip-submission/src/main/java/com/naocraftlab/skins/compat/client/identifier/submission/mixin/LivingEntityRendererPreviewMixin.java',
                '26.1': 'compat/capabilities/preview/avatar-pip-extraction-player-model/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/mixin/LivingEntityRendererPreviewMixin.java',
                '26.2': 'compat/capabilities/preview/avatar-pip-render-simple-model/src/main/java/com/naocraftlab/skins/compat/client/identifier/extraction/mixin/LivingEntityRendererPreviewMixin.java'
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
    void identifierSubmissionPreviewExtractsAndSubmitsInsideDedicatedDeferredRenderer() {
        File previewRoot = new File(
                repository,
                'compat/capabilities/preview/avatar-pip-submission/src/main')
        File renderer = new File(
                previewRoot,
                'java/com/naocraftlab/skins/compat/client/identifier/submission/SubmissionPreviewRenderer.java')
        File liveRenderer = new File(
                previewRoot,
                'java/com/naocraftlab/skins/compat/client/identifier/submission/LivePreviewRenderer.java')
        File liveState = new File(
                previewRoot,
                'java/com/naocraftlab/skins/compat/client/identifier/submission/LivePreviewRenderState.java')
        File scope = new File(
                previewRoot,
                'java/com/naocraftlab/skins/compat/client/identifier/submission/PreviewScope.java')
        File guiMixin = new File(
                previewRoot,
                'java/com/naocraftlab/skins/compat/client/identifier/submission/mixin/GuiEntityRendererMixin.java')
        File layerMixin = new File(
                previewRoot,
                'java/com/naocraftlab/skins/compat/client/identifier/submission/mixin/LivingEntityRendererPreviewMixin.java')
        File modelPartMixin = new File(
                previewRoot,
                'java/com/naocraftlab/skins/compat/client/identifier/submission/mixin/ModelPartPreviewMixin.java')
        File mixins = new File(previewRoot, 'resources/nclskins.identifier-submission.mixins.json')

        assertTrue(scope.exists())
        assertFalse(guiMixin.exists())
        assertTrue(liveRenderer.exists())
        assertTrue(liveState.exists())
        assertTrue(modelPartMixin.exists())
        assertTrue(renderer.text.contains('LivePreviewRenderState'))
        assertTrue(scope.text.contains('minecraft.player != player'))
        assertTrue(liveRenderer.text.contains('EditorPreviewLayerGuard.open('))
        assertTrue(liveRenderer.text.contains('state.previewContext().open(minecraft)'))
        int extract = liveRenderer.text.indexOf('.extractEntity(')
        int submit = liveRenderer.text.indexOf('.submit(')
        assertTrue(extract >= 0 && submit > extract)
        assertTrue(liveRenderer.text.contains('renderAllFeatures()'))
        assertTrue(layerMixin.text.contains('PreviewModelAnchors.open('))
        assertTrue(layerMixin.text.contains('state instanceof AvatarRenderState'))
        assertTrue(modelPartMixin.text.contains('cubes.isEmpty()'))
        assertFalse(mixins.text.contains('"GuiEntityRendererMixin"'))
        assertFalse(mixins.text.contains('"AvatarRenderStateMixin"'))
        assertTrue(mixins.text.contains('"ModelPartPreviewMixin"'))

        String fabricRegistration = new File(
                repository,
                'loader/fabric/pip-submission/src/main/java/com/naocraftlab/skins/compat/client/identifier/submission/mixin/GuiRendererMixin.java').text
        String neoForgeRegistration = new File(
                repository,
                'loader/neoforge/pip-submission/src/main/java/com/naocraftlab/skins/loader/neoforge/NeoForgePipRendererRegistration.java').text
        assertEquals(1, fabricRegistration.count('new BakedPreviewRenderer(bufferSource)'))
        assertEquals(1, fabricRegistration.count('new LivePreviewRenderer(bufferSource)'))
        assertEquals(1, neoForgeRegistration.count('BakedPreviewRenderer::new'))
        assertEquals(1, neoForgeRegistration.count('LivePreviewRenderer::new'))

        StringBuilder productionSources = new StringBuilder()
        [previewRoot,
         new File(repository, 'loader/fabric/pip-submission/src/main/java'),
         new File(repository, 'loader/neoforge/pip-submission/src/main/java')].each { File root ->
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
    void identifierSubmissionUsesVanillaElytraGeometryAndNeutralPoseInBothPreviewPaths() {
        File previewRoot = new File(
                repository,
                'compat/capabilities/preview/avatar-pip-submission/src/main/java/com/naocraftlab/skins/compat/client/identifier/submission')
        String liveRenderer = new File(previewRoot, 'SubmissionPreviewRenderer.java').text
        String simpleRenderer = new File(previewRoot, 'SimplePreviewRenderer.java').text
        String bakedRenderer = new File(previewRoot, 'BakedPreviewRenderer.java').text
        String bakedState = new File(previewRoot, 'BakedPreviewRenderState.java').text

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
    void semanticVerifierRejectsVanillaOnlySubmissionBakedPitch() {
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
                            SimplePreviewRenderer baked;
                            ItemStack empty = ItemStack.EMPTY;
                        }
                    }
                    ''')
            List<String> errors = []

            SemanticVerifier.verifyPreviewBundle(
                    'avatar-pip-submission-fabric', [sourceRoot] as Set, errors)

            assertTrue(errors.any {
                it.contains('submission preview lacks required marker')
                        && it.contains('BakedPreviewRenderState')
            }, errors.toString())
        } finally {
            sourceRoot.toFile().deleteDir()
        }
    }

    @Test
    void semanticVerifierRejectsBakedPitchConventionInExtractionLivePreview() {
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
                            AvatarPreviewContext context;
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
                            BakedPlayerPose.applyPitch(pose, state.pitchDegrees());
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
                    'avatar-pip-extraction-simple-model-attack-time-fabric', [sourceRoot] as Set, errors)

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

    private static String sha256(File file) {
        MessageDigest.getInstance('SHA-256').digest(file.bytes)
                .collect { String.format('%02x', it & 0xff) }.join()
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

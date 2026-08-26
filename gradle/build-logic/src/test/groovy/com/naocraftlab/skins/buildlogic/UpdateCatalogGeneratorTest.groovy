package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

final class UpdateCatalogGeneratorTest {
    private final File repository = new File('../..').canonicalFile
    private final Map catalog = CatalogTools.loadCatalog(repository)

    @Test
    void partialReleaseAssociatesOnlyExactPublishedTargetArtifact() {
        List<Map> inventory = parse([
                release('1.0.0-beta.3', ['fabric-1.21.1'])
        ])

        assertEquals(1, inventory.size())
        assertEquals(['fabric-1.21.1'], inventory.first().targetIds)
        assertTrue(inventory.first().assets.contains(
                artifact('fabric-1.21.1', '1.0.0-beta.3').name))
    }

    @Test
    void finalBackfillInventoryAddsExactTargetsWithoutInferringSiblings() {
        List<Map> inventory = parse([
                release('1.0.0-beta.3', ['fabric-1.21.1', 'neoforge-26.2'])
        ])

        assertEquals(['fabric-1.21.1', 'neoforge-26.2'], inventory.first().targetIds)
        assertFalse(inventory.first().targetIds.contains('neoforge-1.21.1'))
    }

    @Test
    void draftsAreIgnoredAndExperimentalArtifactsNeverCreateAssociations() {
        Map visible = release('1.0.0-beta.3', ['fabric-1.21.1', 'fabric-26.3'])
        Map draft = release('1.0.0-beta.4', ['fabric-26.2'])
        draft.draft = true

        List<Map> inventory = parse([draft, visible])

        assertEquals(1, inventory.size())
        assertEquals('1.0.0-beta.3', inventory.first().version)
        assertEquals(['fabric-1.21.1'], inventory.first().targetIds)
        assertTrue(inventory.first().assets.contains(
                artifact('fabric-26.3', '1.0.0-beta.3').name))
    }

    @Test
    void currentExperimentalTargetCreatesNoUpdateCatalogOrEndpoint() {
        List<Map> inventory = parse([
                release('1.0.0-beta.3', ['fabric-1.21.1', 'fabric-26.3'])
        ])

        Map<String, String> site = UpdateCatalogSite.files(catalog, inventory)
        Map common = new groovy.json.JsonSlurper().parseText(
                site['updates/v1/catalog.json']) as Map

        assertFalse((common.targets as Map).containsKey('fabric-26.3'))
        assertFalse(site.containsKey('updates/v1/native/fabric-26.3.json'))
        assertFalse(site.keySet().any { String path -> path.contains('fabric-26.3') })
    }

    @Test
    void duplicateVersionsAssetsAndConflictingUrlsFailClosed() {
        assertThrows(IllegalArgumentException) {
            parse([release('1.0.0-beta.3', ['fabric-26.2']),
                   release('1.0.0-beta.3', ['neoforge-26.2'])])
        }

        Map duplicateAsset = release('1.0.0-beta.3', ['fabric-26.2'])
        duplicateAsset.assets.add(new LinkedHashMap(duplicateAsset.assets.first() as Map))
        assertThrows(IllegalArgumentException) { parse([duplicateAsset]) }

        Map wrongAssetUrl = release('1.0.0-beta.3', ['fabric-26.2'])
        wrongAssetUrl.assets.first().browser_download_url =
                'https://example.com/untrusted.jar'
        assertThrows(IllegalArgumentException) { parse([wrongAssetUrl]) }

        Map wrongReleaseUrl = release('1.0.0-beta.3', ['fabric-26.2'])
        wrongReleaseUrl.html_url =
                'https://github.com/NaoCraftLab/nclskins/releases/tag/1.0.0-beta.2'
        assertThrows(IllegalArgumentException) { parse([wrongReleaseUrl]) }
    }

    @Test
    void countAndShapeLimitsAreEnforced() {
        List<Map> tooMany = (1..101).collect { int number ->
            release("1.0.${number}", [])
        }
        assertThrows(IllegalArgumentException) { parse(tooMany) }
        assertThrows(IllegalArgumentException) {
            GithubReleaseInventory.parse(catalog, new byte[GithubReleaseInventory.MAX_BYTES + 1])
        }
        assertThrows(IllegalArgumentException) {
            parse([[tag_name: '1.0.0', draft: 'false', html_url: 'x', assets: []]])
        }
    }

    @Test
    void catalogJsonIsCanonicalDeterministicAndSemverSorted() {
        Map stableRelease = release('1.0.0', ['forge-1.20.1'])
        Map betaRelease = release('1.0.0-beta.10', ['fabric-1.21.1'])
        Map alphaRelease = release('1.0.0-alpha.2', ['fabric-1.21.1'])
        List<Map> forward = parse([stableRelease, betaRelease, alphaRelease])
        List<Map> reverse = parse([alphaRelease, betaRelease, stableRelease])

        String forwardJson = UpdateCatalogSite.catalogJson(catalog, forward)
        String reverseJson = UpdateCatalogSite.catalogJson(catalog, reverse)

        assertEquals(forwardJson, reverseJson)
        Map generated = new groovy.json.JsonSlurper().parseText(forwardJson) as Map
        assertEquals(['1.0.0-alpha.2', '1.0.0-beta.10', '1.0.0'],
                generated.releases.keySet().toList())
        assertEquals(['alpha', 'beta', 'release'],
                generated.releases.values()*.channel)
        assertEquals(['1.0.0-alpha.2', '1.0.0-beta.10'],
                generated.targets.'fabric-1.21.1'.versions)
        assertTrue((generated.targets as Map).containsKey('neoforge-26.2'))
        assertEquals([], generated.targets.'neoforge-26.2'.versions)
        assertFalse(forwardJson.contains('generated'))
        assertFalse(forwardJson.contains(repository.absolutePath))
    }

    @Test
    void catalogJsonHasByteExactCanonicalShape() {
        Map miniCatalog = [targets: [[
                id             : 'fabric-1.21.1',
                loader         : [id: 'fabric'],
                minecraft      : [version: '1.21.1'],
                releaseEligible: true
        ]]]
        miniCatalog.mod = [contact: [homepage: 'https://naocraftlab.com']]
        List<Map> inventory = [[
                version  : '1.2.3-beta.4',
                url      : 'https://github.com/NaoCraftLab/nclskins/releases/tag/1.2.3-beta.4',
                targetIds: ['fabric-1.21.1']
        ]]

        assertEquals('''{
    "project": "nclskins",
    "releases": {
        "1.2.3-beta.4": {
            "channel": "beta",
            "url": "https://github.com/NaoCraftLab/nclskins/releases/tag/1.2.3-beta.4"
        }
    },
    "schemaVersion": 1,
    "targets": {
        "fabric-1.21.1": {
            "loader": "fabric",
            "minecraftVersion": "1.21.1",
            "versions": [
                "1.2.3-beta.4"
            ]
        }
    }
}
''', UpdateCatalogSite.catalogJson(miniCatalog, inventory))
    }

    @Test
    void nativeCatalogSupportsEveryExactRuntimeAliasWithoutChangingTargetIdentity() {
        String targetId = 'neoforge-26.1'
        String version = '1.0.0-beta.3'
        String releaseUrl =
                "https://github.com/NaoCraftLab/nclskins/releases/tag/${version}"
        Map generated = UpdateCatalogSite.nativeCatalog(
                catalog, parse([release(version, [targetId])]), targetId)

        assertEquals(['26.1', '26.1.1', '26.1.2', 'homepage', 'promos'],
                generated.keySet().toList())
        ['26.1', '26.1.1', '26.1.2'].each { String runtimeVersion ->
            assertEquals([(version): releaseUrl], generated[runtimeVersion])
            assertEquals(version, generated.promos["${runtimeVersion}-latest".toString()])
            assertEquals(version, generated.promos["${runtimeVersion}-recommended".toString()])
        }
        assertFalse(generated.containsKey('neoforge-26.1'))
        assertEquals(releaseUrl, generated.homepage)
    }

    @Test
    void everyReleaseEligibleForgeFamilyTargetHasExactNativeSchema() {
        List<Map> nativeTargets = CatalogTools.releaseTargets(catalog).findAll { Map target ->
            ['forge', 'neoforge'].contains((target.loader as Map).id.toString())
        }
        assertEquals([
                'forge-1.20.1',
                'neoforge-1.21.1',
                'neoforge-1.21.11',
                'neoforge-26.1',
                'neoforge-26.2'
        ], nativeTargets*.id.sort())

        nativeTargets.each { Map target ->
            String version = target.id == 'neoforge-26.2' ? '1.1.0-beta.2' : '1.0.0-alpha.3'
            List<Map> inventory = parse([release(version, [target.id.toString()])])
            Map nativeCatalog = UpdateCatalogSite.nativeCatalog(
                    catalog, inventory, target.id.toString())
            List<String> runtimeVersions = UpdateCatalogSite.nativeRuntimeVersions(target)
            String releaseUrl =
                    "https://github.com/NaoCraftLab/nclskins/releases/tag/${version}"

            assertEquals((runtimeVersions + ['homepage', 'promos']).sort(),
                    nativeCatalog.keySet().toList())
            assertEquals(releaseUrl, nativeCatalog.homepage)
            runtimeVersions.each { String runtimeVersion ->
                assertEquals([(version): releaseUrl], nativeCatalog[runtimeVersion])
                assertEquals(version,
                        nativeCatalog.promos["${runtimeVersion}-latest".toString()])
                assertEquals(version,
                        nativeCatalog.promos["${runtimeVersion}-recommended".toString()])
            }
            assertEquals(runtimeVersions.size() * 2, nativeCatalog.promos.size())
        }
    }

    @Test
    void nativeRuntimeAliasesFailClosedOnMalformedOrContradictoryCatalogData() {
        Map duplicate = CatalogTools.materialize(catalog) as Map
        CatalogTools.selectTarget(duplicate, 'neoforge-26.1')
                .compatibility.minecraftVersions = ['26.1', '26.1']
        assertThrows(IllegalArgumentException) {
            UpdateCatalogSite.nativeCatalog(duplicate, [], 'neoforge-26.1')
        }

        Map malformed = CatalogTools.materialize(catalog) as Map
        CatalogTools.selectTarget(malformed, 'neoforge-26.1')
                .compatibility.minecraftVersions = ['26.1', 'latest']
        assertThrows(IllegalArgumentException) {
            UpdateCatalogSite.nativeCatalog(malformed, [], 'neoforge-26.1')
        }

        Map wrongBaseline = CatalogTools.materialize(catalog) as Map
        CatalogTools.selectTarget(wrongBaseline, 'neoforge-26.1')
                .compatibility.minecraftVersions = ['26.1.1', '26.1.2']
        assertThrows(IllegalArgumentException) {
            UpdateCatalogSite.nativeCatalog(wrongBaseline, [], 'neoforge-26.1')
        }

        Map wrongIdentity = CatalogTools.materialize(catalog) as Map
        CatalogTools.selectTarget(wrongIdentity, 'neoforge-26.1').minecraft.version = '26.1.1'
        assertThrows(IllegalArgumentException) {
            UpdateCatalogSite.nativeCatalog(wrongIdentity, [], 'neoforge-26.1')
        }
    }

    @Test
    void nativeCatalogUsesOnlyExactTargetAndRemainsValidWhenEmpty() {
        List<Map> inventory = parse([
                release('1.0.0-alpha.2', ['neoforge-1.21.1']),
                release('1.0.0-beta.1', ['neoforge-26.2']),
                release('1.0.0', ['forge-1.20.1'])
        ])

        Map exact = UpdateCatalogSite.nativeCatalog(catalog, inventory, 'neoforge-26.2')
        assertEquals(['1.0.0-beta.1'], exact.'26.2'.keySet().toList())
        assertEquals('1.0.0-beta.1', exact.promos.'26.2-latest')
        assertEquals(exact.promos.'26.2-latest', exact.promos.'26.2-recommended')
        assertEquals(
                'https://github.com/NaoCraftLab/nclskins/releases/tag/1.0.0-beta.1',
                exact.homepage)

        Map empty = UpdateCatalogSite.nativeCatalog(catalog, inventory, 'neoforge-26.1')
        assertEquals([:], empty.'26.1')
        assertEquals([:], empty.'26.1.1')
        assertEquals([:], empty.'26.1.2')
        assertFalse(empty.containsKey('26.1.3'))
        assertEquals([:], empty.promos)
        assertEquals(catalog.mod.contact.homepage, empty.homepage)
        assertThrows(IllegalArgumentException) {
            UpdateCatalogSite.nativeCatalog(catalog, inventory, 'fabric-1.21.1')
        }
    }

    @Test
    void taskBuildsTrackedFixtureSiteAndRejectsUnsafeOutput(@TempDir Path temporary) {
        def project = ProjectBuilder.builder().withProjectDir(temporary.toFile()).build()
        Path allowedRoot = temporary.resolve('build')
        Path output = allowedRoot.resolve('site')
        GenerateUpdateCatalogTask task = project.tasks.create(
                'generateFixtureSite', GenerateUpdateCatalogTask)
        task.catalogFile.set(new File(repository, 'gradle/targets.json'))
        task.inventoryFile.set(new File(repository,
                'gradle/build-logic/src/test/resources/update-catalog/releases.json'))
        task.allowedOutputRoot.set(allowedRoot.toFile())
        task.outputDirectory.set(output.toFile())

        task.generate()

        List<String> files = Files.walk(output).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) }
                    .map { output.relativize(it).toString() }
                    .sorted()
                    .toList()
        }
        assertEquals([
                '.nojekyll',
                'updates/v1/catalog.json',
                'updates/v1/native/forge-1.20.1.json',
                'updates/v1/native/neoforge-1.21.1.json',
                'updates/v1/native/neoforge-1.21.11.json',
                'updates/v1/native/neoforge-26.1.json',
                'updates/v1/native/neoforge-26.2.json'
        ], files)
        assertTrue(Files.readString(output.resolve('updates/v1/catalog.json'))
                .contains('"fabric-1.21.1"'))

        GenerateUpdateCatalogTask unsafe = project.tasks.create(
                'generateUnsafeSite', GenerateUpdateCatalogTask)
        unsafe.catalogFile.set(task.catalogFile)
        unsafe.inventoryFile.set(task.inventoryFile)
        unsafe.allowedOutputRoot.set(allowedRoot.toFile())
        unsafe.outputDirectory.set(temporary.resolve('outside').toFile())
        assertThrows(IllegalArgumentException) { unsafe.generate() }

        Path forbidden = temporary.resolve('openspec/releases.json')
        Files.createDirectories(forbidden.parent)
        Files.writeString(forbidden, '[]')
        assertThrows(IllegalArgumentException) {
            GenerateUpdateCatalogTask.validateInput(forbidden, 'inventory')
        }
    }

    @Test
    void currentCatalogWinsOverHistoricalTagCatalogAndTagMoveAloneChangesNothing() {
        Map historical = fixtureCatalog('catalog-historical.json')
        Map current = fixtureCatalog('catalog-current.json')
        List<Map> historicalInventory = fixtureInventory(
                historical, 'release-before-backfill.json')
        List<Map> currentInventory = fixtureInventory(
                current, 'release-before-backfill.json')
        List<Map> movedTagInventory = fixtureInventory(
                current, 'release-after-tag-move.json')

        Map<String, String> historicalSite = UpdateCatalogSite.files(
                historical, historicalInventory)
        Map<String, String> currentSite = UpdateCatalogSite.files(current, currentInventory)

        assertFalse(historicalSite.containsKey(
                'updates/v1/native/neoforge-26.2.json'))
        assertTrue(currentSite.containsKey('updates/v1/native/neoforge-26.2.json'))
        Map currentCommon = new groovy.json.JsonSlurper().parseText(
                currentSite['updates/v1/catalog.json']) as Map
        assertEquals(['fabric-1.21.1', 'forge-1.20.1', 'neoforge-26.2'],
                currentCommon.targets.keySet().toList())
        assertEquals([], currentCommon.targets.'neoforge-26.2'.versions)
        assertEquals(currentSite, UpdateCatalogSite.files(current, movedTagInventory))
    }

    @Test
    void backfillAddsOnlyExactAssociationToExistingRelease() {
        Map current = fixtureCatalog('catalog-current.json')
        Map<String, String> before = UpdateCatalogSite.files(current,
                fixtureInventory(current, 'release-before-backfill.json'))
        Map<String, String> after = UpdateCatalogSite.files(current,
                fixtureInventory(current, 'release-after-backfill.json'))
        Map beforeCommon = new groovy.json.JsonSlurper().parseText(
                before['updates/v1/catalog.json']) as Map
        Map afterCommon = new groovy.json.JsonSlurper().parseText(
                after['updates/v1/catalog.json']) as Map

        assertEquals(1, afterCommon.releases.size())
        assertEquals(beforeCommon.releases, afterCommon.releases)
        assertEquals(beforeCommon.targets.'fabric-1.21.1'.versions,
                afterCommon.targets.'fabric-1.21.1'.versions)
        assertEquals([], beforeCommon.targets.'neoforge-26.2'.versions)
        assertEquals(['1.0.0-beta.3'], afterCommon.targets.'neoforge-26.2'.versions)
        assertEquals([], afterCommon.targets.'forge-1.20.1'.versions)
        Map nativeAfter = new groovy.json.JsonSlurper().parseText(
                after['updates/v1/native/neoforge-26.2.json']) as Map
        assertEquals('1.0.0-beta.3', nativeAfter.promos.'26.2-latest')
    }

    @Test
    void multiRuntimeBackfillUpdatesEveryAliasWithoutChangingSiblingTargets() {
        String version = '1.0.0-beta.3'
        List<Map> beforeInventory = parse([
                release(version, ['fabric-26.1'])
        ])
        List<Map> afterInventory = parse([
                release(version, ['fabric-26.1', 'neoforge-26.1'])
        ])

        Map before = UpdateCatalogSite.nativeCatalog(
                catalog, beforeInventory, 'neoforge-26.1')
        Map after = UpdateCatalogSite.nativeCatalog(
                catalog, afterInventory, 'neoforge-26.1')
        Map siblingBefore = UpdateCatalogSite.nativeCatalog(
                catalog, beforeInventory, 'neoforge-26.2')
        Map siblingAfter = UpdateCatalogSite.nativeCatalog(
                catalog, afterInventory, 'neoforge-26.2')

        ['26.1', '26.1.1', '26.1.2'].each { String runtimeVersion ->
            assertEquals([:], before[runtimeVersion])
            assertEquals([version], after[runtimeVersion].keySet().toList())
            assertEquals(version, after.promos["${runtimeVersion}-latest".toString()])
            assertEquals(version,
                    after.promos["${runtimeVersion}-recommended".toString()])
        }
        assertEquals([:], before.promos)
        assertFalse(after.containsKey('26.1.3'))
        assertFalse(after.promos.containsKey('26.1.3-latest'))
        assertEquals(siblingBefore, siblingAfter)
    }

    @Test
    void multiRuntimeNativeJsonRemainsByteExactAcrossInventoryOrder() {
        Map alpha = release('1.0.0-alpha.2', ['neoforge-26.1'])
        Map beta = release('1.0.0-beta.3', ['neoforge-26.1'])
        List<Map> forward = parse([alpha, beta])
        List<Map> reverse = parse([beta, alpha])

        String forwardJson = UpdateCatalogSite.nativeJson(
                catalog, forward, 'neoforge-26.1')
        String reverseJson = UpdateCatalogSite.nativeJson(
                catalog, reverse, 'neoforge-26.1')

        assertEquals(forwardJson, reverseJson)
        assertTrue(forwardJson.contains('"26.1.1-latest": "1.0.0-beta.3"'))
        assertTrue(forwardJson.contains('"26.1.2-recommended": "1.0.0-beta.3"'))
    }

    @Test
    void channelMatrixNeverPromotesAcrossExactTargets() {
        Map current = fixtureCatalog('catalog-current.json')
        Map<String, String> site = UpdateCatalogSite.files(current,
                fixtureInventory(current, 'release-channel-matrix.json'))
        Map common = new groovy.json.JsonSlurper().parseText(
                site['updates/v1/catalog.json']) as Map

        assertEquals(['1.0.0-alpha.4', '1.0.0-beta.1', '1.0.0'],
                common.releases.keySet().toList())
        assertEquals(['1.0.0-alpha.4'], common.targets.'fabric-1.21.1'.versions)
        assertEquals(['1.0.0-beta.1'], common.targets.'neoforge-26.2'.versions)
        assertEquals(['1.0.0'], common.targets.'forge-1.20.1'.versions)

        Map neo = new groovy.json.JsonSlurper().parseText(
                site['updates/v1/native/neoforge-26.2.json']) as Map
        Map forge = new groovy.json.JsonSlurper().parseText(
                site['updates/v1/native/forge-1.20.1.json']) as Map
        assertEquals(['1.0.0-beta.1'], neo.'26.2'.keySet().toList())
        assertEquals('1.0.0-beta.1', neo.promos.'26.2-recommended')
        assertEquals(['1.0.0'], forge.'1.20.1'.keySet().toList())
        assertEquals('1.0.0', forge.promos.'1.20.1-recommended')
    }

    private List<Map> parse(List<Map> releases) {
        GithubReleaseInventory.parse(
                catalog, JsonOutput.toJson(releases).getBytes(StandardCharsets.UTF_8))
    }

    private Map release(String version, List<String> targetIds) {
        [
                tag_name: version,
                draft   : false,
                html_url: "https://github.com/NaoCraftLab/nclskins/releases/tag/${version}".toString(),
                assets  : targetIds.collect { String targetId -> artifact(targetId, version) }
        ]
    }

    private Map artifact(String targetId, String version) {
        Map target = CatalogTools.selectTarget(catalog, targetId)
        String name = target.artifact.file.toString().replace('{modVersion}', version)
        [
                name: name,
                browser_download_url:
                        "https://github.com/NaoCraftLab/nclskins/releases/download/${version}/" +
                                name.replace('+', '%2B')
        ]
    }

    private Map fixtureCatalog(String name) {
        CatalogTools.loadJson(new File(repository,
                "gradle/build-logic/src/test/resources/update-catalog/${name}"))
    }

    private List<Map> fixtureInventory(Map fixtureCatalog, String name) {
        GithubReleaseInventory.parse(fixtureCatalog, Files.readAllBytes(new File(repository,
                "gradle/build-logic/src/test/resources/update-catalog/${name}").toPath()))
    }
}

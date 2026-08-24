package com.naocraftlab.skins.buildlogic


import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.nio.file.Files

final class PublicationSupport {
    static Map loadManifest(File bundleDirectory) {
        File manifestFile = new File(bundleDirectory, 'release-manifest.json')
        if (!manifestFile.isFile() || java.nio.file.Files.isSymbolicLink(manifestFile.toPath())) {
            throw new IllegalStateException('release-manifest.json is missing or unsafe')
        }
        Object parsed = new JsonSlurper().parse(manifestFile)
        if (!(parsed instanceof Map) || parsed.schemaVersion != 3 ||
                !(parsed.mode in ['tag', 'backfill', 'reconcile-tag']) ||
                !(parsed.version instanceof String) ||
                !CatalogTools.VERSION_PATTERN.matcher(parsed.version as String).matches() ||
                !(parsed.channel in ['release', 'beta', 'alpha']) ||
                !(parsed.prerelease instanceof Boolean) ||
                !(parsed.targets instanceof List) || !(parsed.assets instanceof List) ||
                !(parsed.serverPlugin instanceof Map) ||
                !(parsed.platforms instanceof Map) || !(parsed.releaseNotes instanceof Map)) {
            throw new IllegalStateException('unsupported release manifest')
        }
        Map manifest = CatalogTools.materialize(parsed) as Map
        if (manifest.prerelease != (manifest.channel != 'release') ||
                manifest.targetCount != (manifest.targets as List).size() ||
                manifest.targetCount != (manifest.selectedTargetIds as List)?.size()) {
            throw new IllegalStateException('inconsistent release manifest classification or target count')
        }
        Set<String> names = [] as Set
        Map<String, Map> assets = [:]
        (manifest.assets as List).each { Object raw ->
            if (!(raw instanceof Map)) throw new IllegalStateException('invalid release asset')
            Map asset = raw as Map
            String fileName = asset.file?.toString()
            if (!safeFileName(fileName) || !names.add(fileName) ||
                    !(asset.kind in ['mod', 'mod-sources', 'server-plugin', 'server-plugin-sources']) ||
                    !(asset.size instanceof Number) ||
                    !(asset.sha1 ==~ /[0-9a-f]{40}/) || !(asset.sha256 ==~ /[0-9a-f]{64}/) ||
                    !(asset.sha512 ==~ /[0-9a-f]{128}/)) {
                throw new IllegalStateException("invalid or duplicate release asset: ${fileName}")
            }
            File file = new File(bundleDirectory, "assets/${fileName}")
            requireAsset(file, asset)
            assets[fileName] = asset
        }
        boolean publishServer = manifest.serverPlugin.publish == true
        int expectedAssets = (manifest.targets as List).size() + 1 + (publishServer ? 2 : 0)
        if (assets.size() != expectedAssets ||
                assets.values().count { it.kind == 'mod-sources' } != 1 ||
                assets.values().count { it.kind == 'server-plugin' } != (publishServer ? 1 : 0) ||
                assets.values().count { it.kind == 'server-plugin-sources' } != (publishServer ? 1 : 0)) {
            throw new IllegalStateException('release manifest contains an inconsistent component asset set')
        }
        Map sourcesAsset = assets.values().find { it.kind == 'mod-sources' } as Map
        File assetsDirectory = new File(bundleDirectory, 'assets')
        if (!assetsDirectory.isDirectory() || Files.isSymbolicLink(assetsDirectory.toPath()) ||
                (assetsDirectory.listFiles() as List<File>).any {
                    !it.isFile() || Files.isSymbolicLink(it.toPath()) || !assets.containsKey(it.name)
                } || assetsDirectory.listFiles().length != assets.size()) {
            throw new IllegalStateException('release asset directory contains missing or unexpected files')
        }
        Set<String> targetIds = [] as Set
        Set<String> targetHashes = [] as Set
        (manifest.targets as List).each { Object raw ->
            if (!(raw instanceof Map)) throw new IllegalStateException('invalid publication target')
            Map target = raw as Map
            Map asset = target.asset instanceof Map ? target.asset as Map : [:]
            String fileName = asset.file?.toString()
            if (!(target.id instanceof String) || !targetIds.add(target.id.toString()) ||
                    !(target.minecraftVersion instanceof String) ||
                    target.name != publicationName(manifest.version.toString(),
                            target.minecraftVersion.toString(), target.loader?.toString()) ||
                    target.versionNumber != manifest.version || target.channel != manifest.channel ||
                    !(target.loader in ['fabric', 'forge', 'neoforge']) ||
                    !(target.javaRelease instanceof Number) ||
                    (target.javaRelease as Number).intValue() <= 0 ||
                    (target.javaRelease as Number).doubleValue() !=
                            (target.javaRelease as Number).intValue() ||
                    !(target.gameVersions instanceof List) || (target.gameVersions as List).isEmpty() ||
                    (target.gameVersions as List).first() != target.minecraftVersion ||
                    assets[fileName] != asset || asset.kind != 'mod' || asset.target != target.id ||
                    target.sourcesAsset != sourcesAsset ||
                    !targetHashes.add(asset.sha512.toString())) {
                throw new IllegalStateException("invalid publication target: ${target.id}")
            }
        }
        if (targetIds != (manifest.selectedTargetIds as List).collect { it.toString() } as Set) {
            throw new IllegalStateException('selected target IDs differ from publication targets')
        }
        validateServerPlugin(manifest, assets)
        Map notes = manifest.releaseNotes as Map
        File notesFile = new File(bundleDirectory, notes.file?.toString() ?: '')
        if (notes.file != 'release-notes.md' || !notesFile.isFile() ||
                Files.isSymbolicLink(notesFile.toPath()) || !(notes.text instanceof String) ||
                ReleaseBundle.sha256(notesFile) != notes.sha256 ||
                notesFile.getText(StandardCharsets.UTF_8.name()) != notes.text) {
            throw new IllegalStateException('release notes are missing, unsafe, or inconsistent')
        }
        manifest
    }

    static void validateServerPlugin(Map manifest, Map<String, Map> assets) {
        Map server = manifest.serverPlugin as Map
        if (!(server.publish instanceof Boolean) ||
                !(server.reason in ['initial', 'server-change', 'stable-promotion', 'unchanged']) ||
                !(server.activeVersion instanceof String) ||
                !(server.currentFingerprint ==~ /[0-9a-f]{64}/) ||
                !(server.activeFingerprint ==~ /[0-9a-f]{64}/) ||
                !(server.protocolIds instanceof List) || !(server.matrixId instanceof String) ||
                !(server.publications instanceof Map)) {
            throw new IllegalStateException('invalid server plugin release state')
        }
        if (server.publish != true) {
            if (server.artifact != null || server.sourcesArtifact != null || server.publication != null) {
                throw new IllegalStateException('unchanged server plugin must not contain artifacts')
            }
            return
        }
        Map publication = server.publication instanceof Map ? server.publication as Map : [:]
        Map artifact = server.artifact instanceof Map ? server.artifact as Map : [:]
        Map sources = server.sourcesArtifact instanceof Map ? server.sourcesArtifact as Map : [:]
        if (publication.id != 'server-plugin' || publication.kind != 'server-plugin' ||
                publication.name != "${manifest.version}+universal" ||
                publication.versionNumber != manifest.version ||
                publication.channel != manifest.channel ||
                publication.environment != 'server' ||
                !(publication.loaders instanceof List) || (publication.loaders as List).isEmpty() ||
                (publication.loaders as List).size() != (publication.loaders as List).toSet().size() ||
                !(publication.loaders as List).every {
                    it in ['bukkit', 'spigot', 'paper', 'purpur', 'folia',
                           'velocity', 'bungeecord']
                } ||
                !(publication.gameVersions instanceof List) ||
                (publication.gameVersions as List).isEmpty() ||
                (publication.gameVersions as List).size() !=
                        (publication.gameVersions as List).toSet().size() ||
                !(publication.gameVersions as List).every {
                    it instanceof String && !it.isBlank()
                } ||
                publication.javaRelease != 17 ||
                !(publication.javaReleases instanceof List) ||
                (publication.javaReleases as List).isEmpty() ||
                !(publication.javaReleases as List).every { it instanceof Number } ||
                (publication.javaReleases as List).collect { (it as Number).intValue() } !=
                        (publication.javaReleases as List).collect {
                            (it as Number).intValue()
                        }.toSet().sort() ||
                !(publication.javaReleases as List).collect {
                    (it as Number).intValue()
                }.contains((publication.javaRelease as Number).intValue()) ||
                publication.asset != artifact || publication.sourcesAsset != sources ||
                assets[artifact.file?.toString()] != artifact || artifact.kind != 'server-plugin' ||
                assets[sources.file?.toString()] != sources || sources.kind != 'server-plugin-sources' ||
                !(publication.platforms instanceof Map) ||
                publication.platforms.modrinth.projectId == null ||
                publication.platforms.curseforge.projectId == null) {
            throw new IllegalStateException('invalid server plugin publication')
        }
    }

    static void requireAsset(File file, Map metadata) {
        if (!file.isFile() || java.nio.file.Files.isSymbolicLink(file.toPath())) {
            throw new IllegalStateException("release asset is missing or unsafe: ${file.name}")
        }
        if (file.length() != (metadata.size as Number).longValue() ||
                ReleaseBundle.sha256(file) != metadata.sha256 ||
                ReleaseBundle.sha512(file) != metadata.sha512 ||
                ReleaseBundle.sha1(file) != metadata.sha1) {
            throw new IllegalStateException("release asset checksum mismatch: ${file.name}")
        }
    }

    static Map classify(String platform, Map desired, List<Map> remoteEntries) {
        List<Map> coordinates = remoteEntries.findAll { Map remote ->
            normalizedName(platform, remote) == desired.name &&
                    normalizedChannel(platform, remote) == desired.channel
        }
        if (coordinates.size() > 1) {
            return [action: 'conflict', reason: 'multiple remote entries use the publication coordinate']
        }
        if (coordinates.size() == 1) {
            Map coordinate = coordinates.first()
            List<String> mismatches = metadataMismatches(platform, desired, coordinate)
            if (!mismatches.isEmpty()) {
                Set<String> mutable = platform == 'modrinth'
                        ? ['game versions differ', 'loader differs'] as Set<String>
                        : ['game versions differ'] as Set<String>
                boolean mutableOnly = mutable.containsAll(mismatches as Set<String>)
                boolean kindAllowsChanges = desired.kind == 'server-plugin' ||
                        (mismatches as Set<String>) ==
                                (['game versions differ'] as Set<String>)
                if (mutableOnly && kindAllowsChanges) {
                    return [action: 'update-metadata', reason: mismatches.join('; '),
                            remoteId: normalizedId(coordinate)]
                }
                return [action: 'conflict', reason: mismatches.join('; '), remoteId: normalizedId(coordinate)]
            }
            return [action: 'skip', reason: 'exact publication exists', remoteId: normalizedId(coordinate)]
        }

        Set<String> desiredGames = desired.gameVersions as Set<String>
        List<Map> overlapping = remoteEntries.findAll { Map remote ->
                    normalizedChannel(platform, remote) == desired.channel &&
                    normalizedVersion(platform, remote) == desired.versionNumber &&
                    !normalizedGames(platform, remote).intersect(desiredGames).isEmpty() &&
                    loadersOverlap(platform, remote, desired)
        }
        overlapping.isEmpty()
                ? [action: 'upload', reason: 'publication is missing']
                : [action: 'conflict', reason: 'overlapping publication uses another coordinate']
    }

    static List<String> metadataMismatches(String platform, Map desired, Map remote) {
        List<String> mismatches = []
        if (normalizedVersion(platform, remote) != desired.versionNumber) {
            mismatches.add('version number differs')
        }
        if (normalizedGames(platform, remote) != (desired.gameVersions as Set<String>)) {
            mismatches.add('game versions differ')
        }
        if (normalizedLoaders(platform, remote) != expectedRemoteLoaders(platform, desired)) {
            mismatches.add('loader differs')
        }
        if (platform == 'modrinth' && remote.environment?.toString() !=
                (desired.kind == 'server-plugin' ? 'server_only' : 'client_only_server_optional')) {
            mismatches.add('environment differs')
        }
        if (normalizedDependencies(platform, remote) != desiredDependencies(platform, desired)) {
            mismatches.add('dependencies differ')
        }
        String actualFile = normalizedFileName(platform, remote)
        if (actualFile != desired.asset.file) mismatches.add('filename differs')
        String actualHash = normalizedHash(platform, remote)
        String expectedHash = platform == 'modrinth' ? desired.asset.sha512 : desired.asset.sha1
        if (actualHash == null || actualHash != expectedHash) mismatches.add('file hash differs')
        if (platform == 'modrinth') {
            List<Map> files = remote.files instanceof List
                    ? (remote.files as List).findAll { it instanceof Map } as List<Map> : []
            Map sourcesAsset = desired.sourcesAsset instanceof Map ? desired.sourcesAsset as Map : [:]
            Set<String> expectedFiles = [desired.asset.file?.toString(), sourcesAsset.file?.toString()] as Set<String>
            Set<String> actualFiles = files.collect { it.filename?.toString() } as Set<String>
            if (files.size() != 2 || actualFiles != expectedFiles) mismatches.add('file set differs')
            List<Map> sourceFiles = files.findAll { it.file_type == 'sources-jar' }
            if (sourceFiles.size() != 1 || sourceFiles.first().filename != sourcesAsset.file) {
                mismatches.add('sources filename or type differs')
            } else if (((sourceFiles.first().hashes as Map)?.sha512?.toString()) != sourcesAsset.sha512) {
                mismatches.add('sources file hash differs')
            }
        }
        mismatches
    }

    static Map modrinthMetadata(Map manifest, Map target) {
        [
                name          : target.name,
                version_number: target.versionNumber,
                changelog     : manifest.releaseNotes.text,
                dependencies  : (target.dependencies.modrinth as List).collect {
                    [project_id: it.projectId, dependency_type: it.type]
                },
                game_versions : target.gameVersions,
                version_type  : target.channel,
                loaders       : desiredLoaders(target) as List,
                featured      : false,
                status        : 'listed',
                project_id    : manifest.platforms.modrinth.projectId,
                file_parts    : ['file', 'sources'],
                primary_file  : 'file',
                file_types    : [sources: 'sources-jar'],
                environment   : target.kind == 'server-plugin'
                        ? 'server_only' : 'client_only_server_optional'
        ]
    }

    static Map curseForgeMetadata(Map manifest, Map target) {
        List<String> gameVersionNames = new ArrayList<>(target.gameVersions as List)
        if (target.kind == 'server-plugin') {
            gameVersionNames.add('Server')
        } else {
            gameVersionNames.add(loaderDisplayName(target.loader.toString()))
            gameVersionNames.addAll(['Client', 'Server'])
        }
        desiredCurseForgeJavaReleases(target).sort().each { int release ->
            gameVersionNames.add("Java ${release}".toString())
        }
        Map metadata = [
                changelog               : manifest.releaseNotes.text,
                changelogType           : 'markdown',
                displayName             : target.name,
                gameVersionNames        : gameVersionNames,
                releaseType             : target.channel,
                isMarkedForManualRelease: false
        ]
        List<Map> relations = (target.dependencies.curseforge as List).collect {
            [slug: it.slug, projectID: (it.projectId as Number).intValue(),
             type: it.type == 'required' ? 'requiredDependency' : 'optionalDependency']
        }
        if (!relations.isEmpty()) metadata.relations = [projects: relations]
        metadata
    }

    static byte[] multipart(List<Map> parts, String boundary) {
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        parts.each { Map part ->
            output.write("--${boundary}\r\n".getBytes(StandardCharsets.US_ASCII))
            String disposition = "Content-Disposition: form-data; name=\"${quote(part.name)}\""
            if (part.filename != null) disposition += "; filename=\"${quote(part.filename)}\""
            output.write("${disposition}\r\n".getBytes(StandardCharsets.UTF_8))
            output.write("Content-Type: ${part.contentType}\r\n\r\n".getBytes(StandardCharsets.US_ASCII))
            output.write(part.bytes as byte[])
            output.write('\r\n'.getBytes(StandardCharsets.US_ASCII))
        }
        output.write("--${boundary}--\r\n".getBytes(StandardCharsets.US_ASCII))
        output.toByteArray()
    }

    static Set<Map> desiredDependencies(String platform, Map desired) {
        (desired.dependencies[platform] as List).collect { Map dependency ->
            [id: dependency.projectId.toString(), type: dependency.type.toString()]
        } as Set<Map>
    }

    static Set<Map> normalizedDependencies(String platform, Map remote) {
        if (platform == 'modrinth') {
            if (!(remote.dependencies instanceof List)) return [] as Set<Map>
            return (remote.dependencies as List).findAll { it instanceof Map && it.project_id != null }
                    .collect { [id: it.project_id.toString(), type: it.dependency_type.toString()] } as Set<Map>
        }
        if (!(remote.dependencies instanceof List)) return [] as Set<Map>
        (remote.dependencies as List).findAll { it instanceof Map && it.modId != null }
                .collect {
                    int relation = (it.relationType as Number).intValue()
                    [id: it.modId.toString(), type: relation == 3 ? 'required' : relation == 2 ? 'optional' : "other-${relation}"]
                } as Set<Map>
    }

    static String normalizedName(String platform, Map remote) {
        (platform == 'modrinth' ? remote.name : remote.displayName)?.toString() ?: ''
    }

    static String normalizedVersion(String platform, Map remote) {
        (platform == 'modrinth' ? remote.version_number : versionFromName(normalizedName(platform, remote)))?.toString()
    }

    static String versionFromName(String name) {
        int separator = name.indexOf('+')
        if (separator >= 0) return name.substring(0, separator)
        int space = name.lastIndexOf(' ')
        String trailing = space < 0 ? name : name.substring(space + 1)
        CatalogTools.VERSION_PATTERN.matcher(trailing).matches() ? trailing : name
    }

    static String normalizedChannel(String platform, Map remote) {
        Object raw = platform == 'modrinth' ? remote.version_type : remote.releaseType
        if (platform == 'curseforge' && raw instanceof Number) {
            return [1: 'release', 2: 'beta', 3: 'alpha'][(raw as Number).intValue()]
        }
        raw?.toString()?.toLowerCase(Locale.ROOT)
    }

    static Set<String> normalizedGames(String platform, Map remote) {
        Object raw = platform == 'modrinth' ? remote.game_versions : remote.gameVersions
        if (!(raw instanceof List)) return [] as Set<String>
        Set<String> values = (raw as List).collect { it.toString() } as Set<String>
        if (platform == 'curseforge') {
            values = values.findAll {
                String normalized = it.toLowerCase(Locale.ROOT)
                !(normalized in ['fabric', 'forge', 'neoforge', 'paper', 'purpur',
                                  'velocity', 'bungeecord', 'client', 'server']) &&
                        !(normalized ==~ /java\s+[0-9]+/)
            } as Set<String>
        }
        values
    }

    static Set<Integer> desiredJavaReleases(Map desired) {
        desired.javaReleases instanceof List
                ? (desired.javaReleases as List).collect { (it as Number).intValue() } as Set<Integer>
                : [(desired.javaRelease as Number).intValue()] as Set<Integer>
    }

    static Set<Integer> desiredCurseForgeJavaReleases(Map desired) {
        desired.kind == 'server-plugin'
                ? [(desired.javaRelease as Number).intValue()] as Set<Integer>
                : desiredJavaReleases(desired)
    }

    static String publicationName(String version, String minecraftVersion, String loader) {
        "${version}+${minecraftVersion}-${loader}".toString()
    }

    static Set<String> normalizedLoaders(String platform, Map remote) {
        if (platform == 'modrinth') {
            return remote.loaders instanceof List
                    ? (remote.loaders as List).collect { it.toString() } as Set<String> : [] as Set<String>
        }
        Set<String> values = remote.gameVersions instanceof List
                ? (remote.gameVersions as List).collect { it.toString().toLowerCase(Locale.ROOT) } as Set<String>
                : [] as Set<String>
        ['fabric', 'forge', 'neoforge', 'paper', 'purpur', 'velocity', 'bungeecord']
                .findAll { values.contains(it) } as Set<String>
    }

    static Set<String> desiredLoaders(Map desired) {
        desired.loaders instanceof List
                ? (desired.loaders as List).collect { it.toString() } as Set<String>
                : [desired.loader.toString()] as Set<String>
    }

    static Set<String> expectedRemoteLoaders(String platform, Map desired) {
        platform == 'curseforge' && desired.kind == 'server-plugin'
                ? [] as Set<String> : desiredLoaders(desired)
    }

    static boolean loadersOverlap(String platform, Map remote, Map desired) {
        Set<String> expected = expectedRemoteLoaders(platform, desired)
        expected.isEmpty() || !normalizedLoaders(platform, remote).intersect(expected).isEmpty()
    }

    static String normalizedFileName(String platform, Map remote) {
        if (platform == 'curseforge') return remote.fileName?.toString()
        if (!(remote.files instanceof List)) return null
        List<Map> primary = (remote.files as List).findAll { it instanceof Map && it.primary == true } as List<Map>
        primary.size() == 1 ? primary.first().filename?.toString() : null
    }

    static String normalizedHash(String platform, Map remote) {
        if (platform == 'modrinth') {
            if (!(remote.files instanceof List)) return null
            List<Map> primary = (remote.files as List).findAll {
                it instanceof Map && it.primary == true
            } as List<Map>
            return primary.size() == 1
                    ? ((primary.first().hashes as Map)?.sha512?.toString()) : null
        }
        if (!(remote.hashes instanceof List)) return null
        Map sha1 = (remote.hashes as List).find { it instanceof Map && (it.algo as Number)?.intValue() == 1 } as Map
        sha1?.value?.toString()
    }

    static String normalizedId(Map remote) {
        remote.id?.toString()
    }

    static String loaderDisplayName(String loader) {
        [fabric: 'Fabric', forge: 'Forge', neoforge: 'NeoForge'][loader]
    }

    static boolean safeFileName(String name) {
        name != null && !name.isBlank() && !name.contains('/') && !name.contains('\\') &&
                !name.startsWith('.') && name.endsWith('.jar')
    }

    private static String quote(Object value) {
        value.toString().replace('\\', '\\\\').replace('"', '\\"')
    }

    private PublicationSupport() {}
}

package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

abstract class PublishPlatformsTask extends DefaultTask {
    private static final String USER_AGENT =
            'NaoCraftLab/NCL-Skins-Publisher (https://github.com/NaoCraftLab/nclskins)'

    @InputDirectory
    abstract DirectoryProperty getBundleDirectory()

    private transient HttpClient client

    PublishPlatformsTask() {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    void publish() {
        File bundle = bundleDirectory.get().asFile
        Map manifest = PublicationSupport.loadManifest(bundle)
        String modrinthToken = requireSecret('MODRINTH_TOKEN')
        String curseForgeApiKey = requireSecret('CURSEFORGE_API_KEY')
        String curseForgeUploadToken = requireSecret('CURSEFORGE_UPLOAD_TOKEN')

        publishManifest(manifest, bundle, modrinthToken, curseForgeApiKey, curseForgeUploadToken)
    }

    void publishManifest(
            Map manifest,
            File bundle,
            String modrinthToken,
            String curseForgeApiKey,
            String curseForgeUploadToken) {

        List<Map> targets = publicationTargets(manifest)
        Map<String, Map<String, List<Map>>> remote = fetchAll(
                manifest, targets, modrinthToken, curseForgeApiKey)
        Map plan = classifyPerTarget(targets, remote)
        requireNoConflicts(plan)
        appendSummary('Publication preflight', plan)

        remote = fetchAll(manifest, targets, modrinthToken, curseForgeApiKey)
        Map recheck = classifyPerTarget(targets, remote)
        requireNoConflicts(recheck)
        List<String> disappeared = []
        ['modrinth', 'curseforge'].each { String platform ->
            targets.each { Map target ->
                if (plan[platform][target.id].action == 'skip' &&
                        recheck[platform][target.id].action != 'skip') {
                    disappeared.add("${platform} ${target.name}")
                }
            }
        }
        if (!disappeared.isEmpty()) {
            throw new IllegalStateException('Publications disappeared after preflight: ' + disappeared.join(', '))
        }

        List<List<String>> results = []
        ['modrinth', 'curseforge'].each { String platform ->
            targets.each { Map target ->
                Map state = recheck[platform][target.id] as Map
                if (state.action == 'skip') {
                    results.add([target.name.toString(), platform, 'skipped', state.remoteId?.toString() ?: ''])
                    return
                }
                Map targetManifest = manifestForTarget(manifest, target)
                if (state.action == 'update-metadata') {
                    String remoteId = platform == 'modrinth'
                            ? updateModrinthMetadata(target, state.remoteId.toString(), modrinthToken)
                            : updateCurseForgeMetadata(
                                    targetManifest, target, state.remoteId.toString(), curseForgeUploadToken)
                    results.add([target.name.toString(), platform, 'metadata updated', remoteId])
                    return
                }
                File artifact = new File(bundle, "assets/${target.asset.file}")
                String remoteId
                String result
                if (platform == 'modrinth') {
                    File sources = new File(bundle, "assets/${target.sourcesAsset.file}")
                    remoteId = uploadModrinth(targetManifest, target, artifact, sources, modrinthToken)
                    result = 'uploaded'
                } else {
                    remoteId = uploadCurseForge(targetManifest, target, artifact, curseForgeUploadToken)
                    result = 'uploaded'
                }
                results.add([target.name.toString(), platform, result, remoteId])
            }
        }
        appendResultSummary(results)
    }

    static List<Map> publicationTargets(Map manifest) {
        List<Map> targets = new ArrayList<>(manifest.targets as List<Map>)
        if (manifest.serverPlugin instanceof Map && manifest.serverPlugin.publish == true) {
            targets.add(manifest.serverPlugin.publication as Map)
        }
        targets
    }

    Map<String, Map<String, List<Map>>> fetchAll(
            Map manifest, List<Map> targets, String modrinthToken, String curseForgeApiKey) {
        Map<String, Map<String, List<Map>>> result = [modrinth: [:], curseforge: [:]]
        ['modrinth', 'curseforge'].each { String platform ->
            Map<String, List<Map>> byProject = [:]
            targets.each { Map target ->
                Map view = manifestForTarget(manifest, target)
                String projectId = view.platforms[platform].projectId.toString()
                if (!byProject.containsKey(projectId)) {
                    byProject[projectId] = platform == 'modrinth'
                            ? fetchModrinth(view, modrinthToken)
                            : fetchCurseForge(view, curseForgeApiKey)
                }
                result[platform][target.id.toString()] = byProject[projectId]
            }
        }
        result
    }

    Map classifyPerTarget(List<Map> targets, Map<String, Map<String, List<Map>>> remote) {
        Map result = [modrinth: [:], curseforge: [:]]
        result.each { String platform, Map states ->
            targets.each { Map target ->
                states[target.id] = PublicationSupport.classify(
                        platform, target, remote[platform][target.id.toString()])
            }
        }
        result
    }

    static Map manifestForTarget(Map manifest, Map target) {
        if (!(target.platforms instanceof Map)) return manifest
        Map notes = new LinkedHashMap(manifest.releaseNotes as Map)
        if (target.releaseNotes instanceof String) notes.text = target.releaseNotes
        new LinkedHashMap(manifest) + [platforms: target.platforms, releaseNotes: notes]
    }

    Map classifyAll(List<Map> targets, Map<String, List<Map>> remote) {
        Map result = [modrinth: [:], curseforge: [:]]
        result.each { String platform, Map states ->
            targets.each { Map target ->
                states[target.id] = PublicationSupport.classify(platform, target, remote[platform])
            }
        }
        result
    }

    void requireNoConflicts(Map plan) {
        List<String> conflicts = []
        plan.each { String platform, Map states ->
            states.each { Object target, Object raw ->
                Map state = raw as Map
                if (state.action == 'conflict') conflicts.add("${platform} ${target}: ${state.reason}")
            }
        }
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException('Publication preflight found conflicts:\n- ' + conflicts.join('\n- '))
        }
    }

    List<Map> fetchModrinth(Map manifest, String token) {
        String projectId = manifest.platforms.modrinth.projectId.toString()
        Object payload = json(request(
                'GET', "${apiBase('MODRINTH_API_BASE', 'https://api.modrinth.com/v2')}/project/${projectId}/version",
                ['Authorization': token], null, null, [200] as Set<Integer>, [token]))
        if (!(payload instanceof List)) throw new IllegalStateException('Modrinth version list is not an array')
        (payload as List).findAll { it instanceof Map } as List<Map>
    }

    List<Map> fetchCurseForge(Map manifest, String apiKey) {
        String projectId = manifest.platforms.curseforge.projectId.toString()
        String base = apiBase('CURSEFORGE_API_BASE', 'https://api.curseforge.com')
        List<Map> files = []
        int index = 0
        int pageSize = 50
        while (true) {
            Object payload = json(request(
                    'GET', "${base}/v1/mods/${projectId}/files?index=${index}&pageSize=${pageSize}",
                    ['X-API-Key': apiKey], null, null, [200] as Set<Integer>, [apiKey]))
            if (!(payload instanceof Map) || !(payload.data instanceof List)) {
                throw new IllegalStateException('CurseForge file list has no data array')
            }
            List<Map> page = (payload.data as List).findAll { it instanceof Map } as List<Map>
            files.addAll(page)
            Map pagination = payload.pagination instanceof Map ? payload.pagination as Map : [:]
            int resultCount = pagination.resultCount instanceof Number
                    ? (pagination.resultCount as Number).intValue() : page.size()
            Integer totalCount = pagination.totalCount instanceof Number
                    ? (pagination.totalCount as Number).intValue() : null
            index += resultCount
            if (resultCount == 0 || (totalCount != null && index >= totalCount) ||
                    (totalCount == null && page.size() < pageSize)) break
            if (index > 10_000) throw new IllegalStateException('CurseForge pagination exceeded 10000 files')
        }
        files
    }

    String uploadModrinth(Map manifest, Map target, File artifact, File sources, String token) {
        Map metadata = PublicationSupport.modrinthMetadata(manifest, target)
        String boundary = "NclSkins${UUID.randomUUID().toString().replace('-', '')}"
        byte[] body = PublicationSupport.multipart([
                [name: 'data', filename: null, contentType: 'application/json',
                 bytes: JsonOutput.toJson(metadata).getBytes(StandardCharsets.UTF_8)],
                [name: 'file', filename: artifact.name, contentType: 'application/java-archive',
                 bytes: artifact.bytes],
                [name: 'sources', filename: sources.name, contentType: 'application/java-archive',
                 bytes: sources.bytes]
        ], boundary)
        Object payload = json(request(
                'POST', "${apiBase('MODRINTH_API_BASE', 'https://api.modrinth.com/v2')}/version",
                ['Authorization': token], body, "multipart/form-data; boundary=${boundary}",
                [200, 201] as Set<Integer>, [token]))
        if (!(payload instanceof Map) || payload.id == null) {
            throw new IllegalStateException("Modrinth upload for ${target.name} returned no version ID")
        }
        payload.id.toString()
    }

    String updateModrinthMetadata(Map target, String remoteId, String token) {
        Map metadata = [
                game_versions: target.gameVersions,
                loaders      : PublicationSupport.desiredLoaders(target) as List
        ]
        request(
                'PATCH', "${apiBase('MODRINTH_API_BASE', 'https://api.modrinth.com/v2')}" +
                        "/version/${URLEncoder.encode(remoteId, StandardCharsets.UTF_8)}",
                ['Authorization': token],
                JsonOutput.toJson(metadata).getBytes(StandardCharsets.UTF_8),
                'application/json', [204] as Set<Integer>, [token])
        remoteId
    }

    String uploadCurseForge(Map manifest, Map target, File artifact, String token) {
        uploadCurseForgeFile(
                manifest, target, artifact, PublicationSupport.curseForgeMetadata(manifest, target), token)
    }

    String updateCurseForgeMetadata(
            Map manifest, Map target, String remoteId, String token) {
        long fileId
        try {
            fileId = Long.parseLong(remoteId)
        } catch (NumberFormatException error) {
            throw new IllegalStateException("invalid CurseForge file ID: ${remoteId}", error)
        }
        Map metadata = [
                fileID          : fileId,
                gameVersionNames: PublicationSupport.curseForgeMetadata(
                        manifest, target).gameVersionNames
        ]
        String boundary = "NclSkins${UUID.randomUUID().toString().replace('-', '')}"
        byte[] body = PublicationSupport.multipart([
                [name: 'metadata', filename: null, contentType: 'application/json',
                 bytes: JsonOutput.toJson(metadata).getBytes(StandardCharsets.UTF_8)]
        ], boundary)
        Object payload = json(request(
                'POST', "${apiBase('CURSEFORGE_UPLOAD_API_BASE', 'https://minecraft.curseforge.com')}" +
                        "/api/projects/${manifest.platforms.curseforge.projectId}/update-file",
                ['X-Api-Token': token], body, "multipart/form-data; boundary=${boundary}",
                [200, 201] as Set<Integer>, [token]))
        Object returned = payload instanceof Map ? payload.id : null
        if (returned == null || returned.toString() != remoteId) {
            throw new IllegalStateException(
                    "CurseForge metadata update returned another file ID: ${returned}")
        }
        remoteId
    }

    String uploadCurseForgeFile(Map manifest, Map target, File artifact, Map metadata, String token) {
        String boundary = "NclSkins${UUID.randomUUID().toString().replace('-', '')}"
        byte[] body = PublicationSupport.multipart([
                [name: 'metadata', filename: null, contentType: 'application/json',
                 bytes: JsonOutput.toJson(metadata).getBytes(StandardCharsets.UTF_8)],
                [name: 'file', filename: artifact.name, contentType: 'application/java-archive',
                 bytes: artifact.bytes]
        ], boundary)
        Object payload = json(request(
                'POST', "${apiBase('CURSEFORGE_UPLOAD_API_BASE', 'https://minecraft.curseforge.com')}" +
                        "/api/projects/${manifest.platforms.curseforge.projectId}/upload-file",
                ['X-Api-Token': token], body, "multipart/form-data; boundary=${boundary}",
                [200, 201] as Set<Integer>, [token]))
        Object id = payload instanceof Map ? payload.id : null
        if (id == null && payload instanceof Map && payload.data instanceof Map) id = payload.data.id
        if (id == null) throw new IllegalStateException("CurseForge upload for ${target.name} returned no file ID")
        id.toString()
    }

    HttpResult request(
            String method,
            String url,
            Map<String, String> headers,
            byte[] body,
            String contentType,
            Set<Integer> expectedStatuses,
            List<String> secrets) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header('User-Agent', USER_AGENT)
                .header('Accept', 'application/json')
        headers.each { String name, String value -> builder.header(name, value) }
        if (contentType != null) builder.header('Content-Type', contentType)
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)
        HttpResponse<byte[]> response
        try {
            response = httpClient().send(
                    builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofByteArray())
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt()
            throw new IllegalStateException("${method} ${url} was interrupted", error)
        } catch (IOException error) {
            throw new IllegalStateException("${method} ${url} failed: ${error.message}", error)
        }
        String responseBody = new String(response.body(), StandardCharsets.UTF_8)
        if (!expectedStatuses.contains(response.statusCode())) {
            secrets.findAll { it != null && it.size() >= 8 }.each {
                responseBody = responseBody.replace(it, '[redacted]')
            }
            throw new IllegalStateException("${method} ${url} returned HTTP ${response.statusCode()}" +
                    (responseBody.trim() ? ": ${responseBody.take(4096)}" : ''))
        }
        new HttpResult(response.statusCode(), responseBody)
    }

    Object json(HttpResult response) {
        if (response.body.isBlank()) return [:]
        try {
            new JsonSlurper().parseText(response.body)
        } catch (Exception error) {
            throw new IllegalStateException('Publication API returned invalid JSON', error)
        }
    }

    String requireSecret(String name) {
        String value = System.getenv(name)?.trim()
        if (value == null || value.isBlank()) throw new IllegalStateException("required secret ${name} is not set")
        value
    }

    String apiBase(String environmentName, String fallback) {
        (System.getenv(environmentName) ?: fallback).replaceAll('/+$', '')
    }

    private HttpClient httpClient() {
        if (client == null) {
            client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()
        }
        client
    }

    void appendSummary(String title, Map plan) {
        File summary = summaryFile()
        if (summary == null) return
        summary << "## ${title}\n\n| Target | Platform | Action |\n|---|---|---|\n"
        plan.each { String platform, Map states ->
            states.each { Object target, Object state ->
                summary << "| ${target} | ${platform} | ${(state as Map).action} |\n"
            }
        }
        summary << '\n'
    }

    void appendResultSummary(List<List<String>> rows) {
        File summary = summaryFile()
        if (summary == null) return
        summary << "## Platform publication\n\n| Version | Platform | Result | Remote ID |\n|---|---|---|---|\n"
        rows.each { List<String> row -> summary << "| ${row.join(' | ')} |\n" }
        summary << '\n'
    }

    File summaryFile() {
        String path = System.getenv('GITHUB_STEP_SUMMARY')
        path == null || path.isBlank() ? null : new File(path)
    }

    static final class HttpResult {
        final int status
        final String body

        HttpResult(int status, String body) {
            this.status = status
            this.body = body
        }
    }
}

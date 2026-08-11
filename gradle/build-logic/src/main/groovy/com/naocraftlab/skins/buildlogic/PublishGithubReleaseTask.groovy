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

abstract class PublishGithubReleaseTask extends DefaultTask {
    private static final String API_VERSION = '2022-11-28'
    private static final String USER_AGENT =
            'NaoCraftLab/NCL-Skins-Publisher (https://github.com/NaoCraftLab/nclskins)'

    @InputDirectory
    abstract DirectoryProperty getBundleDirectory()

    private transient HttpClient client

    PublishGithubReleaseTask() {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    void publish() {
        File bundle = bundleDirectory.get().asFile
        Map manifest = PublicationSupport.loadManifest(bundle)
        String token = requireEnvironment('GITHUB_TOKEN')
        String repository = requireRepository()
        String api = apiBase()
        request(
                'GET', "${api}/repos/${repository}/git/ref/tags/${encodePath(manifest.version.toString())}",
                headers(token), null, null, [200] as Set<Integer>, token)
        Map release = findRelease(api, repository, manifest.version.toString(), token)
        if (manifest.mode == 'backfill' && release == null) {
            throw new IllegalStateException('backfill requires an existing GitHub Release')
        }

        Map plan = GithubReleaseSupport.plan(
                manifest,
                release == null ? [] : release.assets as List<Map>,
                { Map asset -> remoteSha256(api, repository, asset, token) })
        requireNoConflicts(plan)

        Map metadata = [
                tag_name   : manifest.version,
                name       : "NCL Skins ${manifest.version}".toString(),
                body       : manifest.releaseNotes.text,
                draft      : false,
                prerelease : manifest.prerelease,
                make_latest: manifest.prerelease ? 'false' : 'true'
        ]
        if (release == null) {
            release = json(request(
                    'POST', "${api}/repos/${repository}/releases", headers(token),
                    JsonOutput.toJson(metadata).getBytes(StandardCharsets.UTF_8),
                    'application/json', [201] as Set<Integer>, token)) as Map
        } else {
            Map update = new LinkedHashMap(metadata)
            update.remove('tag_name')
            release = json(request(
                    'PATCH', "${api}/repos/${repository}/releases/${release.id}", headers(token),
                    JsonOutput.toJson(update).getBytes(StandardCharsets.UTF_8),
                    'application/json', [200] as Set<Integer>, token)) as Map
        }
        String releaseId = required(release.id, 'GitHub Release ID')

        (plan.actions as List<Map>).findAll { it.action in ['delete', 'replace'] }.each { Map action ->
            request('DELETE', "${api}/repos/${repository}/releases/assets/${action.remoteId}",
                    headers(token), null, null, [204] as Set<Integer>, token)
        }
        (plan.actions as List<Map>).findAll { it.action in ['upload', 'replace'] }.each { Map action ->
            File asset = new File(bundle, "assets/${action.file}")
            String encodedName = URLEncoder.encode(action.file.toString(), StandardCharsets.UTF_8)
            Map uploaded = json(request(
                    'POST', "${uploadBase()}/repos/${repository}/releases/${releaseId}/assets?name=${encodedName}",
                    headers(token), asset.bytes, 'application/java-archive',
                    [201] as Set<Integer>, token)) as Map
            required(uploaded.id, "uploaded GitHub asset ID for ${action.file}")
        }

        Map finalRelease = findRelease(api, repository, manifest.version.toString(), token)
        if (finalRelease == null) throw new IllegalStateException('GitHub Release disappeared after publication')
        Map finalPlan = GithubReleaseSupport.plan(
                manifest, finalRelease.assets as List<Map>,
                { Map asset -> remoteSha256(api, repository, asset, token) })
        List<Map> incomplete = (finalPlan.actions as List<Map>).findAll { it.action != 'keep' }
        if (!incomplete.isEmpty()) {
            throw new IllegalStateException('GitHub Release verification failed: ' +
                    incomplete.collect { "${it.action} ${it.file}" }.join(', '))
        }
        appendSummary(manifest, finalPlan)
    }

    Map findRelease(String api, String repository, String tag, String token) {
        HttpResult response = request(
                'GET', "${api}/repos/${repository}/releases/tags/${encodePath(tag)}",
                headers(token), null, null, [200, 404] as Set<Integer>, token)
        if (response.status == 404) return null
        Object parsed = json(response)
        if (!(parsed instanceof Map) || !(parsed.assets instanceof List) || parsed.id == null) {
            throw new IllegalStateException('GitHub Release response is malformed')
        }
        parsed as Map
    }

    String remoteSha256(String api, String repository, Map asset, String token) {
        String digest = asset.digest?.toString()
        if (digest?.startsWith('sha256:') && digest.substring('sha256:'.length()) ==~ /[0-9a-f]{64}/) {
            return digest.substring('sha256:'.length())
        }
        String downloadUrl = required(asset.browser_download_url, 'GitHub release asset download URL')
        URI uri = URI.create(downloadUrl)
        if (uri.scheme != 'https' || uri.host != 'github.com' ||
                !uri.path.startsWith("/${repository}/releases/download/")) {
            throw new IllegalStateException('GitHub release asset download URL is not allowed')
        }
        HttpResult response = request(
                'GET', downloadUrl, [:], null, null,
                [200] as Set<Integer>, null, false)
        CatalogTools.sha256(response.body)
    }

    HttpResult request(
            String method,
            String url,
            Map<String, String> requestHeaders,
            byte[] body,
            String contentType,
            Set<Integer> expectedStatuses,
            String secret,
            boolean acceptJson = true) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header('User-Agent', USER_AGENT)
        if (acceptJson) builder.header('Accept', 'application/vnd.github+json')
        requestHeaders.each { String name, String value -> builder.setHeader(name, value) }
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
        if (!expectedStatuses.contains(response.statusCode())) {
            String responseBody = new String(response.body(), StandardCharsets.UTF_8)
            if (secret != null && secret.size() >= 8) responseBody = responseBody.replace(secret, '[redacted]')
            throw new IllegalStateException("${method} ${url} returned HTTP ${response.statusCode()}" +
                    (responseBody.trim() ? ": ${responseBody.take(4096)}" : ''))
        }
        new HttpResult(response.statusCode(), response.body())
    }

    Object json(HttpResult response) {
        if (response.body.length == 0) return [:]
        try {
            new JsonSlurper().parse(response.body)
        } catch (Exception error) {
            throw new IllegalStateException('GitHub API returned invalid JSON', error)
        }
    }

    Map<String, String> headers(String token) {
        ['Authorization': "Bearer ${token}".toString(), 'X-GitHub-Api-Version': API_VERSION]
    }

    String requireEnvironment(String name) {
        String value = System.getenv(name)?.trim()
        if (value == null || value.isBlank()) throw new IllegalStateException("required environment ${name} is not set")
        value
    }

    String requireRepository() {
        String repository = requireEnvironment('GITHUB_REPOSITORY')
        if (!(repository ==~ /[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+/)) {
            throw new IllegalStateException('GITHUB_REPOSITORY is invalid')
        }
        repository
    }

    String apiBase() {
        (System.getenv('GITHUB_API_URL') ?: 'https://api.github.com').replaceAll('/+$', '')
    }

    String uploadBase() {
        (System.getenv('GITHUB_UPLOAD_URL') ?: 'https://uploads.github.com').replaceAll('/+$', '')
    }

    private HttpClient httpClient() {
        if (client == null) {
            client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
        }
        client
    }

    void requireNoConflicts(Map plan) {
        List<Map> conflicts = plan.conflicts as List<Map>
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException('GitHub Release conflicts: ' +
                    conflicts.collect { "${it.file}: ${it.reason}" }.join('; '))
        }
    }

    void appendSummary(Map manifest, Map plan) {
        String path = System.getenv('GITHUB_STEP_SUMMARY')
        if (path == null || path.isBlank()) return
        File summary = new File(path)
        summary << "## GitHub Release ${manifest.version}\n\n| Asset | Result |\n|---|---|\n"
        (plan.actions as List<Map>).each { Map action -> summary << "| ${action.file} | ${action.action} |\n" }
        summary << '\n'
    }

    static String required(Object value, String label) {
        if (value == null || value.toString().isBlank()) throw new IllegalStateException("${label} is missing")
        value.toString()
    }

    static String encodePath(String value) {
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace('+', '%20')
    }

    static final class HttpResult {
        final int status
        final byte[] body

        HttpResult(int status, byte[] body) {
            this.status = status
            this.body = body
        }
    }
}

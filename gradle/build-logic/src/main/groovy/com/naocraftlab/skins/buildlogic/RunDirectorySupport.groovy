package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

final class RunDirectorySupport {
    private static final String LOOPBACK_ADDRESS = '127.0.0.1'
    static final List<String> LOADER_ORDER = ['fabric', 'neoforge', 'forge'].asImmutable()
    static final List<String> KERNEL_ORDER = [
            'craftbukkit', 'spigot', 'paper', 'purpur', 'folia'
    ].asImmutable()
    static final List<String> PROXY_ORDER = ['velocity', 'bungeecord'].asImmutable()
    static final Map<String, String> DISPLAY_NAMES = [
            fabric: 'Fabric', neoforge: 'NeoForge', forge: 'Forge',
            craftbukkit: 'CraftBukkit', spigot: 'Spigot', paper: 'Paper',
            purpur: 'Purpur', folia: 'Folia', velocity: 'Velocity',
            bungeecord: 'BungeeCord'
    ].asImmutable()

    static void prepareAll(File root, Map catalog) {
        requireEulaAcceptance(root)
        catalog.targets.each { Map target ->
            CatalogTools.targetRuntimeSpecs(target).each { Map runtime ->
                prepareTargetServer(root, catalog, target, runtime.minecraftVersion.toString())
            }
        }
        catalog.serverPluginTopologies.each { Map topology ->
            prepareTopologyBackends(root, catalog, topology)
        }
        Set<String> versions = catalog.targets.collectMany { Map target ->
            CatalogTools.targetRuntimeSpecs(target)*.minecraftVersion
        } as Set
        versions.each { String version -> prepareClients(root, catalog, version) }
    }

    static void prepareClients(File root, Map catalog, String version) {
        List<Map<String, String>> entries = serverEntries(catalog, version)
        Set<String> allManagedNames = managedServerNames()
        catalog.targets.collectMany { Map target ->
            CatalogTools.targetRuntimeSpecs(target).findAll {
                it.minecraftVersion.toString() == version
            }
        }.each { Map runtime ->
            Map target = runtime.target as Map
            ['Client', 'LicensedClient'].each { String runKind ->
                Path file = new File(RunLayout.modDirectory(
                        root, target, version, runKind),
                        'servers.dat').toPath()
                MinecraftServerList.merge(file, entries, allManagedNames)
                List<Map<String, String>> actual = MinecraftServerList.entries(file)
                if (actual.take(entries.size()) != entries ||
                        actual.findAll { allManagedNames.contains(it.name) } != entries) {
                    throw new GradleException("Managed Minecraft server list verification failed: ${file}")
                }
            }
        }
    }

    static List<Map<String, String>> serverEntries(Map catalog, String version) {
        List<Map<String, String>> result = [[name: 'LAN', ip: "${LOOPBACK_ADDRESS}:25565".toString()]]
        LOADER_ORDER.each { String loader ->
            Map runtime = catalog.targets.collectMany { Map target ->
                CatalogTools.targetRuntimeSpecs(target)
            }.find {
                it.minecraftVersion.toString() == version &&
                        it.target.loader.id.toString() == loader
            } as Map
            if (runtime != null) result.add([
                    name: DISPLAY_NAMES[loader],
                    ip: "${LOOPBACK_ADDRESS}:${runtime.serverPort}".toString()
            ])
        }
        KERNEL_ORDER.each { String kernel ->
            Map topology = selectTopology(catalog, version, 'standalone', kernel)
            if (topology != null) result.add([
                    name: DISPLAY_NAMES[kernel],
                    ip: "${LOOPBACK_ADDRESS}:${topology.ports.server}".toString()
            ])
        }
        PROXY_ORDER.each { String proxy ->
            KERNEL_ORDER.each { String kernel ->
                Map topology = selectTopology(catalog, version, proxy, kernel)
                if (topology != null) result.add([
                        name: "${DISPLAY_NAMES[proxy]} ${DISPLAY_NAMES[kernel]}".toString(),
                        ip: "${LOOPBACK_ADDRESS}:${topology.ports.proxy}".toString()
                ])
            }
        }
        result.asImmutable()
    }

    static Set<String> managedServerNames() {
        Set<String> result = ['LAN'] as LinkedHashSet
        LOADER_ORDER.each { result.add(DISPLAY_NAMES[it]) }
        KERNEL_ORDER.each { result.add(DISPLAY_NAMES[it]) }
        PROXY_ORDER.each { String proxy ->
            KERNEL_ORDER.each { String kernel ->
                result.add("${DISPLAY_NAMES[proxy]} ${DISPLAY_NAMES[kernel]}".toString())
            }
        }
        result.asImmutable()
    }

    private static Map selectTopology(Map catalog, String version, String mode, String kernel) {
        catalog.serverPluginTopologies.find {
            it.minecraft.toString() == version && it.mode == mode && it.kernel == kernel
        } as Map
    }

    static void ensureTargetEula(File root, Map target) {
        ensureTargetEula(root, target, target.minecraft.version.toString())
    }

    static void ensureTargetEula(File root, Map target, String minecraftVersion) {
        requireEulaAcceptance(root)
        ensureEula(new File(RunLayout.modDirectory(
                root, target, minecraftVersion, 'Server'), 'eula.txt').toPath())
    }

    static void prepareTargetServer(File root, Map catalog, Map target, String minecraftVersion) {
        ensureTargetEula(root, target, minecraftVersion)
        File directory = RunLayout.modDirectory(
                root, target, minecraftVersion, 'Server')
        ensureServerOnlineMode(new File(directory, 'server.properties').toPath())
        ensureOperators(new File(directory, 'ops.json').toPath(),
                CatalogTools.developmentOperators(catalog))
    }

    static void ensureServerOnlineMode(Path file) {
        List<String> lines = Files.isRegularFile(file)
                ? Files.readAllLines(file, StandardCharsets.UTF_8)
                : []
        List<String> updated = []
        boolean found = false
        lines.each { String line ->
            if (line.trim() ==~ /online-mode\s*=.*/) {
                if (!found) updated.add('online-mode=true')
                found = true
            } else {
                updated.add(line)
            }
        }
        if (!found) updated.add('online-mode=true')
        String content = updated.join('\n') + '\n'
        if (!Files.isRegularFile(file) || Files.readString(file) != content) {
            ServerPluginRuntimeSupport.writeAtomic(file, content)
        }
        if (Files.readAllLines(file, StandardCharsets.UTF_8).count {
            it.trim() == 'online-mode=true'
        } != 1) {
            throw new GradleException("Managed server authentication verification failed: ${file}")
        }
    }

    static void ensureTopologyEula(File root, Map topology) {
        requireEulaAcceptance(root)
        File stateRoot = RunLayout.topologyDirectory(root, topology)
        if (topology.mode == 'standalone') {
            ensureEula(new File(stateRoot, 'server/eula.txt').toPath())
        } else {
            ensureEula(new File(stateRoot, 'lobby/eula.txt').toPath())
            ensureEula(new File(stateRoot, 'target/eula.txt').toPath())
        }
    }

    static void prepareTopologyBackends(File root, Map catalog, Map topology) {
        ensureTopologyEula(root, topology)
        File stateRoot = RunLayout.topologyDirectory(root, topology)
        List<String> roles = topology.mode == 'standalone' ? ['server'] : ['lobby', 'target']
        List<Map> operators = CatalogTools.developmentOperators(catalog)
        roles.each { String role ->
            ensureOperators(new File(stateRoot, "${role}/ops.json").toPath(), operators)
        }
    }

    static void ensureOperators(Path file, List<Map> desiredOperators) {
        List existing = []
        if (Files.isRegularFile(file)) {
            Object parsed
            try {
                parsed = new JsonSlurper().parse(file.toFile())
            } catch (Exception error) {
                throw new GradleException("Managed operator list is invalid JSON: ${file}", error)
            }
            if (!(parsed instanceof List) || (parsed as List).any { !(it instanceof Map) }) {
                throw new GradleException("Managed operator list must be a JSON array: ${file}")
            }
            existing = parsed as List
        }
        Set<String> desiredUuids = desiredOperators*.uuid.collect {
            it.toString().toLowerCase(Locale.ROOT)
        } as Set
        Set<String> desiredNames = desiredOperators*.name.collect {
            it.toString().toLowerCase(Locale.ROOT)
        } as Set
        List preserved = existing.findAll { Object raw ->
            Map entry = raw as Map
            String uuid = entry.uuid?.toString()?.toLowerCase(Locale.ROOT)
            String name = entry.name?.toString()?.toLowerCase(Locale.ROOT)
            !desiredUuids.contains(uuid) && !desiredNames.contains(name)
        }
        List merged = desiredOperators.collect { new LinkedHashMap(it) } + preserved
        String content = JsonOutput.prettyPrint(JsonOutput.toJson(merged)) + '\n'
        if (!Files.isRegularFile(file) || Files.readString(file) != content) {
            ServerPluginRuntimeSupport.writeAtomic(file, content)
        }
        Object verified = new JsonSlurper().parse(file.toFile())
        if (!(verified instanceof List) || (verified as List).take(desiredOperators.size()) !=
                desiredOperators) {
            throw new GradleException("Managed operator list verification failed: ${file}")
        }
    }

    static void ensureEula(Path file) {
        List<String> lines = Files.isRegularFile(file)
                ? Files.readAllLines(file, StandardCharsets.UTF_8)
                : []
        List<String> updated = []
        boolean found = false
        lines.each { String line ->
            if (line.trim() ==~ /eula\s*=.*/) {
                if (!found) updated.add('eula=true')
                found = true
            } else {
                updated.add(line)
            }
        }
        if (!found) updated.add('eula=true')
        String content = updated.join('\n') + '\n'
        if (!Files.isRegularFile(file) || Files.readString(file) != content) {
            ServerPluginRuntimeSupport.writeAtomic(file, content)
        }
        int accepted = Files.readAllLines(file, StandardCharsets.UTF_8).count {
            it.trim() == 'eula=true'
        }
        if (accepted != 1) throw new GradleException("Managed EULA verification failed: ${file}")
    }

    static void requireEulaAcceptance(File root) {
        File marker = new File(root, ServerPluginRuntimeSupport.EULA_MARKER)
        if (!ServerPluginRuntimeSupport.validEulaMarker(marker.toPath())) {
            throw new GradleException('Minecraft EULA is not accepted for managed runtimes. Run: ' +
                    './gradlew acceptServerRuntimeEula -PnclskinsAcceptMinecraftEula=true')
        }
    }

    private RunDirectorySupport() {}
}

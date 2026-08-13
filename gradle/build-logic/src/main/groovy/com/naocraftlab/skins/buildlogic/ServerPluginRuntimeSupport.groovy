package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom

final class ServerPluginRuntimeSupport {
    static final String EULA_MARKER = '.gradle/nclskins/server-runtimes/eula-accepted.json'

    static String taskName(Map topology) {
        String version = topology.minecraft.toString().replaceAll('[^A-Za-z0-9]', '')
        String kernel = topology.kernel.toString().split('-').collect { String part ->
            part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1)
        }.join('')
        switch (topology.mode.toString()) {
            case 'standalone': return "runServerPlugin${kernel}${version}"
            case 'velocity': return "runProxyNetworkVelocity${kernel}${version}"
            case 'bungeecord': return "runProxyNetworkBungeecord${kernel}${version}"
            default: throw new IllegalArgumentException("Unknown topology mode ${topology.mode}")
        }
    }

    static String configurationName(Map topology) {
        topology.mode == 'standalone'
                ? "${topology.minecraft}:${topology.kernel}:runServer"
                : "${topology.minecraft}:${topology.mode}:${topology.kernel}:runProxy"
    }

    static String previousConfigurationName(Map topology) {
        topology.mode == 'standalone'
                ? "${topology.minecraft}:${topology.kernel}:runServerPlugin"
                : "${topology.minecraft}:${topology.mode}:${topology.kernel}:runNetwork"
    }

    static String configurationFileName(Map topology) {
        configurationName(topology).replaceAll('[^A-Za-z0-9]', '_') + '.xml'
    }

    static Map<String, String> managedFiles(Map topology, String velocitySecret, String bungeeToken) {
        Map<String, String> result = [:]
        if (topology.mode == 'standalone') {
            result['server/server.properties'] = serverProperties(topology.ports.server as int, true)
            result['server/plugins/NCLSkinsPlugin/nclskins-server.json5'] =
                    nclConfiguration(false)
            return result
        }
        ['lobby', 'target'].each { String role ->
            int port = topology.ports[role] as int
            result["${role}/server.properties"] = serverProperties(port, false)
            result["${role}/plugins/NCLSkinsPlugin/nclskins-server.json5"] =
                    nclConfiguration(true)
        }
        if (topology.mode == 'velocity') {
            result['proxy/velocity.toml'] = velocityToml(topology, velocitySecret)
            ['lobby', 'target'].each { String role ->
                result["${role}/spigot.yml"] = "settings:\n  bungeecord: false\n"
                result["${role}/config/paper-global.yml"] = velocityPaperGlobal(velocitySecret)
            }
        } else {
            result['proxy/config.yml'] = bungeeConfig(topology)
            result['proxy/plugins/BungeeGuard/token.yml'] = "token: '${bungeeToken}'\n"
            ['lobby', 'target'].each { String role ->
                result["${role}/spigot.yml"] = "settings:\n  bungeecord: true\n"
                result["${role}/bukkit.yml"] = "settings:\n  connection-throttle: -1\n"
                result["${role}/plugins/BungeeGuard/config.yml"] =
                        "allowed-tokens:\n  - '${bungeeToken}'\n"
            }
        }
        result
    }

    static String randomVelocitySecret(SecureRandom random) {
        byte[] value = new byte[32]
        random.nextBytes(value)
        Base64.encoder.encodeToString(value)
    }

    static String randomBungeeToken(SecureRandom random) {
        byte[] value = new byte[32]
        random.nextBytes(value)
        value.collect { String.format('%02x', it & 0xff) }.join('')
    }

    static boolean validVelocitySecret(String value) {
        try {
            value != null && Base64.decoder.decode(value).length == 32 &&
                    Base64.encoder.encodeToString(Base64.decoder.decode(value)) == value
        } catch (IllegalArgumentException invalid) {
            false
        }
    }

    static boolean validBungeeToken(String value) {
        value != null && value ==~ /[0-9a-f]{64}/
    }

    static void writeAtomic(Path destination, String content) {
        Files.createDirectories(destination.parent)
        Path temporary = destination.resolveSibling(destination.fileName.toString() + '.tmp')
        Files.writeString(temporary, content, StandardCharsets.UTF_8)
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    static void restrict(Path path, boolean directory) {
        try {
            Set<java.nio.file.attribute.PosixFilePermission> permissions = directory
                    ? java.nio.file.attribute.PosixFilePermissions.fromString('rwx------')
                    : java.nio.file.attribute.PosixFilePermissions.fromString('rw-------')
            Files.setPosixFilePermissions(path, permissions)
        } catch (UnsupportedOperationException ignored) {
        }
    }

    static String sha256(Path path) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        path.withInputStream { InputStream input ->
            byte[] buffer = new byte[64 * 1024]
            int count
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        digest.digest().collect { String.format('%02x', it & 0xff) }.join('')
    }

    static String eulaMarker(String acceptedAt) {
        JsonOutput.prettyPrint(JsonOutput.toJson([
                schemaVersion: 1,
                accepted: true,
                acceptedAt: acceptedAt,
                notice: 'Minecraft EULA acceptance explicitly recorded by the local operator.'
        ])) + '\n'
    }

    static boolean validEulaMarker(Path marker) {
        try {
            if (!Files.isRegularFile(marker) || Files.size(marker) > 4_096L) return false
            Object parsed = new JsonSlurper().parse(marker.toFile())
            parsed instanceof Map && parsed.schemaVersion == 1 && parsed.accepted == true &&
                    parsed.acceptedAt instanceof String && !parsed.acceptedAt.isBlank() &&
                    parsed.notice == 'Minecraft EULA acceptance explicitly recorded by the local operator.'
        } catch (IOException | RuntimeException invalid) {
            false
        }
    }

    private static String serverProperties(int port, boolean online) {
        """server-ip=127.0.0.1
server-port=${port}
online-mode=${online}
enable-query=false
enable-rcon=false
management-server-enabled=false
motd=NCL Skins Plugin managed test runtime
"""
    }

    private static String nclConfiguration(boolean trustedProxyForwarding) {
        """{
  "realtimeRefresh": {
    "enabled": true,
    "trustedProxyForwarding": ${trustedProxyForwarding},
    "maxConcurrentLookups": 2,
    "lookupRatePerSecond": 10.0,
    "lookupBurst": 20
  }
}
"""
    }

    private static String velocityToml(Map topology, String secret) {
        """config-version = "2.8"
bind = "127.0.0.1:${topology.ports.proxy}"
motd = "NCL Skins Plugin managed test proxy"
online-mode = true
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"

[servers]
lobby = "127.0.0.1:${topology.ports.lobby}"
target = "127.0.0.1:${topology.ports.target}"
try = ["lobby"]

[forced-hosts]
"""
    }

    private static String velocityPaperGlobal(String secret) {
        """proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: '${secret}'
"""
    }

    private static String bungeeConfig(Map topology) {
        """online_mode: true
ip_forward: true
query_enabled: false
listeners:
- host: 127.0.0.1:${topology.ports.proxy}
  query_enabled: false
  forced_hosts: {}
  priorities:
  - lobby
servers:
  lobby:
    address: 127.0.0.1:${topology.ports.lobby}
    restricted: false
  target:
    address: 127.0.0.1:${topology.ports.target}
    restricted: false
"""
    }

    private ServerPluginRuntimeSupport() {}
}

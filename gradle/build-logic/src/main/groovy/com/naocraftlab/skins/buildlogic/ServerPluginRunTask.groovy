package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

abstract class ServerPluginRunTask extends DefaultTask {
    static final String USER_AGENT =
            'NaoCraftLab/NCL-Skins-Plugin-Runtime (https://github.com/NaoCraftLab/nclskins)'

    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @InputFile
    abstract RegularFileProperty getPluginArtifact()

    @Input
    abstract Property<String> getTopologyId()

    @Input
    abstract Property<Boolean> getDryRun()

    @Input
    abstract Property<Boolean> getDevelopmentLogging()

    @TaskAction
    void runTopology() {
        File root = repositoryDirectory.get().asFile
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        Map topology = catalog.serverPluginTopologies.find { it.id == topologyId.get() } as Map
        if (topology == null) throw new GradleException("Unknown server plugin topology ${topologyId.get()}")
        File marker = new File(root, ServerPluginRuntimeSupport.EULA_MARKER)
        if (!ServerPluginRuntimeSupport.validEulaMarker(marker.toPath())) {
            throw new GradleException('Minecraft EULA is not accepted for managed runtimes. Run: ' +
                    './gradlew acceptServerRuntimeEula -PnclskinsAcceptMinecraftEula=true')
        }

        File stateRoot = RunLayout.topologyDirectory(root, topology)
        Files.createDirectories(stateRoot.toPath())
        ServerPluginRuntimeSupport.restrict(stateRoot.toPath(), true)
        RunDirectorySupport.prepareTopologyBackends(root, catalog, topology)
        File lockFile = new File(stateRoot, '.run.lock')
        FileChannel channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)
        FileLock lock = channel.tryLock()
        if (lock == null) {
            channel.close()
            throw new GradleException("Topology ${topology.id} is already running")
        }
        try {
            List<Integer> ports = (topology.ports as Map).values().collect { it as int }
            ports.each { int port -> ServerPluginRunTask.requirePortFree(port) }
            PreparedTopology prepared = prepare(root, catalog, topology, stateRoot)
            if (dryRun.get()) {
                logger.lifecycle("Prepared ${topology.id} (${prepared.roles*.name.join(', ')}) without launching")
                return
            }
            new Supervisor(logger, prepared).run()
        } finally {
            lock.release()
            channel.close()
        }
    }

    private PreparedTopology prepare(File root, Map catalog, Map topology, File stateRoot) {
        File plugin = pluginArtifact.get().asFile
        if (!plugin.isFile()) throw new GradleException("Missing verified plugin JAR ${plugin}")

        SecureRandom random = new SecureRandom()
        String velocitySecret = topology.mode == 'velocity'
                ? readOrCreateSecret(new File(stateRoot, 'forwarding.secret'),
                    { ServerPluginRuntimeSupport.randomVelocitySecret(random) },
                    { String value -> ServerPluginRuntimeSupport.validVelocitySecret(value) })
                : ''
        String bungeeToken = topology.mode == 'bungeecord'
                ? readOrCreateSecret(new File(stateRoot, 'bungeeguard.token'),
                    { ServerPluginRuntimeSupport.randomBungeeToken(random) },
                    { String value -> ServerPluginRuntimeSupport.validBungeeToken(value) })
                : ''
        Map<String, String> managed = ServerPluginRuntimeSupport.managedFiles(
                topology, velocitySecret, bungeeToken)
        managed.each { String relative, String content ->
            File destination = new File(stateRoot, relative)
            ServerPluginRuntimeSupport.writeAtomic(destination.toPath(), content)
            Path current = destination.parentFile.toPath()
            while (current != null && current.startsWith(stateRoot.toPath())) {
                ServerPluginRuntimeSupport.restrict(current, true)
                current = current.parent
            }
            ServerPluginRuntimeSupport.restrict(destination.toPath(), false)
        }
        if (topology.mode == 'velocity') {
            File proxySecret = new File(stateRoot, 'proxy/forwarding.secret')
            ServerPluginRuntimeSupport.writeAtomic(proxySecret.toPath(), velocitySecret + '\n')
            ServerPluginRuntimeSupport.restrict(proxySecret.toPath(), false)
        }

        List<Role> roles = []
        if (topology.mode == 'standalone') {
            roles.add(backendRole(root, catalog, topology, stateRoot, 'server', plugin))
        } else {
            roles.add(backendRole(root, catalog, topology, stateRoot, 'lobby', plugin))
            roles.add(backendRole(root, catalog, topology, stateRoot, 'target', plugin))
            roles.add(proxyRole(root, catalog, topology, stateRoot, plugin))
        }
        roles.findAll { it.kind == 'backend' }.each { Role role ->
            ServerPluginRuntimeSupport.writeAtomic(new File(role.directory, 'eula.txt').toPath(),
                    'eula=true\n')
        }
        new PreparedTopology(topology, roles)
    }

    private Role backendRole(File root, Map catalog, Map topology, File stateRoot,
                             String name, File plugin) {
        boolean buildToolsKernel = topology.kernel in ['craftbukkit', 'spigot']
        Map runtimeSpec = runtime(catalog, buildToolsKernel
                ? 'buildtools-200'
                : "${topology.kernel}-${topology.minecraft}")
        File serverJar = resolveRuntime(root, catalog, runtimeSpec, topology.kernel.toString())
        File directory = new File(stateRoot, name)
        install(directory, plugin)
        if (topology.mode == 'bungeecord') {
            install(directory, resolveRuntime(root, catalog, runtime(catalog, 'bungeeguard-1.4.0'), null))
            if ((topology.dependencies as List).contains('protocollib')) {
                install(directory, resolveRuntime(root, catalog, runtime(catalog, 'protocollib-5.4.0'), null))
            }
        }
        String javaHome = TargetRuntime.resolveJavaHome(runtimeSpec.javaRelease as int)
        List<String> command = [new File(javaHome, 'bin/java').absolutePath]
        if (developmentLogging.get()) {
            command.addAll(log4jDebugArguments(directory, backendOverlay(topology.kernel.toString())))
        }
        command.addAll(['-jar', serverJar.absolutePath, '--nogui'])
        new Role(name, 'backend', directory, command,
                ['Done (', 'NCL_SKINS_PLUGIN_READY'], 'stop')
    }

    private Role proxyRole(File root, Map catalog, Map topology, File stateRoot, File plugin) {
        String runtimeId = topology.mode == 'velocity' ? 'velocity-4.0.0-6' : 'bungeecord-2086'
        Map runtimeSpec = runtime(catalog, runtimeId)
        File proxyJar = resolveRuntime(root, catalog, runtimeSpec, null)
        File directory = new File(stateRoot, 'proxy')
        install(directory, plugin)
        if (topology.mode == 'bungeecord') {
            install(directory, resolveRuntime(root, catalog, runtime(catalog, 'bungeeguard-1.4.0'), null))
        }
        String javaHome = TargetRuntime.resolveJavaHome(runtimeSpec.javaRelease as int)
        List<String> command = [new File(javaHome, 'bin/java').absolutePath]
        if (developmentLogging.get()) {
            if (topology.mode == 'velocity') {
                command.addAll(log4jDebugArguments(directory, velocityOverlay()))
            } else {
                command.addAll([
                        '-Dnet.md_5.bungee.console-log-level=FINE',
                        '-Dnet.md_5.bungee.file-log-level=FINE'])
            }
        }
        command.addAll(['-jar', proxyJar.absolutePath])
        new Role('proxy', 'proxy', directory, command,
                ['NCL_SKINS_PROXY_READY'], topology.mode == 'velocity' ? 'shutdown' : 'end')
    }

    private File resolveRuntime(File root, Map catalog, Map runtime, String buildToolsKernel) {
        if (buildToolsKernel in ['craftbukkit', 'spigot']) {
            return resolveBuildToolsRuntime(root, catalog, buildToolsKernel)
        }
        File directory = new File(root, ".gradle/nclskins/server-runtimes/${runtime.id}")
        Files.createDirectories(directory.toPath())
        File destination = new File(directory, "${runtime.id}.jar")
        if (destination.isFile() && ServerPluginRuntimeSupport.sha256(destination.toPath()) == runtime.sha256) {
            return destination
        }
        if (destination.exists()) Files.delete(destination.toPath())
        download(runtime.url.toString(), destination.toPath())
        String actual = ServerPluginRuntimeSupport.sha256(destination.toPath())
        if (actual != runtime.sha256) {
            Files.deleteIfExists(destination.toPath())
            throw new GradleException("Checksum mismatch for ${runtime.id}: expected=${runtime.sha256}, actual=${actual}")
        }
        destination
    }

    private File resolveBuildToolsRuntime(File root, Map catalog, String kernel) {
        File directory = new File(root, ".gradle/nclskins/server-runtimes/buildtools-1.20.1/${kernel}")
        File provenance = new File(directory, 'provenance.json')
        File result = new File(directory, "${kernel}-1.20.1.jar")
        Map buildTools = runtime(catalog, 'buildtools-200')
        if (result.exists() || provenance.exists()) {
            if (!result.isFile() || !provenance.isFile()) {
                throw new GradleException("Incomplete BuildTools provenance for ${kernel}; " +
                        "run ./gradlew resetServerPluginBuildToolsRuntime -PserverPluginKernel=${kernel}")
            }
            Map recorded
            try {
                recorded = new JsonSlurper().parse(provenance) as Map
            } catch (RuntimeException malformed) {
                throw new GradleException("Malformed BuildTools provenance for ${kernel}; " +
                        "run ./gradlew resetServerPluginBuildToolsRuntime -PserverPluginKernel=${kernel}",
                        malformed)
            }
            String actual = ServerPluginRuntimeSupport.sha256(result.toPath())
            boolean commitsValid = recorded.upstreamCommits instanceof Map &&
                    !recorded.upstreamCommits.isEmpty() &&
                    recorded.upstreamCommits.values().every { it ==~ /[0-9a-f]{40}/ }
            boolean matches = recorded.schemaVersion == 1 && recorded.kernel == kernel &&
                    recorded.minecraft == '1.20.1' &&
                    recorded.buildToolsSha256 == buildTools.sha256 &&
                    recorded.resultSha256 == actual && commitsValid
            if (!matches) {
                throw new GradleException("BuildTools provenance mismatch for ${kernel}; " +
                        "run ./gradlew resetServerPluginBuildToolsRuntime -PserverPluginKernel=${kernel}")
            }
            return result
        }
        Files.createDirectories(directory.toPath())
        File tool = resolveRuntime(root, catalog, buildTools, null)
        String javaHome = TargetRuntime.resolveJavaHome(17)
        List<String> command = [new File(javaHome, 'bin/java').absolutePath, '-jar', tool.absolutePath,
                                '--rev', '1.20.1']
        if (kernel == 'craftbukkit') command.addAll(['--compile', 'craftbukkit'])
        Process process = new ProcessBuilder(command).directory(directory).inheritIO().start()
        if (process.waitFor() != 0) throw new GradleException("BuildTools failed for ${kernel}")
        List<File> candidates = directory.listFiles()?.findAll { File file ->
            file.isFile() && file.name.toLowerCase(Locale.ROOT).startsWith(kernel) &&
                    file.name.endsWith('.jar') && file != tool
        }?.sort { File a, File b -> b.lastModified() <=> a.lastModified() } ?: []
        if (candidates.isEmpty()) throw new GradleException("BuildTools did not produce ${kernel} 1.20.1")
        Files.move(candidates.first().toPath(), result.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Map<String, String> upstreamCommits = [:]
        ['BuildData', 'Bukkit', 'CraftBukkit', 'Spigot'].each { String name ->
            File checkout = new File(directory, name)
            if (new File(checkout, '.git').exists()) {
                upstreamCommits[name] = ServerPluginRunTask.gitCommit(checkout)
            }
        }
        if (upstreamCommits.isEmpty()) {
            throw new GradleException('BuildTools produced no verifiable upstream Git checkouts')
        }
        ServerPluginRuntimeSupport.writeAtomic(provenance.toPath(), JsonOutput.prettyPrint(JsonOutput.toJson([
                schemaVersion: 1, buildToolsSha256: buildTools.sha256,
                resultSha256: ServerPluginRuntimeSupport.sha256(result.toPath()), kernel: kernel,
                minecraft: '1.20.1', upstreamCommits: upstreamCommits
        ])) + '\n')
        result
    }

    static String gitCommit(File checkout) {
        Process process = new ProcessBuilder('git', 'rev-parse', 'HEAD')
                .directory(checkout).start()
        String output = process.inputStream.getText('UTF-8').trim()
        String error = process.errorStream.getText('UTF-8').trim()
        if (process.waitFor() != 0 || !(output ==~ /[0-9a-f]{40}/)) {
            throw new GradleException(
                    "Unable to capture BuildTools provenance for ${checkout.name}: ${error}")
        }
        output
    }

    private static void install(File roleDirectory, File artifact) {
        File plugins = new File(roleDirectory, 'plugins')
        Files.createDirectories(plugins.toPath())
        File destination = new File(plugins, artifact.name)
        if (!destination.isFile() ||
                ServerPluginRuntimeSupport.sha256(destination.toPath()) !=
                ServerPluginRuntimeSupport.sha256(artifact.toPath())) {
            Files.copy(artifact.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private static List<String> log4jDebugArguments(File directory, String content) {
        File managed = new File(directory, '.nclskins/logging-debug.xml')
        ServerPluginRuntimeSupport.writeAtomic(managed.toPath(), content)
        ServerPluginRuntimeSupport.restrict(managed.parentFile.toPath(), true)
        ServerPluginRuntimeSupport.restrict(managed.toPath(), false)
        ["-Dlog4j2.configurationFile=classpath:log4j2.xml,${managed.toPath().toUri()}".toString()]
    }

    static String backendOverlay(String kernel) {
        String logger = 'com.naocraftlab.skins.server.plugin.bukkit.NclSkinsBukkitPlugin'
        if (kernel in ['paper', 'purpur', 'folia']) {
            return """<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<Configuration status=\"WARN\"><Loggers><Logger name=\"${logger}\" level=\"DEBUG\"><AppenderRef ref=\"TerminalConsole\"><LevelMatchFilter level=\"DEBUG\" onMatch=\"ACCEPT\" onMismatch=\"DENY\"/></AppenderRef></Logger></Loggers></Configuration>
"""
        }
        """<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<Configuration status=\"WARN\"><Loggers><Logger name=\"${logger}\" level=\"DEBUG\"/></Loggers></Configuration>
"""
    }

    static String velocityOverlay() {
        '''<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN"><Loggers><Logger name="nclskins-plugin" level="DEBUG"/></Loggers></Configuration>
'''
    }

    private static String readOrCreateSecret(
            File file, Closure<String> generator, Closure<Boolean> validator) {
        String value
        if (file.isFile()) {
            value = file.getText('UTF-8').trim()
        } else {
            value = generator.call()
            ServerPluginRuntimeSupport.writeAtomic(file.toPath(), value + '\n')
        }
        if (!validator.call(value)) {
            throw new GradleException(
                    "Managed secret file ${file.name} is malformed; reset its topology explicitly")
        }
        ServerPluginRuntimeSupport.restrict(file.parentFile.toPath(), true)
        ServerPluginRuntimeSupport.restrict(file.toPath(), false)
        value
    }

    private static Map runtime(Map catalog, String id) {
        Map found = catalog.serverPluginRuntimes.find { it.id == id } as Map
        if (found == null) throw new GradleException("Missing runtime ${id}")
        found
    }

    private static void download(String url, Path destination) {
        Path temporary = destination.resolveSibling(destination.fileName.toString() + '.part')
        Files.deleteIfExists(temporary)
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection()
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.setRequestProperty('User-Agent', USER_AGENT)
        int status = connection.responseCode
        if (status < 200 || status >= 300) {
            throw new GradleException("Runtime download failed HTTP ${status}: ${url}")
        }
        try (InputStream input = connection.inputStream) {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            connection.disconnect()
        }
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
    }

    static void requirePortFree(int port) {
        if (!portFree(port)) throw new GradleException("Port 127.0.0.1:${port} is occupied")
    }

    private static boolean portFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.reuseAddress = false
            socket.bind(new InetSocketAddress('127.0.0.1', port))
            return true
        } catch (IOException ignored) {
            return false
        }
    }

    static final class Role {
        final String name
        final String kind
        final File directory
        final List<String> command
        final List<String> readiness
        final String stopCommand

        Role(String name, String kind, File directory, List<String> command,
             List<String> readiness, String stopCommand) {
            this.name = name
            this.kind = kind
            this.directory = directory
            this.command = command.asImmutable()
            this.readiness = readiness.asImmutable()
            this.stopCommand = stopCommand
        }
    }

    static final class PreparedTopology {
        final Map topology
        final List<Role> roles

        PreparedTopology(Map topology, List<Role> roles) {
            this.topology = topology.asImmutable()
            this.roles = roles.asImmutable()
        }
    }

    static final class Supervisor {
        private final org.gradle.api.logging.Logger logger
        private final PreparedTopology prepared
        private final Map<String, Process> processes = new LinkedHashMap<>()
        private final Map<String, Set<String>> observed = new ConcurrentHashMap<>()
        private final Map<String, ArrayDeque<String>> tails = new ConcurrentHashMap<>()
        private final AtomicBoolean stopping = new AtomicBoolean(false)

        Supervisor(org.gradle.api.logging.Logger logger, PreparedTopology prepared) {
            this.logger = logger
            this.prepared = prepared
        }

        void run() {
            Thread hook = new Thread({ stopAll() }, "nclskins-${prepared.topology.id}-shutdown")
            Runtime.runtime.addShutdownHook(hook)
            try {
                prepared.roles.each { Role role ->
                    start(role)
                    awaitReady(role, Duration.ofMinutes(3))
                }
                logger.lifecycle("NCL Skins Plugin topology READY at 127.0.0.1:${prepared.topology.ports.values().first()}")
                consoleLoop()
            } finally {
                stopAll()
                try { Runtime.runtime.removeShutdownHook(hook) } catch (IllegalStateException ignored) {}
                requirePortsReleased()
            }
        }

        private void start(Role role) {
            Process process = new ProcessBuilder(role.command).directory(role.directory)
                    .redirectErrorStream(true).start()
            processes[role.name] = process
            observed[role.name] = ConcurrentHashMap.newKeySet()
            tails[role.name] = new ArrayDeque<>()
            Thread.startDaemon("nclskins-${role.name}-logs") {
                process.inputStream.withReader(StandardCharsets.UTF_8.name()) { reader ->
                    String line
                    while ((line = reader.readLine()) != null) {
                        logger.lifecycle("[${role.name}] ${line}")
                        synchronized (tails[role.name]) {
                            tails[role.name].addLast(line)
                            while (tails[role.name].size() > 40) tails[role.name].removeFirst()
                        }
                        role.readiness.each { String marker ->
                            if (line.contains(marker)) observed[role.name].add(marker)
                        }
                    }
                }
            }
        }

        private void awaitReady(Role role, Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos()
            Process process = processes[role.name]
            while (System.nanoTime() < deadline) {
                if (!process.alive) throw failure(role, "exited with ${process.exitValue()}")
                if (observed[role.name].containsAll(role.readiness)) return
                Thread.sleep(100)
            }
            throw failure(role, 'readiness timeout')
        }

        private GradleException failure(Role role, String message) {
            String tail
            synchronized (tails[role.name]) { tail = tails[role.name].join('\n') }
            new GradleException("${role.name} ${message}. Last log lines:\n${tail}")
        }

        private void consoleLoop() {
            LinkedBlockingQueue<String> commands = new LinkedBlockingQueue<>()
            AtomicBoolean inputClosed = new AtomicBoolean(false)
            Thread.startDaemon("nclskins-${prepared.topology.id}-console") {
                try {
                    System.in.withReader(StandardCharsets.UTF_8.name()) { reader ->
                        String line
                        while ((line = reader.readLine()) != null) commands.put(line)
                    }
                } finally {
                    inputClosed.set(true)
                }
            }
            while (!stopping.get()) {
                Map.Entry<String, Process> exited = processes.entrySet().find {
                    !it.value.alive
                }
                if (exited != null) {
                    Role role = prepared.roles.find { it.name == exited.key }
                    throw failure(role, "exited with ${exited.value.exitValue()}")
                }
                String line = commands.poll(100, TimeUnit.MILLISECONDS)
                if (line == null) {
                    if (inputClosed.get()) return
                    continue
                }
                if (line == '@stop') return
                if (line == '@status') {
                    processes.each { String name, Process process ->
                        logger.lifecycle("[${name}] ${process.alive ? 'running' : 'stopped'}")
                    }
                    continue
                }
                def match = line =~ /^@(lobby|target|proxy|server)\s+(.+)$/
                if (!match.matches()) {
                    logger.warn('Use @lobby, @target, @proxy, @server, @status, or @stop')
                    continue
                }
                Process process = processes[match.group(1)]
                if (process == null || !process.alive) {
                    logger.warn("Role ${match.group(1)} is not running")
                    continue
                }
                write(process, match.group(2))
            }
        }

        private void stopAll() {
            if (!stopping.compareAndSet(false, true)) return
            List<Role> proxies = prepared.roles.findAll { it.kind == 'proxy' }
            List<Role> backends = prepared.roles.findAll { it.kind == 'backend' }
            stopGracefully(proxies, 3)
            stopGracefully(backends, 7)
            processes.values().each { Process process ->
                if (process.alive) {
                    process.toHandle().descendants().forEach { it.destroy() }
                    process.destroy()
                }
            }
            processes.values().each { Process process ->
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.toHandle().descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                    process.waitFor(3, TimeUnit.SECONDS)
                }
            }
        }

        private void stopGracefully(List<Role> roles, int seconds) {
            roles.each { Role role ->
                Process process = processes[role.name]
                if (process?.alive) write(process, role.stopCommand)
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (roles.any { Role role -> processes[role.name]?.alive }
                    && System.nanoTime() < deadline) {
                Thread.sleep(50)
            }
        }

        private void requirePortsReleased() {
            List<Integer> ports = (prepared.topology.ports as Map).values()
                    .collect { it as int }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            List<Integer> occupied = ports.findAll { !ServerPluginRunTask.portFree(it) }
            while (!occupied.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(50)
                occupied = ports.findAll { !ServerPluginRunTask.portFree(it) }
            }
            if (!occupied.isEmpty()) {
                throw new GradleException(
                        "Topology ${prepared.topology.id} left occupied ports ${occupied}")
            }
            List<Long> descendants = processes.values().collectMany { Process process ->
                process.toHandle().descendants()
                        .filter { it.alive }
                        .map { it.pid() }
                        .toList()
            }
            if (!descendants.isEmpty()) {
                throw new GradleException(
                        "Topology ${prepared.topology.id} left descendant processes ${descendants}")
            }
            processes.values().each { Process process ->
                if (process.alive) {
                    throw new GradleException(
                            "Topology ${prepared.topology.id} left process ${process.pid()}")
                }
            }
        }

        private static void write(Process process, String command) {
            process.outputStream.write((command + '\n').getBytes(StandardCharsets.UTF_8))
            process.outputStream.flush()
        }
    }
}

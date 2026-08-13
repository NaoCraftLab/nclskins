package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant

abstract class MigrateRunDirectoriesTask extends DefaultTask {
    private static final Set<String> SERVER_ROOT_NAMES = [
            'config', 'defaultconfigs', 'serverconfig',
            'server.properties', 'eula.txt', 'server-icon.png',
            'ops.json', 'whitelist.json', 'allowlist.json',
            'banned-ips.json', 'banned-players.json',
            'usercache.json', 'usernamecache.json',
            'bukkit.yml', 'spigot.yml', 'paper.yml', 'paper-global.yml',
            'commands.yml', 'permissions.yml'
    ].asImmutable()

    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @TaskAction
    void migrate() {
        File root = repositoryDirectory.get().asFile.canonicalFile
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        Path lockPath = new File(root, '.gradle/nclskins/run-layout-migration.lock').toPath()
        Files.createDirectories(lockPath.parent)
        FileChannel migrationChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)
        FileLock migrationLock = migrationChannel.tryLock()
        if (migrationLock == null) {
            migrationChannel.close()
            throw new GradleException('Another run-directory migration is active')
        }
        List<FileLock> topologyLocks = []
        List<FileChannel> topologyChannels = []
        try {
            preflight(root, catalog, topologyLocks, topologyChannels)
            long timestamp = Instant.now().toEpochMilli()
            File backupRoot = new File(root, "runs/.trash/run-layout-migration-${timestamp}")
            int modCount = migrateMods(root, catalog, backupRoot)
            int topologyCount = migrateTopologies(root, catalog)
            removeEmptyLegacyTopologyRoot(root)
            logger.lifecycle("Migrated ${modCount} mod run directories and ${topologyCount} " +
                    "server topology directories into ${new File(root, 'runs')}")
            if (backupRoot.exists()) {
                logger.lifecycle("Preserved original mixed mod runs in recoverable ${backupRoot}")
            }
        } finally {
            topologyLocks.reverseEach { FileLock lock -> if (lock.valid) lock.release() }
            topologyChannels.reverseEach { FileChannel channel -> if (channel.open) channel.close() }
            migrationLock.release()
            migrationChannel.close()
        }
    }

    private static void preflight(File root, Map catalog, List<FileLock> locks,
                                  List<FileChannel> channels) {
        Set<String> destinations = [] as Set
        catalog.targets.each { Map target ->
            File source = RunLayout.legacyModDirectory(root, target)
            List<File> targetDestinations = RunLayout.RUN_KINDS.collect {
                RunLayout.modDirectory(root, target, it)
            }
            targetDestinations.each { File destination ->
                if (!destinations.add(destination.canonicalPath)) {
                    throw new GradleException("Duplicate canonical run destination ${destination}")
                }
            }
            if (source.exists() && targetDestinations.any { it.exists() }) {
                throw new GradleException("Cannot migrate ${source}: a canonical destination already exists")
            }
            if (!source.exists() && targetDestinations.any { it.exists() } &&
                    !targetDestinations.every { it.exists() }) {
                throw new GradleException("Incomplete canonical mod run layout for ${target.id}")
            }
        }
        catalog.serverPluginTopologies.each { Map topology ->
            File source = RunLayout.legacyTopologyDirectory(root, topology)
            File destination = RunLayout.topologyDirectory(root, topology)
            if (!destinations.add(destination.canonicalPath)) {
                throw new GradleException("Duplicate canonical topology destination ${destination}")
            }
            if (source.exists() && destination.exists()) {
                throw new GradleException("Cannot migrate ${source}: ${destination} already exists")
            }
            if (source.exists()) MigrateRunDirectoriesTask.acquireTopologyLock(
                    source, topology, locks, channels)
        }
        List<Integer> ports = []
        ports.addAll(catalog.targets.collect { it.development.serverPort as int })
        catalog.serverPluginTopologies.each { Map topology ->
            ports.addAll((topology.ports as Map).values().collect { it as int })
        }
        ports.unique().each { int port -> ServerPluginRunTask.requirePortFree(port) }
    }

    private static void acquireTopologyLock(File source, Map topology, List<FileLock> locks,
                                            List<FileChannel> channels) {
        File lockFile = new File(source, '.run.lock')
        Files.createDirectories(lockFile.parentFile.toPath())
        FileChannel channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)
        FileLock lock
        try {
            lock = channel.tryLock()
        } catch (java.nio.channels.OverlappingFileLockException busy) {
            channel.close()
            throw new GradleException("Topology ${topology.id} is active", busy)
        }
        if (lock == null) {
            channel.close()
            throw new GradleException("Topology ${topology.id} is active")
        }
        channels.add(channel)
        locks.add(lock)
    }

    private int migrateMods(File root, Map catalog, File backupRoot) {
        int migrated = 0
        catalog.targets.each { Map target ->
            File source = RunLayout.legacyModDirectory(root, target)
            if (!source.exists()) return
            File client = RunLayout.modDirectory(root, target, 'Client')
            File licensed = RunLayout.modDirectory(root, target, 'LicensedClient')
            File server = RunLayout.modDirectory(root, target, 'Server')
            MigrateRunDirectoriesTask.copyTree(source.toPath(), client.toPath())
            MigrateRunDirectoriesTask.copyTree(source.toPath(), licensed.toPath())
            MigrateRunDirectoriesTask.copyServerState(source.toPath(), server.toPath())
            MigrateRunDirectoriesTask.verifyIdentical(source.toPath(), client.toPath())
            MigrateRunDirectoriesTask.verifyIdentical(source.toPath(), licensed.toPath())

            File backup = new File(backupRoot,
                    "mod-targets/${RunLayout.segment(target.minecraft.version)}/" +
                            "${RunLayout.segment(target.loader.id)}/run")
            Files.createDirectories(backup.parentFile.toPath())
            MigrateRunDirectoriesTask.move(source.toPath(), backup.toPath())
            migrated++
        }
        migrated
    }

    private int migrateTopologies(File root, Map catalog) {
        int migrated = 0
        catalog.serverPluginTopologies.each { Map topology ->
            File source = RunLayout.legacyTopologyDirectory(root, topology)
            if (!source.exists()) return
            File destination = RunLayout.topologyDirectory(root, topology)
            Files.createDirectories(destination.parentFile.toPath())
            Map<String, String> before = MigrateRunDirectoriesTask.fingerprints(source.toPath())
            MigrateRunDirectoriesTask.move(source.toPath(), destination.toPath())
            Map<String, String> after = MigrateRunDirectoriesTask.fingerprints(destination.toPath())
            if (before != after) {
                throw new GradleException("Topology migration verification failed: ${topology.id}")
            }
            migrated++
        }
        migrated
    }

    private static void copyServerState(Path source, Path destination) {
        Files.createDirectories(destination)
        source.toFile().listFiles()?.sort { it.name }.each { File entry ->
            if (entry.name == 'config' && entry.isDirectory()) {
                MigrateRunDirectoriesTask.copyServerConfig(
                        entry.toPath(), destination.resolve(entry.name))
            } else if (MigrateRunDirectoriesTask.serverOwned(entry.name)) {
                MigrateRunDirectoriesTask.copyTree(
                        entry.toPath(), destination.resolve(entry.name))
            }
        }
    }

    private static void copyServerConfig(Path source, Path destination) {
        source.toFile().listFiles()?.sort { it.name }.each { File entry ->
            String name = entry.name
            if (name.startsWith('nclskins-server.') || name == 'neoforge-server.toml' ||
                    name == 'neoforge-common.toml') {
                Files.createDirectories(destination)
                MigrateRunDirectoriesTask.copyTree(entry.toPath(), destination.resolve(name))
            }
        }
    }

    private static boolean serverOwned(String name) {
        SERVER_ROOT_NAMES.contains(name) || name.startsWith('world') ||
                (name.startsWith('paper-') && name.endsWith('.yml'))
    }

    private static void copyTree(Path source, Path destination) {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new GradleException("Refusing to overwrite ${destination}")
        }
        if (Files.isSymbolicLink(source)) {
            Files.createDirectories(destination.parent)
            Files.createSymbolicLink(destination, Files.readSymbolicLink(source))
            return
        }
        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(destination.parent)
            Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
            return
        }
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                Path relative = source.relativize(directory)
                Files.createDirectories(destination.resolve(relative))
                FileVisitResult.CONTINUE
            }

            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                Path target = destination.resolve(source.relativize(file))
                if (Files.isSymbolicLink(file)) {
                    Files.createSymbolicLink(target, Files.readSymbolicLink(file))
                } else {
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES)
                }
                FileVisitResult.CONTINUE
            }
        })
    }

    private static void verifyIdentical(Path expected, Path actual) {
        if (MigrateRunDirectoriesTask.fingerprints(expected) !=
                MigrateRunDirectoriesTask.fingerprints(actual)) {
            throw new GradleException("Run-directory copy verification failed: ${expected} -> ${actual}")
        }
    }

    private static Map<String, String> fingerprints(Path root) {
        Map<String, String> result = new TreeMap<>()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return result
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                result[MigrateRunDirectoriesTask.relative(root, directory) + '/'] = 'directory'
                FileVisitResult.CONTINUE
            }

            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                String name = MigrateRunDirectoriesTask.relative(root, file)
                result[name] = Files.isSymbolicLink(file)
                        ? "link:${Files.readSymbolicLink(file)}".toString()
                        : "${Files.size(file)}:${MigrateRunDirectoriesTask.sha256(file)}".toString()
                FileVisitResult.CONTINUE
            }
        })
        result
    }

    private static String relative(Path root, Path value) {
        Path relative = root.relativize(value)
        relative.nameCount == 0 ? '.' : relative.toString().replace(File.separatorChar, '/' as char)
    }

    private static String sha256(Path file) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        file.withInputStream { InputStream input ->
            byte[] buffer = new byte[64 * 1024]
            int count
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        digest.digest().collect { String.format('%02x', it & 0xff) }.join('')
    }

    private static void move(Path source, Path destination) {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination)
        }
    }

    private static void removeEmptyLegacyTopologyRoot(File root) {
        File legacy = new File(root, 'runs/server-plugins')
        if (!legacy.isDirectory()) return
        File[] remaining = legacy.listFiles()
        if (remaining != null && remaining.length == 0) Files.delete(legacy.toPath())
    }
}

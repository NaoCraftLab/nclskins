package com.naocraftlab.skins.buildlogic;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class ArchitectureBoundaries {
    public record Role(String layer, String side, boolean common, String exposure) {
        public Role(String layer, String side, boolean common) { this(layer, side, common, "internal"); }
    }
    public static Map<String, Role> roles() throws IOException {
        Map<String, Role> result = new TreeMap<>();
        try (var in = ArchitectureBoundaries.class.getResourceAsStream("/architecture-roles.tsv")) {
            if (in == null) throw new IOException("Missing architecture role inventory");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\\s+");
                if (p.length != 5 || !Set.of("domain", "application", "presentation", "infrastructure", "composition").contains(p[1])
                        || !Set.of("shared", "client", "server").contains(p[2]) || !Set.of("common", "native").contains(p[3])
                        || !Set.of("internal", "input", "pure").contains(p[4])
                        || result.put(p[0], new Role(p[1], p[2], p[3].equals("common"), p[4])) != null)
                    throw new IOException("Invalid or duplicate architecture role: " + line);
            }
        }
        return result;
    }
    public static Role role(Map<String, Role> roles, String type) {
        Role result = roles.get(type);
        if (result == null && type.contains("$")) result = role(roles, type.substring(0, type.lastIndexOf('$')));
        return result;
    }
    public static List<String> verify(Collection<File> roots) throws IOException {
        return verify(roots, roles());
    }
    public static List<String> verify(Collection<File> roots, Map<String, Role> roles) throws IOException {
        List<String> errors = new ArrayList<>();
        for (File root : roots) {
            if (!root.isDirectory()) continue;
            try (var paths = Files.walk(root.toPath())) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                    var scan = CompiledDependencies.read(Files.readAllBytes(path));
                    Role source = role(roles, scan.name());
                    if (source == null) { errors.add(scan.name() + ": unclassified production type"); continue; }
                    for (var edge : scan.edges()) {
                        String rule = violation(source, role(roles, edge.target()), edge.target());
                        if (rule == null && Set.of("domain", "application", "presentation").contains(source.layer())
                                && edge.target().equals("java/lang/Runtime") && "exec".equals(edge.memberName()))
                            rule = "inner-to-concrete-io";
                        if (rule != null) errors.add(edge.source() + " -> " + edge.target() + " [" + rule + "] at " + edge.location());
                    }
                }
            }
        }
        return errors.stream().distinct().sorted().toList();
    }
    public static String violation(Role source, Role target, String name) {
        if (name.startsWith("com/naocraftlab/skins/") && target == null) return "unclassified project dependency";
        if (source.common() && (nativeType(name) || target != null && !target.common())) return "common-to-native";
        if (source.side().equals("server") && (name.startsWith("net/minecraft/client/") || target != null && target.side().equals("client"))) return "server-to-client";
        if (target != null) {
            if (source.layer().equals("domain") && !target.layer().equals("domain")) return "domain-to-" + target.layer();
            if (source.layer().equals("application") && Set.of("infrastructure", "presentation", "composition").contains(target.layer())) return "application-to-" + target.layer();
            if (source.layer().equals("presentation") && target.layer().equals("application")
                    && target.exposure().equals("internal")) return "presentation-to-application-implementation";
            if (source.layer().equals("presentation") && Set.of("infrastructure", "composition").contains(target.layer())) return "presentation-to-" + target.layer();
        }
        if (Set.of("domain", "application", "presentation").contains(source.layer()) && effectType(name)) return "inner-to-concrete-io";
        return null;
    }
    private static boolean nativeType(String n) {
        return List.of("net/minecraft/", "net/fabricmc/", "net/minecraftforge/", "net/neoforged/", "org/spongepowered/", "org/bukkit/", "io/papermc/", "com/velocitypowered/", "net/md_5/", "com/mojang/authlib/", "org/lwjgl/").stream().anyMatch(n::startsWith);
    }
    private static boolean effectType(String n) {
        return Set.of(
                "java/nio/file/Files", "java/nio/file/FileSystems", "java/nio/file/spi/FileSystemProvider",
                "java/nio/channels/FileChannel", "java/nio/channels/AsynchronousFileChannel",
                "java/nio/channels/SocketChannel", "java/nio/channels/ServerSocketChannel",
                "java/nio/channels/AsynchronousSocketChannel", "java/nio/channels/AsynchronousServerSocketChannel",
                "java/nio/channels/DatagramChannel", "java/io/File", "java/io/FileInputStream",
                "java/io/FileOutputStream", "java/io/FileReader", "java/io/FileWriter", "java/io/RandomAccessFile",
                "java/net/URL", "java/net/URLClassLoader", "java/net/InetAddress", "java/net/Inet4Address",
                "java/net/Inet6Address", "java/net/Socket", "java/net/ServerSocket", "java/net/DatagramSocket",
                "java/net/MulticastSocket", "java/util/zip/ZipFile", "java/util/jar/JarFile",
                "java/lang/ProcessBuilder").contains(n.split("\\$", 2)[0])
                || n.startsWith("java/net/http/") || n.contains("URLConnection")
                || n.startsWith("javax/net/") || n.startsWith("java/sql/") || n.startsWith("javax/sql/")
                || n.startsWith("org/sqlite/") || n.startsWith("okhttp3/")
                || n.startsWith("org/apache/http/") || n.startsWith("org/apache/hc/")
                || n.equals("org/apache/commons/io/FileUtils") || n.equals("org/apache/commons/io/FileSystemUtils")
                || n.equals("com/google/common/io/Files") || n.equals("com/google/common/io/MoreFiles")
                || n.equals("com/google/common/io/Resources");
    }
    private ArchitectureBoundaries() {}
}

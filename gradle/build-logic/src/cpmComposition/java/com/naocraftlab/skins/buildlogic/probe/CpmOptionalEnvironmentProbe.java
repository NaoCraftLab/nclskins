package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinExtensionEnvironmentSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public final class CpmOptionalEnvironmentProbe {
    public static boolean isLoaded(String id) {
        return id.equals("cpm") && Boolean.getBoolean("nclskins.cpm.optionalPresent");
    }

    public static SkinExtensionEnvironmentSource environment() throws Exception {
        for (String loader : List.of("Fabric", "Forge", "NeoForge")) {
            String name = "com.naocraftlab.skins.compat.environment." + loader + "SkinExtensionEnvironmentSource";
            if (ClassLoader.getSystemResource(name.replace('.', '/') + ".class") != null)
                return (SkinExtensionEnvironmentSource) Class.forName(name).getConstructor(SkinCatalogSource.class)
                        .newInstance((SkinCatalogSource) (collection, entry, model) -> { throw new IOException(); });
        }
        throw new AssertionError("Missing actual target environment leaf");
    }

    private static void constructor(ClassWriter writer) {
        var init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd();
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("setup")) {
            for (String owner : List.of("net/fabricmc/loader/api/FabricLoader", "net/minecraftforge/fml/ModList", "net/neoforged/fml/ModList")) {
                var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                boolean itf = owner.endsWith("FabricLoader");
                String implementation = itf ? owner + "$NclProbe" : owner;
                writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | (itf ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : 0), owner, null, "java/lang/Object", null);
                if (!itf) constructor(writer);
                for (String getter : List.of("get", "getInstance")) {
                    var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, getter, "()L" + owner + ";", null, null);
                    method.visitCode(); method.visitTypeInsn(Opcodes.NEW, implementation); method.visitInsn(Opcodes.DUP);
                    method.visitMethodInsn(Opcodes.INVOKESPECIAL, implementation, "<init>", "()V", false);
                    method.visitInsn(Opcodes.ARETURN); method.visitMaxs(0, 0); method.visitEnd();
                }
                for (String query : List.of("isLoaded", "isModLoaded")) {
                    var method = writer.visitMethod(Opcodes.ACC_PUBLIC | (itf ? Opcodes.ACC_ABSTRACT : 0), query, "(Ljava/lang/String;)Z", null, null);
                    if (itf) { method.visitEnd(); continue; }
                    method.visitCode(); method.visitVarInsn(Opcodes.ALOAD, 1);
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/naocraftlab/skins/buildlogic/probe/CpmOptionalEnvironmentProbe", "isLoaded", "(Ljava/lang/String;)Z", false);
                    method.visitInsn(Opcodes.IRETURN); method.visitMaxs(0, 0); method.visitEnd();
                }
                writer.visitEnd(); Path output = Path.of(args[1]).resolve(owner + ".class");
                Files.createDirectories(output.getParent()); Files.write(output, writer.toByteArray());
                if (itf) {
                    var concrete = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    concrete.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, implementation, null, "java/lang/Object", new String[]{owner});
                    constructor(concrete);
                    for (String query : List.of("isLoaded", "isModLoaded")) {
                        var method = concrete.visitMethod(Opcodes.ACC_PUBLIC, query, "(Ljava/lang/String;)Z", null, null);
                        method.visitCode(); method.visitVarInsn(Opcodes.ALOAD, 1);
                        method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/naocraftlab/skins/buildlogic/probe/CpmOptionalEnvironmentProbe", "isLoaded", "(Ljava/lang/String;)Z", false);
                        method.visitInsn(Opcodes.IRETURN); method.visitMaxs(0, 0); method.visitEnd();
                    }
                    concrete.visitEnd(); Files.write(Path.of(args[1]).resolve(implementation + ".class"), concrete.toByteArray());
                }
            }
            return;
        }
        if (ClassLoader.getSystemResource("com/tom/cpm/shared/MinecraftClientAccess.class") != null)
            throw new AssertionError("CPM must be physically absent");
        var environment = environment();
        if (environment.modelFileConversion().isPresent() || environment.snapshot().cpmPresent())
            throw new AssertionError("Absent CPM exposes conversion");
        if (SkinExtensionEnvironmentSource.unknown().modelFileConversion().isPresent())
            throw new AssertionError("Common/server-safe default exposes conversion");
        java.net.URL[] classpath = java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(entry -> { try { return Path.of(entry).toUri().toURL(); } catch (Exception failure) { throw new IllegalArgumentException(failure); } })
                .toArray(java.net.URL[]::new);
        try (var server = new java.net.URLClassLoader(classpath, ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.tom.") || name.startsWith("net.minecraft.") || name.startsWith("com.naocraftlab.skins.client."))
                    throw new ClassNotFoundException("Forbidden server dependency");
                return super.loadClass(name, resolve);
            }
        }) {
            for (String name : List.of("com.naocraftlab.skins.server.ConnectionSnapshot", "com.naocraftlab.skins.server.AppearanceRefreshSignalProtocol",
                    "com.naocraftlab.skins.server.runtime.ConnectionGenerationRegistry", "com.naocraftlab.skins.server.runtime.ServerAppearanceRefreshService")) {
                var type = Class.forName(name, true, server); type.getDeclaredMethods(); type.getDeclaredConstructors();
            }
            for (String forbidden : List.of("com.tom.cpm.shared.MinecraftClientAccess", "com.naocraftlab.skins.client.CpmModelFileConversion")) {
                try { server.loadClass(forbidden); throw new AssertionError("Server filter did not reject client dependency"); }
                catch (ClassNotFoundException expected) { }
            }
            var registry = server.loadClass("com.naocraftlab.skins.server.runtime.ConnectionGenerationRegistry");
            ((AutoCloseable) registry.getConstructor().newInstance()).close();
        }
        System.out.println("PASS actual optional loader environment absent; isolated server common/runtime classloading with CPM/Minecraft/client denied");
    }
}

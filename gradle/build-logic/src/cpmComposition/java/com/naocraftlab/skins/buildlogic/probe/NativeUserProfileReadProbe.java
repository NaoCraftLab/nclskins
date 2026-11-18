package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class NativeUserProfileReadProbe {
    public static int identityReads;
    public static int profileReads;
    public static int tokenReads;

    public static void identityRead() { identityReads++; }
    public static void profileRead() { profileReads++; }
    public static void tokenRead() { tokenReads++; }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("instrument")) {
            instrument(Path.of(args[1]));
            return;
        }
        require(args[0].equals("session"), "Known native identity probe phase");
        session();
        System.out.println("NATIVE_USER_SESSION_PASS live-replacement=true token-boundary=true game-launched=false");
    }

    private static void instrument(Path root) throws Exception {
        String owner = "net/minecraft/client/User";
        byte[] original;
        try (InputStream stream = NativeUserProfileReadProbe.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            require(stream != null, "Original selected User class is available");
            original = stream.readAllBytes();
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int[] matches = new int[3];
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                String hook;
                if (name.equals("getProfileId") && descriptor.equals("()Ljava/util/UUID;")) {
                    hook = "identityRead"; matches[0]++;
                } else if (name.equals("getGameProfile") && descriptor.equals("()Lcom/mojang/authlib/GameProfile;")) {
                    hook = "profileRead"; matches[1]++;
                } else if (name.equals("getAccessToken") && descriptor.equals("()Ljava/lang/String;")) {
                    hook = "tokenRead"; matches[2]++;
                } else return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "com/naocraftlab/skins/buildlogic/probe/NativeUserProfileReadProbe", hook, "()V", false);
                    }
                };
            }
        }, 0);
        require(matches[0] == 1 && matches[1] == 1 && matches[2] == 1, "Exact native User observation bindings");
        Path target = root.resolve(owner + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, writer.toByteArray());
        System.out.println("NATIVE_USER_OBSERVATION_SETUP_PASS original-code-preserved=true");
    }

    private static Object user(String raw, String token) throws Exception {
        Class<?> type = Class.forName("net.minecraft.client.User");
        Class<?> accountType = Class.forName("net.minecraft.client.User$Type");
        Constructor<?> constructor = type.getConstructor(String.class, String.class, String.class,
                Optional.class, Optional.class, accountType);
        return constructor.newInstance("Fixture", raw, token, Optional.empty(), Optional.empty(), accountType.getEnumConstants()[0]);
    }

    private static void current(Object value) throws Exception {
        Class.forName("net.minecraft.client.Minecraft").getField("current").set(null, value);
    }

    private static void reset() { identityReads = 0; profileReads = 0; tokenReads = 0; }

    private static void session() throws Exception {
        Class<?> type = Class.forName("net.minecraft.client.User");
        GameSessionTokenSource source = (GameSessionTokenSource) Class.forName(
                "com.naocraftlab.skins.compat.client.MinecraftGameSessionTokenSource").getConstructor().newInstance();
        for (String raw : List.of("00000000000000000000000000000001", "ffffffffffffffffffffffffffffffff",
                "ABCDEF0123456789ABCDEF0123456789", "00000000-0000-0000-0000-000000000001")) {
            Object value = user(raw, "synthetic-unused-placeholder");
            UUID expected = (UUID) type.getMethod("getProfileId").invoke(value);
            require(expected != null, "Canonical native fixture has an identity");
            current(value); reset();
            var actual = source.currentSession();
            require(actual.profileId().equals(expected) && actual.profileName().equals("Fixture"), "Exact native session identity");
            require(tokenReads == 0 && profileReads == 0, "Identity lookup does not inspect token or allocate GameProfile");
            require(identityReads == 0, "Canonical session lookup must not call legacy UUID conversion");
        }
        UUID replacement = new UUID(0, 2);
        current(user(replacement.toString().replace("-", ""), "synthetic-current-placeholder")); reset();
        require(source.currentSession().profileId().equals(replacement), "Live User replacement is observed without a tick");
        require(tokenReads == 0, "Replacement identity lookup does not inspect token");
        source.withSession((identity, token) -> {
            require(identity.profileId().equals(replacement) && !token.isBlank(), "Session callback uses one current User");
            return null;
        });
        require(tokenReads == 1, "Token read occurs only at the explicit session callback boundary");
        current(null);
        try { source.currentSession(); throw new AssertionError("Missing User must fail closed"); }
        catch (NullPointerException unavailable) {}
    }
}

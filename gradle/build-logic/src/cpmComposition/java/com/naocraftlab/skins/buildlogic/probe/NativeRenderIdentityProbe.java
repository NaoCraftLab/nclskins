package com.naocraftlab.skins.buildlogic.probe;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class NativeRenderIdentityProbe extends ClassLoader {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        byte[] source;
        try (var input = NativeRenderIdentityProbe.class.getClassLoader().getResourceAsStream(
                "com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/mixin/PlayerInfoProviderMixin.class")) {
            require(input != null, "Compiled legacy PlayerInfo consumer is present"); source = input.readAllBytes();
        }
        ClassNode original = new ClassNode(); new ClassReader(source).accept(original, 0);
        MethodNode method = original.methods.stream().filter(m -> m.name.equals("nclskins$local") && m.desc.equals("()Z")).findFirst().orElseThrow();
        int singleton = 0, user = 0, profile = 0, cast = 0, receiver = 0;
        for (var instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("net/minecraft/client/Minecraft") && call.name.equals("getInstance")) {
                    method.instructions.set(call, new VarInsnNode(Opcodes.ALOAD, 0)); singleton++;
                } else if (call.owner.equals("net/minecraft/client/Minecraft") && call.name.equals("getUser")) {
                    method.instructions.remove(call); user++;
                } else if (call.owner.equals("net/minecraft/client/multiplayer/PlayerInfo") && call.name.equals("getProfile")) {
                    method.instructions.remove(call); profile++;
                }
            } else if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.CHECKCAST
                    && type.desc.equals("net/minecraft/client/multiplayer/PlayerInfo")) {
                method.instructions.remove(type); cast++;
            } else if (instruction instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ALOAD && variable.var == 0) {
                variable.var = 1; receiver++;
            }
        }
        require(singleton == 1 && user == 1 && profile == 1 && cast == 1 && receiver == 1, "Exact owned entry-terminal substitutions only");
        method.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        method.name = "local";
        method.desc = "(Lnet/minecraft/client/User;Lcom/mojang/authlib/GameProfile;)Z";
        method.visibleAnnotations = null; method.invisibleAnnotations = null;
        method.localVariables = null; method.maxLocals = 2;
        ClassNode slice = new ClassNode(); slice.version = Opcodes.V17; slice.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;
        slice.name = "com/naocraftlab/skins/buildlogic/probe/LocalIdentitySlice"; slice.superName = "java/lang/Object"; slice.methods.add(method);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); slice.accept(writer);
        byte[] code = writer.toByteArray();
        Class<?> subject = new NativeRenderIdentityProbe().defineClass(null, code, 0, code.length);
        Class<?> userType = Class.forName("net.minecraft.client.User"); Class<?> account = Class.forName("net.minecraft.client.User$Type");
        var constructor = userType.getConstructor(String.class,String.class,String.class,Optional.class,Optional.class,account);
        Object first = constructor.newInstance("Fixture","00000000000000000000000000000001","synthetic-unused-placeholder",Optional.empty(),Optional.empty(),account.getEnumConstants()[0]);
        Object second = constructor.newInstance("Fixture","00000000000000000000000000000002","synthetic-unused-placeholder",Optional.empty(),Optional.empty(),account.getEnumConstants()[0]);
        Method predicate = subject.getMethod("local",userType,GameProfile.class); GameProfile actor = new GameProfile(new UUID(0,1),"Fixture");
        NativeUserProfileReadProbe.identityReads = 0; NativeUserProfileReadProbe.profileReads = 0; NativeUserProfileReadProbe.tokenReads = 0;
        for (int i = 0; i < 100; i++) {
            require(Boolean.TRUE.equals(predicate.invoke(null,first,actor)), "Current local classification preserved");
            require(Boolean.FALSE.equals(predicate.invoke(null,second,actor)), "Fresh replacement/foreign classification preserved");
        }
        require(NativeUserProfileReadProbe.tokenReads == 0 && NativeUserProfileReadProbe.profileReads == 0,"No token/full-profile lookup in predicate");
        System.out.println("NATIVE_RENDER_IDENTITY_COUNTS calls=200 nativeUuidConversions=" + NativeUserProfileReadProbe.identityReads + " tokenReads=0 profileReads=0 semanticControls=PASS");
        require(NativeUserProfileReadProbe.identityReads == 0, "Render identity predicate must avoid repeated legacy UUID conversion");
        System.out.println("NATIVE_RENDER_IDENTITY_PASS calls=200 localForeign=true noLegacyConversions=true tokenReads=0");
    }
}

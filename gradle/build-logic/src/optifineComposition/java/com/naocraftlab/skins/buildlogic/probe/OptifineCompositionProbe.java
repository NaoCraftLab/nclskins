package com.naocraftlab.skins.buildlogic.probe;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

public final class OptifineCompositionProbe {
    private OptifineCompositionProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("present") || args[0].equals("absent"))) {
            throw new IllegalArgumentException("Expected present or absent");
        }
        MixinBootstrap.init();
        MixinEnvironment environment = MixinEnvironment.getDefaultEnvironment();
        environment.setSide(MixinEnvironment.Side.CLIENT);
        environment.setObfuscationContext("searge");
        var constructor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer")
                .getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
        MixinExtrasBootstrap.init();
        Mixins.addConfiguration("nclskins.resourcelocation-playerinfo.mixins.json");
        Mixins.addConfiguration("nclskins.optifine-cape.mixins.json");
        IMixinTransformer transformer = (IMixinTransformer) environment.getActiveTransformer();
        String player = "net.minecraft.client.player.AbstractClientPlayer";
        ClassNode transformedPlayer = transform(transformer, environment, player);
        if (args[0].equals("present")) {
            verifyPresentPlayer(transformedPlayer);
            verifySuppression(transform(transformer, environment, "net.optifine.player.CapeUtils"));
        } else {
            verifyAbsentPlayer(transformedPlayer);
        }
        System.out.println("Mixin composition applied: " + args[0]);
    }

    private static ClassNode transform(IMixinTransformer transformer, MixinEnvironment environment, String name)
            throws Exception {
        String path = name.replace('.', '/') + ".class";
        try (InputStream input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing composition input " + path);
            byte[] bytes = input.readAllBytes();
            byte[] transformed = transformer.transformClass(environment, name, bytes);
            ClassNode node = new ClassNode();
            new ClassReader(transformed).accept(node, 0);
            System.out.println("Transformed " + node.name + " " + bytes.length + " -> "
                    + transformed.length);
            return node;
        }
    }

    private static void verifyPresentPlayer(ClassNode player) {
        MethodNode cape = method(player, "m_108561_", "()Lnet/minecraft/resources/ResourceLocation;");
        MethodNode elytra = method(player, "m_108563_", "()Lnet/minecraft/resources/ResourceLocation;");
        MethodNode eligibility = method(player, "hasElytraCape", "()Z");
        exact(cape, "nclskins$cape", "zza", 3);
        exact(cape, "nclskins$cape", "zzi", 3);
        exact(elytra, "nclskins$elytra", "zza", 1);
        exact(elytra, "nclskins$elytra", "zzi", 1);
        exact(eligibility, "nclskins$hasElytraCape", "zzi", 3);
        MethodNode preview = player.methods.stream().filter(method -> method.name.contains("nclskins$cape")
                && method.name.contains("zza")).findFirst().orElseThrow();
        MethodNode projection = player.methods.stream().filter(method -> method.name.contains("nclskins$cape")
                && method.name.contains("zzi")).findFirst().orElseThrow();
        exactOwnerCall(preview, "com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/PreviewScope", "cape", 1);
        exactOwnerCall(projection, "com/naocraftlab/skins/runtime/CapeProjection$Result", "capeLocation", 1);
        exactOwnerCall(projection, "com/naocraftlab/skins/compat/client/resourcelocation/playerinfo/PreviewScope", "cape", 1);
        System.out.println("Final player returns: cape preview/projection=3/3, elytra=1/1, eligibility=3; PreviewScope composes");
    }

    private static void verifyAbsentPlayer(ClassNode player) {
        MethodNode cape = method(player, "m_108561_", "()Lnet/minecraft/resources/ResourceLocation;");
        MethodNode elytra = method(player, "m_108563_", "()Lnet/minecraft/resources/ResourceLocation;");
        exact(cape, "nclskins$cape", "zza", 1);
        exact(elytra, "nclskins$elytra", "zza", 1);
        for (MethodNode method : player.methods) {
            if (method.name.contains("zzi") && (method.name.contains("nclskins$cape")
                    || method.name.contains("nclskins$elytra")
                    || method.name.contains("nclskins$hasElytraCape"))) {
                throw new IllegalStateException("Optional OptiFine mixin applied without OptiFine: " + method.name);
            }
        }
        System.out.println("Absent OptiFine: preview applied, optional mixins skipped");
    }

    private static void verifySuppression(ClassNode utils) {
        for (String action : Arrays.asList("DownloadCape", "ReloadCape")) {
            String entry = action.equals("DownloadCape") ? "downloadCape" : "reloadCape";
            MethodNode publicMethod = method(utils, entry,
                    "(Lnet/minecraft/client/player/AbstractClientPlayer;)V");
            exact(publicMethod, "nclskins$suppress" + action, "wrapMethod", 1);
            MethodNode suppressor = utils.methods.stream()
                    .filter(method -> method.name.contains("nclskins$suppress" + action))
                    .findFirst().orElseThrow();
            long calls = Arrays.stream(suppressor.instructions.toArray())
                    .filter(MethodInsnNode.class::isInstance).count();
            if (calls != 0) throw new IllegalStateException("Suppression invokes original: " + action);
        }
        System.out.println("CapeUtils public download/reload entrypoints route to empty NCL suppressors");
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream().filter(method -> Objects.equals(method.name, name)
                && Objects.equals(method.desc, descriptor)).findFirst().orElseThrow();
    }

    private static void exact(MethodNode method, String nameFragment, String prefixFragment, long expected) {
        long actual = Arrays.stream(method.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .filter(call -> call.name.contains(nameFragment) && call.name.contains(prefixFragment)).count();
        if (actual != expected) throw new IllegalStateException(method.name + " " + nameFragment
                + " expected " + expected + " calls, found " + actual);
    }

    private static void exactOwnerCall(MethodNode method, String owner, String name, long expected) {
        long actual = Arrays.stream(method.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.equals(owner) && call.name.equals(name)).count();
        if (actual != expected) throw new IllegalStateException(method.name + " " + owner + "."
                + name + " expected " + expected + " calls, found " + actual);
    }
}

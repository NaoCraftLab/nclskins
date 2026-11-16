package com.naocraftlab.skins.probe;

import java.nio.file.*;
import java.util.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class Weave {
    public static void main(String[] args) throws Exception {
        MixinBootstrap.init();
        var phase = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        phase.setAccessible(true);
        phase.invoke(null, MixinEnvironment.Phase.DEFAULT);
        var environment = MixinEnvironment.getDefaultEnvironment();
        environment.setSide(MixinEnvironment.Side.CLIENT);
        var constructor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
        com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
        Mixins.addConfiguration("lifetime-binding.json");
        if (args.length < 2 || !args[1].equals("vanilla")) Mixins.addConfiguration("ears-binding.json");
        var transformer = (IMixinTransformer) environment.getActiveTransformer();
        for (String owner : List.of("com.mojang.blaze3d.platform.NativeImage", "net.minecraft.client.renderer.texture.SkinTextureDownloader", "net.minecraft.client.renderer.texture.TextureManager", "net.minecraft.client.renderer.texture.DynamicTexture")) {
            String entry = owner.replace('.', '/') + ".class";
            byte[] before;
            try (var in = ClassLoader.getSystemResourceAsStream(entry)) { before = in.readAllBytes(); }
            byte[] after = transformer.transformClass(environment, owner, before);
            if (Arrays.equals(before, after)) throw new AssertionError("Missing actual Mixin transformation " + owner);
            Path output = Path.of(args[0]).resolve(entry);
            Files.createDirectories(output.getParent());
            Files.write(output, after);
            ClassNode node = new ClassNode(); new ClassReader(after).accept(node, 0);
            if (owner.endsWith("SkinTextureDownloader") && node.methods.stream().noneMatch(m -> m.name.equals("nclskins$textureRegistration"))) throw new AssertionError("Missing ownership bridge");
            if (owner.endsWith("TextureManager") && node.methods.stream().noneMatch(m -> m.name.equals("nclskins$isRegistered"))) throw new AssertionError("Missing exact identity lookup");
            System.out.println("NORMAL_WEAVE_PASS " + owner + " methods=" + node.methods.size());
        }
    }
}

package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;

public final class WeaveImmediateCapeProbe {
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
        MixinExtrasBootstrap.init();
        Mixins.addConfiguration("nclskins.cape-pass-immediate.mixins.json");
        String name = "net.minecraft.client.renderer.RenderType";
        String entry = name.replace('.', '/') + ".class";
        byte[] before;
        try (var input = ClassLoader.getSystemResourceAsStream(entry)) { before = input.readAllBytes(); }
        byte[] after = ((IMixinTransformer) environment.getActiveTransformer()).transformClass(environment, name, before);
        ClassNode node = new ClassNode();
        new ClassReader(after).accept(node, 0);
        if (node.methods.stream().noneMatch(method -> method.name.equals("nclskins$sortOnUpload")))
            throw new AssertionError("Production RenderTypeFlagsMixin absent");
        Path path = Path.of(args[0]).resolve(entry);
        Files.createDirectories(path.getParent());
        Files.write(path, after);
        System.out.println("NORMAL_MIXIN_IMMEDIATE_PASS productionHandler=nclskins$sortOnUpload");
    }
}
